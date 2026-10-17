package com.videohub.personal;

import android.app.DownloadManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.webkit.JavascriptInterface;

/**
 * 暴露给 WebView 内页面（含远程站点）的原生能力接口，挂在 window.AppNative 上。
 * Capacitor 的桥 JS 只注入本地页面（addDocumentStartJavaScript 仅本地 origin），
 * 远程服务器页面拿不到 window.Capacitor，因此这里用 WebView 原生 JS 接口提供能力。
 */
public class AppNative {

    private final MainActivity activity;

    public AppNative(MainActivity activity) {
        this.activity = activity;
    }

    /** 进入沉浸全屏并锁定方向（orientation: "landscape" / "portrait"）。 */
    @JavascriptInterface
    public void enterImmersive(String orientation) {
        activity.enterImmersive(orientation);
    }

    /** 退出沉浸全屏，恢复系统栏与自动旋转。 */
    @JavascriptInterface
    public void exitImmersive() {
        activity.exitImmersive();
    }

    /** 播放时保持屏幕常亮。 */
    @JavascriptInterface
    public void setKeepAwake(boolean on) {
        activity.runOnUiThread(() -> {
            if (on) {
                activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });
    }

    /** 进入画中画小窗。 */
    @JavascriptInterface
    public void enterPip() {
        activity.runOnUiThread(activity::enterPipMode);
    }

    /** 同步播放状态给原生（用于退到桌面时自动进入画中画）。 */
    @JavascriptInterface
    public void setPlaying(boolean playing) {
        activity.setUserPlaying(playing);
    }

    /** 用系统外部播放器打开视频直链（弹起选择器：MX Player / VLC 等）。 */
    @JavascriptInterface
    public void playExternal(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse(url), "video/*");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception ignored) {
            // 没有可用的外部播放器
        }
    }

    /** 当前 APP 版本号（如 "1.3"）。 */
    @JavascriptInterface
    public String getAppVersion() {
        return activity.getAppVersionName();
    }

    /** 用系统 DownloadManager 下载新版本 APK，完成后通知栏点击安装。 */
    @JavascriptInterface
    public void downloadApk(String url) {
        activity.runOnUiThread(() -> activity.downloadUpdate(url));
    }
}
