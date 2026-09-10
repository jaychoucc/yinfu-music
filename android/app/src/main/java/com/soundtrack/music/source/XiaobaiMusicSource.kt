package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 小白音乐 XiaoBaiMusicClient 移植：https://music.90svip.cn/
 * - 搜索：POST https://music.90svip.cn/  (form: input, filter=name, type=<source>, page)
 *   多个源 netease/qq/kugou/kuwo 分别 POST，结果合并。返回 JSON {"data":[...]}
 *   每项含 songid/name/artist/album/url/cover/lrc（相对路径，需拼 base）
 * - 下载：url 拼 base 后 HEAD 跟随重定向得到真实外链
 * - 歌词：lrc 拼 base 后 GET
 * 该源无夸克依赖，可直接拿到外链。
 */
class XiaobaiMusicSource : MusicSource {
    override val id = "xiaobai"
    override val label = "小白音乐"

    private val BASE = "https://music.90svip.cn/"
    private val SOURCES = listOf("netease", "qq", "kugou", "kuwo")

    private val searchHeaders = mapOf(
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36",
        "origin" to "https://music.90svip.cn",
        "x-requested-with" to "XMLHttpRequest",
        "accept" to "application/json, text/javascript, */*; q=0.01",
        "content-type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "referer" to "https://music.90svip.cn/"
    )
    private val downloadHeaders = mapOf(
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36",
        "referer" to "https://music.90svip.cn/"
    )

    private data class Meta(val rootSource: String, val url: String, val cover: String, val lrc: String)
    private val metaCache = ConcurrentHashMap<String, Meta>()

    private fun join(base: String, part: String?): String {
        if (part.isNullOrBlank()) return ""
        if (part.startsWith("http")) return part
        return base + part.removePrefix("/")
    }

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        val list = ArrayList<Song>()
        try {
            for (source in SOURCES) {
                try {
                    val body = FormBody.Builder()
                        .add("input", keyword)
                        .add("filter", "name")
                        .add("type", source)
                        .add("page", "1")
                        .build()
                    val req = Request.Builder().url(BASE)
                        .headers(okhttp3.Headers.headersOf(*searchHeaders.flatMap { listOf(it.key, it.value) }.toTypedArray()))
                        .post(body).build()
                    val resp = Net.client().newCall(req).execute()
                    if (!resp.isSuccessful) continue
                    val text = resp.body?.string().orEmpty()
                    val json = JSONObject(text)
                    val data = json.optJSONArray("data") ?: continue
                    for (i in 0 until data.length()) {
                        val item = data.optJSONObject(i) ?: continue
                        val songId = item.optString("songid").ifBlank { continue }
                        val url = item.optString("url").ifBlank { continue }
                        val title = item.optString("name").ifBlank { continue }
                        val key = songId + "|" + source
                        metaCache[key] = Meta(
                            rootSource = source,
                            url = url,
                            cover = item.optString("cover"),
                            lrc = item.optString("lrc")
                        )
                        list.add(
                            Song(
                                songId = songId,
                                source = this@XiaobaiMusicSource.id,
                                title = title,
                                artist = (item.optString("artist") ?: "").replace("/", ", "),
                                album = item.optString("album"),
                                durationSec = 0,
                                coverUrl = join(BASE, item.optString("cover")),
                                playUrl = "",
                                lrc = "",
                                ext = "mp3",
                                fileSizeBytes = 0L,
                                token = source
                            )
                        )
                    }
                } catch (e: Exception) {
                    // 单个源失败不影响其它源
                }
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        val meta = metaCache[song.songId + "|" + song.token] ?: return@withContext null
        val target = join(BASE, meta.url)
        if (target.isBlank()) return@withContext null
        withTimeoutOrNull(20_000) {
            val tested = AudioLinkTester.test(target)
            if (tested != null) {
                song.playUrl = tested.url
                song.ext = tested.ext
                song.fileSizeBytes = tested.contentLength
                tested.url
            } else null
        }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.lrc.isNotBlank()) return@withContext song.lrc
        val meta = metaCache[song.songId + "|" + song.token] ?: return@withContext null
        val lrcUrl = join(BASE, meta.lrc)
        if (lrcUrl.isBlank()) return@withContext null
        try {
            val req = Request.Builder().url(lrcUrl)
                .headers(okhttp3.Headers.headersOf(*downloadHeaders.flatMap { listOf(it.key, it.value) }.toTypedArray()))
                .get().build()
            val resp = Net.client().newCall(req).execute()
            val text = resp.body?.string().orEmpty()
            val cleaned = text.trim()
            if (cleaned.isBlank() || cleaned == "NULL") null else cleaned.also { song.lrc = it }
        } catch (e: Exception) {
            null
        }
    }
}
