package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 酷我音乐源：搜索 + mobi.s DES 加密解析 + 第三方歌词兜底
 */
class KuwoMusicSource : MusicSource {
    override val id = "kuwo"
    override val label = "酷我音乐"

    private val searchHeaders = mapOf(
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
    )

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        val url = "http://www.kuwo.cn/search/searchMusicBykeyWord?" +
                "vipver=1&client=kt&ft=music&cluster=0&strategy=2012&encoding=utf8&rformat=json&mobi=1&issubtitle=1&show_copyright_off=1&pn=0&rn=$pageSize&all=${URLEncoder.encode(keyword, "UTF-8")}"
        val req = Request.Builder().url(url)
            .headers(okhttp3.Headers.headersOf(*searchHeaders.flatMap { listOf(it.key, it.value) }.toTypedArray()))
            .build()
        val resp = Net.client().newCall(req).execute()
        val json = JSONObject(resp.body?.string() ?: "{}")
        val abslist = json.optJSONObject("abslist") ?: json.optJSONArray("abslist") ?: return@withContext emptyList()

        val arr = if (abslist is org.json.JSONArray) abslist else json.optJSONArray("abslist") ?: return@withContext emptyList()
        val list = mutableListOf<Song>()
        for (i in 0 until arr.length()) {
            parseItem(arr.getJSONObject(i))?.let { list.add(it) }
        }
        list
    }

    private fun parseItem(item: JSONObject): Song? {
        val ridFull = item.optString("MUSICRID").ifBlank { item.optString("musicrid") }.ifBlank { return null }
        val rid = ridFull.removePrefix("MUSIC_")
        val name = item.optString("SONGNAME").ifBlank { item.optString("name").ifBlank { item.optString("songName") } }.ifBlank { return null }
        val artist = item.optString("ARTIST").ifBlank { item.optString("artist") }
        val album = item.optString("ALBUM").ifBlank { item.optString("album") }
        val cover = item.optString("hts_MVPIC").ifBlank { item.optString("albumpic").ifBlank { item.optString("pic") } }
        val duration = item.optString("DURATION").ifBlank { item.optString("duration") }.toFloatOrNull()?.toInt() ?: 0
        // 搜索阶段不解析播放地址（避免逐首联网导致慢 + 播放时二次轮询），点击播放时再解析
        return Song(
            songId = rid,
            source = id,
            title = name,
            artist = artist,
            album = album,
            durationSec = duration,
            coverUrl = cover,
            playUrl = "",
            lrc = "",
            ext = "mp3",
            fileSizeBytes = 0L
        )
    }

    private fun resolveUrl(rid: String): AudioLinkTester.Result? {
        val formats = listOf("flac", "mp3")
        for (fmt in formats) {
            try {
                val query = "user=0&corp=kuwo&source=kwplayer_ar_5.1.0.0_B_jiakong_vh.apk&p2p=1&type=convert_url2&sig=0&format=$fmt&rid=$rid"
                val enc = KuwoCrypto.encryptQuery(query)
                val url = "http://mobi.kuwo.cn/mobi.s?f=kuwo&q=${URLEncoder.encode(enc, "UTF-8")}"
                val req = Request.Builder().url(url)
                    .header("user-agent", "okhttp/3.10.0")
                    .build()
                val resp = Net.client().newCall(req).execute()
                val text = resp.body?.string() ?: continue
                val match = Regex("http[^\\s$\"]+").find(text)?.value ?: continue
                if (!match.startsWith("http")) continue
                AudioLinkTester.test(match)?.let { return it }
            } catch (_: Exception) {
            }
        }
        return null
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        // 单首解析，加 20 秒超时，失败返回 null（由 PlayerRepository 跳下一首）
        withTimeoutOrNull(20_000) {
            resolveUrl(song.songId)?.url?.also { song.playUrl = it }
        } ?: song.playUrl.takeIf { it.startsWith("http") }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.lrc.isNotBlank()) return@withContext song.lrc
        // 酷我歌词实现较复杂，暂返回空，不影响播放
        ""
    }
}
