package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.net.URLEncoder

/**
 * HTQYY 音乐 (http://www.htqyy.com)：HTML 搜索 + 详情页 script 中拼出直链 mp3。
 * 搜索阶段只解析列表并记下详情页 URL（存入 token），播放阶段再抓详情页提取 mp3 直链。
 * 该站无歌词接口，fetchLyric 返回 null。
 */
class HtqyyMusicSource : MusicSource {
    override val id = "htqyy"
    override val label = "HTQYY音乐"

    private val base = "http://www.htqyy.com"
    private val searchHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "zh-CN,zh;q=0.9",
        "Referer" to "http://www.htqyy.com/",
        "Host" to "www.htqyy.com"
    )
    private val detailHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36",
        "Referer" to "http://www.htqyy.com/"
    )

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        val url = "$base/home/search?wd=${URLEncoder.encode(keyword, "UTF-8")}"
        val req = Request.Builder().url(url).headers(toHeaders(searchHeaders)).get().build()
        try {
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val html = resp.body?.string().orEmpty()
            val list = mutableListOf<Song>()
            val rows = Regex("""<li[^>]*class="[^"]*musicItem[^"]*"[^>]*>(.*?)</li>""", RegexOption.DOT_MATCHES_ALL).findAll(html)
            for (m in rows) {
                val inner = m.groupValues[1]
                val songId = Regex("""name="checked"[^>]*?value="([^"]*)"""").find(inner)?.groupValues?.get(1) ?: continue
                val href = Regex("""class="title"[^>]*>\s*<a[^>]*?href="([^"]*)"""").find(inner)?.groupValues?.get(1) ?: continue
                val title = Regex("""class="title"[^>]*>\s*<a[^>]*?>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).find(inner)?.groupValues?.get(1)?.let { unescapeHtml(it) }?.trim() ?: continue
                val artist = Regex("""class="artistName"[^>]*>\s*<a[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).find(inner)?.groupValues?.get(1)?.let { unescapeHtml(it) }?.trim() ?: ""
                val album = Regex("""class="albumName"[^>]*>\s*<a[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).find(inner)?.groupValues?.get(1)?.let { unescapeHtml(it) }?.trim() ?: ""
                list.add(
                    Song(
                        songId = songId,
                        source = id,
                        title = title,
                        artist = artist,
                        album = album,
                        durationSec = 0,
                        coverUrl = "",
                        playUrl = "",
                        lrc = "",
                        ext = "mp3",
                        fileSizeBytes = 0L,
                        token = joinUrl(base, href)
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
        val detail = song.token
        if (detail.isBlank()) return@withContext null
        withTimeoutOrNull(20_000) {
            try {
                val req = Request.Builder().url(detail).headers(toHeaders(detailHeaders)).get().build()
                val resp = Net.client().newCall(req).execute()
                val html = resp.body?.string().orEmpty()
                val url = extractMp3Url(html) ?: return@withTimeoutOrNull null
                val tested = AudioLinkTester.test(url) ?: return@withTimeoutOrNull null
                song.playUrl = tested.url
                tested.url
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        null
    }

    /** 从详情页 script 中提取 fileHost + mp3 路径拼成直链 */
    private fun extractMp3Url(html: String): String? {
        val script = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).findAll(html)
            .firstOrNull { t ->
                val s = t.groupValues[1]
                s.contains("PageData") && (s.contains("fileHost") || s.contains("var mp3"))
            }?.groupValues?.get(1) ?: return null
        val fileHost = Regex("""var\s+fileHost\s*=\s*["']([^"']*)["']""").find(script)?.groupValues?.get(1)
        val mp3 = Regex("""var\s+mp3\s*=\s*["']([^"']*)["']""").find(script)?.groupValues?.get(1)
        return if (!fileHost.isNullOrBlank() && !mp3.isNullOrBlank()) (fileHost + mp3) else null
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
}
