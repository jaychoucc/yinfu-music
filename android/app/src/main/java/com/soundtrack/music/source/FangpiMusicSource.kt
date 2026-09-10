package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import java.net.URLEncoder

/**
 * Fangpi 音乐 (https://www.fangpi.net)：与歌曲宝同源结构，搜索列表拿到 /music/<id>，
 * 播放阶段先抓详情页解析 window.appData 得到播放 id，再 POST /member/common-play-url 取直链（无需夸克网盘）。
 * 歌词在详情页 #content-lrc。
 */
class FangpiMusicSource : MusicSource {
    override val id = "fangpi"
    override val label = "Fangpi音乐"

    private val base = "https://www.fangpi.net"
    private val searchHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "https://www.fangpi.net/"
    )
    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Content-Type" to "application/json",
        "Origin" to "https://www.fangpi.net",
        "Referer" to "https://www.fangpi.net/"
    )

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        val url = "$base/s/${URLEncoder.encode(keyword, "UTF-8")}"
        val req = Request.Builder().url(url).headers(toHeaders(searchHeaders)).get().build()
        try {
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val html = resp.body?.string().orEmpty()
            val list = mutableListOf<Song>()
            val rows = Regex("""<div[^>]*class="row"[^>]*>(.*?)(?=<div[^>]*class="row"|$)""", RegexOption.DOT_MATCHES_ALL).findAll(html)
            for (m in rows) {
                val inner = m.groupValues[1]
                val href = Regex("""href="/music/([^"]*)"""").find(inner)?.groupValues?.get(1) ?: continue
                val songId = href.trimEnd('/').substringAfterLast('/')
                if (songId.isBlank()) continue
                val title = Regex("""<a[^>]*href="/music/[^"]*"[^>]*title="([^"]*)"""").find(inner)?.groupValues?.get(1)?.let { unescapeHtml(it) }?.trim()
                    ?: Regex("""<span[^>]*class="text-primary"[^>]*>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL).find(inner)?.groupValues?.get(1)?.let { unescapeHtml(it) }?.trim()
                    ?: continue
                val artist = Regex("""<small[^>]*class="text-jade"[^>]*>(.*?)</small>""", RegexOption.DOT_MATCHES_ALL).find(inner)?.groupValues?.get(1)?.let { unescapeHtml(it) }?.trim() ?: ""
                list.add(
                    Song(
                        songId = songId,
                        source = id,
                        title = title,
                        artist = artist,
                        album = "",
                        durationSec = 0,
                        coverUrl = "",
                        playUrl = "",
                        lrc = "",
                        ext = "mp3",
                        fileSizeBytes = 0L,
                        token = "$base/music/$songId"
                    )
                )
                if (list.size >= pageSize) break
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        val detail = song.token
        if (detail.isBlank()) return@withContext null
        withTimeoutOrNull(20_000) {
            try {
                val detailReq = Request.Builder().url(detail).headers(toHeaders(searchHeaders)).get().build()
                val detailResp = Net.client().newCall(detailReq).execute()
                val detailHtml = detailResp.body?.string().orEmpty()
                val playId = extractPlayId(detailHtml) ?: song.songId
                val payload = org.json.JSONObject().put("id", playId).toString()
                val body = RequestBody.create("application/json".toMediaType(), payload)
                val apiReq = Request.Builder().url("$base/member/common-play-url").headers(toHeaders(apiHeaders)).post(body).build()
                val apiResp = Net.client().newCall(apiReq).execute()
                val json = org.json.JSONObject(apiResp.body?.string().orEmpty())
                val url = json.optJSONObject("data")?.optString("url") ?: return@withTimeoutOrNull null
                if (!url.startsWith("http")) return@withTimeoutOrNull null
                val tested = AudioLinkTester.test(url) ?: return@withTimeoutOrNull null
                song.playUrl = tested.url
                tested.url
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.lrc.isNotBlank()) return@withContext song.lrc
        val detail = song.token
        if (detail.isBlank()) return@withContext null
        try {
            val req = Request.Builder().url(detail).headers(toHeaders(searchHeaders)).get().build()
            val resp = Net.client().newCall(req).execute()
            val html = resp.body?.string().orEmpty()
            val block = Regex("""<div[^>]*id="content-lrc"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1) ?: return@withContext null
            val text = block.replace("<[^>]+>".toRegex(), "\n").let { unescapeHtml(it) }
            val cleaned = cleanLrc(text)
            if (cleaned != null) song.lrc = cleaned
            cleaned
        } catch (e: Exception) {
            null
        }
    }

    /** 详情页 window.appData 里的播放 id（优先 play_id，其次 id/mp3_id） */
    private fun extractPlayId(html: String): String? {
        val m = Regex("""window\.appData\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL).find(html) ?: return null
        return try {
            val obj = org.json.JSONObject(m.groupValues[1])
            val cand = obj.optString("play_id").ifBlank { obj.optString("id") }.ifBlank { obj.optString("mp3_id") }
            cand.ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    private fun toHeaders(map: Map<String, String>): okhttp3.Headers =
        okhttp3.Headers.headersOf(*map.flatMap { listOf(it.key, it.value) }.toTypedArray())

    private fun unescapeHtml(s: String): String {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
            .replace("<[^>]+>".toRegex(), "").trim()
    }

    private fun cleanLrc(s: String): String? {
        val lines = s.lines().map { it.trim() }.filter { it.isNotBlank() }
        return if (lines.isEmpty()) null else lines.joinToString("\n")
    }
}
