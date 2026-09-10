package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * MOOV 音乐源（MOOVMusicClient 移植）：moov.hk / mtg.now.com（港台站）。
 *
 * 接口说明：
 * - 搜索：GET https://search-hk.moov-music.com/search/api/search/search ，结果在
 *   dataObject.primarySearch + secondarySearch。
 * - 解析播放地址：getProductDetail 拿详情后，对每种音质 checkout 拿 playUrl（HLS m3u8 流）。
 * - 歌词：GET https://mtg.now.com/moov/api/lyric/getLyric?pid=...
 * - 已知限制：MOOV 播放地址是 HLS(m3u8)，AudioLinkTester 仅认音频直链，故本源在 test 失败时直接返回
 *   m3u8 直链（仍是真实地址），但普通音频播放器可能不支持 HLS；且 checkout 可能需要设备注册，可能拿不到地址。
 */
class MoovMusicSource : MusicSource {
    override val id = "moov"
    override val label = "MOOV音乐"

    private val deviceId = UUID.randomUUID().toString().uppercase()

    private val searchHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36",
        "Origin" to "https://moov.hk",
        "Referer" to "https://moov.hk/",
        "Accept" to "application/json, text/javascript, */*; q=0.01"
    )

    private val parseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36",
        "Origin" to "https://moov.hk",
        "Referer" to "https://moov.hk/",
        "Accept" to "application/json, text/javascript, */*; q=0.01"
    )

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val params = mapOf(
                "value" to keyword,
                "type" to "product",
                "from" to "0",
                "count" to pageSize.toString(),
                "_" to (System.currentTimeMillis()).toString()
            )
            val url = buildUrl("https://search-hk.moov-music.com/search/api/search/search", params)
            val req = Request.Builder().url(url).headers(headersOf(searchHeaders)).get().build()
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val json = JSONObject(resp.body?.string().orEmpty())
            val dataObject = json.optJSONObject("dataObject") ?: return@withContext emptyList()
            val primary = dataObject.optJSONArray("primarySearch") ?: JSONArray()
            val secondary = dataObject.optJSONArray("secondarySearch") ?: JSONArray()
            val combined = JSONArray()
            for (i in 0 until primary.length()) combined.put(primary.opt(i))
            for (i in 0 until secondary.length()) combined.put(secondary.opt(i))

            val list = ArrayList<Song>()
            for (i in 0 until combined.length()) {
                val item = combined.optJSONObject(i) ?: continue
                val pid = item.optString("productId").ifBlank { continue }
                val title = item.optString("productTitle").ifBlank { continue }
                val artist = joinArtists(item.opt("artists"))
                val album = item.optString("albumTitle")
                val cover = item.optString("image").ifBlank { item.optString("albumCover") }.ifBlank { item.optString("thumbnail") }
                val duration = item.optInt("productLength", 0)
                list.add(
                    Song(
                        songId = pid,
                        source = id,
                        title = legalizeString(title),
                        artist = legalizeString(artist),
                        album = legalizeString(album),
                        durationSec = duration,
                        coverUrl = cover,
                        playUrl = "",
                        lrc = "",
                        ext = "m3u8",
                        fileSizeBytes = 0L
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        withTimeoutOrNull(20_000) {
            val url = resolveMoov(song.songId)
            if (url != null) {
                song.playUrl = url
                song.ext = "m3u8"
                url
            } else null
        }
    }

    private fun resolveMoov(pid: String): String? {
        return try {
            // 1) 拿详情（合并 qualities 等）
            val detailUrl = "https://mtg.now.com/moov/api/content/getProductDetail?productId=$pid"
            val detailReq = Request.Builder().url(detailUrl).headers(headersOf(parseHeaders)).get().build()
            val detailResp = Net.client().newCall(detailReq).execute()
            val detail = JSONObject(detailResp.body?.string().orEmpty()).optJSONObject("dataObject") ?: JSONObject()

            val qualitiesRaw = detail.optString("qualities", "HD")
            val qualities = qualitiesRaw.split(",").map { it.trim() }.filter { it.isNotBlank() }.reversed()
            val qualityList = if (qualities.isEmpty()) listOf("HD") else qualities

            for (q in qualityList) {
                val params = mapOf(
                    "deviceid" to deviceId,
                    "devicetype" to "web",
                    "cat" to "playlist",
                    "refid" to "",
                    "reftype" to "",
                    "pid" to pid,
                    "preview" to "F",
                    "connect" to "web",
                    "streamtype" to "stdHlsSgl",
                    "quality" to q,
                    "application" to "moovnext",
                    "clientver" to "2.0.6",
                    "carrier" to "csl"
                )
                val url = buildUrl("https://mtg.now.com/moov/api/content/checkout", params)
                val req = Request.Builder().url(url).headers(headersOf(parseHeaders)).get().build()
                val resp = Net.client().newCall(req).execute()
                if (!resp.isSuccessful) continue
                val json = JSONObject(resp.body?.string().orEmpty())
                val dataObject = json.optJSONObject("result")?.optJSONObject("dataObject") ?: continue
                var dl = dataObject.optString("playUrl").takeIf { it.startsWith("http") }
                if (dl == null) dl = dataObject.optString("playUrlValidate").takeIf { it.startsWith("http") }
                if (dl.isNullOrBlank()) continue
                // HLS 流：AudioLinkTester 不认 m3u8，直接返回真实地址
                return dl
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.lrc.isNotBlank()) return@withContext song.lrc
        try {
            val url = "https://mtg.now.com/moov/api/lyric/getLyric?pid=${song.songId}&_=${System.currentTimeMillis()}"
            val req = Request.Builder().url(url).headers(headersOf(parseHeaders)).get().build()
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext null
            val json = JSONObject(resp.body?.string().orEmpty())
            val lyric = json.optJSONObject("dataObject")?.optString("lyric").orEmpty()
            val cleaned = cleanLrc(lyric)
            if (cleaned.isBlank()) null else cleaned.also { song.lrc = it }
        } catch (_: Exception) {
            null
        }
    }
}
