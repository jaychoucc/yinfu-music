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
 * MyFreeMP3 聚合搜索（手机端本地调用）：POST 到 myfreemp3.com.cn，
 * 实际返回的是网易云外链（id + download_url 都是 music.163.com）。
 * 优点：搜索源与网易云不同，可能搜到一些网易云直搜搜不到的副标题歌曲。
 */
class MyFreeMP3MusicSource : MusicSource {
    override val id = "myfreemp3"
    override val label = "MyFreeMP3"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36",
        "Accept" to "*/*",
        "X-Requested-With" to "XMLHttpRequest",
        "Origin" to "https://www.myfreemp3.com.cn",
        "Referer" to "https://www.myfreemp3.com.cn/"
    )

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("input", keyword)
            .add("filter", "name")
            .add("type", "netease")
            .add("page", "1")
            .build()
        val req = Request.Builder()
            .url("https://www.myfreemp3.com.cn/")
            .headers(okhttp3.Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray()))
            .post(body)
            .build()
        try {
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val raw = resp.body?.string()?.trim().orEmpty()
            // 返回的是非标准 JSON 数组字符串
            val arr = parseLooseArray(raw)
            val list = ArrayList<Song>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                val name = item.optString("title")
                if (name.isBlank()) continue
                val author = item.optString("author")
                val cover = item.optString("pic")
                list.add(
                    Song(
                        songId = id,
                        source = this@MyFreeMP3MusicSource.id,
                        title = name,
                        artist = author,
                        album = "",
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

    /**
     * 解析 myfreemp3 返回的"近似 JSON"：可能含 BOM/单引号/未转义双引号
     * 常规 JSON 解析可能直接报错，所以用正则提取每个 {} 对象
     */
    private fun parseLooseArray(raw: String): org.json.JSONArray {
        if (raw.isEmpty()) return org.json.JSONArray()
        val trimmed = raw.trim().removePrefix("\uFEFF")
        // 先尝试标准 JSON
        try {
            val v = org.json.JSONTokener(trimmed).nextValue()
            if (v is org.json.JSONArray) return v
            if (v is org.json.JSONObject) return org.json.JSONArray().put(v)
        } catch (_: Exception) {}
        // 兜底：提取所有 { ... } 块（简易处理：取最外层花括号匹配的）
        val arr = org.json.JSONArray()
        val regex = Regex("\\{[^\\{\\}]*\\}")
        regex.findAll(trimmed).forEach { m ->
            runCatching { arr.put(org.json.JSONObject(m.value)) }
        }
        return arr
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        if (song.songId.isBlank()) return@withContext null
        // 网易云外链（HEAD follow redirect 拿真实 URL）
        val testUrl = "http://music.163.com/song/media/outer/url?id=${song.songId}.mp3"
        try {
            withTimeoutOrNull(20_000) {
                val req = Request.Builder().url(testUrl).head()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36")
                    .header("Referer", "https://music.163.com/")
                    .build()
                val resp = Net.client().newCall(req).execute()
                val real = resp.request.url.toString()
                resp.close()
                if (real.startsWith("http") && !real.contains("error")) {
                    song.playUrl = real
                    real
                } else null
            }
        } catch (_: Exception) { null }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        // 不实现；可由 PlayerActivity 走网易云路径补（TODO 后续加）
        null
    }
}
