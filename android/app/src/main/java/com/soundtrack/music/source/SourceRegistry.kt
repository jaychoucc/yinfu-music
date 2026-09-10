package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.coroutineContext

class SourceRegistry(val sources: List<MusicSource>) {

    fun byId(id: String): MusicSource? = sources.find { it.id == id }

    suspend fun searchAll(
        keyword: String,
        ids: List<String>,
        onEach: (Song) -> Unit,
        onSourceDone: (id: String, count: Int, timedOut: Boolean) -> Unit
    ) = coroutineScope {
        val selected = sources.filter { it.id in ids }
        if (selected.isEmpty()) return@coroutineScope

        selected.forEach { source ->
            launch(Dispatchers.IO) {
                var count = 0
                var timedOut = false
                try {
                    withTimeout(10_000) {  // 单源搜索超时 10s：35s 会让整个搜索页卡死等一个慢源（「晚安」转圈根因）
                        val list = source.search(keyword, 20)
                        list.forEach {
                            ensureActive()
                            withContext(Dispatchers.Main) { onEach(it) }
                            count++
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    timedOut = true
                } catch (_: Exception) {
                }
                withContext(Dispatchers.Main) {
                    onSourceDone(source.id, count, timedOut)
                }
            }
        }
    }
}
