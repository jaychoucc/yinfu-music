package com.soundtrack.music.source

import java.security.MessageDigest

/**
 * JOOX 音源签名 / 哈希辅助。
 * 参考 ref_all/joox.py：
 *  - makesecret：对请求参数做 urlencode 后用 MD5(SALT + qs) 生成签名；
 *  - 第三方 gdstudio 接口签名：MD5( server_time[:9]|host|version|id ) 取末尾 8 位十六进制大写；
 *  - urlEncodeComponent / jsEncode：复刻 Python urllib.parse.quote(safe="-_.!~*'()") 的编码行为。
 */
object JooxCrypto {
    const val SALT = "Jo0x@t3Nc3nT"

    fun md5Hex(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    fun makesecret(params: Map<String, String>): String {
        val qs = params.entries.joinToString("&") { "${it.key}=${urlEncodeComponent(it.value)}" }
        return md5Hex(SALT + qs)
    }

    /** 复刻 Python quote(str, safe="-_.!~*'()") 的编码（空格 -> %20，保留 -_.!~*'()） */
    fun urlEncodeComponent(value: String): String {
        val sb = StringBuilder()
        for (c in value) {
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || (c in "-_.~")) {
                sb.append(c)
            } else {
                sb.append(String.format("%%%02X", c.code))
            }
        }
        return sb.toString()
    }

    /** gdstudio 接口使用的 encodeURIComponent 变体（连 !*'() 也编码） */
    fun jsEncode(value: String): String = urlEncodeComponent(value)

    /** "2026.08.01" -> "20260801" */
    fun normalizeVersion(version: String): String =
        version.split(".").joinToString("") { it.padStart(2, '0') }

    /** 计算 gdstudio 签名：MD5(rawSignText) 末尾 8 位十六进制大写（等价于 python struct.pack 后取 [-8:]） */
    fun signForGdStudio(rawSignText: String): String {
        val full = md5Hex(rawSignText)
        return if (full.length >= 32) full.substring(24).uppercase() else full.uppercase()
    }
}
