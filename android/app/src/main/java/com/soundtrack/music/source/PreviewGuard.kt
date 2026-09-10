package com.soundtrack.music.source

import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request

/**
 * 试听片段守护：用 Content-Length / Content-Range 总大小 ÷ 歌曲元数据时长估算码率，
 * 明显低于合理码率（< 48kbps）即判为试听片段。
 *
 * 背景：sync-word 密度判定会被「完整有效的 30s 试听」骗过（haitangw 返回的网易 IoT
 * 通道试听 480KB 全是有效 MPEG 帧，sync=1087 > 旧阈值 600），导致用户听 30 秒戛然而止。
 *
 * 判定规则（拿到 totalBytes 后）：
 *  - expectedDurationSec >= 60：隐含码率 = totalBytes*8/expectedDurationSec < 48_000 → 试听
 *    （实测：混帐 269s 的 30s 试听 460KB → 13.7kbps → 判中；128k 完整版 4.3MB → 128kbps → 放行）
 *  - 时长未知或 < 60s：totalBytes < 600_000（≈37s@128kbps）→ 试听
 *    （30s@128k 试听=480KB 判中；45s@128k 完整=720KB 放行）
 *  - 拿不到 totalBytes（206 无 Content-Range / chunked）：退回旧 sync-word 密度判定
 *
 * 任何异常一律保守判为试听（true），宁可走跨源回退也不把试听放给 ExoPlayer。
 */
object PreviewGuard {
    /** totalBytes 未知 */
    private const val UNKNOWN = -1L

    suspend fun isPreview(url: String, expectedDurationSec: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(2_500L) {
                val req = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=0-262143")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                Net.client().newCall(req).execute().use { resp ->
                    val totalBytes = resolveTotalBytes(resp)
                    if (totalBytes > 0) {
                        sizeBasedVerdict(totalBytes, expectedDurationSec)
                    } else {
                        // 退路：读 body 数 sync word（旧逻辑，处理无 Content-Range 的响应）
                        syncWordVerdict(resp)
                    }
                }
            } ?: true
        } catch (_: Exception) {
            true
        }
    }

    /** 优先 Content-Range（"bytes 0-N/TOTAL" → TOTAL）；200 响应用 Content-Length；拿不到返回 UNKNOWN */
    private fun resolveTotalBytes(resp: okhttp3.Response): Long {
        val cr = resp.header("Content-Range")
        if (!cr.isNullOrBlank()) {
            // bytes 0-262143/460000 → 取最后一段
            val total = cr.substringAfterLast('/').trim().toLongOrNull()
            if (total != null && total > 0) return total
        }
        return resp.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 } ?: UNKNOWN
    }

    private fun sizeBasedVerdict(totalBytes: Long, expectedDurationSec: Int): Boolean {
        return if (expectedDurationSec >= 60) {
            val impliedBps = totalBytes * 8 / expectedDurationSec
            impliedBps < 48_000L
        } else {
            totalBytes < 600_000L
        }
    }

    private fun syncWordVerdict(resp: okhttp3.Response): Boolean {
        val body = resp.body ?: return true
        val buf = ByteArray(262_144)
        var off = 0
        body.byteStream().use { ins ->
            while (off < buf.size) {
                val n = ins.read(buf, off, buf.size - off)
                if (n <= 0) break
                off += n
            }
        }
        if (off == 0) return true
        var syncCount = 0
        var i = 0
        val limit = off - 1
        while (i < limit) {
            if ((buf[i].toInt() and 0xFF) == 0xFF && (buf[i + 1].toInt() and 0xE0) == 0xE0) {
                syncCount++
                i += 2
            } else {
                i++
            }
        }
        return off < 65_536 || syncCount < 600
    }
}
