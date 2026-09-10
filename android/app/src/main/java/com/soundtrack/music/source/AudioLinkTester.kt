package com.soundtrack.music.source

import com.soundtrack.music.util.Net
import okhttp3.Request

object AudioLinkTester {
    private val VALID_EXTS = setOf("mp3", "flac", "wav", "m4a", "aac", "ape", "ogg", "mp4")

    data class Result(
        val url: String,
        val ext: String,
        val contentType: String,
        val contentLength: Long
    )

    fun test(url: String): Result? {
        if (!url.startsWith("http")) return null
        val req = Request.Builder().url(url).head().build()
        return try {
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return null
            val ct = resp.header("Content-Type") ?: ""
            val cl = resp.header("Content-Length")?.toLongOrNull() ?: 0L
            val ext = guessExt(url, ct)
            if (ext !in VALID_EXTS) return null
            Result(url, ext, ct, cl)
        } catch (_: Exception) {
            null
        }
    }

    private fun guessExt(url: String, contentType: String): String {
        val fromUrl = url.substringBefore('?').substringAfterLast('.', "")
        if (fromUrl in VALID_EXTS) return fromUrl
        return when {
            contentType.contains("mpeg") -> "mp3"
            contentType.contains("flac") -> "flac"
            contentType.contains("wav") -> "wav"
            contentType.contains("mp4") -> "m4a"
            contentType.contains("aac") -> "aac"
            contentType.contains("ogg") -> "ogg"
            contentType.contains("ape") -> "ape"
            else -> "mp3"
        }
    }
}
