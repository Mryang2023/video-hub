package com.videohub.tv.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import com.videohub.tv.R
import com.videohub.tv.ui.focus.resetFocusScale
import com.videohub.tv.ui.focus.roundedOutline
import com.videohub.tv.ui.focus.tvFocus
import com.videohub.tv.util.placeholderColor

/**
 * 货架卡片 UI 模型（历史/收藏/站点内容统一映射）。
 * wide=true 时用 272x153 宽卡（历史页，图上叠标题），否则 208x117 标准卡（图下标题）。
 */
data class CardUi(
    val title: String,
    val imageUrl: String?,
    val seed: String = title,
    /** 0..100，观看进度 */
    val progress: Int? = null,
    /** 追更红点文案，如「更新 2 集」 */
    val badge: String? = null,
    /** 宽卡第二行小字（如 vod_remarks / 看到第 N 集） */
    val sub: String? = null,
    val wide: Boolean = false,
    val onClick: () -> Unit = {},
    /** 长按 OK 弹上下文菜单（收藏/历史页） */
    val onLongClick: (() -> Unit)? = null,
)

class CardAdapter : ListAdapter<CardUi, CardAdapter.VH>(Diff) {

    class VH(root: View) : RecyclerView.ViewHolder(root) {
        val card: FrameLayout = root.findViewById(R.id.card)
        val img: ImageView = root.findViewById(R.id.img)
        val phTitle: TextView? = root.findViewById(R.id.phTitle)
        val title: TextView = root.findViewById(R.id.title)
        val sub: TextView? = root.findViewById(R.id.sub)
        val badge: TextView = root.findViewById(R.id.badge)
        val progress: ProgressBar = root.findViewById(R.id.progress)
        val cornerPx: Int =
            (12f * root.resources.displayMetrics.density).toInt().coerceAtLeast(8)
    }

    override fun getItemViewType(position: Int): Int =
        if (getItem(position).wide) TYPE_WIDE else TYPE_NORMAL

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (viewType == TYPE_WIDE) R.layout.item_card_wide else R.layout.item_card
        val vh = VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
        // 聚焦：放大 + 上浮（圆角投影），标题灰→白
        vh.card.tvFocus(scale = 1.12f) { hasFocus ->
            vh.title.setTextColor(
                if (hasFocus) 0xFFFFFFFF.toInt() else 0xFF9A9AA2.toInt()
            )
        }
        vh.card.roundedOutline(vh.cornerPx.toFloat())
        vh.card.setOnClickListener {
            val pos = vh.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) getItem(pos).onClick()
        }
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val ctx = holder.itemView.context
        holder.card.setBackgroundColor(ctx.placeholderColor(item.seed))
        holder.title.text = item.title

        holder.sub?.let { s ->
            val text = item.sub
            s.visibility = if (text.isNullOrBlank()) View.INVISIBLE else View.VISIBLE
            s.text = text ?: ""
        }

        val badge = item.badge
        holder.badge.visibility = if (badge == null) View.GONE else View.VISIBLE
        holder.badge.text = badge ?: ""

        val progress = item.progress
        holder.progress.visibility =
            if (progress != null && progress > 0) View.VISIBLE else View.GONE
        holder.progress.progress = progress ?: 0

        // 位图级圆角（不用轮廓裁剪，低端电视 GPU 上会有伪影）。
        // 不写死 override：按视图实际尺寸解码，避免过度缩放导致封面发虚
        val opts = RequestOptions().transform(
            CenterCrop(),
            com.videohub.tv.util.SharpenTransformation(0.35f),
            RoundedCorners(holder.cornerPx),
        )
        if (item.imageUrl != null) {
            holder.phTitle?.visibility = View.GONE
            Glide.with(ctx)
                .load(item.imageUrl)
                .apply(opts)
                .into(holder.img)
        } else {
            Glide.with(ctx).clear(holder.img)
            holder.img.setImageDrawable(null)
            // 无封面：占位色块中央兜底显示片名
            holder.phTitle?.let { pt ->
                pt.text = item.title
                pt.visibility = View.VISIBLE
            }
        }

        holder.card.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) false
            else if (item.onLongClick != null) {
                item.onLongClick.invoke()
                true
            } else false
        }
    }

    override fun onViewRecycled(holder: VH) {
        holder.card.resetFocusScale()
        Glide.with(holder.img).clear(holder.img)
    }

    companion object {
        private const val TYPE_NORMAL = 0
        private const val TYPE_WIDE = 1

        private val Diff = object : DiffUtil.ItemCallback<CardUi>() {
            override fun areItemsTheSame(oldItem: CardUi, newItem: CardUi) =
                oldItem.title == newItem.title && oldItem.imageUrl == newItem.imageUrl

            override fun areContentsTheSame(oldItem: CardUi, newItem: CardUi) = oldItem == newItem
        }
    }
}
