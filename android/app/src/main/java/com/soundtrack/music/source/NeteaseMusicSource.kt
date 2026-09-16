package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 网易云音乐源：公开搜索 + 第三方解析链 + 官方歌词
 */
class NeteaseMusicSource : MusicSource {
    override val id = "netease"
    override val label = "网易云音乐"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
        "Referer" to "https://music.163.com/"
    )

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("s", keyword)
            .add("type", "1")
            .add("limit", pageSize.toString())
            .add("offset", "0")
            .build()
        val req = Request.Builder()
            .url("https://music.163.com/api/cloudsearch/pc")
            .headers(okhttp3.Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray()))
            .post(body)
            .build()
        val resp = Net.client().newCall(req).execute()
        val json = JSONObject(resp.body?.string() ?: "{}")
        val songs = json.optJSONObject("result")?.optJSONArray("songs") ?: return@withContext emptyList()

        val list = mutableListOf<Song>()
        for (i in 0 until songs.length()) {
            val item = songs.getJSONObject(i)
            parseItem(item)?.let { list.add(it) }
        }
        list
    }

    private fun parseItem(item: JSONObject): Song? {
        val songId = item.optLong("id", 0L).takeIf { it > 0 } ?: return null
        val name = item.optString("name").ifBlank { return null }
        // ar/al/dt 是 cloudsearch 与 playlist.detail.tracks 的结构；
        // artists/album/duration 是 song/detail 的结构——两套都兼容。
        val artist = item.optJSONArray("ar")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }.joinToString(", ")
        } ?: item.optJSONArray("artists")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }.joinToString(", ")
        } ?: ""
        val al = item.optJSONObject("al") ?: item.optJSONObject("album")
        val album = al?.optString("name") ?: ""
        val cover = al?.optString("picUrl") ?: ""
        // 统一用 optLong 读 dt/duration，否则 Int 与 Long 混合推导成 Number 导致 / 编译失败
        val durationMs = item.optLong("dt", 0L).takeIf { it > 0 } ?: item.optLong("duration", 0L)
        val durationSec = (durationMs / 1000L).toInt()
        // 搜索阶段不解析播放地址（避免逐首联网导致慢 + 播放时二次轮询）
        return Song(
            songId = songId.toString(),
            source = this.id,
            title = name,
            artist = artist,
            album = album,
            durationSec = durationSec,
            coverUrl = cover,
            playUrl = "",
            lrc = "",
            ext = "mp3",
            fileSizeBytes = 0L
        )
    }

    /**
     * 解析网易云歌单：返回 `(歌单名, 曲目列表)`，曲目只含元数据（playUrl/lrc 留空，播放时再解析）。
     *
     * 端点策略（实测 2026-09-17 后调整顺序）：
     *  1. POST `https://music.163.com/api/v6/playlist/detail`（表单 id + n）—— **优先**。
     *     根 key 为 `playlist`，tracks 字段完整（含 ar/al/dt），可直接复用 [parseItem]。
     *  2. GET `https://music.163.com/api/playlist/detail?id=$id&n=1000` —— 回退。
     *     根 key 为 `result`；未登录时 tracks **精简**（只有 id/name，无 ar/al/dt），
     *     解析出的曲目缺歌手与时长，匹配评分会受损，仅作为 v6 失败时的兜底
     *     （有歌单名+曲目名，失败歌单的点歌名搜索仍可用）。
     *
     * tracks 优先；缺失/截断时用 trackIds 批量 `song/detail` 补全（未登录时该接口同样精简，
     * 属最后兜底）。任何一层失败返回 null，由调用方提示。
     */
    suspend fun fetchPlaylist(playlistId: Long): Pair<String, List<Song>>? = withContext(Dispatchers.IO) {
        runCatching {
            fetchPlaylistViaPost(playlistId) ?: fetchPlaylistViaGet(playlistId)
        }.getOrNull()
    }

    private fun fetchPlaylistViaGet(playlistId: Long): Pair<String, List<Song>>? {
        val req = Request.Builder()
            .url("https://music.163.com/api/playlist/detail?id=$playlistId&n=1000")
            .headers(okhttp3.Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray()))
            .get()
            .build()
        val resp = Net.client().newCall(req).execute()
        return parsePlaylistFromResp(resp)
    }

    private fun fetchPlaylistViaPost(playlistId: Long): Pair<String, List<Song>>? {
        val form = FormBody.Builder()
            .add("id", playlistId.toString())
            .add("n", "1000")
            .build()
        val req = Request.Builder()
            .url("https://music.163.com/api/v6/playlist/detail")
            .headers(okhttp3.Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray()))
            .post(form)
            .build()
        val resp = Net.client().newCall(req).execute()
        return parsePlaylistFromResp(resp)
    }

    private fun parsePlaylistFromResp(resp: okhttp3.Response): Pair<String, List<Song>>? {
        val body = resp.body?.string().orEmpty()
        if (body.isBlank()) return null
        val root = JSONObject(body)
        if (root.optInt("code", -1) != 200) return null
        val playlist = root.optJSONObject("playlist") ?: root.optJSONObject("result") ?: return null
        val name = playlist.optString("name").ifBlank { return null }

        val list = mutableListOf<Song>()
        // 优先 tracks：v6 端点的 tracks 含完整 ar/al/dt，可直接复用 parseItem
        val tracks = playlist.optJSONArray("tracks")
        if (tracks != null && tracks.length() > 0) {
            for (i in 0 until tracks.length()) {
                val item = tracks.optJSONObject(i) ?: continue
                parseItem(item)?.let { list.add(it) }
            }
            if (list.isNotEmpty()) return name to list
        }

        // tracks 缺失 / 被截断 → trackIds 批量请求 song/detail 补全（song/detail 用 artists/album/duration）
        val trackIds = playlist.optJSONArray("trackIds") ?: return name to emptyList()
        val ids = StringBuilder()
        for (i in 0 until trackIds.length()) {
            val tid = trackIds.optJSONObject(i)?.optLong("id", 0L) ?: continue
            if (tid > 0) {
                if (ids.isNotEmpty()) ids.append(',')
                ids.append(tid)
            }
        }
        if (ids.isEmpty()) return name to emptyList()
        runCatching {
            val form = FormBody.Builder().add("c", "[$ids]").build()
            val req = Request.Builder()
                .url("https://music.163.com/api/song/detail")
                .headers(okhttp3.Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray()))
                .post(form)
                .build()
            val resp2 = Net.client().newCall(req).execute()
            val body2 = resp2.body?.string().orEmpty()
            if (body2.isBlank()) return@runCatching
            val songsArr = JSONObject(body2).optJSONArray("songs") ?: return@runCatching
            for (i in 0 until songsArr.length()) {
                val item = songsArr.optJSONObject(i) ?: continue
                parseItem(item)?.let { list.add(it) }
            }
        }
        return name to list
    }

    /**
     * 解析播放地址：逐 quality 尝试第三方解析链。
     * 试听守护已升级为「时长估算」并移至共享的 [PreviewGuard]（sync-word 密度判定
     * 会被 haitangw 返回的「完整有效 30s 试听」骗过），这里按元数据时长做码率估算。
     */
    private suspend fun resolveUrl(songId: String, durationSec: Int): AudioLinkTester.Result? {
        val qualities = listOf("lossless", "exhigh", "standard")
        for (q in qualities) {
            try {
                val url = "https://musicapi.haitangw.net/music/wy.php?id=$songId&level=$q&type=json"
                val req = Request.Builder().url(url)
                    .header("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                val resp = Net.client().newCall(req).execute()
                val json = JSONObject(resp.body?.string() ?: "{}")
                val downloadUrl = json.optJSONObject("data")?.optString("url") ?: continue
                // 试听探测：third-party 解析链对多数歌只返回 9~30s 试听片段，
                // 命中则跳过该 quality，避免试听流到 ExoPlayer（9 秒自动 STATE_ENDED）。
                if (PreviewGuard.isPreview(downloadUrl, durationSec)) continue
                AudioLinkTester.test(downloadUrl)?.let { return it }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun fetchLyric(songId: String): String? {
        try {
            val body = FormBody.Builder()
                .add("id", songId)
                .add("cp", "false")
                .add("tv", "0")
                .add("lv", "0")
                .add("rv", "0")
                .add("kv", "0")
                .add("yv", "0")
                .add("ytv", "0")
                .add("yrv", "0")
                .build()
            val req = Request.Builder()
                .url("https://interface3.music.163.com/api/song/lyric")
                .headers(okhttp3.Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray()))
                .post(body)
                .build()
            val resp = Net.client().newCall(req).execute()
            val json = JSONObject(resp.body?.string() ?: "{}")
            return json.optJSONObject("lrc")?.optString("lyric")
        } catch (_: Exception) {
            return null
        }
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        withTimeoutOrNull(20_000) {
            resolveUrl(song.songId, song.durationSec)?.url?.also { song.playUrl = it }
        } ?: song.playUrl.takeIf { it.startsWith("http") }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.lrc.isNotBlank()) return@withContext song.lrc
        fetchLyric(song.songId)?.also { song.lrc = it }
    }
}
