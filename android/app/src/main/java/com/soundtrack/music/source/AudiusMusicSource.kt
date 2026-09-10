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
 * Audius 去中心化音乐源（公开 API，无需 key）。
 * 搜索: https://api.audius.co/v1/tracks/search
 * 播放: https://api.audius.co/v1/tracks/{id}/stream?app_name=musicdl
 * 对应 ref_all/audius.py。playUrl 在 resolvePlayUrl 阶段用 AudioLinkTester 校验后写入。
 */
class AudiusMusicSource : MusicSource {
    override val id = "audius"
    override val label = "Audius"

    private val apiBase = "https://api.audius.co"
    private val appName = "musicdl"

    private val headers = mapOf(
        "accept" to "application/json",
        "referer" to "https://audius.co/",
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
    )

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val url = apiBase + "/v1/tracks/search?" + buildQuery(mapOf(
                "query" to keyword,
                "limit" to pageSize.toString(),
                "offset" to "0",
                "app_name" to appName
            ))
            val req = Request.Builder().url(url)
                .headers(okhttp3.Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray()))
                .build()
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val json = JSONObject(resp.body?.string().orEmpty())
            val data = json.optJSONArray("data") ?: return@withContext emptyList()

            val list = mutableListOf<Song>()
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val trackId = item.optString("id").ifBlank { continue }
                val title = legalize(item.optString("title"))
                if (title.isBlank()) continue
                val user = item.optJSONObject("user")
                val artist = legalize(
                    user?.optString("name").orEmpty().ifBlank { user?.optString("handle").orEmpty() }
                )
                val album = legalize(item.optString("album_name").ifBlank { item.optString("playlist_name") })
                val durationSec = item.optDouble("duration", 0.0).toInt()
                val artwork = item.optJSONObject("artwork")
                val cover = artwork?.optString("1000x1000").orEmpty()
                    .ifBlank { artwork?.optString("480x480").orEmpty().ifBlank { artwork?.optString("150x150").orEmpty() } }
                list.add(Song(
                    songId = trackId,
                    source = this@AudiusMusicSource.id,
                    title = title,
                    artist = artist,
                    album = album,
                    durationSec = durationSec,
                    coverUrl = cover,
                    playUrl = "",
                    lrc = "",
                    ext = "mp3",
                    fileSizeBytes = 0L
                ))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        if (song.songId.isBlank()) return@withContext null
        val raw = apiBase + "/v1/tracks/" + song.songId + "/stream?app_name=" + appName
        withTimeoutOrNull(20_000) {
            val tested = AudioLinkTester.test(raw)
            if (tested != null) {
                song.playUrl = tested.url
                tested.url
            } else null
        }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        null
    }

    private fun buildQuery(params: Map<String, String>): String =
        params.map { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }.joinToString("&")

    private fun legalize(s: String?): String {
        if (s == null) return ""
        return s.replace("\u0000", "").replace("\u200b", "").trim()
    }
}
