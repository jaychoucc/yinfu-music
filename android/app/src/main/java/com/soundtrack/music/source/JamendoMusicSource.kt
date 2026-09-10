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
import java.security.MessageDigest

/**
 * Jamendo 音乐源（网站内部 API，无需 client_id）。
 * 搜索: https://www.jamendo.com/api/search?query=...&type=track   (需 x-jam-call 签名头)
 * 播放: 从搜索结果的 stream/download 对象取直链，或 prod-1.storage.jamendo.com 兜底。
 * 对应 ref_all/jamendo.py。x-jam-call = "$(sha1(path+random))*random~"。
 */
class JamendoMusicSource : MusicSource {
    override val id = "jamendo"
    override val label = "Jamendo"

    private val headersBase = mapOf(
        "referer" to "https://www.jamendo.com/search?q=musicdl",
        "sec-fetch-dest" to "empty",
        "sec-fetch-mode" to "cors",
        "sec-fetch-site" to "same-origin",
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36",
        "x-jam-version" to "4rkl5f",
        "x-requested-with" to "XMLHttpRequest"
    )

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val url = "https://www.jamendo.com/api/search?" + buildQuery(mapOf(
                "query" to keyword,
                "type" to "track",
                "limit" to pageSize.toString(),
                "offset" to "0",
                "identities" to "www"
            ))
            val req = Request.Builder().url(url)
                .headers(okhttp3.Headers.headersOf(*(headersBase + ("x-jam-call" to makeXJamCall("/api/search"))).flatMap { listOf(it.key, it.value) }.toTypedArray()))
                .build()
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val body = resp.body?.string().orEmpty()

            val arr = when (val v = org.json.JSONTokener(body).nextValue()) {
                is JSONArray -> v
                is JSONObject -> v.optJSONArray("results") ?: v.optJSONArray("data") ?: JSONArray()
                else -> JSONArray()
            }

            val list = mutableListOf<Song>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val trackId = item.optString("id").ifBlank { continue }
                val name = legalize(item.optString("name"))
                if (name.isBlank()) continue

                val artistObj = item.optJSONObject("artist")
                val artist = legalize(artistObj?.optString("name").orEmpty())
                val albumObj = item.optJSONObject("album")
                val album = legalize(albumObj?.optString("name").orEmpty())
                val albumId = albumObj?.optString("id").orEmpty()
                val durationSec = item.optDouble("duration", 0.0).toInt()

                val cover = if (albumId.isNotBlank())
                    "https://usercontent.jamendo.com?type=album&id=$albumId&width=300&trackid=$trackId"
                else item.optString("image")

                val token = pickCandidateUrl(item).orEmpty()

                list.add(Song(
                    songId = trackId,
                    source = this@JamendoMusicSource.id,
                    title = name,
                    artist = artist,
                    album = album,
                    durationSec = durationSec,
                    coverUrl = cover,
                    playUrl = "",
                    lrc = "",
                    ext = "mp3",
                    fileSizeBytes = 0L,
                    token = token
                ))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        if (song.songId.isBlank()) return@withContext null
        val candidates = mutableListOf<String>()
        if (song.token.isNotBlank()) candidates.add(song.token)
        candidates.add("https://prod-1.storage.jamendo.com/download/track/" + song.songId + "/flac/")
        withTimeoutOrNull(20_000) {
            for (raw in candidates) {
                val tested = AudioLinkTester.test(raw)
                if (tested != null) {
                    song.playUrl = tested.url
                    return@withTimeoutOrNull tested.url
                }
            }
            null
        }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.lrc.isNotBlank()) return@withContext song.lrc
        if (song.songId.isBlank()) return@withContext null
        try {
            val url = "https://www.jamendo.com/api/tracks?" + buildQuery(mapOf("id[]" to song.songId))
            val req = Request.Builder().url(url)
                .headers(okhttp3.Headers.headersOf(*(headersBase + ("x-jam-call" to makeXJamCall("/api/tracks"))).flatMap { listOf(it.key, it.value) }.toTypedArray()))
                .build()
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext null
            val arr = org.json.JSONTokener(resp.body?.string().orEmpty()).nextValue()
            val obj = when (arr) {
                is JSONArray -> arr.optJSONObject(0)
                is JSONObject -> arr
                else -> null
            }
            val lyric = obj?.optString("lyrics").orEmpty().trim()
            if (lyric.isNotBlank()) {
                song.lrc = lyric
                lyric
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun pickCandidateUrl(item: JSONObject): String? {
        val stream = item.optJSONObject("stream")
        val download = item.optJSONObject("download")
        val order = listOf(
            stream to "flac", stream to "mp33", stream to "mp32", stream to "mp3",
            download to "flac", download to "mp3", stream to "ogg", download to "ogg"
        )
        for ((obj, key) in order) {
            val u = obj?.optString(key).orEmpty()
            if (u.startsWith("http")) return u
        }
        return null
    }

    private fun makeXJamCall(path: String): String {
        val rand = Math.random().toString()
        val sha = sha1Hex((path + rand).toByteArray(Charsets.UTF_8))
        return "\$$sha*$rand~"
    }

    private fun sha1Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun buildQuery(params: Map<String, String>): String =
        params.map { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }.joinToString("&")

    private fun legalize(s: String?): String {
        if (s == null) return ""
        return s.replace("\u0000", "").replace("\u200b", "").trim()
    }
}
