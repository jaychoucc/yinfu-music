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
import java.util.concurrent.ConcurrentHashMap

/**
 * JioSaavn（印度音乐）音源。
 *
 * 实现方式：
 * 1. 搜索走公开接口 api.php?__call=search.getResults，结果里的 more_info.encrypted_media_url
 *    是经 DES 加密的媒体地址，本地 DES/ECB 解密后再经 AudioLinkTester 校验。
 * 2. 解密后的链接可按音质档位（320/160/96/48/12）归一化后逐个探测，取首个可用真实音频。
 * 3. 歌词走 lyrics.getLyrics，剥离 HTML 标签后返回；拿不到返回 null。
 *
 * 限制：完整/高码率音轨依赖 JioSaavn 服务端可用性，个别地区可能 403；解密失败则无播放链接。
 * 参考 ref_all/jiosaavn.py。
 */
class JiosaavnMusicSource : MusicSource {
    override val id = "jiosaavn"
    override val label = "JioSaavn"

    // songId -> 加密媒体地址（搜索阶段记录，播放阶段直接解密，避免重复请求）
    private val encCache = ConcurrentHashMap<String, String>()

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36",
        "Accept" to "application/json",
        "Content-Type" to "application/json"
    )

    private fun buildQuery(params: Map<String, String>): String =
        params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }

    private fun reqHeaders() =
        okhttp3.Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray())

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        val url = "https://www.jiosaavn.com/api.php?" + buildQuery(
            mapOf(
                "p" to "1",
                "q" to keyword,
                "_format" to "json",
                "_marker" to "0",
                "api_version" to "4",
                "ctx" to "web6dot0",
                "n" to pageSize.toString(),
                "__call" to "search.getResults"
            )
        )
        val req = Request.Builder().url(url).headers(reqHeaders()).build()
        try {
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val body = resp.body?.string() ?: return@withContext emptyList()
            val json = JSONObject(body)
            val results = json.optJSONArray("results") ?: return@withContext emptyList()

            val list = ArrayList<Song>()
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val sid = item.optString("id").ifBlank { continue }
                val title = item.optString("title").ifBlank { item.optString("song") }.ifBlank { continue }
                val moreInfo = item.optJSONObject("more_info")
                val enc = (moreInfo?.optString("encrypted_media_url") ?: "").ifBlank {
                    item.optString("encrypted_media_url")
                }
                if (enc.isNotBlank()) encCache[sid] = enc
                val artist = item.optString("primary_artists").ifBlank { item.optString("singers") }
                val album = item.optString("album")
                val cover = item.optString("image")
                val durStr = item.optString("duration").ifBlank { moreInfo?.optString("duration") ?: "" }
                val duration = durStr.toIntOrNull() ?: 0
                list.add(
                    Song(
                        songId = sid,
                        source = this@JiosaavnMusicSource.id,
                        title = title,
                        artist = artist,
                        album = album,
                        durationSec = duration,
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

    private fun normalizeMediaUrl(url: String, quality: String): String {
        val quals = listOf("12", "48", "96", "160", "320")
        val exts = listOf("mp4", "mp3", "m4a")
        var u = url
        // 把链接里已有的 _<码率>.<格式> 全部替换为目标码率（与 python _normalizemediaurl 等价）
        for (q in quals) for (e in exts) {
            u = u.replace("_${q}.${e}", "_${quality}.${e}")
        }
        return u
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        withTimeoutOrNull(20_000) {
            try {
                var enc = encCache[song.songId].orEmpty()
                if (enc.isBlank()) {
                    // 回退：直接用 song.getDetails 取加密地址
                    val detailUrl = "https://www.jiosaavn.com/api.php?" + buildQuery(
                        mapOf(
                            "__call" to "song.getDetails",
                            "cc" to "in",
                            "_format" to "json",
                            "_marker" to "0",
                            "pids" to song.songId
                        )
                    )
                    val req = Request.Builder().url(detailUrl).headers(reqHeaders()).build()
                    val resp = Net.client().newCall(req).execute()
                    val j = JSONObject(resp.body?.string() ?: "{}")
                    val detail = j.optJSONObject(song.songId)
                    enc = (detail?.optString("encrypted_media_url") ?: "").ifBlank {
                        detail?.optString("encrypted_drm_media_url") ?: ""
                    }
                    if (enc.isNotBlank()) encCache[song.songId] = enc
                }
                if (enc.isBlank()) return@withTimeoutOrNull null
                val base = JiosaavnCrypto.decryptUrl(enc)
                if (base.isBlank()) return@withTimeoutOrNull null
                val qualities = listOf("320", "160", "96", "48", "12")
                for (q in qualities) {
                    val u = normalizeMediaUrl(base, q)
                    val tested = AudioLinkTester.test(u)
                    if (tested != null) {
                        song.playUrl = tested.url
                        return@withTimeoutOrNull tested.url
                    }
                }
                null
            } catch (_: Exception) {
                null
            }
        }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.lrc.isNotBlank()) return@withContext song.lrc
        try {
            val url = "https://www.jiosaavn.com/api.php?" + buildQuery(
                mapOf(
                    "__call" to "lyrics.getLyrics",
                    "ctx" to "web6dot0",
                    "api_version" to "4",
                    "_format" to "json",
                    "_marker" to "0",
                    "lyrics_id" to song.songId
                )
            )
            val req = Request.Builder().url(url).headers(reqHeaders()).build()
            val resp = Net.client().newCall(req).execute()
            val json = JSONObject(resp.body?.string() ?: "{}")
            val raw = json.optString("lyrics").ifBlank { return@withContext null }
            val cleaned = raw.replace(Regex("<[^>]+>"), "\n").replace(Regex("\\n+"), "\n").trim()
            if (cleaned.isBlank()) null else cleaned
        } catch (_: Exception) {
            null
        }
    }
}
