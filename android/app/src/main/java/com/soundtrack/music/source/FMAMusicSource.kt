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
 * Free Music Archive 源（网站搜索，无需 key）。
 * 搜索: https://freemusicarchive.org/search/?quicksearch=...  返回 HTML，
 *       从中提取 .play-item[data-track-info] 的 JSON（含 fileUrl/playbackUrl 等直链）。
 * 对应 ref_all/fma.py。无 JSoup，用 Regex 提取 data-track-info 并解析 JSON。
 */
class FMAMusicSource : MusicSource {
    override val id = "fma"
    override val label = "Free Music Archive"

    private val headers = mapOf(
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
    )

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val url = "https://freemusicarchive.org/search/?" + buildQuery(mapOf(
                "quicksearch" to keyword,
                "pageSize" to pageSize.toString(),
                "page" to "1"
            ))
            val req = Request.Builder().url(url)
                .headers(okhttp3.Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray()))
                .build()
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val html = resp.body?.string().orEmpty()

            val list = mutableListOf<Song>()
            val regex = Regex("""data-track-info\s*=\s*"([^"]*)"""")
            for (m in regex.findAll(html)) {
                val raw = unescapeHtml(m.groupValues[1])
                val obj = runCatching { JSONObject(raw) }.getOrNull() ?: continue
                val trackId = obj.optString("id").ifBlank { continue }
                val title = legalize(obj.optString("title"))
                if (title.isBlank()) continue
                val artist = legalize(obj.optString("artistName"))
                val album = legalize(obj.optString("albumTitle"))
                val durationSec = parseDuration(obj.optString("duration"))
                val token = listOf(
                    obj.optString("fileUrl"), obj.optString("playbackUrl"), obj.optString("downloadUrl")
                ).firstOrNull { it.startsWith("http") }.orEmpty()
                val cover = obj.optString("trackImage").ifBlank { obj.optString("image") }

                list.add(Song(
                    songId = trackId,
                    source = this@FMAMusicSource.id,
                    title = title,
                    artist = artist,
                    album = album,
                    durationSec = durationSec,
                    coverUrl = cover,
                    playUrl = "",
                    lrc = "",
                    ext = "mp3",
                    fileSizeBytes = 0L,
                    token = token
                ))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        val raw = song.token.ifBlank { return@withContext null }
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

    private fun parseDuration(raw: String): Int {
        if (raw.isBlank()) return 0
        if (raw.contains(":")) {
            val parts = raw.split(":").mapNotNull { it.toIntOrNull() }
            return when (parts.size) {
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                2 -> parts[0] * 60 + parts[1]
                1 -> parts[0]
                else -> 0
            }
        }
        return raw.toIntOrNull() ?: 0
    }

    private fun unescapeHtml(s: String): String {
        return s.replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace("&#x27;", "'")
    }

    private fun buildQuery(params: Map<String, String>): String =
        params.map { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }.joinToString("&")

    private fun legalize(s: String?): String {
        if (s == null) return ""
        return s.replace("\u0000", "").replace("\u200b", "").trim()
    }
}
