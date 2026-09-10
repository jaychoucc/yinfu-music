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
 * 音乐岛源（YinyuedaoMusicClient 移植）：1mp3.top。
 *
 * 接口说明：
 * - 搜索：GET https://1mp3.top/search.html?keyword=... ，结果是静态 HTML，
 *   没有 JSoup，这里用正则抽取 <a href="/mdetail/TOKEN"> 链接，TOKEN 作为 songId。
 * - 解析播放地址：GET https://1mp3.top/geturl?id={TOKEN}&quality=exhigh&type=json 拿 data.url 直链。
 * - 已知限制：1mp3.top 详情/夸克网盘路径依赖 QuarkParser（需夸克 cookies），本实现只走 web geturl 直链；
 *   若搜索页为 JS 渲染或带反爬，则搜索可能返回空。歌词未实现（返回 null，不伪造）。
 */
class YinyuedaoMusicSource : MusicSource {
    override val id = "yinyuedao"
    override val label = "音乐岛"

    private val searchHeaders = mapOf(
        "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "accept-encoding" to "gzip, deflate, br, zstd",
        "accept-language" to "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7",
        "referer" to "https://1mp3.top/",
        "sec-ch-ua" to "\"Chromium\";v=\"142\", \"Google Chrome\";v=\"142\", \"Not_A Brand\";v=\"99\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\"",
        "sec-fetch-dest" to "document",
        "sec-fetch-mode" to "navigate",
        "sec-fetch-site" to "same-origin",
        "sec-fetch-user" to "?1",
        "upgrade-insecure-requests" to "1",
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"
    )

    private val downloadHeaders = mapOf(
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"
    )

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val url = "https://1mp3.top/search.html?keyword=" + URLEncoder.encode(keyword, "UTF-8")
            val req = Request.Builder().url(url).headers(headersOf(searchHeaders)).get().build()
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val html = resp.body?.string().orEmpty()

            val list = ArrayList<Song>()
            val re = Regex("<a[^>]+href=\"/mdetail/([^\"]+)\"[^>]*>(.*?)</a>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            for (m in re.findAll(html)) {
                val token = m.groupValues[1].trim()
                if (token.isBlank()) continue
                val rawText = m.groupValues[2]
                val text = stripTags(rawText)
                if (text.isBlank()) continue
                // 形如 "歌名 - 歌手" 时拆开，否则整体作为标题
                val (title, artist) = if (text.contains(" - ")) {
                    val idx = text.indexOf(" - ")
                    text.substring(0, idx).trim() to text.substring(idx + 3).trim()
                } else {
                    text to ""
                }
                list.add(
                    Song(
                        songId = token,
                        source = id,
                        title = legalizeString(title),
                        artist = legalizeString(artist),
                        album = "",
                        durationSec = 0,
                        coverUrl = "",
                        playUrl = "",
                        lrc = "",
                        ext = "mp3",
                        fileSizeBytes = 0L
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
        withTimeoutOrNull(20_000) {
            val url = resolveYinyuedao(song.songId)
            if (url != null) {
                song.playUrl = url
                url
            } else null
        }
    }

    private fun resolveYinyuedao(token: String): String? {
        return try {
            val url = "https://1mp3.top/geturl?id=${URLEncoder.encode(token, "UTF-8")}&quality=exhigh&type=json"
            val req = Request.Builder().url(url).headers(headersOf(downloadHeaders)).get().build()
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return null
            val json = JSONObject(resp.body?.string().orEmpty())
            val dl = json.optJSONObject("data")?.optString("url")?.takeIf { it.startsWith("http") } ?: return null
            AudioLinkTester.test(dl)?.url ?: dl
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        // 音乐岛歌词需夸克 cookies，本免登录实现不伪造
        null
    }

    private fun stripTags(s: String): String {
        return s.replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
