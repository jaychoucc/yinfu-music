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
 * 不菇音乐 BuguyyMusicClient 移植：
 * - 搜索：GET https://buguyy.top/api/search?keyword=...  (JSON: {"data":[...]})
 * - 下载/歌词：GET https://buguyy.top/api/geturl?id=... (JSON: {"url":..., "lrc":...})
 * 未配置夸克 cookies，走 web 分支（geturl 直接给外链），不解析夸克网盘。
 */
class BuguyyMusicSource : MusicSource {
    override val id = "buguyy"
    override val label = "不菇音乐"

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36",
        "Referer" to "https://buguyy.top/",
        "Content-Type" to "application/json"
    )

    // songId -> (downloadUrl, lrc)，避免重复请求 geturl
    private val metaCache = ConcurrentHashMap<String, Pair<String?, String?>>()

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val url = "https://buguyy.top/api/search?" + "keyword=" + java.net.URLEncoder.encode(keyword, "UTF-8")
            val req = Request.Builder().url(url)
                .headers(okhttp3.Headers.headersOf(*baseHeaders.flatMap { listOf(it.key, it.value) }.toTypedArray()))
                .get().build()
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val body = resp.body?.string().orEmpty()
            val json = JSONObject(body)
            val data = json.optJSONArray("data") ?: return@withContext emptyList()
            val list = ArrayList<Song>()
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val songId = item.optString("id").ifBlank { continue }
                val title = item.optString("title").ifBlank { continue }
                val artist = item.optString("singer")
                val album = item.optString("album")
                val cover = item.optString("picurl")
                list.add(
                    Song(
                        songId = songId,
                        source = this@BuguyyMusicSource.id,
                        title = title,
                        artist = artist,
                        album = album,
                        durationSec = 0,
                        coverUrl = cover,
                        playUrl = "",
                        lrc = "",
                        ext = "mp3",
                        fileSizeBytes = 0L
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getMeta(songId: String): Pair<String?, String?> {
        metaCache[songId]?.let { return it }
        return try {
            val url = "https://buguyy.top/api/geturl?id=$songId"
            val req = Request.Builder().url(url)
                .headers(okhttp3.Headers.headersOf(*baseHeaders.flatMap { listOf(it.key, it.value) }.toTypedArray()))
                .get().build()
            val resp = Net.client().newCall(req).execute()
            val body = resp.body?.string().orEmpty()
            val json = JSONObject(body)
            val pair = json.optString("url").takeIf { it.startsWith("http") } to
                json.optString("lrc").takeIf { it.isNotBlank() && it != "NULL" }
            metaCache[songId] = pair
            pair
        } catch (e: Exception) {
            Pair(null, null)
        }
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        val (url) = getMeta(song.songId)
        if (url.isNullOrBlank()) return@withContext null
        withTimeoutOrNull(20_000) {
            val tested = AudioLinkTester.test(url)
            if (tested != null) {
                song.playUrl = tested.url
                song.ext = tested.ext
                song.fileSizeBytes = tested.contentLength
                tested.url
            } else null
        }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.lrc.isNotBlank()) return@withContext song.lrc
        val (_, lrc) = getMeta(song.songId)
        val cleaned = cleanLrc(lrc ?: return@withContext null)
        if (cleaned.isBlank()) null else cleaned.also { song.lrc = it }
    }

    private fun cleanLrc(raw: String): String {
        var s = raw.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("<[^>]+>"), "")
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
        s = s.trim()
        if (s == "NULL" || s == "null" || s == "None" || s.contains("歌词获取失败")) return ""
        return s
    }
}
