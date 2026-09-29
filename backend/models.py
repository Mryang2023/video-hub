"""Pydantic models."""
from pydantic import BaseModel
from typing import Optional


class SourceCreate(BaseModel):
    name: str
    url: str


class Source(SourceCreate):
    id: str
    site_count: int = 0


class SiteInfo(BaseModel):
    key: str
    name: str
    api: str
    type: int = 0  # 0=xml, 1=json
    searchable: int = 1
    quickSearch: int = 0
    filterable: int = 0


class SourceDetail(BaseModel):
    id: str
    name: str
    url: str
    sites: list[SiteInfo]
    spider: Optional[str] = None
    lives: list[dict] = []


class WatchRecord(BaseModel):
    site_key: str
    site_name: str
    vod_id: str | int
    vod_name: str
    vod_pic: str = ""
    episode_index: int = 0
    episode_name: str = ""
    current_time: float = 0
    duration: float = 0
    play_url: str = ""  # current episode URL (new records) for one-click resume
    api_url: str = ""  # site API (new records) so resume needs no lookup
    updated_at: str = ""


class Favorite(BaseModel):
    site_key: str
    site_name: str
    vod_id: str | int
    vod_name: str
    vod_pic: str = ""
    api_url: str = ""
    added_at: str = ""
