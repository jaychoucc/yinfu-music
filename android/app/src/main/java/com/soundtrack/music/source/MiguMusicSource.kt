package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 咪咕音乐源：搜索 + listen-url 解密 + 歌词
 */
class MiguMusicSource : MusicSource {
    override val id = "migu"
    override val label = "咪咕音乐"

    private val magic = byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0x01)
    private val key = "Jk8qzuePiJ1qE3mDYhLQ3T73DtDoAhLP".toByteArray()
    // contentId -> copyrightId 缓存（搜索阶段记录，播放阶段直接用于解析）
    private val copyrightCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val searchHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Origin" to "https://h5.nf.migu.cn",
        "Referer" to "https://h5.nf.migu.cn/",
        "ua" to "Android_migu",
        "version" to "6.8.8",
        "channel" to "014021I",
        "subchannel" to "014021I"
    )

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        val switch = URLEncoder.encode("""{"song":1,"album":0,"singer":0,"tagSong":1,"mvSong":0,"bestShow":1}""", "UTF-8")
        val url = "https://c.musicapp.migu.cn/v1.0/content/search_all.do?text=${URLEncoder.encode(keyword, "UTF-8")}&pageNo=1&pageSize=$pageSize&isCopyright=1&sort=1&searchSwitch=$switch"
        val req = Request.Builder().url(url).headers(okhttp3.Headers.headersOf(*searchHeaders.flatMap { listOf(it.key, it.value) }.toTypedArray())).build()
        val resp = Net.client().newCall(req).execute()
        val body = resp.body?.string() ?: return@withContext emptyList()
        val json = JSONObject(body)
        val results = json.optJSONObject("songResultData")?.optJSONArray("result") ?: return@withContext emptyList()

        val list = mutableListOf<Song>()
        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            val song = parseSearchItem(item)
            if (song != null) list.add(song)
        }
        list
    }

    private fun parseSearchItem(item: JSONObject): Song? {
        val contentId = item.optString("contentId").ifBlank { return null }
        val copyrightId = item.optString("copyrightId").ifBlank { return null }
        val name = item.optString("name").ifBlank { item.optString("songName") }.ifBlank { return null }
        val singers = item.optJSONArray("singers")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optString("name") }.joinToString(", ")
        } ?: item.optJSONArray("singerList")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optString("name") }.joinToString(", ")
        } ?: ""
        val album = item.optJSONArray("albums")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optString("name") }.joinToString(", ")
        } ?: item.optString("album")
        val cover = item.optJSONArray("imgItems")?.let { arr ->
            if (arr.length() > 0) arr.getJSONObject(arr.length() - 1).optString("img") else null
        } ?: item.optString("img3").ifBlank { item.optString("img2").ifBlank { item.optString("img1") } }
        val duration = item.optInt("length", 0)
        // 记录 copyrightId 供播放阶段直接解析，避免重新搜索（轮询根源之一）
        copyrightCache[contentId] = copyrightId
        return Song(
            songId = contentId,
            source = id,
            title = name,
            artist = singers,
            album = album,
            durationSec = duration,
            coverUrl = cover?.let { if (it.startsWith("http")) it else "https://d.musicapp.migu.cn$it" } ?: "",
            playUrl = "",
            lrc = "",
            ext = "mp3",
            fileSizeBytes = 0L
        )
    }

    /** 用 contentId + copyrightId 直接解析播放地址（不经搜索，避免轮询） */
    private fun resolveBy(contentId: String, copyrightId: String): AudioLinkTester.Result? {
        val formats = listOf("PQ", "HQ", "SQ", "ZQ")
        for (formatType in formats) {
            val url = "https://c.musicapp.migu.cn/strategy/listen-url/h5/v2.4".toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("contentId", contentId)
                .addQueryParameter("copyrightId", copyrightId)
                .addQueryParameter("resourceType", "2")
                .addQueryParameter("netType", "01")
                .addQueryParameter("toneFlag", formatType)
                .addQueryParameter("scene", "")
                .addQueryParameter("lowerQualityContentId", contentId)
                .build()
            val req = Request.Builder().url(url)
                .headers(okhttp3.Headers.headersOf(*searchHeaders.flatMap { listOf(it.key, it.value) }.toTypedArray()))
                .header("birth", "h5page")
                .header("signature", "1")
                .build()
            try {
                val resp = Net.client().newCall(req).execute()
                val bytes = resp.body?.bytes() ?: continue
                val decrypted = decrypt(bytes)
                val json = JSONObject(decrypted)
                var downloadUrl = json.optJSONObject("data")?.optString("url") ?: ""
                if (downloadUrl.isBlank()) {
                    downloadUrl = "https://app.pd.nf.migu.cn/MIGUM3.0/v1.0/content/sub/listenSong.do?channel=mx&copyrightId=$copyrightId&contentId=$contentId&toneFlag=$formatType&resourceType=2&userId=15548614588710179085069&netType=00"
                }
                downloadUrl = downloadUrl.replace(Regex("(?<=/)MP3_128_16_Stero(?=/)"), "MP3_320_16_Stero")
                val tested = AudioLinkTester.test(downloadUrl)
                if (tested != null) return tested
            } catch (_: Exception) {
            }
        }
        return null
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        val cid = copyrightCache[song.songId]
            ?: runCatching { search(song.title + " " + song.artist, 3).firstOrNull()?.let { copyrightCache[song.songId] } }.getOrNull()
        if (cid.isNullOrBlank()) return@withContext null
        withTimeoutOrNull(20_000) {
            resolveBy(contentId = song.songId, copyrightId = cid)?.url?.also { song.playUrl = it }
        }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.lrc.isNotBlank()) return@withContext song.lrc
        // 咪咕歌词需要 contentId/copyrightId，这里尝试搜索后获取
        val list = search(song.title + " " + song.artist, 5)
        val match = list.find { it.songId == song.songId || it.title == song.title }
        match?.lrc?.also { song.lrc = it }
    }

    private fun fetchLyricFromItem(item: JSONObject): String? {
        val contentId = item.optString("contentId").ifBlank { return null }
        val copyrightId = item.optString("copyrightId").ifBlank { return null }
        val lyricUrl = item.optString("lyricUrl").ifBlank {
            try {
                val url = "https://app.c.nf.migu.cn/MIGUM3.0/strategy/pc/listen/v1.0?scene=&netType=01&resourceType=2&copyrightId=$copyrightId&contentId=$contentId&toneFlag=PQ"
                val req = Request.Builder().url(url).headers(okhttp3.Headers.headersOf(*searchHeaders.flatMap { listOf(it.key, it.value) }.toTypedArray())).build()
                val resp = Net.client().newCall(req).execute()
                val body = resp.body?.string() ?: return@ifBlank null
                JSONObject(body).optJSONObject("data")?.optString("lrcUrl")
            } catch (_: Exception) {
                null
            }
        } ?: return null
        return try {
            val req = Request.Builder().url(lyricUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "https://y.migu.cn/")
                .build()
            Net.client().newCall(req).execute().body?.string()
        } catch (_: Exception) {
            null
        }
    }

    private fun decrypt(raw: ByteArray): String {
        if (raw.size < 4) return String(raw)
        if (raw.sliceArray(0..2).contentEquals(magic)) {
            val seed = raw[3].toInt() and 0xFF
            val plain = ByteArray(raw.size - 4)
            for (i in 0 until plain.size) {
                plain[i] = ((raw[i + 4].toInt() and 0xFF) + seed - (key[i % key.size].toInt() and 0xFF) and 0xFF).toByte()
            }
            return String(plain, Charsets.UTF_8)
        }
        return String(raw, Charsets.UTF_8)
    }
}
