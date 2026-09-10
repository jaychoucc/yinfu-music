package com.soundtrack.music.player

import java.util.regex.Pattern

object LrcParser {
    data class Line(val timeMs: Long, val text: String)

    fun parse(raw: String): List<Line> {
        val lines = mutableListOf<Line>()
        val pattern = Pattern.compile("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\](.*)")
        raw.lineSequence().forEach { l ->
            val matcher = pattern.matcher(l)
            while (matcher.find()) {
                val min = matcher.group(1)?.toIntOrNull() ?: 0
                val sec = matcher.group(2)?.toIntOrNull() ?: 0
                val msRaw = matcher.group(3) ?: "0"
                val ms = msRaw.padEnd(3, '0').take(3).toIntOrNull() ?: 0
                val text = matcher.group(4)?.trim() ?: ""
                val timeMs = min * 60_000L + sec * 1000L + ms
                lines.add(Line(timeMs, text))
            }
        }
        lines.sortBy { it.timeMs }
        if (lines.isEmpty() && raw.isNotBlank()) {
            return raw.lines().map { Line(-1L, it.trim()) }
        }
        return lines
    }

    fun indexOf(lines: List<Line>, timeMs: Long): Int {
        if (lines.isEmpty()) return -1
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= timeMs) idx = i else break
        }
        return idx
    }
}
