package com.soundtrack.music.source

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * JioSaavn 音源加解密辅助。
 * 参考 ref_all/jiosaavn.py 的 _decrypturl：
 *   DES/ECB 解密 base64(encrypted_media_url)，密钥固定为 "38346591"，
 *   解出后用 https 替换 http。
 */
object JiosaavnCrypto {
    private const val KEY = "38346591"

    fun decryptUrl(encUrl: String): String {
        if (encUrl.isBlank()) return ""
        return try {
            val keyBytes = KEY.toByteArray(Charsets.UTF_8)
            // JioSaavn 使用 DES/ECB + PKCS5 填充
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "DES"))
            val decoded = Base64.getDecoder().decode(encUrl.trim())
            val out = cipher.doFinal(decoded)
            String(out, Charsets.UTF_8).trim().replace("http://", "https://")
        } catch (e: Exception) {
            ""
        }
    }
}
