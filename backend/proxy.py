"""API & stream proxy layer — optimized with shared client and caching."""
import asyncio
import copy
import re
import json
import time
import hashlib
import logging
import httpx
from urllib.parse import urljoin, urlparse, quote
from config import HTTP_TIMEOUT, HTTP_HEADERS, MAX_BODY_BYTES
from log import logger

# ── Shared HTTP Client ─────────────────────────────────
_client = None  # httpx.AsyncClient, shared across all requests


def _get_client() -> httpx.AsyncClient:
    """Get or create the shared async HTTP client (connection pooling)."""
    global _client
    if _client is None or _client.is_closed:
        _client = httpx.AsyncClient(
            timeout=HTTP_TIMEOUT,
            follow_redirects=True,
            limits=httpx.Limits(
                max_connections=100,
                max_keepalive_connections=40,
                keepalive_expiry=60,
            ),
            headers={"Connection": "keep-alive"},
        )
    return _client


async def close_client():
    """Shutdown the shared client (called on app shutdown)."""
    global _client
    if _client and not _client.is_closed:
        await _client.aclose()
        _client = None


# ── In-Memory TTL Cache ────────────────────────────────
# key -> (created_at, expires_at, value); TTL is kept out of the value itself
_cache: dict[str, tuple[float, float, dict]] = {}
_IMAGE_CACHE: dict[str, tuple[float, float, dict]] = {}
_CACHE_MAX = 500
_IMAGE_CACHE_MAX = 200
# Only cache images up to this size (bytes); bigger ones are streamed uncached
_IMAGE_CACHE_MAX_BYTES = 2 * 1024 * 1024


def _check_body_size(resp: httpx.Response):
    """Reject responses whose declared content-length exceeds MAX_BODY_BYTES."""
    cl = resp.headers.get("content-length", "")
    if cl.isdigit() and int(cl) > MAX_BODY_BYTES:
        raise ValueError(f"response too large: {cl} bytes")


def _check_body_downloaded(content: bytes):
    if len(content) > MAX_BODY_BYTES:
        raise ValueError(f"response too large: {len(content)} bytes")


def _cache_key(*args) -> str:
    raw = "|".join(str(a) for a in args)
    return hashlib.md5(raw.encode()).hexdigest()


def make_cache_key(*args) -> str:
    """Public key builder for other modules."""
    return _cache_key(*args)


def cache_get(key: str, store: dict | None = None):
    """Return a deep copy of a live cache entry (never the stored object itself)."""
    target = _cache if store is None else store
    entry = target.get(key)
    if entry is None:
        return None
    created_at, expires_at, val = entry
    if time.time() >= expires_at:
        del target[key]
        return None
    return copy.deepcopy(val)


def cache_set(key: str, val: dict, ttl: int = 60, store: dict | None = None):
    target = _cache if store is None else store
    max_entries = _CACHE_MAX if store is None else _IMAGE_CACHE_MAX
    if len(target) > max_entries:
        # Evict oldest
        oldest = sorted(target, key=lambda k: target[k][0])[:100]
        for k in oldest:
            del target[k]
    created_at = time.time()
    target[key] = (created_at, created_at + ttl, copy.deepcopy(val))


def cache_get_json(key: str):
    """Public accessor for other modules."""
    return cache_get(key)


def cache_set_json(key: str, val: dict, ttl: int = 60):
    """Public writer for other modules."""
    cache_set(key, val, ttl)


def cache_invalidate(prefix: str = ""):
    """Invalidate all cache entries, or those matching prefix."""
    global _cache
    if not prefix:
        _cache.clear()
        _IMAGE_CACHE.clear()
    else:
        _cache = {k: v for k, v in _cache.items() if not k.startswith(prefix)}


# ── Body Decoding ─────────────────────────────────────

def decode_body(content: bytes, content_type: str = "") -> str:
    """Decode response bytes: declared charset -> utf-8 -> gbk -> utf-8 replace."""
    charset = ""
    if content_type:
        m = re.search(r"charset=([\w\-]+)", content_type, re.IGNORECASE)
        if m:
            charset = m.group(1).strip("'\" ")
    text = None
    for enc in ([charset] if charset else []) + ["utf-8", "gbk"]:
        try:
            text = content.decode(enc)
            break
        except (UnicodeDecodeError, LookupError):
            continue
    if text is None:
        text = content.decode("utf-8", errors="replace")
    return text.lstrip("\ufeff\ufffe")


# ── API Proxy ──────────────────────────────────────────

async def proxy_api(api_url: str, params: dict, referer: str = "", cache_ttl: int = 0) -> dict:
    """Proxy a TVBox JSON API request with caching and retry."""
    # Check cache
    if cache_ttl > 0:
        ck = _cache_key(api_url, str(sorted(params.items())), referer)
        cached = cache_get(ck)
        if cached is not None:
            return cached

    headers = {**HTTP_HEADERS}
    if referer:
        headers["Referer"] = referer

    client = _get_client()
    for attempt in range(3):  # up to 3 attempts (initial + 2 retries)
        try:
            resp = await client.get(api_url, params=params, headers=headers)
            resp.raise_for_status()
            text = decode_body(resp.content, resp.headers.get("content-type", "")).strip()
            if text.startswith("{"):
                result = json.loads(text)
            elif "json" in resp.headers.get("content-type", ""):
                result = json.loads(text)
            else:
                return {"error": "non_json_response", "content_type": resp.headers.get("content-type", ""), "preview": text[:200]}

            if cache_ttl > 0 and "error" not in result:
                cache_set(ck, result, cache_ttl)
            return result
        except httpx.HTTPStatusError as e:
            if e.response.status_code >= 500 and attempt < 2:
                await asyncio.sleep(0.5 * (2 ** attempt))  # 0.5s, 1s
                continue
            logger.warning("proxy_api HTTP %s: %s", e.response.status_code, api_url)
            return {"error": f"HTTP {e.response.status_code}"}
        except Exception as e:
            if attempt < 2:
                await asyncio.sleep(0.5 * (2 ** attempt))
                continue
            msg = str(e) or type(e).__name__
            logger.warning("proxy_api failed: %s %s", api_url, msg)
            return {"error": msg}


async def search_site(api_url: str, keyword: str, page: int = 1, referer: str = "") -> dict:
    params = {"ac": "list", "wd": keyword, "pg": str(page)}
    return await proxy_api(api_url, params, referer, cache_ttl=120)  # 2min cache


async def search_with_covers(api_url: str, keyword: str, page: int = 1, referer: str = "") -> dict:
    """Search and batch-fetch cover images from detail API."""
    result = await search_site(api_url, keyword, page=page, referer=referer)

    if "error" in result or not result.get("list"):
        return result

    items = result["list"]
    ids = [str(v.get("vod_id", "")) for v in items if v.get("vod_id")]
    if not ids:
        return result

    batch_ids = ",".join(ids[:40])
    try:
        detail_result = await get_detail(api_url, batch_ids, referer)
    except Exception:
        detail_result = {}

    if isinstance(detail_result, dict) and detail_result.get("list"):
        pic_map = {}
        for d in detail_result["list"]:
            vid = str(d.get("vod_id", ""))
            pic = d.get("vod_pic", "")
            if vid and pic:
                pic_map[vid] = pic

        for v in items:
            vid = str(v.get("vod_id", ""))
            if vid in pic_map:
                v["vod_pic"] = pic_map[vid]

    return result


async def get_categories(api_url: str, page: int = 1, category_id: str = "", referer: str = "") -> dict:
    params = {"ac": "list", "pg": str(page)}
    if category_id:
        params["t"] = category_id
    ttl = 300 if not category_id else 120  # Homepage 5min, category 2min
    return await proxy_api(api_url, params, referer, cache_ttl=ttl)


async def get_detail(api_url: str, vod_id, referer: str = "") -> dict:
    params = {"ac": "detail", "ids": str(vod_id)}
    return await proxy_api(api_url, params, referer, cache_ttl=600)  # 10min cache


async def get_categories_with_covers(api_url: str, page: int = 1, category_id: str = "", referer: str = "") -> dict:
    """Get category list and batch-fetch cover images from detail API."""
    result = await get_categories(api_url, page=page, category_id=category_id, referer=referer)

    if "error" in result or not result.get("list"):
        return result

    # Collect IDs and batch-fetch detail for covers
    items = result["list"]
    ids = [str(v.get("vod_id", "")) for v in items if v.get("vod_id")]
    if not ids:
        return result

    # Batch request: most APIs support comma-separated IDs
    batch_ids = ",".join(ids[:40])  # Limit to 40 to avoid URL length issues
    try:
        detail_result = await get_detail(api_url, batch_ids, referer)
    except Exception:
        detail_result = {}

    if isinstance(detail_result, dict) and detail_result.get("list"):
        # Build a lookup map: vod_id -> vod_pic
        pic_map = {}
        for d in detail_result["list"]:
            vid = str(d.get("vod_id", ""))
            pic = d.get("vod_pic", "")
            if vid and pic:
                pic_map[vid] = pic

        # Merge covers and extra fields back into list items
        for v in items:
            vid = str(v.get("vod_id", ""))
            if vid in pic_map:
                v["vod_pic"] = pic_map[vid]

    return result


# ── Stream Proxy ───────────────────────────────────────

def rewrite_m3u8_text(text: str, url: str, referer: str = "") -> str:
    """Rewrite an m3u8 playlist so all key/map/segment URLs go through our proxy."""
    parsed = urlparse(url)
    base_url = f"{parsed.scheme}://{parsed.netloc}"
    url_dir = url.rsplit("/", 1)[0] + "/"
    ref_enc = quote(referer, safe="")

    lines = text.split("\n")
    new_lines = []

    for line in lines:
        line = line.strip()
        if not line:
            new_lines.append("")
            continue

        # EXT-X-KEY: rewrite key URI
        if line.startswith("#EXT-X-KEY") and "URI=" in line:
            match = re.search(r'URI="([^"]+)"', line)
            if match:
                key_url = _resolve_url(match.group(1), base_url, url_dir)
                proxied = f"/api/proxy/stream?url={quote(key_url, safe='')}&ref={ref_enc}"
                line = line.replace(f'URI="{match.group(1)}"', f'URI="{proxied}"')
            new_lines.append(line)
            continue

        # EXT-X-MAP: rewrite URI
        if line.startswith("#EXT-X-MAP") and "URI=" in line:
            match = re.search(r'URI="([^"]+)"', line)
            if match:
                seg_url = _resolve_url(match.group(1), base_url, url_dir)
                proxied = f"/api/proxy/stream?url={quote(seg_url, safe='')}&ref={ref_enc}"
                line = line.replace(f'URI="{match.group(1)}"', f'URI="{proxied}"')
            new_lines.append(line)
            continue

        if line.startswith("#"):
            new_lines.append(line)
            continue

        # Segment URL — rewrite to proxy
        if line.startswith("http") or line.startswith("//"):
            seg_url = line if line.startswith("http") else "https:" + line
        else:
            seg_url = urljoin(url_dir, line)
        proxied = f"/api/proxy/stream?url={quote(seg_url, safe='')}&ref={ref_enc}"
        new_lines.append(proxied)

    return "\n".join(new_lines)


async def proxy_m3u8(url: str, referer: str = "", timeout: float = 10) -> str:
    """Fetch m3u8 playlist and rewrite segment URLs through our proxy."""
    headers = {**HTTP_HEADERS}
    if referer:
        headers["Referer"] = referer

    # Cache rewritten playlists (segments don't change often)
    ck = _cache_key("m3u8", url, referer)
    cached = cache_get(ck)
    if cached is not None:
        return cached["_content"]

    client = _get_client()
    resp = await client.get(url, headers=headers, timeout=timeout)
    resp.raise_for_status()
    content = decode_body(resp.content, resp.headers.get("content-type", ""))

    result = rewrite_m3u8_text(content, url, referer)
    cache_set(ck, {"_content": result}, ttl=300)  # 5min cache
    return result


async def open_stream(url: str, referer: str = "", timeout: float = 15,
                      range_header: str | None = None) -> httpx.Response:
    """Open an upstream media response in streaming mode (caller owns closing).

    Used for video segment/key pass-through: bytes flow to the player as they
    arrive instead of being fully buffered in memory first, which cuts
    start/seek latency and removes per-segment memory spikes.
    """
    headers = {**HTTP_HEADERS}
    if referer:
        headers["Referer"] = referer
    if range_header:
        headers["Range"] = range_header
    client = _get_client()
    resp = await client.send(client.build_request("GET", url, headers=headers), stream=True)
    resp.raise_for_status()
    return resp


async def iter_stream(resp: httpx.Response, chunk_size: int = 256 * 1024):
    """Yield an upstream streaming response in chunks, always closing it."""
    try:
        async for chunk in resp.aiter_bytes(chunk_size):
            yield chunk
    finally:
        await resp.aclose()


async def proxy_image(url: str, referer: str = "") -> tuple[bytes, str]:
    """Proxy an image request to bypass hotlink protection. Returns (content, content_type)."""
    headers = {**HTTP_HEADERS}
    if referer:
        headers["Referer"] = referer

    # Check cache (images use a dedicated store with a smaller entry cap)
    ck = _cache_key("img", url, referer)
    cached = cache_get(ck, store=_IMAGE_CACHE)
    if cached is not None:
        return cached["_content"], cached["_type"]

    client = _get_client()
    resp = await client.get(url, headers=headers)
    resp.raise_for_status()
    _check_body_size(resp)
    ct = resp.headers.get("content-type", "image/jpeg")

    if len(resp.content) <= _IMAGE_CACHE_MAX_BYTES:
        # Cache for 30 minutes
        cache_set(ck, {"_content": resp.content, "_type": ct}, ttl=1800, store=_IMAGE_CACHE)
    return resp.content, ct


# ── Play URL Resolver ─────────────────────────────────

async def resolve_play_url(url: str, timeout: float = 10) -> dict:
    """Resolve a play page URL to an actual video stream URL."""
    # Already a direct stream URL?
    if re.search(r'\.(m3u8|mp4|mkv|avi|flv)(\?|$)', url, re.IGNORECASE):
        return {"url": url, "type": "hls" if ".m3u8" in url.lower() else "mp4"}

    # Check cache
    ck = _cache_key("resolve", url)
    cached = cache_get(ck)
    if cached is not None:
        return cached

    client = _get_client()
    try:
        resp = await client.get(url, headers=HTTP_HEADERS, timeout=timeout)
        resp.raise_for_status()
        text = decode_body(resp.content, resp.headers.get("content-type", ""))

        result = None
        # Find m3u8 URL
        m3u8_urls = re.findall(r'(https?://[^\s"\'<>]+\.m3u8[^\s"\'<>]*)', text)
        if m3u8_urls:
            result = {"url": m3u8_urls[0], "type": "hls"}
        else:
            mp4_urls = re.findall(r'(https?://[^\s"\'<>]+\.mp4[^\s"\'<>]*)', text)
            if mp4_urls:
                result = {"url": mp4_urls[0], "type": "mp4"}
            else:
                config_patterns = re.findall(r'(?:url|src|source|file)\s*[:=]\s*["\']([^"\']+)["\']', text)
                for u in config_patterns:
                    if "m3u8" in u or "mp4" in u:
                        resolved = urljoin(url, u)
                        result = {"url": resolved, "type": "hls" if "m3u8" in resolved else "mp4"}
                        break

        if result is None:
            result = {"error": "no_video_url_found", "page_length": len(text)}

        if "error" not in result:
            cache_set(ck, result, ttl=1800)  # 30min cache
        return result
    except Exception as e:
        return {"error": str(e)}


def _resolve_url(url: str, base_url: str, url_dir: str) -> str:
    if url.startswith("http://") or url.startswith("https://"):
        return url
    if url.startswith("//"):
        return "https:" + url
    if url.startswith("/"):
        return base_url + url
    return urljoin(url_dir, url)
