package com.videohub.tv.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/**
 * TVBox 采集接口里 id 一会儿是数字一会儿是字符串，后端也按 str|int 透传，
 * 统一宽松反序列化成 String。
 */
object FlexStr : KSerializer<String> {
    override val descriptor =
        PrimitiveSerialDescriptor("FlexStr", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) =
        encoder.encodeString(value)

    override fun deserialize(decoder: Decoder): String =
        if (decoder is JsonDecoder) {
            when (val e = decoder.decodeJsonElement()) {
                is JsonPrimitive -> e.content
                else -> ""
            }
        } else decoder.decodeString()
}

@Serializable
data class Health(val status: String = "")

@Serializable
data class Status(val status: String = "")

@Serializable
data class Source(val id: String = "", val name: String = "", val url: String = "", val site_count: Int = 0)

@Serializable
data class SourceCreate(val name: String, val url: String)

@Serializable
data class SourceHealthItem(
    val source_id: String = "",
    val source_name: String = "",
    val ok: Boolean = false,
    val site_count: Int = 0,
    val latency_ms: Int? = null,
    val error: String? = null,
)

@Serializable
data class SourceHealthResult(val results: List<SourceHealthItem> = emptyList())

@Serializable
data class SiteInfo(
    val key: String = "",
    val name: String = "",
    val api: String = "",
    val type: Int = 0,
    val searchable: Int = 1,
)

@Serializable
data class SourceConfig(val sites: List<SiteInfo> = emptyList(), val spider: String? = null)

@Serializable
data class VodItem(
    @Serializable(with = FlexStr::class) val vod_id: String = "",
    val vod_name: String = "",
    val vod_pic: String = "",
    val vod_remarks: String? = null,
)

/** MacCMS 站点分类（响应里 class 数组的元素） */
@Serializable
data class VodClass(
    @SerialName("type_id") @Serializable(with = FlexStr::class) val typeId: String = "",
    @SerialName("type_name") val typeName: String = "",
)

@Serializable
data class VodPage(
    val list: List<VodItem> = emptyList(),
    @SerialName("class") val klass: List<VodClass> = emptyList(),
    val page: Int = 1,
    val pagecount: Int = 1,
)

@Serializable
data class VodDetail(
    @Serializable(with = FlexStr::class) val vod_id: String = "",
    val vod_name: String = "",
    val vod_pic: String = "",
    val vod_remarks: String? = null,
    val type_name: String? = null,
    val vod_year: String? = null,
    val vod_play_from: String = "",
    val vod_play_url: String = "",
    val vod_content: String = "",
)

@Serializable
data class DetailResult(val list: List<VodDetail> = emptyList())

@Serializable
data class SearchGroup(
    val source_id: String = "",
    val source_name: String = "",
    val site_key: String = "",
    val site_name: String = "",
    val api_url: String = "",
    val ok: Boolean = false,
    val error: String? = null,
    val list: List<VodItem> = emptyList(),
)

@Serializable
data class SearchAllResult(
    val wd: String = "",
    val results: List<SearchGroup> = emptyList(),
    val elapsed_ms: Int = 0,
)

@Serializable
data class SuggestItem(
    val name: String = "",
    val query: String = "",
)

@Serializable
data class SuggestResult(
    val wd: String = "",
    val ready: Boolean = false,
    val matches: List<SuggestItem> = emptyList(),
)

@Serializable
data class HistoryRecord(
    val site_key: String = "",
    val site_name: String = "",
    @Serializable(with = FlexStr::class) val vod_id: String = "",
    val vod_name: String = "",
    val vod_pic: String = "",
    val episode_index: Int = 0,
    val episode_name: String = "",
    val current_time: Double = 0.0,
    val duration: Double = 0.0,
    val play_url: String = "",
    val api_url: String = "",
    val updated_at: String = "",
)

@Serializable
data class Favorite(
    val site_key: String = "",
    val site_name: String = "",
    @Serializable(with = FlexStr::class) val vod_id: String = "",
    val vod_name: String = "",
    val vod_pic: String = "",
    val api_url: String = "",
    val added_at: String = "",
    val last_ep_count: Int = 0,
    val update_flag: Boolean = false,
    val update_gap: Int = 0,
    val last_checked_at: String? = null,
)

@Serializable
data class Favorited(val favorited: Boolean = false)

@Serializable
data class Resolved(val url: String = "", val type: String = "")

@Serializable
data class SiteLookup(
    val api_url: String = "",
    val site_key: String = "",
    val site_name: String = "",
    val source_id: String = "",
    val source_name: String = "",
)

@Serializable
data class AppSettings(
    val default_playback_rate: Double = 1.0,
    val autoplay_next: Boolean = true,
    val record_history: Boolean = true,
    val accent: String = "default",
)

@Serializable
data class TvVersion(
    val version: String = "",
    val url: String = "",
    val notes: String? = null,
)

@Serializable
data class VersionInfo(
    val version: String? = null,
    val url: String? = null,
    val notes: String? = null,
    val tv: TvVersion? = null,
)

// ── 直播 ──────────────────────────────────
@Serializable
data class LiveChannel(val name: String = "", val urls: List<String> = emptyList())

@Serializable
data class LiveGroup(val name: String = "", val channels: List<LiveChannel> = emptyList())

@Serializable
data class LiveChannels(val groups: List<LiveGroup> = emptyList())
