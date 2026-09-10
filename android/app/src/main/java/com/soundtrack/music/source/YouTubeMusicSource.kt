package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * YouTube Music 音乐源（手机端本地调用）。
 *
 * 限制说明（务必注意）：
 * 1. 搜索走 YouTube「InnerTube」公开 API（music.youtube.com/youtubei/v1/search），
 *    使用公开的 WEB_REMIX client + 公开的 web API key，无需任何账号即可拿到
 *    videoId / 标题 / 作者 / 时长 / 封面。这条路径与 Python 端的 yt-dlp / 第三方转换
 *    站不同，是真正公开、稳定的。
 * 2. 播放链接：YouTube 的音轨实际下载地址需要对 player 返回的 streamingData 做
 *    签名（signatureCipher）/ n 参数反混淆，等价于一个 JS 引擎 + yt-dlp 的 cipher，
 *    移动端没有 JS 引擎、也没有 yt-dlp，因此【无法】产出可播放的真实音轨。
 *    resolvePlayUrl 直接返回 null（已在注释说明），绝不伪造链接。
 * 3. 歌词：未实现，返回 null。
 *
 * 参考：ref_all/youtube.py —— 这里只照搬「搜索得到 videoId」的公开部分，放弃所有需要
 * yt-dlp / 第三方转换站 / OAuth 的解析路径。
 */
class YouTubeMusicSource : MusicSource {
    override val id = "youtube"
    override val label = "YouTube Music"

    // 公开、未登录的 YouTube web InnerTube API key（与 music.youtube.com 网页同源）
    private val INNERTUBE_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private val SEARCH_URL = "https://music.youtube.com/youtubei/v1/search?key=$INNERTUBE_KEY"
    private val CLIENT_VERSION = "1.20240909.01.00"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36",
        "Content-Type" to "application/json",
        "Origin" to "https://music.youtube.com",
        "Referer" to "https://music.youtube.com/"
    )

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("query", keyword)
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", CLIENT_VERSION)
                })
            })
        }
        val req = Request.Builder().url(SEARCH_URL)
            .headers(okhttp3.Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray()))
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        try {
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val body = resp.body?.string() ?: return@withContext emptyList()
            val root = JSONObject(body)
            val renderers = ArrayList<JSONObject>()
            collectMusicItems(root, renderers)
            val list = ArrayList<Song>()
            val seen = HashSet<String>()
            for (renderer in renderers) {
                val song = parseRenderer(renderer) ?: continue
                if (song.songId in seen) continue
                seen.add(song.songId)
                list.add(song)
                if (list.size >= pageSize) break
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 递归收集响应里所有 musicResponsiveListItemRenderer 节点（搜索结果项） */
    private fun collectMusicItems(node: Any?, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = node.opt(k)
                    if (k == "musicResponsiveListItemRenderer" && v is JSONObject) {
                        out.add(v)
                    }
                    collectMusicItems(v, out)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectMusicItems(node.opt(i), out)
                }
            }
        }
    }

    /** 从一个 musicResponsiveListItemRenderer 提取 Song（只取带 videoId 的歌曲结果） */
    private fun parseRenderer(renderer: JSONObject): Song? {
        // videoId：overlay -> playNavigationEndpoint -> watchEndpoint.videoId
        val videoId = renderer.optJSONObject("overlay")
            ?.optJSONObject("musicItemThumbnailOverlayRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("musicPlayButtonRenderer")
            ?.optJSONObject("playNavigationEndpoint")
            ?.optJSONObject("watchEndpoint")
            ?.optString("videoId")
            ?.ifBlank { null } ?: return null

        // 标题：flexColumns[0].text.runs[0].text
        val title = renderer.optJSONArray("flexColumns")
            ?.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text")
            ?.ifBlank { null } ?: return null

        // 作者：flexColumns[1].text.runs[].text 拼接
        val artist = renderer.optJSONArray("flexColumns")
            ?.optJSONObject(1)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
            ?.let { runs ->
                val sb = StringBuilder()
                for (i in 0 until runs.length()) {
                    val t = runs.optJSONObject(i)?.optString("text") ?: ""
                    if (t.isNotBlank()) sb.append(t).append(" ")
                }
                sb.toString().trim()
            } ?: ""

        // 时长：fixedColumns[0].musicResponsiveListItemFixedColumnRenderer.text.runs[0].text -> "3:45"
        val durationStr = renderer.optJSONArray("fixedColumns")
            ?.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text") ?: ""
        val durationSec = parseDuration(durationStr)

        // 封面：thumbnail.musicThumbnailRenderer.thumbnail.thumbnails 取最后一个 url
        val coverUrl = renderer.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
            ?.let { thumbs ->
                if (thumbs.length() > 0) thumbs.optJSONObject(thumbs.length() - 1)?.optString("url") else null
            } ?: ""

        return Song(
            songId = videoId,
            source = this@YouTubeMusicSource.id,
            title = title,
            artist = artist,
            album = "",
            durationSec = durationSec,
            coverUrl = coverUrl,
            playUrl = "",
            lrc = "",
            // 解析不到真实链接，ext 仅占位；resolvePlayUrl 返回 null 不会用到
            ext = "m4a",
            fileSizeBytes = 0L
        )
    }

    private fun parseDuration(text: String): Int {
        if (text.isBlank()) return 0
        val parts = text.split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> 0
        }
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        // 见文件头说明：YouTube 音轨真实下载地址需要签名/JS 引擎反混淆（等价于 yt-dlp），
        // 移动端无法复刻。此处直接返回 null，绝不返回伪造的链接。
        null
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        null
    }
}
