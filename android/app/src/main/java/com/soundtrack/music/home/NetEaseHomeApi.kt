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
 *  - /api/personalized/playlist              推荐歌单（原 /api/personalized 已下线，返回 404）
 *  - /api/toplist/detail                     全部排行榜（根字段 list，附带 {first,second} 简版预览）
 *  - /api/v1/discovery/new/songs             新歌速递
 *  - /api/v6/playlist/detail?id=xxx          歌单 / 排行榜详情
 *
 * ⚠️ 字段两套风格并存，解析必须同时兼容：
 *   - 老接口（playlist/detail）：艺术家 `ar`、专辑 `al`、时长 `dt`
 *   - 新接口（discovery/new/songs）：艺术家 `artists`、专辑 `album`、时长 `duration`
 *   早期只按老风格解析，导致新歌速递封面为空、歌手栏直接吐出 artists 数组的原始 JSON（可见 img1v1Id）。
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

    /**
     * 推荐歌单。
     * 旧地址 /api/personalized 已于近期下线（`{"code":404,"message":"接口未找到！"}`），
     * 改用 /api/personalized/playlist，根字段为 result。
     */
    suspend fun recommendPlaylists(limit: Int = 6): List<PlaylistCard> = withContext(Dispatchers.IO) {
        runCatching {
            val arr = getArray(
                "https://music.163.com/api/personalized/playlist?limit=$limit",
                "result", "data", "playlists"
            )
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optLong("id")
                val name = o.optString("name")
                // personalized/playlist 用 picUrl；个别分支用 coverImgUrl，两者都兜一下
                val cover = o.optString("picUrl").ifBlank { o.optString("coverImgUrl") }
                if (id <= 0L || name.isBlank() || cover.isBlank()) null
                else PlaylistCard(id, name, cover, o.optLong("playCount", 0L), o.optString("copywriter"))
            }.take(limit)
        }.getOrElse { emptyList() }
    }

    /**
     * 全部官方排行榜。
     * 根字段是 **list**（不是 data）；每张榜单的 tracks 是 `{first: 歌名, second: 歌手}` 简版结构，
     * 没有歌曲 id，无法构造可播放的 Song，因此这里只取文本预览用于卡片展示，
     * 真正的曲目在进入详情页时用 playlistDetail 拉取。
     */
    suspend fun toplists(): List<ToplistCard> = withContext(Dispatchers.IO) {
        runCatching {
            val arr = getArray("https://music.163.com/api/toplist/detail", "list", "data", "result")
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optLong("id")
                val name = o.optString("name")
                val cover = o.optString("coverImgUrl").ifBlank { o.optString("picUrl") }
                if (id <= 0L || name.isBlank() || cover.isBlank()) null
                else ToplistCard(id, name, cover, parseTopPreviews(o.optJSONArray("tracks")))
            }
        }.getOrElse { emptyList() }
    }

    /**
     * 新歌速递。根字段 data，曲目使用**新风格**字段：artists / album / duration。
     * 该接口会忽略 limit 直接返回 100 条，这里本地截断到 limit。
     */
    suspend fun newSongs(area: String = "ALL", limit: Int = 20): List<Song> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://music.163.com/api/v1/discovery/new/songs?area=${URLEncoder.encode(area, "UTF-8")}&limit=$limit"
            val arr = getArray(url, "data", "result")
            (0 until arr.length()).mapNotNull { i -> trackToSong(arr.optJSONObject(i)) }.take(limit)
        }.getOrElse { emptyList() }
    }

    /**
     * 歌单 / 排行榜详情：返回完整曲目列表（老风格字段 ar / al / dt）。
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

    /**
     * 按优先级从根对象里取数组。根字段在不同接口上可能是 data / result / list。
     */
    private fun getArray(url: String, vararg keys: String): JSONArray {
        val root = getJsonObject(url)
        if (root.length() == 0) return JSONArray()
        for (k in keys) {
            val a = root.optJSONArray(k)
            if (a != null) return a
        }
        return JSONArray()
    }

    private fun getJsonObject(url: String): JSONObject {
        val req = Request.Builder().url(url).headers(headers).get().build()
        Net.client().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return JSONObject()
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) return JSONObject()
            return try {
                val tok = org.json.JSONTokener(body).nextValue()
                tok as? JSONObject ?: JSONObject()
            } catch (_: Exception) {
                JSONObject()
            }
        }
    }

    /**
     * 排行榜内置的简版榜单：`[{"first":"歌名","second":"歌手"}, ...]`
     * 转成 "1. 歌名 - 歌手" 这样的展示文本。
     */
    private fun parseTopPreviews(tracks: JSONArray?): List<String> {
        if (tracks == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until tracks.length().coerceAtMost(3)) {
            val o = tracks.optJSONObject(i) ?: continue
            val first = o.optString("first").trim()
            val second = o.optString("second").trim()
            if (first.isBlank()) continue
            out.add(if (second.isBlank()) "${i + 1}. $first" else "${i + 1}. $first - $second")
        }
        return out
    }

    /**
     * 艺术家名：兼容 `ar`（老接口）与 `artists`（新接口）两种数组字段名。
     *
     * 关键点：如果直接对 `artists` 调用 optString()，而它实际是 JSONArray，
     * 会返回整段 JSON 文本（里面含 img1v1Id 等字段），这就是「歌名下面显示 img1v1id」的原因。
     * 因此这里只在**不是数组**时才走字符串兜底。
     */
    private fun artistsOf(t: JSONObject): String {
        val names = mutableListOf<String>()
        val ar = t.optJSONArray("ar") ?: t.optJSONArray("artists")
        if (ar != null) {
            for (i in 0 until ar.length()) {
                val o = ar.optJSONObject(i) ?: continue
                val n = o.optString("name").trim()
                if (n.isNotBlank()) names.add(n)
            }
        }
        if (names.isEmpty()) {
            // 少数结构直接给字符串形式的歌手名；若仍是 JSON 文本（以 [ 开头）则丢弃
            val s = t.optString("artists").trim()
            if (s.isNotBlank() && !s.startsWith("[") && !s.startsWith("{")) names.add(s)
        }
        return names.distinct().joinToString(" / ")
    }

    /**
     * NetEase 歌曲结构转本 app 的 Song（兼容新老两套字段）。
     * 播放由 NeteaseMusicSource.resolvePlayUrl 完成。
     *
     * 版权位路由（修复 30s 试听 bug）：
     * 经验证,网易在 tracks[].fee 里直接告诉了我们这首歌能不能给完整流:
     *   - fee=1 (VIP) / fee=4 (专辑独占) → 网易 /api/song/url 必返 30s 试听
     *   - fee=0 (免费) / fee=8 (低音质)  → 网易能返完整流
     * PlayerRepository 的 FALLBACK_SOURCES 顺序是 ["migu","kuwo","qq","kugou","myfreemp3","netease"],
     * 因此把 VIP/专辑独占两类 track 的 song.source 改成 "migu",主解析器第一发就会击中
     * 咪咕的完整流,网易退到兜底链末尾。其余曲目继续让 netease 当主,行为与旧版本一致。
     *
     * 字段缺失时 optInt 默认 0,等价于 fee=0 = 免费 → 走 netease,100% 向后兼容。
     *
     * ⚠️ 不读 tracks[].st 字段:在 /api/personalized/playlist 与 /api/v6/playlist/detail
     *    这两个端点里 st=0 是正常可播取值(60/60 命中),用它做路由会 100% 短路成 migu。
     */
    private fun trackToSong(t: JSONObject?): Song? {
        if (t == null) return null
        val id = t.optLong("id")
        if (id <= 0L) return null
        val name = t.optString("name").ifBlank { return null }
        val artist = artistsOf(t)

        // 专辑对象：新接口 album，老接口 al
        val al = t.optJSONObject("album") ?: t.optJSONObject("al")
        val album = al?.optString("name").orEmpty()
        // 封面：专辑对象为主，歌曲层 picUrl / 专辑 pic 为辅
        val pic = al?.optString("picUrl").orEmpty()
            .ifBlank { t.optString("picUrl") }
            .ifBlank { al?.optString("pic").orEmpty() }

        // 时长：新接口 duration，老接口 dt
        val durationMs = t.optLong("duration").takeIf { it > 0 } ?: t.optLong("dt", 0L)

        // 版权位路由：fee=1 VIP / fee=4 专辑独占 → 网易必返 30s 试听,绕开
        val fee = t.optInt("fee", 0)  // 字段缺失 → 默认 0 → 走 netease,全兼容
        val source = when {
            fee == 1 || fee == 4 -> "migu"  // VIP / 专辑独占,让咪咕当主
            else -> "netease"               // fee=0 免费 / fee=8 低音质 / 缺失 → 网易主解析器直接给完整
        }

        return Song(
            songId = id.toString(),
            source = source,
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
