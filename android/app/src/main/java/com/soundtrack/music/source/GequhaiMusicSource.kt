package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.Request
import java.net.URLEncoder

/**
 * 歌曲海 (https://www.gequhai.com)：搜索列表拿到 /play/<id>，播放阶段 POST /api/music 取直链。
 * 无需夸克网盘。歌词在详情页 #content-lrc2 文本中，fetchLyric 尝试抽取。
 */
class GequhaiMusicSource : MusicSource {
    override val id = "gequhai"
    override val label = "歌曲海"

    private val base = "https://www.gequhai.com"
    private val searchHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "zh-CN,zh;q=0.9",
        "Referer" to "https://www.gequhai.com/"
    )
    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "X-Requested-With" to "Http",
        "Origin" to "https://www.gequhai.com",
        "Referer" to "https://www.gequhai.com/"
    )

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        val url = "$base/s/${URLEncoder.encode(keyword, "UTF-8")}"
        val req = Request.Builder().url(url).headers(toHeaders(searchHeaders)).get().build()
        try {
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val html = resp.body?.string().orEmpty()
            val list = mutableListOf<Song>()
            val rows = Regex("""<tr[^>]*>(.*?)</tr>""", RegexOption.DOT_MATCHES_ALL).findAll(html)
            for (m in rows) {
                val inner = m.groupValues[1]
                val aHref = Regex("""href="([^"]*/play/\d+[^"]*)"""").find(inner)?.groupValues?.get(1) ?: continue
                val playId = Regex("""/play/(\d+)""").find(aHref)?.groupValues?.get(1) ?: continue
                val title = Regex("""<a[^>]*href="[^"]*/play/\d+"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).find(inner)?.groupValues?.get(1)?.let { unescapeHtml(it) }?.trim() ?: continue
                val tds = Regex("""<td[^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL).findAll(inner).toList()
                val singer = tds.getOrNull(2)?.groupValues?.get(1)?.let { unescapeHtml(it) }?.trim() ?: ""
                list.add(
                    Song(
                        songId = playId,
                        source = id,
                        title = title,
                        artist = singer,
                        album = "",
                        durationSec = 0,
                        coverUrl = "",
                        playUrl = "",
                        lrc = "",
                        ext = "mp3",
                        fileSizeBytes = 0L,
                        token = joinUrl(base, aHref)
                    )
                )
                if (list.size >= pageSize) break
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        if (song.songId.isBlank()) return@withContext null
        withTimeoutOrNull(20_000) {
            try {
                val body = FormBody.Builder().add("id", song.songId).add("type", "0").build()
                val req = Request.Builder().url("$base/api/music").headers(toHeaders(apiHeaders)).post(body).build()
                val resp = Net.client().newCall(req).execute()
                val json = org.json.JSONObject(resp.body?.string().orEmpty())
                val url = json.optJSONObject("data")?.optString("url") ?: return@withTimeoutOrNull null
                if (!url.startsWith("http")) return@withTimeoutOrNull null
                val tested = AudioLinkTester.test(url) ?: return@withTimeoutOrNull null
                song.playUrl = tested.url
                tested.url
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.lrc.isNotBlank()) return@withContext song.lrc
        val detail = song.token
        if (detail.isBlank()) return@withContext null
        try {
            val req = Request.Builder().url(detail).headers(toHeaders(searchHeaders)).get().build()
            val resp = Net.client().newCall(req).execute()
            val html = resp.body?.string().orEmpty()
            val block = Regex("""<div[^>]*id="content-lrc2"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1) ?: return@withContext null
            val text = block.replace("<[^>]+>".toRegex(), "\n").let { unescapeHtml(it) }
            val cleaned = cleanLrc(text)
            if (cleaned != null) song.lrc = cleaned
            cleaned
        } catch (e: Exception) {
            null
        }
    }

    private fun toHeaders(map: Map<String, String>): okhttp3.Headers =
        okhttp3.Headers.headersOf(*map.flatMap { listOf(it.key, it.value) }.toTypedArray())

    private fun joinUrl(base: String, href: String): String =
        if (href.startsWith("http")) href else base + href

    private fun unescapeHtml(s: String): String {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
            .replace("<[^>]+>".toRegex(), "").trim()
    }

    private fun cleanLrc(s: String): String? {
        val lines = s.lines().map { it.trim() }.filter { it.isNotBlank() }
        return if (lines.isEmpty()) null else lines.joinToString("\n")
    }
}
