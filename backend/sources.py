"""Source CRUD — JSON file persistence with thread-safe locking."""
import json
import uuid
import threading
from datetime import datetime
from config import SOURCES_FILE
from models import Source, SourceCreate

_lock = threading.Lock()


def _read() -> list[dict]:
    if SOURCES_FILE.exists():
        with open(SOURCES_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    return []


def _write(data: list[dict]):
    """Write atomically via temp file to prevent corruption."""
    tmp = SOURCES_FILE.with_suffix(".tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    tmp.replace(SOURCES_FILE)


def list_all() -> list[dict]:
    with _lock:
        return _read()


def add(req: SourceCreate) -> dict:
    with _lock:
        sources = _read()
        src = {
            "id": uuid.uuid4().hex[:8],
            "name": req.name,
            "url": req.url,
            "site_count": 0,
            "added_at": datetime.now().isoformat(),
        }
        sources.append(src)
        _write(sources)
        return src


def delete(source_id: str) -> bool:
    with _lock:
        sources = _read()
        before = len(sources)
        sources = [s for s in sources if s["id"] != source_id]
        if len(sources) < before:
            _write(sources)
            return True
        return False


def update_site_count(source_id: str, count: int):
    with _lock:
        sources = _read()
        for s in sources:
            if s["id"] == source_id:
                s["site_count"] = count
                break
        _write(sources)
