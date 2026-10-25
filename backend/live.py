"""Live TV — m3u 订阅拉取、解析、智能分组、TTL 缓存。

频道来源两类:
- subs: 远程 m3u/txt 订阅(社区维护,自动更新;单源失败不影响整体)
- custom: lives.json 里手工添加的固定频道(置顶展示)

分组规则:频道名含 CCTV/央视 → 「央视」;含 卫视 → 「卫视」;其余沿用
m3u 的 group-title(txt 的 #genre#)。组序:自定义 > 央视 > 卫视 > 其他。
"""
import asyncio
import json
import re
import threading
from config import LIVES_FILE, DATA_DIR
from log import logger
from proxy import _get_client, cache_get_json, cache_set_json

_lock = threading.Lock()

# 默认订阅:iptv-org 中国频道表(直连 CDN)+ vbskycn 社区源(GitHub 经反代)
DEFAULT_SUBS = [
    "https://iptv-org.github.io/iptv/countries/cn.m3u",
    "https://gh-proxy.com/https://raw.githubusercontent.com/vbskycn/iptv/master/tv/iptv4.txt",
]

_CACHE_KEY = "live:channels"
_CACHE_TTL = 1800  # 30 分钟:频道表不常变,少打上游

_URL_HEADS = ("http://", "https://")


def _norm_name(name: str) -> str:
    """频道名归一化用于同名聚合:去空格/横线/括号注记/清晰度词,CCTV-1/CCTV1 归一。"""
    n = name.upper()
    n = re.sub(r"[(（].*?[)）]", "", n)          # 去括号注记 (720p) / (1080p)
    n = re.sub(r"[-—–\s]", "", n)               # 去横线与空格
    n = re.sub(r"(超清|高清|标清|蓝光|HD|FHD|SD|4K|8K)$", "", n)
    return n


def _load_conf() -> dict:
    """读 lives.json;不存在则写入默认配置(可手工改文件加订阅/频道)。"""
    with _lock:
        if not LIVES_FILE.exists():
            DATA_DIR.mkdir(parents=True, exist_ok=True)
            conf = {"subs": DEFAULT_SUBS, "custom": []}
            tmp = LIVES_FILE.with_suffix(".tmp")
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(conf, f, ensure_ascii=False, indent=2)
            tmp.replace(LIVES_FILE)
            return conf
        with open(LIVES_FILE, "r", encoding="utf-8") as f:
            conf = json.load(f)
        conf.setdefault("subs", DEFAULT_SUBS)
        conf.setdefault("custom", [])
        return conf


# iptv-org 英文组 → 中文;vbskycn「XX频道」组归并到同名中文组
_GROUP_CN = {
    "general": "综合", "news": "新闻", "movies": "电影", "kids": "少儿",
    "animation;kids": "少儿", "animation": "少儿", "kids;lifestyle": "少儿",
    "sports": "体育", "documentary": "纪录", "education": "教育",
    "education;lifestyle;science": "教育", "education;science": "教育",
    "business": "财经", "lifestyle": "生活", "culture": "文化", "music": "音乐",
    "entertainment": "娱乐", "weather": "天气", "science": "科技", "shop": "购物",
    "religious": "其他", "legislative": "法制", "family": "生活",
    "general;travel": "综合", "undefined": "其他", "": "其他",
    "电影频道": "电影", "纪录频道": "纪录", "儿童频道": "少儿",
    "体育频道": "体育", "音乐频道": "音乐", "数字频道": "综合",
    "央视频道": "央视", "卫视频道": "卫视",
}

# 组名命中即整组丢弃(源文件顶部的元信息,不是频道)
_DROP_GROUPS = {"更新时间"}


def _smart_group(name: str, raw_group: str) -> str:
    n = name.upper().replace(" ", "").replace("-", "")
    if "CCTV" in n or "央视" in name:
        return "央视"
    if "卫视" in name:
        return "卫视"
    g = _GROUP_CN.get(raw_group.strip().lower())
    if g:
        return g
    return raw_group


def parse_playlist(text: str) -> list[dict]:
    """单遍扫描,同时兼容两种格式:
    - m3u:  #EXTINF...组属性,名字 / 下一行 url
    - txt:  组名,#genre# 声明分组;之后每行 名字,url
    """
    chans: list[dict] = []
    pending_name: str | None = None
    pending_group = ""
    txt_group = ""

    for raw in text.splitlines():
        s = raw.strip()
        if not s or s.startswith("#EXTM3U"):
            continue
        if s.startswith("#EXTINF"):
            g = re.search(r'group-title="([^"]*)"', s)
            pending_group = g.group(1) if g else ""
            pending_name = s.rsplit(",", 1)[-1].strip() or None
            continue
        if s.startswith("#"):
            continue
        if "," in s:
            left, right = (x.strip() for x in s.split(",", 1))
            # txt 分组声明行:组名,#genre#
            if right.startswith("#genre#"):
                txt_group = left
                continue
            if left and right.startswith(_URL_HEADS):
                # txt 频道行(EXTINF 的名字不含逗号+url 组合,不会误入)
                if pending_name:
                    # 上一行 EXTINF 名字恰含逗号的兜底:优先当 m3u url 行处理
                    chans.append({"name": pending_name, "url": s if s.startswith(_URL_HEADS) else right,
                                  "group": pending_group or txt_group})
                    pending_name = None
                else:
                    chans.append({"name": left, "url": right, "group": txt_group})
                continue
        if s.startswith(_URL_HEADS) and pending_name:
            chans.append({"name": pending_name, "url": s, "group": pending_group or txt_group})
            pending_name = None
    return chans


def _sort_group_key(item: tuple) -> tuple:
    g, chans = item
    order = {"自定义": 0, "央视": 1, "卫视": 2, "地方频道": 3, "电影": 4, "纪录": 5}
    return (order.get(g, 9), -len(chans), g)


async def fetch_channels() -> list[dict]:
    """拉全部订阅并归组。返回 [{name: 组名, channels: [{name, url}]}]。"""
    cached = cache_get_json(_CACHE_KEY)
    if cached is not None:
        return cached

    conf = _load_conf()
    client = _get_client()

    async def pull(url: str) -> list[dict]:
        try:
            r = await client.get(url, timeout=12, follow_redirects=True)
            r.raise_for_status()
            return parse_playlist(r.text)
        except Exception as e:  # 单源失败跳过
            logger.warning("live sub failed %s: %s", url, e)
            return []

    lists = await asyncio.gather(*[pull(u) for u in conf["subs"]])

    # 合并去重(同名频道聚合多源 URLs,失败可切下一个;自定义优先)
    merged: dict[str, list[dict]] = {}
    custom = [
        {"name": c["name"], "url": c["url"], "group": "自定义"}
        for c in conf["custom"] if c.get("name") and c.get("url")
    ]
    for ch in custom + [c for sub in lists for c in sub]:
        if "更新时间" in ch["name"] or ch.get("group", "") in _DROP_GROUPS:
            continue  # 源文件顶部的元信息行,不是频道
        key = _norm_name(ch["name"])
        if not key:
            continue
        g = _smart_group(ch["name"], ch.get("group", ""))
        bucket_list = merged.setdefault(g, [])
        hit = next((c for c in bucket_list if _norm_name(c["name"]) == key), None)
        if hit is None:
            bucket_list.append({"name": ch["name"], "urls": [ch["url"]]})
        else:
            if ch["url"] not in hit["urls"] and len(hit["urls"]) < 4:
                hit["urls"].append(ch["url"])

    groups = [
        {"name": g, "channels": chans}
        for g, chans in sorted(merged.items(), key=_sort_group_key)
    ]
    cache_set_json(_CACHE_KEY, groups, _CACHE_TTL)
    return groups
