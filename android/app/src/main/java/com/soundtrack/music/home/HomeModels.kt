package com.soundtrack.music.home

import com.soundtrack.music.model.Song

/**
 * 首页"推荐歌单"卡片。NetEase `/api/personalized/playlist` 的 result[] 直接映射。
 */
data class PlaylistCard(
    val id: Long,
    val name: String,
    val coverUrl: String,
    val playCount: Long,
    val copywriter: String
)

/**
 * 首页"排行榜"卡片：封面 + 名称 + 前 3 首文字预览。
 *
 * 注意：/api/toplist/detail 返回的 tracks 是 `{first: 歌名, second: 歌手}` 简版结构，
 * **没有歌曲 id**，无法构造可播放的 Song。所以这里存展示用的文本预览，
 * 完整可播曲目在点进详情页时再用 playlistDetail 拉取。
 */
data class ToplistCard(
    val id: Long,
    val name: String,
    val coverUrl: String,
    val previews: List<String> = emptyList()
)

/**
 * 首页三个 Rail 一次拉齐后的数据快照。
 */
data class HomeBundle(
    val recommend: List<PlaylistCard> = emptyList(),
    val newSongs: List<Song> = emptyList(),
    val toplists: List<ToplistCard> = emptyList()
)
