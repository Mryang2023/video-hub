"""VideoHub — FastAPI backend for TVBox source playback."""
import asyncio
import json
import time
from contextlib import asynccontextmanager
from datetime import datetime
from pathlib import Path
from fastapi import FastAPI, Query, HTTPException, BackgroundTasks, Body
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response

from config import HTTP_HEADERS, HISTORY_FILE, FAVORITES_FILE
from models import SourceCreate, WatchRecord, Favorite
from log import logger
import config
import security
import settings_store
import sources as sources_mgr
import sites
import proxy


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Manage shared resources lifecycle."""
    yield  # App is running
    await proxy.close_client()  # Cleanup on shutdown


app = FastAPI(title="VideoHub", version="0.2.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=config.CORS_ORIGINS,
    allow_methods=["*"],
    allow_headers=["*"],
)


def _check_url(url: str):
    """Validate a user-supplied proxy target (basic SSRF guard)."""
    ok, reason = security.validate_public_url(
        url, allow_private=config.ALLOW_PRIVATE_TARGETS
    )
    if not ok:
        raise HTTPException(400, reason)


# ── Async JSON file helpers ────────────────────────────

async def _read_json(path: Path, default=None):
    """Read a JSON file without blocking the event loop."""
    if path.exists():
        try:
            text = await asyncio.to_thread(path.read_text, encoding="utf-8")
            return json.loads(text)
        except (json.JSONDecodeError, OSError):
            return default if default is not None else []
    return default if default is not None else []


async def _write_json(path: Path, data):
    """Write a JSON file atomically without blocking the event loop."""
    tmp = path.with_suffix(".tmp")

    def _do_write():
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        tmp.replace(path)

    await asyncio.to_thread(_do_write)


# ── Health ──────────────────────────────────────────────
@app.get("/api/health")
def health():
    return {"status": "ok"}


# ── Sources ─────────────────────────────────────────────
@app.get("/api/sources")
def list_sources():
    return sources_mgr.list_all()


@app.post("/api/sources")
async def add_source(req: SourceCreate, background_tasks: BackgroundTasks):
    _check_url(req.url)
    src = sources_mgr.add(req)
    # Fetch site count in background, don't block the response
    background_tasks.add_task(_fetch_site_count, src["id"], req.url)
    return src


async def _fetch_site_count(source_id: str, url: str):
    """Background task to fetch site count for a source."""
    try:
        cfg = await sites.fetch_source_config(url)
        sources_mgr.update_site_count(source_id, len(cfg["sites"]))
    except Exception:
        logger.warning("fetch site count failed: %s", url, exc_info=True)


@app.delete("/api/sources/{source_id}")
def delete_source(source_id: str):
    if sources_mgr.delete(source_id):
        return {"status": "ok"}
    raise HTTPException(404, "Source not found")


@app.get("/api/sources/health")
async def sources_health():
    """Probe every source: config fetch + first usable site categories latency."""
    semaphore = asyncio.Semaphore(6)

    async def _probe(src: dict) -> dict:
        out = {
            "source_id": src["id"],
            "source_name": src["name"],
            "ok": False,
            "site_count": src.get("site_count", 0),
            "latency_ms": None,
            "error": None,
        }

        async def _run():
            cfg_key = proxy.make_cache_key("site_cfg", src["id"])
            cfg = proxy.cache_get_json(cfg_key)
            if cfg is None:
                cfg = await sites.fetch_source_config(src["url"])
                proxy.cache_set_json(cfg_key, cfg, ttl=600)
            site = next(
                (s for s in cfg.get("sites", [])
                 if s.get("api") and s.get("type", 0) in (0, 1)),
                None,
            )
            if not site:
                out["error"] = "无标准站点"
                return
            result = await proxy.get_categories(site["api"], page=1)
            if "error" in result:
                out["error"] = str(result["error"])
                return
            out["ok"] = True
            out["latency_ms"] = int((time.monotonic() - started) * 1000)
            out["site_count"] = len(cfg.get("sites", []))

        started = time.monotonic()
        async with semaphore:
            try:
                await asyncio.wait_for(_run(), timeout=10)
            except asyncio.TimeoutError:
                out["error"] = "timeout"
            except Exception as e:
                out["error"] = str(e) or type(e).__name__
        return out

    results = await asyncio.gather(*[_probe(s) for s in sources_mgr.list_all()])
    return {"results": list(results)}


# ── Sites ───────────────────────────────────────────────

@app.get("/api/sources/{source_id}/sites")
async def get_sites(source_id: str):
    # Check TTL cache via proxy module (unified cache, public API)
    key = proxy.make_cache_key("site_cfg", source_id)
    cached = proxy.cache_get_json(key)
    if cached is not None:
        return cached

    src_list = sources_mgr.list_all()
    src = next((s for s in src_list if s["id"] == source_id), None)
    if not src:
        raise HTTPException(404, "Source not found")
    try:
        # Use a dedicated 10s timeout for source config fetch
        cfg = await asyncio.wait_for(
            sites.fetch_source_config(src["url"]),
            timeout=10,
        )
        sources_mgr.update_site_count(source_id, len(cfg["sites"]))
        # Cache for 10 minutes using unified cache
        proxy.cache_set_json(key, cfg, ttl=600)
        return cfg
    except asyncio.TimeoutError:
        raise HTTPException(504, f"源地址超时: {src['url']}")
    except Exception as e:
        msg = str(e) or type(e).__name__
        raise HTTPException(502, f"获取源配置失败: {msg}")


@app.get("/api/site-lookup")
async def site_lookup(site_key: str = Query(...)):
    """Find which source/site serves a site_key (legacy history/favorites resume)."""
    for src in sources_mgr.list_all():
        try:
            cfg_key = proxy.make_cache_key("site_cfg", src["id"])
            cfg = proxy.cache_get_json(cfg_key)
            if cfg is None:
                cfg = await asyncio.wait_for(
                    sites.fetch_source_config(src["url"]), timeout=8
                )
                proxy.cache_set_json(cfg_key, cfg, ttl=600)
        except Exception:
            continue  # a dead source must not break the lookup
        for s in cfg.get("sites", []):
            if s.get("key") == site_key and s.get("api"):
                return {
                    "api_url": s["api"],
                    "site_key": s.get("key", ""),
                    "site_name": s.get("name", ""),
                    "source_id": src["id"],
                    "source_name": src["name"],
                }
    raise HTTPException(404, f"未找到站点: {site_key}")


# ── Site API Proxy ──────────────────────────────────────
@app.get("/api/site/categories")
async def get_categories(
    api_url: str = Query(...),
    page: int = Query(1),
    t: str = Query("", description="Category ID"),
):
    """Get video list with cover images (batch-fetched from detail API)."""
    _check_url(api_url)
    result = await proxy.get_categories_with_covers(api_url, page=page, category_id=t)
    if "error" in result:
        raise HTTPException(502, result["error"])
    return result


@app.get("/api/site/search")
async def search_videos(
    api_url: str = Query(...),
    wd: str = Query(...),
    page: int = Query(1),
):
    """Search videos with cover images."""
    _check_url(api_url)
    result = await proxy.search_with_covers(api_url, wd, page=page)
    if "error" in result:
        raise HTTPException(502, result["error"])
    return result


# ── Aggregate Search ────────────────────────────────────
@app.get("/api/search/all")
async def search_all_sources(
    wd: str = Query(..., min_length=1),
    page: int = Query(1),
):
    """Search every configured source concurrently, grouped by source.

    Each group carries the site (key/name/api) that served the search, so the
    frontend can open details or play episodes from that source directly.
    A slow or broken source never blocks or fails the whole request.
    """
    started = time.monotonic()
    src_list = sources_mgr.list_all()
    semaphore = asyncio.Semaphore(6)

    async def _search_one(src: dict) -> dict:
        base = {
            "source_id": src["id"],
            "source_name": src["name"],
            "site_key": "",
            "site_name": "",
            "api_url": "",
        }

        def _fail(err: str) -> dict:
            return {**base, "ok": False, "error": err, "list": []}

        async def _run() -> dict:
            # Reuse the cached site config when possible (same cache as /sites)
            cfg_key = proxy.make_cache_key("site_cfg", src["id"])
            cfg = proxy.cache_get_json(cfg_key)
            if cfg is None:
                cfg = await sites.fetch_source_config(src["url"])
                proxy.cache_set_json(cfg_key, cfg, ttl=600)

            site = next(
                (
                    s for s in cfg["sites"]
                    if s.get("api") and s.get("searchable", 1) and s.get("type", 0) in (0, 1)
                ),
                None,
            )
            if not site:
                return _fail("无可搜索站点")

            result = await proxy.search_with_covers(site["api"], wd, page=page)
            if "error" in result:
                return _fail(str(result["error"]))

            return {
                **base,
                "site_key": site.get("key", ""),
                "site_name": site.get("name", ""),
                "api_url": site["api"],
                "ok": True,
                "error": None,
                "list": (result.get("list") or [])[:12],
            }

        async with semaphore:
            try:
                return await asyncio.wait_for(_run(), timeout=8)
            except asyncio.TimeoutError:
                return _fail("timeout")
            except Exception as e:
                return _fail(str(e) or type(e).__name__)

    results = await asyncio.gather(*[_search_one(s) for s in src_list])
    return {
        "wd": wd,
        "results": list(results),
        "elapsed_ms": int((time.monotonic() - started) * 1000),
    }


@app.get("/api/site/detail")
async def get_video_detail(
    api_url: str = Query(...),
    ids: str = Query(...),
):
    """Get video detail (episodes, play URLs)."""
    _check_url(api_url)
    result = await proxy.get_detail(api_url, ids)
    if "error" in result:
        raise HTTPException(502, result["error"])
    return result


@app.get("/api/site/resolve")
async def resolve_play_url(
    url: str = Query(..., description="Play page URL to resolve"),
):
    """Resolve a play page URL to an actual video stream URL."""
    _check_url(url)
    result = await proxy.resolve_play_url(url)
    if "error" in result:
        raise HTTPException(502, result.get("error", "Resolve failed"))
    return result


# ── Stream Proxy ────────────────────────────────────────
@app.get("/api/proxy/m3u8")
async def proxy_m3u8_endpoint(
    url: str = Query(...),
    referer: str = Query("", alias="ref"),
):
    """Rewrite m3u8 playlist so all segments go through our proxy."""
    _check_url(url)
    try:
        content = await proxy.proxy_m3u8(url, referer=referer)
        return Response(
            content=content,
            media_type="application/vnd.apple.mpegurl",
            headers={
                "Access-Control-Allow-Origin": "*",
                "Cache-Control": "no-cache",
            },
        )
    except Exception as e:
        logger.warning("m3u8 proxy failed: %s %s", url, e)
        raise HTTPException(502, f"Failed to proxy m3u8: {e}")


@app.get("/api/proxy/stream")
async def proxy_stream_endpoint(
    url: str = Query(...),
    referer: str = Query("", alias="ref"),
):
    """Proxy a raw video segment / key file."""
    _check_url(url)
    try:
        content, content_type, is_m3u8 = await proxy.proxy_stream(url, referer=referer)

        if is_m3u8:
            # Already downloaded — rewrite in place, no second fetch
            rewritten = proxy.rewrite_m3u8_text(content, url, referer=referer)
            return Response(
                content=rewritten,
                media_type="application/vnd.apple.mpegurl",
                headers={"Access-Control-Allow-Origin": "*"},
            )

        return Response(
            content=content,
            media_type=content_type,
            headers={
                "Access-Control-Allow-Origin": "*",
                "Cache-Control": "public, max-age=3600",  # Browser cache segments 1h
            },
        )
    except Exception as e:
        logger.warning("stream proxy failed: %s %s", url, e)
        raise HTTPException(502, f"Stream proxy error: {e}")


@app.get("/api/proxy/image")
async def proxy_image(
    url: str = Query(...),
    referer: str = Query("", alias="ref"),
):
    """Proxy image requests to bypass third-party hotlink protection."""
    _check_url(url)
    try:
        content, content_type = await proxy.proxy_image(url, referer=referer)
        return Response(
            content=content,
            media_type=content_type,
            headers={
                "Access-Control-Allow-Origin": "*",
                "Cache-Control": "public, max-age=86400",  # Cache images 24h
            },
        )
    except Exception as e:
        logger.warning("image proxy failed: %s %s", url, e)
        raise HTTPException(502, f"Image proxy error: {e}")


# ── Watch History ───────────────────────────────────────
# Lock to serialize writes to the same file
_history_lock = asyncio.Lock()
_favorites_lock = asyncio.Lock()


@app.get("/api/history")
async def get_history():
    return await _read_json(HISTORY_FILE, [])


@app.post("/api/history")
async def save_history(record: WatchRecord):
    async with _history_lock:
        records = await _read_json(HISTORY_FILE, [])
        data = record.model_dump()
        data["updated_at"] = datetime.now().isoformat()

        # Upsert by site_key + vod_id, newest first
        key = f"{data['site_key']}_{data['vod_id']}"
        found = False
        for i, r in enumerate(records):
            if f"{r.get('site_key')}_{r.get('vod_id')}" == key:
                records.pop(i)
                records.insert(0, data)
                found = True
                break
        if not found:
            records.insert(0, data)

        # Keep max 200 records
        records = records[:200]
        await _write_json(HISTORY_FILE, records)
    return {"status": "ok"}


@app.delete("/api/history/{site_key}/{vod_id}")
async def delete_history(site_key: str, vod_id: str):
    async with _history_lock:
        records = await _read_json(HISTORY_FILE, [])
        records = [r for r in records if not (r.get("site_key") == site_key and str(r.get("vod_id")) == vod_id)]
        await _write_json(HISTORY_FILE, records)
    return {"status": "ok"}


@app.delete("/api/history")
async def clear_history():
    async with _history_lock:
        await _write_json(HISTORY_FILE, [])
    return {"status": "ok"}


# ── Favorites ───────────────────────────────────────────
@app.get("/api/favorites")
async def get_favorites():
    return await _read_json(FAVORITES_FILE, [])


@app.get("/api/favorites/check")
async def check_favorite(site_key: str = Query(...), vod_id: str = Query(...)):
    favs = await _read_json(FAVORITES_FILE, [])
    for f in favs:
        if f.get("site_key") == site_key and str(f.get("vod_id")) == vod_id:
            return {"favorited": True}
    return {"favorited": False}


@app.post("/api/favorites")
async def add_favorite(fav: Favorite):
    async with _favorites_lock:
        favs = await _read_json(FAVORITES_FILE, [])
        data = fav.model_dump()
        key = f"{data['site_key']}_{data['vod_id']}"
        for f in favs:
            if f"{f.get('site_key')}_{f.get('vod_id')}" == key:
                return {"status": "exists"}
        data["added_at"] = datetime.now().isoformat()
        favs.insert(0, data)
        await _write_json(FAVORITES_FILE, favs)
    return {"status": "ok"}


@app.delete("/api/favorites/{site_key}/{vod_id}")
async def delete_favorite(site_key: str, vod_id: str):
    async with _favorites_lock:
        favs = await _read_json(FAVORITES_FILE, [])
        favs = [f for f in favs if not (f.get("site_key") == site_key and str(f.get("vod_id")) == vod_id)]
        await _write_json(FAVORITES_FILE, favs)
    return {"status": "ok"}


# ── Settings ────────────────────────────────────────────
@app.get("/api/settings")
def get_app_settings():
    return settings_store.get_settings()


@app.put("/api/settings")
def update_app_settings(patch: dict = Body(...)):
    return settings_store.update_settings(patch)


# ── Backup / Restore ────────────────────────────────────
@app.get("/api/backup")
async def export_backup():
    data = {
        "exported_at": datetime.now().isoformat(),
        "sources": sources_mgr.list_all(),
        "favorites": await _read_json(FAVORITES_FILE, []),
        "settings": settings_store.get_settings(),
    }
    return Response(
        content=json.dumps(data, ensure_ascii=False, indent=2),
        media_type="application/json",
        headers={"Content-Disposition": "attachment; filename=videohub-backup.json"},
    )


@app.post("/api/restore")
async def restore_backup(payload: dict = Body(...)):
    """Merge sources (dedupe by url), favorites (dedupe by key) and settings."""
    added = {"sources": 0, "favorites": 0}

    urls = {s.get("url") for s in sources_mgr.list_all()}
    for s in payload.get("sources") or []:
        if s.get("url") and s["url"] not in urls:
            sources_mgr.add(SourceCreate(name=s.get("name") or "导入源", url=s["url"]))
            urls.add(s["url"])
            added["sources"] += 1

    async with _favorites_lock:
        favs = await _read_json(FAVORITES_FILE, [])
        keys = {f"{f.get('site_key')}_{f.get('vod_id')}" for f in favs}
        for f in payload.get("favorites") or []:
            if not f.get("site_key") or f.get("vod_id") is None:
                continue
            k = f"{f.get('site_key')}_{f.get('vod_id')}"
            if k not in keys:
                fav = dict(f)
                fav["added_at"] = f.get("added_at") or datetime.now().isoformat()
                favs.insert(0, fav)
                keys.add(k)
                added["favorites"] += 1
        await _write_json(FAVORITES_FILE, favs)

    if isinstance(payload.get("settings"), dict):
        settings_store.update_settings(payload["settings"])

    return {"status": "ok", **added}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host=config.HOST, port=config.PORT, reload=True)
