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
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 蜻蜓FM 源：移动端 m-bff 搜索。
 *
 * 重要限制：播放地址需要账号登录态（access_token / qingting_id）做 HMAC-MD5 签名，
 * 未配置登录态时只能搜索、无法解析真实播放地址（resolvePlayUrl 返回 null）。
 * 这里照搬 Python 的签名算法（HMAC_KEY / DEVICE_ID），把登录态常量留空，
 * 由上层在拿到账号后注入；当前实现可正常搜索展示，但播放需补登录态。
 */
class QingtingMusicSource : MusicSource {
    override val id = "qingting"
    override val label = "蜻蜓FM"

    private val HMAC_KEY = "99@b8#571(bb38_b"
    private val DEVICE_ID = "66f6e3b560ad8876e52e6e67ee535c5c"
    // 登录态：需在拿到账号后填入，否则 resolvePlayUrl 直接返回 null
    private val ACCESS_TOKEN = ""
    private val QINGTING_ID = ""

    private val searchHeaders = mapOf(
        "User-Agent" to "QingTing-iOS/10.7.9.0 com.Qting.QTTour Mozilla/5.0 (iPhone; CPU iPhone OS 16_6_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148",
        "QT-App-Version" to "10.7.9.0"
    )

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val kw = URLEncoder.encode(keyword, "UTF-8")
            val list = ArrayList<Song>()
            for (include in listOf("program_ondemand", "channel_ondemand")) {
                val url = "https://app.qtfm.cn/m-bff/v1/search/result?k=$kw&sort_type=0&page=1&include=$include&pagesize=$pageSize&k_src=direct"
                val body = httpGet(url, searchHeaders) ?: continue
                val json = JSONObject(body)
                val arr = json.optJSONObject("data")?.optJSONArray("data") ?: continue
                for (i in 0 until arr.length()) {
                    if (list.size >= pageSize) break
                    val item = arr.optJSONObject(i) ?: continue
                    val type = item.optString("type")
                    if (include == "program_ondemand" && type != "program") continue
                    if (include == "channel_ondemand" && type != "channel_ondemand") continue
                    val pid = item.optString("id")
                    if (pid.isBlank()) continue
                    val title = legalize(item.optString("title"))
                    if (title.isBlank()) continue
                    val duration = item.optInt("duration", 0)
                    val cover = item.optString("cover")
                    val appUrl = item.optString("url")
                    list.add(
                        Song(
                            songId = pid,
                            source = id,
                            title = title,
                            artist = "",
                            album = legalize(item.optString("desc")),
                            durationSec = duration,
                            coverUrl = cover,
                            playUrl = "",
                            lrc = "",
                            ext = "m4a",
                            fileSizeBytes = 0L,
                            token = appUrl
                        )
                    )
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        if (ACCESS_TOKEN.isBlank() || QINGTING_ID.isBlank()) return@withContext null
        withTimeoutOrNull(20_000) {
            val channelId = Regex("channel_id=([^&]+)").find(song.token)?.groupValues?.getOrNull(1) ?: return@withTimeoutOrNull null
            val programId = Regex("program_id=([^&]+)").find(song.token)?.groupValues?.getOrNull(1) ?: song.songId
            val pathQuery = "/m-bff/v1/audiostreams/channel/$channelId/program/$programId?access_token=$ACCESS_TOKEN&device_id=$DEVICE_ID&qingting_id=$QINGTING_ID&type=play"
            val sign = hmacMd5(HMAC_KEY, pathQuery)
            val url = "https://app.qtfm.cn$pathQuery&sign=$sign"
            val body = httpGet(url, searchHeaders) ?: return@withTimeoutOrNull null
            val json = JSONObject(body)
            val data = json.optJSONObject("data") ?: return@withTimeoutOrNull null
            val editions = ArrayList<JSONObject?>()
            val e1 = data.optJSONArray("editions")
            if (e1 != null) for (k in 0 until e1.length()) editions.add(e1.optJSONObject(k))
            val e2 = data.optJSONArray("backup_editions")
            if (e2 != null) for (k in 0 until e2.length()) editions.add(e2.optJSONObject(k))
            for (ed in editions) {
                val e = ed ?: continue
                val raw = e.opt("urls")
                val urlList = if (raw is String) listOf(raw) else if (raw is JSONArray) {
                    val tmp = ArrayList<String>()
                    for (k in 0 until raw.length()) tmp.add(raw.optString(k))
                    tmp
                } else emptyList()
                for (u in urlList) {
                    if (!u.startsWith("http")) continue
                    val tested = AudioLinkTester.test(u)
                    if (tested != null) {
                        song.playUrl = tested.url
                        return@withTimeoutOrNull tested.url
                    }
                }
            }
            null
        }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        null
    }

    private fun hmacMd5(key: String, msg: String): String {
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacMD5"))
        return mac.doFinal(msg.toByteArray()).joinToString("") { "%02x".format(it) }
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
