# VideoHub 安卓 APP 说明

个人自用安卓客户端（不上架），采用 **Capacitor WebView 壳 + 服务器同源托管** 方案：

- 后端 FastAPI 直接托管前端构建产物（`main.py` 末尾挂载 `StaticFiles`），整个站点是同源的
  `http://<电脑IP>:8000` 一个地址；
- APK 只是一个带图标的轻量壳：启动页设置/记住服务器地址 → 内嵌 WebView 打开该地址；
- 前端/后端任何改动只需改服务器，**APK 基本不用重发**。

工程位置：`app/`（`app/www/index.html` 是启动页，`app/android/` 是原生壳工程）。

## 一、安装到手机

前提：手机与电脑在同一 Wi-Fi；电脑已运行 `start.bat`（后端 8000 端口已放行防火墙）。

方式一（推荐）：手机浏览器访问

```
http://192.168.101.18:8000/apk/videohub.apk
```

下载后点击安装（系统会提示"未知来源应用"，允许即可）。

方式二：数据线连接，开启 USB 调试后：

```bat
adb install -r video_hub\apk\videohub.apk
```

## 二、首次启动

1. 桌面点开 **VideoHub** 图标；
2. 启动页已预填 `http://192.168.101.18:8000`，点"连接"；
3. 之后自动进入影视站，日常使用无需再配置。

**修改服务器地址**：在任意页面按系统返回键退到启动页（或清掉应用重新打开），
启动页可改地址后重新连接。电脑 IP 若变了，建议在路由器里给电脑做 DHCP 静态绑定。

## 三、APP 内的原生行为

| 行为 | 说明 |
|---|---|
| 状态栏适配 | 安卓 15+ 强制 edge-to-edge，原生层按系统栏/刘海/键盘 insets 给页面加 padding（沉浸全屏时自动清零铺满），标题栏不会被状态栏遮挡 |
| 返回键 | 有浏览历史 → 页面回退；播放页全屏中 → 先退出全屏；无历史 → 退出应用 |
| 全屏横屏 | APP 内为 CSS 全屏 + 原生沉浸：点全屏按钮/双击画面中部 → 隐藏状态栏/导航栏并按视频宽高比锁定横/竖屏；返回键先退全屏 |
| 播放手势 | 画面上横滑=快进退（带时间预览）、左半屏上下滑=亮度、右半屏上下滑=音量、长按=3 倍速（松开恢复） |
| 播放锁定 | 控制条上的锁按钮 → 锁定触控防误触，右上角只留解锁按钮 |
| 画中画 | 手动小窗按钮；播放中退到桌面/切走自动进入画中画 |
| 外部播放 | 播放页"外部播放器"按钮 → 弹系统选择器（MX Player / VLC 等硬解更强的播放器） |
| 屏幕常亮 | 播放中保持屏幕常亮，暂停/退出播放页即恢复 |
| APP 自更新 | 启动后对比 `/apk/version.json` 与内置版本，有新版显示横幅 → 下载更新 → 系统下载完成后通知栏安装 |
| 明文 HTTP | `AndroidManifest` 已开 `usesCleartextTraffic`（局域网自用必须） |

**原生通道架构**（维护须知）：Capacitor 的桥 JS 只注入本地页面（`addDocumentStartJavaScript`
仅本地 origin），服务器上的远程站点拿不到 `window.Capacitor`，因此站点内所有原生能力都走
MainActivity 注入的 `window.AppNative`（`JavascriptInterface`，见 `app/android/.../AppNative.java`）：
`enterImmersive(orientation)` / `exitImmersive()` / `setKeepAwake(bool)` / `enterPip()` /
`setPlaying(bool)` / `playExternal(url)` / `getAppVersion()` / `downloadApk(url)`。
不要用 Capacitor 插件 API 的方式给远程站点加能力，那是不生效的。

**发布新版本 APK 的流程**：
1. `cd app/android && gradlew.bat assembleRelease`
2. 复制 `app-release.apk` 到 `video_hub/apk/videohub.apk`
3. 更新 `video_hub/apk/version.json` 里的 `version`（必须与 build.gradle 的 versionName 一致）和 `notes`
4. 手机端打开 APP 即会收到更新横幅，点击下载安装

## 四、更新

- **前端/后端更新**：改完代码 → `cd frontend && npm run build` → 重启 `start.bat` 即可，手机端无需重装；
- **壳/启动页/图标更新**：`cd app && npx cap sync android`，再重新打包（见下节），
  然后 `copy` 新 APK 覆盖 `video_hub\apk\videohub.apk`，手机端重新下载安装即可覆盖升级
  （签名一致可直接覆盖，历史/收藏都在服务器上不会丢）。

## 五、重新打包 APK

环境要求（本机已就绪）：

- JDK 21（`JAVA_HOME` 已设）
- Android SDK：`C:\Users\Administrator\AppData\Local\Android\Sdk`
  （`ANDROID_HOME` 已通过 `setx` 写入用户环境变量；`app/android/local.properties` 亦已指向）
- 国内镜像已配置：Gradle 发行包走腾讯镜像（`gradle-wrapper.properties`），
  Maven 依赖走阿里云镜像（`app/android/build.gradle`），换网络环境可自行改回官方源

打包命令：

```bat
cd video_hub\app\android
gradlew.bat assembleRelease        :: 正式签名包 → app\build\outputs\apk\release\app-release.apk
gradlew.bat assembleDebug          :: 调试包
```

打包完成后把 `app-release.apk` 复制到 `video_hub\apk\videohub.apk` 供手机下载。

## 六、签名 keystore（重要）

- 位置：`app/videohub-release.keystore`，凭据在 `app/android/keystore.properties`
  （两者均已 gitignore，**请自行备份一份到网盘/移动硬盘**）；
- 有效期 30 年，别名 `videohub`；
- 丢失后果：无法覆盖升级，只能卸载重装（观看历史/收藏存在服务器上，不会丢）。

## 七、外网访问（可选）

在家以外看片需要让手机能连到家里服务器，推荐 **Tailscale**（零公网暴露、免端口转发）：

1. 电脑和手机都安装 Tailscale 并登录同一账号；
2. APP 启动页把地址改成电脑的 Tailscale IP（如 `http://100.x.y.z:8000`）；
3. 完成， anywhere 都能用。

不要把 8000 端口直接暴露到公网（服务无鉴权）。

## 八、已知限制

- **第三方资源站经常失效**（域名更换/被墙），表现为某站点打不开或搜不到东西——这不是 APP 的问题。
  全源搜索已内置故障转移（每源并发竞速前 5 个候选站点，任一可用即出结果）；
  某个站点打不开时可在左侧换站点，或直接用「全源」搜索（默认）。
- 源配置本身失效（如 404）时全源搜索会明确提示"源可能已失效"，建议在源的菜单里删除后重新添加；
- 个别 **H.265/HEVC** 编码的源在 WebView 内可能无法解码（绝大多数 TVBox 源是 H.264，不受影响；
  如确实遇到，后续可换 ExoPlayer 原生播放器插件）；
- 电脑休眠会导致服务中断，建议电源计划设置为"不睡眠"；
- 启动页预填的默认 IP 是打包时的电脑局域网 IP，变了可在启动页修改。
