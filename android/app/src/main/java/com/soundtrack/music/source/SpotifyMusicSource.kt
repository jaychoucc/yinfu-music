package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Spotify 音乐源（手机端本地调用）。
 *
 * 重要限制（务必注意）：
 * Python 端 ref_all/spotify.py 的所有可工作路径都依赖：
 *   - 用户自有 OAuth token / sp_dc cookie（走 Spotify 内部 API / Web API），或
 *   - 第三方下载站（spotidown.me、musicfab.io、spotisaver.net 等）的临时签名接口。
 * 这些都需要账号或外部服务，移动端无法在无用户配置的情况下复刻，也【不能伪造】。
 *
 * 因此本实现是「诚实的空实现」：
 *   - search() 直接返回 emptyList()，并在日志/注释中说明需要用户配置 token；
 *   - resolvePlayUrl() 返回 null；
 *   - fetchLyric() 返回 null。
 * 若后续要启用，应让用户填 OAuth token，再接入 Spotify Web API（搜索 + 第三方转换），
 * 那属于另一项独立工作，不在本批范围内。
 *
 * 注：Spotify 提供了一个无需登录的 oEmbed 接口（open.spotify.com/oembed），只能按
 * 已知 track 链接取标题/作者/封面，无法做关键词搜索，故不在此实现里使用。
 */
class SpotifyMusicSource : MusicSource {
    override val id = "spotify"
    override val label = "Spotify"

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        // 需要 OAuth token / sp_dc，未打包、移动端无法无账号搜索。返回空，绝不伪造。
        emptyList()
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        // 缺少用户 token，无法取得真实播放链接
        null
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        null
    }
}
