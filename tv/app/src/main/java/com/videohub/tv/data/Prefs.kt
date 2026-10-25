package com.videohub.tv.data

import android.content.Context

/** 电视端本地设置（服务器地址等） */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("videohub_tv", Context.MODE_PRIVATE)

    var serverUrl: String?
        get() = sp.getString(KEY_SERVER, null)
        set(value) = sp.edit().putString(KEY_SERVER, value).apply()

    /** 诊断浮层（按键提示 / 屏参信息），排查阶段默认开启 */
    var diagHud: Boolean
        get() = sp.getBoolean(KEY_DIAG_HUD, true)
        set(value) = sp.edit().putBoolean(KEY_DIAG_HUD, value).apply()

    /** 搜索历史，最近在前，最多 10 条 */
    fun addSearchHistory(word: String) {
        val cur = searchHistory().filter { it != word }.toMutableList()
        cur.add(0, word)
        sp.edit().putString(KEY_SEARCH_HIST, cur.take(10).joinToString(SEP)).apply()
    }

    fun searchHistory(): List<String> =
        sp.getString(KEY_SEARCH_HIST, null)?.split(SEP)?.filter { it.isNotBlank() } ?: emptyList()

    companion object {
        private const val KEY_SERVER = "server_url"
        private const val KEY_DIAG_HUD = "diag_hud"
        private const val KEY_SEARCH_HIST = "search_history"
        private const val SEP = "␟"
    }
}
