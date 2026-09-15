package com.soundtrack.music.ui

import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.soundtrack.music.R
import com.soundtrack.music.adapter.MyPlaylistAdapter
import com.soundtrack.music.data.LocalPlaylist
import com.soundtrack.music.data.PlaylistModels
import com.soundtrack.music.data.PlaylistStore
import com.soundtrack.music.util.MiniImageLoader
import com.soundtrack.music.util.PlaylistNameDialog

/**
 * 我的歌单列表页（S3）。
 *
 *  - 默认歌单恒置顶，自建歌单按创建时间倒序
 *  - 顶栏「+」新建；自建行可重命名 / 删除（二次确认）
 *  - 点击行进入 S4 详情页
 *  - onResume 重读：从详情页返回刷新歌曲数
 */
class MyPlaylistsActivity : AppCompatActivity() {

    private lateinit var store: PlaylistStore
    private lateinit var adapter: MyPlaylistAdapter
    private lateinit var miniPlayer: MiniPlayerController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_my_playlists)
        store = PlaylistStore.get(this)

        miniPlayer = MiniPlayerController(this, findViewById(R.id.mini_player))
        miniPlayer.bind()

        val btnBack = findViewById<ImageButton>(R.id.btn_back)
        val btnNew = findViewById<ImageButton>(R.id.btn_new_playlist)
        val recycler = findViewById<RecyclerView>(R.id.recycler_playlists)

        btnBack.setOnClickListener { finish() }
        btnNew.setOnClickListener {
            PlaylistNameDialog.showNew(this, store) { reload() }
        }

        recycler.layoutManager = LinearLayoutManager(this)
        adapter = MyPlaylistAdapter(
            loader = MiniImageLoader(this),
            onClick = { p -> openDetail(p) },
            onRename = { p -> PlaylistNameDialog.showRename(this, store, p) { reload() } },
            onDelete = { p -> confirmDelete(p) },
            coverOf = { p -> store.coverUrlOf(p.id) }
        )
        recycler.adapter = adapter

        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val all = store.playlists()
        // 默认歌单恒置顶；自建歌单按创建时间倒序
        val def = all.firstOrNull { it.builtin }
        val custom = all.filter { !it.builtin }.sortedByDescending { it.createdAt }
        val ordered = buildList {
            if (def != null) add(def)
            addAll(custom)
        }
        adapter.setData(ordered)
    }

    private fun openDetail(p: LocalPlaylist) {
        val intent = Intent(this, MyPlaylistDetailActivity::class.java).apply {
            putExtra(PlaylistModels.EXTRA_LOCAL_PLAYLIST_ID, p.id)
            putExtra(PlaylistModels.EXTRA_LOCAL_PLAYLIST_NAME, p.name)
        }
        startActivity(intent)
    }

    private fun confirmDelete(p: LocalPlaylist) {
        AlertDialog.Builder(this)
            .setTitle("删除歌单")
            .setMessage("确定要删除「${p.name}」吗？其中的歌曲会一并移除。")
            .setPositiveButton("删除") { _, _ ->
                store.deletePlaylist(p.id)
                reload()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
