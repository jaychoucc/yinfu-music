package com.soundtrack.music.source

import com.soundtrack.music.model.Song

interface MusicSource {
    val id: String
    val label: String

    suspend fun search(keyword: String, pageSize: Int = 20): List<Song>
    suspend fun resolvePlayUrl(song: Song): String?
    suspend fun fetchLyric(song: Song): String?
}
