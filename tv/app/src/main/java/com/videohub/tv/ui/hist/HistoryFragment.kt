package com.videohub.tv.ui.hist

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.videohub.tv.App
import com.videohub.tv.MainActivity
import com.videohub.tv.R
import com.videohub.tv.data.HistoryRecord
import com.videohub.tv.databinding.FragmentGridBinding
import com.videohub.tv.ui.detail.DetailArgs
import com.videohub.tv.ui.focus.GridSpacing
import com.videohub.tv.ui.home.CardAdapter
import com.videohub.tv.ui.home.CardUi
import com.videohub.tv.util.toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/** 历史页：宽卡网格 + 观看进度 + 断点续播入口 */
class HistoryFragment : Fragment(R.layout.fragment_grid) {

    private var _b: FragmentGridBinding? = null
    private val b get() = _b!!

    private val app: App get() = requireActivity().application as App
    private val repo get() = app.repo
    private val adapter = CardAdapter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _b = FragmentGridBinding.bind(view)
        b.grid.layoutManager = GridLayoutManager(requireContext(), 4)
        b.grid.adapter = adapter
        b.grid.addItemDecoration(
            GridSpacing((14 * resources.displayMetrics.density).toInt(), 4)
        )
        b.empty.setText(R.string.hist_empty)
        load()
    }

    private fun load() {
        val api = repo.api ?: return
        lifecycleScope.launch {
            val hist = runCatching { api.history() }.getOrDefault(emptyList())
            b.empty.isVisible = hist.isEmpty()
            adapter.submitList(hist.map { h ->
                CardUi(
                    title = h.vod_name,
                    imageUrl = repo.proxyImage(h.vod_pic),
                    wide = true,
                    progress = if (h.duration > 0) (h.current_time / h.duration * 100).toInt() else null,
                    sub = h.episode_name.ifBlank { getString(R.string.hist_at_ep, h.episode_index + 1) },
                    onLongClick = { showMenu(h) },
                    onClick = { open(h.api_url, h.site_key, h.vod_id, h.site_name, h.vod_name, h.vod_pic) },
                )
            })
        }
    }

    /** 长按 OK：删除单条 / 清空全部 */
    private fun showMenu(h: HistoryRecord) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(h.vod_name)
            .setItems(arrayOf("删除这条记录", "清空全部历史", "取消")) { _, which ->
                val api = repo.api ?: return@setItems
                when (which) {
                    0 -> lifecycleScope.launch {
                        runCatching { api.deleteHistory(h.site_key, h.vod_id) }
                        requireContext().toast("已删除")
                        load()
                    }
                    1 -> MaterialAlertDialogBuilder(requireContext())
                        .setTitle("清空全部历史")
                        .setMessage("所有设备的观看记录都会被清空，确定吗？")
                        .setPositiveButton("清空") { _, _ ->
                            lifecycleScope.launch {
                                runCatching { api.clearHistory() }
                                requireContext().toast("已清空")
                                load()
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
            .show()
    }

    /** 旧记录可能没存 api_url：通过 site-lookup 兜底查一次 */
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
