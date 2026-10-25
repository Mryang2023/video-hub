package com.videohub.tv.ui.live

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.videohub.tv.App
import com.videohub.tv.R
import com.videohub.tv.databinding.FragmentLivePlayerBinding
import com.videohub.tv.ui.focus.tvFocus
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.net.URLEncoder

/**
 * 直播播放器:tvOS 直播语义——↑↓ 换台、←→ 换线路、OK 呼出信息、返回退出。
 *
 * 频道序列是"每频道一行"的类型化列表(group/name/urls 整体序列化进 arguments),
 * 不再做任何运行时拼装——之前的 zip 拼接在多源频道存在时会整体错位,
 * 出现"点 A 台播 B 台"。公益源常把流标错台标,←→ 手动换线路纠正。
 * 地址一律走后端 m3u8 代理——流由家里电脑拉,电视只连后端。
 */
class LivePlayerFragment : Fragment(R.layout.fragment_live_player) {

    private var _b: FragmentLivePlayerBinding? = null
    private val b get() = _b!!

    private val app: App get() = requireActivity().application as App

    @Serializable
    private data class Row(val group: String = "", val name: String = "", val urls: List<String> = emptyList())

    private val flat: List<Row> by lazy {
        val json = requireArguments().getString(ARG_FLAT) ?: "[]"
        runCatching { Json.decodeFromString(ListSerializer(Row.serializer()), json) }
            .getOrDefault(emptyList())
            .filter { it.urls.isNotEmpty() }
    }

    private var rootRef: View? = null
    private var index: Int = 0
    /** 当前频道正在用的线路下标 */
    private var urlIndex = 0
    private var player: ExoPlayer? = null
    private val osdHider = Runnable { if (_b != null) b.osd.isVisible = false }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _b = FragmentLivePlayerBinding.bind(view)
        index = requireArguments().getInt(ARG_INDEX, 0).coerceIn(0, (flat.size - 1).coerceAtLeast(0))

        b.btnRetry.tvFocus(1.05f)
        b.btnRetry.setOnClickListener {
            urlIndex = 0
            play(index)
        }
        // 错误面板的"重试"仅供鼠标/触屏;焦点必须留在根布局,否则按钮吃掉 ↑↓ 换台失效
        b.btnRetry.isFocusable = false

        val root = view as FrameLayout
        rootRef = root
        root.isFocusable = true
        root.isFocusableInTouchMode = true
        root.requestFocus()
        // 换台/换线路统一在根布局监听;子视图(错误按钮等)拿到焦点会被夺回,
        // 保证遥控键永远是直播语义
        root.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { step(-1); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { step(+1); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { cycleSource(-1); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { cycleSource(+1); true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (b.osd.isVisible) hideOsd() else showOsd()
                    true
                }
                else -> false
            }
        }

        if (flat.isEmpty()) {
            b.buffering.isVisible = false
            b.errorText.text = getString(R.string.live_bad_stream)
            b.errorPanel.isVisible = true
            b.btnRetry.isVisible = false
            return
        }
        play(index)
    }

    /** 换台 */
    private fun step(delta: Int) {
        if (flat.isEmpty()) return
        index = (index + delta).mod(flat.size)
        urlIndex = 0
        play(index)
    }

    /** 手动换线路:同一频道的多个源来回切,对付"标错台标"的源 */
    private fun cycleSource(delta: Int) {
        val ch = flat.getOrNull(index) ?: return
        if (ch.urls.size < 2) {
            showOsd()   // 单线路:只呼出信息提示没有别的线路
            return
        }
        urlIndex = (urlIndex + delta).mod(ch.urls.size)
        play(index)
    }

    private fun play(i: Int) {
        val ch = flat[i]
        val server = app.prefs.serverUrl
        b.errorPanel.isVisible = false
        b.buffering.isVisible = true
        updateOsd()
        if (server == null) return

        if (urlIndex >= ch.urls.size) {
            showError()
            return
        }
        val raw = ch.urls[urlIndex]
        if (!raw.startsWith("http")) {
            nextSourceOrError(ch)
            return
        }
        val uri = server + "api/proxy/m3u8?url=" + URLEncoder.encode(raw, "UTF-8")

        val p = player ?: ExoPlayer.Builder(requireContext()).build().also {
            player = it
            b.playerView.player = it
            it.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (_b == null) return
                    when (state) {
                        Player.STATE_READY -> {
                            b.buffering.isVisible = false
                            b.errorPanel.isVisible = false
                            showOsd()
                        }
                        Player.STATE_BUFFERING ->
                            b.buffering.isVisible = !b.errorPanel.isVisible
                        else -> Unit
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (_b == null) return
                    nextSourceOrError(flat.getOrNull(index) ?: return)
                }
            })
        }
        p.setMediaItem(
            MediaItem.Builder().setUri(android.net.Uri.parse(uri))
                .setMimeType(MimeTypes.APPLICATION_M3U8).build()
        )
        p.prepare()
        p.playWhenReady = true
    }

    /** 当前线路失败 → 自动切下一线路;全失败才报错(报错后 ↑↓ 换台/←→ 重选线路仍可用) */
    private fun nextSourceOrError(ch: Row) {
        urlIndex += 1
        if (urlIndex < ch.urls.size) {
            play(index)
        } else {
            showError()
        }
    }

    private fun showError() {
        b.buffering.isVisible = false
        b.errorText.text = getString(R.string.live_play_fail)
        b.errorPanel.isVisible = true
        rootRef?.requestFocus()   // 焦点回根,↑↓ 换台 / ←→ 换线路继续可用
    }

    private fun updateOsd() {
        val ch = flat.getOrNull(index) ?: return
        b.osdGroup.text = if (ch.urls.size > 1) {
            getString(R.string.live_line_of, ch.group, urlIndex + 1, ch.urls.size)
        } else ch.group
        b.osdName.text = ch.name
    }

    private fun showOsd() {
        b.osd.isVisible = true
        b.osd.removeCallbacks(osdHider)
        b.osd.postDelayed(osdHider, 4000)
    }

    private fun hideOsd() {
        b.osd.isVisible = false
        b.osd.removeCallbacks(osdHider)
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
        if (_b != null) b.playerView.player = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        rootRef = null
        _b = null
    }

    companion object {
        private const val ARG_FLAT = "flat"
        private const val ARG_INDEX = "index"

        /** 扁平化成"每频道一行",abs = 被点频道在扁平序列里的下标(与播放端严格一致) */
        fun newInstance(groups: List<com.videohub.tv.data.LiveGroup>, groupIndex: Int, channelIndex: Int): LivePlayerFragment {
            val rows = mutableListOf<Row>()
            var abs = 0
            groups.forEachIndexed { gi, g ->
                g.channels.forEachIndexed { ci, ch ->
                    if (gi == groupIndex && ci == channelIndex) abs = rows.size
                    rows.add(Row(group = g.name, name = ch.name, urls = ch.urls))
                }
            }
            val json = Json.encodeToString(ListSerializer(Row.serializer()), rows)
            return LivePlayerFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_FLAT, json)
                    putInt(ARG_INDEX, abs)
                }
            }
        }
    }
}
