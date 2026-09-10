package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * GD音乐台 GDStudioMusicClient 移植：https://music.gdstudio.xyz/
 * - 搜索/下载/歌词均为 POST https://music.gdstudio.xyz/api.php
 * - 需带签名 s = md5("<server_time前9位>|music.gdstudio.xyz|<版本号归一>|<payload>")[-8:].upper()
 *   payload = jsencodeuricomponent(关键字 / url_id / lyric_id)
 * 多源 netease/joox/tidal/qobuz/apple/bilibili 分别搜索；下载失败回退 music-proxy.gdstudio.org。
 * 未实现封面（需额外 getpic 请求，省去以免请求过多）。
 */
class GDStudioMusicSource : MusicSource {
    override val id = "gdstudio"
    override val label = "GD音乐台"

    private val HOST = "music.gdstudio.xyz"
    private val BASE_URL = "https://music.gdstudio.xyz/"
    private val TIME_URL = "https://music.gdstudio.xyz/time"
    private val API_URL = "https://music.gdstudio.xyz/api.php"
    private val VERSION_TEXT = "20260801" // normalizeversion("2026.08.01")
    private val SOURCES = listOf("netease", "joox", "tidal", "qobuz", "apple", "bilibili")

    private val searchHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "Referer" to "https://music.gdstudio.xyz/",
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Origin" to "https://music.gdstudio.xyz"
    )

    private data class Meta(
        val urlId: String, val rootSource: String,
        val lyricId: String, val name: String, val artist: String
    )
    private val metaCache = ConcurrentHashMap<String, Meta>()

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        val list = ArrayList<Song>()
        try {
            val sign = makeSign(jsEncode(keyword))
            for (source in SOURCES) {
                if (list.size >= pageSize) break
                try {
                    val body = FormBody.Builder()
                        .add("types", "search")
                        .add("count", pageSize.toString())
                        .add("pages", "1")
                        .add("name", keyword)
                        .add("source", source)
                        .add("s", sign)
                        .build()
                    val req = Request.Builder().url(API_URL)
                        .headers(okhttp3.Headers.headersOf(*searchHeaders.flatMap { listOf(it.key, it.value) }.toTypedArray()))
                        .post(body).build()
                    val resp = Net.client().newCall(req).execute()
                    if (!resp.isSuccessful) continue
                    val text = resp.body?.string().orEmpty()
                    val arr = try { JSONArray(text) } catch (e: Exception) { null } ?: continue
                    for (i in 0 until arr.length()) {
                        if (list.size >= pageSize) break
                        val item = arr.optJSONObject(i) ?: continue
                        val songId = item.optString("id").ifBlank { continue }
                        val urlId = item.optString("url_id").ifBlank { continue }
                        val rootSource = item.optString("source").ifBlank { continue }
                        val name = item.optString("name").ifBlank { continue }
                        val artists = item.optJSONArray("artist")
                        val artist = if (artists != null) {
                            (0 until artists.length()).mapNotNull { artists.optString(it) }.joinToString(", ")
                        } else item.optString("artist")
                        val album = item.optString("album")
                        val lyricId = item.optString("lyric_id")
                        val extra = item.optJSONObject("extra_data")
                        val duration = extra?.optInt("duration", 0) ?: 0
                        // 尝试拿下载地址
                        val down = getSongUrl(urlId, rootSource) ?: continue
                        val downUrl = down.first ?: continue
                        metaCache[songId] = Meta(urlId, rootSource, lyricId, name, artist ?: "")
                        list.add(
                            Song(
                                songId = songId,
                                source = this@GDStudioMusicSource.id,
                                title = name,
                                artist = artist ?: "",
                                album = album,
                                durationSec = duration,
                                coverUrl = "",
                                playUrl = "",
                                lrc = "",
                                ext = down.second.ifEmpty { "mp3" },
                                fileSizeBytes = 0L
                            )
                        )
                    }
                } catch (e: Exception) {
                }
            }
            list
                } catch (e: Exception) {
            emptyList()
        }
    }

    /** returns Pair(url, ext) or null */
    private fun getSongUrl(urlId: String, source: String): Pair<String?, String>? {
        return try {
            val sign = makeSign(jsEncode(urlId))
            val body = FormBody.Builder()
                .add("types", "url")
                .add("id", urlId)
                .add("source", source)
                .add("br", "999")
                .add("s", sign)
                .build()
            val req = Request.Builder().url(API_URL)
                .headers(okhttp3.Headers.headersOf(*searchHeaders.flatMap { listOf(it.key, it.value) }.toTypedArray()))
                .post(body).build()
            val resp = Net.client().newCall(req).execute()
            val text = resp.body?.string().orEmpty()
            val json = JSONObject(text)
            var url = json.optString("url").takeIf { it.isNotBlank() } ?: return null
            val size = json.optString("size")
            val br = json.optString("br")
            if (size == "0" || br == "-1") return null
            if (!url.startsWith("http")) url = BASE_URL + url.removePrefix("/")
            val tested = AudioLinkTester.test(url)
                ?: AudioLinkTester.test("https://music-proxy.gdstudio.org/$url")
            if (tested != null) {
                Pair(tested.url, if (tested.ext in setOf("m4s", "mp4")) "m4a" else tested.ext)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        val meta = metaCache[song.songId] ?: return@withContext null
        withTimeoutOrNull(20_000) {
            val down = getSongUrl(meta.urlId, meta.rootSource)
            val url = down?.first ?: return@withTimeoutOrNull null
            song.playUrl = url
            song.ext = down.second.ifEmpty { "mp3" }
            url
        }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.lrc.isNotBlank()) return@withContext song.lrc
        val meta = metaCache[song.songId] ?: return@withContext null
        if (meta.lyricId.isBlank()) return@withContext null
        try {
            val sign = makeSign(jsEncode(meta.lyricId))
            val body = FormBody.Builder()
                .add("types", "lyric")
                .add("id", meta.lyricId)
                .add("source", meta.rootSource)
                .add("s", sign)
                .build()
            val req = Request.Builder().url(API_URL)
                .headers(okhttp3.Headers.headersOf(*searchHeaders.flatMap { listOf(it.key, it.value) }.toTypedArray()))
                .post(body).build()
            val resp = Net.client().newCall(req).execute()
            val text = resp.body?.string().orEmpty()
            val json = JSONObject(text)
            val lyric = json.optString("lyric").takeIf { it.isNotBlank() } ?: return@withContext null
            if (lyric in setOf("NULL", "null", "None", "none") || "歌词获取失败" in lyric) return@withContext null
            lyric.also { song.lrc = it }
        } catch (e: Exception) {
            null
        }
    }

    /** 等价于 Python jsencodeuricomponent：仅保留 A-Za-z0-9 与 -_. ，其余按 UTF-8 百分号大写编码 */
    private fun jsEncode(value: String): String {
        val sb = StringBuilder()
        for (c in value) {
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.') {
                sb.append(c)
            } else {
                for (b in c.toString().toByteArray(Charsets.UTF_8)) {
                    sb.append("%").append(String.format("%02X", b.toInt() and 0xFF))
                }
            }
        }
        return sb.toString()
    }

    private fun getServerTime(): String {
        return try {
            val req = Request.Builder().url(TIME_URL)
                .header("User-Agent", searchHeaders["User-Agent"] ?: "").get().build()
            val resp = Net.client().newCall(req).execute()
            val t = resp.body?.string().orEmpty().trim()
            if (t.matches(Regex("\\d{6,}"))) t else (System.currentTimeMillis() / 1000).toString()
        } catch (e: Exception) {
            (System.currentTimeMillis() / 1000).toString()
        }
    }

    private fun makeSign(payload: String): String {
        val ts = getServerTime()
        val prefix = if (ts.length >= 9) ts.substring(0, 9) else ts
        val input = "$prefix|${HOST}|${VERSION_TEXT}|$payload"
        val hash = md5Hex(input)
        return if (hash.length >= 8) hash.substring(hash.length - 8).uppercase() else hash.uppercase()
    }

    private fun md5Hex(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (b in bytes) sb.append(String.format("%02x", b.toInt() and 0xFF))
        return sb.toString()
    }
}
