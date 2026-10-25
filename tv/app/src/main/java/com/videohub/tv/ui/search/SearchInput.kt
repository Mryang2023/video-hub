package com.videohub.tv.ui.search

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import androidx.appcompat.widget.AppCompatEditText

/**
 * 搜索框：在 pre-IME 阶段拦截方向键。
 *
 * 输入法开着时，遥控器的 ↑/↓ 会先送进输入法（Gboard 实测直接吞掉），
 * 应用层的 OnKey 永远收不到，焦点就困在键盘里出不去。
 * dispatchKeyEventPreIme 在事件进入输入法**之前**触发，是唯一能抢先处理的点。
 */
class SearchInput @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : AppCompatEditText(context, attrs) {

    /** 返回 true 表示事件已消费（不再交给输入法） */
    var onPreImeDirection: ((keyCode: Int) -> Boolean)? = null

    override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
        ) {
            if (onPreImeDirection?.invoke(event.keyCode) == true) return true
        }
        return super.dispatchKeyEventPreIme(event)
    }
}
