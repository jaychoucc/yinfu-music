package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

/**
 * 维基共享资源（Wikimedia Commons）音频搜索源，无需 key。
 * 搜索: https://commons.wikimedia.org/w/api.php  (generator=search, filetype:audio)
 * 每个音频文件的 imageinfo.url 即下载直链，存入 token，resolvePlayUrl 校验后写入 playUrl。
 * 对应 ref_all/wikimediacommons.py。
 */
class WikimediaCommonsMusicSource : MusicSource {
    override val id = "wikimedia"
    override val label = "维基共享"

    private val headers = mapOf(
        "accept" to "application/json",
        "referer" to "https://commons.wikimedia.org/",
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"
    )

    private val audioExts = setOf("mp3", "flac", "wav", "ogg", "oga", "m4a", "aac", "opus")

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val url = "https://commons.wikimedia.org/w/api.php?" + buildQuery(mapOf(
                "action" to "query",
                "generator" to "search",
                "gsrsearch" to (keyword + " filetype:audio"),
                "gsrnamespace" to "6",
                "gsrlimit" to pageSize.toString(),
                "gsroffset" to "0",
                "prop" to "imageinfo",
                "iiprop" to "url|size|mime|extmetadata",
                "iiurlwidth" to "500",
                "format" to "json",
                "formatversion" to "2",
                "origin" to "*"
            ))
            val req = Request.Builder().url(url)
                .headers(okhttp3.Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray()))
                .build()
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val json = JSONObject(resp.body?.string().orEmpty())
            val pages = json.optJSONObject("query")?.optJSONArray("pages") ?: return@withContext emptyList()

            val list = mutableListOf<Song>()
            for (i in 0 until pages.length()) {
                val page = pages.optJSONObject(i) ?: continue
                val imageinfo = page.optJSONArray("imageinfo") ?: continue
                if (imageinfo.length() == 0) continue
                val info = imageinfo.optJSONObject(0) ?: continue
                val durl = info.optString("url").ifBlank { continue }
                if (!durl.startsWith("http")) continue
                val mime = info.optString("mime")
                val extFromUrl = durl.substringBefore('?').substringAfterLast('.', "").lowercase()
                if (!mime.startsWith("audio") && extFromUrl !in audioExts) continue

                val meta = info.optJSONObject("extmetadata")
                val extMeta = fun(key: String): String {
                    val node = meta?.optJSONObject(key)
                    return node?.optString("value").orEmpty()
                }
                val titleRaw = extMeta("ObjectName").ifBlank { extMeta("ImageDescription") }
                    .ifBlank { page.optString("title").removePrefix("File:") }
                val artist = legalize(extMeta("Artist").ifBlank { extMeta("Credit").ifBlank { extMeta("Author").ifBlank { extMeta("Attribution") } } })
                val album = legalize(extMeta("Collection").ifBlank { extMeta("Categories") }.ifBlank { "Wikimedia Commons" })
                val cover = info.optString("thumburl")
                val durationSec = info.optDouble("duration", 0.0).toInt()
                val songId = page.optString("pageid").ifBlank { UUID.randomUUID().toString() }

                list.add(Song(
                    songId = songId,
                    source = this@WikimediaCommonsMusicSource.id,
                    title = legalize(titleRaw),
                    artist = artist,
                    album = album,
                    durationSec = durationSec,
                    coverUrl = cover,
                    playUrl = "",
                    lrc = "",
                    ext = extFromUrl.ifBlank { "mp3" },
                    fileSizeBytes = 0L,
                    token = durl
                ))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        val raw = song.token.ifBlank { return@withContext null }
        withTimeoutOrNull(20_000) {
            val tested = AudioLinkTester.test(raw)
            if (tested != null) {
                song.playUrl = tested.url
                tested.url
            } else null
        }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        null
    }

    private fun buildQuery(params: Map<String, String>): String =
        params.map { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }.joinToString("&")

    private fun legalize(s: String?): String {
        if (s == null) return ""
        return s.replace("\u0000", "").replace("\u200b", "").trim()
    }
}
