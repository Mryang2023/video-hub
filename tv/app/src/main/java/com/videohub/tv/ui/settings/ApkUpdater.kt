package com.videohub.tv.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** 应用内自更新：下载 APK → 拉起系统安装器（安装确认需遥控器按一次，系统限制） */
object ApkUpdater {

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** 「允许此来源安装」设置页 */
    fun installPermissionIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )

    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file,
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    /** 逐段比版本号：0.3.2 > 0.3.1 > 0.3 */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val l = local.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrNull(i) ?: 0
            val lv = l.getOrNull(i) ?: 0
            if (rv != lv) return rv > lv
        }
        return false
    }

    /**
     * 下载 APK。电视 Wi-Fi 常见断流（unexpected end of stream），
     * 因此自动重试并带 Range 断点续传，最多 4 次尝试。
     */
    suspend fun download(url: String, dest: File, onProgress: suspend (Int) -> Unit) {
        withContext(Dispatchers.IO) {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
            dest.parentFile?.mkdirs()
            val tmp = File(dest.absolutePath + ".tmp")
            var lastError: Throwable = IllegalStateException("下载失败")

            for (attempt in 1..4) {
                try {
                    if (tryDownloadOnce(client, url, tmp) { p -> onProgress(p) }) {
                        if (dest.exists()) dest.delete()
                        check(tmp.renameTo(dest)) { "写入失败" }
                        return@withContext
                    }
                    lastError = IllegalStateException("传输中断（已续传重试 $attempt 次）")
                } catch (e: Throwable) {
                    lastError = e
                }
                if (attempt < 4) Thread.sleep(800L * attempt)
            }
            tmp.delete()
            throw IllegalStateException("多次下载失败（${lastError.message}），请检查网络后重试")
        }
    }

    /** 单次尝试：tmp 已有部分内容时发 Range 续传。返回是否完整下完。 */
    private suspend fun tryDownloadOnce(
        client: OkHttpClient,
        url: String,
        tmp: File,
        onProgress: suspend (Int) -> Unit,
    ): Boolean {
        val have = if (tmp.exists()) tmp.length() else 0L
        val rb = Request.Builder().url(url)
        if (have > 0) rb.header("Range", "bytes=$have-")
        client.newCall(rb.build()).execute().use { resp ->
            if (resp.code == 416) {
                // 续传起点已越过文件末尾：作废重来
                tmp.delete()
                error("续传失效")
            }
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            val body = resp.body ?: error("空响应")
            val partial = resp.code == 206
            var total = body.contentLength()
            var done = 0L
            if (partial) {
                // Content-Range: bytes start-end/total
                total = resp.header("Content-Range")
                    ?.substringAfter('/')?.toLongOrNull()
                    ?: (if (total > 0) total + have else -1L)
                done = have
            } else {
                tmp.delete()
            }
            FileOutputStream(tmp, partial).use { out ->
                body.byteStream().use { ins ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(((done * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            return total <= 0 || tmp.length() == total
        }
    }
}
