package com.soundtrack.music.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.soundtrack.music.R
import com.soundtrack.music.model.Song
import com.soundtrack.music.source.SourceResolver
import com.soundtrack.music.util.MiniImageLoader

class SongAdapter(
    private val loader: MiniImageLoader,
    private val onClick: (Song) -> Unit,
    private val onDownload: ((Song) -> Unit)? = null
) : RecyclerView.Adapter<SongAdapter.VH>() {

    private val items = mutableListOf<Song>()

    fun setData(list: List<Song>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun add(item: Song) {
        items.add(item)
        notifyItemInserted(items.size - 1)
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    fun getItems(): List<Song> = items.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val cover: ImageView = v.findViewById(R.id.cover)
        private val title: TextView = v.findViewById(R.id.title)
        private val artist: TextView = v.findViewById(R.id.artist)
        private val source: TextView = v.findViewById(R.id.source)
        private val quality: TextView = v.findViewById(R.id.quality)
        private val more: ImageButton = v.findViewById(R.id.btn_more)

        fun bind(s: Song) {
            title.text = s.title
            artist.text = "${s.artist} · ${s.album}".trim('·', ' ', '·')
            // 优先查 BuiltinSources 中已适配的 label，否则查直接源硬编码表
            val label = com.soundtrack.music.source.BuiltinSources.BY_ID[s.source]?.label
                ?: DIRECT_LABEL[s.source]
                ?: s.source
            source.text = label
            quality.text = s.qualityLabel
            // 品质徽标颜色：标准灰、高品质青、无损金
            quality.setTextColor(when (s.qualityLabel) {
                "无损" -> 0xFFFFD27A.toInt()
                "高品质" -> 0xFF2EDFA3.toInt()
                else -> 0xFF7E8590.toInt()
            })
            loader.load(s.coverUrl, cover)
            itemView.setOnClickListener { onClick(s) }
            more.setOnClickListener { onDownload?.invoke(s) }
        }
    }

    companion object {
        private val DIRECT_LABEL = mapOf(
            "migu" to "咪咕音乐",
            "netease" to "网易云音乐",
            "kuwo" to "酷我音乐",
            "qq" to "QQ音乐",
            "myfreemp3" to "MyFreeMP3",
        )
    }
}
