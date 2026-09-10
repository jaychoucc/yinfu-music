package com.soundtrack.music.player

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.soundtrack.music.model.Song
import com.soundtrack.music.source.SourceResolver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

class PlayerRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("player_repo", Context.MODE_PRIVATE)

    val player: ExoPlayer = ExoPlayer.Builder(appContext).build().apply {
        addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) this@PlayerRepository.next()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }
            // 处理播放错误：MOOV 的 m3u8、不可达 URL、404、DRM 失败等都会触发；
            // 不实现会让 ExoPlayer 把异常直接抛回调用栈（主线程），造成播放闪退。
            override fun onPlayerError(error: PlaybackException) {
                _isPlaying.value = false
                _error.value = "播放失败: ${error.errorCodeName}${error.message?.let { " · $it" } ?: ""}"
                // 不自动跳歌；停在当前歌，等用户手动切。
            }
        })
    }

    private val _queue = mutableListOf<Song>()
    val queue: List<Song> get() = _queue.toList()
    private var currentIndex: Int = -1
    // 本轮播放中已尝试过但解析失败的索引，避免解析失败时 next() 无限轮询
    private val failedInThisRound = linkedSetOf<Int>()

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionJob: Job? = null

    init {
        restoreFromPrefs()
        startPositionLoop()
    }

    companion object {
        @Volatile
        private var instance: PlayerRepository? = null
        fun get(context: Context): PlayerRepository = instance ?: synchronized(this) {
            instance ?: PlayerRepository(context).also { instance = it }
        }
    }

    fun play(list: List<Song>, startIndex: Int) {
        _queue.clear()
        _queue.addAll(list)
        currentIndex = startIndex.coerceIn(0, _queue.size - 1)
        failedInThisRound.clear()
        _error.value = null
        startService()
        playAt(currentIndex)
        persistToPrefs()
    }

    private fun startService() {
        val intent = Intent(appContext, PlayerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
    }

    fun playAt(index: Int) {
        if (index < 0 || index >= _queue.size) return
        currentIndex = index
        val song = _queue[index]
        _currentSong.value = song
        if (!song.hasPlayUrl) {
            // 异步解析后播放；解析失败时先尝试用其他音源补搜同一首歌，
            // 全都拿不到才提示用户——绝不自动跳下一首（否则歌名会一路乱跳）
            scope.launch(Dispatchers.IO) {
                val url = resolvePlayableUrl(song)
                withContext(Dispatchers.Main) {
                    if (url != null) {
                        failedInThisRound.clear()  // 成功就重置本轮失败标记
                        setMediaAndPlay(url)
                    } else {
                        // 全部音源都拿不到播放地址：停在当前歌并提示，不自动跳歌
                        player.clearMediaItems()
                        player.stop()
                        _isPlaying.value = false
                        _error.value = "无法解析《${song.title}》的播放地址，请切换音源或稍后重试"
                    }
                }
            }
            return
        }
        failedInThisRound.clear()
        setMediaAndPlay(song.playUrl)
    }

    /**
     * 解析可播放地址：先试当前音源，失败则用其他已启用音源按"同名歌"补搜。
     * 这样点击歌曲时只锁定这首歌，不会跳到列表里的其他歌。
     */
    private suspend fun resolvePlayableUrl(song: Song): String? {
        // 1) 当前音源
        runCatching {
            withTimeoutOrNull(20_000) { SourceResolver.resolve(song.source)?.resolvePlayUrl(song) }
        }.getOrNull()?.let { return it }

        // 2) 其他已启用音源：搜索 "歌名 歌手"，找同名同人的替换播放地址
        val candidates = SourceResolver.registry.sources
            .filter { it.id != song.source }
        for (src in candidates) {
            val found = runCatching {
                withTimeoutOrNull(15_000) {
                    src.search("${song.title} ${song.artist}", 5)
                        .firstOrNull { it.title.equals(song.title, ignoreCase = true) && it.hasPlayUrl }
                        ?.playUrl
                }
            }.getOrNull()
            if (!found.isNullOrBlank() && found.startsWith("http")) {
                song.playUrl = found
                return found
            }
        }
        return null
    }

    private fun setMediaAndPlay(url: String) {
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.play()
        _duration.value = player.duration.coerceAtLeast(0L)
    }

    /** 自然播放结束时才调用：切下一首（解析失败不在这里处理） */
    fun next() {
        if (_queue.isEmpty()) return
        var idx = currentIndex
        for (i in 0 until _queue.size) {
            idx = (idx + 1) % _queue.size
            if (idx in failedInThisRound) continue
            playAt(idx)
            return
        }
        player.clearMediaItems()
        player.stop()
        _currentSong.value = null
    }

    /** 用户手动点下一首/上一首：清掉失败标记，允许重新尝试 */
    fun nextManual() {
        failedInThisRound.clear()
        next()
    }

    fun prevManual() {
        failedInThisRound.clear()
        if (_queue.isEmpty()) return
        var idx = currentIndex - 1
        if (idx < 0) idx = _queue.size - 1
        playAt(idx)
    }

    fun toggle() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun addToQueue(song: Song) {
        _queue.add(song)
        persistToPrefs()
    }

    fun clearQueue() {
        _queue.clear()
        currentIndex = -1
        player.clearMediaItems()
        persistToPrefs()
    }

    fun seekTo(ms: Long) {
        player.seekTo(ms.coerceIn(0L, player.duration.coerceAtLeast(0L)))
    }

    private fun startPositionLoop() {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (isActive) {
                _position.value = player.currentPosition.coerceAtLeast(0L)
                // 时长在 media ready 后才可用（prepare 阶段是 UNKNOWN），
                // 这里持续同步，避免进度条因 duration=0 而失准
                val d = player.duration
                if (d > 0) _duration.value = d
                delay(200)
            }
        }
    }

    fun persistToPrefs() {
        val json = JSONObject().apply {
            put("index", currentIndex)
            put("songs", JSONArray(_queue.map { songToJson(it) }))
        }
        prefs.edit().putString("queue", json.toString()).apply()
    }

    fun restoreFromPrefs() {
        val str = prefs.getString("queue", null) ?: return
        try {
            val json = JSONObject(str)
            currentIndex = json.optInt("index", -1)
            _queue.clear()
            val arr = json.optJSONArray("songs") ?: return
            for (i in 0 until arr.length()) {
                _queue.add(songFromJson(arr.getJSONObject(i)))
            }
            if (currentIndex in _queue.indices) {
                _currentSong.value = _queue[currentIndex]
            }
        } catch (_: Exception) {
        }
    }

    private fun songToJson(s: Song): JSONObject = JSONObject().apply {
        put("id", s.songId)
        put("source", s.source)
        put("title", s.title)
        put("artist", s.artist)
        put("album", s.album)
        put("duration", s.durationSec)
        put("cover", s.coverUrl)
        put("playUrl", s.playUrl)
        put("lrc", s.lrc)
        put("ext", s.ext)
        put("size", s.fileSizeBytes)
    }

    private fun songFromJson(j: JSONObject): Song = Song(
        songId = j.optString("id"),
        source = j.optString("source"),
        title = j.optString("title"),
        artist = j.optString("artist"),
        album = j.optString("album"),
        durationSec = j.optInt("duration"),
        coverUrl = j.optString("cover"),
        playUrl = j.optString("playUrl"),
        lrc = j.optString("lrc"),
        ext = j.optString("ext"),
        fileSizeBytes = j.optLong("size")
    )
}
