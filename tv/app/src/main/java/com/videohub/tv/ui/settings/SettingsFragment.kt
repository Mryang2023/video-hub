package com.videohub.tv.ui.settings

import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.videohub.tv.App
import com.videohub.tv.MainActivity
import com.videohub.tv.R
import com.videohub.tv.data.AppSettings
import com.videohub.tv.data.VersionInfo
import com.videohub.tv.databinding.DialogUpdateBinding
import com.videohub.tv.databinding.FragmentSettingsBinding
import com.videohub.tv.ui.focus.tvFocus
import com.videohub.tv.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** 设置页：服务器 / 源管理入口 / 播放设置（同步后端）/ 清历史 / 检查更新 */
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!

    private val app: App get() = requireActivity().application as App
    private val repo get() = app.repo

    private var settings = AppSettings()
    private var pendingUpdateUrl: String? = null
    private var updateJob: Job? = null

    /** 从「允许安装未知应用」设置页回来后继续更新流程 */
    private val installPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val url = pendingUpdateUrl
        pendingUpdateUrl = null
        if (!isAdded) return@registerForActivityResult
        if (url != null && ApkUpdater.canInstall(requireContext())) startUpdate(url)
        else requireContext().toast("未授权安装权限，无法自动更新")
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _b = FragmentSettingsBinding.bind(view)

        listOf(
            b.rowServer, b.rowChangeServer, b.rowSrc, b.rowHealth,
            b.rowRate, b.rowAuto, b.rowHist, b.rowClear, b.rowDiag, b.rowAbout,
        ).forEach { it.tvFocus(1.01f) }

        b.vServer.text = app.prefs.serverUrl?.trimEnd('/') ?: "未配置"
        load()
        checkServer()

        b.rowServer.setOnClickListener { checkServer() }
        b.rowChangeServer.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("修改服务器地址")
                .setMessage("将断开当前连接并回到连接页")
                .setPositiveButton("确定") { _, _ ->
                    app.prefs.serverUrl = null
                    (requireActivity() as? MainActivity)?.restartToConnect()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        b.rowSrc.setOnClickListener {
            (requireActivity() as? MainActivity)?.pushSub(SourceManagerFragment())
        }
        b.rowHealth.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("重置源健康记录")
                .setMessage("清空各播放源的失败计数，详情页将恢复默认选源。确定吗？")
                .setPositiveButton("重置") { _, _ ->
                    com.videohub.tv.util.SourceHealth.clear(requireContext())
                    bindHealth()
                    requireContext().toast("已重置")
                }
                .setNegativeButton("取消", null)
                .show()
        }
        b.rowRate.setOnClickListener {
            val next = RATES[(RATES.indexOf(settings.default_playback_rate) + 1).let {
                if (it >= RATES.size) 0 else it
            }]
            put("default_playback_rate", JsonPrimitive(next))
        }
        b.rowAuto.setOnClickListener {
            put("autoplay_next", JsonPrimitive(!settings.autoplay_next))
        }
        b.rowHist.setOnClickListener {
            put("record_history", JsonPrimitive(!settings.record_history))
        }
        b.rowClear.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("清空观看历史")
                .setMessage("所有设备的观看记录都会被清空，确定吗？")
                .setPositiveButton("清空") { _, _ ->
                    lifecycleScope.launch {
                        val api = repo.api ?: return@launch
                        runCatching { api.clearHistory() }
                        requireContext().toast("已清空")
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
        b.rowDiag.setOnClickListener {
            app.prefs.diagHud = !app.prefs.diagHud
            b.vDiag.text = if (app.prefs.diagHud) "开" else "关"
        }
        b.rowAbout.setOnClickListener { checkUpdate() }
    }

    private fun load() {
        val api = repo.api ?: return
        lifecycleScope.launch {
            runCatching { api.settings() }.getOrNull()?.let { settings = it }
            bind()
            // 源数量显示在「源管理」行
            val count = runCatching { api.sources().size }.getOrNull()
            b.vSrc.text = count?.let { "$it 个源" } ?: ""
        }
    }

    private fun bind() {
        val r = settings.default_playback_rate
        b.vRate.text = (if (r % 1.0 == 0.0) r.toInt().toString() else r.toString()) + "x"
        b.vAuto.text = if (settings.autoplay_next) "开" else "关"
        b.vHist.text = if (settings.record_history) "开" else "关"
        b.vAbout.text = "v$APP_VERSION · 按 OK 检查更新"
        b.vDiag.text = if (app.prefs.diagHud) "开" else "关"
        bindHealth()
    }

    private fun bindHealth() {
        b.vHealth.text = com.videohub.tv.util.SourceHealth.summary(requireContext())
    }

    private fun put(key: String, value: JsonPrimitive) {
        val api = repo.api ?: return
        lifecycleScope.launch {
            val result = try {
                api.updateSettings(buildJsonObject { put(key, value) })
            } catch (e: Exception) {
                android.util.Log.e("VHTV", "settings PUT failed", e)
                requireContext().toast("保存失败，请检查服务器")
                null
            }
            result?.let { settings = it }
            bind()
        }
    }

    private fun checkServer() {
        val api = repo.api ?: return
        b.vServer.text = (app.prefs.serverUrl?.trimEnd('/') ?: "") + " · 检查中…"
        lifecycleScope.launch {
            val ok = runCatching { api.health() }.getOrNull()?.status == "ok"
            b.vServer.text = (app.prefs.serverUrl?.trimEnd('/') ?: "") +
                if (ok) " · 在线" else " · 无法连接"
        }
    }

    /** 检查更新：有新版 → 确认弹窗 → 自动下载 → 拉起系统安装器 */
    private fun checkUpdate() {
        val api = repo.api ?: return
        lifecycleScope.launch {
            val tv = runCatching { api.versionInfo() }.getOrNull()?.tv
            when {
                tv == null || tv.version.isBlank() -> requireContext().toast("读取更新信息失败")
                ApkUpdater.isNewer(tv.version, APP_VERSION) ->
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("发现新版本 v${tv.version}")
                        .setMessage((tv.notes ?: "") + "\n\n是否立即更新？")
                        .setPositiveButton("立即更新") { _, _ -> startUpdate(tv.url) }
                        .setNegativeButton("以后再说", null)
                        .show()
                else -> requireContext().toast("已是最新版本 v$APP_VERSION")
            }
        }
    }

    private fun startUpdate(url: String) {
        val ctx = requireContext()
        if (!ApkUpdater.canInstall(ctx)) {
            pendingUpdateUrl = url
            requireContext().toast("请先允许 VideoHub TV 安装应用")
            installPermLauncher.launch(ApkUpdater.installPermissionIntent(ctx))
            return
        }
        val db = DialogUpdateBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle("正在下载更新")
            .setView(db.root)
            .setOnDismissListener { updateJob?.cancel() }
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()

        updateJob = app.appScope.launch {
            try {
                val dest = File(
                    ctx.getExternalFilesDir(null) ?: ctx.filesDir,
                    "videohub-tv-update.apk",
                )
                ApkUpdater.download(url, dest) { pct ->
                    withContext(Dispatchers.Main) {
                        db.progress.progress = pct
                        db.percent.text = "$pct%"
                    }
                }
                withContext(Dispatchers.Main) {
                    db.percent.text = "下载完成"
                    dialog.dismiss()
                    ApkUpdater.install(ctx, dest)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    ctx.toast("更新失败：${e.message ?: "网络错误"}")
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    companion object {
        const val APP_VERSION = "0.8.2"
        private val RATES = listOf(1.0, 1.25, 1.5, 2.0, 0.75, 3.0)
    }
}
