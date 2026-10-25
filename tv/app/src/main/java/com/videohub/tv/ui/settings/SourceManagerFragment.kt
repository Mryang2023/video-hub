package com.videohub.tv.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.videohub.tv.App
import com.videohub.tv.R
import com.videohub.tv.data.Source
import com.videohub.tv.data.SourceCreate
import com.videohub.tv.data.SourceHealthItem
import com.videohub.tv.databinding.DialogAddSourceBinding
import com.videohub.tv.databinding.FragmentSrcmgrBinding
import com.videohub.tv.databinding.ItemSourceBinding
import com.videohub.tv.ui.focus.tvFocus
import com.videohub.tv.util.toast
import kotlinx.coroutines.launch

/** 源管理：列表 + 健康检测 + 添加 / 长按删除 */
class SourceManagerFragment : Fragment(R.layout.fragment_srcmgr) {

    private var _b: FragmentSrcmgrBinding? = null
    private val b get() = _b!!

    private val app: App get() = requireActivity().application as App
    private val repo get() = app.repo

    private val healthMap = mutableMapOf<String, SourceHealthItem>()
    private val adapter = SrcAdapter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _b = FragmentSrcmgrBinding.bind(view)

        b.btnSrcBack.tvFocus(1.05f)
        b.btnHealth.tvFocus(1.05f)
        b.btnAdd.tvFocus(1.05f)
        b.btnSrcBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        b.btnHealth.setOnClickListener { checkHealth() }
        b.btnAdd.setOnClickListener { showAddDialog() }

        b.srcList.layoutManager = LinearLayoutManager(requireContext())
        b.srcList.adapter = adapter
        load()
    }

    private fun load() {
        val api = repo.api ?: return
        lifecycleScope.launch {
            val sources = runCatching { api.sources() }.getOrDefault(emptyList())
            adapter.submitList(sources)
            if (sources.isEmpty()) {
                b.btnAdd.requestFocus()
            }
        }
    }

    private fun checkHealth() {
        val api = repo.api ?: return
        requireContext().toast("正在检测全部源…")
        lifecycleScope.launch {
            val result = runCatching { api.sourcesHealth() }.getOrNull()
            healthMap.clear()
            result?.results?.forEach { healthMap[it.source_id] = it }
            adapter.notifyDataSetChanged()
        }
    }

    private fun showAddDialog() {
        val db = DialogAddSourceBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("添加源")
            .setView(db.root)
            .setPositiveButton("添加") { _, _ ->
                val name = db.editName.text?.toString()?.trim().orEmpty()
                val url = db.editUrl.text?.toString()?.trim().orEmpty()
                if (name.isEmpty() || url.isEmpty()) {
                    requireContext().toast("名称和地址都要填")
                    return@setPositiveButton
                }
                val api = repo.api ?: return@setPositiveButton
                lifecycleScope.launch {
                    val added = runCatching { api.addSource(SourceCreate(name, url)) }.isSuccess
                    if (added) {
                        requireContext().toast("已添加「$name」，站点数稍后自动统计")
                        load()
                    } else {
                        requireContext().toast("添加失败：地址无法访问或格式不对")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(src: Source) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("删除源「${src.name}」")
            .setMessage("删除后需要重新添加才能恢复")
            .setPositiveButton("删除") { _, _ ->
                val api = repo.api ?: return@setPositiveButton
                lifecycleScope.launch {
                    runCatching { api.deleteSource(src.id) }
                    requireContext().toast("已删除")
                    load()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private inner class SrcAdapter :
        ListAdapter<Source, SrcAdapter.VH>(object : DiffUtil.ItemCallback<Source>() {
            override fun areItemsTheSame(oldItem: Source, newItem: Source) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: Source, newItem: Source) = oldItem == newItem
        }) {

        inner class VH(val vb: ItemSourceBinding) : RecyclerView.ViewHolder(vb.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val vb = ItemSourceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            val vh = VH(vb)
            vb.root.tvFocus(1.01f)
            vb.root.setOnLongClickListener {
                val pos = vh.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) confirmDelete(getItem(pos))
                true
            }
            return vh
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val src = getItem(position)
            val ctx = holder.vb.root.context
            holder.vb.srcName.text = src.name
            holder.vb.srcUrl.text = src.url
            val h = healthMap[src.id]
            val status = when {
                h == null -> if (src.site_count > 0) "${src.site_count} 个站点" else ""
                h.ok -> ContextCompat.getColor(ctx, R.color.ok_green).let {
                    "${src.site_count} 站点 · ${h.latency_ms}ms"
                }
                else -> ContextCompat.getColor(ctx, R.color.err_red).let {
                    h.error?.take(14) ?: "失败"
                }
            }
            holder.vb.srcStatus.text = status
            holder.vb.srcStatus.setTextColor(
                when {
                    h == null -> ContextCompat.getColor(ctx, R.color.text_secondary)
                    h.ok -> ContextCompat.getColor(ctx, R.color.ok_green)
                    else -> ContextCompat.getColor(ctx, R.color.err_red)
                },
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
