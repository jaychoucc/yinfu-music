package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject

/**
 * QQ 音乐源：musicu.fcg 搜索 + 第三方 vkeys 解析 + 官方/base64 歌词
 */
class QQMusicSource : MusicSource {
    override val id = "qq"
    override val label = "QQ音乐"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36",
        "Referer" to "https://y.qq.com/",
        "Origin" to "https://y.qq.com/",
        "Content-Type" to "application/json"
    )

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("music.search.SearchCgiService", JSONObject().apply {
                put("method", "DoSearchForQQMusicMobile")
                put("module", "music.search.SearchCgiService")
                put("param", JSONObject().apply {
                    put("searchid", (System.currentTimeMillis() / 1000).toString())
                    put("query", keyword)
                    put("search_type", 0)
                    put("num_per_page", pageSize)
                    put("page_num", 1)
                    put("highlight", 1)
                    put("grp", 1)
                })
            })
        }
        val req = Request.Builder()
            .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
            .headers(okhttp3.Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray()))
            .post(okhttp3.RequestBody.create("application/json".toMediaType(), payload.toString()))
            .build()
        val resp = Net.client().newCall(req).execute()
        val json = JSONObject(resp.body?.string() ?: "{}")
        val items = json.optJSONObject("music.search.SearchCgiService.DoSearchForQQMusicMobile")
            ?.optJSONObject("data")?.optJSONObject("body")?.optJSONArray("item_song") ?: return@withContext emptyList()

        val list = mutableListOf<Song>()
        for (i in 0 until items.length()) {
            parseItem(items.getJSONObject(i))?.let { list.add(it) }
        }
        list
    }

    private fun parseItem(item: JSONObject): Song? {
        val mid = item.optString("mid").ifBlank { item.optString("songmid") }.ifBlank { return null }
        val name = item.optString("title").ifBlank { item.optString("songname") }.ifBlank { return null }
        val artist = item.optJSONArray("singer")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optString("name") }.joinToString(", ")
        } ?: ""
        val album = item.optJSONObject("album")?.optString("title") ?: item.optString("albumname")
        val albumMid = item.optJSONObject("album")?.optString("mid") ?: item.optString("albummid")
        val cover = if (albumMid.isNotBlank()) "https://y.gtimg.cn/music/photo_new/T002R800x800M000${albumMid}.jpg" else ""
        val duration = item.optInt("interval", 0)
        // 搜索阶段不解析播放地址（避免逐首联网导致慢 + 播放时二次轮询）
        return Song(
            songId = mid,
            source = id,
            title = name,
            artist = artist,
            album = album,
            durationSec = duration,
            coverUrl = cover,
            playUrl = "",
            lrc = "",
            ext = "mp3",
            fileSizeBytes = 0L
        )
    }

    private fun resolveUrl(mid: String): AudioLinkTester.Result? {
        val qualities = listOf("flac", "mp3_320", "mp3_128", "m4a")
        for (q in qualities) {
            try {
                val url = "https://api.vkeys.cn/music/tencent/song/link?mid=$mid&quality=$q"
                val req = Request.Builder().url(url)
                    .header("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                val resp = Net.client().newCall(req).execute()
                val json = JSONObject(resp.body?.string() ?: "{}")
                val downloadUrl = json.optJSONObject("data")?.optString("url") ?: continue
                AudioLinkTester.test(downloadUrl)?.let { return it }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun fetchLyric(mid: String): String? {
        try {
            val url = "https://api.vkeys.cn/v2/music/tencent/lyric?mid=$mid"
            val req = Request.Builder().url(url)
                .header("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            val resp = Net.client().newCall(req).execute()
            val json = JSONObject(resp.body?.string() ?: "{}")
            return json.optJSONObject("data")?.optString("lrc")
        } catch (_: Exception) {
            return null
        }
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        withTimeoutOrNull(20_000) {
            resolveUrl(song.songId)?.url?.also { song.playUrl = it }
        } ?: song.playUrl.takeIf { it.startsWith("http") }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.lrc.isNotBlank()) return@withContext song.lrc
        fetchLyric(song.songId)?.also { song.lrc = it }
    }
}
