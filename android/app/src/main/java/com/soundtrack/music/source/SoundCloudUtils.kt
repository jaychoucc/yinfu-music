package com.soundtrack.music.source

import com.soundtrack.music.util.Net
import okhttp3.Request
import java.util.concurrent.atomic.AtomicReference

/**
 * SoundCloud 工具：client_id 的获取 + 流链接解析所需的小工具。
 * 对应 Python 端 ref_all/soundcloud.py 里的 _setclientid / _parsewithofficialapiv1。
 *
 * client_id 是调用 api-v2.soundcloud.com 的必需参数。Python 端先从 soundcloud.com 首页
 * 的 JS 里正则抠出 client_id，失败则用硬编码兜底。移动端没有 JSoup，但可以用 OkHttp 把
 * 首页与脚本内容抓下来做正则，逻辑完全照搬。
 */
object SoundCloudUtils {
    // Python 端 _setclientid 里的硬编码兜底 client_id
    private const val FALLBACK_CLIENT_ID = "9jZvetLfDs6An08euQgJ0lYlHkKdGFzV"

    private val cachedClientId = AtomicReference<String?>(null)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"
    )

    /** 获取 client_id：先抓首页脚本抠，失败用兜底。结果缓存。 */
    fun obtainClientId(): String {
        val cached = cachedClientId.get()
        if (!cached.isNullOrBlank()) return cached
        val id = scrapeClientId() ?: FALLBACK_CLIENT_ID
        cachedClientId.set(id)
        return id
    }

    private fun scrapeClientId(): String? {
        return try {
            val homeReq = Request.Builder().url("https://soundcloud.com/")
                .headers(okhttp3.Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray()))
                .build()
            val homeResp = Net.client().newCall(homeReq).execute()
            val html = homeResp.body?.string() ?: return null
            // 提取所有 <script src="...">
            val scriptRegex = Regex("""<script[^>]+src="([^"]+)"""")
            val scripts = scriptRegex.findAll(html).map { it.groupValues[1] }.toList().reversed()
            for (src in scripts) {
                val abs = if (src.startsWith("http")) src else "https://soundcloud.com$src"
                try {
                    val jsReq = Request.Builder().url(abs)
                        .headers(okhttp3.Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray()))
                        .build()
                    val jsResp = Net.client().newCall(jsReq).execute()
                    val js = jsResp.body?.string() ?: continue
                    val m = Regex("""client_id\s*:\s*"([0-9a-zA-Z]{32})"""").find(js)
                    if (m != null) return m.groupValues[1]
                } catch (_: Exception) {
                    // 单个脚本失败继续下一个
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
