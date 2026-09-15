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
import com.soundtrack.music.util.MiniImageLoader

/**
 * 歌单详情（S4）条目适配器。
 *
 * 与 `SongAdapter` 的区别：多展示一行「解析后的播放地址」，且长按语义不同
 * （长按条目=移除、长按地址行=复制完整地址）。因此**不复用 `item_song`**。
 *
 * 音源中文名沿用 `SongAdapter` 第 65–67 行的既有映射逻辑（本文件自建一份等价 private 表，
 * 不去改动 `SongAdapter` 的可见性）。
 */
class PlaylistSongAdapter(
    private val loader: MiniImageLoader,
    private val onClick: (Song) -> Unit,
    private val onLongClickItem: (Song) -> Unit,
    private val onLongClickUrl: (Song) -> Unit
) : RecyclerView.Adapter<PlaylistSongAdapter.VH>() {

    private val items = mutableListOf<Song>()

    fun setData(list: List<Song>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist_song, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val cover: ImageView = v.findViewById(R.id.cover)
        private val title: TextView = v.findViewById(R.id.title)
        private val duration: TextView = v.findViewById(R.id.duration)
        private val artist: TextView = v.findViewById(R.id.artist)
        private val source: TextView = v.findViewById(R.id.source)
        private val playUrl: TextView = v.findViewById(R.id.play_url)
        private val more: ImageButton = v.findViewById(R.id.btn_item_more)

        fun bind(s: Song) {
            title.text = s.title.ifBlank { "未知歌曲" }
            artist.text = "${s.artist} · ${s.album}".trim('·', ' ', '·')
            // 优先查 BuiltinSources 中已适配的 label，否则查直接源硬编码表（与 SongAdapter 一致）
            source.text = com.soundtrack.music.source.BuiltinSources.BY_ID[s.source]?.label
                ?: DIRECT_LABEL[s.source]
                ?: s.source
            duration.text = if (s.durationSec > 0) formatDuration(s.durationSec) else ""
            loader.load(s.coverUrl, cover)

            // 地址行：恒占一行；有效地址原文显示（超长由 XML 的 ellipsize=middle 截断），
            // 无效/空一律显示占位文案，绝不留空、绝不显示 null。
            val url = s.playUrl
            if (url.isNotBlank() && url.startsWith("http")) {
                playUrl.text = url
                // 长按复制**完整**地址（用户获取完整地址的唯一途径）
                playUrl.setOnLongClickListener {
                    onLongClickUrl(s)
                    true
                }
            } else {
                playUrl.text = "未解析 · 播放时自动解析"
                playUrl.setOnLongClickListener(null)
            }

            itemView.setOnClickListener { onClick(s) }
            // 长按条目 = 从本歌单移除（默认歌单也允许移除）
            itemView.setOnLongClickListener {
                onLongClickItem(s)
                true
            }
            more.setOnClickListener { onLongClickItem(s) }
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

private fun formatDuration(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return "%d:%02d".format(m, s)
}
