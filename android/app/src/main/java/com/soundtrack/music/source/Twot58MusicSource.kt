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
 * 2T58音乐 (twot58)：搜索 so 页为 HTML，抽取 .play_list 内 .name 链接；
 * 播放走 plug/down.php?ac=music&id=&k=(flac|wav|320)，302 跳转后直链可测。
 * 注意：该站有人机验证页，触发时搜索会返回空（无法自动通过）。
 */
class Twot58MusicSource : MusicSource {
    override val id = "twot58"
    override val label = "2T58音乐"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
        "Connection" to "keep-alive"
    )

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        val enc = URLEncoder.encode(keyword, "UTF-8")
        val url = "https://www.2t58.com/so/$enc.html"
        val html = get(url, headers) ?: return@withContext emptyList()
        val list = ArrayList<Song>()
        val aRe = Regex(
            """<a\b[^>]*class=["'][^"']*\bname\b[^"']*["'][^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        for (m in aRe.findAll(html)) {
            val href = m.groupValues[1]
            val title = stripHtml(m.groupValues[2]).trim()
            if (title.isBlank()) continue
            val path = href.trim('/').split('/').last().split('.').first()
            if (path.isBlank()) continue
            list.add(
                Song(
                    songId = path, source = this@Twot58MusicSource.id, title = title, artist = "", album = "",
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
            val qualities = listOf("flac", "wav", "320")
            for (quality in qualities) {
                val url = "https://www.2t58.com/plug/down.php?ac=music&id=${song.songId}&k=$quality"
                val tested = AudioLinkTester.test(url) ?: continue
                song.playUrl = tested.url
                song.ext = tested.ext
                song.fileSizeBytes = tested.contentLength
                return@withTimeoutOrNull tested.url
            }
            null
        }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.lrc.isNotBlank()) return@withContext song.lrc
        return@withContext try {
            val url = "https://www.2t58.com/plug/down.php?ac=music&lk=lrc&id=${song.songId}"
            val raw = get(url, headers) ?: return@withContext null
            val cleaned = cleanLrc(raw.replace("""[00:00.00]欢迎来访爱听音乐网 www.2t58.com""", ""))
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
