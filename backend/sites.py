"""Fetch and parse TVBox source configs."""
import re
import json
from config import HTTP_HEADERS
from log import logger
from proxy import _get_client, decode_body


def strip_json_comments(text: str) -> str:
    """Remove // and /* */ comments from JSONC text (string-safe)."""
    result = []
    in_string = False
    in_block = False
    escape = False
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if in_block:
            if ch == '*' and i + 1 < n and text[i + 1] == '/':
                in_block = False
                i += 2
                continue
            i += 1
            continue
        if escape:
            result.append(ch)
            escape = False
            i += 1
            continue
        if ch == '\\' and in_string:
            result.append(ch)
            escape = True
            i += 1
            continue
        if ch == '"':
            in_string = not in_string
            result.append(ch)
            i += 1
            continue
        if not in_string and ch == '/' and i + 1 < n:
            nxt = text[i + 1]
            if nxt == '/':
                # Skip until end of line
                while i < n and text[i] != '\n':
                    i += 1
                continue
            if nxt == '*':
                in_block = True
                i += 2
                continue
        result.append(ch)
        i += 1
    return ''.join(result)


def _fallback_parse(raw: str) -> dict:
    """Fallback: extract sites from malformed JSONC using regex."""
    sites = []
    # Find all JSON objects that look like site entries
    pattern = re.compile(
        r'\{[^{}]*"key"\s*:\s*"([^"]*)"[^{}]*"name"\s*:\s*"([^"]*)"[^{}]*"api"\s*:\s*"([^"]*)"[^{}]*\}'
    )
    for m in pattern.finditer(raw):
        key, name, api = m.group(1), m.group(2), m.group(3)
        # Try to extract type
        type_match = re.search(r'"type"\s*:\s*(\d+)', m.group(0))
        site_type = int(type_match.group(1)) if type_match else 0
        searchable_match = re.search(r'"searchable"\s*:\s*(\d+)', m.group(0))
        searchable = int(searchable_match.group(1)) if searchable_match else 1
        sites.append({
            "key": key, "name": name, "api": api,
            "type": site_type, "searchable": searchable,
            "quickSearch": 0, "filterable": 0,
        })
    return {"sites": sites, "spider": "", "lives": []}


async def fetch_source_config(source_url: str, timeout: float = 10) -> dict:
    """Fetch a TVBox JSON source config and return parsed sites."""
    client = _get_client()
    resp = await client.get(source_url, headers=HTTP_HEADERS, timeout=timeout)
    resp.raise_for_status()
    # Decode with declared-charset -> utf-8 -> gbk fallback (some servers are GBK)
    raw = decode_body(resp.content, resp.headers.get("content-type", ""))

    # TVBox sources often use JSONC (JSON with comments)
    cleaned = strip_json_comments(raw)
    # Remove trailing commas before ] or } (common in JSONC)
    cleaned = re.sub(r',\s*([}\]])', r'\1', cleaned)

    try:
        data = json.loads(cleaned)
    except json.JSONDecodeError:
        logger.warning("source config parse failed, fallback to regex: %s", source_url)
        # Fallback: try to extract sites array with regex
        data = _fallback_parse(raw)

    sites = []
    for s in data.get("sites", []):
        sites.append({
            "key": str(s.get("key", "")),
            "name": s.get("name", ""),
            "api": s.get("api", ""),
            "type": s.get("type", 0),
            "searchable": s.get("searchable", 1),
            "quickSearch": s.get("quickSearch", 0),
            "filterable": s.get("filterable", 0),
        })

    return {
        "sites": sites,
        "spider": data.get("spider", ""),
        "lives": data.get("lives", []),
    }
