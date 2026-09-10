package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * 千千音乐源（QianqianMusicClient 移植）：music.91q.com / taihe。
 *
 * 接口说明：
 * - 搜索 / 解析都需要参数签名：把参数按 key 排序后用 & 连接，再拼 secret 做 md5。
 *   secret 固定为 0b50b02fd0d73a9c4c8c3a781c30845f，并附带 timestamp。
 * - 搜索结果 data.typeTrack 给出 TSID / title / artist / albumTitle / pic / lyric(歌词URL)。
 * - 解析播放地址：GET /v1/song/tracklink，按 3000/320/128/64 音质依次尝试，取 data.path。
 * - 已知限制：服务端若要求登录 authorization 则搜索/解析会失败，本实现不带登录态，可能受限。
 */
class QianqianMusicSource : MusicSource {
    override val id = "qianqian"
    override val label = "千千音乐"

    private val APPID = "16073360"
    private val SECRET = "0b50b02fd0d73a9c4c8c3a781c30845f"

    private val searchHeaders = mapOf(
        "accept" to "*/*",
        "accept-language" to "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7",
        "referer" to "https://music.91q.com/player",
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36",
        "from" to "web"
    )

    // TSID -> 歌词 URL（搜索时记录，fetchLyric 时直接用）
    private val lyricUrlCache = ConcurrentHashMap<String, String>()

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val params = linkedMapOf(
                "word" to keyword,
                "type" to "1",
                "pageNo" to "1",
                "pageSize" to pageSize.toString(),
                "appid" to APPID
            )
            addSign(params)
            val url = buildUrl("https://music.91q.com/v1/search", params)
            val req = Request.Builder().url(url).headers(headersOf(searchHeaders)).get().build()
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val json = JSONObject(resp.body?.string().orEmpty())
            val arr = json.optJSONObject("data")?.optJSONArray("typeTrack") ?: return@withContext emptyList()

            val list = ArrayList<Song>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val tsid = item.optString("TSID").ifBlank { continue }
                val title = item.optString("title").ifBlank { continue }
                val artist = item.optJSONArray("artist")?.let { a ->
                    (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") }.joinToString(", ")
                } ?: ""
                val album = item.optString("albumTitle")
                val cover = item.optString("pic")
                val lyricUrl = item.optString("lyric")
                if (lyricUrl.startsWith("http")) lyricUrlCache[tsid] = lyricUrl
                list.add(
                    Song(
                        songId = tsid,
                        source = id,
                        title = legalizeString(title),
                        artist = legalizeString(artist),
                        album = legalizeString(album),
                        durationSec = 0,
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

    /** 给参数追加 timestamp 与 sign（与 Python _addsignandtstoparams 一致）。 */
    private fun addSign(params: LinkedHashMap<String, String>) {
        params["timestamp"] = (System.currentTimeMillis() / 1000).toString()
        val sorted = params.toSortedMap()
        val raw = sorted.entries.joinToString("&") { "${it.key}=${it.value}" }
        params["sign"] = md5Hex(raw + SECRET)
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        withTimeoutOrNull(20_000) {
            val url = resolveTrack(song.songId)
            if (url != null) {
                song.playUrl = url
                song.ext = "mp3"
                url
            } else null
        }
    }

    private fun resolveTrack(tsid: String): String? {
        val qualities = listOf("3000", "320", "128", "64")
        for (q in qualities) {
            try {
                val params = linkedMapOf(
                    "TSID" to tsid,
                    "appid" to APPID,
                    "rate" to q
                )
                addSign(params)
                val url = buildUrl("https://music.91q.com/v1/song/tracklink", params)
                val req = Request.Builder().url(url).headers(headersOf(searchHeaders)).get().build()
                val resp = Net.client().newCall(req).execute()
                if (!resp.isSuccessful) continue
                val json = JSONObject(resp.body?.string().orEmpty())
                val data = json.optJSONObject("data") ?: continue
                var dl = data.optString("path").takeIf { it.startsWith("http") }
                if (dl == null) {
                    dl = data.optJSONObject("trail_audio_info")?.optString("path")?.takeIf { it.startsWith("http") }
                }
                if (dl.isNullOrBlank()) continue
                // 千千直链多为 mp3，HEAD 校验通过即可；不通过也直接采用（仍是真链）
                AudioLinkTester.test(dl)
                return dl
            } catch (_: Exception) {
            }
        }
        return null
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.lrc.isNotBlank()) return@withContext song.lrc
        val lyricUrl = lyricUrlCache[song.songId] ?: return@withContext null
        try {
            val req = Request.Builder().url(lyricUrl).headers(headersOf(searchHeaders)).get().build()
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext null
            val text = resp.body?.string().orEmpty()
            val cleaned = cleanLrc(text)
            if (cleaned.isBlank()) null else cleaned.also { song.lrc = it }
        } catch (_: Exception) {
            null
        }
    }
}
