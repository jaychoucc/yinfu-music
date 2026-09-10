package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 米兔音乐 MituMusicClient 移植：https://www.qqmp3.vip/
 * - 搜索：GET https://api.qqmp3.vip/api/songs.php?keyword=...&type=search (JSON: {"data":[...]})
 * - 下载/歌词：GET https://api.qqmp3.vip/api/kw.php?rid=...&type=json&level=exhigh&lrc=true (JSON: {"data":{"url":..., "lrc":...}})
 * 未配置夸克 cookies，走 web 分支（kw.php 的 data.url 直接给外链）。
 */
class MituMusicSource : MusicSource {
    override val id = "mitu"
    override val label = "米兔音乐"

    private val searchHeaders = mapOf(
        "accept" to "*/*",
        "accept-language" to "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7",
        "origin" to "https://www.qqmp3.vip",
        "referer" to "https://www.qqmp3.vip/",
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"
    )
    private val downloadHeaders = mapOf(
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"
    )

    private val metaCache = ConcurrentHashMap<String, Pair<String?, String?>>()

    private fun headersOf(map: Map<String, String>): okhttp3.Headers =
        okhttp3.Headers.headersOf(*map.flatMap { listOf(it.key, it.value) }.toTypedArray())

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.qqmp3.vip/api/songs.php?keyword=" +
                java.net.URLEncoder.encode(keyword, "UTF-8") + "&type=search"
            val req = Request.Builder().url(url).headers(headersOf(searchHeaders)).get().build()
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val body = resp.body?.string().orEmpty()
            val json = JSONObject(body)
            val data = json.optJSONArray("data") ?: return@withContext emptyList()
            val list = ArrayList<Song>()
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val songId = item.optString("rid").ifBlank { continue }
                val title = item.optString("name").ifBlank { continue }
                val artist = item.optString("artist")
                val cover = item.optString("pic")
                list.add(
                    Song(
                        songId = songId,
                        source = this@MituMusicSource.id,
                        title = title,
                        artist = artist,
                        album = "",
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
            val url = "https://api.qqmp3.vip/api/kw.php?rid=$songId&type=json&level=exhigh&lrc=true"
            val req = Request.Builder().url(url).headers(headersOf(downloadHeaders)).get().build()
            val resp = Net.client().newCall(req).execute()
            val body = resp.body?.string().orEmpty()
            val json = JSONObject(body)
            val d = json.optJSONObject("data") ?: JSONObject()
            val pair = d.optString("url").takeIf { it.startsWith("http") } to
                d.optString("lrc").takeIf { it.isNotBlank() && it != "NULL" }
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
        var s = raw.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
        s = s.trim()
        if (s == "NULL" || s == "null" || s == "None" || s.contains("歌词获取失败")) return ""
        return s
    }
}
