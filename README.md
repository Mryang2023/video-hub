# VideoHub 使用说明书

个人自用的 **TVBox 式影视聚合系统**：添加若干资源站配置（TVBox 源），即可在网页、手机 App、电视（原生 TV App）上聚合浏览、搜索、播放。后端一套 API 三端共用，观看历史与收藏按客户端分桶、互不干扰。

- 后端：Python FastAPI（`backend/`，端口 8000）
- 网页前端：Vue 3 + Vite + Tailwind（`frontend/`，构建产物由后端托管，日常无需 dev server）
- 电视端：Android TV 原生客户端，Kotlin + ExoPlayer（`tv/`）
- 手机端：Capacitor WebView 壳（`app/`），内含签名 keystore
- 存储：单用户 JSON 文件（`backend/data/*.json`），无数据库、无鉴权，**仅建议本地/可信局域网使用**

## 一、整体架构

```
┌─────────────┐   ┌───────────────────┐   ┌─────────────────────┐
│ Web 浏览器   │   │ 手机 APK(Capacitor)│   │ TV 原生 App(Kotlin)  │
│ frontend/   │   │ app/ → apk/*.apk  │   │ tv/ → apk/*-tv.apk  │
└──────┬──────┘   └────────┬──────────┘   └──────────┬──────────┘
       │        同一局域网，全部指向同一后端              │
       └──────────────┬────┴───────────────────────┘
                      ▼
        ┌────────────────────────────────┐
        │  后端 backend/ (FastAPI :8000)  │
        │  · 源/站点代理 + TTL 缓存 + 竞速  │
        │  · 聚合搜索(SSE 流式) + 源级熔断  │
        │  · 拼音首字母索引(后台自建)        │
        │  · 历史/收藏(按客户端分桶)         │
        │  · m3u8 重写/切片流式/图片代理     │
        │  · 静态托管 frontend/dist + apk/  │
        └───────────────┬────────────────┘
                        ▼
              各 TVBox 资源站（用户自配）
```

| 目录 | 内容 |
|---|---|
| `backend/` | FastAPI 后端：源管理、站点代理、聚合搜索、拼音索引、历史收藏、备份 |
| `frontend/` | 网页前端（浏览器与手机 App 共用同一套页面） |
| `tv/` | Android TV 原生客户端独立工程（设计细节见 `tv/DESIGN.md`） |
| `app/` | 手机 Android 壳工程；`docs/android-app.md` 有完整说明 |
| `apk/` | 对外发布的 APK 与版本清单 `version.json`（后端静态托管，供 App 内自更新） |

## 二、快速开始（服务端）

一键启动（清残留进程 → 检查依赖 → 起后端 → 轮询健康检查 → 打开浏览器）：

```bat
start.bat      :: 停止用 stop.bat；重复双击是安全的
```

手动启动：

```bat
:: 1. 构建前端（首次或前端改动后）
cd frontend && npm install && npm run build && cd ..

:: 2. 启动后端（自动托管 frontend/dist 与 apk/ 两个目录）
cd backend
pip install -r requirements.txt
python -m uvicorn main:app --host 0.0.0.0 --port 8000
```

浏览器打开 `http://<电脑IP>:8000`。首次使用：左上角「选择源 → 添加影视源」，粘贴 TVBox 配置订阅地址即可。

### 配置（backend/config.py，均可用环境变量覆盖）

| 配置 | 默认 | 环境变量 | 说明 |
|---|---|---|---|
| HOST / PORT | 0.0.0.0 / 8000 | `VIDEOHUB_HOST` / `VIDEOHUB_PORT` | 后端绑定地址与端口 |
| CORS_ORIGINS | `*` | `VIDEOHUB_CORS_ORIGINS` | 逗号分隔来源白名单 |
| ALLOW_PRIVATE_TARGETS | True | `VIDEOHUB_ALLOW_PRIVATE` | 允许代理内网/环回地址（设 0 封禁） |
| MAX_BODY_BYTES | 50MB | `VIDEOHUB_MAX_BODY_BYTES` | 流/图片代理响应体上限 |
| HTTP_TIMEOUT | 15s | — | 出站请求超时 |

## 三、电视端（TV App）

安装与更新三选一：

1. **App 内自更新**（推荐）：电视打开 VideoHub TV → 有新版本弹横幅 → 下载安装；
2. 电视浏览器访问 `http://<电脑IP>:8000/apk/videohub-tv.apk` 直接安装；
3. 开发者：`adb install -r tv/app/build/outputs/apk/release/app-release.apk`。

TV 端功能与操作要点：

- **焦点即切页**：顶部标签栏（首页/直播/搜索/收藏/历史/设置）获得焦点即切页，OK 键把焦点送进当前页；
- **搜索**：输入完整片名直接搜；输入纯字母（如 `wmsj`）给出**拼音首字母猜测**词条，按 ↑/↓ 选中、OK 即按该片名搜索（自动剥掉"精编版/2024"等后缀，按核心名搜索，多源更容易命中）；
- **历史/收藏独立**：电视端与网页端的观看历史、收藏**互不可见**（服务端按 `client=tv` 分桶存储，`data/history.tv.json` 等）；
- **追更**：收藏的剧有更新时列表出现角标，点开即清除（后端每小时自动检查一次）；
- **播放**：ExoPlayer 原生播放，支持倍速、自动连播；返回键逐层回退，首页连按两次退出；
- 交互设计细节见 `tv/DESIGN.md`。

## 四、网页端 / 手机端

- 搜索框旁「本源 / 全源」切换：**全源**（默认）并发搜索所有源，按源分组、流式逐源上屏，先到先显示；单源失败自动跳过不影响整体；
- 拼音首字母搜索：输入 `wmsj` 字母，结果区顶部出现「拼音猜测」片名按钮，点击即搜；
- 详情页自动解析多播放线路；「在其他源找这部剧」一键跨源换源；
- 直播页：m3u 订阅解析 + 频道分组，播放复用 m3u8 代理；
- 设置：默认倍速、自动连播、数据备份/恢复（JSON）；
- 手机 App 原生能力（手势/锁定/画中画/外部播放器/状态栏适配）见 `docs/android-app.md`。

## 五、后端 API 一览

| 分类 | 端点 | 说明 |
|---|---|---|
| 源管理 | `GET/POST/DELETE /api/sources` | 源增删查（TVBox 订阅配置，含 JSONC 容错） |
| 站点 | `GET /api/sources/{id}/sites` | 源内站点列表（10 分钟缓存） |
| 内容 | `GET /api/site/categories` / `detail` / `resolve` | 分类/详情/播放地址解析（列表自动补全封面） |
| 搜索 | `GET /api/site/search` | 单站点搜索 |
| 搜索 | `GET /api/search/all` | **全源聚合**：每源竞速前 10 个候选站点、关键词相关性优先、3 分钟结果缓存、源级熔断（连续失败 2 次跳过 10 分钟） |
| 搜索 | `GET /api/search/stream` | 聚合搜索 SSE 流式版（每源完成即推送，客户端中断即停止后续请求） |
| 搜索 | `GET /api/search/suggest` | 拼音首字母猜测（`wmsj → [{name:完美世界精编版, query:完美世界}]`） |
| 历史 | `GET/POST/DELETE /api/history` | 观看历史；`?client=tv` 走电视端独立桶 |
| 收藏 | `GET/POST/DELETE /api/favorites`、`/check`、`/check-updates`、`/clear-update` | 收藏与追更；同样支持 `client=tv` |
| 直播 | `GET /api/live/channels` | m3u 订阅解析（30 分钟缓存） |
| 代理 | `GET /api/proxy/m3u8` / `stream` / `image` | m3u8 重写、切片流式透传、图片防盗链代理 |
| 系统 | `GET /api/sources/health`、`GET/POST /api/backup`/`restore`、`GET/PUT /api/settings` | 源健康探测 / 备份恢复（含 TV 收藏桶）/ 设置 |

## 六、隐私与数据说明

- `backend/data/` **全部不入库**（.gitignore 已排除），均为本地个人数据：
  - `sources.json` 用户配置的源；`settings.json` 应用设置；`lives.json` 直播订阅；
  - `history.json` / `favorites.json`（Web 桶）、`history.tv.json` / `favorites.tv.json`（电视桶）——**观看记录，属个人隐私**；
  - `title_index.json` 拼音标题索引（后台自动构建，可随时删除重建）。
- **签名文件不入库**：`app/videohub-release.keystore`、`**/keystore.properties` 已 gitignore。丢失将无法覆盖升级，请自行网盘备份。
- 服务无鉴权，**勿暴露公网**；异地访问推荐 Tailscale（见 docs/android-app.md 第七节）。

## 七、发版流程

**前端 / 后端改动**（电视与手机无需重装）：

```bat
cd frontend && npm run build && cd ..
:: 重启后端即可
```

**TV 端发版**：

```bat
:: 1. 修改 tv/app/build.gradle 的 versionCode / versionName
cd tv && gradlew.bat assembleRelease
:: 2. 产物覆盖下载目录，并同步 apk/version.json 的 tv.version / notes
copy app\build\outputs\apk\release\app-release.apk ..\apk\videohub-tv.apk
:: 3. 电视端打开 App → 收到横幅 → 自更新
```

**手机端发版**：`cd app/android && gradlew.bat assembleRelease` → 覆盖 `apk/videohub.apk` → 更新 `version.json` 的 `version`/`notes`（详见 docs/android-app.md）。

## 八、自检与开发

```bash
cd backend
python -m pytest tests -q     # 27 个接口测试（覆盖全部主要端点）
python -m compileall -q .     # 语法检查
```

- 源健康探测：`GET /api/sources/health`（网页源菜单"一键检测"）；
- 拼音索引：后端启动 15 秒后后台自动构建（并发 4、限页数防打爆源站），每 6 小时刷新；爬取失败的源自动沿用旧索引；
- 熔断状态、聚合缓存、站点配置缓存均为内存 TTL，重启即清；
- `tv/dev/` 内有 TV 端回归脚本与验证清单。

## 九、性能机制

- **聚合搜索流式化**：`/api/search/stream` 逐源推送，最慢的源不再拖累整体（原"全部搜完才返回"最坏 20~30 秒 → 首屏 1 秒级）；重复搜索命中 3 分钟缓存，秒回；
- **候选站点竞速**：每源并发竞速前 10 个候选站点，任一返回与关键词相关的内容即采用；纯字母关键词不采用无关结果；
- **分段流式透传**：`/api/proxy/stream` 边收边发，起播/拖动更快，无每段内存峰值；
- **hls.js 按需加载**：播放器库进入 HLS 播放时动态 import；`backBufferLength=90` 修剪已播缓冲；
- **缓存体系**：API 列表/详情/搜索/m3u8/图片/站点探测均为内存 TTL 缓存；图片响应带 `Cache-Control: max-age=86400` 由 WebView 二次缓存。

## 十、已知限制

- 第三方资源站质量参差：失效站点由"竞速 + 熔断 + 流式"三层容错兜底，但内容可用性无法保证；源整体失效时在源菜单删除后重新添加；
- 拼音索引只覆盖各源当前选中站点的分类目录，冷门片可能不在索引里（完整中文片名搜索不受影响）；
- `/api/site/resolve` 为静态正则解析，不执行 JS，无法处理需要脚本渲染的播放页；
- 个别 H.265 源在手机 WebView 内可能无法解码（TV 端 ExoPlayer 基本无此问题）；
- 电脑休眠会导致服务中断，建议电源计划设为"不睡眠"；
- 单用户设计：无鉴权、CORS 默认全开，仅限可信局域网使用。
