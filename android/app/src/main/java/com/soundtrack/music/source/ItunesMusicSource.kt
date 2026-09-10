package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * iTunes 播客（有声书）源：使用 Apple 公开搜索 API（media=podcast，无需登录）。
 * 搜索返回播客（专辑），再逐个拉取 RSS feed 解析其中的单集（episode）作为可播放的 Song。
 * 单集 enclosure 多为真实 mp3/m4a，用 AudioLinkTester 校验后写入 playUrl。
 */
class ItunesMusicSource : MusicSource {
    override val id = "itunes"
    override val label = "iTunes"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
    )
    private val maxFeeds = 8
    private val maxEpsPerFeed = 30

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val term = URLEncoder.encode(keyword, "UTF-8")
            val url = "https://itunes.apple.com/search?term=$term&limit=${maxOf(pageSize, maxFeeds)}&media=podcast"
            val body = httpGet(url, headers) ?: return@withContext emptyList()
            val json = JSONObject(body)
            val results = json.optJSONArray("results") ?: return@withContext emptyList()
            val list = ArrayList<Song>()
            for (i in 0 until results.length()) {
                if (list.size >= pageSize) break
                val album = results.optJSONObject(i) ?: continue
                val feedUrl = album.optString("feedUrl")
                if (feedUrl.isBlank()) continue
                val collectionId = album.optString("collectionId")
                val albumTitle = legalize(album.optString("collectionName").ifBlank { album.optString("trackName") })
                val albumAuthor = legalize(album.optString("artistName"))
                val albumCover = album.optString("artworkUrl600").ifBlank { album.optString("artworkUrl100") }
                val eps = withTimeoutOrNull(10_000) { parseFeed(feedUrl, collectionId, albumTitle, albumAuthor, albumCover) } ?: continue
                for (ep in eps) {
                    if (list.size >= pageSize) break
                    list.add(ep)
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseFeed(feedUrl: String, collectionId: String, albumTitle: String, albumAuthor: String, albumCover: String): List<Song> {
        val xml = httpGet(feedUrl, headers) ?: return emptyList()
        val out = ArrayList<Song>()
        try {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(InputSource(StringReader(xml)))
            val channel = doc.getElementsByTagName("channel").item(0) as? Element ?: return emptyList()
            val chanCover = childAttr(channel, "itunes:image", "href") ?: albumCover
            val items = doc.getElementsByTagName("item")
            for (i in 0 until items.length) {
                if (out.size >= maxEpsPerFeed) break
                val item = items.item(i) as? Element ?: continue
                val title = legalize(childText(item, "title"))
                if (title.isBlank()) continue
                val enclosure = item.getElementsByTagName("enclosure").item(0) as? Element
                val audioUrl = enclosure?.getAttribute("url") ?: continue
                if (!audioUrl.startsWith("http")) continue
                val duration = parseDuration(childText(item, "duration"))
                val cover = childAttr(item, "itunes:image", "href") ?: chanCover
                val tested = AudioLinkTester.test(audioUrl) ?: continue
                out.add(
                    Song(
                        songId = "$collectionId-ep$i",
                        source = id,
                        title = title,
                        artist = albumAuthor,
                        album = albumTitle,
                        durationSec = duration,
                        coverUrl = cover,
                        playUrl = tested.url,
                        lrc = "",
                        ext = tested.ext,
                        fileSizeBytes = tested.contentLength
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun childText(element: Element, tag: String): String? {
        val nodes = element.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n.nodeType == Node.ELEMENT_NODE) {
                val nm = n.nodeName
                if (nm == tag || nm.endsWith(":$tag")) {
                    val txt = n.textContent?.trim()
                    if (!txt.isNullOrBlank()) return txt
                }
            }
        }
        return null
    }

    private fun childAttr(element: Element, tag: String, attr: String): String? {
        val node = element.getElementsByTagName(tag).item(0) as? Element ?: return null
        val v = node.getAttribute(attr)
        return if (v.isNullOrBlank()) null else v
    }

    private fun parseDuration(s: String?): Int {
        if (s.isNullOrBlank()) return 0
        val t = s.trim()
        if (t.matches(Regex("^\\d+$"))) return t.toInt()
        val parts = t.split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> 0
        }
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        // 单集播放地址已在搜索阶段（RSS feed）取得并写入 playUrl；此处兜底
        withTimeoutOrNull(20_000) {
            if (song.playUrl.startsWith("http")) {
                val tested = AudioLinkTester.test(song.playUrl)
                if (tested != null) {
                    song.playUrl = tested.url
                    tested.url
                } else null
            } else null
        }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        null
    }

    private fun httpGet(url: String, hdrs: Map<String, String>): String? {
        val req = Request.Builder().url(url).headers(hdrs.toOkHeaders()).build()
        return try {
            val resp = Net.client().newCall(req).execute()
            val s = resp.body?.string()
            resp.close()
            s
        } catch (_: Exception) {
            null
        }
    }

    private fun legalize(s: String?): String {
        if (s.isNullOrBlank()) return ""
        return s.trim().replace(Regex("\\s+"), " ")
    }

    private fun Map<String, String>.toOkHeaders(): okhttp3.Headers =
        okhttp3.Headers.headersOf(*flatMap { listOf(it.key, it.value) }.toTypedArray())
}
