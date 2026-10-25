package com.videohub.tv.data

import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** VideoHub 后端 REST 接口（与手机 APP / Web 共用同一套 API） */
interface Api {

    @GET("api/health")
    suspend fun health(): Health

    // ── 源 / 站点 ──────────────────────────────
    @GET("api/sources")
    suspend fun sources(): List<Source>

    @POST("api/sources")
    suspend fun addSource(@Body src: SourceCreate): Source

    @DELETE("api/sources/{id}")
    suspend fun deleteSource(@Path("id") id: String): Status

    @GET("api/sources/health")
    suspend fun sourcesHealth(): SourceHealthResult

    @GET("api/sources/{id}/sites")
    suspend fun sites(@Path("id") id: String): SourceConfig

    @GET("api/site-lookup")
    suspend fun siteLookup(@Query("site_key") siteKey: String): SiteLookup

    // ── 内容 ──────────────────────────────────
    @GET("api/site/categories")
    suspend fun categories(
        @Query("api_url") apiUrl: String,
        @Query("page") page: Int = 1,
        @Query("t") category: String = "",
    ): VodPage

    @GET("api/site/search")
    suspend fun search(
        @Query("api_url") apiUrl: String,
        @Query("wd") wd: String,
        @Query("page") page: Int = 1,
    ): VodPage

    @GET("api/search/all")
    suspend fun searchAll(@Query("wd") wd: String, @Query("page") page: Int = 1): SearchAllResult

    @GET("api/search/suggest")
    suspend fun suggest(@Query("wd") wd: String): SuggestResult

    @GET("api/site/detail")
    suspend fun detail(@Query("api_url") apiUrl: String, @Query("ids") ids: String): DetailResult

    @GET("api/site/resolve")
    suspend fun resolve(@Query("url") url: String): Resolved

    // ── 历史 ──────────────────────────────────
    @GET("api/history")
    suspend fun history(): List<HistoryRecord>

    @POST("api/history")
    suspend fun saveHistory(@Body record: HistoryRecord): Status

    @DELETE("api/history/{site_key}/{vod_id}")
    suspend fun deleteHistory(@Path("site_key") siteKey: String, @Path("vod_id") vodId: String): Status

    @DELETE("api/history")
    suspend fun clearHistory(): Status

    // ── 收藏 ──────────────────────────────────
    @GET("api/favorites")
    suspend fun favorites(): List<Favorite>

    @POST("api/favorites")
    suspend fun addFavorite(@Body fav: Favorite): Status

    @DELETE("api/favorites/{site_key}/{vod_id}")
    suspend fun removeFavorite(@Path("site_key") siteKey: String, @Path("vod_id") vodId: String): Status

    @GET("api/favorites/check")
    suspend fun checkFavorite(
        @Query("site_key") siteKey: String,
        @Query("vod_id") vodId: String,
    ): Favorited

    @POST("api/favorites/clear-update")
    suspend fun clearUpdateFlag(@Body payload: JsonObject): Status

    // ── 设置 ──────────────────────────────────
    @GET("api/settings")
    suspend fun settings(): AppSettings

    @PUT("api/settings")
    suspend fun updateSettings(@Body patch: JsonObject): AppSettings

    /** 升级信息（后端静态目录 apk/version.json，手机/TV 共用一份） */
    @GET("apk/version.json")
    suspend fun versionInfo(): VersionInfo

    // ── 直播 ──────────────────────────────
    @GET("api/live/channels")
    suspend fun liveChannels(): LiveChannels
}
