package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * B站音频源（BilibiliMusicClient 移植）。
 *
 * 接口说明：
 * - 搜索走官方 web 搜索接口 https://api.bilibili.com/x/web-interface/search/type ，
 *   该接口需要 WBI 签名（w_rid + wts）和 buvid3 Cookie，否则会返回 -412。
 *   本文件内联实现了 WBI 签名算法（mixinKeyEncTab + md5）。
 * - 解析播放地址需要两步：先 view 拿分 P 列表（cid），再 playurl 拿 dash 音频流 baseUrl。
 * - 已知限制：B 站搜索需要有效 buvid3 / WBI，若服务端风控（code -412）则搜索返回空；
 *   dash 音频为 m4a，HEAD 直测若被 Referer 拦截会降级为直接返回 baseUrl（仍是真实音频）。
 */
class BilibiliMusicSource : MusicSource {
    override val id = "bilibili"
    override val label = "B站音频"

    // WBI 签名所需的 mixin key 重排表（与 B 站官方一致）
    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 62, 63, 52, 34, 44, 20, 11, 57, 36
    )

    private val searchHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36 Edg/121.0.0.0",
        "Referer" to "https://www.bilibili.com/",
        "Accept" to "application/json, text/plain, */*"
    )

    private val playHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36 Edg/121.0.0.0",
        "Referer" to "https://www.bilibili.com/"
    )

    // 缓存 WBI 密钥与 buvid Cookie（多协程共享，简单 volatile 即可）
    @Volatile private var wbiImgKey: String? = null
    @Volatile private var wbiSubKey: String? = null
    @Volatile private var buvid3: String? = null
    @Volatile private var buvid4: String? = null

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            ensureWbi()
            val params = linkedMapOf(
                "__refresh__" to "true",
                "_extra" to "",
                "page" to "1",
                "page_size" to pageSize.toString(),
                "platform" to "pc",
                "highlight" to "1",
                "context" to "",
                "single_column" to "0",
                "keyword" to keyword,
                "category_id" to "",
                "search_type" to "video",
                "dynamic_offset" to "0",
                "preload" to "true",
                "com2co" to "true"
            )
            val url = signedUrl("https://api.bilibili.com/x/web-interface/search/type", params)
            val req = Request.Builder().url(url).headers(headersOf(searchHeaders)).get().build()
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val json = JSONObject(resp.body?.string().orEmpty())
            if (json.optInt("code", -1) != 0) return@withContext emptyList()
            val result = json.optJSONObject("data")?.optJSONArray("result") ?: return@withContext emptyList()

            val list = ArrayList<Song>()
            for (i in 0 until result.length()) {
                val item = result.optJSONObject(i) ?: continue
                val bvid = item.optString("bvid").ifBlank { continue }
                val title = item.optString("title").ifBlank { continue }
                val author = item.optString("author")
                val pic = item.optString("pic")
                val cover = if (pic.startsWith("http")) pic else "https:$pic"
                val duration = parseDuration(item.optString("duration"))
                // songId 编码 bvid 与 cid 之间用 '|' 分隔，解析时再拆分；cid 在 resolvePlayUrl 时重新拉 view 获取
                list.add(
                    Song(
                        songId = bvid,
                        source = id,
                        title = stripTags(title),
                        artist = author,
                        album = "",
                        durationSec = duration,
                        coverUrl = cover,
                        playUrl = "",
                        lrc = "",
                        ext = "m4a",
                        fileSizeBytes = 0L
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 确保 WBI 密钥与 buvid3 已就绪。 */
    private fun ensureWbi() {
        if (wbiImgKey != null && wbiSubKey != null && buvid3 != null) return
        try {
            // 1. 取 buvid3（搜索风控需要）
            if (buvid3 == null) {
                val spReq = Request.Builder()
                    .url("https://api.bilibili.com/x/frontend/finger/sp")
                    .header("User-Agent", searchHeaders["User-Agent"] ?: "")
                    .header("Referer", "https://www.bilibili.com/")
                    .get().build()
                Net.client().newCall(spReq).execute().use { sp ->
                    val spj = JSONObject(sp.body?.string().orEmpty())
                    val d = spj.optJSONObject("data") ?: JSONObject()
                    if (buvid3 == null) {
                        buvid3 = d.optString("b_3").ifBlank { null }
                        buvid4 = d.optString("b_4").ifBlank { null }
                    }
                }
            }
            // 2. 取 WBI 密钥
            if (wbiImgKey == null || wbiSubKey == null) {
                val navReq = Request.Builder()
                    .url("https://api.bilibili.com/x/web-interface/nav")
                    .header("User-Agent", searchHeaders["User-Agent"] ?: "")
                    .header("Referer", "https://www.bilibili.com/")
                    .get().build()
                Net.client().newCall(navReq).execute().use { nav ->
                    val navj = JSONObject(nav.body?.string().orEmpty())
                    val wbi = navj.optJSONObject("data")?.optJSONObject("wbi_img") ?: JSONObject()
                    val img = wbi.optString("img_url")
                    val sub = wbi.optString("sub_url")
                    wbiImgKey = img.substringBefore(".png").substringAfterLast("/")
                    wbiSubKey = sub.substringBefore(".png").substringAfterLast("/")
                }
            }
        } catch (_: Exception) {
            // 失败则后续请求可能返回 -412，由调用方返回空列表
        }
    }

    /** 用 img_key + sub_key 生成 32 位 mixinKey。 */
    private fun mixinKey(): String {
        val orig = (wbiImgKey ?: "") + (wbiSubKey ?: "")
        val sb = StringBuilder()
        for (i in 0 until 32) {
            val idx = MIXIN_KEY_ENC_TAB[i]
            if (idx < orig.length) sb.append(orig[idx])
        }
        return sb.toString()
    }

    /** 给参数加上 wts / w_rid 并返回完整带签 URL。 */
    private fun signedUrl(base: String, params: LinkedHashMap<String, String>): String {
        val wts = (System.currentTimeMillis() / 1000).toString()
        params["wts"] = wts
        // 按键排序
        val sorted = params.toSortedMap()
        val query = sorted.entries.joinToString("&") { (k, v) ->
            val fv = v.replace(Regex("[!'()*]"), "")
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(fv, "UTF-8")}"
        }
        val wRid = md5Hex(query + mixinKey())
        return "$base?$query&w_rid=$wRid"
    }

    private fun buildCookie(): String {
        val sb = StringBuilder()
        buvid3?.let { sb.append("buvid3=$it; ") }
        buvid4?.let { sb.append("buvid4=$it; ") }
        return sb.toString().trimEnd().trimEnd(';')
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        val bvid = song.songId
        withTimeoutOrNull(20_000) {
            val url = resolveBilibiliAudio(bvid)
            if (url != null) {
                song.playUrl = url
                song.ext = "m4a"
                url
            } else null
        }
    }

    private fun resolveBilibiliAudio(bvid: String): String? {
        return try {
            // 1) view 拿到分 P 列表，取第一个 cid
            val viewUrl = "https://api.bilibili.com/x/web-interface/view?bvid=$bvid"
            val viewReq = Request.Builder().url(viewUrl)
                .headers(headersOf(playHeaders))
                .header("Cookie", buildCookie())
                .get().build()
            val viewResp = Net.client().newCall(viewReq).execute()
            val viewJson = JSONObject(viewResp.body?.string().orEmpty())
            val pages = viewJson.optJSONObject("data")?.optJSONArray("pages") ?: return null
            if (pages.length() == 0) return null
            val first = pages.optJSONObject(0) ?: return null
            val cid = first.optString("cid").ifBlank { return null }

            // 2) playurl 拿 dash 音频流
            val puUrl = "https://api.bilibili.com/x/player/playurl?fnval=16&bvid=$bvid&cid=$cid"
            val puReq = Request.Builder().url(puUrl)
                .headers(headersOf(playHeaders))
                .header("Cookie", buildCookie())
                .get().build()
            val puResp = Net.client().newCall(puReq).execute()
            val puJson = JSONObject(puResp.body?.string().orEmpty())
            val dash = puJson.optJSONObject("data")?.optJSONObject("dash") ?: return null
            val audio = pickBestAudio(dash) ?: return null

            if (audio == null) return null
            val tested = AudioLinkTester.test(audio)
            if (tested != null) return tested.url
            // 降级：B 站 dash 直链本身是真实音频，HEAD 可能因 Referer 被拦，直接返回
            if (audio.startsWith("http")) audio else null
        } catch (_: Exception) {
            null
        }
    }

    /** 从 dash 中挑选带宽最高的音频 baseUrl（flac > dolby > audio）。 */
    private fun pickBestAudio(dash: JSONObject): String? {
        val candidates = ArrayList<JSONObject>()
        for (key in listOf("flac", "dolby", "audio")) {
            val node = dash.optJSONObject(key)
            if (node != null) {
                val arr = node.optJSONArray("audio")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i)
                        if (o != null) candidates.add(o)
                    }
                }
            }
        }
        if (candidates.isEmpty()) return null
        candidates.sortByDescending { it.optInt("bandwidth", 0) }
        val best = candidates[0]
        val base = best.optString("baseUrl").ifBlank { null }
            ?: run {
                val backup = best.opt("backupUrl")
                when (backup) {
                    is JSONArray -> if (backup.length() > 0) backup.optString(0) else null
                    is String -> backup
                    else -> null
                }
            }
        return base?.takeIf { it.startsWith("http") }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        // B 站无官方逐字歌词接口，返回 null（不伪造）
        null
    }

    private fun parseDuration(s: String?): Int {
        if (s.isNullOrBlank()) return 0
        val parts = s.split(":").map { it.toIntOrNull() ?: 0 }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> 0
        }
    }

    private fun stripTags(s: String): String {
        return s.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"").trim()
    }
}
