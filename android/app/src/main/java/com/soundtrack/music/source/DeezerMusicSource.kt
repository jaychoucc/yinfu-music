package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Deezer 音乐源（手机端本地调用）。
 *
 * 限制说明（务必注意）：
 * 1. 本实现只走「公开、无需登录」的搜索接口 https://api.deezer.com/search/track 。
 *    该接口返回每条结果的 30 秒试听片段（preview 字段），这是不需要 ARL / license_token
 *    就能拿到的真实音频链接。
 * 2. 完整长度音轨需要登录态（ARL）并通过 media.deezer.com/v1/get_url + Blowfish 解密，
 *    移动端无 JS 引擎、也未打包账号，故「无法」提供完整音轨。请使用 preview 链接（30s）。
 * 3. 歌词同样需要登录态（Bearertoken），这里直接返回 null，不做伪造。
 *
 * 参考：ref_all/deezer.py —— _constructsearchurls 使用 api.deezer.com/search/track?q=...
 * 与官方解析路径不同的是，我们放弃了需要鉴权的 get_url / 第三方下载器，仅保留公开试听。
 */
class DeezerMusicSource : MusicSource {
    override val id = "deezer"
    override val label = "Deezer"

    private val searchHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (X11; Linux i686; rv:135.0) Gecko/20100101 Firefox/135.0",
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "https://www.deezer.com/"
    )

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        val url = "https://api.deezer.com/search/track?q=${URLEncoder.encode(keyword, "UTF-8")}" +
            "&limit=$pageSize&index=0"
        val req = Request.Builder().url(url)
            .headers(okhttp3.Headers.headersOf(*searchHeaders.flatMap { listOf(it.key, it.value) }.toTypedArray()))
            .build()
        try {
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val body = resp.body?.string() ?: return@withContext emptyList()
            val json = JSONObject(body)
            val data = json.optJSONArray("data") ?: return@withContext emptyList()

            val list = ArrayList<Song>()
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val trackId = item.optString("id").ifBlank { continue }
                val title = item.optString("title").ifBlank { continue }
                val artist = item.optJSONObject("artist")?.optString("name") ?: ""
                val album = item.optJSONObject("album")?.optString("title") ?: ""
                val albumCover = item.optJSONObject("album")?.optString("cover") ?: ""
                val duration = item.optInt("duration", 0)
                // 公开、无需鉴权的 30 秒试听片段；真实可播放的 mp3
                val preview = item.optString("preview")
                list.add(
                    Song(
                        songId = trackId,
                        source = this@DeezerMusicSource.id,
                        title = title,
                        artist = artist,
                        album = album,
                        durationSec = duration,
                        coverUrl = albumCover,
                        // preview 是真实音频（30s），直接填到 playUrl，resolve 时原样返回
                        playUrl = preview,
                        lrc = "",
                        ext = "mp3",
                        fileSizeBytes = 0L
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        // 没有 preview 时（例如外部构造的 Song），尝试用公开接口取一次预览并校验
        if (song.songId.isBlank()) return@withContext null
        val probeUrl = "https://api.deezer.com/track/${song.songId}"
        return@withContext withTimeoutOrNull(20_000) {
            try {
                val req = Request.Builder().url(probeUrl)
                    .headers(okhttp3.Headers.headersOf(*searchHeaders.flatMap { listOf(it.key, it.value) }.toTypedArray()))
                    .build()
                val resp = Net.client().newCall(req).execute()
                val body = resp.body?.string() ?: return@withTimeoutOrNull null
                val json = JSONObject(body)
                val preview = json.optString("preview").ifBlank { return@withTimeoutOrNull null }
                val tested = AudioLinkTester.test(preview)
                if (tested != null) {
                    song.playUrl = tested.url
                    tested.url
                } else null
            } catch (_: Exception) {
                null
            }
        }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        // 歌词需要登录态（Bearer token），公开接口拿不到，返回 null，不伪造
        null
    }
}
