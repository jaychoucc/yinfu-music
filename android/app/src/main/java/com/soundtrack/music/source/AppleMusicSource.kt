package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Apple Music 音源。
 *
 * 实现方式：
 *  - Apple Music 的公开搜索接口（amp-api-edge.music.apple.com）需要一个 developer JWT。该 token 并不保密，
 *    直接嵌在 music.apple.com 前端 JS 里。这里复刻 musicdl 的做法：抓取首页 -> 定位 index.js ->
 *    从 JS 中提取 "eyJ...eyJ...xxx" 形式的 JWT，作为 Bearer 使用。无需登录。
 *  - 搜索返回的每个歌曲都有 attributes.previews[0].url，是官方提供的真实 30 秒试听片段（m4a），
 *    可直接播放。完整音轨需要 Apple Music 订阅（media-user-token），本端不提供，故不伪造。
 *  - 歌词：非订阅账户通过公开接口通常拿不到 synced lyrics，尽力解析 resources.lyrics，没有则返回 null。
 *
 * 限制：仅 30 秒试听；若 Apple 改版导致 JWT 抓取失败，搜索返回空列表。
 * 参考 ref_all/apple.py 与 musicdl/modules/utils/appleutils.py。
 */
class AppleMusicSource : MusicSource {
    override val id = "apple"
    override val label = "Apple Music"

    private val geo = "us"
    private var devToken: String? = null

    private val baseHeaders = mapOf(
        "accept" to "*/*",
        "accept-language" to "en-US",
        "origin" to "https://music.apple.com",
        "referer" to "https://music.apple.com",
        "sec-fetch-dest" to "empty",
        "sec-fetch-mode" to "cors",
        "sec-fetch-site" to "same-site",
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
    )

    private fun authHeaders(): okhttp3.Headers {
        val token = devToken ?: return okhttp3.Headers.headersOf(*baseHeaders.flatMap { listOf(it.key, it.value) }.toTypedArray())
        val map = baseHeaders.toMutableMap()
        map["authorization"] = "Bearer $token"
        return okhttp3.Headers.headersOf(*map.flatMap { listOf(it.key, it.value) }.toTypedArray())
    }

    /** 抓取并缓存 Apple Music 公开 developer JWT。 */
    private fun ensureToken(): Boolean {
        if (!devToken.isNullOrBlank()) return true
        try {
            val homeReq = Request.Builder().url("https://music.apple.com/$geo").headers(
                okhttp3.Headers.headersOf(*baseHeaders.flatMap { listOf(it.key, it.value) }.toTypedArray())
            ).build()
            val homeResp = Net.client().newCall(homeReq).execute()
            val home = homeResp.body?.string() ?: return false
            val jsMatch = Regex("""/assets/index[~-][^/"]+\.js""").find(home) ?: return false
            val jsReq = Request.Builder().url("https://music.apple.com${jsMatch.value}").headers(
                okhttp3.Headers.headersOf(*baseHeaders.flatMap { listOf(it.key, it.value) }.toTypedArray())
            ).build()
            val jsResp = Net.client().newCall(jsReq).execute()
            val js = jsResp.body?.string() ?: return false
            val tokenMatch = Regex("""eyJ[A-Za-z0-9\-_]+\.eyJ[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+""").find(js) ?: return false
            devToken = tokenMatch.value
            return true
        } catch (_: Exception) {
            return false
        }
    }

    private fun formatArtwork(url: String): String =
        url.replace("{w}", "600").replace("{h}", "600").replace("{f}", "jpg")

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        if (!ensureToken()) return@withContext emptyList()
        val base = "https://amp-api-edge.music.apple.com/v1/catalog/$geo/search".toHttpUrlOrNull() ?: return@withContext emptyList()
        val b = base.newBuilder()
        b.addQueryParameter("term", keyword)
        b.addQueryParameter("types", "songs")
        b.addQueryParameter("limit", pageSize.toString())
        b.addQueryParameter("offset", "0")
        b.addQueryParameter("l", "en-US")
        b.addQueryParameter("with", "lyrics")
        b.addQueryParameter("platform", "web")
        b.addQueryParameter("include[songs]", "albums")
        val req = Request.Builder().url(b.build()).headers(authHeaders()).build()
        try {
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val json = JSONObject(resp.body?.string() ?: "{}")
            val songsObj = json.optJSONObject("resources")?.optJSONObject("songs") ?: return@withContext emptyList()

            val list = ArrayList<Song>()
            val keys = songsObj.keys()
            while (keys.hasNext()) {
                val key = keys.next() as String
                val item = songsObj.optJSONObject(key) ?: continue
                val id = item.optString("id").ifBlank { continue }
                val attrs = item.optJSONObject("attributes") ?: continue
                val title = attrs.optString("name").ifBlank { continue }
                val artist = attrs.optString("artistName")
                val album = attrs.optString("albumName")
                // artwork 在 v1 catalog 接口里是对象 {url, width, height...}，少数老接口直接给字符串
                val artworkUrl = attrs.optJSONObject("artwork")?.optString("url")
                    ?: attrs.optString("artwork").takeIf { it.startsWith("http") }
                val cover = artworkUrl?.let { formatArtwork(it) } ?: ""
                val durationMs = attrs.optLong("durationInMillis", 0L)
                val durationSec = (durationMs / 1000).toInt()
                val previews = attrs.optJSONArray("previews")
                val preview = previews?.optJSONObject(0)?.optString("url")?.takeIf { it.startsWith("http") } ?: ""
                list.add(
                    Song(
                        songId = id,
                        source = this@AppleMusicSource.id,
                        title = title,
                        artist = artist,
                        album = album,
                        durationSec = durationSec,
                        coverUrl = cover,
                        playUrl = preview,
                        lrc = "",
                        ext = "m4a",
                        fileSizeBytes = 0L
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 单首歌曲查询，取 previews[0] 试听地址（用于外部构造的 Song）。 */
    private fun getSongPreview(songId: String): String? {
        if (!ensureToken()) return null
        val url = "https://amp-api-edge.music.apple.com/v1/catalog/$geo/songs/$songId?extend=extendedAssetUrls&include=lyrics,albums&l=en-US"
        val req = Request.Builder().url(url).headers(authHeaders()).build()
        val resp = Net.client().newCall(req).execute()
        if (!resp.isSuccessful) return null
        val json = JSONObject(resp.body?.string() ?: "{}")
        val data = json.optJSONArray("data") ?: return null
        val item = data.optJSONObject(0)?.optJSONObject("attributes") ?: return null
        val previews = item.optJSONArray("previews") ?: return null
        return previews.optJSONObject(0)?.optString("url")?.takeIf { it.startsWith("http") }
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        withTimeoutOrNull(20_000) {
            try {
                val preview = getSongPreview(song.songId) ?: return@withTimeoutOrNull null
                // 注意：Song.ext 为 val，解析阶段无法改写；搜索时已按试听格式置为 m4a
                // Apple 试听片段本身是真实 m4a，HEAD 校验不过时仍按 best-effort 返回原始地址
                val finalUrl = AudioLinkTester.test(preview)?.url ?: preview
                song.playUrl = finalUrl
                finalUrl
            } catch (_: Exception) {
                null
            }
        }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.lrc.isNotBlank()) return@withContext song.lrc
        if (!ensureToken()) return@withContext null
        try {
            val url = "https://amp-api-edge.music.apple.com/v1/catalog/$geo/songs/${song.songId}?extend=extendedAssetUrls&include=lyrics,albums&l=en-US"
            val req = Request.Builder().url(url).headers(authHeaders()).build()
            val resp = Net.client().newCall(req).execute()
            val json = JSONObject(resp.body?.string() ?: "{}")
            // 直接尝试 songs.<id>.attributes.lyrics（部分账户会带纯文本歌词）
            val direct = json.optJSONArray("data")?.optJSONObject(0)?.optJSONObject("attributes")?.optString("lyrics")?.ifBlank { null }
            if (direct != null) return@withContext direct.trim()
            // 否则尝试 resources.lyrics
            val lyricsObj = json.optJSONObject("resources")?.optJSONObject("lyrics") ?: return@withContext null
            val lk = lyricsObj.keys()
            while (lk.hasNext()) {
                val text = lyricsObj.optJSONObject(lk.next())?.optJSONObject("attributes")?.optString("text")?.ifBlank { null }
                if (text != null) return@withContext text.trim()
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
