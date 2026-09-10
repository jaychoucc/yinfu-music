package com.soundtrack.music.util

object Formatters {
    fun duration(seconds: Int): String {
        if (seconds <= 0) return "0:00"
        val m = seconds / 60
        val s = seconds % 60
        return "$m:${s.toString().padStart(2, '0')}"
    }

    fun safeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(120)
    }

    fun fileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var idx = 0
        while (size >= 1024 && idx < units.lastIndex) {
            size /= 1024
            idx++
        }
        return "%.1f %s".format(size, units[idx])
    }
}
