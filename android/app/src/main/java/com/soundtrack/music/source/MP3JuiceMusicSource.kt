package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

// MP3Juice MP3JuiceMusicClient 移植：https://mp3juice.sc/
// - 搜索：GET https://mp3juice.sc/api/v1/search?y=s&q=<base64(percent(keyword))>&_=<ms>
//   返回 yt 与 sc 两个数组
// - 下载：
//   - SoundCloud：https://thetacloud.org/s/<id_base64>/<title_base64>/
//   - YouTube：theta.thetacloud.org 的 auth→init→convert→redirect 多步流程
// 链接短时效，resolve 时即时计算；歌词无接口返回 null。海外站需带 UA/Referer。
class MP3JuiceMusicSource : MusicSource {
    override val id = "mp3juice"
    override val label = "MP3Juice"

    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val SEARCH_URL = "https://mp3juice.sc/api/v1/search"

    private data class Meta(val rootSource: String, val idBase64: String, val titleBase64: String)
    private val metaCache = ConcurrentHashMap<String, Meta>()

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val q = Base64.getEncoder().encodeToString(pctEncode(keyword).toByteArray(Charsets.UTF_8))
            val url = "$SEARCH_URL?y=s&q=$q&_=${System.currentTimeMillis()}"
            val req = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Referer", "https://mp3juice.sc/")
                .header("Origin", "https://mp3juice.sc")
                .get().build()
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val text = resp.body?.string().orEmpty()
            val json = JSONObject(text)
            val list = ArrayList<Song>()
            val yt = json.optJSONArray("yt")
            val sc = json.optJSONArray("sc")
            for (arr in arrayOf(yt, sc)) {
                if (arr == null) continue
                for (i in 0 until arr.length()) {
                    if (list.size >= pageSize) break
                    val item = arr.optJSONObject(i) ?: continue
                    val songId = item.optString("id").ifBlank { continue }
                    val title = item.optString("title").ifBlank { continue }
                    val rs = if (arr === yt) "yt" else "sc"
                    if (rs == "sc" && (item.optString("id_base64").isBlank() || item.optString("title_base64").isBlank())) continue
                    metaCache[songId + "|" + rs] = Meta(
                        rootSource = rs,
                        idBase64 = item.optString("id_base64"),
                        titleBase64 = item.optString("title_base64")
                    )
                    list.add(
                        Song(
                            songId = songId,
                            source = this@MP3JuiceMusicSource.id,
                            title = title,
                            artist = "",
                            album = "",
                            durationSec = 0,
                            coverUrl = "",
                            playUrl = "",
                            lrc = "",
                            ext = "mp3",
                            fileSizeBytes = 0L,
                            token = rs
                        )
                    )
                }
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        val meta = metaCache[song.songId + "|" + song.token] ?: return@withContext null
        withTimeoutOrNull(20_000) {
            try {
                val url = if (meta.rootSource == "sc") {
                    "https://thetacloud.org/s/${meta.idBase64}/${meta.titleBase64}/"
                } else {
                    resolveYouTube(song.songId) ?: return@withTimeoutOrNull null
                }
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

    private fun resolveYouTube(videoId: String): String? {
        val t = System.currentTimeMillis().toString()
        // 1. auth
        val authReq = Request.Builder().url("https://theta.thetacloud.org/api/v1/auth?_=$t")
            .header("User-Agent", UA).get().build()
        val authResp = Net.client().newCall(authReq).execute()
        val authJson = JSONObject(authResp.body?.string().orEmpty())
        val key = authJson.optString("key").ifBlank { return null }
        // 2. init
        val initReq = Request.Builder().url("https://theta.thetacloud.org/api/v1/init?_=$t")
            .header("User-Agent", UA)
            .header("Authorization", "Bearer $key").get().build()
        val initResp = Net.client().newCall(initReq).execute()
        val initJson = JSONObject(initResp.body?.string().orEmpty())
        val convertUrl = initJson.optString("convertURL").ifBlank { return null }
        // 3. convert
        val convReq = Request.Builder().url("$convertUrl&v=$videoId&f=mp3&_=${System.currentTimeMillis()}")
            .header("User-Agent", UA).get().build()
        val convResp = Net.client().newCall(convReq).execute()
        val convJson = JSONObject(convResp.body?.string().orEmpty())
        val redirectUrl = convJson.optString("redirectURL").ifBlank { return null }
        // 4. redirect
        val redReq = Request.Builder().url(redirectUrl).header("User-Agent", UA).get().build()
        val redResp = Net.client().newCall(redReq).execute()
        val redJson = JSONObject(redResp.body?.string().orEmpty())
        return redJson.optString("downloadURL").takeIf { it.startsWith("http") }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        // MP3Juice 该客户端无歌词接口
        null
    }

    /** 等价 Python quote(keyword, safe="")：仅保留 A-Za-z0-9.-_~，其余按 UTF-8 大写百分号编码 */
    private fun pctEncode(value: String): String {
        val sb = StringBuilder()
        for (c in value) {
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_' || c == '~') {
                sb.append(c)
            } else {
                for (b in c.toString().toByteArray(Charsets.UTF_8)) {
                    sb.append("%").append(String.format("%02X", b.toInt() and 0xFF))
                }
            }
        }
        return sb.toString()
    }
}
