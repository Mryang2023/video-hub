package com.videohub.tv.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.videohub.tv.databinding.ItemHeroBinding
import com.videohub.tv.databinding.ItemShelfBinding
import com.videohub.tv.ui.focus.HorizontalSpacing
import com.videohub.tv.ui.focus.resetFocusScale
import com.videohub.tv.ui.focus.tvFocus
import com.videohub.tv.util.placeholderColor

/** 首页条目：Hero 大图 + 若干横向货架 */
sealed class HomeItem {
    data class Hero(
        val title: String,
        val meta: String,
        val imageUrl: String?,
        /** 播放胶囊文案（继续播放 / 立即播放） */
        val btnLabel: String,
        val seed: String = title,
        val onClick: () -> Unit,
    ) : HomeItem()

    data class Shelf(val title: String, val cards: List<CardUi>) : HomeItem()
}

class HomeAdapter : ListAdapter<HomeItem, RecyclerView.ViewHolder>(Diff) {

    /** Hero 绑定回调：HomeFragment 用它同步环境光背景 */
    var onHeroShown: ((HomeItem.Hero) -> Unit)? = null

    class HeroVH(val b: ItemHeroBinding) : RecyclerView.ViewHolder(b.root)

    class ShelfVH(val b: ItemShelfBinding) : RecyclerView.ViewHolder(b.root) {
        val cardAdapter = CardAdapter()
    }

    override fun getItemViewType(position: Int): Int =
        when (getItem(position)) {
            is HomeItem.Hero -> TYPE_HERO
            is HomeItem.Shelf -> TYPE_SHELF
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val ctx = parent.context
        return when (viewType) {
            TYPE_HERO -> HeroVH(
                ItemHeroBinding.inflate(LayoutInflater.from(ctx), parent, false)
            )
            else -> ShelfVH(ItemShelfBinding.inflate(LayoutInflater.from(ctx), parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is HomeItem.Hero -> bindHero(holder as HeroVH, item)
            is HomeItem.Shelf -> bindShelf(holder as ShelfVH, item)
        }
    }

    private fun bindHero(vh: HeroVH, item: HomeItem.Hero) {
        val ctx = vh.b.root.context
        vh.b.heroCard.setBackgroundColor(ctx.placeholderColor(item.seed))
        vh.b.heroTitle.text = item.title
        vh.b.heroMeta.text = item.meta
        vh.b.heroPlay.text = item.btnLabel
        // 全幅大卡不再放大（放大出屏观感差），聚焦用光晕描边表达
        vh.b.heroCard.tvFocus(scale = 1f)
        vh.b.heroCard.setOnClickListener { item.onClick() }
        if (item.imageUrl != null) {
            val corner = (18 * ctx.resources.displayMetrics.density).toInt()
            Glide.with(ctx)
                .load(item.imageUrl)
                .transition(DrawableTransitionOptions.withCrossFade(350))
                .apply(
                    RequestOptions().transform(
                        CenterCrop(),
                        // 小图放大后发软，锐化补偿；顺序必须锐化在圆角前
                        com.videohub.tv.util.SharpenTransformation(0.45f),
                        RoundedCorners(corner),
                    )
                )
                .into(vh.b.heroImg)
        } else {
            Glide.with(ctx).clear(vh.b.heroImg)
            vh.b.heroImg.setImageDrawable(null)
        }
        onHeroShown?.invoke(item)
    }

    private fun bindShelf(vh: ShelfVH, item: HomeItem.Shelf) {
        vh.b.shelfTitle.text = item.title
        if (vh.b.shelfRow.adapter == null) {
            val ctx = vh.b.root.context
            vh.b.shelfRow.layoutManager =
                LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
            vh.b.shelfRow.adapter = vh.cardAdapter
            vh.b.shelfRow.addItemDecoration(
                HorizontalSpacing((16 * ctx.resources.displayMetrics.density).toInt())
            )
        }
        vh.cardAdapter.submitList(item.cards)
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is HeroVH -> {
                holder.b.heroCard.resetFocusScale()
                Glide.with(holder.b.heroImg).clear(holder.b.heroImg)
            }
            is ShelfVH -> holder.cardAdapter.submitList(emptyList())
        }
    }

    companion object {
        private const val TYPE_HERO = 0
        private const val TYPE_SHELF = 1

        private val Diff = object : DiffUtil.ItemCallback<HomeItem>() {
            override fun areItemsTheSame(oldItem: HomeItem, newItem: HomeItem): Boolean =
                when (oldItem) {
                    is HomeItem.Hero ->
                        newItem is HomeItem.Hero && oldItem.title == newItem.title
                    is HomeItem.Shelf ->
                        newItem is HomeItem.Shelf && oldItem.title == newItem.title
                }

            override fun areContentsTheSame(oldItem: HomeItem, newItem: HomeItem) =
                oldItem == newItem
        }
    }
}
