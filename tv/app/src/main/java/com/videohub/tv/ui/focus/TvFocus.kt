package com.videohub.tv.ui.focus

import android.graphics.Outline
import android.graphics.Rect
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

/**
 * tvOS 风格焦点反馈：180ms 减速缩放 + 焦点上浮（translationZ 产生投影）。
 * 父容器需要 clipChildren=false，放大后才不会被裁切。
 * 同时确保视图可聚焦——布局里的"按钮"多为纯 TextView，默认 focusable=false，
 * 遥控器方向键永远到不了；凡是挂了焦点反馈的视图一律视为可选中。
 *
 * @param onChange 聚焦态变化回调（如卡片下方标题灰→白），随动画一起触发
 */
fun View.tvFocus(
    scale: Float = 1.08f,
    ring: Boolean = false,
    onChange: ((hasFocus: Boolean) -> Unit)? = null,
) {
    isFocusable = true
    // 冷启动等场景窗口可能处于 touch mode，非 touch-mode-focusable 的视图
    // requestFocus 会直接失败（焦点真空）；一并放开，遥控/触屏都能落焦
    isFocusableInTouchMode = true
    setOnFocusChangeListener { v, hasFocus ->
        val target = if (hasFocus) scale else 1f
        v.animate()
            .scaleX(target)
            .scaleY(target)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .start()
        v.translationZ = if (hasFocus) 16f * resources.displayMetrics.density else 0f
        if (ring && v is MaterialCardView) {
            v.strokeWidth = if (hasFocus) (3 * resources.displayMetrics.density).toInt() else 0
        }
        onChange?.invoke(hasFocus)
    }
}

/** 回收时重置缩放，避免复用残影 */
fun View.resetFocusScale() {
    animate().cancel()
    scaleX = 1f
    scaleY = 1f
    translationZ = 0f
}

/**
 * 圆角轮廓：让 translationZ 的投影跟随卡片圆角，而不是方角阴影。
 * 位图级圆角（Glide RoundedCorners）不产生轮廓，需要手动提供。
 */
fun View.roundedOutline(radiusPx: Float) {
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
        }
    }
    clipToOutline = false
}

/** 横向货架的卡片间距（只给非首个元素加左边距） */
class HorizontalSpacing(private val px: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        if (parent.getChildAdapterPosition(view) != 0) outRect.left = px
    }
}

/** 网格间距：列间/行间各 px，首行/首列不外扩 */
class GridSpacing(private val px: Int, private val spanCount: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        val pos = parent.getChildAdapterPosition(view)
        if (pos < 0) return
        outRect.left = if (pos % spanCount == 0) 0 else px
        outRect.top = if (pos < spanCount) 0 else px
    }
}
