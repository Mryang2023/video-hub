package com.videohub.tv.ui.home

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.videohub.tv.App
import com.videohub.tv.MainActivity
import com.videohub.tv.R
import com.videohub.tv.data.Favorite
import com.videohub.tv.data.HistoryRecord
import com.videohub.tv.data.Repo
import com.videohub.tv.data.VodItem
import com.videohub.tv.databinding.FragmentHomeBinding
import com.videohub.tv.ui.detail.DetailArgs
import com.videohub.tv.util.BlurTransformation
import com.videohub.tv.util.toast
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** 首页：tvOS 风格 —— 全幅 Hero 轮播 + 环境光背景 + 货架（标签栏在 MainActivity） */
class HomeFragment : Fragment(R.layout.fragment_home) {

    private var _b: FragmentHomeBinding? = null
    private val b get() = _b!!

    private val app: App get() = requireActivity().application as App
    private val adapter = HomeAdapter()

    /** Hero 轮播池与当前序号；空 = 无 Hero 行 */
    private var heroes: List<HomeItem.Hero> = emptyList()
    private var heroIndex = 0
    /** 除 Hero 外的货架行，轮播时原样拼回 */
    private var shelfItems: List<HomeItem> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private val rotateTick = object : Runnable {
        override fun run() {
            if (!isAdded || _b == null || heroes.size < 2) return
            // 焦点在 Hero 上时不轮播（点击目标不能从指缝里溜走）
            val focused = b.homeList.findViewHolderForAdapterPosition(0)?.itemView?.hasFocus()
            if (focused != true) {
                heroIndex = (heroIndex + 1) % heroes.size
                adapter.submitList(listOf(heroes[heroIndex]) + shelfItems)
            }
            handler.postDelayed(this, HERO_ROTATE_MS)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _b = FragmentHomeBinding.bind(view)
        b.homeList.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false)
        b.homeList.adapter = adapter
        // Hero 换图 → 环境光背景跟着换（模糊 + 交叉淡入）
        adapter.onHeroShown = { hero ->
            if (_b != null && hero.imageUrl != null) {
                Glide.with(this)
                    .load(hero.imageUrl)
                    .apply(RequestOptions().transform(CenterCrop(), BlurTransformation(10)))
                    .transition(DrawableTransitionOptions.withCrossFade(700))
                    .into(b.ambientImg)
            }
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(rotateTick)
        handler.postDelayed(rotateTick, HERO_ROTATE_MS)
    }

    override fun onPause() {
        handler.removeCallbacks(rotateTick)
        super.onPause()
    }

    override fun onDestroyView() {
        handler.removeCallbacks(rotateTick)
        super.onDestroyView()
        _b = null
    }

    /** 首页数据快照：切标签回来时 30 秒内直接复用，秒开不重新请求 */
    private class Snapshot(
        val history: List<HistoryRecord> = emptyList(),
        val favs: List<Favorite> = emptyList(),
        val site: Triple<String, String, String>? = null, // api / key / name
        val hot: List<VodItem> = emptyList(),
        val cats: List<Pair<String, List<VodItem>>> = emptyList(),
    )

    private fun load() {
        val repo = app.repo
        val api = repo.api ?: return
        val cached = cache
        if (cached != null && SystemClock.elapsedRealtime() - cacheAt < CACHE_MS) {
            render(cached)
            return
        }
        lifecycleScope.launch {
            // 第一批：历史 + 收藏（并发拉取，少等一个网络往返）
            val (historyList, favs) = coroutineScope {
                val h = async { runCatching { api.history() }.getOrDefault(emptyList()) }
                val f = async { runCatching { api.favorites() }.getOrDefault(emptyList()) }
                h.await() to f.await()
            }

            // 第二批：热门内容 —— 源→站点→分类整条链路，慢或失败整行不显示
            val hot = runCatching {
                val src = api.sources().firstOrNull() ?: error("未配置源")
                val cfg = api.sites(src.id)
                val site = cfg.sites.firstOrNull {
                    it.api.isNotBlank() && (it.type == 0 || it.type == 1)
                } ?: error("源内无可用站点")
                Triple(site.api, site.key, site.name) to api.categories(site.api, page = 1)
            }.getOrNull()

            // 第三批：按站点分类补「电影 / 剧集 / 动漫」货架（并发拉取、固定顺序）
            var cats: List<Pair<String, List<VodItem>>> = emptyList()
            if (hot != null && hot.second.list.isNotEmpty()) {
                val buckets = listOf(
                    getString(R.string.shelf_movie) to listOf("电影"),
                    getString(R.string.shelf_series) to listOf("电视剧", "连续剧", "剧集"),
                    getString(R.string.shelf_anime) to listOf("动漫", "动画"),
                )
                cats = buckets.map { (title, keys) ->
                    val cls = hot.second.klass.firstOrNull { c -> keys.any { c.typeName.contains(it) } }
                    coroutineScope {
                        async {
                            if (cls == null) null
                            else title to runCatching {
                                api.categories(hot.first.first, page = 1, category = cls.typeId).list
                            }.getOrNull().orEmpty().take(12)
                        }
                    }
                }.map { it.await() }.filterNotNull().filter { it.second.isNotEmpty() }
            }

            val snap = Snapshot(
                history = historyList,
                favs = favs,
                site = hot?.first,
                hot = hot?.second?.list.orEmpty().take(12),
                cats = cats,
            )
            cache = snap
            cacheAt = SystemClock.elapsedRealtime()
            render(snap)
        }
    }

    private fun render(snap: Snapshot) {
        val repo = app.repo

        // Hero 轮播池：在追的放最前（继续播放），不足再从热门补齐
        val pool = mutableListOf<HomeItem.Hero>()
        snap.history.firstOrNull()?.let { h ->
            pool += HomeItem.Hero(
                title = h.vod_name,
                meta = "看到第 ${h.episode_index + 1} 集 · 继续观看",
                imageUrl = repo.proxyImage(h.vod_pic),
                btnLabel = getString(R.string.hero_resume),
            ) { h.open() }
        }
        val site = snap.site
        if (site != null) {
            snap.hot
                .filter { v -> snap.history.firstOrNull()?.vod_name != v.vod_name }
                .take(MAX_HEROES - pool.size)
                .forEach { v ->
                    pool += HomeItem.Hero(
                        title = v.vod_name,
                        meta = v.vod_remarks?.takeIf { it.isNotBlank() } ?: site.third,
                        imageUrl = repo.proxyImage(v.vod_pic),
                        btnLabel = getString(R.string.hero_play),
                    ) { pushDetail(DetailArgs(site.first, v.vod_id, site.second, site.third, v.vod_name, v.vod_pic)) }
                }
        }
        heroes = pool
        heroIndex = 0

        val items = mutableListOf<HomeItem>()
        if (snap.history.isNotEmpty()) {
            items += HomeItem.Shelf(
                getString(R.string.shelf_continue),
                snap.history.take(12).map { it.toCard(repo) },
            )
        }
        if (snap.favs.isNotEmpty()) {
            items += HomeItem.Shelf(
                getString(R.string.shelf_fav),
                snap.favs.take(12).map { it.toCard(repo) },
            )
        }
        if (site != null && snap.hot.isNotEmpty()) {
            items += HomeItem.Shelf(
                getString(R.string.shelf_hot),
                snap.hot.map { it.toCard(repo, site.first, site.second, site.third) },
            )
            snap.cats.forEach { (title, list) ->
                items += HomeItem.Shelf(
                    title,
                    list.map { it.toCard(repo, site.first, site.second, site.third) },
                )
            }
        }
        shelfItems = items
        adapter.submitList(
            (heroes.firstOrNull()?.let { listOf(it) } ?: emptyList()) + shelfItems
        )

        // 冷启动/回到首页时，若焦点还停在标签栏等外壳上，抢到 Hero——
        // 打开就能按 OK 继续看；用户已聚焦内容则不打扰
        if (heroes.isNotEmpty()) { heroFocusTries = 0; tryFocusHero() }
    }

    /** 焦点抢到 Hero。渲染快于 layout 或焦点尚在壳上时都会失败，
     *  统一延迟 + 校验重试（对 requestFocus 返回值也校验），直到真正落上 */
    private var heroFocusTries = 0
    private fun tryFocusHero() {
        if (heroFocusTries > 15 || !isAdded || _b == null) return
        b.homeList.postDelayed({
            if (!isAdded || _b == null) return@postDelayed
            val hero = b.homeList.findViewHolderForAdapterPosition(0)?.itemView
            if (hero == null) {
                heroFocusTries++
                tryFocusHero()
                return@postDelayed
            }
            val cf = requireActivity().currentFocus
            // 焦点即切页后，「焦点在标签栏」= 用户正在扫标签，绝不能抢；
            // 只有焦点悬空/在内容外壳上，或冷启动（用户还没按过键）时才抢到 Hero
            val onShell = cf == null || cf.id == R.id.tabContainer || cf.id == R.id.homeList
            val onTab = cf != null && generateSequence(cf.parent as? View) { it.parent as? View }
                .any { it.id == R.id.tabbar }
            val coldStart = (requireActivity() as? MainActivity)?.userNavigated == false
            if (onShell || (coldStart && onTab)) {
                if (!hero.requestFocus() || !hero.hasFocus()) {
                    heroFocusTries++
                    tryFocusHero()
                }
            }
        }, 100)
    }

    private fun pushDetail(args: DetailArgs) {
        (requireActivity() as? MainActivity)?.pushDetail(args)
    }

    /** 旧记录可能没存 api_url：通过 site-lookup 兜底 */
    private fun openWithLookup(apiUrl: String, vodId: String, siteKey: String, siteName: String, title: String, pic: String) {
        if (apiUrl.isNotBlank()) {
            pushDetail(DetailArgs(apiUrl, vodId, siteKey, siteName, title, pic))
            return
        }
        val api = app.repo.api ?: return
        lifecycleScope.launch {
            val url = runCatching { api.siteLookup(siteKey).api_url }.getOrNull()
            if (url.isNullOrBlank()) {
                app.toast("站点查找失败：$siteName")
                return@launch
            }
            pushDetail(DetailArgs(url, vodId, siteKey, siteName, title, pic))
        }
    }

    private fun HistoryRecord.open() = openWithLookup(api_url, vod_id, site_key, site_name, vod_name, vod_pic)

    private fun HistoryRecord.toCard(repo: Repo) = CardUi(
        title = vod_name,
        imageUrl = repo.proxyImage(vod_pic),
        progress = if (duration > 0) (current_time / duration * 100).toInt() else null,
        onClick = { open() },
    )

    private fun Favorite.toCard(repo: Repo) = CardUi(
        title = vod_name,
        imageUrl = repo.proxyImage(vod_pic),
        badge = if (update_flag && update_gap > 0) "更新 ${update_gap} 集" else null,
        onClick = { openWithLookup(api_url, vod_id, site_key, site_name, vod_name, vod_pic) },
    )

    private fun VodItem.toCard(repo: Repo, siteApi: String, siteKey: String, siteName: String) = CardUi(
        title = vod_name,
        imageUrl = repo.proxyImage(vod_pic),
        badge = if (!vod_remarks.isNullOrBlank()) vod_remarks else null,
        onClick = { pushDetail(DetailArgs(siteApi, vod_id, siteKey, siteName, vod_name, vod_pic)) },
    )

    companion object {
        private const val CACHE_MS = 30_000L
        private const val HERO_ROTATE_MS = 12_000L
        private const val MAX_HEROES = 4
        private var cache: Snapshot? = null
        private var cacheAt = 0L
    }
}
