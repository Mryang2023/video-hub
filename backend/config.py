"""Global configuration."""
import os
from pathlib import Path

DATA_DIR = Path(__file__).parent / "data"
SOURCES_FILE = DATA_DIR / "sources.json"
HISTORY_FILE = DATA_DIR / "history.json"
FAVORITES_FILE = DATA_DIR / "favorites.json"

DATA_DIR.mkdir(exist_ok=True)

# ── Server binding (env-overridable; defaults keep current behaviour) ──
HOST = os.getenv("VIDEOHUB_HOST", "0.0.0.0")
PORT = int(os.getenv("VIDEOHUB_PORT", "8000"))
# Comma-separated list, or "*" for any origin
CORS_ORIGINS = os.getenv("VIDEOHUB_CORS_ORIGINS", "*").split(",")

# ── Security ──────────────────────────────────────────
# Allow proxy targets on loopback/private ranges (set 0 to block, at the cost
# of LAN-hosted sources). Default keeps existing behaviour.
ALLOW_PRIVATE_TARGETS = os.getenv("VIDEOHUB_ALLOW_PRIVATE", "1") not in ("0", "false", "False")
# Cap for proxied stream/image bodies, to avoid unbounded memory use
MAX_BODY_BYTES = int(os.getenv("VIDEOHUB_MAX_BODY_BYTES", str(50 * 1024 * 1024)))

# HTTP client settings
HTTP_TIMEOUT = 15
HTTP_HEADERS = {
    "User-Agent": "okhttp/3.12.11",
    "Accept": "application/json, text/plain, */*",
}
