package com.videohub.tv.ui.live

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.videohub.tv.App
import com.videohub.tv.MainActivity
import com.videohub.tv.R
import com.videohub.tv.data.LiveChannel
import com.videohub.tv.data.LiveGroup
import com.videohub.tv.databinding.FragmentLiveBinding
import com.videohub.tv.ui.focus.GridSpacing
import com.videohub.tv.ui.focus.tvFocus
import kotlinx.coroutines.launch

/** 直播页:左分组 / 右频道网格;聚焦分组即切换右侧频道,OK 播放 */
class LiveFragment : Fragment(R.layout.fragment_live) {

    companion object {
        private const val GRID_SPAN = 4
    }

    private var _b: FragmentLiveBinding? = null
    private val b get() = _b!!

    private val app: App get() = requireActivity().application as App
    private var groups: List<LiveGroup> = emptyList()
    private var currentGroup = 0

    private val groupAdapter = object : ListAdapter<String, GVH>(StrDiff) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GVH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_live_group, parent, false) as TextView
            val vh = GVH(v)
            // 焦点即切换右侧频道列表(遥控扫分组,右边实时刷新)
            v.tvFocus(1.05f) { hasFocus ->
                if (hasFocus) {
                    val pos = vh.bindingAdapterPosition
                    if (pos >= 0 && pos < groups.size) showChannels(pos)
                }
            }
            return vh
        }

        override fun onBindViewHolder(holder: GVH, position: Int) {
            holder.v.text = getItem(position)
        }
    }

    private inner class GVH(val v: TextView) : RecyclerView.ViewHolder(v)

    private val channelAdapter = object : ListAdapter<LiveChannel, CVH>(ChDiff) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CVH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_live_channel, parent, false) as TextView
            val vh = CVH(v)
            v.tvFocus(1.06f)
            v.setOnClickListener {
                val pos = vh.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) openChannel(pos)
            }
            // 网格边界:
            // 第一列按 ← → 焦点回到左栏"当前选中"的分组(tvOS 惯例),用户可 ↑↓ 换分组再 → 进网格;
            // 不能放任系统就近搜索——会落到与网格项同屏 y 相近的别的分组,联动误切频道
            // 顶行按 ↑ → 吃掉(防止逃到顶部标签栏)
            v.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val pos = vh.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT ->
                        if (pos % GRID_SPAN == 0) { focusGroup(currentGroup); true } else false
                    KeyEvent.KEYCODE_DPAD_UP -> (pos < GRID_SPAN)
                    else -> false
                }
            }
            return vh
        }

        override fun onBindViewHolder(holder: CVH, position: Int) {
            holder.v.text = getItem(position).name
        }
    }

    private inner class CVH(val v: TextView) : RecyclerView.ViewHolder(v)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _b = FragmentLiveBinding.bind(view)

        b.groupList.layoutManager = LinearLayoutManager(requireContext())
        b.groupList.adapter = groupAdapter

        b.channelList.layoutManager = GridLayoutManager(requireContext(), 4)
        b.channelList.adapter = channelAdapter
        val spacing = (14 * resources.displayMetrics.density).toInt()
        b.channelList.addItemDecoration(GridSpacing(spacing, 4))

        load()
    }

    private fun load() {
        val api = app.repo.api ?: return
        lifecycleScope.launch {
            val res = runCatching { api.liveChannels() }.getOrNull()
            if (_b == null) return@launch
            if (res == null || res.groups.isEmpty()) {
                b.empty.text = getString(R.string.live_load_fail)
                b.empty.isVisible = true
                return@launch
            }
            groups = res.groups
            groupAdapter.submitList(groups.map { it.name })
            showChannels(0)
        }
    }

    private fun showChannels(index: Int) {
        currentGroup = index
        channelAdapter.submitList(groups.getOrNull(index)?.channels.orEmpty())
    }

    /** 把焦点送回左栏第 pos 个分组;视图被回收/滚出屏幕时先滚过去再聚焦 */
    private fun focusGroup(pos: Int) {
        if (pos < 0 || pos >= groups.size) return
        val holder = b.groupList.findViewHolderForAdapterPosition(pos)
        if (holder?.itemView?.requestFocus() == true) return
        b.groupList.scrollToPosition(pos)
        b.groupList.post {
            if (_b == null) return@post
            b.groupList.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
        }
    }

    private fun openChannel(channelPos: Int) {
        (requireActivity() as? MainActivity)?.pushLivePlayer(groups, currentGroup, channelPos)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    private object StrDiff : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String) = oldItem == newItem
        override fun areContentsTheSame(oldItem: String, newItem: String) = oldItem == newItem
    }

    private object ChDiff : DiffUtil.ItemCallback<LiveChannel>() {
        override fun areItemsTheSame(oldItem: LiveChannel, new: LiveChannel) = oldItem.name == new.name
        override fun areContentsTheSame(oldItem: LiveChannel, new: LiveChannel) = oldItem == new
    }
}
