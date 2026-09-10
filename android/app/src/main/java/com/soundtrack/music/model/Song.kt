package com.soundtrack.music.model

import java.io.Serializable

/**
 * 歌曲数据模型（与网页端 SSE 字段统一）
 */
/**
 * 说明：字段设计为可变（var），因为音源普遍采用「搜索时给元数据、解析播放链接时再回填
 * 真实 ext / 文件大小 / 时长 / 封面 / 专辑」的延迟富化流程（例如拿到 AudioLinkTester
 * 结果后写入 ext 与 fileSizeBytes）。所有 MusicSource 实现都依赖这一点。
 */
data class Song(
    var songId: String,
    var source: String,
    var title: String,
    var artist: String,
    var album: String,
    var durationSec: Int,
    var coverUrl: String,
    var playUrl: String,
    var lrc: String,
    var ext: String,
    var fileSizeBytes: Long,
    var bitrate: Int = 0,         // kbps，0 表示未知；>=900 视为高品质
    var token: String = ""        // 音源自定义暂存字段（详情页 URL / 解析所需的额外 id）
) : Serializable {
    val hasPlayUrl: Boolean get() = playUrl.isNotBlank() && playUrl.startsWith("http")

    /** 品质标签：flac/wav/ape/alac → 无损；mp3@>=320 → 高品；其余 → 标准 */
    val qualityLabel: String get() = when {
        ext.lowercase() in setOf("flac", "wav", "ape", "alac") -> "无损"
        bitrate >= 900 -> "无损"
        bitrate >= 320 -> "高品质"
        else -> "标准"
    }
}
