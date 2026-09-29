"""Smoke tests for the VideoHub API — no real network, no real data files.

Run from backend/: python -m pytest tests -q
"""
import pytest
from fastapi.testclient import TestClient

import main
import sources as sources_mgr
import settings_store
from security import validate_public_url


async def _noop_fetch(*args, **kwargs):
    return None


@pytest.fixture()
def client(tmp_path, monkeypatch):
    # Redirect JSON storage to temp files; stub the source-config background fetch
    monkeypatch.setattr(main, "HISTORY_FILE", tmp_path / "history.json")
    monkeypatch.setattr(main, "FAVORITES_FILE", tmp_path / "favorites.json")
    monkeypatch.setattr(sources_mgr, "SOURCES_FILE", tmp_path / "sources.json")
    monkeypatch.setattr(settings_store, "SETTINGS_FILE", tmp_path / "settings.json")
    monkeypatch.setattr(main, "_fetch_site_count", _noop_fetch)
    return TestClient(main.app)


def test_health(client):
    assert client.get("/api/health").json() == {"status": "ok"}


def _hist(site_key="k1", vod_id="1", name="片名"):
    return {
        "site_key": site_key,
        "site_name": "站点",
        "vod_id": vod_id,
        "vod_name": name,
    }


def test_sources_crud(client):
    r = client.post("/api/sources", json={"name": "测试源", "url": "https://example.com/config.json"})
    assert r.status_code == 200
    src = r.json()
    assert src["name"] == "测试源" and src["id"]

    listed = client.get("/api/sources").json()
    assert any(s["id"] == src["id"] for s in listed)

    assert client.delete(f"/api/sources/{src['id']}").json() == {"status": "ok"}
    assert client.delete(f"/api/sources/{src['id']}").status_code == 404
    assert all(s["id"] != src["id"] for s in client.get("/api/sources").json())


def test_source_rejects_invalid_url(client):
    r = client.post("/api/sources", json={"name": "坏源", "url": "ftp://example.com/c.json"})
    assert r.status_code == 400


def test_history_upsert_moves_to_front_and_dedupes(client):
    for vid in ("1", "2"):
        assert client.post("/api/history", json=_hist("k1", vid)).json() == {"status": "ok"}

    # Updating an older record must move it to the front, without duplicates
    assert client.post("/api/history", json=_hist("k1", "1", "片名(更新)")).json() == {"status": "ok"}
    records = client.get("/api/history").json()
    assert [rec["vod_id"] for rec in records] == ["1", "2"]
    assert records[0]["vod_name"] == "片名(更新)"

    assert client.delete("/api/history/k1/1").json() == {"status": "ok"}
    assert [rec["vod_id"] for rec in client.get("/api/history").json()] == ["2"]


def test_favorites_dedupe_and_delete(client):
    assert client.post("/api/favorites", json=_hist("k1", "5")).json() == {"status": "ok"}
    assert client.post("/api/favorites", json=_hist("k1", "5")).json() == {"status": "exists"}
    favs = client.get("/api/favorites").json()
    assert len(favs) == 1 and favs[0]["added_at"]

    assert client.delete("/api/favorites/k1/5").json() == {"status": "ok"}
    assert client.get("/api/favorites").json() == []


def test_validate_public_url():
    ok, _ = validate_public_url("https://example.com/a.m3u8")
    assert ok

    ok, _ = validate_public_url("http://192.168.1.5/x", allow_private=False)
    assert not ok
    ok, _ = validate_public_url("http://192.168.1.5/x", allow_private=True)
    assert ok

    ok, _ = validate_public_url("ftp://example.com/x")
    assert not ok

    ok, _ = validate_public_url("http://localhost:8080/x", allow_private=False)
    assert not ok


def test_search_all_empty_sources(client, monkeypatch):
    monkeypatch.setattr(sources_mgr, "list_all", lambda: [])
    r = client.get("/api/search/all", params={"wd": "test"})
    assert r.status_code == 200
    body = r.json()
    assert body["results"] == []


# ── v0.3 additions ────────────────────────────────────

def test_favorites_check_endpoint(client):
    r = client.get("/api/favorites/check", params={"site_key": "k1", "vod_id": "9"})
    assert r.json() == {"favorited": False}
    client.post("/api/favorites", json=_hist("k1", "9"))
    r = client.get("/api/favorites/check", params={"site_key": "k1", "vod_id": "9"})
    assert r.json() == {"favorited": True}


def test_clear_history(client):
    client.post("/api/history", json=_hist("k1", "1"))
    client.post("/api/history", json=_hist("k2", "2"))
    assert len(client.get("/api/history").json()) == 2
    assert client.delete("/api/history").json() == {"status": "ok"}
    assert client.get("/api/history").json() == []


def test_history_new_fields_roundtrip(client):
    rec = _hist("k1", "5")
    rec.update({"play_url": "http://v/e1.m3u8", "api_url": "http://api/x"})
    client.post("/api/history", json=rec)
    saved = client.get("/api/history").json()[0]
    assert saved["play_url"] == "http://v/e1.m3u8"
    assert saved["api_url"] == "http://api/x"


def test_site_lookup_404_when_no_sources(client):
    r = client.get("/api/site-lookup", params={"site_key": "nope"})
    assert r.status_code == 404


def test_site_lookup_hit(client, monkeypatch):
    import proxy
    monkeypatch.setattr(
        sources_mgr, "list_all",
        lambda: [{"id": "s1", "name": "源一", "url": "http://src.example/c.json"}],
    )
    proxy.cache_set_json(
        proxy.make_cache_key("site_cfg", "s1"),
        {"sites": [{"key": "siteA", "name": "站A", "api": "http://api.example/a.php", "type": 1}]},
        ttl=600,
    )
    r = client.get("/api/site-lookup", params={"site_key": "siteA"})
    assert r.status_code == 200
    body = r.json()
    assert body["api_url"] == "http://api.example/a.php"
    assert body["source_id"] == "s1"


def test_settings_defaults_and_whitelist(client):
    body = client.get("/api/settings").json()
    assert body["default_playback_rate"] == 1.0
    assert body["autoplay_next"] is True
    assert body["record_history"] is True

    # valid keys apply; unknown keys and wrong types are ignored
    r = client.put("/api/settings", json={
        "default_playback_rate": 1.5,
        "autoplay_next": False,
        "evil_key": "x",
        "record_history": "yes-not-a-bool",
    })
    body = r.json()
    assert body["default_playback_rate"] == 1.5
    assert body["autoplay_next"] is False
    assert body["record_history"] is True  # wrong type ignored -> default kept
    assert "evil_key" not in body


def test_backup_and_restore(client):
    # seed data
    client.post("/api/sources", json={"name": "源A", "url": "https://example.com/a.json"})
    client.post("/api/favorites", json=_hist("k1", "7"))

    backup = client.get("/api/backup").json()
    assert backup["sources"] and backup["favorites"]
    assert "settings" in backup

    # restore into clean storage (same tmp fixture, merged by dedupe rules)
    r = client.post("/api/restore", json={
        "sources": [{"name": "源A副本", "url": "https://example.com/a.json"},   # dup url -> skipped
                    {"name": "源B", "url": "https://example.com/b.json"}],       # new -> added
        "favorites": [dict(_hist("k1", "7")),                                    # dup -> skipped
                      dict(_hist("k9", "8"))],                                   # new -> added
    })
    body = r.json()
    assert body["status"] == "ok"
    assert body["sources"] == 1
    assert body["favorites"] == 1

    urls = [s["url"] for s in client.get("/api/sources").json()]
    assert urls.count("https://example.com/a.json") == 1
    fav_keys = [f"{f['site_key']}_{f['vod_id']}" for f in client.get("/api/favorites").json()]
    assert "k9_8" in fav_keys


def test_sources_health_empty(client, monkeypatch):
    monkeypatch.setattr(sources_mgr, "list_all", lambda: [])
    r = client.get("/api/sources/health")
    assert r.status_code == 200
    assert r.json() == {"results": []}
