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
 * 喜马拉雅源：revision/search（core=track）做搜索，播放地址走免签名的 m.ximalaya.com/tracks/{id}.json
 * （play_path_64 / play_path_32 / play_path），无需 xm-sign / AES 解密即可拿到真实音频。
 * 注：未采用 Python 的 baseInfo 签名路径（xm_sign + decryptplayurl），改用更轻量的 tracks/{id}.json 公开接口，
 * 因此无需 XimalayaCrypto.kt。
 */
class XimalayaMusicSource : MusicSource {
    override val id = "ximalaya"
    override val label = "喜马拉雅"

    private val searchHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*"
    )
    private val trackHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
        "Referer" to "https://www.ximalaya.com/"
    )

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val kw = URLEncoder.encode(keyword, "UTF-8")
            val url = "https://www.ximalaya.com/revision/search?core=track&kw=$kw&page=1&rows=$pageSize&spellchecker=true&condition=relation&device=web"
            val body = httpGet(url, searchHeaders) ?: return@withContext emptyList()
            val json = JSONObject(body)
            val docs = json.optJSONObject("data")?.optJSONObject("result")?.optJSONObject("response")?.optJSONArray("docs") ?: return@withContext emptyList()
            val list = ArrayList<Song>()
            for (i in 0 until docs.length()) {
                if (list.size >= pageSize) break
                val d = docs.optJSONObject(i) ?: continue
                val tid = d.optString("id").ifBlank { d.optString("trackId") }
                if (tid.isBlank()) continue
                val title = legalize(d.optString("title").ifBlank { d.optString("trackName") })
                if (title.isBlank()) continue
                val artist = legalize(d.optString("nickname").ifBlank { d.optString("anchorName") })
                val album = legalize(d.optString("album_title").ifBlank { d.optString("albumTitle").ifBlank { d.optString("albumName") } })
                val duration = d.optInt("duration", 0)
                val cover = d.optString("cover_path").ifBlank {
                    d.optString("coverMiddle").ifBlank { d.optString("coverLarge").ifBlank { d.optString("coverSmall") } }
                }
                list.add(
                    Song(
                        songId = tid,
                        source = id,
                        title = title,
                        artist = artist,
                        album = album,
                        durationSec = duration,
                        coverUrl = cover,
                        playUrl = "",
                        lrc = "",
                        ext = "m4a",
                        fileSizeBytes = 0L
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        withTimeoutOrNull(20_000) {
            val id = song.songId
            val body = httpGet("https://m.ximalaya.com/tracks/$id.json", trackHeaders) ?: return@withTimeoutOrNull null
            val json = JSONObject(body)
            val ret = json.opt("ret")
            if (ret != null) {
                val rv = (ret as? Number)?.toInt() ?: ret.toString().toIntOrNull() ?: -1
                if (rv != 0 && rv != 200) return@withTimeoutOrNull null
            }
            var url = json.optString("play_path_64").ifBlank {
                json.optString("play_path_32").ifBlank { json.optString("play_path") }
            }
            if (url.isBlank() || !url.startsWith("http")) return@withTimeoutOrNull null
            val tested = AudioLinkTester.test(url) ?: return@withTimeoutOrNull null
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
