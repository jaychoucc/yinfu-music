package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Random

/**
 * 波点音乐源（BodianMusicClient 移植）：bodian.kuwo.cn。
 *
 * 接口说明：
 * - 搜索：GET https://bd-api.kuwo.cn/api/search/music/list （JSON，data.resultList）。
 * - 解析播放地址：走酷我第三方 mobi.s 接口（jiakong / tianbao 两种），
 *   传入 rid 即可拿到 data.url，无需登录/签名。
 * - 已知限制：官方 checkRight/audioUrl 需要 uid+token 登录态，本实现仅走第三方免登录路径，
 *   个别曲目可能拿不到地址；歌词需要 token，故返回 null（不伪造）。
 */
class BodianMusicSource : MusicSource {
    override val id = "bodian"
    override val label = "波点音乐"

    private val searchHeaders = mapOf(
        "user-agent" to "Dart/3.3 (dart:io)",
        "plat" to "win",
        "accept-encoding" to "gzip",
        "api-ver" to "application/json",
        "channel" to "W1",
        "brand" to "Windows 11 Pro for Workstations",
        "net" to "wifi",
        "content-type" to "application/json",
        "ver" to "1.1.5",
        "svrver" to "13"
    )

    private val rand = Random()

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val params = mapOf(
                "pn" to "0",
                "rn" to pageSize.toString(),
                "keyword" to keyword,
                "correct" to "1",
                "uid" to "-1",
                "token" to ""
            )
            val url = buildUrl("https://bd-api.kuwo.cn/api/search/music/list", params)
            val req = Request.Builder().url(url).headers(headersOf(searchHeaders)).get().build()
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val json = JSONObject(resp.body?.string().orEmpty())
            val arr = json.optJSONObject("data")?.optJSONArray("resultList") ?: return@withContext emptyList()

            val list = ArrayList<Song>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val rid = item.optString("id").ifBlank { continue }
                val title = item.optString("name").ifBlank { item.optString("songName") }.ifBlank { continue }
                val artist = item.optString("artist")
                val album = item.optString("album")
                val cover = item.optString("albumPic").ifBlank { item.optString("pic") }
                val duration = item.optString("duration").toFloatOrNull()?.toInt() ?: 0
                list.add(
                    Song(
                        songId = rid,
                        source = id,
                        title = legalizeString(title),
                        artist = legalizeString(artist),
                        album = legalizeString(album),
                        durationSec = duration,
                        coverUrl = cover,
                        playUrl = "",
                        lrc = "",
                        ext = "flac",
                        fileSizeBytes = 0L
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        if (song.hasPlayUrl) return@withContext song.playUrl
        withTimeoutOrNull(20_000) {
            val url = resolveBodian(song.songId)
            if (url != null) {
                song.playUrl = url
                song.ext = "flac"
                url
            } else null
        }
    }

    private fun resolveBodian(rid: String): String? {
        // 先试 jiakong 接口
        val jiakong = tryJiakong(rid)
        if (jiakong != null) return jiakong
        // 再试 tianbao 接口
        return tryTianbao(rid)
    }

    private fun tryJiakong(rid: String): String? {
        return try {
            val params = mapOf(
                "f" to "web",
                "source" to "kwplayercar_ar_6.0.0.9_B_jiakong_vh.apk",
                "type" to "convert_url_with_sign",
                "rid" to rid,
                "br" to "2000kflac",
                "user" to java.lang.Math.abs(rand.nextInt()).toString(),
                "loginUid" to java.lang.Math.abs(rand.nextInt()).toString()
            )
            val url = buildUrl("https://nmobi.kuwo.cn/mobi.s", params)
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36")
                .header("Accept", "*/*")
                .get().build()
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return null
            val json = JSONObject(resp.body?.string().orEmpty())
            val dl = json.optJSONObject("data")?.optString("url")?.takeIf { it.startsWith("http") } ?: return null
            AudioLinkTester.test(dl)?.url ?: dl
        } catch (_: Exception) {
            null
        }
    }

    private fun tryTianbao(rid: String): String? {
        return try {
            val params = mapOf(
                "f" to "web",
                "user" to (1000000 + rand.nextInt(9000000)).toString(),
                "source" to "kwplayerhd_ar_4.3.0.8_tianbao_T1A_qirui.apk",
                "type" to "convert_url_with_sign",
                "br" to "2000kflac",
                "rid" to rid
            )
            val url = buildUrl("https://mobi.kuwo.cn/mobi.s", params)
            val req = Request.Builder().url(url)
                .header("User-Agent", "Dart/2.19 (dart:io)")
                .header("plat", "ar")
                .header("channel", "aliopen")
                .get().build()
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return null
            val json = JSONObject(resp.body?.string().orEmpty())
            val dl = json.optJSONObject("data")?.optString("url")?.takeIf { it.startsWith("http") } ?: return null
            AudioLinkTester.test(dl)?.url ?: dl
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        // 波点歌词接口需要 token 登录态，本免登录实现不伪造歌词
        null
    }
}
