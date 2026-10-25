package com.videohub.tv.ui.detail

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.videohub.tv.App
import com.videohub.tv.MainActivity
import com.videohub.tv.R
import com.videohub.tv.data.Favorite
import com.videohub.tv.data.VodDetail
import com.videohub.tv.databinding.FragmentDetailBinding
import com.videohub.tv.ui.focus.tvFocus
import com.videohub.tv.ui.player.EpData
import com.videohub.tv.ui.player.PlayGroupData
import com.videohub.tv.ui.player.PlayState
import com.videohub.tv.ui.player.PlayStore
import com.videohub.tv.util.toast
import kotlinx.coroutines.launch

/** 详情页入参：站点 API + 影片 id（收藏/历史/搜索/分类四条路径统一） */
data class DetailArgs(
    val apiUrl: String,
    val vodId: String,
    val siteKey: String = "",
    val siteName: String = "",
    val title: String = "",
    val pic: String = "",
)

class DetailFragment : Fragment(R.layout.fragment_detail) {

    private var _b: FragmentDetailBinding? = null
    private val b get() = _b!!

    private val app: App get() = requireActivity().application as App
    private val repo get() = app.repo

    private lateinit var args: DetailArgs

    private data class Episode(val name: String, val url: String)
    private data class PlayGroup(val name: String, val episodes: List<Episode>)

    private data class EpItem(val name: String, val url: String, val watched: Boolean, val current: Boolean)

    private var groups: List<PlayGroup> = emptyList()
    private var resumeEp = -1 // 0-based
    private var resumeMs = 0L
    private var currentGroup = 0
    private var curPic = ""
    private var favored = false
    private var epAdapter: EpAdapter? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _b = FragmentDetailBinding.bind(view)
        args = DetailArgs(
            apiUrl = requireArguments().getString(K_API).orEmpty(),
            vodId = requireArguments().getString(K_ID).orEmpty(),
            siteKey = requireArguments().getString(K_SITE).orEmpty(),
            siteName = requireArguments().getString(K_SITE_NAME).orEmpty(),
            title = requireArguments().getString(K_TITLE).orEmpty(),
            pic = requireArguments().getString(K_PIC).orEmpty(),
        )

        b.btnBack.tvFocus(1.05f)
        b.btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        b.btnResume.tvFocus(1.05f)
        b.btnPlay.tvFocus(1.05f)
        b.btnFav.tvFocus(1.05f)

        b.eGrid.layoutManager = GridLayoutManager(requireContext(), 5)
        epAdapter = EpAdapter(
            grid = b.eGrid,
            backView = { if (_b != null) b.btnFav else b.root },
            upView = { _b?.gTabs?.getChildAt(0) },
        ) { pos -> startPlayer(currentGroup, pos, 0) }
        b.eGrid.adapter = epAdapter

        load()
    }

    private fun load() {
        val api = repo.api ?: return
        b.loading.isVisible = true
        lifecycleScope.launch {
            val detail = runCatching { api.detail(args.apiUrl, args.vodId) }
                .getOrNull()?.list?.firstOrNull()
            val history = runCatching { api.history() }.getOrNull()
                ?.firstOrNull { it.site_key == args.siteKey && it.vod_id == args.vodId }
            favored = runCatching { api.checkFavorite(args.siteKey, args.vodId) }
                .getOrNull()?.favorited == true
            b.loading.isVisible = false

            if (detail == null) {
                requireContext().toast(R.string.detail_load_fail)
                requireActivity().onBackPressedDispatcher.onBackPressed()
                return@launch
            }
            resumeEp = history?.episode_index ?: -1
            resumeMs = ((history?.current_time ?: 0.0) * 1000).toLong()
            groups = parseGroups(detail.vod_play_from, detail.vod_play_url)

            bindHeader(detail)
            buildGroupTabs()
            showGroup(defaultGroupIndex())

            // 焦点显式落在「继续播放」（无历史则「播放第 1 集」），避免加载后焦点丢失
            (if (resumeEp >= 0) b.btnResume else b.btnPlay).requestFocus()
            // 焦点导航：三按钮横排（继续播放→播放→收藏），右键顺排自然走；
            // 收藏再往右进宫格，落在与收藏同一水平位置最近的那一集；
            // ↑ 直达分类行（第一季/第二季…）
            b.btnResume.nextFocusRightId = View.NO_ID
            b.btnPlay.nextFocusRightId = View.NO_ID
            b.btnFav.nextFocusRightId = View.NO_ID
            b.btnFav.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    focusNearestEpCell()
                    true
                } else {
                    false
                }
            }
            b.btnResume.setOnKeyListener { _, keyCode, event ->
                event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT
            }
            b.btnResume.nextFocusUpId = R.id.gTabs
            b.btnPlay.nextFocusUpId = R.id.gTabs
            b.btnFav.nextFocusUpId = R.id.gTabs

            b.btnResume.setOnClickListener {
                val eps = groups.getOrNull(currentGroup)?.episodes?.size ?: 1
                startPlayer(currentGroup, resumeEp.coerceIn(0, eps - 1), resumeMs)
            }
            b.btnPlay.setOnClickListener { startPlayer(currentGroup, 0, 0) }
            b.btnFav.setOnClickListener { toggleFav() }
        }
    }

    /** 从收藏按钮往右进宫格：聚焦与收藏按钮垂直位置最近的一集 */
    private fun focusNearestEpCell() {
        val lm = b.eGrid.layoutManager as? GridLayoutManager ?: return
        val loc = IntArray(2)
        b.btnFav.getLocationInWindow(loc)
        val favY = loc[1] + b.btnFav.height / 2
        var best: View? = null
        var bestDiff = Int.MAX_VALUE
        for (i in 0 until lm.childCount) {
            val child = lm.getChildAt(i) ?: continue
            child.getLocationInWindow(loc)
            val diff = Math.abs(loc[1] + child.height / 2 - favY)
            if (diff < bestDiff) {
                bestDiff = diff
                best = child
            }
        }
        best?.requestFocus()
    }

    private fun bindHeader(d: VodDetail) {
        b.dTitle.text = d.vod_name.ifBlank { args.title }
        val totalEps = groups.sumOf { it.episodes.size }
        b.dMeta.text = listOfNotNull(
            d.vod_year?.takeIf { it.isNotBlank() },
            d.type_name?.takeIf { it.isNotBlank() },
            d.vod_remarks?.takeIf { it.isNotBlank() },
            if (totalEps > 0) "$totalEps 集" else null,
        ).joinToString(" · ")
        b.dSyn.text = d.vod_content.replace(Regex("<[^>]*>"), "").trim()

        if (resumeEp >= 0) {
            b.btnResume.isVisible = true
            b.btnResume.text = if (totalEps > 1) getString(R.string.play_resume, resumeEp + 1)
            else getString(R.string.play_resume_single)
        }
        updateFavBtn()

        val pic = d.vod_pic.takeIf { it.isNotBlank() } ?: args.pic
        curPic = pic
        repo.proxyImage(pic)?.let {
            Glide.with(this).load(it)
                .transform(
                    com.bumptech.glide.load.resource.bitmap.CenterCrop(),
                    com.videohub.tv.util.SharpenTransformation(0.45f),
                )
                .into(b.backdrop)
        }
    }

    /** MacCMS 播放串：组$$$组，组内 集$地址#集$地址 */
    private fun parseGroups(from: String, playUrl: String): List<PlayGroup> {
        if (playUrl.isBlank()) return emptyList()
        val names = from.split("$$$")
        return playUrl.split("$$$").mapIndexedNotNull { gi, group ->
            val eps = group.split("#").mapNotNull { seg ->
                val i = seg.indexOf('$')
                if (i <= 0) null
                else Episode(seg.substring(0, i).trim(), seg.substring(i + 1).trim())
                    .takeIf { it.url.isNotBlank() && it.name.isNotBlank() }
            }
            if (eps.isEmpty()) null
            else PlayGroup(
                names.getOrNull(gi)?.takeIf { it.isNotBlank() } ?: "线路${gi + 1}",
                eps,
            )
        }
    }

    private fun defaultGroupIndex(): Int {
        if (resumeEp < 0) return 0
        groups.forEachIndexed { i, g ->
            if (resumeEp < g.episodes.size) {
                // 上次用的源近期连续失败较多、且存在健康源时顺延，
                // 避免每次进详情都撞上同一个坏源
                val ctx = requireContext()
                if (com.videohub.tv.util.SourceHealth.score(ctx, g.name) >=
                    com.videohub.tv.util.SourceHealth.BAD_THRESHOLD
                ) {
                    val healthy = groups.indexOfFirst {
                        com.videohub.tv.util.SourceHealth.score(ctx, it.name) <
                            com.videohub.tv.util.SourceHealth.BAD_THRESHOLD
                    }
                    if (healthy >= 0) return healthy
                }
                return i
            }
        }
        return 0
    }

    private fun buildGroupTabs() {
        b.gTabs.removeAllViews()
        groups.forEachIndexed { i, g ->
            val tab = layoutInflater.inflate(R.layout.item_gtab, b.gTabs, false) as TextView
            tab.text = g.name
            // 分类行 ↓ 直达集数宫格（宫格容器不可聚焦，会落到具体集数格）
            tab.nextFocusDownId = R.id.eGrid
            tab.setOnClickListener { showGroup(i) }
            b.gTabs.addView(tab)
        }
    }

    private fun showGroup(index: Int) {
        currentGroup = index
        val group = groups.getOrNull(index) ?: return
        for (i in 0 until b.gTabs.childCount) {
            b.gTabs.getChildAt(i).isSelected = i == index
        }
        val eps = group.episodes.mapIndexed { i, e ->
            EpItem(
                name = e.name,
                url = e.url,
                watched = resumeEp >= 0 && i < resumeEp,
                current = resumeEp >= 0 && i == resumeEp,
            )
        }
        epAdapter?.submitList(eps)
        // 定位到「继续播放」那一集所在的行，长剧不用一路翻
        val scrollTo = if (resumeEp in eps.indices) (resumeEp / 5) * 5 else 0
        b.eGrid.scrollToPosition(scrollTo.coerceAtMost(maxOf(0, eps.size - 1)))
    }

    private fun updateFavBtn() {
        b.btnFav.text = getString(if (favored) R.string.fav_remove else R.string.fav_add)
    }

    private fun toggleFav() {
        val api = repo.api ?: return
        lifecycleScope.launch {
            if (favored) {
                runCatching { api.removeFavorite(args.siteKey, args.vodId) }
                favored = false
                requireContext().toast(R.string.fav_removed)
            } else {
                runCatching {
                    api.addFavorite(
                        Favorite(
                            site_key = args.siteKey,
                            site_name = args.siteName,
                            vod_id = args.vodId,
                            vod_name = b.dTitle.text.toString(),
                            vod_pic = args.pic,
                            api_url = args.apiUrl,
                        )
                    )
                }
                favored = true
                requireContext().toast(R.string.fav_added)
            }
            updateFavBtn()
        }
    }

    /** 填好播放队列后拉起播放器 */
    private fun startPlayer(groupIndex: Int, episodeIndex: Int, startMs: Long) {
        PlayStore.current = PlayState(
            siteKey = args.siteKey,
            siteName = args.siteName,
            vodId = args.vodId,
            vodName = b.dTitle.text.toString(),
            vodPic = curPic,
            apiUrl = args.apiUrl,
            groups = groups.map { g ->
                PlayGroupData(g.name, g.episodes.map { EpData(it.name, it.url) })
            },
            groupIndex = groupIndex,
            episodeIndex = episodeIndex,
            resumeMs = startMs,
        )
        (requireActivity() as? MainActivity)?.pushPlayer()
    }

    /** 集数宫格适配器：格子点击换集 + 宫格边界（首列/行尾/首行）显式接管 */
    private class EpAdapter(
        val grid: RecyclerView,
        val backView: () -> View,   // 第一列按左回到这里（收藏按钮）
        val upView: () -> View?,    // 首行按上到这里（分类行第一个 tab，构建后才有）
        val onClick: (Int) -> Unit,
    ) : ListAdapter<EpItem, EpAdapter.VH>(Diff) {

        class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_ep, parent, false) as TextView
            val vh = VH(tv)
            tv.tvFocus(1.06f)
            tv.setOnClickListener {
                val pos = vh.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(pos)
            }
            tv.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val pos = vh.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnKeyListener false
                val lm = grid.layoutManager as? GridLayoutManager
                    ?: return@setOnKeyListener false
                val span = lm.spanCount
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        // 行尾：到下一行第一格；已是最后一格则原地停住
                        if (pos % span == span - 1) {
                            focusEpAt(pos + 1)
                            true
                        } else false
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        // 第一列：回到「收藏」（来时的路）
                        if (pos % span == 0) {
                            backView().requestFocus()
                            true
                        } else false
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        // 首行：上到分类行
                        if (pos < span) {
                            upView()?.requestFocus()
                            true
                        } else false
                    }
                    else -> false
                }
            }
            return vh
        }

        private fun focusEpAt(pos: Int) {
            val target = pos.coerceIn(0, (grid.adapter?.itemCount ?: 1) - 1)
            grid.scrollToPosition(target)
            grid.postDelayed({
                if (target >= (grid.adapter?.itemCount ?: 0)) return@postDelayed
                grid.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
            }, 60)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = getItem(position)
            holder.tv.text = item.name
            holder.tv.typeface = if (item.current) Typeface_BOLD else Typeface_NORMAL
            holder.tv.alpha = if (item.watched) 0.45f else 1f
            holder.tv.isActivated = item.current
        }

        companion object {
            private val Typeface_BOLD = android.graphics.Typeface.DEFAULT_BOLD
            private val Typeface_NORMAL = android.graphics.Typeface.DEFAULT
            private val Diff = object : DiffUtil.ItemCallback<EpItem>() {
                override fun areItemsTheSame(oldItem: EpItem, newItem: EpItem) =
                    oldItem.name == newItem.name && oldItem.url == newItem.url

                override fun areContentsTheSame(oldItem: EpItem, newItem: EpItem) = oldItem == newItem
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    companion object {
        private const val K_API = "api"
        private const val K_ID = "id"
        private const val K_SITE = "site"
        private const val K_SITE_NAME = "siteName"
        private const val K_TITLE = "title"
        private const val K_PIC = "pic"

        fun newInstance(args: DetailArgs): DetailFragment = DetailFragment().apply {
            arguments = bundleOf(
                K_API to args.apiUrl,
                K_ID to args.vodId,
                K_SITE to args.siteKey,
                K_SITE_NAME to args.siteName,
                K_TITLE to args.title,
                K_PIC to args.pic,
            )
        }
    }
}
