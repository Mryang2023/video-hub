"""URL validation — basic SSRF guard for user-supplied proxy targets."""
import ipaddress
from urllib.parse import urlparse

# Loopback / private / link-local ranges (literal host check only, no DNS)
_PRIVATE_NETS = [
    ipaddress.ip_network("0.0.0.0/8"),
    ipaddress.ip_network("127.0.0.0/8"),
    ipaddress.ip_network("10.0.0.0/8"),
    ipaddress.ip_network("172.16.0.0/12"),
    ipaddress.ip_network("192.168.0.0/16"),
    ipaddress.ip_network("169.254.0.0/16"),
    ipaddress.ip_network("::1/128"),
    ipaddress.ip_network("fc00::/7"),
    ipaddress.ip_network("fe80::/10"),
]


def _is_private_host(host: str) -> bool:
    if not host:
        return True
    h = host.lower()
    if h == "localhost" or h.endswith(".localhost"):
        return True
    try:
        ip = ipaddress.ip_address(h.strip("[]"))
        return any(ip in net for net in _PRIVATE_NETS)
    except ValueError:
        return False


def validate_public_url(url: str, allow_private: bool = True) -> tuple[bool, str]:
    """Basic SSRF check for user-supplied URLs.

    Literal host check only (no DNS resolution). Returns (ok, reason).
    """
    try:
        parsed = urlparse(url)
    except ValueError:
        return False, "URL 解析失败"
    if parsed.scheme not in ("http", "https"):
        return False, f"不支持的协议: {parsed.scheme or '(空)'}"
    host = parsed.hostname or ""
    if not host:
        return False, "URL 缺少主机名"
    if not allow_private and _is_private_host(host):
        return False, f"禁止访问内网地址: {host}"
    return True, ""
