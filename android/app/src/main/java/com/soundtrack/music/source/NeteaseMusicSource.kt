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
        val artist = item.optJSONArray("ar")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optString("name") }.joinToString(", ")
        } ?: ""
        val album = item.optJSONObject("al")?.optString("name") ?: ""
        val cover = item.optJSONObject("al")?.optString("picUrl") ?: ""
        val durationSec = (item.optInt("dt", 0) / 1000)
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

    private suspend fun resolveUrl(songId: String): AudioLinkTester.Result? {
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
                if (isLikelyPreview(downloadUrl)) continue
                AudioLinkTester.test(downloadUrl)?.let { return it }
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * 试听片段探测：拉取前 256KB 并统计 MPEG audio sync word（0xFF + 次字节 111xxxxx）密度。
     * MPEG1 L3@128kbps 每帧 144B：9.7s 试听 ≈ 420 帧，256KB 完整切片 ≈ 1780 帧，
     * 阈值 600 帧（≈5.3s 真音频）可稳定区分；任何异常一律保守判为试听，宁可走跨源回退。
     */
    private suspend fun isLikelyPreview(downloadUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(2_500L) {
                val req = Request.Builder()
                    .url(downloadUrl)
                    .header("Range", "bytes=0-262143")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                val bytes = Net.client().newCall(req).execute().use { resp ->
                    val body = resp.body ?: return@use null
                    val buf = ByteArray(262_144)
                    var off = 0
                    body.byteStream().use { ins ->
                        while (off < buf.size) {
                            val n = ins.read(buf, off, buf.size - off)
                            if (n <= 0) break
                            off += n
                        }
                    }
                    if (off == buf.size) buf else buf.copyOf(off)
                } ?: return@withTimeoutOrNull true

                var syncCount = 0
                var i = 0
                val limit = bytes.size - 1
                while (i < limit) {
                    if ((bytes[i].toInt() and 0xFF) == 0xFF &&
                        (bytes[i + 1].toInt() and 0xE0) == 0xE0
                    ) {
                        syncCount++
                        i += 2
                    } else {
                        i++
                    }
                }
                bytes.size < 65_536 || syncCount < 600
            } ?: true
        } catch (_: Exception) {
            true
        }
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
            resolveUrl(song.songId)?.url?.also { song.playUrl = it }
        } ?: song.playUrl.takeIf { it.startsWith("http") }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.lrc.isNotBlank()) return@withContext song.lrc
        fetchLyric(song.songId)?.also { song.lrc = it }
    }
}
