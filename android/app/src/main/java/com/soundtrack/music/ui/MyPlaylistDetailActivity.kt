package com.soundtrack.music.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.soundtrack.music.R
import com.soundtrack.music.adapter.ImportMissAdapter
import com.soundtrack.music.adapter.PlaylistSongAdapter
import com.soundtrack.music.data.ImportMiss
import com.soundtrack.music.data.PlaylistModels
import com.soundtrack.music.data.PlaylistStore
import com.soundtrack.music.data.PrefsStore
import com.soundtrack.music.model.Song
import com.soundtrack.music.model.SongKeys
import com.soundtrack.music.player.PlayerRepository
import com.soundtrack.music.util.MiniImageLoader

/**
 * 本地歌单详情页（S4）。
 *
 *  - 顶栏：返回 + 歌单名 + 歌曲数
 *  - 列表：条目展示封面 / 歌名 / 歌手 / 音源中文名 / **解析后的播放地址**
 *  - 空态：默认歌单与自建歌单分别给不同引导文案
 *  - 点击条目：整歌单入队、点击项为起点播放，并跳播放页（Q1 裁定）
 *  - 长按条目 / 点右侧按钮：从本歌单移除；长按地址行：复制完整地址
 *  - onResume 重读：直链回填后地址行自动刷新
 *
 * **失败列表模式**（[playlistId] == [PlaylistModels.FAILED_PLAYLIST_ID]）：
 * 主列表恒空（该内置歌单不放歌曲条目），改用 [ImportMissAdapter] 展示 [PlaylistStore.allMisses]
 * 聚合的失败条目；点击=搜索该歌，长按=移除记录。
 */
class MyPlaylistDetailActivity : AppCompatActivity() {

    private lateinit var store: PlaylistStore
    private lateinit var prefs: PrefsStore
    private lateinit var adapter: PlaylistSongAdapter
    private lateinit var missAdapter: ImportMissAdapter
    private lateinit var miniPlayer: MiniPlayerController
    private lateinit var titleView: TextView
    private lateinit var countView: TextView
    private lateinit var emptyHint: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var missRecycler: RecyclerView

    private var playlistId: String = PlaylistModels.DEFAULT_PLAYLIST_ID
    private var songs: List<Song> = emptyList()
    private var misses: List<ImportMiss> = emptyList()

    private val failedMode: Boolean
        get() = playlistId == PlaylistModels.FAILED_PLAYLIST_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_my_playlist_detail)
        store = PlaylistStore.get(this)
        prefs = PrefsStore(this)

        // 必须放在任何提前 return 之前，否则异常分支没有迷你条（参照 PlaylistDetailActivity）
        miniPlayer = MiniPlayerController(this, findViewById(R.id.mini_player))
        miniPlayer.bind()

        playlistId = intent.getStringExtra(PlaylistModels.EXTRA_LOCAL_PLAYLIST_ID)
            ?: PlaylistModels.DEFAULT_PLAYLIST_ID
        val nameExtra = intent.getStringExtra(PlaylistModels.EXTRA_LOCAL_PLAYLIST_NAME).orEmpty()

        val btnBack = findViewById<ImageButton>(R.id.btn_back)
        titleView = findViewById(R.id.detail_title)
        countView = findViewById(R.id.detail_count)
        emptyHint = findViewById(R.id.empty_hint)
        recycler = findViewById(R.id.recycler_playlist_songs)
        missRecycler = findViewById(R.id.recycler_import_misses)

        // 秒显标题：优先用传入名字，回退到 Store 里的真实名
        titleView.text = nameExtra.ifBlank {
            store.playlists().firstOrNull { it.id == playlistId }?.name ?: "歌单"
        }
        btnBack.setOnClickListener { finish() }

        recycler.layoutManager = LinearLayoutManager(this)
        adapter = PlaylistSongAdapter(
            loader = MiniImageLoader(this),
            onClick = { song -> playFromHere(song) },
            onLongClickItem = { song -> confirmRemove(song) },
            onLongClickUrl = { song -> copyUrl(song) }
        )
        recycler.adapter = adapter

        missRecycler.layoutManager = LinearLayoutManager(this)
        missAdapter = ImportMissAdapter(
            onClick = { miss -> searchMiss(miss) },
            onLongClick = { miss -> confirmRemoveMiss(miss) }
        )
        missRecycler.adapter = missAdapter

        reload()
    }

    override fun onResume() {
        super.onResume()
        // 从播放页/选择器返回、或直链回填后，刷新条目与地址行
        reload()
    }

    private fun reload() {
        val playlist = store.playlists().firstOrNull { it.id == playlistId }
        titleView.text = playlist?.name ?: "歌单"

        if (failedMode) {
            // —— 失败列表模式 ——
            songs = emptyList()
            adapter.setData(songs)
            misses = store.allMisses()
            missAdapter.setData(misses)
            countView.text = "${misses.size} 首"

            recycler.visibility = View.GONE
            if (misses.isEmpty()) {
                missRecycler.visibility = View.GONE
                emptyHint.visibility = View.VISIBLE
                emptyHint.text = "暂无导入失败的歌曲\n导入时未匹配上的歌会出现在这里，可点击搜索"
            } else {
                missRecycler.visibility = View.VISIBLE
                emptyHint.visibility = View.GONE
            }
            return
        }

        // —— 普通歌单模式 ——
        missRecycler.visibility = View.GONE
        songs = store.songsOf(playlistId)
        countView.text = "${songs.size} 首"
        adapter.setData(songs)

        if (songs.isEmpty()) {
            recycler.visibility = View.GONE
            emptyHint.visibility = View.VISIBLE
            emptyHint.text = if (playlistId == PlaylistModels.DEFAULT_PLAYLIST_ID) {
                "还没有喜欢的歌曲，去播放页点小红心收藏吧"
            } else {
                "这个歌单还没有歌曲\n导入失败的歌曲会进入「导入失败歌曲」歌单"
            }
        } else {
            recycler.visibility = View.VISIBLE
            emptyHint.visibility = View.GONE
        }
    }

    /** 整歌单入队、点击项为起点。命中缓存直链时 PlayerRepository 会零解析直接播放（AC-27）。 */
    private fun playFromHere(song: Song) {
        val list = songs
        if (list.isEmpty()) return
        val idx = list.indexOfFirst { SongKeys.of(it) == SongKeys.of(song) }.coerceAtLeast(0)
        PlayerRepository.get(this).play(list, idx)
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    /**
     * 失败条目 → 搜索页：用「歌名 歌手」搜索，并记入搜索历史。
     *
     * 跳 [MainActivity]（CLEAR_TOP|SINGLE_TOP 复用已存在的首页实例，避免回退栈堆叠多个 MainActivity），
     * keyword 由 extra 传入，MainActivity 切到搜索 Tab 并把词带给 SearchFragment。
     */
    private fun searchMiss(miss: ImportMiss) {
        val keyword = buildString {
            append(miss.title.trim())
            if (miss.artist.isNotBlank()) {
                append(' ')
                append(miss.artist.trim())
            }
        }.ifBlank { return }

        // 让搜索历史也记录这首歌（按规格记录歌名；点击历史词条即重搜这首歌）
        prefs.addHistory(miss.title)

        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra(PlaylistModels.EXTRA_SEARCH_KEYWORD, keyword)
                putExtra(PlaylistModels.EXTRA_SEARCH_TAB, true)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
    }

    /** 长按地址行：复制完整 playUrl 到剪贴板。 */
    private fun copyUrl(song: Song) {
        val url = song.playUrl
        if (url.isBlank() || !url.startsWith("http")) {
            Toast.makeText(this, "该歌曲尚未解析出播放地址", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("play_url", url))
            Toast.makeText(this, "已复制播放地址", Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            // 极端情况下剪贴板不可用：静默失败，绝不崩溃
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmRemove(song: Song) {
        AlertDialog.Builder(this)
            .setTitle("从本歌单移除")
            .setMessage("确定要把《${song.title}》从这个歌单移除吗？")
            .setPositiveButton("移除") { _, _ ->
                store.removeFromPlaylist(playlistId, SongKeys.of(song))
                reload()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmRemoveMiss(miss: ImportMiss) {
        AlertDialog.Builder(this)
            .setTitle("移除失败记录")
            .setMessage("确定要移除《${miss.title}》的导入失败记录吗？")
            .setPositiveButton("移除") { _, _ ->
                store.removeMiss(miss)
                reload()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
