package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID

/**
 * Suno AI 生成音乐源。
 * 注意：搜索需要登录后的 auth_token（Bearer），musicdl 原始实现也是从 cookie 读取，
 *       源码中没有硬编码默认值。本实现默认 authToken 为空 => search() 直接返回 emptyList()，
 *       不会崩溃。请在调用前通过 `SunoMusicSource.authToken = "xxx"` 注入有效 token 才能搜到歌。
 * 搜索: POST https://studio-api-prod.suno.com/api/unified/feed  (omnisearch_songs)
 * 播放: media_urls 中的直链，或兜底 https://cdn1.suno.ai/{id}.mp3
 * 对应 ref_all/suno.py。
 */
class SunoMusicSource : MusicSource {
    override val id = "suno"
    override val label = "Suno AI"

    /** 登录后从浏览器 Cookie 取到的 auth_token；为空则无法搜索。 */
    var authToken: String = ""

    private val feedUrl = "https://studio-api-prod.suno.com/api/unified/feed"
    private val cdnBase = "https://cdn1.suno.ai"

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        if (authToken.isBlank()) return@withContext emptyList()
        try {
            val bodyJson = JSONObject().apply {
                put("feed_id", "omnisearch_songs")
                put("cursor", JSONObject.NULL)
                put("page_size", pageSize)
                put("request_metadata", JSONObject().put("term", keyword))
            }
            val req = Request.Builder().url(feedUrl)
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .headers(okhttp3.Headers.headersOf(*searchHeaders().flatMap { listOf(it.key, it.value) }.toTypedArray()))
                .build()
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val json = JSONObject(resp.body?.string().orEmpty())
            val items = json.optJSONObject("feed")?.optJSONArray("items") ?: return@withContext emptyList()

            val list = mutableListOf<Song>()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                if (item.optString("content_type") != "clip") continue
                val clip = item.optJSONObject("content_item") ?: continue
                val clipId = clip.optString("id").ifBlank { continue }
                val title = legalize(clip.optString("title"))
                if (title.isBlank()) continue

                val persona = clip.optJSONObject("persona")
                val artist = legalize(persona?.optString("name").orEmpty().ifBlank { clip.optString("display_name") })
                val album = legalize(clip.optString("model_name"))
                val durationSec = (clip.optJSONObject("metadata")?.optDouble("duration", 0.0) ?: 0.0).toInt()
                val prompt = clip.optJSONObject("metadata")?.optString("prompt").orEmpty().trim()
                val cover = clip.optString("image_large_url").ifBlank { clip.optString("image_url") }
                val token = pickMediaUrl(clip).orEmpty()

                list.add(Song(
                    songId = clipId,
                    source = this@SunoMusicSource.id,
                    title = title,
                    artist = artist,
                    album = album,
                    durationSec = durationSec,
                    coverUrl = cover,
                    playUrl = "",
                    lrc = prompt,
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
        candidates.add(cdnBase + "/" + song.songId + ".mp3")
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
        // metadata.prompt 已在搜索阶段写入 lrc（真实文本，非伪造），直接返回
        if (song.lrc.isNotBlank()) song.lrc else null
    }

    private fun pickMediaUrl(clip: JSONObject): String? {
        val media = clip.optJSONArray("media_urls") ?: return clip.optString("audio_url").ifBlank { null }
        var m4a: String? = null
        var mp3: String? = null
        for (i in 0 until media.length()) {
            val m = media.optJSONObject(i) ?: continue
            val ct = m.optString("content_type")
            val u = m.optString("url").ifBlank { null } ?: continue
            when {
                ct == "m4a-opus" && m4a == null -> m4a = u
                ct == "mp3" && mp3 == null -> mp3 = u
            }
        }
        return m4a ?: mp3 ?: clip.optString("audio_url").ifBlank { null }
    }

    private fun searchHeaders(): Map<String, String> {
        val h = mutableMapOf(
            "accept" to "*/*",
            "content-type" to "application/json",
            "origin" to "https://suno.com",
            "referer" to "https://suno.com/",
            "browser-token" to browserToken(),
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36",
            "device-id" to UUID.randomUUID().toString()
        )
        h["authorization"] = "Bearer $authToken"
        return h
    }

    private fun browserToken(): String {
        val ts = System.currentTimeMillis()
        val inner = "{\"timestamp\":$ts}"
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(inner.toByteArray(Charsets.UTF_8))
        return "{\"token\":\"$b64\"}"
    }

    private fun legalize(s: String?): String {
        if (s == null) return ""
        return s.replace("\u0000", "").replace("\u200b", "").trim()
    }
}
