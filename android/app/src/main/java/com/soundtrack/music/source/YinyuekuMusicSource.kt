package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * 音乐库 (yinyueku)：POST 到 http://yinyueku.cn/api.php?
 * types=search 返回歌曲列表（id/source/sign/name/artist/album/pic_id/lyric_id），
 * 再按需用 types=url / pic / lyric 取得直链、封面、歌词。接口为普通 JSON，无加密，可独立解析。
 */
class YinyuekuMusicSource : MusicSource {
    override val id = "yinyueku"
    override val label = "音乐库"

    private data class Meta(val source: String, val sign: String, val picId: String, val lyricId: String)
    private val metaCache = ConcurrentHashMap<String, Meta>()

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36",
        "Accept" to "text/javascript, application/javascript, application/ecmascript, application/x-ecmascript, */*; q=0.01",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "Origin" to "http://yinyueku.cn",
        "Referer" to "http://yinyueku.cn/",
        "X-Requested-With" to "XMLHttpRequest"
    )

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        val params = mapOf(
            "types" to "search",
            "count" to pageSize.toString(),
            "pages" to "1",
            "name" to keyword
        )
        val raw = postForm("http://yinyueku.cn/api.php?", params, headers) ?: return@withContext emptyList()
        val arr: JSONArray = try {
            when (val v = org.json.JSONTokener(raw).nextValue()) {
                is JSONArray -> v
                is JSONObject -> v.optJSONArray("data") ?: v.optJSONArray("list") ?: v.optJSONArray("results") ?: JSONArray()
                else -> JSONArray()
            }
        } catch (_: Exception) {
            return@withContext emptyList()
        }
        val list = ArrayList<Song>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val songId = item.optString("id")
            val songSource = item.optString("source")
            val sign = item.optString("sign")
            val name = item.optString("name")
            if (songId.isBlank() || songSource.isBlank() || sign.isBlank() || name.isBlank()) continue
            val artistArr = item.optJSONArray("artist")
            val artist = if (artistArr != null) {
                (0 until artistArr.length()).mapNotNull { artistArr.optString(it).takeIf { s -> s.isNotBlank() } }.joinToString(", ")
            } else ""
            val album = item.optString("album")
            val picId = item.optString("pic_id")
            val lyricId = item.optString("lyric_id")
            metaCache[songId] = Meta(songSource, sign, picId, lyricId)
            list.add(
                Song(
                    songId = songId, source = this@YinyuekuMusicSource.id, title = name, artist = artist, album = album,
                    durationSec = 0, coverUrl = "", playUrl = "", lrc = "", ext = "mp3", fileSizeBytes = 0L
                )
            )
        }
        list
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        val meta = metaCache[song.songId] ?: return@withContext null
        withTimeoutOrNull(20_000) {
            val params = mapOf(
                "types" to "url",
                "id" to song.songId,
                "source" to meta.source,
                "sign" to meta.sign
            )
            val raw = postForm("http://yinyueku.cn/api.php?", params, headers) ?: return@withTimeoutOrNull null
            val json = try { JSONObject(raw) } catch (_: Exception) { return@withTimeoutOrNull null }
            val downloadUrl = json.optString("url")
            if (downloadUrl.isBlank() || !downloadUrl.startsWith("http")) return@withTimeoutOrNull null
            val tested = AudioLinkTester.test(downloadUrl) ?: return@withTimeoutOrNull null
            song.playUrl = tested.url
            song.ext = tested.ext
            song.fileSizeBytes = tested.contentLength
            if (song.coverUrl.isBlank() && meta.picId.isNotBlank()) {
                val picParams = mapOf(
                    "types" to "pic", "id" to meta.picId, "source" to meta.source,
                    "song" to song.songId, "sign" to meta.sign
                )
                val picRaw = postForm("http://yinyueku.cn/api.php?", picParams, headers)
                val picJson = try { JSONObject(picRaw ?: "") } catch (_: Exception) { null }
                val cover = picJson?.optString("url") ?: ""
                if (cover.startsWith("http")) song.coverUrl = cover
            }
            tested.url
        }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.lrc.isNotBlank()) return@withContext song.lrc
        val meta = metaCache[song.songId] ?: return@withContext null
        if (meta.lyricId.isBlank()) return@withContext null
        return@withContext try {
            val params = mapOf(
                "types" to "lyric", "id" to meta.lyricId, "source" to meta.source,
                "song" to song.songId, "sign" to meta.sign
            )
            val raw = postForm("http://yinyueku.cn/api.php?", params, headers) ?: return@withContext null
            val json = JSONObject(raw)
            val lyric = json.optString("lyric")
            val cleaned = cleanLrc(lyric)
            if (cleaned.isBlank()) null else cleaned.also { song.lrc = it }
        } catch (_: Exception) { null }
    }

    private fun postForm(url: String, params: Map<String, String>, headers: Map<String, String>): String? {
        return try {
            val body = FormBody.Builder()
            for ((k, v) in params) body.add(k, v)
            val req = Request.Builder().url(url)
                .headers(Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray()))
                .post(body.build()).build()
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
