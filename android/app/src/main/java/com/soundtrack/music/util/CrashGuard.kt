package com.soundtrack.music.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashGuard {
    fun install(context: Context) {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val file = File(context.getExternalFilesDir(null) ?: context.filesDir, "crash.log")
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                file.appendText("[$time] ${thread.name}\n${sw.toString()}\n\n")
            } catch (_: Exception) {
            }
            default?.uncaughtException(thread, throwable)
        }
    }
}
