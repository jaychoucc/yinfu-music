package com.soundtrack.music.player

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.soundtrack.music.model.Song
import com.soundtrack.music.source.PreviewGuard
import com.soundtrack.music.source.SourceResolver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

class PlayerRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("player_repo", Context.MODE_PRIVATE)

    /**
     * 主线程 Handler：所有「由播放器回调触发」的操作都必须 post 出去再执行。
     *
     * ExoPlayer 的 onPlaybackStateChanged / onPlayerError 是在播放器内部锁与回调栈里调用的，
     * 在回调里同步执行 stop() / clearMediaItems() / setMediaItem() / prepare() 属于重入，
     * Media3 会在随后的状态同步时抛 IllegalStateException —— 表现为「一首歌播完就闪退」。
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 注意：监听器不能在 `player` 的初始化表达式里注册 —— 那条 lambda 会去读
     * `this@PlayerRepository.player`（此时属性还没赋值完成），编译器直接报
     * "Variable 'player' must be initialized"。故放到 init 里统一挂载。
     */
    val player: ExoPlayer = ExoPlayer.Builder(appContext).build()

    private val _queue = mutableListOf<Song>()
    val queue: List<Song> get() = _queue.toList()
    private var currentIndex: Int = -1
    // 本轮播放中已尝试过但解析失败的索引，避免解析失败时 next() 无限轮询
    private val failedInThisRound = linkedSetOf<Int>()
    /**
     * 本轮播放中「已经完整播过」的索引。
     *
     * 之前只靠 failedInThisRound 判定，而它只 clear 从不 add（见缺陷 D），
     * 于是单曲队列（首页「新歌速递」点进来 size == 1）播完后
     * idx = (idx + 1) % 1 == 0，永远回到第 0 首又调 playAt(0)，
     * 播完再触发 STATE_ENDED —— 无限自转并最终崩在重入上。
     * 这里显式记录已播过的歌，队列跑完就真正停下来。
     */
    private val playedInThisRound = linkedSetOf<Int>()

    /**
     * 当前正在进行的播放地址解析任务。
     * 旧实现每次切歌都新起一个协程且不取消旧的，多个协程并发跑，
     * 先完成的旧结果会覆盖后点的新歌 —— 表现为「切了没反应 / 要暂停再切才好」。
     */
    private var resolveJob: Job? = null
    /** 每次发起新的切歌请求自增，用于丢弃已过期的解析结果。 */
    private var resolveToken: Int = 0
    /**
     * 本轮 resolvePlayableUrl 是否因试听守护跳过过候选 URL。
     * 用于在最终失败时给出「只有试听」的诚实提示，而不是笼统的「解析失败」。
     */
    private var skippedPreviewInResolve = false

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    /** 正在解析播放地址（供 UI 显示"缓冲/解析中"，让点击有即时反馈） */
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

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
        attachPlayerListener()
        startPositionLoop()
    }

    private fun attachPlayerListener() {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    // 切出回调栈：等本次事件分发结束后再决定是否切下一首
                    mainHandler.post {
                        // 二次确认仍在 ENDED，避免状态抖动造成重复触发
                        val now = runCatching { player.playbackState }
                            .getOrDefault(Player.STATE_IDLE)
                        if (now == Player.STATE_ENDED) next()
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }

            // 处理播放错误：MOOV 的 m3u8、不可达 URL、404、DRM 失败等都会触发；
            // 不实现会让 ExoPlayer 把异常直接抛回调用栈（主线程），造成播放闪退。
            override fun onPlayerError(error: PlaybackException) {
                _isPlaying.value = false
                _loading.value = false
                _error.value = "播放失败: ${error.errorCodeName}${error.message?.let { " · $it" } ?: ""}"
                // 不自动跳歌；停在当前歌，等用户手动切。
            }
        })
    }

    companion object {
        @Volatile
        private var instance: PlayerRepository? = null
        fun get(context: Context): PlayerRepository = instance ?: synchronized(this) {
            instance ?: PlayerRepository(context).also { instance = it }
        }

        /** 单个音源解析超时 */
        private const val RESOLVE_TIMEOUT_MS = 15_000L
        /** 跨源补搜的单源超时：海外源(joox/apple)单源需要更多 */
        private const val FALLBACK_PER_SOURCE_MS = 8_000L
        /**
         * 跨源补搜总预算。
         * 旧实现会顺序遍历全部 57 个音源（每个 15 秒超时），
         * 一旦主音源失败就要几十分钟才返回，用户只看到「点了没反应」。
         * 链路更长（CN 主力 + joox + netease + apple）后这里上调到 24s。
         */
        private const val FALLBACK_BUDGET_MS = 24_000L
        /**
         * 跨源补搜音源列表，按可靠性 + 地理覆盖排序：
         *  CN 主力（migu/kuwo/qq/kugou/myfreemp3）+ HK/SEA 兜底（joox）
         *  + netease（兜底，可能 30s 片段会被守护拒）+ iTunes + amp-api（apple, 全球目录）
         */
        private val FALLBACK_SOURCES = listOf(
            "migu", "kuwo", "qq", "kugou", "myfreemp3",
            "joox",
            "netease",
            "apple",
        )
    }

    fun play(list: List<Song>, startIndex: Int) {
        _queue.clear()
        _queue.addAll(list)
        currentIndex = startIndex.coerceIn(0, (_queue.size - 1).coerceAtLeast(0))
        failedInThisRound.clear()
        playedInThisRound.clear()
        _error.value = null
        startService()
        playAt(currentIndex)
        persistToPrefs()
    }

    private fun startService() {
        val intent = Intent(appContext, PlayerService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        }
    }

    /**
     * 切到队列第 index 首。
     *
     * 关键修复：
     *  1. 先取消上一个解析任务，避免旧结果覆盖新歌；
     *  2. 用 token 校验结果是否仍然对应当前请求；
     *  3. 需要解析时立即停掉上一首的播放，让"点了就有反应"（旧行为会继续放上一首直到解析完成）；
     *  4. 进入新歌前复位 position / duration，避免进度条残留上一首的进度。
     */
    fun playAt(index: Int) {
        if (index < 0 || index >= _queue.size) return

        resolveJob?.cancel()
        resolveJob = null
        val token = ++resolveToken

        currentIndex = index
        val song = _queue[index]
        _currentSong.value = song
        _error.value = null
        // 切歌瞬间复位进度：否则 seekBar / 迷你条会短暂显示上一首的时长与位置
        _duration.value = 0L
        _position.value = 0L

        if (song.hasPlayUrl) {
            failedInThisRound.clear()
            _loading.value = false
            setMediaAndPlay(song.playUrl)
            return
        }

        // 需要现解析：先停掉当前播放，避免"还在放上一首"的错觉
        _loading.value = true
        stopPlayback()

        resolveJob = scope.launch(Dispatchers.IO) {
            val url = runCatching { resolvePlayableUrl(song) }.getOrNull()
            withContext(Dispatchers.Main) {
                // 已被更新的切歌请求取代，或队列已被清空 —— 丢弃这次结果
                if (token != resolveToken || currentIndex != index) return@withContext
                _loading.value = false
                if (!url.isNullOrBlank() && url.startsWith("http")) {
                    failedInThisRound.clear()
                    setMediaAndPlay(url)
                } else {
                    // 记入本轮失败集合，next() 才能熔断（旧代码只 clear 不 add，等于没有熔断）
                    failedInThisRound.add(index)
                    // 多首队列里某一首解析不出来时，跳过它继续放下一首，
                    // 而不是就地中断整轮播放（用户得手动点下一首才能继续）。
                    // failedInThisRound 单调增长 + findNextIndex 最多遍历 _queue.size 次，
                    // 因此下一首再失败也只会继续往前走，跑完一定返回 -1，不会死循环。
                    val nextIdx = findNextIndex(failedInThisRound + playedInThisRound)
                    if (nextIdx >= 0) {
                        playAt(nextIdx)
                        // playAt 开头会把 _error 清成 null，所以提示必须在它之后设置
                        _error.value = if (skippedPreviewInResolve) "《${song.title}》仅找到试听片段（已过滤），已跳过" else "《${song.title}》解析失败，已跳过"
                    } else {
                        // 单曲队列（保持就地停止，不自动重播）或所有候选都失败：真正停下来
                        stopPlayback()
                        _error.value = if (skippedPreviewInResolve) "《${song.title}》暂无完整免费音源（试听片段已过滤）" else "无法解析《${song.title}》的播放地址，请切换音源或稍后重试"
                    }
                }
            }
        }
    }

    /**
     * 解析可播放地址：先试当前音源，失败再用有限的几个主力音源按"同名歌"补搜。
     *
     * 旧实现会顺序遍历 registry 里的全部 57 个音源，每个 15 秒超时，
     * 主音源失败时整体耗时可达数十分钟，UI 完全无响应 —— 这是「切歌没反应」的主因之一。
     *
     * 另一个旧缺陷：主音源分支拿到 url 后直接 return，没有写回 song.playUrl，
     * 导致 hasPlayUrl 永远为 false，每次播完/切回都要重解析一遍（15s 主源 + 12s 回退预算）。
     * 这里两个分支都回填。
     */
    private suspend fun resolvePlayableUrl(song: Song): String? {
        skippedPreviewInResolve = false
        // 1) 当前音源
        val mainUrl = runCatching {
            withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                SourceResolver.resolve(song.source)?.resolvePlayUrl(song)
            }
        }.getOrNull()
        if (!mainUrl.isNullOrBlank() && mainUrl.startsWith("http")) {
            // 全源守护：主源返回的 URL 也可能是 30s 试听（实测 migu 之外的链路发生过），
            // 被守护拦下则记标记并继续走跨源回退，而不是直接放给 ExoPlayer。
            if (PreviewGuard.isPreview(mainUrl, song.durationSec)) {
                skippedPreviewInResolve = true
            } else {
                // 回填：让这首歌下次直接命中 hasPlayUrl 分支，不再重复解析
                song.playUrl = mainUrl
                return mainUrl
            }
        }

        // 2) 主力音源补搜：有总时间预算，且每个音源独立超时
        val deadline = System.currentTimeMillis() + FALLBACK_BUDGET_MS
        for (srcId in FALLBACK_SOURCES) {
            if (srcId == song.source) continue
            val src = SourceResolver.resolve(srcId) ?: continue
            val now = System.currentTimeMillis()
            if (now >= deadline) break
            val perSource = (deadline - now).coerceAtMost(FALLBACK_PER_SOURCE_MS)
            val foundSong = runCatching {
                withTimeoutOrNull(perSource) {
                    val matches = src.search("${song.title} ${song.artist}", 5)
                        .filter { it.title.equals(song.title, ignoreCase = true) }
                    if (matches.isEmpty()) return@withTimeoutOrNull null
                    val first = matches.first()
                    // 关键：search 返回的 Song.playUrl 通常是空（URL 由 resolvePlayUrl 单独拿），
                    // 不能用 it.hasPlayUrl 过滤 —— 那样 fallback 会 100% 返回 null。
                    val url = src.resolvePlayUrl(first)
                    if (!url.isNullOrBlank() && url.startsWith("http")) {
                        first.playUrl = url
                        first
                    } else null
                }
            }.getOrNull()
            val found = foundSong?.playUrl
            if (!found.isNullOrBlank() && found.startsWith("http")) {
                // 全源守护：不再只盯 netease —— 任何源的命中 URL 都可能是 9~30s 试听
                // （实测拦下 haitangw 30s / kuwo 11s / apple 30s 试听），拦下则继续找下一源。
                val expectedDur = maxOf(song.durationSec, foundSong.durationSec)
                if (PreviewGuard.isPreview(found, expectedDur)) {
                    skippedPreviewInResolve = true
                    continue
                }
                song.playUrl = found
                // 补搜命中的歌往往带更完整的元数据，顺手富化回来
                if (foundSong.ext.isNotBlank()) song.ext = foundSong.ext
                if (foundSong.durationSec > 0) song.durationSec = foundSong.durationSec
                if (song.coverUrl.isBlank() && foundSong.coverUrl.isNotBlank()) {
                    song.coverUrl = foundSong.coverUrl
                }
                return found
            }
        }
        return null
    }

    /**
     * 停止并清空当前播放。Media3 里这是切换到另一首前最稳妥的复位动作：
     * 直接 setMediaItem 而不 stop，在播放器处于缓冲/错误态时可能不生效。
     */
    private fun stopPlayback() {
        runCatching {
            player.stop()
            player.clearMediaItems()
        }
        _isPlaying.value = false
    }

    private fun setMediaAndPlay(url: String) {
        runCatching {
            // 先复位再装载，保证切歌一定生效
            player.stop()
            player.clearMediaItems()
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.play()
            applyDurationFromPlayer()
        }.onFailure {
            _isPlaying.value = false
            _error.value = "播放失败: ${it.message ?: "未知错误"}"
        }
    }

    /**
     * 从播放器读取时长。
     * duration 在 media 未 ready 时是 C.TIME_UNSET（一个负的极大值），
     * 用 `> 0` 判断看着没问题，但一旦读到 UNSET 就会把脏值写进 _duration，
     * 进度条瞬间跳到满格。这里显式排除 TIME_UNSET。
     */
    private fun applyDurationFromPlayer() {
        val d = runCatching { player.duration }.getOrDefault(C.TIME_UNSET)
        _duration.value = if (d != C.TIME_UNSET && d > 0) d else 0L
    }

    /**
     * 找下一个「还值得播」的索引；没有则返回 -1。
     * skip 里是要跳过的索引（解析失败的 + 本轮已播完的）。
     */
    private fun findNextIndex(skip: Set<Int>): Int {
        if (_queue.isEmpty()) return -1
        var idx = currentIndex
        for (i in 0 until _queue.size) {
            idx = (idx + 1) % _queue.size
            if (idx in skip) continue
            return idx
        }
        return -1
    }

    /**
     * 队列已经跑完（单曲队列 / 所有候选都试过）：真正停下来并暂停，不再循环 playAt。
     *
     * 这里刻意不用 stopPlayback()：stop + clearMediaItems 会把 media 一起清掉，
     * 迷你条上的播放按钮就变成"点了没反应"。改为暂停并回到开头，
     * 用户想重听时点一下播放即可（网易云也是这个行为）。
     */
    private fun stopAtQueueEnd() {
        resolveJob?.cancel()
        resolveJob = null
        resolveToken++
        _loading.value = false
        failedInThisRound.clear()
        playedInThisRound.clear()
        val ok = runCatching {
            player.pause()
            player.seekTo(0L)
        }.isSuccess
        if (!ok) stopPlayback()
        _isPlaying.value = false
        _position.value = 0L
    }

    /**
     * 自然播放结束时才调用：切下一首（解析失败不在这里处理）。
     *
     * ⚠️ 只能从主线程且不在 ExoPlayer 回调栈内调用 —— onPlaybackStateChanged 里已 post 出去。
     */
    fun next() {
        if (_queue.isEmpty()) return
        if (currentIndex in _queue.indices) playedInThisRound.add(currentIndex)
        val skip = failedInThisRound + playedInThisRound
        val nextIdx = findNextIndex(skip)
        if (nextIdx < 0) {
            // 单曲队列播完、或所有候选都试过：停下来并暂停，禁止自转
            stopAtQueueEnd()
            return
        }
        playAt(nextIdx)
    }

    /** 用户手动点下一首：清掉失败与已播标记，允许重新尝试并循环回到开头 */
    fun nextManual() {
        if (_queue.isEmpty()) return
        failedInThisRound.clear()
        playedInThisRound.clear()
        val nextIdx = findNextIndex(emptySet())
        if (nextIdx < 0) {
            stopAtQueueEnd()
            return
        }
        playAt(nextIdx)
    }

    fun prevManual() {
        failedInThisRound.clear()
        playedInThisRound.clear()
        if (_queue.isEmpty()) return
        var idx = currentIndex - 1
        if (idx < 0) idx = _queue.size - 1
        playAt(idx)
    }

    fun toggle() {
        runCatching {
            if (player.isPlaying) player.pause() else player.play()
        }
    }

    fun addToQueue(song: Song) {
        _queue.add(song)
        persistToPrefs()
    }

    fun clearQueue() {
        resolveJob?.cancel()
        resolveJob = null
        resolveToken++
        _loading.value = false
        _queue.clear()
        currentIndex = -1
        failedInThisRound.clear()
        playedInThisRound.clear()
        _duration.value = 0L
        _position.value = 0L
        stopPlayback()
        _currentSong.value = null
        persistToPrefs()
    }

    fun seekTo(ms: Long) {
        val d = runCatching { player.duration }.getOrDefault(C.TIME_UNSET)
        val safeMax = if (d != C.TIME_UNSET && d > 0) d else ms.coerceAtLeast(0L)
        runCatching { player.seekTo(ms.coerceIn(0L, safeMax)) }
    }

    private fun startPositionLoop() {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (isActive) {
                _position.value = runCatching { player.currentPosition }.getOrDefault(0L).coerceAtLeast(0L)
                // 时长在 media ready 后才可用（prepare 阶段是 UNSET），
                // 这里持续同步，避免进度条因 duration=0 而失准
                val d = runCatching { player.duration }.getOrDefault(C.TIME_UNSET)
                if (d != C.TIME_UNSET && d > 0) _duration.value = d
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
