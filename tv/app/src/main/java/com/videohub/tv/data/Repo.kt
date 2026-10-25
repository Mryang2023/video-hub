package com.videohub.tv.data

import android.util.Log
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** 仓库层：按服务器地址构建 Retrofit，统一 URL 工具 */
class Repo(private val prefs: Prefs) {

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            // 历史/收藏按客户端分桶：电视端与 Web 端各自独立存取
            val req = chain.request()
            val path = req.url.encodedPath
            val tagged = if (path.contains("/api/history") || path.contains("/api/favorites")) {
                req.newBuilder()
                    .url(req.url.newBuilder().addQueryParameter("client", "tv").build())
                    .build()
            } else req
            chain.proceed(tagged)
        }
        .addInterceptor(
            HttpLoggingInterceptor { msg -> Log.d("VHTV-NET", msg) }
                .setLevel(HttpLoggingInterceptor.Level.BASIC)
        )
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var apiCache: Pair<String, Api>? = null

    fun apiFor(baseUrl: String): Api {
        val base = normalize(baseUrl)
        require(base.isNotEmpty()) { "服务器地址为空" }
        synchronized(this) {
            apiCache?.let { if (it.first == base) return it.second }
            val api = Retrofit.Builder()
                .baseUrl(base)
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(Api::class.java)
            apiCache = base to api
            return api
        }
    }

    /** 当前已保存服务器对应的 API；未配置返回 null */
    val api: Api?
        get() = prefs.serverUrl?.let { runCatching { apiFor(it) }.getOrNull() }

    /** 封面防盗链：一律走后端图片代理 */
    fun proxyImage(pic: String?): String? {
        if (pic.isNullOrBlank() || !pic.startsWith("http")) return null
        val base = prefs.serverUrl ?: return null
        return "${base}api/proxy/image?url=${URLEncoder.encode(pic, "UTF-8")}"
    }

    companion object {
        /** 补协议、去尾部斜杠、加 baseUrl 要求的结尾斜杠 */
        fun normalize(raw: String): String {
            var v = raw.trim()
            if (!v.startsWith("http://", ignoreCase = true) &&
                !v.startsWith("https://", ignoreCase = true)
            ) {
                if (v.isEmpty()) return ""
                v = "http://$v"
            }
            while (v.endsWith("/")) v = v.dropLast(1)
            return "$v/"
        }
    }
}
