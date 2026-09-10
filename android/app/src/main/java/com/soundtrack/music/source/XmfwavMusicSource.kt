package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Headers
import okhttp3.Request
import java.net.URLEncoder

/**
 * XMFWAV (xmfwav)：搜索 allsrc 页为 HTML，正则抽取 srcsong-item 列表；
 * 详情页 /song/{id} 内嵌 JS 对象含 url/title/author/pic，提取 url 直链测试即可（无需夸克）。
 * web 直链失效时该源会回退到夸克（移动端无 cookie 取不到），此处走 web 路径。
 */
class XmfwavMusicSource : MusicSource {
    override val id = "xmfwav"
    override val label = "XMFWAV"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36",
        "Referer" to "https://www.xmfwav.com/"
    )

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        val enc = URLEncoder.encode(keyword, "UTF-8")
        val url = "https://www.xmfwav.com/allsrc/$enc?kwd=$enc&page=1"
        val html = get(url, headers) ?: return@withContext emptyList()
        val items = Regex(
            """<a\b[^>]*class=["'][^"']*\bsrcsong-item\b[^"']*["'][^>]*>.*?</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(html)
        val list = ArrayList<Song>()
        for (m in items) {
            val block = m.value
            val idM = Regex("""href=["']/song/(\d+)(?:\.html)?["']""", RegexOption.IGNORE_CASE).find(block) ?: continue
            val id = idM.groupValues[1]
            val infoM = Regex(
                """<span[^>]*class=["']srcsong-name["'][^>]*>(.*?)</span>\s*-\s*<span[^>]*class=["']srcsinger-name["'][^>]*>(.*?)</span>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ).find(block) ?: continue
            val title = stripHtml(infoM.groupValues[1])
            val singer = stripHtml(infoM.groupValues[2])
            if (id.isBlank() || title.isBlank()) continue
            list.add(
                Song(
                    songId = id, source = this@XmfwavMusicSource.id, title = title, artist = singer, album = "",
                    durationSec = 0, coverUrl = "", playUrl = "", lrc = "", ext = "mp3", fileSizeBytes = 0L
                )
            )
            if (list.size >= pageSize) break
        }
        list
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        withTimeoutOrNull(20_000) {
            val html = get("https://www.xmfwav.com/song/${song.songId}", headers) ?: return@withTimeoutOrNull null
            val urlM = Regex("""\burl\s*:\s*(['"])(.*?)\1""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)
            val downloadUrl = urlM?.groupValues?.getOrNull(2) ?: return@withTimeoutOrNull null
            if (!downloadUrl.startsWith("http")) return@withTimeoutOrNull null
            val tested = AudioLinkTester.test(downloadUrl) ?: return@withTimeoutOrNull null
            song.playUrl = tested.url
            song.ext = tested.ext
            song.fileSizeBytes = tested.contentLength
            val picM = Regex("""\bpic\s*:\s*(['"])(.*?)\1""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)
            val pic = picM?.groupValues?.getOrNull(2)
            if (!pic.isNullOrBlank()) {
                song.coverUrl = if (pic.startsWith("http")) pic else "https://www.xmfwav.com$pic"
            }
            tested.url
        }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.lrc.isNotBlank()) return@withContext song.lrc
        return@withContext try {
            val html = get("https://www.xmfwav.com/song/${song.songId}", headers) ?: return@withContext null
            val m = Regex(
                """<section[^>]*id=["']demo["'][^>]*>.*?<article[^>]*>(.*?)</article>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ).find(html)
            val raw = m?.groupValues?.getOrNull(1) ?: return@withContext null
            val cleaned = cleanLrc(raw)
            if (cleaned.isBlank()) null else cleaned.also { song.lrc = it }
        } catch (_: Exception) { null }
    }

    private fun get(url: String, headers: Map<String, String>): String? {
        return try {
            val req = Request.Builder().url(url)
                .headers(Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray()))
                .get().build()
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) { resp.close(); return null }
            resp.body?.string()
        } catch (_: Exception) { null }
    }

    private fun stripHtml(s: String?): String {
        if (s.isNullOrBlank()) return ""
        return s.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
    }

    private fun cleanLrc(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val noTags = raw.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), " ")
        val unescaped = noTags
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        return unescaped.trim()
    }
}
