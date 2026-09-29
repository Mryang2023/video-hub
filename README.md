# VideoHub

TVBox 风格的影视源聚合与播放工具:管理多个 TVBox JSON 源,浏览/搜索影片,解析并代理播放 m3u8 直播流与点播片段。

- 后端:Python FastAPI(`backend/`,端口 8000)
- 前端:Vue 3 + Vite + Tailwind CSS(`frontend/`,端口 5173)
- 存储:单用户 JSON 文件(`backend/data/*.json`),无数据库、无鉴权,**仅建议本地使用**

## 功能

- 多源管理:添加/删除 TVBox JSON(含 JSONC 注释容错),自动拉取站点列表
- 分类浏览、分页、封面代理(绕过防盗链)
- 单站搜索;**全源聚合搜索**(`全源` 模式,并发搜索所有源、按源分组展示,单源失败不影响整体)
- 播放:m3u8 重写代理(分段/密钥均经本机代理)、Referer 伪造、hls.js 播放
- 观看历史(自动置顶去重)与收藏

## 架构

```
浏览器 (frontend, :5173, vite proxy /api)
   └── FastAPI 后端 (backend, :8000)
         ├── proxy.py   API/流/图片代理 + TTL 内存缓存
         ├── sites.py   TVBox 源配置解析(JSONC/GBK 容错)
         ├── sources.py 源 CRUD(JSON 文件)
         ├── security.py URL 校验(基础 SSRF 防护)
         └── data/      sources.json / history.json / favorites.json
   └── 第三方影视源(HTTP)
```

## 快速开始

一键启动(后端 + 前端 + 自动打开浏览器):

```bat
start.bat
```

手动启动:

```bat
:: 后端
cd backend
pip install -r requirements.txt
python -m uvicorn main:app --host 0.0.0.0 --port 8000 --reload

:: 前端(另开终端)
cd frontend
npm install
npx vite --host
```

## 配置(config.py,均可用环境变量覆盖)

| 配置 | 默认 | 环境变量 | 说明 |
|---|---|---|---|
| HOST / PORT | 0.0.0.0 / 8000 | `VIDEOHUB_HOST` / `VIDEOHUB_PORT` | 后端绑定地址与端口 |
| CORS_ORIGINS | `*` | `VIDEOHUB_CORS_ORIGINS` | 逗号分隔来源白名单 |
| ALLOW_PRIVATE_TARGETS | True | `VIDEOHUB_ALLOW_PRIVATE` | 是否允许代理目标为内网/环回地址(供局域网源使用;设 0 可封禁) |
| MAX_BODY_BYTES | 50MB | `VIDEOHUB_MAX_BODY_BYTES` | 流/图片代理响应体上限 |
| HTTP_TIMEOUT | 15s | — | 出站请求超时 |

## API 一览

| 方法 | 路径 | 参数 | 说明 |
|---|---|---|---|
| GET | /api/health | — | 健康检查 |
| GET | /api/sources | — | 源列表 |
| POST | /api/sources | `{name, url}` | 添加源(URL 会校验) |
| DELETE | /api/sources/{id} | — | 删除源 |
| GET | /api/sources/{id}/sites | — | 源的站点配置(缓存 10 分钟) |
| GET | /api/site/categories | `api_url, page, t?` | 分类/列表(含封面) |
| GET | /api/site/search | `api_url, wd, page?` | 单站搜索 |
| GET | /api/search/all | `wd, page?` | **全源聚合搜索**(单源 8s 超时,并发 6) |
| GET | /api/site/detail | `api_url, ids` | 详情(剧集/播放地址) |
| GET | /api/site/resolve | `url` | 播放页 URL → 直链解析 |
| GET | /api/proxy/m3u8 | `url, ref?` | m3u8 重写代理 |
| GET | /api/proxy/stream | `url, ref?` | 分段/密钥代理(响应体 ≤ 50MB) |
| GET | /api/proxy/image | `url, ref?` | 图片代理(≤2MB 入缓存 30 分钟) |
| GET/POST | /api/history | — / WatchRecord | 观看历史(同片去重置顶,上限 200) |
| DELETE | /api/history/{site_key}/{vod_id} | — | 删除历史 |
| GET/POST | /api/favorites | — / Favorite | 收藏(重复添加返回 exists) |
| DELETE | /api/favorites/{site_key}/{vod_id} | — | 删除收藏 |

## 目录结构

```
video_hub/
├── start.bat / stop.bat     一键启停
├── README.md
├── backend/
│   ├── main.py              FastAPI 路由
│   ├── proxy.py             代理层 + 缓存
│   ├── sites.py / sources.py / security.py / config.py / log.py / models.py
│   ├── tests/               pytest 冒烟测试(python -m pytest tests -q)
│   └── data/                JSON 存储(运行时生成,不入库)
└── frontend/                Vue3 + Vite + Tailwind
```

## 已知限制

- 单用户设计:JSON 文件存储、无鉴权、CORS 默认全开,仅建议本机或可信局域网使用。
- `/api/site/resolve` 为静态正则解析,不执行 JS,无法处理需要脚本渲染的播放页。
- 聚合搜索每源返回前 12 条,暂不支持聚合模式翻页。
- 字面量级 SSRF 校验,不做 DNS 解析级防护。

## 维护建议

- 数据全部在 `backend/data/`(已加入 .gitignore),升级代码前建议手动备份该目录。
- 公益源接口经常失效,用源下拉的「一键检测」排查;源地址失效时更换配置 URL 即可。
