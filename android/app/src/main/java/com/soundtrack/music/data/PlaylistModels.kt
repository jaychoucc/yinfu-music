package com.soundtrack.music.data

/**
 * 导入失败条目：网易云歌单导入时未能匹配到任何可播放音源的原曲。
 *
 * 只保留「够用来重新搜索」的最小信息（歌名 / 歌手 / 时长 / 来源歌单名），
 * 不持有任何网络对象。展示在「导入失败歌曲」内置歌单里，点击即用 title+artist 发起搜索。
 * 持久化由 [PlaylistStore] 手写 JSON（每项 title/artist/durationSec/fromPlaylistName），
 * 旧数据没有该数组时按空列表处理（向后兼容）。
 */
data class ImportMiss(
    val title: String,
    val artist: String,
    val durationSec: Int,
    val fromPlaylistName: String
)

/** 歌单条目：只持有「引用 + 加入时间」，歌曲元数据统一放在全局曲库（见 [PlaylistStore]）。
 *
 * 这样同一首歌即使被多张歌单引用，也只存一份元数据；回填直链时一处更新、处处生效。
 */
data class PlaylistEntry(
    val songKey: String,
    val addedAt: Long
)

/** 一张本地歌单。默认歌单 [builtin] = true，不可删不可改名。 */
data class LocalPlaylist(
    val id: String,
    var name: String,
    val builtin: Boolean,
    val createdAt: Long,
    val entries: MutableList<PlaylistEntry>,

    /** 该歌单导入时未能匹配到音源的原曲（仅自建歌单会有，默认/失败歌单恒为空）。 */
    val importMisses: MutableList<ImportMiss> = mutableListOf()
) {
    /** 歌曲数（供列表页 / 我的页摘要展示）。 */
    val count: Int get() = entries.size
}

/** 本地歌单系统的共享常量。 */
object PlaylistModels {

    /** 内置默认歌单 id。 */
    const val DEFAULT_PLAYLIST_ID = "default"

    /** 内置默认歌单名（同时是「保留名」，新建/重命名一律拒绝）。 */
    const val DEFAULT_PLAYLIST_NAME = "我喜欢的音乐"

    /** 内置「导入失败歌曲」歌单 id（恒在、不可删不可改名；本身不放歌曲条目）。 */
    const val FAILED_PLAYLIST_ID = "import_failed"

    /** 内置「导入失败歌曲」歌单名。 */
    const val FAILED_PLAYLIST_NAME = "导入失败歌曲"

    /** 存储 schema 版本；解析到未知版本会降级为「仅默认歌单 + 空曲库」。 */
    const val SCHEMA_VERSION = 1

    /** 歌单名长度上限（去首尾空格后）。 */
    const val MAX_NAME_LEN = 20

    /**
     * S3 → S4 传参 extra key。
     *
     * ⚠️ 故意与网易云远程歌单的 `"playlist_id"`（Long）区分开，避免语义混淆。
     */
    const val EXTRA_LOCAL_PLAYLIST_ID = "local_playlist_id"

    /** S3 → S4：歌单名（仅用于秒显标题，真正的名字仍以 Store 为准）。 */
    const val EXTRA_LOCAL_PLAYLIST_NAME = "local_playlist_name"

    /** 失败列表 → MainActivity：点击失败条目后要搜索的关键词（"歌名 歌手"）。 */
    const val EXTRA_SEARCH_KEYWORD = "search_keyword"

    /** 失败列表 → MainActivity：附带标记「切到搜索 Tab」（关键词非空时同义）。 */
    const val EXTRA_SEARCH_TAB = "search_tab"
}
