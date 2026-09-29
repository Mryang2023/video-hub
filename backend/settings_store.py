"""App settings — JSON file persistence with typed whitelist and defaults."""
import json
import threading

from config import DATA_DIR

SETTINGS_FILE = DATA_DIR / "settings.json"
_lock = threading.Lock()

DEFAULTS = {
    "default_playback_rate": 1.0,
    "autoplay_next": True,
    "record_history": True,
    "accent": "default",
}

# key -> allowed python type(s); anything else is ignored on write, reset on read
_ALLOWED = {
    "default_playback_rate": (int, float),
    "autoplay_next": bool,
    "record_history": bool,
    "accent": str,
}


def _read() -> dict:
    if SETTINGS_FILE.exists():
        try:
            return json.loads(SETTINGS_FILE.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            return {}
    return {}


def get_settings() -> dict:
    with _lock:
        data = {**DEFAULTS, **_read()}
    for k, v in list(data.items()):
        if k in _ALLOWED and not isinstance(v, _ALLOWED[k]):
            data[k] = DEFAULTS.get(k)
    return data


def update_settings(patch: dict) -> dict:
    with _lock:
        current = {**DEFAULTS, **_read()}
        for k, v in patch.items():
            if k in _ALLOWED and isinstance(v, _ALLOWED[k]):
                current[k] = v
        tmp = SETTINGS_FILE.with_suffix(".tmp")
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(current, f, ensure_ascii=False, indent=2)
        tmp.replace(SETTINGS_FILE)
        return current
