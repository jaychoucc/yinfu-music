package com.soundtrack.music.model

/**
 * songKey 唯一规则：一首歌在「全局曲库 / 歌单内去重 / 红心归属 / 重解析防风暴」中的唯一身份。
 *
 * 设计取舍：不做跨源合并 —— 不同音源的同名歌 songKey 不同，视为两首歌（符合 PRD 非目标）。
 */
object SongKeys {

    /**
     * 由 [Song] 生成 songKey。
     *
     * 优先 `source:songId`（稳定且唯一）；[Song.songId] 缺失时退化为
     * `source:title#artist`（保证不会因空 id 让所有歌折成同一个 key）。
     */
    fun of(song: Song): String {
        val id = song.songId.trim()
        return if (id.isNotEmpty()) {
            "${song.source}:$id"
        } else {
            "${song.source}:${song.title.trim()}#${song.artist.trim()}"
        }
    }

    /** 字段版：供不便构造 [Song] 的场景使用（语义与 [of] 完全一致）。 */
    fun of(songId: String, source: String, title: String = "", artist: String = ""): String =
        if (songId.trim().isNotEmpty()) {
            "$source:${songId.trim()}"
        } else {
            "$source:${title.trim()}#${artist.trim()}"
        }
}
