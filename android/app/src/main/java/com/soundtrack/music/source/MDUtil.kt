package com.soundtrack.music.source

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

/**
 * musicdl utils 的内联移植（原本在 utils.py / kugouutils / sodautils / base 里）。
 * 本文件为 D 批 7 个源（bilibili / yinyuedao / bodian / soda / moov / kugou / qianqian）
 * 共享，全部为包级函数，不直接依赖任何外部库（除 okhttp3 / org.json / JCE）。
 */

/** legalizestring：清掉零宽字符、BOM、音符符号，折叠空白。 */
fun legalizeString(s: String?): String {
    if (s == null) return ""
    return s.replace("\u200b", "")
        .replace("\ufeff", "")
        .replace("♪", " ")
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("&[a-zA-Z]+;"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

/** cleanlrc：去掉 id/by/hash 等元信息行与空行，并清除 kugou/波点的 <-?\d+,-\d+> 标记。 */
fun cleanLrc(s: String?): String {
    if (s.isNullOrBlank()) return ""
    val out = s.lineSequence()
        .filterNot { it.matches(Regex("^\\s*(id|by|hash|al|ar|ti|offset|total|publisher)\\s*:.*", RegexOption.IGNORE_CASE)) }
        .filterNot { it.isBlank() }
        .joinToString("\n")
    return out.replace(Regex("<-\\d+,-\\d+>"), "").trim()
}

fun md5Hex(input: String): String {
    val d = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
    return d.joinToString("") { "%02x".format(it) }
}

fun sha256Hex(input: String): String {
    val d = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return d.joinToString("") { "%02x".format(it) }
}

fun hmacSha256(key: String, data: String): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(data.toByteArray(Charsets.UTF_8))
}

fun b64encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
fun b64decode(s: String): ByteArray = Base64.getDecoder().decode(s)
fun b64decodeStr(s: String): String = String(Base64.getDecoder().decode(s), Charsets.UTF_8)
fun b64urlNoPad(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

/** 把 Map<String,String?> 拼成 query 串，生成完整 URL（值为 null 的跳过）。 */
fun buildUrl(base: String, params: Map<String, String?>): String {
    val sb = StringBuilder(base)
    var first = !base.contains('?')
    for ((k, v) in params) {
        if (v == null) continue
        sb.append(if (first) '?' else '&')
            .append(URLEncoder.encode(k, "UTF-8"))
            .append('=')
            .append(URLEncoder.encode(v, "UTF-8"))
        first = false
    }
    return sb.toString()
}

/** 由 Map 构造 okhttp3.Headers。 */
fun headersOf(map: Map<String, String>): okhttp3.Headers =
    okhttp3.Headers.headersOf(*map.flatMap { listOf(it.key, it.value) }.toTypedArray())

/**
 * safeextractfromdict：沿 keys 顺序在嵌套的 JSONObject / JSONArray 中取值。
 * key 为纯数字时按数组下标取值（用于形如 ['data', 0, 'url'] 的路径）。
 */
fun extractFromJson(json: Any?, keys: List<String>, default: Any? = null): Any? {
    var cur: Any? = json
    for (k in keys) {
        if (cur == null) return default
        cur = when (cur) {
            is JSONObject -> cur.opt(k)
            is JSONArray -> {
                val idx = k.toIntOrNull()
                if (idx != null && idx >= 0 && idx < cur.length()) cur.opt(idx) else null
            }
            else -> null
        }
    }
    return cur ?: default
}

/** searchdictbykey：递归收集 JSON 树中所有键等于 key 的值。 */
fun findByKey(json: Any?, key: String): MutableList<Any> {
    val out = mutableListOf<Any>()
    fun dfs(node: Any?) {
        when (node) {
            is JSONObject -> {
                val it = node.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val v = node.opt(k)
                    if (k == key && v != null) out.add(v)
                    dfs(v)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) dfs(node.opt(i))
            }
        }
    }
    dfs(json)
    return out
}

/** extractdurationsecondsfromlrc：扫描 [mm:ss.xx] 时间戳，返回最后一个时间戳的秒数。 */
fun extractDurationSecondsFromLrc(lrc: String?): Int {
    if (lrc.isNullOrBlank()) return 0
    val re = Regex("\\[(\\d+):(\\d+)(?:\\.(\\d+))?\\]")
    var last = 0
    for (m in re.findAll(lrc)) {
        val min = m.groupValues[1].toIntOrNull() ?: 0
        val sec = m.groupValues[2].toIntOrNull() ?: 0
        last = min * 60 + sec
    }
    return last
}

/** 把 JSON 里的歌手数组（[{name:...}] 或 {name:...}）拼接成逗号分隔字符串。 */
fun joinArtists(arr: Any?): String {
    if (arr == null) return ""
    val names = mutableListOf<String>()
    fun collect(v: Any?) {
        when (v) {
            is JSONArray -> for (i in 0 until v.length()) collect(v.opt(i))
            is JSONObject -> {
                v.optString("name").takeIf { it.isNotBlank() }?.let { names.add(it) }
            }
        }
    }
    collect(arr)
    return names.joinToString(", ")
}
