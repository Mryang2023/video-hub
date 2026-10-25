# TV 端开发验证指南

不用拷 U 盘。两种方式，推荐两种都配好：**日常开发用模拟器（在电脑上看），验收用真机 adb 无线（装到电视上按遥控器试）**。

---

## 方式一：真机 adb 无线直连（最快、最真实）

一次配置，之后每次更新只需几秒：

1. **电视上打开开发者选项**（长虹 Android 9 通用做法）：
   - 设置 → 关于（关于本机）→ 找到「版本号」或「Build 号」→ **连按 7 次**，提示"已进入开发者模式"
   - 返回设置 → 开发者选项 → 打开 **「USB 调试」** 和 **「网络 ADB / 网络 USB 调试」**（不同批次叫法略有差异）
2. 查电视 IP：设置 → 网络 → 当前连接详情（和电脑同一 WiFi/网段）
3. 电脑上双击运行：

   ```
   dev\install-real-tv.bat 192.168.101.50    （换成你的电视 IP）
   ```

   脚本会：`adb connect` → 增量安装 APK → 自动启动应用。

4. 看运行日志（排查崩溃）：

   ```
   adb logcat -s VideoHubTV
   ```

> 提示：改动代码后重新编译安装一条龙：
> `gradlew assembleDebug && dev\install-real-tv.bat 192.168.101.50`

### 可选：scrcpy 把电视画面投到电脑

开源工具 [scrcpy](https://github.com/Genymobile/scrcpy)（解压即用）。`adb connect` 之后运行 `scrcpy -s 电视IP:5555`，电视画面就显示在电脑窗口里，**键盘方向键 = 遥控器方向键、Enter = OK、Backspace = 返回**，不用一直盯着电视。调试利器。

---

## 方式二：本机 Android TV 模拟器（已配好，不依赖电视）

和长虹同为 **Android 9（API 28）** 的官方 Android TV 镜像，AVD 名称 `videohub_tv`。模拟器窗口里键盘方向键就是遥控器。

启动（有窗口）：

```
emulator -avd videohub_tv -gpu swiftshader_indirect
```

启动后同样用 `adb install -r app-debug.apk` 安装，或直接 `gradlew installDebug`。

无头模式（后台跑，用于自动化验证/截图）：

```
emulator -avd videohub_tv -no-window -gpu swiftshader_indirect -no-audio -no-boot-anim
adb wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 1; done'
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.videohub.tv/.MainActivity
adb exec-out screencap -p > screen.png
```

> 模拟器已开启硬件加速（WHPX），首次冷启动 1~2 分钟，之后秒开（快照）。
> 模拟器没有真实遥控器的长按 OK，快捷菜单验证请用真机。

---

## 常见问题

- **adb connect 连不上**：电视的「网络ADB」开关没开；或电视休眠了，先按遥控器任意键唤醒
- **5555 端口不通**：部分长虹固件要插一次 U 盘触发调试授权；再不行用 `adb tcpip 5555`（需先 USB 线连一次，电视一般没有 USB-Host 调试口，所以优先走网络开关）
- **安装显示 INSTALL_FAILED_UPDATE_INCOMPATIBLE**：之前装过签名不同的同包名应用，先 `adb uninstall com.videohub.tv`

## 0.3.3 回归脚本（2026-10-24）
- `tv-regress2.sh`：遥控路径全链路（详情焦点→播放器 OSD 焦点链→选集）。注意 uiautomator dump 每次约 2-3s，
  会撞上播放器 5s OSD 自动隐藏，关键键序列必须连发后再统一 dump。

## 0.3.3 修复清单（均已模拟器验证）
1. 首页/搜索卡片点击无反应 — CardUi 尾随 lambda 绑到了 onLongClick（最后一个参数），onClick 落空。
2. 封面中横线 — scrim_bottom.xml 渐变方向写反（angle=90 实际 startColor 在底部），卡片下半整块压黑。
3. 详情页/播放器按钮遥控选不中 — tvFocus() 只做缩放动画，未设 isFocusable；布局里的 TextView 按钮默认不可聚焦，
   焦点一直留在覆盖层背后的首页卡片上（OK 键点穿到背后的卡片）。
4. 播放器按钮排焦点抖动 — 显式 nextFocus 链（进度条↓进按钮排、按钮↑回进度条、左右串联）。
5. 覆盖层焦点管理 — fullContainer/tabContainer 可聚焦，push 时立即抢焦点，返回后焦点交回标签页。

## 夜间自主迭代 · 第 2 轮（0.4.2，2026-10-24 晚）
- 源健康度记忆：SourceHealth（SharedPreferences 记各播放组连续失败次数，成功清零）；
  详情页 defaultGroupIndex 避开连续失败≥2 的源；设置页新增「源健康记录」行可重置
- 冷启动/回首页焦点直接落在 Hero（之前停在顶部标签栏，要先按下才能用）
- review 修复：分类货架并发拉取但固定顺序提交（行序不再随网络跳动）；
  openWithLookup 的 requireContext() 改 app.toast（异步回来时 fragment 可能已 detach）
- 验证：构建零错、无 FATAL；货架顺序 热门→电影→动漫；设置页源健康行正常；冷启动焦点=heroCard

## 夜间自主迭代 · 第 3 轮（0.4.3，2026-10-24 晚）
- 播放器 OSD：「下一集」按钮直接显示下一集集名（如「下一集 · 第67集」），自动连播前可预知
- 设置新增「按键/屏参诊断浮层」开关（Prefs.diagHud，默认开）：问题排查稳定后用户可自行关闭两个角落浮层
- review：hero 焦点抢占逻辑补 isAdded/_b 判空防御（已改，随下轮发版）；logcat 中 2 条 FATAL 属系统
  Live TV 应用 com.android.tv 自身崩溃，与本应用无关）
- 验证：构建零错；hero→详情→播放链路正常；「下一集 · 第67集」渲染确认；诊断开关行渲染确认

## 夜间自主迭代 · 第 4 轮（0.4.4，2026-10-24 深夜·收尾轮）
- 首页快照缓存（companion + 30s TTL）：load() 重构为「拉取快照 → render()」两段；
  切标签回来直接复用快照渲染，秒开且不重复请求（注意：缓存的是 DTO 而非 HomeItem，
  避免 lambda 捕获已销毁的旧 Fragment）
- 修缓存路径焦点时序：渲染太快 post 跑在 layout 前 → tryFocusHero() 延迟+重试直到 ViewHolder 就绪
- 随版带上第 3 轮遗留的 isAdded/_b 判空防御
- 验证：冷启动焦点=heroCard；从其他 tab 切回首页 1.5s 内上屏且焦点=heroCard；无 videohub 崩溃

### 夜间四轮总账（0.4.1 → 0.4.4）
0.4.1 手动重试恢复自动换源/分类行下键直达/首页并发提速
0.4.2 源健康记忆+详情避坏源/冷启动焦点直达 Hero/货架顺序固定
0.4.3 下一集按钮带集名/诊断浮层开关
0.4.4 首页 30s 缓存秒开/焦点时序重试

## 0.5.0 · 选集面板重设计（2026-10-25 早）
用户反馈"选集页面不能好好选择"（原设计：选集=退回详情页，来回跳转）。
- 播放器内直接弹出选集面板：底部圆角卡片 + 分组行（item_gtab 复用）+ 5 列集数网格（item_ep 复用）
- 焦点直达当前集（第66集实测）；方向键网格内自由移动；OK 换集并自动关面板（第66→77集实测生效）
- BACK 只关面板不退播放器（OnBackPressedCallback，面板可见才启用）
- OSD 互斥：面板开时 hideOsd/showOsd 均不抢焦点；换集后面板自动刷新高亮
- 踩坑记录：焦点抢格子必须先 scrollToPosition 再找 ViewHolder（远端位置的
  ViewHolder 要滚动布局后才存在）；grep -c 对单行 ui.xml 只能判断存在性不能计数
- 验证：面板开/焦点/移动/换集/BACK 全过；无崩溃；OSD 标题联动"下一集 · 第78集"

## 0.5.1 · 详情页右键导航 + 冷启动焦点真空修复（2026-10-25）
用户明确导航模型：继续播放 →右键→ 播放第1集 →右键→ 收藏 →右键→ 宫格(最近的集)。
- 移除三按钮 nextFocusRightId=eGrid（原设置会从继续播放直接跳宫格、跳过另两个按钮）
- 收藏 RIGHT 拦截 → focusNearestEpCell()：遍历可视格子找与收藏按钮垂直中心最近者聚焦
- 根因修复"冷启动焦点真空"：窗口 touch mode 下非 focusableInTouchMode 视图 requestFocus
  返回 false（cf=null 日志实证）→ tvFocus() 统一补 isFocusableInTouchMode=true，
  hero 抢焦点一次成功；HomeFragment 重试链对 requestFocus 返回值也校验
- 验证：继续播放(77)→播放第1集→收藏→宫格第71集 全链通过

## 0.5.2 · 方向键边界全面接管（2026-10-25）
用户反馈"到页面边界还有问题"。逐边界显式接管，不再依赖系统焦点搜索：
- 宫格第一列 LEFT → 收藏按钮；行尾 RIGHT → 下一行首格（focusEpAt 滚动+延迟聚焦）；
  最后一格 RIGHT 原地停住；首行 UP → 分类行第一个 tab
- 「继续播放」LEFT 停住（左边只有海报）
- **根因级修复：覆盖层(详情/播放/连接页)打开期间 tabbar 全部 isFocusable=false**
  （之前宫格/内容按 UP 会把焦点搜到被盖住的顶部标签上——"焦点消失"的另一机制）
- 播放器：进度条 ↑ 顶栏返回键、顶栏 ↓ 进度条显式互链
- 验证：LEFT 链 72→71→收藏→播放→继续→停；RIGHT 行尾 74→75 顺行；
  UP 狂按 18 次焦点稳停在 btnBack，无逃逸、无崩溃

## 0.5.3 · 标签焦点即切换（2026-10-25）
- 焦点到顶部标签直接 switchTab（OK 键仍有效）
- 配套：焦点在标签栏期间，首页 Hero/搜索页初始焦点不再抢焦点（可连续横移扫标签）；
  冷启动例外（userNavigated=false 时仍抢到 Hero，打开即可按 OK 续看）
- 验证：冷启动焦点 heroCard；UP 进标签栏后 RIGHT 逐个扫过，页面实时切换（历史→设置实测）

## 0.6.0 · 封面画质专项（2026-10-25）
用户再次反馈封面模糊（电视上更明显）。三个动作：
1. **修掉 Hero 二次放大**：HomeAdapter 还带着 .override(780,440)（夜间重构只清了卡片），
   780px 中间图再显示到 1040px = 白白多一次插值。去掉后按视图实际尺寸解码
2. **SharpenTransformation（新增）**：3x3 十字锐化卷积（边缘像素按实际邻居数降权防爆边），
   应用到 Hero(0.45)/卡片(0.35)/详情背景(0.45)；顺序必须 CenterCrop→Sharpen→RoundedCorners，
   否则会把圆角透明区烧黑
3. 受控 A/B：270x360 源图放大后，锐化边缘方差 371 万 vs 无锐化 148 万（2.5 倍锐度）；
   真机截图视觉评估无光晕/无明显噪点（注意：真机 A/B 因 pm clear 后首页内容变化不可比）
说明：源图 270px 的物理上限仍在，锐化是"放大软"的观感补偿，不能凭空造细节。
