package com.soundtrack.music.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.soundtrack.music.R
import com.soundtrack.music.data.ImportMiss

/**
 * 导入失败条目适配器（「导入失败歌曲」内置歌单详情页）。
 *
 *  - 整行 / 右侧搜索图标点击 → [onClick]：跳到搜索页搜「歌名 歌手」
 *  - 长按整行 → [onLongClick]：二次确认后移除该条失败记录
 *
 * 与 [PlaylistSongAdapter] 的区别：没有封面 / 播放地址行（失败条目只有元数据），
 * 且点击语义是「去搜索」而非「播放」。
 */
class ImportMissAdapter(
    private val onClick: (ImportMiss) -> Unit,
    private val onLongClick: (ImportMiss) -> Unit
) : RecyclerView.Adapter<ImportMissAdapter.VH>() {

    private val items = mutableListOf<ImportMiss>()

    fun setData(list: List<ImportMiss>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_import_miss, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val title: TextView = v.findViewById(R.id.miss_title)
        private val artist: TextView = v.findViewById(R.id.miss_artist)
        private val duration: TextView = v.findViewById(R.id.miss_duration)
        private val from: TextView = v.findViewById(R.id.miss_from)
        private val btnSearch: ImageView = v.findViewById(R.id.btn_miss_search)

        fun bind(m: ImportMiss) {
            title.text = m.title.ifBlank { "未知歌曲" }
            artist.text = m.artist.ifBlank { "未知歌手" }
            duration.text = if (m.durationSec > 0) formatDuration(m.durationSec) else ""
            from.text = if (m.fromPlaylistName.isBlank()) "导入失败" else "来自《${m.fromPlaylistName}》"

            itemView.setOnClickListener { onClick(m) }
            btnSearch.setOnClickListener { onClick(m) }
            itemView.setOnLongClickListener {
                onLongClick(m)
                true
            }
        }
    }
}

private fun formatDuration(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return "%d:%02d".format(m, s)
}
