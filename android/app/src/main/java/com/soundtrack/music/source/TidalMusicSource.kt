package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * TIDAL 音源。
 *
 * 重要限制（务必注意）：
 *  - TIDAL 的完整播放链路需要登录态（device-code / 用户名密码换取 access_token），原始 musicdl 客户端在
 *    构造时就 assert 必须配置 cookies / token，且播放流地址（playbackinfopost）必须带 Bearer 令牌。
 *    本移动端没有可打包的用户账号，也无法做 OAuth 登录交互，因此：
 *      * 搜索：用 musicdl 中硬编码的公开 client_id 走 X-Tidal-Token 做一次「尽力而为」的搜索尝试。
 *        若服务端要求鉴权（多数情况下会 401），则搜索返回空列表，不伪造数据。
 *      * 播放：需要登录态换取流地址，本端无法实现，resolvePlayUrl 直接返回 null。
 *      * 歌词：需要登录态，返回 null。
 *  - 这属于「实现到能做的那一步」，未配置凭据时返回空/ null，绝非伪造成功。
 *
 * 参考 ref_all/tidal.py 与 musicdl/modules/utils/tidalutils.py（其中硬编码 client_id 已采用）。
 */
class TidalMusicSource : MusicSource {
    override val id = "tidal"
    override val label = "TIDAL"

    // musicdl tidalutils 中硬编码的公开 client_id（仅用于搜索头，不足以换取播放流）
    private val clientId = "fX2JxdmntZWK0ixT"

    private val headers = mapOf(
        "X-Tidal-Token" to clientId,
        "User-Agent" to "TIDAL_ANDROID/1039 okhttp/3.14.9",
        "Accept" to "application/json",
        "Connection" to "Keep-Alive"
    )

    private fun reqHeaders() =
        okhttp3.Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray())

    private fun buildQuery(params: Map<String, String>): String =
        params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }

    /** TIDAL 的 album.cover 是 UUID 串，需拼成 resources.tidal.com 的图片地址。 */
    private fun coverUrl(cover: String): String {
        if (cover.isBlank()) return ""
        if (cover.startsWith("http")) return cover
        return "https://resources.tidal.com/images/${cover.replace("-", "/")}/640x640.jpg"
    }

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        val url = "https://api.tidalhifi.com/v1/search?" + buildQuery(
            mapOf(
                "countryCode" to "US",
                "query" to keyword,
                "limit" to pageSize.toString(),
                "offset" to "0",
                "types" to "TRACKS"
            )
        )
        val req = Request.Builder().url(url).headers(reqHeaders()).build()
        try {
            val resp = Net.client().newCall(req).execute()
            // 未登录时多数返回 401，直接返回空列表（真实结果，不伪造）
            if (!resp.isSuccessful) return@withContext emptyList()
            val json = JSONObject(resp.body?.string() ?: "{}")
            val items = json.optJSONObject("tracks")?.optJSONArray("items")
                ?: json.optJSONArray("data")
                ?: return@withContext emptyList()

            val list = ArrayList<Song>()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val sid = item.optString("id").ifBlank { continue }
                val title = item.optString("title").ifBlank { continue }
                val artistsArr = item.optJSONArray("artists")
                val artist = if (artistsArr != null && artistsArr.length() > 0) {
                    (0 until artistsArr.length()).mapNotNull { artistsArr.optJSONObject(it)?.optString("name") }.joinToString(", ")
                } else item.optString("artist")
                val albumObj = item.optJSONObject("album")
                val album = albumObj?.optString("title") ?: item.optString("album")
                val duration = item.optInt("duration", 0)
                val cover = coverUrl(albumObj?.optString("cover") ?: "")
                list.add(
                    Song(
                        songId = sid,
                        source = this@TidalMusicSource.id,
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

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        // TIDAL 播放流需要登录态换取的 Bearer 令牌（playbackinfopost），本端无账号，无法实现。
        null
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        // 歌词同样依赖登录态，无法实现。
        null
    }
}
