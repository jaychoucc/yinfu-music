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
 * SoundCloud 音乐源（手机端本地调用）。
 *
 * 限制说明（务必注意）：
 * 1. 搜索走公开接口 https://api-v2.soundcloud.com/search/tracks ，必需参数 client_id
 *    从 soundcloud.com 首页脚本正则抠取（照搬 Python 端 _setclientid），失败用硬编码兜底。
 * 2. 播放链接：搜索/曲目详情里的 media.transcodings 含多种流，本实现只取 protocol == "progressive"
 *    的明文流（mp3 优先），跳过 cbc-/ctr- DRM 流；最终流地址经 AudioLinkTester 校验后才返回。
 *    这样拿到的是真实可播放的音频（非 DRM）。有些曲目若只有 DRM 流，则 resolvePlayUrl 返回 null。
 * 3. 歌词未实现，返回 null（Python 端靠第三方 LyricSearchClient，移动端不复刻）。
 *
 * 参考：ref_all/soundcloud.py —— _constructsearchurls / _parsewithofficialapiv1 的 progressive 分支。
 */
class SoundCloudMusicSource : MusicSource {
    override val id = "soundcloud"
    override val label = "SoundCloud"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"
    )

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        val clientId = runCatching { SoundCloudUtils.obtainClientId() }.getOrNull()
        if (clientId.isNullOrBlank()) return@withContext emptyList()
        val q = URLEncoder.encode(keyword, "UTF-8")
        val url = "https://api-v2.soundcloud.com/search/tracks?q=$q&client_id=$clientId" +
            "&limit=$pageSize&offset=0&linked_partitioning=1"
        val req = Request.Builder().url(url)
            .headers(okhttp3.Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray()))
            .build()
        try {
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val body = resp.body?.string() ?: return@withContext emptyList()
            val json = JSONObject(body)
            val collection = json.optJSONArray("collection") ?: return@withContext emptyList()

            val list = ArrayList<Song>()
            for (i in 0 until collection.length()) {
                val item = collection.optJSONObject(i) ?: continue
                val trackId = item.optString("id").ifBlank { continue }
                val title = item.optString("title").ifBlank { continue }
                val artist = item.optJSONObject("user")?.optString("username")
                    ?: item.optJSONObject("publisher_metadata")?.optString("artist") ?: ""
                val durationSec = item.optInt("duration", 0) / 1000
                val cover = item.optString("artwork_url")
                val ext = guessExtFromItem(item)
                list.add(
                    Song(
                        songId = trackId,
                        source = this@SoundCloudMusicSource.id,
                        title = title,
                        artist = artist,
                        album = "",
                        durationSec = durationSec,
                        coverUrl = cover,
                        playUrl = "",
                        lrc = "",
                        ext = ext,
                        fileSizeBytes = 0L
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 从曲目的 transcodings 里挑一个 progressive 的 mp3 流来猜 ext（仅展示用） */
    private fun guessExtFromItem(item: JSONObject): String {
        val transcodings = item.optJSONObject("media")?.optJSONArray("transcodings") ?: return "mp3"
        for (i in 0 until transcodings.length()) {
            val t = transcodings.optJSONObject(i) ?: continue
            val proto = t.optJSONObject("format")?.optString("protocol") ?: ""
            if (proto != "progressive") continue
            val mime = t.optJSONObject("format")?.optString("mime_type") ?: ""
            val preset = t.optString("preset")
            if ("mpeg" in mime.lowercase() || "mp3" in preset.lowercase()) return "mp3"
        }
        return "mp3"
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        if (song.songId.isBlank()) return@withContext null
        val clientId = runCatching { SoundCloudUtils.obtainClientId() }.getOrNull()
        if (clientId.isNullOrBlank()) return@withContext null
        return@withContext withTimeoutOrNull(20_000) {
            try {
                // 取曲目详情，确保含 media.transcodings 与 track_authorization
                val detailUrl = "https://api-v2.soundcloud.com/tracks/${song.songId}?client_id=$clientId"
                val detailReq = Request.Builder().url(detailUrl)
                    .headers(okhttp3.Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray()))
                    .build()
                val detailResp = Net.client().newCall(detailReq).execute()
                val detail = JSONObject(detailResp.body?.string() ?: return@withTimeoutOrNull null)
                val trackAuth = detail.optString("track_authorization")
                val transcodings = detail.optJSONObject("media")?.optJSONArray("transcodings")
                    ?: return@withTimeoutOrNull null

                // 收集 progressive（明文）流，mp3 优先
                val candidates = ArrayList<JSONObject>()
                for (i in 0 until transcodings.length()) {
                    val t = transcodings.optJSONObject(i) ?: continue
                    val proto = t.optJSONObject("format")?.optString("protocol") ?: ""
                    if (proto != "progressive") continue // 跳过 cbc-/ctr- DRM
                    candidates.add(t)
                }
                candidates.sortWith(compareBy {
                    val mime = it.optJSONObject("format")?.optString("mime_type")?.lowercase() ?: ""
                    val preset = it.optString("preset").lowercase()
                    if ("mpeg" in mime || "mp3" in preset) 0 else 1
                })

                for (t in candidates) {
                    val policyUrl = t.optString("url").ifBlank { continue }
                    val streamReqUrl = policyUrl +
                        (if (policyUrl.contains("?")) "&" else "?") +
                        "client_id=$clientId" +
                        if (trackAuth.isNotBlank()) "&track_authorization=${URLEncoder.encode(trackAuth, "UTF-8")}" else ""
                    val streamReq = Request.Builder().url(streamReqUrl)
                        .headers(okhttp3.Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray()))
                        .build()
                    try {
                        val streamResp = Net.client().newCall(streamReq).execute()
                        val streamJson = JSONObject(streamResp.body?.string() ?: continue)
                        val finalUrl = streamJson.optString("url").ifBlank { continue }
                        if (!finalUrl.startsWith("http")) continue
                        val tested = AudioLinkTester.test(finalUrl)
                        if (tested != null) {
                            song.playUrl = tested.url
                            song.ext = tested.ext
                            return@withTimeoutOrNull tested.url
                        }
                    } catch (_: Exception) {
                        // 尝试下一个候选流
                    }
                }
                null
            } catch (_: Exception) {
                null
            }
        }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        // 歌词未实现（依赖第三方 LyricSearchClient），返回 null，不伪造
        null
    }
}
