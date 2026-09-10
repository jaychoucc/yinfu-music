package com.soundtrack.music.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.soundtrack.music.model.Song
import com.soundtrack.music.util.Formatters
import com.soundtrack.music.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class DownloadManager(private val context: Context) {

    suspend fun download(song: Song): Uri? = withContext(Dispatchers.IO) {
        if (!song.hasPlayUrl) return@withContext null
        val ext = song.ext.ifBlank { "mp3" }
        val baseName = "${Formatters.safeFileName(song.title)} - ${Formatters.safeFileName(song.artist)}"
        val fileName = "$baseName.$ext"

        try {
            val req = Request.Builder().url(song.playUrl).get().build()
            val resp = Net.client().newCall(req).execute()
            if (!resp.isSuccessful) return@withContext null

            val body = resp.body ?: return@withContext null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Audio.Media.MIME_TYPE, mimeForExt(ext))
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Soundtrack")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                    put(MediaStore.Audio.Media.ARTIST, song.artist)
                    put(MediaStore.Audio.Media.TITLE, song.title)
                    put(MediaStore.Audio.Media.ALBUM, song.album)
                }
                val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext null
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    body.byteStream().use { it.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                uri
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Soundtrack").apply { mkdirs() }
                val file = File(dir, fileName)
                FileOutputStream(file).use { out ->
                    body.byteStream().use { it.copyTo(out) }
                }
                // 触发媒体库扫描
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DATA, file.absolutePath)
                    put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Audio.Media.MIME_TYPE, mimeForExt(ext))
                    put(MediaStore.Audio.Media.ARTIST, song.artist)
                    put(MediaStore.Audio.Media.TITLE, song.title)
                    put(MediaStore.Audio.Media.ALBUM, song.album)
                }
                context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                Uri.fromFile(file)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun queryDownloads(): List<DownloadItem> {
        val list = mutableListOf<DownloadItem>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DATA
        )
        val (selection, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?" to arrayOf("%Soundtrack%")
        } else {
            "${MediaStore.Audio.Media.DATA} LIKE ?" to arrayOf("%Soundtrack%")
        }
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection, selection, args,
            "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val timeIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val artistIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            while (cursor.moveToNext()) {
                list.add(
                    DownloadItem(
                        uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idIdx).toString()),
                        name = cursor.getString(nameIdx) ?: "",
                        artist = cursor.getString(artistIdx) ?: "",
                        added = cursor.getLong(timeIdx)
                    )
                )
            }
        }
        return list
    }

    private fun mimeForExt(ext: String): String = when (ext.lowercase()) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "m4a", "mp4" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg" -> "audio/ogg"
        else -> "audio/*"
    }

    data class DownloadItem(val uri: Uri, val name: String, val artist: String, val added: Long)
}
