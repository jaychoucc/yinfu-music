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
import java.util.concurrent.ConcurrentHashMap

/**
 * StreetVoice（中国台湾街声）音源。
 *
 * 重要限制（务必注意）：
 *  - 原始 musicdl 客户端靠 BeautifulSoup 解析搜索结果 HTML，而本工程没有 JSoup，因此这里用
 *    正则从搜索页 HTML 中提取歌曲卡片（id / 标题 / 作者 / 封面）。页面结构一旦改版，解析可能失败，
 *    此时搜索返回空列表，不会伪造数据。
 *  - 街声的播放地址是 HLS 流（api/v5/song/{id}/hls/file 返回的 file），并非可直接播放的 mp3/flac 文件。
 *    AudioLinkTester 对 HLS 片段通常校验不过，故这里在「校验通过」时采用校验结果，否则退化为直接返回
 *    该 HLS 文件地址（best-effort，播放器可能不支持 HLS）。这是「能做的那一步」，已在注释说明。
 *
 * 参考：ref_all 下没有 streetvoice.py，源码取自已安装的 musicdl 包
 *       musicdl/modules/sources/streetvoice.py（版本 2.13.11）。
 */
class StreetvoiceMusicSource : MusicSource {
    override val id = "streetvoice"
    override val label = "StreetVoice"

    private val detailCache = ConcurrentHashMap<String, String>()

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36 Edg/121.0.0.0",
        "Referer" to "https://www.streetvoice.cn/",
        "x-requested-with" to "XMLHttpRequest"
    )

    private fun reqHeaders() =
        okhttp3.Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray())

    private val cardRegex = Regex("""<li[^>]*class="[^"]*work-item item_box[^"]*"[^>]*>(.*?)</li>""", RegexOption.DOT_MATCHES_ALL)
    private val songLinkRegex = Regex("""<a[^>]*href="/songs/(\d+)\.html"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    private val userLinkRegex = Regex("""<a[^>]*href="/users/[^"]*"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    private val imgRegex = Regex("""<img[^>]*src="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
    private val tagRegex = Regex("<[^>]+>")

    private fun stripTags(s: String): String = tagRegex.replace(s, "").trim()

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        val url = "https://www.streetvoice.cn/search/?" + listOf(
            "page=1",
            "q=" + URLEncoder.encode(keyword, "UTF-8"),
            "type=song",
            "_pjax=%23pjax-container"
        ).joinToString("&")
        val req = Request.Builder().url(url).headers(reqHeaders()).build()
        try {
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val html = resp.body?.string() ?: return@withContext emptyList()

            val list = ArrayList<Song>()
            val seen = HashSet<String>()
            for (m in cardRegex.findAll(html)) {
                val block = m.value
                val songM = songLinkRegex.find(block) ?: continue
                val sid = songM.groupValues[1]
                if (sid in seen) continue
                seen.add(sid)
                val title = stripTags(songM.groupValues[2]).ifBlank { continue }
                val artist = userLinkRegex.find(block)?.groupValues?.getOrNull(1)?.let { stripTags(it) } ?: ""
                val cover = imgRegex.find(block)?.groupValues?.getOrNull(1) ?: ""
                list.add(
                    Song(
                        songId = sid,
                        source = this@StreetvoiceMusicSource.id,
                        title = title,
                        artist = artist,
                        album = "",
                        durationSec = 0,
                        coverUrl = cover,
                        playUrl = "",
                        lrc = "",
                        ext = "mp3",
                        fileSizeBytes = 0L
                    )
                )
                if (list.size >= pageSize) break
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 获取歌曲详情 JSON（api/v5/song/{id}/）。 */
    private fun getDetail(songId: String): JSONObject? {
        val url = "https://www.streetvoice.cn/api/v5/song/$songId/?_=${System.currentTimeMillis()}"
        val req = Request.Builder().url(url).headers(reqHeaders()).build()
        val resp = Net.client().newCall(req).execute()
        if (!resp.isSuccessful) return null
        val json = JSONObject(resp.body?.string() ?: "{}")
        detailCache[songId] = json.toString()
        return json
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        withTimeoutOrNull(20_000) {
            try {
                val detail = runCatching { getDetail(song.songId) }.getOrNull() ?: return@withTimeoutOrNull null
                val hlsUrl = "https://www.streetvoice.cn/api/v5/song/${song.songId}/hls/file/"
                val hlsReq = Request.Builder().url(hlsUrl).headers(reqHeaders())
                    .post(FormBody.Builder().build()).build()
                val hlsResp = Net.client().newCall(hlsReq).execute()
                val hlsJson = JSONObject(hlsResp.body?.string() ?: "{}")
                val file = hlsJson.optString("file")
                if (!file.startsWith("http")) return@withTimeoutOrNull null
                // 优先用 AudioLinkTester 校验；HLS 通常校验不过，退化返回原始地址（best-effort）
                // 注意：Song.ext 为 val，解析阶段无法改写，ext 沿用搜索时的默认值
                val tested = AudioLinkTester.test(file)
                val finalUrl = tested?.url ?: file
                song.playUrl = finalUrl
                finalUrl
            } catch (_: Exception) {
                null
            }
        }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.lrc.isNotBlank()) return@withContext song.lrc
        try {
            val detailStr = detailCache[song.songId] ?: runCatching { getDetail(song.songId)?.toString() }.getOrNull()
            val json = detailStr?.let { JSONObject(it) } ?: return@withContext null
            val lyrics = json.optString("lyrics").ifBlank { return@withContext null }
            val cleaned = lyrics.trim()
            if (cleaned.isBlank()) null else cleaned
        } catch (_: Exception) {
            null
        }
    }
}
