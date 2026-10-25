"""拼音首字母标题索引：支撑 wmsj → 完美世界 这类首字母搜索猜测。

资源站不支持拼音搜索，这里后台慢速爬取各源选中站点的分类列表，
把片名连同拼音首字母/全拼落盘到 data/title_index.json；
suggest() 对纯字母关键词做前缀/包含匹配，返回候选片名。
"""
import asyncio
import json
import re
import time
from pathlib import Path

from pypinyin import lazy_pinyin

from config import DATA_DIR
from log import logger
import proxy
import sites as sites_mgr
import sources as sources_mgr

INDEX_FILE = DATA_DIR / "title_index.json"
INDEX_TTL = 6 * 3600  # 每 6 小时后台重建
MAX_SITE_CANDIDATES = 5   # 每个源最多尝试前几个候选站点（前面的挂了往后找）
MAX_CATEGORIES = 10       # 每个站点最多爬多少个分类
MAX_PAGES_PER_CATEGORY = 25
MAX_TITLES_PER_SOURCE = 6000
_CRAWL_SEMAPHORE = asyncio.Semaphore(4)

# 内存索引：{"by_source": {源名: {"site": 站点名, "titles": [{"n","i","p"}]}}}
_index_cache: dict = {"loaded_at": 0.0, "by_source": {}}
_cache_loaded = False


def _initials(name: str) -> str:
    return "".join(p[0] for p in lazy_pinyin(name)).lower()


def _full_pinyin(name: str) -> str:
    return "".join(lazy_pinyin(name)).lower()


def index_ready() -> bool:
    _ensure_loaded()
    return any(v["titles"] for v in _index_cache["by_source"].values())


def _ensure_loaded():
    """首次调用时从磁盘加载索引（后台重建完成前先提供旧数据）。"""
    global _cache_loaded
    if _cache_loaded:
        return
    _cache_loaded = True
    if INDEX_FILE.exists():
        try:
            data = json.loads(INDEX_FILE.read_text(encoding="utf-8"))
            _index_cache["by_source"] = data.get("by_source") or {}
            _index_cache["loaded_at"] = data.get("built_at") or 0.0
        except (json.JSONDecodeError, OSError):
            logger.warning("title index file unreadable, ignoring")
    else:
        logger.info("title index not built yet, suggest() returns empty until then")


def _iter_titles():
    for src in _index_cache["by_source"].values():
        for t in src.get("titles") or []:
            yield t


def suggest(wd: str, limit: int = 20) -> list:
    """纯字母关键词 → 候选片名 [{name: 词条名, query: 实际搜索词}]。
    首字母/全拼做前缀/包含匹配，去重，前缀命中优先；
    query 是剥掉"精编版/2024"等装饰后缀的核心名（多源更容易命中）。"""
    kw = (wd or "").strip().lower()
    if not kw or not re.fullmatch(r"[a-z0-9]+", kw):
        return []
    _ensure_loaded()
    prefix, contains = [], []
    seen_names, seen_queries = set(), set()
    for t in _iter_titles():
        name = t.get("n") or ""
        if not name or name in seen_names:
            continue
        ini, full = t.get("i") or "", t.get("p") or ""
        if ini.startswith(kw) or full.startswith(kw):
            bucket = prefix
        elif kw in ini or kw in full:
            bucket = contains
        else:
            continue
        seen_names.add(name)
        query = t.get("c") or core_name(name)
        # 同一核心词的多个变体（精编版/速看版/2024…）只留一条，前端点选语义相同
        if query in seen_queries:
            continue
        seen_queries.add(query)
        bucket.append({"name": name, "query": query})
        if len(prefix) >= limit:
            break
    return (prefix + contains)[:limit]


# 片名尾部常见的"版本装饰词"：点选猜测词条时剥掉它们搜核心名，
# 否则"完美世界精编版"这种某站专属命名其他源多半搜不到
_NAME_SUFFIX = re.compile(
    r'(精编版|速看版|完整版|高清版|修复版|蓝光版|剧场版|动画版|动态漫画|静态漫画|'
    r'国语版|粤语版|抢鲜版|先行版|未删减版|无删减版|典藏版|重制版|真人版|番外篇|'
    r'特别篇|加长版|删减版|中文版|韩语版|英语版|\d{4})$'
)


def core_name(name: str) -> str:
    core = name.strip()
    prev = None
    while prev != core:
        prev = core
        core = _NAME_SUFFIX.sub('', core).strip(' -:：·．.')
    return core if len(core) >= 2 else name.strip()


def _enrich(names) -> list:
    out = []
    for n in names:
        out.append({"n": n, "i": _initials(n), "p": _full_pinyin(n), "c": core_name(n)})
    return out


async def _candidate_sites(cfg: dict) -> list:
    return [
        s for s in cfg.get("sites", [])
        if s.get("api") and s.get("searchable", 1) and s.get("type", 0) in (0, 1)
    ][:MAX_SITE_CANDIDATES]


async def _crawl_site(api_url: str) -> dict | None:
    """爬一个站点的分类列表，返回 {片名: None}（去重用）；站点挂了返回 None。"""
    first = await proxy.get_categories(api_url, page=1)
    if "error" in first or not first.get("class"):
        return None
    type_ids = [c.get("type_id") for c in first["class"] if c.get("type_id") is not None][:MAX_CATEGORIES]
    titles: dict = {}

    async def walk(tid):
        for pg in range(1, MAX_PAGES_PER_CATEGORY + 1):
            page = await proxy.get_categories(api_url, page=pg, category_id=str(tid))
            if "error" in page:
                return
            lst = page.get("list") or []
            if not lst:
                return
            for v in lst:
                name = (v.get("vod_name") or "").strip()
                if name:
                    titles[name] = None
                    if len(titles) >= MAX_TITLES_PER_SOURCE:
                        return
            if pg >= (page.get("pagecount") or 1):
                return

    await asyncio.gather(*[walk(tid) for tid in type_ids])
    return titles or None


async def build_index() -> dict:
    """逐源选一个可用站点爬分类列表建索引；失败的源跳过、保留旧数据。"""
    started = time.monotonic()
    new_by_source: dict = {}
    sem = _CRAWL_SEMAPHORE

    async def crawl_source(src: dict):
        async with sem:
            cfg_key = proxy.make_cache_key("site_cfg", src["id"])
            cfg = proxy.cache_get_json(cfg_key)
            if cfg is None:
                try:
                    cfg = await asyncio.wait_for(sites_mgr.fetch_source_config(src["url"]), timeout=10)
                    proxy.cache_set_json(cfg_key, cfg, ttl=600)
                except Exception as e:
                    logger.warning("pinyin index: source config failed %s: %s", src["name"], e)
                    return
            for site in await _candidate_sites(cfg):
                try:
                    titles = await asyncio.wait_for(_crawl_site(site["api"]), timeout=120)
                except Exception:
                    titles = None
                if titles:
                    new_by_source[src["name"]] = {
                        "site": site.get("name", ""),
                        "titles": _enrich(list(titles.keys())),
                    }
                    return  # 第一个能用的站点就够了

    await asyncio.gather(*[crawl_source(s) for s in sources_mgr.list_all()], return_exceptions=True)

    # 失败的源沿用旧索引，避免候选越建越少
    for name, old in _index_cache["by_source"].items():
        if name not in new_by_source and old.get("titles"):
            new_by_source[name] = old

    total = sum(len(v["titles"]) for v in new_by_source.values())
    _index_cache["by_source"] = new_by_source
    _index_cache["loaded_at"] = time.time()
    global _cache_loaded
    _cache_loaded = True

    def _write():
        tmp = INDEX_FILE.with_suffix(".tmp")
        tmp.write_text(json.dumps(
            {"built_at": _index_cache["loaded_at"], "by_source": new_by_source},
            ensure_ascii=False,
        ), encoding="utf-8")
        tmp.replace(INDEX_FILE)

    await asyncio.to_thread(_write)
    logger.info("pinyin index: %d sources, %d titles in %.1fs",
                len(new_by_source), total, time.monotonic() - started)
    return {"sources": len(new_by_source), "titles": total}
