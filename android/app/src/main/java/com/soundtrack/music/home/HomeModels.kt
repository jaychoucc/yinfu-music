package com.soundtrack.music.home

import com.soundtrack.music.model.Song

/**
 * 首页"推荐歌单"卡片。NetEase `/api/personalized` 的字段直接映射。
 */
data class PlaylistCard(
    val id: Long,
    val name: String,
    val coverUrl: String,
    val playCount: Long,
    val copywriter: String
)

/**
 * 首页"排行榜"卡片：封面 + 前 3-10 首预览歌曲。点开进详情页看全部。
 */
data class ToplistCard(
    val id: Long,
    val name: String,
    val coverUrl: String,
    val topSongs: List<Song>
)

/**
 * 首页三个 Rail 一次拉齐后的数据快照。
 */
data class HomeBundle(
    val recommend: List<PlaylistCard> = emptyList(),
    val newSongs: List<Song> = emptyList(),
    val toplists: List<ToplistCard> = emptyList()
)
