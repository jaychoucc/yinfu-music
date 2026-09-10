package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.net.URLEncoder

/**
 * ccMixter（Creative Commons 音乐）源，无需 key。
 * 搜索: https://ccmixter.org/api/query?f=xspf&t=search_uploads&search=... (返回 XSPF XML)
 * 每首歌的 <location> 即为可直接下载的音频直链，存入 token，resolvePlayUrl 校验后写入 playUrl。
 * 对应 ref_all/ccmixter.py。无 JSoup，用 Regex 解析 XSPF。
 */
class CcMixterMusicSource : MusicSource {
    override val id = "ccmixter"
    override val label = "ccMixter"

    private val headers = mapOf(
        "accept" to "application/xspf+xml, application/xml, text/xml, */*",
        "referer" to "https://ccmixter.org/",
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
    )

    private data class CcTrack(
        val location: String,
        val title: String,
        val creator: String,
        val album: String,
        val durationRaw: String,
        val cover: String,
        val identifier: String
    )

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val url = "https://ccmixter.org/api/query?" + buildQuery(mapOf(
                "f" to "xspf",
                "t" to "search_uploads",
                "search_type" to "any",
                "search" to keyword,
                "limit" to pageSize.toString(),
                "offset" to "0"
            ))
            val req = Request.Builder().url(url)
                .headers(okhttp3.Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray()))
                .build()
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val text = resp.body?.string().orEmpty()

            val tracks = parseXspf(text)
            val list = mutableListOf<Song>()
            for (t in tracks) {
                val title = legalize(t.title)
                if (title.isBlank()) continue
                list.add(Song(
                    songId = t.identifier,
                    source = this@CcMixterMusicSource.id,
                    title = title,
                    artist = legalize(t.creator),
                    album = legalize(t.album),
                    durationSec = parseDuration(t.durationRaw),
                    coverUrl = t.cover,
                    playUrl = "",
                    lrc = "",
                    ext = "mp3",
                    fileSizeBytes = 0L,
                    token = t.location
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

    private fun parseXspf(text: String): List<CcTrack> {
        val tracks = mutableListOf<CcTrack>()
        val trackRegex = Regex("(?s)<track\\b[^>]*>(.*?)</track>")
        for (m in trackRegex.findAll(text)) {
            val block = m.groupValues[1]
            val locations = firstTag(block, "location").filter { it.startsWith("http") }
            val location = locations.firstOrNull() ?: continue
            val title = firstTag(block, "title").firstOrNull().orEmpty()
            val creator = firstTag(block, "creator").firstOrNull().orEmpty()
            val album = firstTag(block, "album").firstOrNull().orEmpty()
            val durationRaw = firstTag(block, "duration").firstOrNull().orEmpty()
            val cover = firstTag(block, "image").firstOrNull().orEmpty()
            val identifier = firstTag(block, "identifier").firstOrNull().orEmpty().ifBlank { location }
            tracks.add(CcTrack(location, title, creator, album, durationRaw, cover, identifier))
        }
        return tracks
    }

    private fun firstTag(block: String, tag: String): List<String> {
        val regex = Regex("(?s)<(?:\\w+:)?$tag\\b[^>]*>(.*?)</(?:\\w+:)?$tag>")
        return regex.findAll(block).map { it.groupValues[1].trim() }.toList()
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
        val n = raw.toLongOrNull() ?: 0L
        return if (n > 100000) (n / 1000).toInt() else n.toInt()
    }

    private fun buildQuery(params: Map<String, String>): String =
        params.map { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }.joinToString("&")

    private fun legalize(s: String?): String {
        if (s == null) return ""
        return s.replace("\u0000", "").replace("\u200b", "").trim()
    }
}
