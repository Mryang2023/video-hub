package com.videohub.tv.ui.fav

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.videohub.tv.App
import com.videohub.tv.MainActivity
import com.videohub.tv.R
import com.videohub.tv.data.Favorite
import com.videohub.tv.databinding.FragmentGridBinding
import com.videohub.tv.ui.detail.DetailArgs
import com.videohub.tv.ui.focus.GridSpacing
import com.videohub.tv.ui.home.CardAdapter
import com.videohub.tv.ui.home.CardUi
import com.videohub.tv.util.toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 收藏页：网格 + 追更角标 */
class FavoritesFragment : Fragment(R.layout.fragment_grid) {

    private var _b: FragmentGridBinding? = null
    private val b get() = _b!!

    private val app: App get() = requireActivity().application as App
    private val repo get() = app.repo
    private val adapter = CardAdapter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _b = FragmentGridBinding.bind(view)
        b.grid.layoutManager = GridLayoutManager(requireContext(), 6)
        b.grid.adapter = adapter
        b.grid.addItemDecoration(
            GridSpacing((14 * resources.displayMetrics.density).toInt(), 6)
        )
        b.empty.setText(R.string.fav_empty)
        load()
    }

    private fun load() {
        val api = repo.api ?: return
        lifecycleScope.launch {
            val favs = runCatching { api.favorites() }.getOrDefault(emptyList())
            b.empty.isVisible = favs.isEmpty()
            adapter.submitList(favs.map { f ->
                CardUi(
                    title = f.vod_name,
                    imageUrl = repo.proxyImage(f.vod_pic),
                    badge = if (f.update_flag && f.update_gap > 0) "更新 ${f.update_gap} 集" else null,
                    onLongClick = { showMenu(f) },
                    onClick = { open(f.api_url, f.site_key, f.vod_id, f.site_name, f.vod_name, f.vod_pic) },
                )
            })
        }
    }

    /** 长按 OK：标记已看（清红点）/ 取消收藏 */
    private fun showMenu(f: Favorite) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(f.vod_name)
            .setItems(arrayOf("标记已看（清除更新提醒）", "取消收藏", "取消")) { _, which ->
                val api = repo.api ?: return@setItems
                when (which) {
                    0 -> lifecycleScope.launch {
                        runCatching {
                            api.clearUpdateFlag(
                                buildJsonObject {
                                    put("site_key", f.site_key)
                                    put("vod_id", f.vod_id)
                                }
                            )
                        }
                        requireContext().toast("已标记已看")
                        load()
                    }
                    1 -> lifecycleScope.launch {
                        runCatching { api.removeFavorite(f.site_key, f.vod_id) }
                        requireContext().toast(R.string.fav_removed)
                        load()
                    }
                }
            }
            .show()
    }

    /** 老收藏可能没存 api_url：通过 site-lookup 兜底查一次 */
    private fun open(apiUrl: String, siteKey: String, vodId: String, siteName: String, title: String, pic: String) {
        val main = requireActivity() as? MainActivity ?: return
        if (apiUrl.isNotBlank()) {
            main.pushDetail(DetailArgs(apiUrl, vodId, siteKey, siteName, title, pic))
            return
        }
        val api = repo.api ?: return
        lifecycleScope.launch {
            val url = runCatching { api.siteLookup(siteKey).api_url }.getOrNull()
            if (url.isNullOrBlank()) {
                requireContext().toast("站点查找失败：$siteName")
                return@launch
            }
            main.pushDetail(DetailArgs(url, vodId, siteKey, siteName, title, pic))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
