package com.soundtrack.music.ui

import android.content.Intent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.soundtrack.music.R
import com.soundtrack.music.player.PlayerRepository
import com.soundtrack.music.util.MiniImageLoader
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 底部悬浮迷你播放器控制器。
 *
 * 解决「按返回键退出播放页后就再也进不去播放界面」的问题：
 * 在 MainActivity / PlaylistDetailActivity 底部常驻一条迷你条，
 * 有歌时显示（可暂停/播放、下一首、关闭），点整条即可回到 PlayerActivity。
 *
 * 用法：
 * ```
 * private lateinit var miniPlayer: MiniPlayerController
 * override fun onCreate(...) {
 *     miniPlayer = MiniPlayerController(this, findViewById(R.id.mini_player))
 *     miniPlayer.bind()
 * }
 * ```
 */
class MiniPlayerController(
    private val activity: AppCompatActivity,
    private val root: View
) {

    private val repo: PlayerRepository = PlayerRepository.get(activity)
    private val loader: MiniImageLoader = MiniImageLoader(activity)
    private var collectJob: Job? = null

    private lateinit var cover: ImageView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnClose: ImageButton
    private lateinit var progress: ProgressBar

    /** 绑定数据收集与点击事件。内部用 repeatOnLifecycle，无需在 onDestroy 反注册。 */
    fun bind() {
        // 初始隐藏：避免布局刚 inflate 时闪一下空条
        root.visibility = View.GONE

        cover = root.findViewById(R.id.mini_cover)
        title = root.findViewById(R.id.mini_title)
        artist = root.findViewById(R.id.mini_artist)
        btnPlay = root.findViewById(R.id.mini_btn_play)
        btnNext = root.findViewById(R.id.mini_btn_next)
        btnClose = root.findViewById(R.id.mini_btn_close)
        progress = root.findViewById(R.id.mini_progress)

        // 让圆角背景真正裁掉封面四角（minSdk 24，API 21+ 才有 clipToOutline，安全）
        cover.clipToOutline = true
        // 跑马灯需要 selected 状态才会滚动
        title.isSelected = true

        // 整条点击进入播放页；三个按钮各自消费自己的点击（子 view 优先，不会被 root 吞掉）
        root.setOnClickListener { openPlayer() }
        btnPlay.setOnClickListener { repo.toggle() }
        btnNext.setOnClickListener { repo.nextManual() }
        btnClose.setOnClickListener {
            // clearQueue 会把 currentSong 置空，下面的收集器随即将 root 置 GONE
            repo.clearQueue()
        }

        collectJob?.cancel()
        collectJob = activity.lifecycleScope.launch {
            // STARTED 才收集：页面不可见时不刷新 UI，省掉后台无谓的重绘
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    repo.currentSong.collect { song ->
                        if (song == null) {
                            root.visibility = View.GONE
                            return@collect
                        }
                        root.visibility = View.VISIBLE
                        title.text = song.title.ifBlank { "未知歌曲" }
                        artist.text = song.artist.ifBlank { "未知歌手" }
                        cover.setImageDrawable(null)
                        loader.load(song.coverUrl, cover)
                    }
                }
                launch {
                    repo.isPlaying.collect { playing ->
                        btnPlay.setImageResource(
                            if (playing) R.drawable.ic_pause else R.drawable.ic_play
                        )
                    }
                }
                launch {
                    repo.position.collect { pos ->
                        updateProgress(pos, repo.duration.value)
                    }
                }
                launch {
                    repo.duration.collect { dur ->
                        updateProgress(repo.position.value, dur)
                    }
                }
            }
        }
    }

    /** 解绑（可选）。不调用也不会泄漏，因为 repeatOnLifecycle 会随 DESTROY 自动取消。 */
    fun unbind() {
        collectJob?.cancel()
        collectJob = null
    }

    private fun openPlayer() {
        if (repo.currentSong.value == null) return
        runCatching {
            activity.startActivity(Intent(activity, PlayerActivity::class.java))
        }
    }

    /** max 固定 1000，直接换算千分比；duration <= 0（未 ready / 直播流）时归零 */
    private fun updateProgress(positionMs: Long, durationMs: Long) {
        progress.progress = if (durationMs > 0) {
            ((positionMs * 1000L) / durationMs).toInt().coerceIn(0, 1000)
        } else {
            0
        }
    }
}
