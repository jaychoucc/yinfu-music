package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.net.URLEncoder

/**
 * OpenGameArt 开放素材站音频源（无需 key，纯 HTML 抓取；无 JSoup，用 Regex）。
 * 搜索: https://opengameart.org/art-search-advanced?keys=...&field_art_type_tid[0]=12
 *       解析结果列表中的 /content/ 详情页链接，存入 token（详情页 URL）。
 * 播放: resolvePlayUrl 阶段抓取详情页，提取 /sites/default/files/ 下的音频直链并校验。
 * 对应 ref_all/opengameart.py。限制：artist/duration 等需进详情页才能拿到，搜索阶段留空。
 */
class OpenGameArtMusicSource : MusicSource {
    override val id = "opengameart"
    override val label = "OpenGameArt"

    private val baseUrl = "https://opengameart.org"
    private val headers = mapOf(
        "user-agent" to "Mozilla/5.0 OGA-Music-Search/1.0"
    )

    private val junkWords = setOf("image", "preview", "first", "previous", "next", "last", "log in", "register", "read more")

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val url = baseUrl + "/art-search-advanced?" + buildQuery(mapOf(
                "keys" to keyword,
                "field_art_type_tid[0]" to "12",
                "items_per_page" to "24",
                "page" to "0"
            ))
            val req = Request.Builder().url(url)
                .headers(okhttp3.Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray()))
                .build()
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val html = resp.body?.string().orEmpty()

            val list = mutableListOf<Song>()
            val seen = mutableSetOf<String>()
            val regex = Regex("(?s)<a\\b[^>]*href=\"(/content/[^\"]+)\"[^>]*>(.*?)</a>")
            for (m in regex.findAll(html)) {
                val path = m.groupValues[1]
                if (path == "/content/faq") continue
                val pageUrl = baseUrl + path
                if (!seen.add(pageUrl)) continue
                val text = stripTags(m.groupValues[2]).trim()
                if (text.isBlank()) continue
                if (text.lowercase() in junkWords) continue
                list.add(Song(
                    songId = pageUrl,
                    source = this@OpenGameArtMusicSource.id,
                    title = legalize(text),
                    artist = "",
                    album = "",
                    durationSec = 0,
                    coverUrl = "",
                    playUrl = "",
                    lrc = "",
                    ext = "mp3",
                    fileSizeBytes = 0L,
                    token = pageUrl
                ))
                if (list.size >= pageSize) break
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        val pageUrl = song.token.ifBlank { return@withContext null }
        withTimeoutOrNull(20_000) {
            try {
                val req = Request.Builder().url(pageUrl)
                    .header("user-agent", headers["user-agent"].orEmpty())
                    .build()
                val resp = Net.client().newCall(req).execute()
                if (!resp.isSuccessful) return@withTimeoutOrNull null
                val html = resp.body?.string().orEmpty()
                val audioUrl = extractAudioUrl(html) ?: return@withTimeoutOrNull null
                val tested = AudioLinkTester.test(audioUrl)
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
        null
    }

    private fun extractAudioUrl(html: String): String? {
        val regex = Regex("""(https?://[^\s"'<>]+)?/sites/default/files/[^\s"'<>?]+\.(mp3|ogg|oga|flac|wav|m4a|opus)""", RegexOption.IGNORE_CASE)
        val priority = mapOf("mp3" to 5, "ogg" to 5, "oga" to 5, "m4a" to 4, "opus" to 4, "flac" to 3, "wav" to 3)
        var best: String? = null
        var bestScore = -1
        for (m in regex.findAll(html)) {
            val raw = m.groupValues[0]
            val url = if (raw.startsWith("http")) raw else baseUrl + raw
            val ext = m.groupValues[1].lowercase()
            val score = priority[ext] ?: 0
            if (score > bestScore) {
                bestScore = score
                best = url
            }
        }
        return best
    }

    private fun stripTags(s: String): String =
        s.replace(Regex("(?s)<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()

    private fun buildQuery(params: Map<String, String>): String =
        params.map { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }.joinToString("&")

    private fun legalize(s: String?): String {
        if (s == null) return ""
        return s.replace("\u0000", "").replace("\u200b", "").trim()
    }
}
