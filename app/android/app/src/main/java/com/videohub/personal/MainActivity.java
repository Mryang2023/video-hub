package com.videohub.personal;

import android.app.DownloadManager;
import android.app.PictureInPictureParams;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.webkit.WebView;
import androidx.activity.OnBackPressedCallback;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    private volatile boolean immersive = false;
    private volatile boolean userPlaying = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final WebView webView = bridge != null ? bridge.getWebView() : null;

        // Capacitor 桥 JS 只注入本地页面，远程站点拿不到 window.Capacitor；
        // 用 WebView 原生 JS 接口向所有页面提供沉浸全屏/常亮/画中画/外部播放等能力。
        if (webView != null) {
            webView.addJavascriptInterface(new AppNative(this), "AppNative");
        }

        // targetSdk 35 在 Android 15+ 上强制 edge-to-edge，页面会顶进状态栏/手势条，
        // 导致顶栏标题被状态栏遮挡、搜索按钮点不到。统一在 content 视图上用
        // 系统栏/刘海/键盘 insets 做 padding（沉浸全屏时清零，让画面铺满全屏）。
        final View content = findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            if (immersive) {
                v.setPadding(0, 0, 0, 0);
            } else {
                v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            }
            return WindowInsetsCompat.CONSUMED;
        });

        // 返回键：WebView 有历史则回退；播放页 CSS 全屏由前端 popstate 先行退出；无历史则退出应用
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    finish();
                }
            }
        });
    }

    /** 进入沉浸全屏并锁定方向（orientation: "landscape" / "portrait"），由 AppNative 调用。 */
    public void enterImmersive(final String orientation) {
        runOnUiThread(() -> {
            if ("portrait".equals(orientation)) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
            } else if ("landscape".equals(orientation)) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            }
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
            immersive = true;
            findViewById(android.R.id.content).setPadding(0, 0, 0, 0);
            WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(
                    getWindow(), getWindow().getDecorView());
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsetsCompat.Type.systemBars());
        });
    }

    /** 退出沉浸全屏，恢复系统栏、自动旋转与 insets padding，由 AppNative 调用。 */
    public void exitImmersive() {
        runOnUiThread(() -> {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            immersive = false;
            WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
            WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(
                    getWindow(), getWindow().getDecorView());
            controller.show(WindowInsetsCompat.Type.systemBars());
        });
    }

    public void setUserPlaying(boolean playing) {
        userPlaying = playing;
    }

    /** 进入画中画小窗（Android 8+）。 */
    public void enterPipMode() {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                enterPictureInPictureMode(new PictureInPictureParams.Builder().build());
            } catch (Exception ignored) {
            }
        }
    }

    /** 播放中退到桌面/切走 → 自动进入画中画小窗。 */
    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (userPlaying) {
            enterPipMode();
        }
    }

    public String getAppVersionName() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    /** 用系统 DownloadManager 下载新版本 APK。 */
    public void downloadUpdate(String url) {
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle("VideoHub 更新包");
            req.setDescription("下载完成后在通知栏点击安装");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "videohub-update.apk");
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            dm.enqueue(req);
        } catch (Exception ignored) {
        }
    }
}
