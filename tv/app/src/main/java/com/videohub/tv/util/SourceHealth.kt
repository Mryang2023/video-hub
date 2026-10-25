package com.videohub.tv.util

import android.content.Context
import org.json.JSONObject

/**
 * 源（播放组）健康度记忆：连续失败次数，播放成功即清零。
 * 详情页默认组优先跳过「近期连续失败 >= 2 次」的源，省去手动换源。
 * 仅存本机 SharedPreferences，不涉及后端。
 */
object SourceHealth {
    private const val PREFS = "src_health"
    private const val KEY = "fails"
    const val BAD_THRESHOLD = 2

    private fun read(ctx: Context): MutableMap<String, Int> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return mutableMapOf()
        return runCatching {
            val o = JSONObject(raw)
            mutableMapOf<String, Int>().apply { o.keys().forEach { k -> put(k, o.optInt(k)) } }
        }.getOrDefault(mutableMapOf())
    }

    private fun write(ctx: Context, map: Map<String, Int>) {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, o.toString()).apply()
    }

    fun fail(ctx: Context, group: String) {
        if (group.isBlank()) return
        val m = read(ctx)
        m[group] = (m[group] ?: 0) + 1
        write(ctx, m)
    }

    fun ok(ctx: Context, group: String) {
        if (group.isBlank()) return
        val m = read(ctx)
        if (m.remove(group) != null) write(ctx, m)
    }

    fun score(ctx: Context, group: String): Int = read(ctx)[group] ?: 0

    fun clear(ctx: Context) {
        write(ctx, emptyMap())
    }

    /** 设置页摘要：「2 个源异常 · 按 OK 重置」或「记录正常」 */
    fun summary(ctx: Context): String {
        val bad = read(ctx).count { it.value >= BAD_THRESHOLD }
        return if (bad > 0) "$bad 个源异常 · 按 OK 重置" else "记录正常"
    }
}
