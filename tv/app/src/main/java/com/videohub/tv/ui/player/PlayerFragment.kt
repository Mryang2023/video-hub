package com.videohub.tv.ui.player

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.videohub.tv.App
import com.videohub.tv.R
import com.videohub.tv.data.AppSettings
import com.videohub.tv.data.Favorite
import com.videohub.tv.data.HistoryRecord
import com.videohub.tv.databinding.FragmentPlayerBinding
import com.videohub.tv.ui.focus.tvFocus
import com.videohub.tv.util.toast
import java.net.URLEncoder
import kotlinx.coroutines.launch

/**
 * 播放器：Media3 ExoPlayer + 自绘 tvOS OSD。
 * 键位：OSD 隐藏时任意键唤醒；OK=播放/暂停；进度条聚焦后 ←→ = ±10s；返回=退出。
 */
class PlayerFragment : Fragment(R.layout.fragment_player) {

    private var _b: FragmentPlayerBinding? = null
    private val b get() = _b!!

    private val app: App get() = requireActivity().application as App
    private val repo get() = app.repo

    private var player: ExoPlayer? = null
    private var state: PlayState? = null
    private var settings = AppSettings()
    private var rateIndex = 0
    private var ready = false
    /** 本轮自动换源已尝试次数（播放成功后在 STATE_READY 清零） */
    private var autoSwitchCount = 0
    private var favored = false
    private var lastSaveAt = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val hideOsdRunnable = Runnable { hideOsd() }
    private val tickRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            maybeSaveHistory()
            handler.postDelayed(this, 1000)
        }
    }
    private val loadingTimeoutRunnable = Runnable {
        if (!ready) showError(getString(R.string.pp_timeout))
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> b.buffering.isVisible = true
                Player.STATE_READY -> {
                    ready = true
                    autoSwitchCount = 0
                    state?.groups?.getOrNull(state?.groupIndex ?: 0)?.name
                        ?.let { com.videohub.tv.util.SourceHealth.ok(requireContext(), it) }
                    b.buffering.isVisible = false
                    b.errorPanel.isVisible = false
                }
                Player.STATE_ENDED -> onEnded()
                Player.STATE_IDLE -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            b.btnPP.text = getString(if (isPlaying) R.string.pp_pause else R.string.pp_play)
        }

        override fun onPlayerError(error: PlaybackException) {
            showError(error.localizedMessage ?: "未知错误")
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _b = FragmentPlayerBinding.bind(view)

        state = PlayStore.current ?: run {
            requireContext().toast("播放队列为空")
            requireActivity().onBackPressedDispatcher.onBackPressed()
            return
        }

        setupPlayer()
        setupOsd()
        setupKeys()
        setupEpPanel()

        lifecycleScope.launch {
            settings = runCatching { repo.api?.settings() }.getOrNull() ?: AppSettings()
            rateIndex = RATES.indexOfFirst { it == settings.default_playback_rate }
                .takeIf { it >= 0 } ?: 0
            applyRate()
        }
        loadEpisode(state!!.groupIndex, state!!.episodeIndex, state!!.resumeMs)
        showOsd()
    }

    private fun setupPlayer() {
        val sourceFactory = DefaultMediaSourceFactory(requireContext())
            .setLoadErrorHandlingPolicy(TvLoadErrorPolicy())
        val p = ExoPlayer.Builder(requireContext())
            .setMediaSourceFactory(sourceFactory)
            .build()
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true,
        )
        p.addListener(listener)
        p.playWhenReady = true
        b.playerView.player = p
        player = p
    }

    private fun setupOsd() {
        listOf(
            b.btnPBack, b.btnPrev, b.btnPP, b.btnNext,
            b.btnEps, b.btnRate, b.btnFav, b.btnRetry, b.btnSwitchSrc, b.btnErrBack,
        ).forEach { it.tvFocus(1.06f) }

        // 显式焦点链：进度条 ↓ 固定进按钮排、按钮 ↑ 固定回进度条，
        // 左右用显式 ID 串联，绕开焦点搜索在横滚容器里的不确定行为
        b.pbarWrap.nextFocusDownId = R.id.btnPP
        // 进度条 ↑ 回顶栏返回键，顶栏 ↓ 回进度条
        b.pbarWrap.nextFocusUpId = R.id.btnPBack
        b.btnPBack.nextFocusDownId = R.id.pbarWrap
        listOf(
            b.btnPrev, b.btnPP, b.btnNext, b.btnEps, b.btnRate, b.btnFav,
        ).forEach { it.nextFocusUpId = R.id.pbarWrap }
        val row = listOf(b.btnPBack, b.btnPrev, b.btnPP, b.btnNext, b.btnEps, b.btnRate, b.btnFav)
        row.forEachIndexed { i, v ->
            v.nextFocusLeftId = row[maxOf(i - 1, 0)].id
            v.nextFocusRightId = row[minOf(i + 1, row.size - 1)].id
        }
        // 错误态按钮排同样串联
        val errRow = listOf(b.btnRetry, b.btnSwitchSrc, b.btnErrBack)
        errRow.forEachIndexed { i, v ->
            v.nextFocusLeftId = errRow[maxOf(i - 1, 0)].id
            v.nextFocusRightId = errRow[minOf(i + 1, errRow.size - 1)].id
        }

        val back = View.OnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        b.btnPBack.setOnClickListener(back)
        b.btnErrBack.setOnClickListener(back)
        b.btnEps.setOnClickListener { if (b.epPanel.isVisible) hideEpPanel() else showEpPanel() }
        b.btnPP.setOnClickListener { togglePlay() }
        b.btnRetry.setOnClickListener {
            autoSwitchCount = 0 // 手动重试后恢复自动换源额度
            val st = state ?: return@setOnClickListener
            loadEpisode(st.groupIndex, st.episodeIndex, 0)
        }
        b.btnSwitchSrc.setOnClickListener {
            autoSwitchCount = 0
            switchSource()
        }
        b.btnPrev.setOnClickListener {
            if (!stepEpisode(-1)) requireContext().toast(R.string.pp_first_ep)
        }
        b.btnNext.setOnClickListener {
            if (!stepEpisode(1)) requireContext().toast(R.string.pp_last_ep)
        }
        b.btnRate.setOnClickListener {
            rateIndex = (rateIndex + 1) % RATES.size
            applyRate()
        }
        b.btnFav.setOnClickListener { toggleFav() }
        b.pbarWrap.setOnClickListener { togglePlay() }

        // 进度条聚焦后 ←→ 快退快进
        b.pbarWrap.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { seek(-10_000); resetOsdTimer(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { seek(10_000); resetOsdTimer(); true }
                else -> false
            }
        }

        b.pHint.text = "←→ 快进快退 · OK 播放/暂停 · 返回退出"
    }

    /** OSD 隐藏时按键都先落在根视图：唤醒 OSD；OK 顺带切换播放 */
    private fun setupKeys() {
        b.root.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (!b.root.hasFocus()) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    showOsd()
                    togglePlay()
                    true
                }
                KeyEvent.KEYCODE_BACK -> {
                    showOsd()
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    showOsd()
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    // OSD 隐藏时左右键 = 直接快退/快进
                    seek(if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -10_000 else 10_000)
                    showOsd()
                    true
                }
                else -> false
            }
        }
    }

    // ── 播放控制 ─────────────────────────────────────

    private fun loadEpisode(groupIndex: Int, episodeIndex: Int, startMs: Long) {
        val st = state ?: return
        val group = st.groups.getOrNull(groupIndex) ?: return
        val ep = group.episodes.getOrNull(episodeIndex) ?: return
        st.groupIndex = groupIndex
        st.episodeIndex = episodeIndex
        st.resumeMs = 0

        ready = false
        b.pTitle.text = "${st.vodName} · ${ep.name}"
        b.pBadge.text = group.name
        b.errorPanel.isVisible = false
        b.buffering.isVisible = true
        handler.removeCallbacks(loadingTimeoutRunnable)
        handler.postDelayed(loadingTimeoutRunnable, 20_000)
        updateEpsButtons()

        val apiUrl = app.prefs.serverUrl
        lifecycleScope.launch {
            if (player == null || apiUrl == null) return@launch
            val resolved = runCatching { repo.api?.resolve(ep.url) }.getOrNull()
            val finalUrl = resolved?.url?.takeIf { it.isNotBlank() } ?: ep.url
            val isHls = resolved?.type == "hls" || finalUrl.lowercase().contains("m3u8")
            val mediaUri = if (isHls) {
                Uri.parse(
                    apiUrl + "api/proxy/m3u8?url=" + URLEncoder.encode(finalUrl, "UTF-8")
                ) to MimeTypes.APPLICATION_M3U8
            } else {
                Uri.parse(finalUrl) to MimeTypes.VIDEO_MP4
            }
            val item = MediaItem.Builder()
                .setUri(mediaUri.first)
                .setMimeType(mediaUri.second)
                .build()
            player?.run {
                setMediaItem(item, if (startMs > 0) startMs else C.TIME_UNSET)
                prepare()
                playWhenReady = true
                applyRate()
            }
        }
    }

    private fun applyRate() {
        player?.setPlaybackSpeed(RATES[rateIndex].toFloat())
        val r = RATES[rateIndex]
        b.btnRate.text = "倍速 " + (if (r % 1.0 == 0.0) r.toInt().toString() else r.toString()) + "x"
    }

    private fun togglePlay() {
        player?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    private fun stepEpisode(delta: Int): Boolean {
        val st = state ?: return false
        val next = st.episodeIndex + delta
        val eps = st.groups.getOrNull(st.groupIndex)?.episodes ?: return false
        return if (next in eps.indices) {
            loadEpisode(st.groupIndex, next, 0)
            true
        } else false
    }

    private fun switchSource() {
        val st = state ?: return
        if (st.groups.size < 2) {
            requireContext().toast(R.string.pp_no_more_src)
            return
        }
        val ng = (st.groupIndex + 1) % st.groups.size
        requireContext().toast("已切换到「${st.groups[ng].name}」")
        loadEpisode(ng, 0, 0)
    }

    private fun seek(deltaMs: Long) {
        val p = player ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val target = (p.currentPosition + deltaMs).coerceAtLeast(0)
            .let { t -> if (dur != null) t.coerceAtMost(dur - 500) else t }
        p.seekTo(target)
        updateProgress()
    }

    private fun onEnded() {
        maybeSaveHistory(force = true)
        val st = state ?: return
        if (settings.autoplay_next) {
            if (!stepEpisode(1)) {
                showOsd()
                requireContext().toast(R.string.pp_last_ep)
            }
        } else {
            showOsd()
        }
    }

    // ── 播放内选集面板 ────────────────────────────────
    // 不退出播放器直接挑集：底部面板 + 分组行 + 集数网格，焦点直达当前集

    private var panelGroup = 0
    private val epPanelAdapter = PanelEpAdapter { pos ->
        val st = state ?: return@PanelEpAdapter
        hideEpPanel()
        loadEpisode(panelGroup, pos, 0)
    }

    /** 面板打开时优先吃掉返回键（只关面板，不退出播放器） */
    private val epPanelBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            hideEpPanel()
        }
    }

    private fun setupEpPanel() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, epPanelBack)
        b.epGrid.layoutManager =
            androidx.recyclerview.widget.GridLayoutManager(requireContext(), 5)
        b.epGrid.addItemDecoration(
            com.videohub.tv.ui.focus.GridSpacing(
                (8 * resources.displayMetrics.density).toInt(), 5,
            )
        )
        b.epGrid.adapter = epPanelAdapter
    }

    private fun showEpPanel() {
        val st = state ?: return
        panelGroup = st.groupIndex
        b.epTitle.text = "选集 · ${st.vodName}"
        buildPanelTabs()
        refreshPanelGrid()

        b.epPanel.isVisible = true
        epPanelBack.isEnabled = true
        // 面板打开时暂停 OSD 自动隐藏，避免焦点被收走
        handler.removeCallbacks(hideOsdRunnable)
    }

    private fun hideEpPanel() {
        b.epPanel.isVisible = false
        epPanelBack.isEnabled = false
        showOsd() // 焦点回进度条，←→ 仍是快进
    }

    private fun buildPanelTabs() {
        val st = state ?: return
        b.epTabs.removeAllViews()
        st.groups.forEachIndexed { i, g ->
            val tab = layoutInflater.inflate(R.layout.item_gtab, b.epTabs, false) as TextView
            tab.text = g.name
            tab.nextFocusDownId = R.id.epGrid
            tab.setOnClickListener {
                if (panelGroup != i) {
                    panelGroup = i
                    buildPanelTabs()
                    refreshPanelGrid()
                }
            }
            b.epTabs.addView(tab)
        }
        // 当前组选中态
        for (i in 0 until b.epTabs.childCount) {
            b.epTabs.getChildAt(i).isSelected = i == panelGroup
        }
    }

    private fun refreshPanelGrid() {
        val st = state ?: return
        val eps = st.groups.getOrNull(panelGroup)?.episodes ?: return
        val cur = if (panelGroup == st.groupIndex) st.episodeIndex else -1
        epPanelAdapter.submitList(eps.mapIndexed { i, e -> PanelEp(e.name, i == cur) })
        // 滚动 + 焦点直达当前集（渲染有时序，延迟重试直到格子就绪）
        epPanelFocusTries = 0
        focusPanelCell(cur.coerceAtLeast(0))
    }

    private var epPanelFocusTries = 0
    private fun focusPanelCell(pos: Int) {
        if (_b == null) return
        // 先把目标集滚进视口——远端位置的 ViewHolder 要滚动布局后才存在
        b.epGrid.scrollToPosition(pos)
        b.epGrid.postDelayed({
            if (_b == null) return@postDelayed
            val cell = b.epGrid.findViewHolderForAdapterPosition(pos)?.itemView
            if (cell == null || !cell.requestFocus() || !cell.hasFocus()) {
                if (epPanelFocusTries < 15) {
                    epPanelFocusTries++
                    focusPanelCell(pos)
                }
            }
        }, 100)
    }

    /** 面板集数格子：当前集加粗+高亮，OK 直接播 */
    private inner class PanelEpAdapter(val onClick: (Int) -> Unit) :
        androidx.recyclerview.widget.ListAdapter<PanelEp, PanelEpAdapter.VH>(
            object : androidx.recyclerview.widget.DiffUtil.ItemCallback<PanelEp>() {
                override fun areItemsTheSame(oldItem: PanelEp, newItem: PanelEp) =
                    oldItem.name == newItem.name

                override fun areContentsTheSame(oldItem: PanelEp, newItem: PanelEp) =
                    oldItem == newItem
            },
        ) {

        inner class VH(val tv: TextView) : androidx.recyclerview.widget.RecyclerView.ViewHolder(tv)

        override fun onCreateViewHolder(
            parent: android.view.ViewGroup,
            viewType: Int,
        ): VH {
            val tv = layoutInflater.inflate(R.layout.item_ep, parent, false) as TextView
            val vh = VH(tv)
            tv.tvFocus(1.06f)
            tv.setOnClickListener {
                val pos = vh.bindingAdapterPosition
                if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) onClick(pos)
            }
            return vh
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = getItem(position)
            holder.tv.text = item.name
            holder.tv.typeface = if (item.current)
                android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            holder.tv.isActivated = item.current
        }
    }

    private fun updateEpsButtons() {
        val st = state ?: return
        val eps = st.groups.getOrNull(st.groupIndex)?.episodes ?: emptyList()
        b.btnPrev.alpha = if (st.episodeIndex > 0) 1f else 0.4f
        b.btnNext.alpha = if (st.episodeIndex < eps.size - 1) 1f else 0.4f
        // 按钮直接带出下一集名，自动连播前用户也能看到即将播什么
        val next = eps.getOrNull(st.episodeIndex + 1)
        b.btnNext.text = if (next != null) "下一集 · ${next.name}" else "下一集"
        // 换集后面板若开着，同步刷新高亮
        if (_b != null && b.epPanel.isVisible) {
            if (panelGroup == st.groupIndex) refreshPanelGrid()
            buildPanelTabs()
        }
    }

    // ── OSD ─────────────────────────────────────────

    private fun showOsd() {
        b.osd.isVisible = true
        // 选集面板开着时焦点归面板（当前集格子），OSD 不抢
        if (b.epPanel.isVisible) {
            resetOsdTimer()
            return
        }
        // 默认焦点给进度条：左右键立即就是 ±10s 快进
        if (b.osd.findFocus() == null || !b.osd.hasFocus()) b.pbarWrap.requestFocus()
        resetOsdTimer()
    }

    private fun resetOsdTimer() {
        handler.removeCallbacks(hideOsdRunnable)
        handler.postDelayed(hideOsdRunnable, 5_000)
    }

    private fun hideOsd() {
        // 选集面板打开时 OSD 保持（被面板盖住），不收走焦点
        if (b.epPanel.isVisible) {
            resetOsdTimer()
            return
        }
        b.osd.isVisible = false
        b.root.requestFocus()
    }

    // ── 进度与历史 ──────────────────────────────────

    private fun fmtTime(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = s % 3600 / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    private fun updateProgress() {
        val p = player ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
        val pos = p.currentPosition.coerceAtLeast(0)
        b.pCur.text = fmtTime(pos)
        b.pDur.text = fmtTime(dur)
        if (dur > 0 && b.pTrack.width > 0) {
            b.pFill.layoutParams.width = (b.pTrack.width * pos / dur).toInt()
            b.pFill.requestLayout()
        }
    }

    private fun maybeSaveHistory(force: Boolean = false) {
        val st = state ?: return
        val p = player ?: return
        if (!settings.record_history) return
        val pos = p.currentPosition
        if (pos <= 0) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastSaveAt < 8_000) return
        lastSaveAt = now
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
        val ep = st.groups.getOrNull(st.groupIndex)?.episodes?.getOrNull(st.episodeIndex)
        val rec = HistoryRecord(
            site_key = st.siteKey,
            site_name = st.siteName,
            vod_id = st.vodId,
            vod_name = st.vodName,
            vod_pic = st.vodPic,
            episode_index = st.episodeIndex,
            episode_name = ep?.name.orEmpty(),
            current_time = pos / 1000.0,
            duration = dur / 1000.0,
            play_url = ep?.url.orEmpty(),
            api_url = st.apiUrl,
        )
        val api = repo.api ?: return
        app.appScope.launch { runCatching { api.saveHistory(rec) } }
    }

    // ── 收藏（与详情页一致） ────────────────────────

    private fun toggleFav() {
        val st = state ?: return
        val api = repo.api ?: return
        lifecycleScope.launch {
            if (!favored && runCatching { api.checkFavorite(st.siteKey, st.vodId) }
                    .getOrNull()?.favorited == true) favored = true
            if (favored) {
                runCatching { api.removeFavorite(st.siteKey, st.vodId) }
                favored = false
                requireContext().toast(R.string.fav_removed)
            } else {
                runCatching {
                    api.addFavorite(
                        Favorite(
                            site_key = st.siteKey,
                            site_name = st.siteName,
                            vod_id = st.vodId,
                            vod_name = st.vodName,
                            vod_pic = st.vodPic,
                            api_url = st.apiUrl,
                        )
                    )
                }
                favored = true
                requireContext().toast(R.string.fav_added)
            }
            b.btnFav.text = getString(if (favored) R.string.fav_remove else R.string.fav_add)
        }
    }

    private fun showError(msg: String) {
        val st = state
        // 记健康度：当前源失败一次
        st?.groups?.getOrNull(st.groupIndex)?.name
            ?.let { com.videohub.tv.util.SourceHealth.fail(requireContext(), it) }
        // 自动换源兜底：源站抖动很常见，失败先自动切下一个源（同一集），
        // 把所有源都试过一遍才弹错误面板
        if (st != null && st.groups.size > 1 && autoSwitchCount < st.groups.size) {
            autoSwitchCount++
            val ng = (st.groupIndex + 1) % st.groups.size
            val epIdx = st.episodeIndex.coerceIn(0, st.groups[ng].episodes.size - 1)
            val keepMs = player?.currentPosition?.takeIf { it > 0 } ?: 0L
            requireContext().toast("本源播放失败，自动切换到「${st.groups[ng].name}」")
            loadEpisode(ng, epIdx, keepMs)
            return
        }
        b.buffering.isVisible = false
        b.errorText.text = getString(R.string.pp_play_fail, msg)
        b.errorPanel.isVisible = true
        showOsd()
    }

    override fun onPause() {
        super.onPause()
        maybeSaveHistory(force = true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        maybeSaveHistory(force = true)
        player?.run {
            removeListener(listener)
            release()
        }
        player = null
        _b = null
    }

    companion object {
        val RATES = listOf(1.0, 1.25, 1.5, 2.0, 0.75, 3.0)
    }

    /**
     * TVBox 资源站分片 502/超时是常态（上游抖动），对这类可重试错误做退避重试，
     * 其余错误走默认策略。手机端 hls.js 有恢复逻辑，这里对齐它。
     */
    private class TvLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(
            loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
        ): Long {
            val cause = loadErrorInfo.exception.cause ?: loadErrorInfo.exception
            return when {
                loadErrorInfo.errorCount >= 6 -> -1L
                cause is HttpDataSource.InvalidResponseCodeException ->
                    if (cause.responseCode >= 500 ||
                        cause.responseCode == 429 || cause.responseCode == 403
                    ) 1500L * loadErrorInfo.errorCount else -1L
                cause is HttpDataSource.HttpDataSourceException ->
                    1000L * loadErrorInfo.errorCount
                else -> super.getRetryDelayMsFor(loadErrorInfo)
            }
        }
    }
}

/** 选集面板的格子模型 */
private data class PanelEp(val name: String, val current: Boolean)
