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
 * 荔枝FM 源：移动端 vodapi 搜索 voice，再用 voicePlayProperty.trackUrl 做多品质（_ud.mp3 / _hd.mp3 / _sd.m4a）解析。
 * 搜索阶段直接拿到真实音频地址并写入 playUrl。
 */
class LizhiMusicSource : MusicSource {
    override val id = "lizhi"
    override val label = "荔枝FM"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 9_1 like Mac OS X) AppleWebKit/601.1.46 (KHTML, like Gecko) Version/9.0 Mobile/13B143 Safari/601.1",
        "Referer" to "https://m.lizhi.fm"
    )
    private val deviceId = "h5-b6ef91a9-3dbb-c716-1fdd-43ba08851150"
    private val qualities = listOf("_ud.mp3", "_hd.mp3", "_sd.m4a")

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val kw = URLEncoder.encode(keyword, "UTF-8")
            val url = "https://m.lizhi.fm/vodapi/search/voice?deviceId=$deviceId&keywords=$kw&page=1&receiptData="
            val body = httpGet(url, headers) ?: return@withContext emptyList()
            val json = JSONObject(body)
            val data = json.optJSONArray("data") ?: return@withContext emptyList()
            val list = ArrayList<Song>()
            for (i in 0 until data.length()) {
                if (list.size >= pageSize) break
                val item = data.optJSONObject(i) ?: continue
                val voiceInfo = item.optJSONObject("voiceInfo") ?: continue
                val voiceId = voiceInfo.optString("voiceId")
                if (voiceId.isBlank()) continue
                val name = legalize(voiceInfo.optString("name"))
                if (name.isBlank()) continue
                val duration = voiceInfo.optInt("duration", 0)
                val userInfo = item.optJSONObject("userInfo")
                val author = legalize(userInfo?.optString("name"))
                val imageUrl = voiceInfo.optString("imageUrl")
                val trackUrl = item.optJSONObject("voicePlayProperty")?.optString("trackUrl") ?: ""
                val tested = withTimeoutOrNull(20_000) { resolveTrackUrl(trackUrl) }
                if (tested == null) continue
                list.add(
                    Song(
                        songId = voiceId,
                        source = id,
                        title = name,
                        artist = author,
                        album = author,
                        durationSec = duration,
                        coverUrl = imageUrl,
                        playUrl = tested.url,
                        lrc = "",
                        ext = tested.ext,
                        fileSizeBytes = tested.contentLength
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun resolveTrackUrl(base: String): AudioLinkTester.Result? {
        if (base.isBlank() || !base.startsWith("http")) return null
        var cur = base
        for (q in qualities) {
            cur = (cur.dropLast(7) + q).replace("//cdn5.lizhi.fm/audio/", "//cdn101.lizhi.fm/audio/")
            val r = AudioLinkTester.test(cur)
            if (r != null) return r
            val r2 = AudioLinkTester.test(cur.replace("//cdn101.lizhi.fm/audio/", "//cdn5.lizhi.fm/audio/"))
            if (r2 != null) return r2
        }
        return null
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        withTimeoutOrNull(20_000) { null }
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
