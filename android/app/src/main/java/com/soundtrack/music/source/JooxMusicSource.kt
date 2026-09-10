package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * JOOX 音源（腾讯海外音乐，含签名/加密）。
 *
 * 实现方式：
 * 1. 搜索走公开接口 cache.api.joox.com/openjoox/v2/search_type（无需签名）。
 * 2. 播放地址解析优先走官方接口 openjoox2/v1/track/{id}：用硬编码的公开 uid/usk + 自算
 *    secret(makesecret = MD5(SALT + 参数)) 获取 play_url_list / refrain_url，逐条经
 *    AudioLinkTester 校验。
 * 3. 官方接口不可用（如签名失效）时，回退第三方 gdstudio.xyz / .org 接口：先取服务端时间，
 *    按 MD5(time|host|version|id) 末尾 8 位十六进制做签名，POST 取下载地址。
 * 4. 歌词从官方接口的 lrc_content（base64）解码得到，拿不到返回 null。
 *
 * 限制：官方接口依赖 musicdl 硬编码的公开 uid/usk，可能随时失效；第三方 gdstudio 站点也可能下线。
 * 校验失败则无播放链接，不会伪造成功。参考 ref_all/joox.py。
 */
class JooxMusicSource : MusicSource {
    override val id = "joox"
    override val label = "JOOX"

    private val metaCache = ConcurrentHashMap<String, String>()  // songId -> 官方接口返回的 meta JSON

    private val searchHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Origin" to "https://www.joox.com",
        "Referer" to "https://www.joox.com/",
        "x-forwarded-for" to "36.73.34.109"
    )

    private fun buildQuery(params: Map<String, String>): String =
        params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }

    private fun reqHeaders() =
        okhttp3.Headers.headersOf(*searchHeaders.flatMap { listOf(it.key, it.value) }.toTypedArray())

    private fun gdHeaders(host: String) = okhttp3.Headers.headersOf(
        "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
        "Origin", "https://$host",
        "Referer", "https://$host/",
        "X-Requested-With", "XMLHttpRequest",
        "Accept", "application/json, text/javascript, */*; q=0.01",
        "Content-Type", "application/x-www-form-urlencoded; charset=UTF-8"
    )

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        val url = "https://cache.api.joox.com/openjoox/v2/search_type?" + buildQuery(
            mapOf("country" to "hk", "lang" to "zh_TW", "key" to keyword, "type" to "0")
        )
        val req = Request.Builder().url(url).headers(reqHeaders()).build()
        try {
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val body = resp.body?.string() ?: return@withContext emptyList()
            val json = JSONObject(body)
            val tracks = json.optJSONArray("tracks") ?: return@withContext emptyList()

            val list = ArrayList<Song>()
            for (i in 0 until tracks.length()) {
                val raw = tracks.opt(i)
                val track: JSONObject? = when (raw) {
                    is JSONObject -> raw
                    is JSONArray -> raw.optJSONObject(0)
                    else -> null
                }
                val t = track ?: continue
                val sid = t.optString("id").ifBlank { continue }
                val title = t.optString("name").ifBlank { t.optString("title") }.ifBlank { continue }
                val artist = t.optString("singer").ifBlank { t.optString("singer_name") }
                val album = t.optString("album_name").ifBlank { t.optString("album") }
                val cover = t.optString("cover").ifBlank {
                    t.optJSONObject("images")?.optString("cover") ?: t.optString("image")
                }
                val durationSec = t.optInt("play_duration", 0)
                list.add(
                    Song(
                        songId = sid,
                        source = this@JooxMusicSource.id,
                        title = title,
                        artist = artist,
                        album = album,
                        durationSec = durationSec,
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

    /** 官方接口：openjoox2/v1/track/{id}，返回 meta JSON（含 play_url_list / lrc_content）。 */
    private fun getMeta(songId: String): JSONObject? {
        val params = linkedMapOf(
            "country" to "hk",
            "lang" to "zh_TW",
            "lyric" to "1",
            "fs" to "1",
            "im" to "0",
            "uid" to "142420656",
            "usk" to "2a5d97d05dc8fe238150184eaf3519ad",
            "id" to songId
        )
        // python: secret = makesecret(params 含 id) -> 随后 del params["id"]，签名必须带 id
        val secret = JooxCrypto.makesecret(params)
        params.remove("id")
        val pathId = songId.replace("/", "_")
        val httpUrl = "https://cache.api.joox.com/openjoox2/v1/track/$pathId".toHttpUrlOrNull() ?: return null
        val b = httpUrl.newBuilder()
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        b.addQueryParameter("secret", secret)
        val req = Request.Builder().url(b.build()).headers(reqHeaders()).build()
        val resp = Net.client().newCall(req).execute()
        val json = JSONObject(resp.body?.string() ?: "{}")
        metaCache[songId] = json.toString()
        return json
    }

    /** 第三方 gdstudio 接口：time + MD5 签名 -> 下载地址。 */
    private fun resolveGdStudio(songId: String, host: String, version: String): String? {
        val timeUrl = "https://$host/time"
        val timeReq = Request.Builder().url(timeUrl).headers(gdHeaders(host)).build()
        val timeResp = Net.client().newCall(timeReq).execute()
        val serverTime = (timeResp.body?.string() ?: "").trim()
        if (serverTime.isEmpty()) return null
        val st = if (serverTime.length >= 9) serverTime.substring(0, 9) else serverTime
        val rawSign = "$st|$host|${JooxCrypto.normalizeVersion(version)}|${JooxCrypto.jsEncode(songId)}"
        val s = JooxCrypto.signForGdStudio(rawSign)
        val apiUrl = "https://$host/api.php"
        val body = FormBody.Builder()
            .add("types", "url")
            .add("id", songId)
            .add("source", "joox")
            .add("br", "999")
            .add("s", s)
            .build()
        val req = Request.Builder().url(apiUrl).headers(gdHeaders(host)).post(body).build()
        val resp = Net.client().newCall(req).execute()
        val json = JSONObject(resp.body?.string() ?: "{}")
        val u = json.optString("url")
        if (u.startsWith("http")) {
            val tested = AudioLinkTester.test(u)
            if (tested != null) return tested.url
        }
        return null
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        withTimeoutOrNull(20_000) {
            try {
                // 1) 官方接口
                var url: String? = null
                val meta = runCatching { getMeta(song.songId) }.getOrNull()
                if (meta != null) {
                    val list = meta.optJSONArray("play_url_list")
                    if (list != null) {
                        for (i in 0 until list.length()) {
                            val u = list.optString(i)
                            if (u.startsWith("http")) {
                                val tested = AudioLinkTester.test(u)
                                if (tested != null) { url = tested.url; break }
                            }
                        }
                    }
                    if (url == null) {
                        val refrain = meta.optString("refrain_url")
                        if (refrain.startsWith("http")) {
                            val tested = AudioLinkTester.test(refrain)
                            if (tested != null) url = tested.url
                        }
                    }
                }
                // 2) 回退第三方 gdstudio
                if (url == null) url = resolveGdStudio(song.songId, "music.gdstudio.xyz", "2026.08.01")
                if (url == null) url = resolveGdStudio(song.songId, "music.gdstudio.org", "2026.08.01")
                if (url != null) {
                    song.playUrl = url
                    url
                } else null
            } catch (_: Exception) {
                null
            }
        }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.lrc.isNotBlank()) return@withContext song.lrc
        try {
            val metaStr = metaCache[song.songId] ?: runCatching { getMeta(song.songId)?.toString() }.getOrNull()
            val json = metaStr?.let { JSONObject(it) } ?: return@withContext null
            val lrcB64 = json.optString("lrc_content")
            if (lrcB64.isBlank()) return@withContext null
            val decoded = String(Base64.getDecoder().decode(lrcB64), Charsets.UTF_8).trim()
            if (decoded.isBlank()) null else decoded
        } catch (_: Exception) {
            null
        }
    }
}
