package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * LRTS 电台（懒惰电台）源：移动端 ajax 搜索，覆盖 album（专辑）与 book（有声书）两类。
 * 搜索阶段列出专辑/书下的单集（track）元数据；播放地址在 resolvePlayUrl 中通过
 * getPlayPath / getListenPath 计算，entityType 与 entityId 编码在 token 字段中。
 */
class LrtsMusicSource : MusicSource {
    override val id = "lrts"
    override val label = "LRTS电台"

    private val searchHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Referer" to "https://m.lrts.me/"
    )
    private val downloadHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
    )
    private val maxEntities = 6
    private val maxTracksPer = 12

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val kw = URLEncoder.encode(keyword, "UTF-8")
            val url = "https://m.lrts.me/ajax/search?keyWord=$kw&pageSize=40&pageNum=1&searchOption=1"
            val body = httpGet(url, searchHeaders) ?: return@withContext emptyList()
            val json = JSONObject(body)
            val data = json.optJSONObject("data") ?: return@withContext emptyList()
            val list = ArrayList<Song>()
            val albumList = data.optJSONObject("albumResult")?.optJSONArray("list") ?: JSONArray()
            val bookList = data.optJSONObject("bookResult")?.optJSONArray("list") ?: JSONArray()
            var processed = 0
            for (t in listOf(2 to albumList, 3 to bookList)) {
                val entityType = t.first
                val arr = t.second
                for (i in 0 until arr.length()) {
                    if (list.size >= pageSize || processed >= maxEntities) break
                    processed++
                    val entry = arr.optJSONObject(i) ?: continue
                    val entityId = entry.optString("id")
                    if (entityId.isBlank()) continue
                    val albumName = legalize(entry.optString("name"))
                    val announcer = legalize(
                        entry.optJSONObject("album_info")?.optString("nickName")
                            ?: entry.optJSONObject("book_info")?.optString("announcer")
                            ?: entry.optString("nickName")
                    )
                    val cover = entry.optString("cover")
                    val tracks = withTimeoutOrNull(10_000) { fetchTracks(entityId, entityType) } ?: emptyList()
                    for (tr in tracks) {
                        if (list.size >= pageSize) break
                        list.add(
                            Song(
                                songId = tr.id,
                                source = id,
                                title = tr.name,
                                artist = announcer,
                                album = albumName,
                                durationSec = tr.duration,
                                coverUrl = cover,
                                playUrl = "",
                                lrc = "",
                                ext = "m4a",
                                fileSizeBytes = 0L,
                                token = "$entityType|$entityId"
                            )
                        )
                    }
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private data class TrackInfo(val id: String, val name: String, val duration: Int)

    private fun fetchTracks(entityId: String, entityType: Int): List<TrackInfo> {
        val url = if (entityType == 3) {
            "https://m.lrts.me/ajax/getBookMenu?bookId=$entityId&pageNum=1&pageSize=50&sortType=0"
        } else {
            "https://m.lrts.me/ajax/getAlbumAudios?ablumnId=$entityId&sortType=0"
        }
        val body = httpGet(url, downloadHeaders) ?: return emptyList()
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val arr = json.optJSONArray("list") ?: json.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<TrackInfo>()
        for (i in 0 until arr.length()) {
            if (out.size >= maxTracksPer) break
            val tr = arr.optJSONObject(i) ?: continue
            val tid = if (entityType == 3) tr.optString("id") else tr.optString("audioId")
            if (tid.isBlank()) continue
            val name = legalize(tr.optString("name"))
            if (name.isBlank()) continue
            val dur = tr.optInt("length", 0)
            out.add(TrackInfo(tid, name, dur))
        }
        return out
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        withTimeoutOrNull(20_000) {
            val parts = song.token.split("|")
            if (parts.size < 2) return@withTimeoutOrNull null
            val entityType = parts[0].toIntOrNull() ?: return@withTimeoutOrNull null
            val entityId = parts[1]
            val audioId = song.songId
            var path = ""
            val p1 = httpGet("https://m.lrts.me/ajax/getPlayPath?entityId=$entityId&entityType=$entityType&opType=1&sections=[$audioId]&type=0", downloadHeaders)
            if (p1 != null) {
                val j = runCatching { JSONObject(p1) }.getOrNull()
                path = j?.optJSONArray("list")?.optJSONObject(0)?.optString("path") ?: ""
            }
            if (path.isBlank()) {
                val p2 = httpGet("https://m.lrts.me/ajax/getListenPath?entityId=$entityId&entityType=$entityType&opType=1&sections=[$audioId]&type=0&id=$audioId&section=1", downloadHeaders)
                if (p2 != null) {
                    val j = runCatching { JSONObject(p2) }.getOrNull()
                    path = j?.optJSONObject("data")?.optString("path") ?: ""
                }
            }
            if (path.isBlank() || !path.startsWith("http")) return@withTimeoutOrNull null
            val tested = AudioLinkTester.test(path) ?: return@withTimeoutOrNull null
            song.playUrl = tested.url
            tested.url
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
