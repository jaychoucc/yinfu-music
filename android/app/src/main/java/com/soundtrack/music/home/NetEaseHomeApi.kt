package com.soundtrack.music.home

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * NetEase 公开 API（无需登录）用于首页发现页。
 *
 *  - /api/personalized                       推荐歌单
 *  - /api/toplist/detail                     全部排行榜（含前若干首）
 *  - /api/v1/discovery/new/songs             新歌速递
 *  - /api/v6/playlist/detail?id=xxx          歌单 / 排行榜详情
 *
 * 所有方法 ① 用 IO 调度 ② 任何异常吞掉返回空 ③ 不依赖电脑端。
 */
class NetEaseHomeApi {

    private val headers = okhttp3.Headers.headersOf(
        "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36",
        "Accept", "application/json, text/plain, */*",
        "Referer", "https://music.163.com/",
        "Origin", "https://music.163.com",
        "Accept-Language", "zh-CN,zh;q=0.9"
    )

    suspend fun recommendPlaylists(limit: Int = 6): List<PlaylistCard> = withContext(Dispatchers.IO) {
        runCatching {
            val arr = getJsonArray("https://music.163.com/api/personalized?limit=$limit")
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optLong("id")
                val name = o.optString("name")
                val cover = o.optString("picUrl")
                if (id <= 0L || name.isBlank() || cover.isBlank()) null
                else PlaylistCard(id, name, cover, o.optLong("playCount", 0L), o.optString("copywriter"))
            }
        }.getOrElse { emptyList() }
    }

    /** 全部官方排行榜（每张含 1 张封面 + 内置的前若干首）。点开用 playlistDetail 拿完整歌单。 */
    suspend fun toplists(): List<ToplistCard> = withContext(Dispatchers.IO) {
        runCatching {
            val arr = getJsonArray("https://music.163.com/api/toplist/detail")
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optLong("id")
                val name = o.optString("name")
                val cover = o.optString("coverImgUrl")
                if (id <= 0L || name.isBlank() || cover.isBlank()) null
                else {
                    val tracksJson = o.optJSONArray("tracks") ?: JSONArray()
                    val preview = (0 until tracksJson.length()).mapNotNull { j ->
                        trackToSong(tracksJson.optJSONObject(j))
                    }
                    ToplistCard(id, name, cover, preview)
                }
            }
        }.getOrElse { emptyList() }
    }

    suspend fun newSongs(area: String = "ALL", limit: Int = 20): List<Song> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = getJsonObject("https://music.163.com/api/v1/discovery/new/songs?area=${URLEncoder.encode(area, "UTF-8")}&limit=$limit")
            val arr = resp.optJSONArray("data") ?: return@runCatching emptyList()
            (0 until arr.length()).mapNotNull { i -> trackToSong(arr.optJSONObject(i)) }
        }.getOrElse { emptyList() }
    }

    /**
     * 歌单 / 排行榜详情：返回完整曲目列表（id + 标题 + 歌手 + 封面 + 时长）。
     * 解析播放由 PlayerRepository 走 NeteaseMusicSource.resolvePlayUrl。
     */
    suspend fun playlistDetail(id: Long, limit: Int = 100): List<Song> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://music.163.com/api/v6/playlist/detail?id=$id&n=$limit&s=0"
            val resp = getJsonObject(url)
            val pl = resp.optJSONObject("playlist") ?: return@runCatching emptyList()
            val tracks = pl.optJSONArray("tracks") ?: return@runCatching emptyList()
            (0 until tracks.length()).mapNotNull { i -> trackToSong(tracks.optJSONObject(i)) }
        }.getOrElse { emptyList() }
    }

    // -------- helpers --------

    private fun getJsonArray(url: String): JSONArray {
        val req = Request.Builder().url(url).headers(headers).get().build()
        Net.client().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return JSONArray()
            val body = resp.body?.string().orEmpty()
            val tok = org.json.JSONTokener(body).nextValue()
            return if (tok is JSONArray) tok else (tok as? JSONObject)?.optJSONArray("data") ?: JSONArray()
        }
    }

    private fun getJsonObject(url: String): JSONObject {
        val req = Request.Builder().url(url).headers(headers).get().build()
        Net.client().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return JSONObject()
            val body = resp.body?.string().orEmpty()
            return try { JSONObject(body) } catch (_: Exception) { JSONObject() }
        }
    }

    /**
     * NetEase 歌曲结构转本 app 的 Song。播放由 NeteaseMusicSource.resolvePlayUrl
     * 完成（已有 MD5 + listen-url 解密 + 多品质回退 + AudioLinkTester 校验）。
     */
    private fun trackToSong(t: JSONObject?): Song? {
        if (t == null) return null
        val id = t.optLong("id")
        if (id <= 0L) return null
        val name = t.optString("name").ifBlank { return null }
        val ar = t.optJSONArray("ar")
        val artist = if (ar != null) (0 until ar.length())
            .mapNotNull { ar.optJSONObject(it).optString("name") }
            .joinToString(" / ") else t.optString("artists")
        val al = t.optJSONObject("al")
        val album = al?.optString("name").orEmpty()
        val pic = al?.optString("picUrl").orEmpty()
        val durationMs = t.optLong("dt", 0L)
        return Song(
            songId = id.toString(),
            source = "netease",
            title = name,
            artist = artist,
            album = album,
            durationSec = (durationMs / 1000L).toInt(),
            coverUrl = pic,
            playUrl = "",
            lrc = "",
            ext = "mp3",
            fileSizeBytes = 0L
        )
    }
}
