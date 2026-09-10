package com.soundtrack.music.home

import com.soundtrack.music.model.Song
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * 首页数据仓库：把 NetEase 三个 Rail 的请求并行起来。
 * 全部走 NetEaseHomeApi（不依赖电脑端）。
 */
class HomeRepository(private val api: NetEaseHomeApi = NetEaseHomeApi()) {

    /**
     * 一次拉齐首页三个 Rail（推荐歌单 / 新歌速递 / 排行榜）。
     * 任何单个 Rail 失败不影响整体，仍然返回已有数据。
     */
    suspend fun loadHome(): HomeBundle = coroutineScope {
        val recommendDef = async { runCatching { api.recommendPlaylists(6) }.getOrDefault(emptyList()) }
        val newSongsDef  = async { runCatching { api.newSongs("ALL", 20) }.getOrDefault(emptyList()) }
        val topsDef      = async { runCatching { api.toplists() }.getOrDefault(emptyList()) }
        HomeBundle(
            recommend = recommendDef.await(),
            newSongs  = newSongsDef.await(),
            toplists  = topsDef.await()
        )
    }

    suspend fun loadPlaylistDetail(id: Long): List<Song> = runCatching { api.playlistDetail(id) }.getOrDefault(emptyList())
}
