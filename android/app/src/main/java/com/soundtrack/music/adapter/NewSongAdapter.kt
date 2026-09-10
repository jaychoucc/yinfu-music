package com.soundtrack.music.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.soundtrack.music.R
import com.soundtrack.music.model.Song
import com.soundtrack.music.util.MiniImageLoader

/**
 * 首页"新歌速递"横向 Rail。点击立即播放这一首（单首队列）。
 */
class NewSongAdapter(
    private val loader: MiniImageLoader,
    private val onClick: (Song) -> Unit
) : RecyclerView.Adapter<NewSongAdapter.VH>() {

    private val items = mutableListOf<Song>()

    fun setData(list: List<Song>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_new_song_row, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val cover: ImageView = v.findViewById(R.id.new_song_cover)
        private val title: TextView = v.findViewById(R.id.new_song_title)
        private val artist: TextView = v.findViewById(R.id.new_song_artist)

        fun bind(song: Song) {
            title.text = song.title
            artist.text = song.artist
            loader.load(song.coverUrl, cover)
            itemView.setOnClickListener { onClick(song) }
        }
    }
}