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
import java.util.concurrent.ConcurrentHashMap

/**
 * 酷狗音乐源（KugouMusicClient 移植）。
 *
 * 接口说明：
 * - 搜索：GET https://songsearch.kugou.com/song_search_v2 （JSON，data.lists），取 hash 作为 songId。
 * - 解析播放地址（免登录）：
 *   主用 trackercdn 免费接口：key = md5(hash + "kgcloudv2")，
 *   GET https://trackercdn.kugou.com/i/v2/?...&hash=..&key=.. 拿到 url；
 *   兜底用 www.kugou.com/yy/index.php?r=play/getdata ，其 data.play_url 为 base64 编码的真实地址。
 * - 歌词：krcs.kugou.com/search 拿 candidates，再 lyrics.kugou.com/download 拿 base64 歌词。
 * - 已知限制：更高音质（Hires/Lossless）需登录签名；免费接口一般仅给标准/高品 mp3/flac。
 */
class KugouMusicSource : MusicSource {
    override val id = "kugou"
    override val label = "酷狗音乐"

    private val searchHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
    )

    private val detailHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
        "Referer" to "https://www.kugou.com/"
    )

    // hash -> album_id（备用，高音质解析可能需要，这里仅缓存）
    private val albumIdCache = ConcurrentHashMap<String, String>()

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val params = mapOf(
                "format" to "json",
                "keyword" to keyword,
                "platform" to "WebFilter",
                "page" to "1",
                "pagesize" to pageSize.toString()
            )
            val url = buildUrl("https://songsearch.kugou.com/song_search_v2", params)
            val req = Request.Builder().url(url).headers(headersOf(searchHeaders)).get().build()
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val json = JSONObject(resp.body?.string().orEmpty())
            val arr = json.optJSONObject("data")?.optJSONArray("lists") ?: return@withContext emptyList()

            val list = ArrayList<Song>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val hash = item.optString("hash").ifBlank { item.optString("FileHash") }.ifBlank { continue }
                val title = item.optString("songname").ifBlank { item.optString("SongName") }
                    .ifBlank { item.optString("songname_original") }.ifBlank { item.optString("OriSongName") }
                    .ifBlank { item.optString("filename") }.ifBlank { continue }
                val artist = item.optString("singername").ifBlank { item.optString("SingerName") }
                    .ifBlank { joinArtists(item.opt("singerinfo") ?: item.opt("Singers")) }
                val album = item.optString("album_name").ifBlank { item.optString("AlbumName") }
                    .ifBlank { item.optJSONObject("albuminfo")?.optString("name") ?: "" }
                val cover = item.optJSONObject("trans_param")?.optString("union_cover")
                    ?.replace("{size}", "400")
                    ?: item.optString("cover_url").ifBlank { item.optString("Image") }
                val durationSec = run {
                    val d = item.optString("duration").ifBlank { item.optString("Duration") }
                    if (d.isNotBlank()) d.toDoubleOrNull()?.toLong() ?: 0L
                    else (item.optDouble("timelen", 0.0) / 1000.0).toLong()
                }
                val albumId = item.optString("album_id")
                if (albumId.isNotBlank()) albumIdCache[hash] = albumId
                list.add(
                    Song(
                        songId = hash,
                        source = id,
                        title = legalizeString(title),
                        artist = legalizeString(artist),
                        album = legalizeString(album),
                        durationSec = durationSec.toInt(),
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

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        withTimeoutOrNull(20_000) {
            val url = resolveKugou(song.songId)
            if (url != null) {
                song.playUrl = url
                url
            } else null
        }
    }

    private fun resolveKugou(hash: String): String? {
        // 主用 trackercdn 免费接口
        val tracker = tryTrackerCdn(hash)
        if (tracker != null) return tracker
        // 兜底 play/getdata
        return tryPlayGetData(hash)
    }

    private fun tryTrackerCdn(hash: String): String? {
        return try {
            val key = md5Hex(hash + "kgcloudv2")
            val params = mapOf(
                "cdnBackup" to "1",
                "behavior" to "download",
                "pid" to "1",
                "cmd" to "21",
                "appid" to "1001",
                "hash" to hash,
                "key" to key
            )
            val url = buildUrl("https://trackercdn.kugou.com/i/v2/", params)
            val req = Request.Builder().url(url).headers(headersOf(detailHeaders)).get().build()
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return null
            val json = JSONObject(resp.body?.string().orEmpty())
            val dl = firstHttpUrl(json.opt("url")) ?: firstHttpUrl(json.opt("backup_url")) ?: firstHttpUrl(json.opt("backupUrl"))
            if (dl.isNullOrBlank()) return null
            AudioLinkTester.test(dl)?.url ?: dl
        } catch (_: Exception) {
            null
        }
    }

    private fun tryPlayGetData(hash: String): String? {
        return try {
            val url = "https://www.kugou.com/yy/index.php?r=play/getdata&hash=$hash"
            val req = Request.Builder().url(url).headers(headersOf(detailHeaders)).get().build()
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return null
            val json = JSONObject(resp.body?.string().orEmpty())
            val data = json.optJSONObject("data") ?: return null
            val b64 = data.optString("play_url").ifBlank { data.optString("play_backup_url") }.ifBlank { return null }
            val decoded = try { b64decodeStr(b64) } catch (_: Exception) { return null }
            if (!decoded.startsWith("http")) return null
            AudioLinkTester.test(decoded)?.url ?: decoded
        } catch (_: Exception) {
            null
        }
    }

    /** 从可能是字符串或数组的字段里取第一个 http(s) URL。 */
    private fun firstHttpUrl(v: Any?): String? {
        return when (v) {
            is String -> v.takeIf { it.startsWith("http") }
            is JSONArray -> {
                for (i in 0 until v.length()) {
                    val s = v.optString(i)
                    if (s.startsWith("http")) return s
                }
                null
            }
            else -> null
        }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.lrc.isNotBlank()) return@withContext song.lrc
        try {
            val params = mapOf(
                "keyword" to song.title,
                "duration" to song.durationSec.toString(),
                "hash" to song.songId,
                "ver" to "1",
                "man" to "yes",
                "client" to "mobi"
            )
            val searchUrl = buildUrl("https://krcs.kugou.com/search", params)
            val req = Request.Builder().url(searchUrl).headers(headersOf(searchHeaders)).get().build()
            val resp = Net.client().newCall(req).execute()
            val json = JSONObject(resp.body?.string().orEmpty())
            val candidates = json.optJSONArray("candidates") ?: return@withContext null
            if (candidates.length() == 0) return@withContext null
            val c = candidates.optJSONObject(0) ?: return@withContext null
            val id = c.optString("id").ifBlank { return@withContext null }
            val accesskey = c.optString("accesskey").ifBlank { return@withContext null }
            val dlUrl = "https://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=${URLEncoder.encode(accesskey, "UTF-8")}&fmt=lrc&charset=utf8"
            val dlReq = Request.Builder().url(dlUrl).headers(headersOf(detailHeaders)).get().build()
            val dlResp = Net.client().newCall(dlReq).execute()
            val dlJson = JSONObject(dlResp.body?.string().orEmpty())
            val content = dlJson.optString("content").ifBlank { return@withContext null }
            val decoded = try { b64decodeStr(content) } catch (_: Exception) { return@withContext null }
            val cleaned = cleanLrc(decoded)
            if (cleaned.isBlank()) null else cleaned.also { song.lrc = it }
        } catch (_: Exception) {
            null
        }
    }
}
