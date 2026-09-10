package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * LivePOO LivePOOMusicClient 移植：https://www.livepoo.cn/
 * - 搜索：GET https://www.livepoo.cn/search?page=0&keyword=... 返回 HTML，正则抽取歌曲条目
 * - 下载（无夸克 cookies 走 web 分支）：GET https://www.livepoo.cn/audio/play?id=<id> 返回纯文本外链
 * - 歌词：详情页内 const detailJson = '...' 解析音乐列表
 * 无 JSoup，全部用正则 + org.json 处理。
 */
class LivePOOMusicSource : MusicSource {
    override val id = "livepoo"
    override val label = "LivePOO"

    private val BASE = "https://www.livepoo.cn/"
    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"

    private data class Meta(val detailUrl: String)
    private val metaCache = ConcurrentHashMap<String, Meta>()

    private fun stripTags(html: String): String =
        html.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val url = BASE + "search?page=0&keyword=" + java.net.URLEncoder.encode(keyword, "UTF-8")
            val req = Request.Builder().url(url).header("User-Agent", UA).header("Referer", BASE).get().build()
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val html = resp.body?.string().orEmpty()
            parseSearchHtml(html, pageSize)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseSearchHtml(html: String, pageSize: Int): List<Song> {
        val list = ArrayList<Song>()
        // 每条 <li class="song_item2"> ... </li>
        val liRegex = Regex("""class=["']?song_item2["']?[^>]*>(.*?)</li>""", RegexOption.DOT_MATCHES_ALL)
        val hrefRegex = Regex("""href=["']([^"']+)["']""")
        val titleRegex = Regex("""song_info2[^>]*>\s*<div[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
        val brTitleRegex = Regex("""^(.*?)《(.*?)》${'$'}""")
        val idRegex = Regex("""[?&]id=([^&"'\s]+)""")
        for (m in liRegex.findAll(html)) {
            if (list.size >= pageSize) break
            val li = m.value
            val href = hrefRegex.find(li)?.groupValues?.get(1) ?: continue
            val idMatch = idRegex.find(href)?.groupValues?.get(1) ?: continue
            val songId = idMatch.removePrefix("MUSIC_")
            val detailUrl = if (href.startsWith("http")) href else BASE + href.removePrefix("/")
            // 标题
            val titleHtml = titleRegex.find(li)?.groupValues?.get(1)
            var titleText = if (titleHtml != null) stripTags(titleHtml) else stripTags(hrefRegex.replace(li) { "" })
            if (titleText.isBlank()) titleText = stripTags(li)
            val (singer, songName) = if (titleText.isNotBlank()) {
                val bm = brTitleRegex.matchEntire(titleText.trim())
                if (bm != null) bm.groupValues[1].trim() to bm.groupValues[2].trim()
                else "" to titleText.trim()
            } else "" to ""
            if (songName.isBlank()) continue
            metaCache[songId] = Meta(detailUrl)
            list.add(
                Song(
                    songId = songId,
                    source = this@LivePOOMusicSource.id,
                    title = songName,
                    artist = singer,
                    album = "",
                    durationSec = 0,
                    coverUrl = "",
                    playUrl = "",
                    lrc = "",
                    ext = "mp3",
                    fileSizeBytes = 0L
                )
            )
        }
        return list
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        val meta = metaCache[song.songId] ?: return@withContext null
        withTimeoutOrNull(20_000) {
            try {
                val req = Request.Builder().url(BASE + "audio/play?id=" + song.songId)
                    .header("User-Agent", UA).header("Referer", meta.detailUrl).get().build()
                val resp = Net.client().newCall(req).execute()
                val url = resp.body?.string().orEmpty().trim()
                if (!url.startsWith("http")) return@withTimeoutOrNull null
                val tested = AudioLinkTester.test(url)
                if (tested != null) {
                    song.playUrl = tested.url
                    song.ext = tested.ext
                    song.fileSizeBytes = tested.contentLength
                    tested.url
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.lrc.isNotBlank()) return@withContext song.lrc
        val meta = metaCache[song.songId] ?: return@withContext null
        try {
            val req = Request.Builder().url(meta.detailUrl)
                .header("User-Agent", UA).header("Referer", BASE).get().build()
            val resp = Net.client().newCall(req).execute()
            val html = resp.body?.string().orEmpty()
            val lrc = buildLrcFromDetail(html) ?: return@withContext null
            lrc.also { song.lrc = it }
        } catch (e: Exception) {
            null
        }
    }

    private fun buildLrcFromDetail(html: String): String? {
        val m = Regex("""const\s+detailJson\s*=\s*'(.+?)';\s*const\s+detail\s*=\s*JSON\.parse""", RegexOption.DOT_MATCHES_ALL)
            .find(html) ?: return null
        val raw = m.groupValues[1]
        val unescaped = unescapeJsString(raw)
        val obj = try { JSONObject(unescaped) } catch (e: Exception) { return null }
        val list = searchKey(obj, "music_lrclist") as? JSONArray ?: return null
        val lines = ArrayList<Pair<Double, String>>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val time = (searchKey(item, "time") as? Number)?.toDouble() ?: continue
            val lyric = (searchKey(item, "lineLyric") as? String) ?: continue
            lines.add(time to lyric.replace(Regex("\\s+"), " ").trim())
        }
        if (lines.isEmpty()) return null
        lines.sortBy { it.first }
        val sb = StringBuilder()
        for ((t, ly) in lines) {
            val mm = (t / 60).toInt()
            val sec = t - mm * 60
            sb.append(String.format("[%02d:%05.2f]%s\n", mm, sec, ly))
        }
        return sb.toString().trim()
    }

    /** 按字符扫描处理 JS 字符串转义（正确区分 \\n 与 \\n 等） */
    private fun unescapeJsString(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\'' -> sb.append('\'')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** 在嵌套的 JSONObject / JSONArray 中递归按 key 找值 */
    private fun searchKey(node: Any?, key: String): Any? {
        return when (node) {
            is JSONObject -> {
                if (node.has(key)) return node.opt(key)
                val it = node.keys()
                while (it.hasNext()) {
                    val v = searchKey(node.opt(it.next()), key)
                    if (v != null) return v
                }
                null
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val v = searchKey(node.opt(i), key)
                    if (v != null) return v
                }
                null
            }
            else -> null
        }
    }
}
