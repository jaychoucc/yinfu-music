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
import com.soundtrack.music.player.LrcParser
import com.soundtrack.music.player.PlayerRepository
import com.soundtrack.music.util.Formatters
import com.soundtrack.music.util.MiniImageLoader
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {

    private lateinit var repo: PlayerRepository
    private lateinit var loader: MiniImageLoader
    private lateinit var lyricAdapter: LyricAdapter
    private val handler = Handler(Looper.getMainLooper())
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

        val bg = findViewById<ImageView>(R.id.bg_blur)
        val cover = findViewById<ImageView>(R.id.player_cover)
        val title = findViewById<TextView>(R.id.player_title)
        val artist = findViewById<TextView>(R.id.player_artist)
        val timeCurrent = findViewById<TextView>(R.id.time_current)
        val timeTotal = findViewById<TextView>(R.id.time_total)
        val seekBar = findViewById<SeekBar>(R.id.seek_bar)
        val btnPlay = findViewById<ImageButton>(R.id.btn_play)
        val btnNext = findViewById<ImageButton>(R.id.btn_next)
        val btnPrev = findViewById<ImageButton>(R.id.btn_prev)
        val btnDownload = findViewById<ImageButton>(R.id.btn_download)
        val recyclerLyrics = findViewById<RecyclerView>(R.id.recycler_lyrics)

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
            song ?: return@onEach
            title.text = song.title
            artist.text = song.artist
            loader.load(song.coverUrl, cover)
            // 模糊背景：先取缩略图做马赛克化放大；bitmap 不可用、回收、零宽高都容错。
            try {
                loader.loadBitmap(song.coverUrl)?.let { bmp ->
                    val blurred = blurApprox(bmp)
                    bg.setImageBitmap(blurred)
                }
            } catch (_: Exception) {
                bg.setImageDrawable(null)
            }
            val lines = LrcParser.parse(song.lrc)
            lyricAdapter.setData(lines)
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
            timeTotal.text = Formatters.duration((dur / 1000).toInt())
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
        val song = repo.currentSong.value ?: return
        val lines = LrcParser.parse(song.lrc)
        val idx = LrcParser.indexOf(lines, repo.position.value)
        if (idx >= 0) {
            lyricAdapter.setActive(idx)
            // runnable 每 300ms 触发，activity 销毁过程中 view 可能已 detach，
            // 旧代码 findViewById 返回 null 时 `.layoutManager` 会 NPE 导致播放页闪退。
            val rv = findViewById<RecyclerView>(R.id.recycler_lyrics) ?: return
            val lm = rv.layoutManager as? LinearLayoutManager ?: return
            lm.scrollToPositionWithOffset(idx, rv.height / 3)
        }
    }

    private fun blurApprox(bitmap: Bitmap): Bitmap {
        if (bitmap.isRecycled) return bitmap
        val w = bitmap.width.coerceAtLeast(1)
        val h = bitmap.height.coerceAtLeast(1)
        val tiny = (20).coerceAtMost(w).coerceAtMost(h)
        val scaled = Bitmap.createScaledBitmap(bitmap, tiny, tiny, true)
        return Bitmap.createScaledBitmap(scaled, w, h, true)
    }
}
