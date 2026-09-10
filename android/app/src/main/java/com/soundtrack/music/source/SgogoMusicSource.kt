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
 * Sgogo音乐 (sgogo)：搜索 src 页为 HTML，抽取 .srcsong-item 列表；
 * 详情页内嵌 new APlayer({...}) 脚本，含 title/author/url/pic，提取 url 直链测试即可（无需夸克）。
 */
class SgogoMusicSource : MusicSource {
    override val id = "sgogo"
    override val label = "Sgogo音乐"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36",
        "Referer" to "https://www.sgogo.com/"
    )

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        val enc = URLEncoder.encode(keyword, "UTF-8")
        val url = "https://www.sgogo.com/src/$enc"
        val html = get(url, headers) ?: return@withContext emptyList()
        val list = ArrayList<Song>()
        val itemRe = Regex(
            """<a\b[^>]*class=["'][^"']*\bsrcsong-item\b[^"']*["'][^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        for (m in itemRe.findAll(html)) {
            val href = m.groupValues[1]
            val inner = m.groupValues[2]
            val idM = Regex("""/song/(\d+)""").find(href) ?: continue
            val id = idM.groupValues[1]
            val nameM = Regex(
                """class=["'][^"']*\bsrcsong-name\b[^"']*["'][^>]*>(.*?)</span>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ).find(inner) ?: continue
            val singerM = Regex(
                """class=["'][^"']*\bsrcsinger-name\b[^"']*["'][^>]*>(.*?)</span>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ).find(inner)
            val title = stripHtml(nameM.groupValues[1]).replace(" ", "")
            val singer = stripHtml(singerM?.groupValues?.getOrNull(1) ?: "").replace(" ", "")
            if (title.isBlank()) continue
            list.add(
                Song(
                    songId = id, source = this@SgogoMusicSource.id, title = title, artist = singer, album = "",
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
            val html = get("https://www.sgogo.com/song/${song.songId}", headers) ?: return@withTimeoutOrNull null
            val scriptM = Regex("""new APlayer""").find(html) ?: return@withTimeoutOrNull null
            val start = scriptM.range.first
            val openTag = html.lastIndexOf("<script", start)
            val closeTag = html.indexOf("</script>", start)
            if (openTag < 0 || closeTag < 0) return@withTimeoutOrNull null
            val script = html.substring(openTag, closeTag)
            val urlM = Regex("""\burl\s*:\s*(['"])(.*?)\1""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(script)
            var downloadUrl = urlM?.groupValues?.getOrNull(2) ?: return@withTimeoutOrNull null
            downloadUrl = downloadUrl.replace("\\/", "/")
            if (!downloadUrl.startsWith("http")) downloadUrl = "https://www.sgogo.com$downloadUrl"
            val tested = AudioLinkTester.test(downloadUrl) ?: return@withTimeoutOrNull null
            song.playUrl = tested.url
            song.ext = tested.ext
            song.fileSizeBytes = tested.contentLength
            val picM = Regex("""\bpic\s*:\s*(['"])(.*?)\1""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(script)
            val pic = picM?.groupValues?.getOrNull(2)
            if (!pic.isNullOrBlank()) {
                val cover = pic.replace("\\/", "/")
                song.coverUrl = if (cover.startsWith("http")) cover else "https://www.sgogo.com$cover"
            }
            tested.url
        }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.lrc.isNotBlank()) return@withContext song.lrc
        return@withContext try {
            val html = get("https://www.sgogo.com/song/${song.songId}", headers) ?: return@withContext null
            val m = Regex(
                """<[^>]*id=["']songlrc["'][^>]*>.*?<article[^>]*>(.*?)</article>""",
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
