package com.soundtrack.music.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.soundtrack.music.R
import com.soundtrack.music.adapter.SongAdapter
import com.soundtrack.music.data.FavoritePlaylistStore
import com.soundtrack.music.model.Song
import com.soundtrack.music.player.PlayerRepository
import com.soundtrack.music.util.MiniImageLoader

/** 默认歌单「我喜欢的音乐」的本地详情页。 */
class FavoritePlaylistActivity : AppCompatActivity() {
    private lateinit var store: FavoritePlaylistStore
    private lateinit var adapter: SongAdapter
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favorite_playlist)

        store = FavoritePlaylistStore(this)
        emptyView = findViewById(R.id.favorite_empty)
        adapter = SongAdapter(
            loader = MiniImageLoader(this),
            onClick = ::playFromFavorites
        )
        findViewById<RecyclerView>(R.id.favorite_songs).apply {
            layoutManager = LinearLayoutManager(this@FavoritePlaylistActivity)
            adapter = this@FavoritePlaylistActivity.adapter
        }
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        val songs = store.songs()
        adapter.setData(songs)
        emptyView.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun playFromFavorites(song: Song) {
        val songs = store.songs()
        val startIndex = songs.indexOfFirst {
            it.source == song.source && it.songId == song.songId
        }.coerceAtLeast(0)
        PlayerRepository.get(this).play(songs, startIndex)
        startActivity(Intent(this, PlayerActivity::class.java))
    }
}
