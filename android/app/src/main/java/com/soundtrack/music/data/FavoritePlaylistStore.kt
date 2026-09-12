package com.soundtrack.music.data

import android.content.Context
import com.soundtrack.music.model.Song
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地「我喜欢的音乐」歌单。
 *
 * 这是一张始终存在的默认歌单。每个条目保存搜索时的来源信息与播放时解析到的 URL，
 * 因而离线重启后仍可展示歌曲名称、歌手、封面、音源及已解析的播放地址。
 */
class FavoritePlaylistStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun contains(song: Song): Boolean = entries().any { it.key == keyOf(song) }

    /** @return true 表示收藏，false 表示已取消收藏。 */
    fun toggle(song: Song): Boolean {
        val current = entries().toMutableList()
        val index = current.indexOfFirst { it.key == keyOf(song) }
        if (index >= 0) {
            current.removeAt(index)
            save(current)
            return false
        }
        current.add(0, FavoriteTrack.from(song))
        save(current)
        return true
    }

    fun songs(): List<Song> = entries().map { it.toSong() }

    private fun entries(): List<FavoriteTrack> {
        val raw = prefs.getString(KEY_TRACKS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    FavoriteTrack.fromJson(array.getJSONObject(i))?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(items: List<FavoriteTrack>) {
        val array = JSONArray()
        items.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_TRACKS, array.toString()).apply()
    }

    private fun keyOf(song: Song) = "${song.source}:${song.songId}"

    private data class FavoriteTrack(
        val key: String,
        val songId: String,
        val source: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationSec: Int,
        val coverUrl: String,
        val sourceUrl: String,
        val resolvedPlayUrl: String,
        val lyric: String,
        val extension: String,
        val sizeBytes: Long,
        val bitrate: Int,
        val addedAt: Long
    ) {
        fun toSong() = Song(
            songId = songId, source = source, title = title, artist = artist, album = album,
            durationSec = durationSec, coverUrl = coverUrl, playUrl = resolvedPlayUrl,
            lrc = lyric, ext = extension, fileSizeBytes = sizeBytes, bitrate = bitrate,
            token = sourceUrl
        )

        fun toJson() = JSONObject().apply {
            put("key", key)
            put("songId", songId)
            put("source", source)
            put("title", title)
            put("artist", artist)
            put("album", album)
            put("durationSec", durationSec)
            put("coverUrl", coverUrl)
            put("sourceUrl", sourceUrl)
            put("resolvedPlayUrl", resolvedPlayUrl)
            put("lyric", lyric)
            put("extension", extension)
            put("sizeBytes", sizeBytes)
            put("bitrate", bitrate)
            put("addedAt", addedAt)
        }

        companion object {
            fun from(song: Song) = FavoriteTrack(
                key = "${song.source}:${song.songId}",
                songId = song.songId,
                source = song.source,
                title = song.title,
                artist = song.artist,
                album = song.album,
                durationSec = song.durationSec,
                coverUrl = song.coverUrl,
                // token is the source-specific detail/page address when a source exposes one.
                sourceUrl = song.token,
                resolvedPlayUrl = song.playUrl,
                lyric = song.lrc,
                extension = song.ext,
                sizeBytes = song.fileSizeBytes,
                bitrate = song.bitrate,
                addedAt = System.currentTimeMillis()
            )

            fun fromJson(j: JSONObject): FavoriteTrack? {
                val source = j.optString("source")
                val songId = j.optString("songId")
                if (source.isBlank() || songId.isBlank()) return null
                return FavoriteTrack(
                    key = j.optString("key", "${source}:${songId}"),
                    songId = songId,
                    source = source,
                    title = j.optString("title"),
                    artist = j.optString("artist"),
                    album = j.optString("album"),
                    durationSec = j.optInt("durationSec"),
                    coverUrl = j.optString("coverUrl"),
                    sourceUrl = j.optString("sourceUrl"),
                    resolvedPlayUrl = j.optString("resolvedPlayUrl"),
                    lyric = j.optString("lyric"),
                    extension = j.optString("extension"),
                    sizeBytes = j.optLong("sizeBytes"),
                    bitrate = j.optInt("bitrate"),
                    addedAt = j.optLong("addedAt")
                )
            }
        }
    }

    private companion object {
        const val PREFS_NAME = "favorite_playlist"
        const val KEY_TRACKS = "tracks"
    }
}
