package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * TuneHub TuneHubMusicClient 移植：https://tunehub.sayqz.com/
 * 默认三源 netease / qq / kuwo：
 * - netease：走公共 Meting API (api.qijieya.cn/meting) 搜索/链接/歌词/封面
 * - qq/kuwo：直接调各自搜索接口拿 id，再用 TuneHub parse 接口解真实链接
 * X-API-Key 由 REQUEST_API_KEYS 派生（base64 解码 key[14:]）。海外/聚合站需带 UA/Referer。
 */
class TuneHubMusicSource : MusicSource {
    override val id = "tunehub"
    override val label = "TuneHub"

    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
    private val METING = "https://api.qijieya.cn/meting/"
    private val TUNEHUB_PARSE = "https://tunehub.sayqz.com/api/v1/parse?"
    private val TUNEHUB_PARSE_FALLBACK = "https://api.yexin.de5.net/api/tunehub/parse?"
    private val NETEASE_BRS = listOf("400", "380", "320", "128")
    private val TUNEHUB_QUALITIES = listOf("flac24bit", "flac", "320k", "128k")

    private val apiKey: String = try {
        val raw = "charlespikachudGhfZGQ2YzNmZDFhZjI1ZTkyNTZmODY5YjU4MzkyNjhiZGNhMjlhYjcwZGY5ZmU4NWYy"
        String(Base64.getDecoder().decode(raw.substring(14)), Charsets.UTF_8)
    } catch (e: Exception) { "" }

    // OkHttp 4 起 MediaType.parse 已移除，改用扩展函数
    private val jsonMediaType = "application/json; charset=utf-8".toMediaTypeOrNull()

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        val list = ArrayList<Song>()
        try {
            // netease (Meting)
            try {
                val url = METING + "server=netease&type=search&id=" + java.net.URLEncoder.encode(keyword, "UTF-8")
                val req = Request.Builder().url(url).header("User-Agent", UA).header("Referer", "https://www.google.com/").get().build()
                val resp = Net.client().newCall(req).execute()
                val arr = try { JSONArray(resp.body?.string().orEmpty()) } catch (e: Exception) { null }
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        if (list.size >= pageSize) break
                        val item = arr.optJSONObject(i) ?: continue
                        val songId = item.optString("id").ifBlank { continue }
                        val title = item.optString("name").ifBlank { continue }
                        val artist = item.optString("artist")
                        val album = item.optString("album")
                        val cover = item.optString("pic")
                        list.add(song(songId, title, artist, album, cover, "netease"))
                    }
                }
            } catch (e: Exception) { }

            // qq (musicu.fcg)
            if (list.size < pageSize) {
                try {
                    val payload = """{"req_1":{"method":"DoSearchForQQMusicDesktop","module":"music.search.SearchCgiService","param":{"num_per_page":$pageSize,"page_num":1,"query":${JSONObject.quote(keyword)},"search_type":"0"}}}"""
                    val body = RequestBody.create(jsonMediaType, payload)
                    val req = Request.Builder().url("https://u.y.qq.com/cgi-bin/musicu.fcg")
                        .header("User-Agent", UA)
                        .header("Referer", "https://y.qq.com/")
                        .header("Content-Type", "application/json")
                        .post(body).build()
                    val resp = Net.client().newCall(req).execute()
                    val root = JSONObject(resp.body?.string().orEmpty())
                    val songs = root.optJSONObject("req_1")?.optJSONObject("data")?.optJSONObject("body")
                        ?.optJSONObject("song")?.optJSONArray("list")
                    if (songs != null) {
                        for (i in 0 until songs.length()) {
                            if (list.size >= pageSize) break
                            val x = songs.optJSONObject(i) ?: continue
                            val songId = x.optString("mid").ifBlank { continue }
                            val title = x.optString("name").ifBlank { continue }
                            val singers = x.optJSONArray("singer")
                            val artist = if (singers != null) (0 until singers.length()).mapNotNull { singers.optJSONObject(it)?.optString("name") }.joinToString(", ") else ""
                            val albumObj = x.optJSONObject("album")
                            val albumMid = albumObj?.optString("mid").orEmpty()
                            val album = albumObj?.optString("name") ?: ""
                            val cover = if (albumMid.isNotBlank())
                                "https://y.gtimg.cn/music/photo_new/T002R300x300M000$albumMid.jpg"
                            else ""
                            list.add(song(songId, title, artist, album, cover, "qq"))
                        }
                    }
                } catch (e: Exception) { }
            }

            // kuwo (search.kuwo.cn/r.s)
            if (list.size < pageSize) {
                try {
                    val params = mapOf(
                        "client" to "kt", "all" to keyword, "pn" to "0", "rn" to pageSize.toString(),
                        "uid" to "794762570", "ver" to "kwplayer_ar_9.2.2.1", "vipver" to "1",
                        "show_copyright_off" to "1", "newver" to "1", "ft" to "music", "cluster" to "0",
                        "strategy" to "2012", "encoding" to "utf8", "rformat" to "json", "vermerge" to "1",
                        "mobi" to "1", "issubtitle" to "1"
                    )
                    val urlBuilder = "http://search.kuwo.cn/r.s".toHttpUrl().newBuilder()
                    for ((k, v) in params) urlBuilder.addQueryParameter(k, v.toString())
                    val req = Request.Builder().url(urlBuilder.build())
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36").get().build()
                    val resp = Net.client().newCall(req).execute()
                    val root = JSONObject(resp.body?.string().orEmpty())
                    val abs = root.optJSONArray("abslist")
                    if (abs != null) {
                        for (i in 0 until abs.length()) {
                            if (list.size >= pageSize) break
                            val it = abs.optJSONObject(i) ?: continue
                            val rid = it.optString("MUSICRID").replace("MUSIC_", "").ifBlank { continue }
                            val title = it.optString("SONGNAME").ifBlank { continue }
                            val artist = (it.optString("ARTIST") ?: "").replace("&", ", ")
                            val album = it.optString("ALBUM")
                            val pic = it.optString("pic").ifBlank { "" }
                            val cover = if (pic.isNotBlank()) pic
                                else "https://img4.kuwo.cn/star/albumcover/300/$rid.jpg"
                            list.add(song(rid, title, artist, album, cover, "kuwo"))
                        }
                    }
                } catch (e: Exception) { }
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun song(songId: String, title: String, artist: String, album: String, cover: String, root: String): Song =
        Song(
            songId = songId,
            source = this@TuneHubMusicSource.id,
            title = title,
            artist = artist,
            album = album,
            durationSec = 0,
            coverUrl = cover,
            playUrl = "",
            lrc = "",
            ext = "mp3",
            fileSizeBytes = 0L,
            token = root
        )

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        withTimeoutOrNull(20_000) {
            try {
                when (song.token) {
                    "netease" -> resolveNetease(song)
                    "qq", "kuwo" -> resolveTunehub(song)
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun resolveNetease(song: Song): String? {
        for (br in NETEASE_BRS) {
            val url = METING + "server=netease&type=url&id=" + song.songId + "&br=" + br
            val req = Request.Builder().url(url)
                .header("User-Agent", UA).header("Referer", "https://www.google.com/").head().build()
            val resp = Net.client().newCall(req).execute()
            val finalUrl = resp.request.url.toString()
            resp.close()
            if (!finalUrl.startsWith("http") || finalUrl.contains("error")) continue
            val tested = AudioLinkTester.test(finalUrl)
            if (tested != null) {
                song.playUrl = tested.url
                song.ext = tested.ext
                song.fileSizeBytes = tested.contentLength
                return tested.url
            }
        }
        return null
    }

    private fun resolveTunehub(song: Song): String? {
        val platform = song.token
        for (quality in TUNEHUB_QUALITIES) {
            val payload = """{"quality":${JSONObject.quote(quality)},"ids":${JSONObject.quote(song.songId)},"platform":${JSONObject.quote(platform)}}"""
            val body = RequestBody.create(jsonMediaType, payload)
            val tested = tryEndpoint(TUNEHUB_PARSE, body) ?: tryEndpoint(TUNEHUB_PARSE_FALLBACK, body)
            if (tested != null) {
                song.playUrl = tested.first
                song.ext = tested.second
                return tested.first
            }
        }
        return null
    }

    private fun tryEndpoint(baseUrl: String, body: RequestBody): Pair<String, String>? {
        return try {
            val req = Request.Builder().url(baseUrl)
                .header("User-Agent", UA)
                .header("X-API-Key", apiKey)
                .header("Content-Type", "application/json")
                .header("Origin", "https://7tangdagui.github.io")
                .header("Referer", "https://7tangdagui.github.io/")
                .post(body).build()
            val resp = Net.client().newCall(req).execute()
            val json = JSONObject(resp.body?.string().orEmpty())
            val url = json.optJSONObject("data")?.optJSONArray("data")?.optJSONObject(0)?.optString("url").takeIf { it?.startsWith("http") == true }
                ?: return null
            val tested = AudioLinkTester.test(url) ?: return null
            Pair(tested.url, tested.ext)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.lrc.isNotBlank()) return@withContext song.lrc
        try {
            when (song.token) {
                "netease" -> {
                    val url = METING + "server=netease&type=lrc&id=" + song.songId
                    val req = Request.Builder().url(url).header("User-Agent", UA).header("Referer", "https://www.google.com/").get().build()
                    val resp = Net.client().newCall(req).execute()
                    val text = resp.body?.string().orEmpty().trim()
                    if (text.isBlank() || text == "NULL") null else text.also { song.lrc = it }
                }
                "qq", "kuwo" -> {
                    // 歌词在 parse 接口的 lyrics 字段里（可能是文本或 http 链接）
                    val payload = """{"quality":"128k","ids":${JSONObject.quote(song.songId)},"platform":${JSONObject.quote(song.token)}}"""
                    val body = RequestBody.create(jsonMediaType, payload)
                    val req = Request.Builder().url(TUNEHUB_PARSE)
                        .header("User-Agent", UA).header("X-API-Key", apiKey)
                        .header("Content-Type", "application/json")
                        .header("Origin", "https://7tangdagui.github.io")
                        .header("Referer", "https://7tangdagui.github.io/")
                        .post(body).build()
                    val resp = Net.client().newCall(req).execute()
                    val json = JSONObject(resp.body?.string().orEmpty())
                    val lyrics = json.optJSONObject("data")?.optJSONArray("data")?.optJSONObject(0)?.optString("lyrics").orEmpty()
                    if (lyrics.startsWith("http")) {
                        val lreq = Request.Builder().url(lyrics).header("User-Agent", UA).get().build()
                        val ltext = Net.client().newCall(lreq).execute().body?.string().orEmpty().trim()
                        if (ltext.isBlank() || ltext == "NULL") null else ltext.also { song.lrc = it }
                    } else if (lyrics.isNotBlank() && lyrics != "NULL") {
                        val cleaned = lyrics.trim()
                        if (cleaned.startsWith("http")) null else cleaned.also { song.lrc = it }
                    } else null
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}
