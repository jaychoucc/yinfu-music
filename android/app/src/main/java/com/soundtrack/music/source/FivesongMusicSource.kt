package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLEncoder

/**
 * FiveSong 音乐 (https://www.5song.xyz)：搜索列表与详情的下载入口均为夸克网盘分享链接，
 * 必须带 quark_parser 的 cookies 才能解析出真实音频地址。Android 端无夸克凭证与解析服务，
 * 故只能搜到结果、无法解析播放地址（resolvePlayUrl 恒返回 null）。如需可用，需后端代理夸克解析。
 */
class FivesongMusicSource : MusicSource {
    override val id = "fivesong"
    override val label = "FiveSong"

    private val base = "https://www.5song.xyz"
    private val searchHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "zh-CN,zh;q=0.9",
        "Referer" to "https://www.5song.xyz/"
    )

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        val url = "$base/search.html?keyword=${URLEncoder.encode(keyword, "UTF-8")}"
        val req = Request.Builder().url(url).headers(toHeaders(searchHeaders)).get().build()
        try {
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val html = resp.body?.string().orEmpty()
            val list = mutableListOf<Song>()
            val items = Regex("""<div[^>]*class="list"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1) ?: html
            val lis = Regex("""<li[^>]*>(.*?)</li>""", RegexOption.DOT_MATCHES_ALL).findAll(items)
            for (m in lis) {
                val inner = m.groupValues[1]
                val detailHref = Regex("""<a[^>]*href="([^"]+)"[^>]*>""").find(inner)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: continue
                val title = Regex("""<h3[^>]*>(.*?)</h3>""", RegexOption.DOT_MATCHES_ALL).find(inner)?.groupValues?.get(1)?.let { unescapeHtml(it) }?.trim() ?: continue
                val singer = Regex("""class="singer"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL).find(inner)?.groupValues?.get(1)?.let { unescapeHtml(it) }?.trim() ?: ""
                val cover = Regex("""<img[^>]*src="([^"]*)"""").find(inner)?.groupValues?.get(1)?.let { joinUrl(base, it) } ?: ""
                val songId = detailHref.trimEnd('/').substringAfterLast('/').substringBefore('.').ifBlank { continue }
                list.add(
                    Song(
                        songId = songId,
                        source = id,
                        title = title,
                        artist = singer,
                        album = "",
                        durationSec = 0,
                        coverUrl = cover,
                        playUrl = "",
                        lrc = "",
                        ext = "mp3",
                        fileSizeBytes = 0L,
                        token = joinUrl(base, detailHref)
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
        // 仅夸克网盘链接，Android 端无法解析，恒返回 null
        null
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        null
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
