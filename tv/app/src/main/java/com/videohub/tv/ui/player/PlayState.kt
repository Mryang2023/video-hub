package com.videohub.tv.ui.player

/** 播放队列：详情页填好，播放页读取（避免 Bundle 传大播放串） */
data class EpData(val name: String, val url: String)

data class PlayGroupData(val name: String, val episodes: List<EpData>)

data class PlayState(
    val siteKey: String,
    val siteName: String,
    val vodId: String,
    val vodName: String,
    val vodPic: String,
    val apiUrl: String,
    val groups: List<PlayGroupData>,
    var groupIndex: Int = 0,
    var episodeIndex: Int = 0,
    var resumeMs: Long = 0,
)

object PlayStore {
    var current: PlayState? = null
}
