package com.soundtrack.music.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.soundtrack.music.R
import com.soundtrack.music.adapter.LyricAdapter
import com.soundtrack.music.download.DownloadManager
import com.soundtrack.music.model.Song
import com.soundtrack.music.player.LrcParser
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
    /** 缓存歌词列表与标题/歌手控件：updateLyric 每 300ms 跑一次，不该重复 findViewById */
    private lateinit var recyclerLyrics: RecyclerView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var coverView: ImageView
    private lateinit var bgView: ImageView
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
        recyclerLyrics = findViewById(R.id.recycler_lyrics)

        recyclerLyrics.layoutManager = LinearLayoutManager(this)
        recyclerLyrics.adapter = lyricAdapter
        lyricAdapter.onLineClick = { ms ->
            if (ms >= 0) repo.seekTo(ms)
        }

        // 长按复制歌词
        recyclerLyrics.setOnLongClickListener {
            val song = repo.currentSong.value ?: return@setOnLongClickListener false
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("lyric", song.lrc))
            Toast.makeText(this, "歌词已复制", Toast.LENGTH_SHORT).show()
            true
        }

        repo.error.onEach { msg ->
            if (!msg.isNullOrBlank()) {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }.launchIn(lifecycleScope)

        repo.currentSong.onEach { song ->
            if (song == null) {
                // 队列被清空（迷你条关闭按钮）或从未播放：复位 UI，
                // 否则会一直停留在上一首的标题/封面/歌词上，看着像"卡住了"。
                titleView.text = "未在播放"
                artistView.text = "—"
                coverView.setImageDrawable(null)
                bgView.setImageBitmap(null)
                lyricLines = emptyList()
                lyricAdapter.setData(emptyList())
                return@onEach
            }
            titleView.text = song.title
            artistView.text = song.artist
            loader.load(song.coverUrl, coverView)
            // 模糊背景：异步取图。同步版 loadBitmap 内部 runBlocking 会把主线程
            // 卡在网络+解码上，切歌时造成明显卡顿甚至 ANR。
            loader.loadBitmapAsync(song.coverUrl) { bmp ->
                // activity 已销毁时不再触碰 view（回调是异步回来的）
                if (!isFinishing && !isDestroyed) {
                    bgView.setImageBitmap(bmp?.let { blurApprox(it) })
                }
            }
            // 解析一次并缓存，updateLyric 直接复用
            lyricLines = LrcParser.parse(song.lrc)
            lyricAdapter.setData(lyricLines)

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
            btnPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
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
        if (idx >= 0) {
            lyricAdapter.setActive(idx)
            // 用 onCreate 里缓存的引用：runnable 每 300ms 触发一次，
            // 反复 findViewById 既费时又可能在 view detach 后拿到 null（NPE 会闪退）。
            val lm = recyclerLyrics.layoutManager as? LinearLayoutManager ?: return
            lm.scrollToPositionWithOffset(idx, recyclerLyrics.height / 3)
        }
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
