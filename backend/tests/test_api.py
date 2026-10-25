"""Smoke tests for the VideoHub API — no real network, no real data files.

Run from backend/: python -m pytest tests -q
"""
import json

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
    monkeypatch.setattr(main, "HISTORY_TV_FILE", tmp_path / "history.tv.json")
    monkeypatch.setattr(main, "FAVORITES_TV_FILE", tmp_path / "favorites.tv.json")
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


# ── Aggregate search (all + stream) ──────────────────────

def _sse_events(text: str) -> list:
    """Parse an SSE body into a list of data-payload dicts."""
    out = []
    for chunk in text.split("\n\n"):
        chunk = chunk.strip()
        if not chunk:
            continue
        line = next(l for l in chunk.split("\n") if l.startswith("data:"))
        out.append(json.loads(line[5:].strip()))
    return out


@pytest.fixture()
def _clean_agg_state():
    main._recent_searches.clear()
    main._source_breakers.clear()
    yield
    main._recent_searches.clear()
    main._source_breakers.clear()


def test_aggregate_search_empty_sources(client, monkeypatch, _clean_agg_state):
    monkeypatch.setattr(sources_mgr, "list_all", lambda: [])
    r = client.get("/api/search/all", params={"wd": "测试"})
    assert r.status_code == 200
    body = r.json()
    assert body["results"] == []
    assert body["cached"] is False
    # 全部失败的结果不写缓存：再查一次仍是 cached=False
    r2 = client.get("/api/search/all", params={"wd": "测试"})
    assert r2.json()["cached"] is False


def test_search_stream_empty_sources(client, monkeypatch, _clean_agg_state):
    monkeypatch.setattr(sources_mgr, "list_all", lambda: [])
    r = client.get("/api/search/stream", params={"wd": "测试"})
    assert r.status_code == 200
    assert r.headers["content-type"].startswith("text/event-stream")
    events = _sse_events(r.text)
    assert events[0]["type"] == "meta" and events[0]["total"] == 0
    assert events[-1]["type"] == "done"
    assert not any(e["type"] == "source" for e in events)


def test_aggregate_cache_replay(client, monkeypatch, _clean_agg_state):
    """成功结果 3 分钟内直接回放：all 与 stream 都不再打源。"""
    async def fake_one(src, wd, page, semaphore):
        return {"source_id": src["id"], "source_name": src["name"],
                "site_key": "k", "site_name": "站点", "api_url": "http://api.example/a.php",
                "ok": True, "error": None, "list": []}

    calls = {"n": 0}

    async def counting_one(src, wd, page, semaphore):
        calls["n"] += 1
        return await fake_one(src, wd, page, semaphore)

    monkeypatch.setattr(sources_mgr, "list_all",
                        lambda: [{"id": "s1", "name": "源1", "url": "https://example.com/c.json"}])
    monkeypatch.setattr(main, "search_one_source", counting_one)

    r1 = client.get("/api/search/all", params={"wd": "庆余年"}).json()
    assert r1["cached"] is False and calls["n"] == 1
    r2 = client.get("/api/search/all", params={"wd": "庆余年"}).json()
    assert r2["cached"] is True and calls["n"] == 1  # 命中缓存，源搜索不再被调用

    s = client.get("/api/search/stream", params={"wd": "庆余年"})
    events = _sse_events(s.text)
    assert calls["n"] == 1
    assert events[0] == {"type": "meta", "total": 1, "cached": True}
    assert events[1]["type"] == "source" and events[1]["result"]["source_id"] == "s1"
    assert events[-1]["type"] == "done" and events[-1]["cached"] is True

    # 大小写/空白归一后同样命中
    r3 = client.get("/api/search/all", params={"wd": " 庆余年 "}).json()
    assert r3["cached"] is True


def test_source_breaker_blocks_after_two_failures(_clean_agg_state):
    main._breaker_record("s1", ok=False)
    assert not main._breaker_blocked("s1")  # 单次失败不熔断
    main._breaker_record("s1", ok=False)
    assert main._breaker_blocked("s1")      # 连续 2 次失败 → 熔断
    main._breaker_record("s1", ok=True)
    assert not main._breaker_blocked("s1")  # 成功即恢复


def test_search_race_prefers_keyword_match(client, monkeypatch, _clean_agg_state):
    """劣质源无视 wd 回推荐列表时，竞速应让位给标题匹配的真结果。"""
    import asyncio

    import proxy

    async def fake_search(api_url, wd, page=1, referer=""):
        if "junk" in api_url:  # 无视关键词的站：先返回、内容无关
            return {"list": [{"vod_id": "1", "vod_name": "热门推荐A"},
                             {"vod_id": "2", "vod_name": "热门推荐B"}]}
        await asyncio.sleep(0.05)  # 正常源稍慢但结果相关
        return {"list": [{"vod_id": "9", "vod_name": "凡人修仙传"}]}

    monkeypatch.setattr(proxy, "search_with_covers", fake_search)
    monkeypatch.setattr(sources_mgr, "list_all",
                        lambda: [{"id": "s1", "name": "源1", "url": "https://example.com/c.json"}])
    cfg = {"sites": [
        {"key": "junk", "name": "垃圾站", "api": "http://junk.example/api", "type": 1, "searchable": 1},
        {"key": "good", "name": "好站", "api": "http://good.example/api", "type": 1, "searchable": 1},
    ]}
    proxy.cache_set_json(proxy.make_cache_key("site_cfg", "s1"), cfg, ttl=600)
    try:
        body = client.get("/api/search/all", params={"wd": "凡人修仙传"}).json()
        g = body["results"][0]
        assert g["ok"] and g["site_key"] == "good"
        assert g["list"][0]["vod_name"] == "凡人修仙传"
    finally:
        proxy.cache_invalidate()


# ── Client buckets (TV 与 Web 的历史/收藏独立) ───────────

def test_history_buckets_separated(client, _clean_agg_state):
    # TV 端写入（client=tv），web 端（默认桶）不可见
    assert client.post("/api/history?client=tv", json=_hist("ktv", "1")).json() == {"status": "ok"}
    assert client.get("/api/history").json() == []
    tv_records = client.get("/api/history", params={"client": "tv"}).json()
    assert [r["vod_id"] for r in tv_records] == ["1"]

    # web 端写入互不干扰，TV 端记录不被覆盖
    assert client.post("/api/history", json=_hist("kweb", "2")).json() == {"status": "ok"}
    assert [r["vod_id"] for r in client.get("/api/history").json()] == ["2"]
    assert [r["vod_id"] for r in client.get("/api/history", params={"client": "tv"}).json()] == ["1"]

    # 删除/清空也按桶生效
    assert client.delete("/api/history/ktv/1", params={"client": "tv"}).json() == {"status": "ok"}
    assert client.get("/api/history", params={"client": "tv"}).json() == []
    assert len(client.get("/api/history").json()) == 1
    assert client.delete("/api/history", params={"client": "web"}).json() == {"status": "ok"}
    assert client.get("/api/history").json() == []


def test_favorites_buckets_separated(client, _clean_agg_state):
    assert client.post("/api/favorites?client=tv", json=_hist("ktv", "7")).json() == {"status": "ok"}
    assert client.get("/api/favorites").json() == []
    assert client.get("/api/favorites", params={"client": "tv"}).json()[0]["vod_id"] == "7"
    # 收藏查重也分桶：同一部剧两端各自收藏
    assert client.get("/api/favorites/check", params={"site_key": "ktv", "vod_id": "7"}).json() == {"favorited": False}
    assert client.get("/api/favorites/check",
                      params={"site_key": "ktv", "vod_id": "7", "client": "tv"}).json() == {"favorited": True}
    assert client.post("/api/favorites", json=_hist("ktv", "7")).json() == {"status": "ok"}


def test_client_bucket_migration_moves_shared_data_to_tv(client, monkeypatch, tmp_path):
    """旧版两端共用一份：迁移把既有数据整体划给电视端，web 从空开始。"""
    import json as _json
    hist_file = tmp_path / "history.json"
    fav_file = tmp_path / "favorites.json"
    hist_file.write_text(_json.dumps([_hist("k1", "1")], ensure_ascii=False), encoding="utf-8")
    fav_file.write_text(_json.dumps([_hist("k1", "5")], ensure_ascii=False), encoding="utf-8")
    monkeypatch.setattr(main, "HISTORY_FILE", hist_file)
    monkeypatch.setattr(main, "FAVORITES_FILE", fav_file)
    monkeypatch.setattr(main, "HISTORY_TV_FILE", tmp_path / "history.tv.json")
    monkeypatch.setattr(main, "FAVORITES_TV_FILE", tmp_path / "favorites.tv.json")

    main._migrate_client_buckets()

    # 旧文件划给 TV 桶；默认桶为空，接口不报错
    assert not hist_file.exists() and not fav_file.exists()
    assert [r["vod_id"] for r in client.get("/api/history", params={"client": "tv"}).json()] == ["1"]
    assert client.get("/api/history").json() == []
    assert client.get("/api/favorites", params={"client": "tv"}).json()[0]["vod_id"] == "5"
    assert client.get("/api/favorites").json() == []

    # 幂等：再次执行不重复迁移、不报错
    main._migrate_client_buckets()
    assert [r["vod_id"] for r in client.get("/api/history", params={"client": "tv"}).json()] == ["1"]


def test_backup_restore_covers_tv_bucket(client, _clean_agg_state):
    assert client.post("/api/favorites?client=tv", json=_hist("ktv", "9")).json() == {"status": "ok"}
    backup = client.get("/api/backup").json()
    assert backup["favorites_tv"][0]["vod_id"] == "9"

    # 清空 TV 桶后恢复，数据回来
    assert client.delete("/api/favorites/ktv/9", params={"client": "tv"}).json() == {"status": "ok"}
    r = client.post("/api/restore", json={"favorites_tv": backup["favorites_tv"]}).json()
    assert r["status"] == "ok" and r["favorites"] == 1
    assert client.get("/api/favorites", params={"client": "tv"}).json()[0]["vod_id"] == "9"


# ── 拼音首字母猜测 ───────────────────────────────────────

def _seed_pinyin_index(monkeypatch):
    import pinyin_index
    monkeypatch.setattr(pinyin_index, "_index_cache", {"loaded_at": 1.0, "by_source": {
        "源1": {"site": "站", "titles": [
            {"n": "完美世界精编版", "i": "wmsjb", "p": "wanmeishijianbgb"},
            {"n": "无名之辈", "i": "wmzb", "p": "wumingzhibei"},
            {"n": "完美世界2024", "i": "wmsj2", "p": "wanmeishijie2024"},
            {"n": "斗罗大陆", "i": "dldl", "p": "douluodalu"},
        ]},
    }})
    monkeypatch.setattr(pinyin_index, "_cache_loaded", True)
    return pinyin_index


def test_pinyin_suggest_matching(client, monkeypatch):
    pi = _seed_pinyin_index(monkeypatch)
    # 前缀命中优先；query 为剥掉"精编版/2024"装饰后缀的核心名，
    # 同一核心词的多个变体只留一条
    assert pi.suggest("wmsj") == [{"name": "完美世界精编版", "query": "完美世界"}]
    assert pi.suggest("wm") == [
        {"name": "完美世界精编版", "query": "完美世界"},
        {"name": "无名之辈", "query": "无名之辈"},
    ]
    assert pi.suggest("wanmei") == [{"name": "完美世界精编版", "query": "完美世界"}]  # 全拼也能命中
    assert pi.suggest("dldl") == [{"name": "斗罗大陆", "query": "斗罗大陆"}]
    assert pi.suggest("xyz") == []
    assert pi.suggest("完美") == []  # 非纯字母走不了拼音猜测


def test_search_suggest_endpoint(client, monkeypatch):
    pi = _seed_pinyin_index(monkeypatch)
    monkeypatch.setattr(pi, "index_ready", lambda: True)
    r = client.get("/api/search/suggest", params={"wd": "wmsj"}).json()
    assert r["ready"] is True
    assert r["matches"] == [{"name": "完美世界精编版", "query": "完美世界"}]


def test_search_race_no_junk_for_letters_keyword(client, monkeypatch, _clean_agg_state):
    """纯字母关键词（拼音首字母）下，垃圾源回推荐列表也不采用——
    宁可该源报无结果，让拼音猜测接管。"""
    import proxy

    async def junk_search(api_url, wd, page=1, referer=""):
        return {"list": [{"vod_id": "1", "vod_name": "热门推荐A"}]}

    monkeypatch.setattr(proxy, "search_with_covers", junk_search)
    monkeypatch.setattr(sources_mgr, "list_all",
                        lambda: [{"id": "s1", "name": "源1", "url": "https://example.com/c.json"}])
    cfg = {"sites": [{"key": "junk", "name": "垃圾站", "api": "http://junk.example/api",
                      "type": 1, "searchable": 1}]}
    proxy.cache_set_json(proxy.make_cache_key("site_cfg", "s1"), cfg, ttl=600)
    try:
        body = client.get("/api/search/all", params={"wd": "wmsj"}).json()
        g = body["results"][0]
        assert not g["ok"] and g["list"] == []
    finally:
        proxy.cache_invalidate()
