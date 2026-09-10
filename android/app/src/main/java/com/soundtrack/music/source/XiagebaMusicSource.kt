package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 下歌吧 (xiageba)：搜索接口返回 JSON（api/music/search），可独立工作。
 * 但真实音频托管在夸克网盘（pan.quark.cn），解析直链需要夸克 cookie，
 * 移动端无法独立完成，故 resolvePlayUrl 返回 null；歌词在 _payload.json 中可直接取，故 fetchLyric 可用。
 */
class XiagebaMusicSource : MusicSource {
    override val id = "xiageba"
    override val label = "下歌吧"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"
    )

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        val url = "https://xiageba.liumingye.cn/api/music/search?q=${URLEncoder.encode(keyword, "UTF-8")}&page=1&pageSize=$pageSize"
        val raw = get(url, headers) ?: return@withContext emptyList()
        val json = try { JSONObject(raw) } catch (_: Exception) { return@withContext emptyList() }
        val data = json.optJSONArray("data") ?: return@withContext emptyList()
        val list = ArrayList<Song>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val songId = item.optString("id")
            val title = item.optString("title")
            if (songId.isBlank() || title.isBlank()) continue
            val artist = item.optString("artist")
            val album = item.optString("album")
            val cover = item.optString("cover")
            list.add(
                Song(
                    songId = songId, source = this@XiagebaMusicSource.id, title = title, artist = artist, album = album,
                    durationSec = 0, coverUrl = cover, playUrl = "", lrc = "", ext = "mp3", fileSizeBytes = 0L
                )
            )
        }
        list
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        // 音频在夸克网盘，需夸克 cookie 才能解析直链，移动端无法独立完成。
        return@withContext if (song.hasPlayUrl) song.playUrl else null
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.lrc.isNotBlank()) return@withContext song.lrc
        return@withContext try {
            val uuid = java.util.UUID.randomUUID().toString()
            val url = "https://xiageba.liumingye.cn/music/${song.songId}/_payload.json?$uuid"
            val raw = get(url, headers) ?: return@withContext null
            val arr = JSONArray(raw)
            var lyrics: String? = null
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.has("lyrics")) { lyrics = obj.optString("lyrics"); break }
            }
            val cleaned = cleanLrc(lyrics)
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
