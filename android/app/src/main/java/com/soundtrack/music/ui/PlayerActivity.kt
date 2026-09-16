package com.soundtrack.music.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.soundtrack.music.R
import com.soundtrack.music.adapter.LyricAdapter
import com.soundtrack.music.data.PlaylistModels
import com.soundtrack.music.data.PlaylistStore
import com.soundtrack.music.download.DownloadManager
import com.soundtrack.music.model.Song
import com.soundtrack.music.model.SongKeys
import com.soundtrack.music.player.LrcParser
import com.soundtrack.music.player.PlayMode
import com.soundtrack.music.player.PlayerRepository
import com.soundtrack.music.source.BuiltinSources
import com.soundtrack.music.source.SourceResolver
import com.soundtrack.music.util.Formatters
import com.soundtrack.music.util.MiniImageLoader
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class PlayerActivity : AppCompatActivity() {

    private lateinit var repo: PlayerRepository
    private lateinit var loader: MiniImageLoader
    private lateinit var lyricAdapter: LyricAdapter
    /**
     * 全屏歌词用**独立的 adapter 实例**（与 [lyricAdapter] 共享同一份 [lyricLines] 数据）。
     *
     * ⚠️ 不能复用同一实例：RecyclerView.setAdapter 内部会让 adapter 从旧 RV 解绑
     * （attachToRecyclerView 覆盖 mRecyclerView 引用并反注册旧 observer），
     * 同一个 adapter 绑两个 RV 时，notify 只回传给后绑的那个，
     * 普通页小歌词将收不到 setData/setActive，切歌后停留在旧歌词上。
     */
    private lateinit var fsLyricAdapter: LyricAdapter
    /** 缓存歌词列表与标题/歌手控件：updateLyric 每 300ms 跑一次，不该重复 findViewById */
    private lateinit var recyclerLyrics: RecyclerView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var coverView: ImageView
    private lateinit var bgView: ImageView
    /** 播放页红心（布局既有控件，位置不动，仅补逻辑） */
    private lateinit var btnFavorite: ImageButton
    /** 播放模式 / 定时关闭按钮（控制行新增） */
    private lateinit var btnMode: ImageButton
    private lateinit var btnSleep: ImageButton
    /** 全屏歌词层控件 */
    private lateinit var fullscreenLyrics: View
    private lateinit var fsBgView: ImageView
    private lateinit var fsTitleView: TextView
    private lateinit var fsArtistView: TextView
    private lateinit var recyclerFsLyrics: RecyclerView
    private lateinit var btnFsMode: ImageButton
    private lateinit var btnFsPrev: ImageButton
    private lateinit var btnFsPlay: ImageButton
    private lateinit var btnFsNext: ImageButton
    private lateinit var btnFsSleep: ImageButton
    /**
     * 全屏歌词拖动跟随控制：
     * 用户手指拖动期间 [userScrolling]=true，updateLyric 跳过自动滚动；
     * 松手静止超过 3 秒（[lastScrollTime]）后复位，恢复自动跟随。
     */
    private var userScrolling = false
    private var lastScrollTime = 0L
    private val handler = Handler(Looper.getMainLooper())

    /** 歌词只在切歌时解析一次；updateLyric 每 300ms 复用这份缓存（旧实现每次都重新 parse） */
    private var lyricLines: List<LrcParser.Line> = emptyList()
    /** 是否正在解析播放地址，用于避免"解析中"提示被 duration 流覆盖 */
    private var isResolving: Boolean = false
    /**
     * 正在跑的歌词拉取任务。key = "source:songId"。
     * 每个 song 只允许一个进行中的 fetch；新歌到来时先 cancel 同 key 的旧 Job，
     * 防止快速切歌时旧的 fetch 把 stale 歌词写回来，以及同一首歌重复拉。
     */
    private val lyricFetchJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val lyricRunnable = object : Runnable {
        override fun run() {
            updateLyric()
            handler.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        repo = PlayerRepository.get(this)
        loader = MiniImageLoader(this)
        lyricAdapter = LyricAdapter()
        fsLyricAdapter = LyricAdapter()

        bgView = findViewById(R.id.bg_blur)
        coverView = findViewById(R.id.player_cover)
        titleView = findViewById(R.id.player_title)
        artistView = findViewById(R.id.player_artist)
        val timeCurrent = findViewById<TextView>(R.id.time_current)
        val timeTotal = findViewById<TextView>(R.id.time_total)
        val seekBar = findViewById<SeekBar>(R.id.seek_bar)
        val btnPlay = findViewById<ImageButton>(R.id.btn_play)
        val btnNext = findViewById<ImageButton>(R.id.btn_next)
        val btnPrev = findViewById<ImageButton>(R.id.btn_prev)
        val btnDownload = findViewById<ImageButton>(R.id.btn_download)
        btnFavorite = findViewById(R.id.btn_favorite)
        btnMode = findViewById(R.id.btn_mode)
        btnSleep = findViewById(R.id.btn_sleep)
        recyclerLyrics = findViewById(R.id.recycler_lyrics)

        // 全屏歌词层
        fullscreenLyrics = findViewById(R.id.fullscreen_lyrics)
        fsBgView = findViewById(R.id.fs_bg)
        fsTitleView = findViewById(R.id.fs_title)
        fsArtistView = findViewById(R.id.fs_artist)
        recyclerFsLyrics = findViewById(R.id.recycler_fs_lyrics)
        btnFsMode = findViewById(R.id.btn_fs_mode)
        btnFsPrev = findViewById(R.id.btn_fs_prev)
        btnFsPlay = findViewById(R.id.btn_fs_play)
        btnFsNext = findViewById(R.id.btn_fs_next)
        btnFsSleep = findViewById(R.id.btn_fs_sleep)

        recyclerLyrics.layoutManager = LinearLayoutManager(this)
        recyclerLyrics.adapter = lyricAdapter
        // 全屏列表用独立 adapter 实例（见字段注释），数据源仍是同一份 lyricLines
        recyclerFsLyrics.layoutManager = LinearLayoutManager(this)
        recyclerFsLyrics.adapter = fsLyricAdapter
        lyricAdapter.onLineClick = { ms ->
            if (ms >= 0) repo.seekTo(ms)
        }
        fsLyricAdapter.onLineClick = { ms ->
            if (ms >= 0) repo.seekTo(ms)
        }

        // 全屏歌词：手指拖动时暂停自动跟随，松手静止 3 秒后恢复
        recyclerFsLyrics.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    userScrolling = true
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    lastScrollTime = System.currentTimeMillis()
                }
            }
        })

        // 长按复制歌词
        recyclerLyrics.setOnLongClickListener {
            val song = repo.currentSong.value ?: return@setOnLongClickListener false
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("lyric", song.lrc))
            Toast.makeText(this, "歌词已复制", Toast.LENGTH_SHORT).show()
            true
        }

        // 播放模式：LIST → SINGLE → SHUFFLE → LIST 循环切换，图标/配色随模式变
        repo.playMode.onEach { mode ->
            val icon = when (mode) {
                PlayMode.LIST -> R.drawable.ic_repeat
                PlayMode.SINGLE -> R.drawable.ic_repeat_one
                PlayMode.SHUFFLE -> R.drawable.ic_shuffle
            }
            // 非 LIST（即"非默认"）模式点亮为强调色，与"激活态"语义一致
            val tint = ContextCompat.getColorStateList(
                this,
                if (mode == PlayMode.LIST) R.color.text_2 else R.color.accent_1
            )
            val desc = when (mode) {
                PlayMode.LIST -> "列表循环"
                PlayMode.SINGLE -> "单曲循环"
                PlayMode.SHUFFLE -> "随机播放"
            }
            btnMode.setImageResource(icon)
            btnMode.imageTintList = tint
            btnMode.contentDescription = desc
            btnFsMode.setImageResource(icon)
            btnFsMode.imageTintList = tint
            btnFsMode.contentDescription = desc
        }.launchIn(lifecycleScope)
        btnMode.setOnClickListener { repo.cyclePlayMode() }
        btnFsMode.setOnClickListener { repo.cyclePlayMode() }

        // 定时关闭：剩余有效时按钮点亮
        repo.sleepEndMs.onEach { end ->
            val active = end > 0L && end > System.currentTimeMillis()
            val tint = ContextCompat.getColorStateList(
                this, if (active) R.color.accent_1 else R.color.text_2
            )
            val desc = if (active) "定时关闭（进行中）" else "定时关闭"
            btnSleep.imageTintList = tint
            btnSleep.contentDescription = desc
            btnFsSleep.imageTintList = tint
            btnFsSleep.contentDescription = desc
        }.launchIn(lifecycleScope)
        btnSleep.setOnClickListener { showSleepTimerDialog() }
        btnFsSleep.setOnClickListener { showSleepTimerDialog() }

        // 全屏歌词：点击封面 / 标题 / 歌手区进入；返回按钮退出
        coverView.setOnClickListener { showFullscreenLyrics() }
        titleView.setOnClickListener { showFullscreenLyrics() }
        artistView.setOnClickListener { showFullscreenLyrics() }
        val btnFsBack = findViewById<ImageButton>(R.id.btn_fs_back)
        btnFsBack.setOnClickListener { hideFullscreenLyrics() }
        btnFsPlay.setOnClickListener { repo.toggle() }
        btnFsNext.setOnClickListener { repo.nextManual() }
        btnFsPrev.setOnClickListener { repo.prevManual() }

        repo.error.onEach { msg ->
            if (!msg.isNullOrBlank()) {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }.launchIn(lifecycleScope)

        repo.currentSong.onEach { song ->
            // 红心随当前歌实时刷新（AC-6）；无播放中歌曲时下面 refreshFavorite 会置灰
            refreshFavorite(song)
            if (song == null) {
                // 队列被清空（迷你条关闭按钮）或从未播放：复位 UI，
                // 否则会一直停留在上一首的标题/封面/歌词上，看着像"卡住了"。
                titleView.text = "未在播放"
                artistView.text = "—"
                fsTitleView.text = "未在播放"
                fsArtistView.text = "—"
                coverView.setImageDrawable(null)
                bgView.setImageBitmap(null)
                fsBgView.setImageBitmap(null)
                lyricLines = emptyList()
                lyricAdapter.setData(emptyList())
                fsLyricAdapter.setData(emptyList())
                return@onEach
            }
            titleView.text = song.title
            artistView.text = song.artist
            fsTitleView.text = song.title
            fsArtistView.text = song.artist
            loader.load(song.coverUrl, coverView)
            // 模糊背景：异步取图。同步版 loadBitmap 内部 runBlocking 会把主线程
            // 卡在网络+解码上，切歌时造成明显卡顿甚至 ANR。
            loader.loadBitmapAsync(song.coverUrl) { bmp ->
                // activity 已销毁时不再触碰 view（回调是异步回来的）
                if (!isFinishing && !isDestroyed) {
                    // 普通页与全屏层共用同一份模糊结果，避免二次解码
                    val blurred = bmp?.let { blurApprox(it) }
                    bgView.setImageBitmap(blurred)
                    fsBgView.setImageBitmap(blurred)
                }
            }
            // 解析一次并缓存，updateLyric 直接复用
            lyricLines = LrcParser.parse(song.lrc)
            lyricAdapter.setData(lyricLines)
            fsLyricAdapter.setData(lyricLines)

            // Bug 2 修复：源侧 search() 返回的 Song.lrc 一直为空字符串，老代码只解析
            // song.lrc（=""）就直接走空集合，于是歌词永远空白。这里在收到新歌、
            // 且本地 lrc 真的为空时，主动调一次 fetchLyric：先主源 8s，再按"同名歌"
            // 补搜 netease / migu 各 4s；拿到后写回 song.lrc + 重解析 + 刷新 adapter，
            // 整段安静失败（不 toast）。
            val key = "${song.source}:${song.songId}"
            // 同 songId 的旧 job 先 cancel：避免"切到 A → 切到 B 又切回 A"重复拉，
            // 也避免旧 fetch 写回时覆盖新 song 的 lyricLines。
            lyricFetchJobs.remove(key)?.cancel()
            if (song.lrc.isBlank()) {
                val targetSong = song
                lyricFetchJobs[key] = lifecycleScope.launch {
                    try {
                        val fetched = fetchLyricWithFallback(targetSong)
                        if (!fetched.isNullOrBlank()) {
                            // 写回 song.lrc，防止下一次 currentSong 重发同一个 Song 时再次 fetch。
                            targetSong.lrc = fetched
                            if (!isFinishing && !isDestroyed) {
                                lyricLines = LrcParser.parse(fetched)
                                lyricAdapter.setData(lyricLines)
                                fsLyricAdapter.setData(lyricLines)
                            }
                        }
                        // 取不到 → 安静失败，UI 保持空集合
                    } catch (_: Throwable) {
                        // 任何异常都吞掉，切歌流畅度优先
                    } finally {
                        lyricFetchJobs.remove(key)
                    }
                }
            }
        }.launchIn(lifecycleScope)

        // 解析播放地址期间给出即时反馈（旧行为：点了新歌毫无反应，要等解析完）
        repo.loading.onEach { loading ->
            isResolving = loading
            if (loading) timeTotal.text = "解析中"
        }.launchIn(lifecycleScope)

        repo.isPlaying.onEach { playing ->
            val icon = if (playing) R.drawable.ic_pause else R.drawable.ic_play
            btnPlay.setImageResource(icon)
            btnFsPlay.setImageResource(icon)
        }.launchIn(lifecycleScope)

        repo.position.onEach { pos ->
            timeCurrent.text = Formatters.duration((pos / 1000).toInt())
            val dur = repo.duration.value
            if (dur > 0) {
                seekBar.progress = ((pos * 1000 / dur).toInt()).coerceIn(0, 1000)
            }
        }.launchIn(lifecycleScope)

        repo.duration.onEach { dur ->
            // 解析中时保留"解析中"提示，不被 0:00 覆盖
            if (!isResolving) timeTotal.text = Formatters.duration((dur / 1000).toInt())
        }.launchIn(lifecycleScope)

        btnPlay.setOnClickListener { repo.toggle() }
        btnNext.setOnClickListener { repo.nextManual() }
        btnPrev.setOnClickListener { repo.prevManual() }
        btnDownload.setOnClickListener {
            val song = repo.currentSong.value ?: return@setOnClickListener
            lifecycleScope.launch {
                val uri = DownloadManager(this@PlayerActivity).download(song)
                Toast.makeText(this@PlayerActivity, if (uri != null) "已下载" else "下载失败", Toast.LENGTH_SHORT).show()
            }
        }

        // 红心：点击打开「加入歌单」面板（首项即「我喜欢的音乐」，勾选/取消即收藏/取消收藏）；
        // 红心图标仍反映"当前歌是否在「我喜欢的音乐」中"（见 refreshFavorite）。
        btnFavorite.setOnClickListener { openAddToPlaylist() }
        // 初始同步一次（onEach 也会补，但二者幂等）
        refreshFavorite(repo.currentSong.value)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val dur = repo.duration.value
                if (dur > 0) {
                    repo.seekTo(seekBar?.progress?.times(dur)?.div(1000L) ?: 0L)
                }
            }
        })

        val keyword = intent.getStringExtra("playlist_keyword")
        if (!keyword.isNullOrBlank()) {
            // 首页歌单进入：交给 SearchFragment 逻辑，这里简化提示
            Toast.makeText(this, "歌单: $keyword", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // 从「加入歌单」选择器返回后，重新同步红心状态
        refreshFavorite(repo.currentSong.value)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 全屏歌词显示时，物理返回键先退出全屏层
        if (fullscreenLyrics.visibility == View.VISIBLE) {
            hideFullscreenLyrics()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    /** 按当前歌是否在「我喜欢的音乐」中切换红心图标；无播放中歌曲时空心 + 置灰 + 点击无效（5.11）。 */
    private fun refreshFavorite(song: Song?) {
        if (song == null) {
            btnFavorite.setImageResource(R.drawable.ic_favorite_border)
            btnFavorite.alpha = 0.4f
            btnFavorite.isEnabled = false
            return
        }
        btnFavorite.isEnabled = true
        btnFavorite.alpha = 1.0f
        val contains = PlaylistStore.get(this)
            .containsIn(PlaylistModels.DEFAULT_PLAYLIST_ID, SongKeys.of(song))
        btnFavorite.setImageResource(
            if (contains) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        )
    }

    /** 红心点击：打开「加入歌单」选择器（红心图标仍反映"是否在「我喜欢的音乐」中"；不改布局、不加常驻按钮）。 */
    private fun openAddToPlaylist() {
        val song = repo.currentSong.value ?: return
        AddToPlaylistSheet.newInstance(song).apply {
            setOnChanged {
                refreshFavorite(repo.currentSong.value)
                // 确认后可能只加了自建歌单，界面上没有其他反馈，补一条轻提示
                Toast.makeText(this@PlayerActivity, "已更新歌单", Toast.LENGTH_SHORT).show()
            }
        }.show(supportFragmentManager, "add_to_playlist")
    }

    /** 定时关闭选项：15 / 30 / 45 / 60 分钟，或取消已设置的定时。 */
    private fun showSleepTimerDialog() {
        if (isFinishing || isDestroyed) return
        val minutes = intArrayOf(15, 30, 45, 60)
        val labels = minutes.map { "$it 分钟" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("定时关闭")
            .setItems(labels) { _, which ->
                val m = minutes[which]
                repo.setSleepTimer(m)
                Toast.makeText(this, "将在 $m 分钟后暂停播放", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("取消定时") { _, _ ->
                repo.cancelSleepTimer()
                Toast.makeText(this, "已取消定时关闭", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 显示全屏歌词层（带一个轻量淡入动画）；标题/歌手/模糊背景由 currentSong 流同步。 */
    private fun showFullscreenLyrics() {
        if (isFinishing || isDestroyed) return
        if (fullscreenLyrics.visibility == View.VISIBLE) return
        fullscreenLyrics.visibility = View.VISIBLE
        fullscreenLyrics.alpha = 0f
        fullscreenLyrics.animate().alpha(1f).setDuration(200).start()
        // 复位拖动状态：每次进入全屏都从「自动跟随」开始
        userScrolling = false
        lastScrollTime = 0L
    }

    /** 隐藏全屏歌词层，并复位拖动跟随状态（普通页小歌词不受影响）。 */
    private fun hideFullscreenLyrics() {
        if (isFinishing || isDestroyed) return
        if (fullscreenLyrics.visibility != View.VISIBLE) return
        fullscreenLyrics.visibility = View.GONE
        userScrolling = false
    }

    override fun onStart() {
        super.onStart()
        handler.post(lyricRunnable)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(lyricRunnable)
    }

    private fun updateLyric() {
        // activity 正在销毁时不再触碰 view（runnable 每 300ms 触发一次）
        if (isFinishing || isDestroyed) return
        val lines = lyricLines
        if (lines.isEmpty()) return
        val idx = LrcParser.indexOf(lines, repo.position.value)
        if (idx < 0) return
        lyricAdapter.setActive(idx)
        fsLyricAdapter.setActive(idx)
        // 普通页底部小歌词：保持原有自动滚动行为不变
        scrollLyricTo(recyclerLyrics, idx)
        // 全屏歌词：用户拖动期间跳过自动滚动，松手静止超过 3 秒恢复自动跟随
        if (fullscreenLyrics.visibility == View.VISIBLE) {
            if (userScrolling) {
                // 拖动 / 惯性滑动（fling）中：继续等待，绝不和手指抢滚动
                if (recyclerFsLyrics.scrollState != RecyclerView.SCROLL_STATE_IDLE) return
                // 已松手静止但停顿不足 3 秒：仍保持用户滚到的位置
                if (System.currentTimeMillis() - lastScrollTime <= 3000) return
                userScrolling = false
            }
            scrollLyricTo(recyclerFsLyrics, idx)
        }
    }

    /** 把指定行滚到列表上 1/3 处，普通页与全屏页共用。 */
    private fun scrollLyricTo(rv: RecyclerView, idx: Int) {
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        lm.scrollToPositionWithOffset(idx, rv.height / 3)
    }

    /**
     * 拉歌词：先主源（8s 超时），失败再按"同名歌"补搜 netease / migu，每个独立 4s。
     *
     * 主源失败时通常是因为原 song 的 source / songId 在该平台上根本没有这首歌的
     * 歌词条目（如播客、聚合源），所以补搜时用 "title artist" 命中同名的另一份标识符，
     * 再调 fetchLyric 取歌词 —— 与 PlayerRepository.resolvePlayableUrl 的补搜策略
     * 一致。
     *
     * 任何一层异常 / 超时都返回 null，由调用方安静失败。
     */
    private suspend fun fetchLyricWithFallback(song: Song): String? {
        // 1) 主源 8s 总超时（避免切歌流畅度被卡住）
        val main: String? = runCatching {
            withTimeoutOrNull(8_000L) {
                SourceResolver.resolve(song.source)?.fetchLyric(song)
            }
        }.getOrNull()
        if (!main.isNullOrBlank()) return main

        // 2) 补搜 netease / migu 主力；按 BuiltinSources.canPlay 过滤 + 跳过同主源
        val fallbackIds = listOf("netease", "migu").filter { id ->
            BuiltinSources.BY_ID[id]?.let { BuiltinSources.canPlay(it) } == true
        }
        for (srcId in fallbackIds) {
            if (srcId == song.source) continue
            val src = SourceResolver.resolve(srcId) ?: continue
            // 独立 4s 超时；内部含 search + fetchLyric，由各源自行 withContext(IO)
            val lrc: String? = runCatching {
                withTimeoutOrNull(4_000L) {
                    val matched = src.search("${song.title} ${song.artist}", 5)
                        .firstOrNull { it.title.equals(song.title, ignoreCase = true) }
                        ?: return@withTimeoutOrNull null
                    src.fetchLyric(matched)
                }
            }.getOrNull()
            if (!lrc.isNullOrBlank()) return lrc
        }
        return null
    }

    /** 马赛克化近似模糊。bitmap 已回收 / 尺寸异常时返回 null，由调用方保持占位。 */
    private fun blurApprox(bitmap: Bitmap): Bitmap? {
        if (bitmap.isRecycled) return null
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 1 || h <= 1) return null
        val tiny = 20.coerceAtMost(w).coerceAtMost(h)
        if (tiny <= 0) return null
        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, tiny, tiny, true)
            Bitmap.createScaledBitmap(scaled, w, h, true)
        } catch (_: Exception) {
            null
        }
    }
}
