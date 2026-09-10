package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.net.URLEncoder

/**
 * 爱听蛙 (https://www.itingwa.com)：搜索结果在 so.itingwa.com，详情页 #tw_player 的 init-data
 * 拼出 mp3.itingwa.com 直链。搜索阶段记录详情页 URL（token），播放阶段抓详情页提取直链。
 * 歌词在详情页 .music_intro 文本中，fetchLyric 尝试抽取。
 */
class ItingwaMusicSource : MusicSource {
    override val id = "itingwa"
    override val label = "爱听蛙"

    private val base = "https://www.itingwa.com"
    private val searchBase = "https://so.itingwa.com"
    private val searchHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "https://so.itingwa.com/",
        "Host" to "so.itingwa.com"
    )
    private val detailHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36",
        "Referer" to "https://www.itingwa.com/"
    )

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        val url = "$searchBase/?c=index&k=${URLEncoder.encode(keyword, "UTF-8")}&t=1&p=1"
        val req = Request.Builder().url(url).headers(toHeaders(searchHeaders)).get().build()
        try {
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val html = resp.body?.string().orEmpty()
            val list = mutableListOf<Song>()
            val rows = Regex("""<tr[^>]*>(.*?)</tr>""", RegexOption.DOT_MATCHES_ALL).findAll(html)
            for (m in rows) {
                val inner = m.groupValues[1]
                val listenHref = Regex("""href="([^"]*/listen/\d+[^"]*)"""").find(inner)?.groupValues?.get(1) ?: continue
                val songId = Regex("""/listen/(\d+)""").find(listenHref)?.groupValues?.get(1) ?: continue
                val name = Regex("""href="[^"]*/listen/\d+[^"]*"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).find(inner)?.groupValues?.get(1)?.let { unescapeHtml(it) }?.trim() ?: continue
                val tds = Regex("""<td[^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL).findAll(inner).toList()
                val author = tds.getOrNull(1)?.groupValues?.get(1)?.let { unescapeHtml(it) }?.trim() ?: ""
                val cover = Regex("""<img[^>]*?(?:data-src|src)="([^"]*)"""").find(inner)?.groupValues?.get(1)?.let { joinUrl(base, it) } ?: ""
                list.add(
                    Song(
                        songId = songId,
                        source = id,
                        title = name,
                        artist = author,
                        album = "",
                        durationSec = 0,
                        coverUrl = cover,
                        playUrl = "",
                        lrc = "",
                        ext = "mp3",
                        fileSizeBytes = 0L,
                        token = joinUrl(base, listenHref)
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
                val initData = Regex("""id="tw_player"[^>]*init-data="([^"]*)"""").find(html)?.groupValues?.get(1) ?: return@withTimeoutOrNull null
                val url = "https://mp3.itingwa.com/" + initData
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
            val req = Request.Builder().url(detail).headers(toHeaders(detailHeaders)).get().build()
            val resp = Net.client().newCall(req).execute()
            val html = resp.body?.string().orEmpty()
            val block = Regex("""<div[^>]*class="music_intro"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1) ?: return@withContext null
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
