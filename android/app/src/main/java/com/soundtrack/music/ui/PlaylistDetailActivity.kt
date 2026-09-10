package com.soundtrack.music.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.soundtrack.music.R
import com.soundtrack.music.adapter.SongAdapter
import com.soundtrack.music.download.DownloadManager
import com.soundtrack.music.home.HomeRepository
import com.soundtrack.music.player.PlayerRepository
import com.soundtrack.music.util.MiniImageLoader
import kotlinx.coroutines.launch

/**
 * 歌单 / 排行榜详情页。
 *
 *  - 顶部 TopBar：返回 + 标题
 *  - 大封面：取歌单里第一首歌的封面
 *  - 副标题："歌单 · 网易云精选" 或 "热歌榜 · 实时榜单"
 *  - 列表：复用 item_song.xml + SongAdapter
 *  - 点击一首歌：整张歌单入队，从点击的 index 开始播放，并跳转 PlayerActivity
 *  - 点击下载：复用 DownloadManager，Toast 提示结果（activity 已 onCreate 完毕，可以 toast）
 */
class PlaylistDetailActivity : AppCompatActivity() {

    private lateinit var loader: MiniImageLoader
    private lateinit var songAdapter: SongAdapter

    private var playlistId: Long = 0L
    private var playlistName: String = ""
    private var isToplist: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist_detail)
        loader = MiniImageLoader(this)

        playlistId = intent.getLongExtra("playlist_id", 0L)
        playlistName = intent.getStringExtra("playlist_name").orEmpty()
        isToplist = intent.getBooleanExtra("is_toplist", false)

        val btnBack = findViewById<ImageButton>(R.id.btn_back)
        val titleView = findViewById<TextView>(R.id.detail_title)
        val subView = findViewById<TextView>(R.id.detail_sub)
        val coverView = findViewById<ImageView>(R.id.detail_cover)
        val loadingView = findViewById<TextView>(R.id.detail_loading)
        val recycler = findViewById<RecyclerView>(R.id.recycler_songs)

        titleView.text = if (playlistName.isNotBlank()) playlistName else "歌单"
        subView.text = if (isToplist) "热歌榜 · 实时榜单" else "歌单 · 网易云精选"

        btnBack.setOnClickListener { finish() }

        recycler.layoutManager = LinearLayoutManager(this)
        songAdapter = SongAdapter(
            loader,
            onClick = { song -> playFromHere(song) },
            onDownload = { song -> startDownload(song) }
        )
        recycler.adapter = songAdapter

        // 拉详情
        if (playlistId <= 0L) {
            loadingView.visibility = View.VISIBLE
            loadingView.text = "无效的歌单 id"
            return
        }
        loadingView.visibility = View.VISIBLE
        loadingView.text = "正在加载歌曲…"
        lifecycleScope.launch {
            val songs = try {
                HomeRepository().loadPlaylistDetail(playlistId)
            } catch (t: Throwable) {
                loadingView.text = "歌曲加载失败，请检查网络"
                emptyList()
            }

            if (songs.isEmpty()) {
                // 保留 loadingView 的失败/空提示
                if (loadingView.text == "正在加载歌曲…") {
                    loadingView.text = "暂无歌曲"
                }
                return@launch
            }
            loadingView.visibility = View.GONE

            // 第一首歌的封面给顶部大图
            loader.load(songs.first().coverUrl, coverView)
            songAdapter.setData(songs)
        }
    }

    /**
     * 把整张歌单作为队列，从点击位置开始播放，跳转到 PlayerActivity。
     * PlayerRepository.play 会异步解析每一首的播放地址（netease 走 NeteaseMusicSource），
     * 因此队列里所有 netease 歌曲点击后都能真实播放。
     */
    private fun playFromHere(song: com.soundtrack.music.model.Song) {
        val items = songAdapter.getItems()
        val idx = items.indexOfFirst { it.songId == song.songId && it.source == song.source }
            .coerceAtLeast(0)
        PlayerRepository.get(this).play(items, idx)
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun startDownload(song: com.soundtrack.music.model.Song) {
        // 详情列表的 song.playUrl 可能还是空（首页详情刚拉下来时还没解析），
        // 给用户一个明确提示，而不是静默失败。
        if (!song.hasPlayUrl) {
            Toast.makeText(this, "正在解析播放地址，播放一次后即可下载", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val uri = DownloadManager(this@PlaylistDetailActivity).download(song)
            Toast.makeText(
                this@PlaylistDetailActivity,
                if (uri != null) "已下载" else "下载失败",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}