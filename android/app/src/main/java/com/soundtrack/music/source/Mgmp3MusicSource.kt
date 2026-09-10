package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Headers
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * MGMP3 (mgmp3)：搜索 api/search 返回 JSON，播放直链经 api/geturl 取得（含 url/lrc/album/duration/pic）。
 * 另有 api/getdown 走夸克网盘，移动端无 cookie 走不到，直接用 web 直链路径即可。
 */
class Mgmp3MusicSource : MusicSource {
    override val id = "mgmp3"
    override val label = "MGMP3"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36",
        "Referer" to "https://www.mgmp3.top/"
    )

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        val url = "https://www.mgmp3.top/api/search?keyword=${URLEncoder.encode(keyword, "UTF-8")}"
        val raw = get(url, headers) ?: return@withContext emptyList()
        val json = try { JSONObject(raw) } catch (_: Exception) { return@withContext emptyList() }
        val data = json.optJSONArray("data") ?: return@withContext emptyList()
        val list = ArrayList<Song>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val id = item.optString("id")
            val title = item.optString("title")
            if (id.isBlank() || title.isBlank()) continue
            val singer = item.optString("singer")
            val pic = item.optString("picurl")
            list.add(
                Song(
                    songId = id, source = this@Mgmp3MusicSource.id, title = title, artist = singer, album = "",
                    durationSec = 0, coverUrl = pic, playUrl = "", lrc = "", ext = "mp3", fileSizeBytes = 0L
                )
            )
        }
        list
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        withTimeoutOrNull(20_000) {
            val raw = get("https://www.mgmp3.top/api/geturl?id=${song.songId}", headers) ?: return@withTimeoutOrNull null
            val json = try { JSONObject(raw) } catch (_: Exception) { return@withTimeoutOrNull null }
            val downloadUrl = json.optString("url")
            if (downloadUrl.isBlank() || !downloadUrl.startsWith("http")) return@withTimeoutOrNull null
            val tested = AudioLinkTester.test(downloadUrl) ?: return@withTimeoutOrNull null
            song.playUrl = tested.url
            song.ext = tested.ext
            song.fileSizeBytes = tested.contentLength
            val lrc = cleanLrc(json.optString("lrc"))
            if (lrc.isNotBlank()) song.lrc = lrc
            val album = json.optString("album")
            if (album.isNotBlank()) song.album = album
            val secs = parseDuration(json.optString("duration"))
            if (secs > 0) song.durationSec = secs
            val pic = json.optString("pic")
            if (pic.isNotBlank()) song.coverUrl = pic
            tested.url
        }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.lrc.isNotBlank()) return@withContext song.lrc
        return@withContext try {
            val raw = get("https://www.mgmp3.top/api/geturl?id=${song.songId}", headers) ?: return@withContext null
            val json = JSONObject(raw)
            val cleaned = cleanLrc(json.optString("lrc"))
            if (cleaned.isBlank()) null else cleaned.also { song.lrc = it }
        } catch (_: Exception) { null }
    }

    private fun parseDuration(s: String?): Int {
        if (s.isNullOrBlank()) return 0
        val hasColon = s.contains(":") || s.contains("：")
        val nums = Regex("""\d+""").findAll(if (hasColon) s.replace("：", ":") else s).map { it.value.toIntOrNull() ?: 0 }.toList()
        return if (hasColon) {
            when {
                nums.size >= 3 -> nums[nums.size - 3] * 3600 + nums[nums.size - 2] * 60 + nums.last()
                nums.size == 2 -> nums[0] * 60 + nums[1]
                nums.size == 1 -> nums[0]
                else -> 0
            }
        } else {
            val h = Regex("""(\d+)\s*(?:小时|时|h|hr)""", RegexOption.IGNORE_CASE).find(s)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val mi = Regex("""(\d+)\s*(?:分钟|分|m|min)""", RegexOption.IGNORE_CASE).find(s)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val se = Regex("""(\d+)\s*(?:秒|s|sec)""", RegexOption.IGNORE_CASE).find(s)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            h * 3600 + mi * 60 + se
        }
    }

    private fun get(url: String, headers: Map<String, String>): String? {
        return try {
            val req = Request.Builder().url(url)
                .headers(Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray()))
                .get().build()
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) { resp.close(); return null }
            resp.body?.string()
        } catch (_: Exception) { null }
    }

    private fun cleanLrc(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val noTags = raw.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), " ")
        val unescaped = noTags
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        return unescaped.trim()
    }
}
