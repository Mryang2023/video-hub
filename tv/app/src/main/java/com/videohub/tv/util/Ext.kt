package com.videohub.tv.util

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.videohub.tv.R

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

fun Context.toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

/** 收起软键盘（搜索触发后别让它盖住结果） */
fun View.hideKeyboard() {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    imm?.hideSoftInputFromWindow(windowToken, 0)
}

private val PLACEHOLDER_COLORS = intArrayOf(
    R.color.ph1, R.color.ph2, R.color.ph3, R.color.ph4,
    R.color.ph5, R.color.ph6, R.color.ph7, R.color.ph8,
)

/** 封面缺失时的占位色：按片名哈希从 8 个 tvOS 风格深色里挑一个 */
@ColorInt
fun Context.placeholderColor(seed: String): Int {
    if (seed.isEmpty()) return ContextCompat.getColor(this, R.color.ph8)
    val idx = ((seed.hashCode() % 8) + 8) % 8
    return ContextCompat.getColor(this, PLACEHOLDER_COLORS[idx])
}

/** 焦点当前是否停在顶部标签栏（焦点即切换模式下，页面不应与标签抢焦点） */
fun View.focusIsOnTabbar(): Boolean {
    var p: View? = this
    while (p != null) {
        if (p.id == com.videohub.tv.R.id.tabbar) return true
        p = p.parent as? View
    }
    return false
}
