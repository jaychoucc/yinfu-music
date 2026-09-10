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
 * 汽水音乐源（SodaMusicClient 移植）：douyin qishui。
 *
 * 接口说明：
 * - 搜索：GET https://api.qishui.com/luna/search/track （带一堆设备参数，结果在 result_groups[0].data）。
 * - 解析播放地址（免登录 non-VIP 路径）：
 *   取 track_id 后 GET https://music.douyin.com/qishui/share/track?track_id=... ，
 *   从 HTML 中抽取 _ROUTER_DATA JSON，取 loaderData.track_page.audioWithLyricsOption.url 作为试听直链。
 * - 歌词：从同一 JSON 的 lyrics.sentences 拼出 LRC。
 * - 已知限制：luna 搜索接口是抖音系，通常需要 X-Gorgon/X-Khronos 等签名（依赖 JS/原生库），
 *   本实现未移植签名，搜索很可能返回空；但只要能拿到 track_id（例如其他途径），分享页解析路径可用。
 */
class SodaMusicSource : MusicSource {
    override val id = "soda"
    override val label = "汽水音乐"

    private val searchHeaders = mapOf(
        "User-Agent" to "LunaPC/3.4.0(388267242)",
        "Content-Type" to "application/json; charset=utf-8",
        "X-Luna-Background-Type" to "foreground",
        "X-Luna-Is-Background-Req" to "0",
        "X-Luna-Is-Local-User" to "1",
        "Accept-Encoding" to "gzip, deflate"
    )

    private val shareHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    /** 构造 luna 搜索参数（固定设备参数 + 动态 _rticket）。 */
    private fun searchParams(keyword: String, cursor: Int, count: Int): Map<String, String> {
        val map = linkedMapOf<String, String>(
            "device_platform" to "android",
            "os" to "android",
            "ssmix" to "a",
            "cdid" to "46556f98-1720-4248-83da-62b74b60b46a",
            "channel" to "xiaomi_8478_64",
            "aid" to "386088",
            "app_name" to "luna",
            "version_code" to "100198030",
            "version_name" to "19.8.0",
            "manifest_version_code" to "100198030",
            "update_version_code" to "100198030",
            "resolution" to "1080*1920",
            "dpi" to "480",
            "device_type" to "ABR-AL80",
            "device_brand" to "HUAWEI",
            "language" to "zh",
            "os_api" to "35",
            "os_version" to "15",
            "ac" to "wifi",
            "device_model" to "ABR-AL80",
            "save_power" to "0",
            "font_size" to "1.00",
            "luna_first_launch_apk_type" to "normal_apk",
            "diversion_channel_name" to "xiaomi_8478_64",
            "is_car_play" to "0",
            "battery" to "0.99",
            "network_speed" to "10156",
            "hybrid_version_code" to "100198030",
            "tz_name" to "Asia/Shanghai",
            "tz_offset" to "28800",
            "luna_register_time" to "1784311292",
            "diversion_category_level_two" to "Xiaomi%E5%95%86%E5%BA%97-%E8%87%AA%E7%84%B6",
            "package" to "com.luna.music",
            "charge" to "0",
            "luna_apk_type" to "normal_apk",
            "output_device_type" to "Phone",
            "volume" to "1.00",
            "brightness" to "0.08",
            "need_personal_recommend" to "1",
            "is_teen_mode" to "0",
            "sim_region" to "cn",
            "diversion_category_level_one" to "%E5%8E%82%E5%95%86%E5%95%96%E5%BA%97-%E8%87%AA%E7%84%B6",
            "android_device_type" to "default",
            "iid" to "2204957404569386",
            "device_id" to "2204957404565290",
            "q" to keyword,
            "cursor" to cursor.toString(),
            "count" to count.toString()
        )
        map["_rticket"] = (System.currentTimeMillis()).toString()
        return map
    }

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val params = searchParams(keyword, 0, pageSize)
            val url = buildUrl("https://api.qishui.com/luna/search/track", params)
            val req = Request.Builder().url(url).headers(headersOf(searchHeaders)).get().build()
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val json = JSONObject(resp.body?.string().orEmpty())
            val groups = json.optJSONArray("result_groups") ?: return@withContext emptyList()
            if (groups.length() == 0) return@withContext emptyList()
            val data = groups.optJSONObject(0)?.optJSONArray("data") ?: return@withContext emptyList()

            val list = ArrayList<Song>()
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val track = extractFromJson(item, listOf("entity", "track")) as? JSONObject ?: continue
                val trackId = track.optString("id").ifBlank { continue }
                val title = track.optString("name").ifBlank { continue }
                val artist = joinArtists(track.opt("artists"))
                val album = extractFromJson(track, listOf("album", "name")) as? String ?: ""
                val cover = buildCover(track)
                list.add(
                    Song(
                        songId = trackId,
                        source = id,
                        title = legalizeString(title),
                        artist = legalizeString(artist),
                        album = legalizeString(album),
                        durationSec = 0,
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

    private fun buildCover(track: JSONObject): String {
        val u0 = extractFromJson(track, listOf("album", "url_cover", "urls", "0")) as? String ?: ""
        val u1 = extractFromJson(track, listOf("album", "url_cover", "uri")) as? String ?: ""
        return (u0 + u1 + "~c5_500x500.jpg")
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        withTimeoutOrNull(20_000) {
            val info = resolveSoda(song.songId)
            if (info != null) {
                song.playUrl = info.url
                song.ext = info.ext
                song.durationSec = info.duration
                song.coverUrl = info.cover.ifBlank { song.coverUrl }
                song.lrc = info.lyric.ifBlank { song.lrc }
                info.url
            } else null
        }
    }

    private data class SodaInfo(val url: String, val ext: String, val duration: Int, val cover: String, val lyric: String)

    private fun resolveSoda(trackId: String): SodaInfo? {
        return try {
            val url = "https://music.douyin.com/qishui/share/track?track_id=$trackId"
            val req = Request.Builder().url(url).headers(headersOf(shareHeaders)).get().build()
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return null
            val html = resp.body?.string().orEmpty()
            val jsonStr = extractRouterData(html) ?: return null
            val meta = JSONObject(jsonStr)
            val audioOption = extractFromJson(meta, listOf("loaderData", "track_page", "audioWithLyricsOption")) as? JSONObject
            var auditionUrl = audioOption?.optString("url")?.replace("\\u002F", "/")?.replace("%7C", "|")?.replace("%3D", "=") ?: ""
            if (!auditionUrl.startsWith("http")) return null
            val tested = AudioLinkTester.test(auditionUrl)
            val finalUrl = tested?.url ?: auditionUrl
            val ext = tested?.ext ?: "m4a"

            val trackInfoList = findByKey(meta, "trackInfo")
            val trackInfo = if (trackInfoList.isNotEmpty()) trackInfoList[0] as? JSONObject else null
            val duration = (trackInfo?.optDouble("duration", 0.0) ?: 0.0).toInt() / 1000
            val name = trackInfo?.optString("name") ?: ""
            val artist = joinArtists(trackInfo?.opt("artists"))
            val album = extractFromJson(trackInfo, listOf("album", "name")) as? String ?: ""
            val cover = buildCover(trackInfo ?: JSONObject())

            val lyric = buildLyricFromSentences(audioOption?.optJSONObject("lyrics")?.optJSONArray("sentences"))
            SodaInfo(finalUrl, ext, duration, cover, lyric)
        } catch (_: Exception) {
            null
        }
    }

    /** 从 HTML 中抽取 _ROUTER_DATA = {...}; 的 JSON 串。 */
    private fun extractRouterData(html: String): String? {
        val m = Regex("_ROUTER_DATA\\s*=\\s*(\\{.*\\})\\s*;", setOf(RegexOption.DOT_MATCHES_ALL)).find(html)
        return m?.groupValues?.getOrNull(1)
    }

    /** 把 sentences(JSONArray of {startMs, words:[{text}]}) 拼成 LRC。 */
    private fun buildLyricFromSentences(sentences: JSONArray?): String {
        if (sentences == null) return ""
        val sb = StringBuilder()
        for (i in 0 until sentences.length()) {
            val s = sentences.optJSONObject(i) ?: continue
            val startMs = s.optInt("startMs", 0)
            val min = startMs / 60000
            val sec = (startMs % 60000) / 1000
            val ms = startMs % 1000
            val words = s.optJSONArray("words")
            val text = StringBuilder()
            if (words != null) {
                for (j in 0 until words.length()) {
                    val w = words.optJSONObject(j) ?: continue
                    text.append(w.optString("text"))
                }
            }
            sb.append(String.format("[%02d:%02d.%03d]%s\n", min, sec, ms, text.toString()))
        }
        return cleanLrc(sb.toString())
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.lrc.isNotBlank()) return@withContext song.lrc
        // 歌词已在 resolvePlayUrl 时一并解析
        null
    }
}
