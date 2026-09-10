package com.soundtrack.music.source

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object KuwoCrypto {
    private const val MUSIC_KEY = "ylzsxkwm"

    fun encryptQuery(query: String): String {
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(MUSIC_KEY.toByteArray(), "DES"))
        return Base64.getEncoder().encodeToString(cipher.doFinal(query.toByteArray()))
    }
}
