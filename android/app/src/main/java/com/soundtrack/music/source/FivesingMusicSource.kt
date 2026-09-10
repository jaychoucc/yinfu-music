package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 5sing 原创音乐（https://5sing.kugou.com）源。
 *
 * 接口与 Python 参考实现 ref_all/fivesing.py 保持一致：
 *   1) 搜索  http://search.5sing.kugou.com/home/json?keyword=&sort=1&page=&filter=0&type=0
 *      type=0 表示「全部」（原创 yc / 翻唱 fc / 伴奏 bz 都会返回），每条结果的 `typeEname`
 *      标明类别，解析播放链接时必须带上它，所以暂存到 Song.token（格式 "songId|typeEname"）。
 *   2) 播放  http://mobileapi.5sing.kugou.com/song/getSongUrl?songid=&songtype=
 *      依次尝试 data.squrl / hqurl / lqurl（及其 _backup），用 AudioLinkTester 校验。
 *   3) 详情/歌词 http://mobileapi.5sing.kugou.com/song/newget?songid=&songtype=
 *      data.dynamicWords 为 LRC，data.albumName 为专辑，data.user.I 为封面（用户头像）。
 *
 * 注意：搜索阶段不做逐条解析（Python 是边搜边解析，手机上太慢），播放链接延迟到
 * resolvePlayUrl 再算，符合 Song 的「延迟富化」设计。
 */
class FivesingMusicSource : MusicSource {
    override val id = "fivesing"
    override val label = "5sing原创"

    private val searchUrl = "http://search.5sing.kugou.com/home/json"
    private val songUrlApi = "http://mobileapi.5sing.kugou.com/song/getSongUrl"
    private val songDetailApi = "http://mobileapi.5sing.kugou.com/song/newget"

    /** 音质从高到低，与 Python 的 MUSIC_QUALITIES 一致 */
    private val qualities = listOf("sq", "hq", "lq")

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "zh-CN,zh;q=0.9",
        "Referer" to "https://5sing.kugou.com/"
    )

    /** songId -> typeEname（yc/fc/bz），Song 被序列化后 token 丢失时的兜底 */
    private val typeCache = ConcurrentHashMap<String, String>()

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val want = if (pageSize <= 0) 20 else pageSize
            val out = ArrayList<Song>()
            val seen = HashSet<String>()
            var page = 1
            while (out.size < want && page <= 5) {
                val url = buildUrl(
                    searchUrl,
                    mapOf(
                        "keyword" to keyword,
                        "sort" to "1",
                        "page" to page.toString(),
                        "filter" to "0",
                        "type" to "0"
                    )
                )
                val json = getJson(url) ?: break
                val arr = json.optJSONArray("list") ?: break
                if (arr.length() <= 0) break
                for (i in 0 until arr.length()) {
                    if (out.size >= want) break
                    val item = arr.optJSONObject(i) ?: continue
                    val song = parseSearchItem(item) ?: continue
                    val dedupKey = song.songId + "|" + song.token
                    if (!seen.add(dedupKey)) continue
                    out.add(song)
                }
                page += 1
            }
            // 补封面/专辑/时长：详情页是逐条请求，只补前若干条，避免拖慢搜索
            val limit = minOf(out.size, 8)
            for (i in 0 until limit) {
                val song = out[i]
                val pair = splitToken(song)
                val detail = fetchDetail(pair.first, pair.second) ?: continue
                applyDetail(song, detail)
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        val pair = splitToken(song)
        if (pair.first.isBlank() || pair.second.isBlank()) return@withContext null
        withTimeoutOrNull(20_000) {
            val url = buildUrl(
                songUrlApi,
                mapOf("songid" to pair.first, "songtype" to pair.second)
            )
            val json = getJson(url) ?: return@withTimeoutOrNull null
            val data = json.optJSONObject("data") ?: return@withTimeoutOrNull null
            for (quality in qualities) {
                val candidate = firstNonBlank(
                    data,
                    listOf(quality + "url", quality + "url_backup", quality + "Url")
                ) ?: continue
                if (!candidate.startsWith("http")) continue
                val tested = AudioLinkTester.test(candidate) ?: continue
                song.playUrl = tested.url
                song.ext = tested.ext
                song.fileSizeBytes = tested.contentLength
                // 顺手把专辑/封面/歌词补齐
                runCatching {
                    val detail = fetchDetail(pair.first, pair.second)
                    if (detail != null) applyDetail(song, detail)
                }
                return@withTimeoutOrNull tested.url
            }
            null
        }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.lrc.isNotBlank()) return@withContext song.lrc
        val pair = splitToken(song)
        if (pair.first.isBlank()) return@withContext null
        try {
            val detail = fetchDetail(pair.first, pair.second) ?: return@withContext null
            applyDetail(song, detail)
            val lrc = cleanLrc(detail.optString("dynamicWords"))
            if (lrc.isBlank()) return@withContext null
            song.lrc = lrc
            return@withContext lrc
        } catch (e: Exception) {
            return@withContext null
        }
    }

    /** 解析搜索结果中的一条记录；token 存 "songId|typeEname" 供后续解析使用 */
    private fun parseSearchItem(item: JSONObject): Song? {
        val songId = firstNonBlank(item, listOf("songId", "songid", "ID", "id")) ?: return null
        if (songId.isBlank()) return null
        val songType = normalizeType(firstNonBlank(item, listOf("typeEname", "typeename", "type", "songType")))
        val title = legalizeString(firstNonBlank(item, listOf("songName", "songname", "name", "title")) ?: "")
        if (title.isBlank()) return null
        val artist = legalizeString(firstNonBlank(item, listOf("singer", "singerName", "nickName", "userName")) ?: "")
        val album = legalizeString(firstNonBlank(item, listOf("albumName", "album")) ?: "")
        if (songType.isNotBlank()) typeCache[songId] = songType
        return Song(
            songId = songId,
            source = id,
            title = title,
            artist = artist,
            album = album,
            durationSec = 0,
            coverUrl = "",
            playUrl = "",
            lrc = "",
            ext = "mp3",
            fileSizeBytes = 0L,
            token = songId + "|" + songType
        )
    }

    /** 详情页（专辑名 / 用户头像 / 动态歌词）。失败返回 null。 */
    private fun fetchDetail(songId: String, songType: String): JSONObject? {
        if (songId.isBlank()) return null
        val url = buildUrl(
            songDetailApi,
            mapOf(
                "songid" to songId,
                "songtype" to songType,
                "songfields" to "",
                "userfields" to ""
            )
        )
        val json = getJson(url) ?: return null
        return json.optJSONObject("data")
    }

    /** 用详情页数据回填专辑 / 封面 / 时长（只在有值时覆盖） */
    private fun applyDetail(song: Song, data: JSONObject) {
        val album = legalizeString(firstNonBlank(data, listOf("albumName", "album_name", "album")) ?: "")
        if (album.isNotBlank()) song.album = album
        val cover = firstNonBlank(
            data,
            listOf("songPhoto", "songphoto", "pic", "cover", "I")
        ) ?: data.optJSONObject("user")?.let { u ->
            firstNonBlank(u, listOf("I", "image", "avatar"))
        }
        if (!cover.isNullOrBlank() && cover.startsWith("http")) song.coverUrl = cover
        if (song.durationSec <= 0) {
            val lrc = cleanLrc(data.optString("dynamicWords"))
            val sec = extractDurationSecondsFromLrc(lrc)
            if (sec > 0) song.durationSec = sec
        }
    }

    /** GET 一个 JSON 接口；先按 Python 的 http 试，失败再试 https */
    private fun getJson(url: String): JSONObject? {
        for (candidate in schemeVariants(url)) {
            try {
                val req = Request.Builder()
                    .url(candidate)
                    .headers(headersOf(headers))
                    .get()
                    .build()
                val resp = Net.client().newCall(req).execute()
                if (!resp.isSuccessful) {
                    resp.close()
                    continue
                }
                val body = resp.body?.string()
                resp.close()
                if (body.isNullOrBlank()) continue
                val value = org.json.JSONTokener(body.trim().removePrefix("\uFEFF")).nextValue()
                if (value is JSONObject) return value
            } catch (e: Exception) {
                // 换下一个 scheme 继续尝试
            }
        }
        return null
    }

    private fun schemeVariants(url: String): List<String> {
        return if (url.startsWith("http://")) listOf(url, "https://" + url.removePrefix("http://")) else listOf(url)
    }

    /** 依次尝试若干 key，返回第一个非空字符串 */
    private fun firstNonBlank(obj: JSONObject, keys: List<String>): String? {
        for (k in keys) {
            val v = obj.opt(k)
            if (v == null || v === JSONObject.NULL) continue
            val s = v.toString().trim()
            if (s.isNotBlank() && s != "null") return s
        }
        return null
    }

    /** 归一化为 yc（原创）/ fc（翻唱）/ bz（伴奏）；无法识别时按原创处理 */
    private fun normalizeType(raw: String?): String {
        val t = raw?.trim()?.lowercase().orEmpty()
        if (t.isBlank()) return "yc"
        if (t == "yc" || t == "fc" || t == "bz") return t
        return when (t) {
            "1", "yc", "original", "原创" -> "yc"
            "2", "fc", "cover", "翻唱" -> "fc"
            "3", "bz", "accompaniment", "伴奏" -> "bz"
            else -> "yc"
        }
    }

    /** 从 Song 还原 (songId, songType) */
    private fun splitToken(song: Song): Pair<String, String> {
        var songId = song.songId.trim()
        var songType = ""
        val token = song.token
        val idx = token.indexOf('|')
        if (idx >= 0) {
            if (songId.isBlank()) songId = token.substring(0, idx).trim()
            songType = token.substring(idx + 1).trim()
        }
        if (songType.isBlank()) songType = typeCache[song.songId] ?: ""
        if (songType.isBlank()) songType = "yc"
        return Pair(songId, songType)
    }
}
