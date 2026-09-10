package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 栗子YY (liziyy)：搜索页为 HTML，抽取 .music-card（id 形如 MUSIC_xxx）；
 * 详情页下载项全部为夸克网盘链接，解析直链需要夸克 cookie，移动端无法独立完成，
 * 故 resolvePlayUrl 返回 null；歌词可从详情页 detailJson / .lyric-line 直接抽取，故 fetchLyric 可用。
 */
class LiziyyMusicSource : MusicSource {
    override val id = "liziyy"
    override val label = "栗子YY"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36",
        "Referer" to "https://liziyy.top/"
    )

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        val enc = URLEncoder.encode(keyword, "UTF-8")
        val url = "https://liziyy.top/search?page=0&keyword=$enc"
        val html = get(url, headers) ?: return@withContext emptyList()
        val list = ArrayList<Song>()
        val cardSplit = Regex("""class=["'][^"']*\bmusic-card\b[^"']*["']""")
        val indices = cardSplit.findAll(html).map { it.range.first }.toList()
        for (i in indices.indices) {
            val start = indices[i]
            val end = if (i + 1 < indices.size) indices[i + 1] else html.length
            val block = html.substring(start, end)
            val hrefM = Regex("""href=["']([^"']*\?id=MUSIC_([^"&]+))["']""", RegexOption.IGNORE_CASE).find(block)
            val id = hrefM?.groupValues?.getOrNull(2) ?: continue
            val nameM = Regex(
                """class=["'][^"']*\bmusic-name\b[^"']*["'][^>]*>(.*?)</""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ).find(block)
            val name = stripHtml(nameM?.groupValues?.getOrNull(1) ?: "").trim()
            if (name.isBlank()) continue
            val singerM = Regex(
                """class=["'][^"']*\bmusic-singer\b[^"']*["'][^>]*>(.*?)</""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ).find(block)
            val singer = stripHtml(singerM?.groupValues?.getOrNull(1) ?: "").trim()
            val cover = Regex("""<img\b[^>]*\bsrc=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(block)?.groupValues?.getOrNull(1)
                ?: Regex("""<img\b[^>]*\bdata-src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(block)?.groupValues?.getOrNull(1)
                ?: Regex("""<img\b[^>]*\bdata-original=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(block)?.groupValues?.getOrNull(1)
                ?: ""
            list.add(
                Song(
                    songId = id, source = this@LiziyyMusicSource.id, title = name, artist = singer, album = "",
                    durationSec = 0, coverUrl = cover, playUrl = "", lrc = "", ext = "mp3", fileSizeBytes = 0L
                )
            )
            if (list.size >= pageSize) break
        }
        list
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        // 下载项均为夸克网盘链接，需夸克 cookie 才能解析直链，移动端无法独立完成。
        return@withContext if (song.hasPlayUrl) song.playUrl else null
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.lrc.isNotBlank()) return@withContext song.lrc
        return@withContext try {
            val url = "https://liziyy.top/song?id=MUSIC_${song.songId}"
            val html = get(url, headers) ?: return@withContext null
            // 优先从 detailJson 抽取带时间轴歌词
            val m = Regex("""const\s+detailJson\s*=\s*'((?:\\.|[^'\\])*)'\s*;""", RegexOption.DOT_MATCHES_ALL).find(html)
            if (m != null) {
                val escaped = m.groupValues[1]
                val literal = decodeJsString(escaped)
                val detail = try { JSONObject(literal) } catch (_: Exception) { null }
                if (detail != null) {
                    val lrclist = detail.optJSONArray("music_lrclist")
                    if (lrclist != null) {
                        val sb = StringBuilder()
                        for (j in 0 until lrclist.length()) {
                            val it = lrclist.optJSONObject(j) ?: continue
                            val t = it.optDouble("time", 0.0)
                            val line = it.optString("lineLyric").trim()
                            if (line.isBlank()) continue
                            val mm = (t / 60).toInt()
                            val ss = t - mm * 60
                            sb.append("[%02d:%05.2f]%s\n".format(mm, ss, line))
                        }
                        val res = sb.toString().trim()
                        if (res.isNotBlank()) return@withContext res.also { song.lrc = it }
                    }
                }
            }
            // 回退：.lyric-line 文本
            val lines = Regex(
                """<[^>]*class=["'][^"']*\blyric-line\b[^"']*["'][^>]*>(.*?)</""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ).findAll(html)
            val sb = StringBuilder()
            for (lm in lines) {
                val txt = stripHtml(lm.groupValues[1]).trim()
                if (txt.isNotBlank()) sb.appendLine(txt)
            }
            val res = sb.toString().trim()
            if (res.isBlank()) null else res.also { song.lrc = it }
        } catch (_: Exception) { null }
    }

    private fun decodeJsString(s: String): String {
        return s.replace("\\\\", "\u0000")
            .replace("\\'", "'")
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "")
            .replace("\\t", "\t")
            .replace("\\/", "/")
            .replace("\u0000", "\\")
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
}
