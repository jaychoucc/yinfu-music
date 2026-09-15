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
    private val onDownload: ((Song) -> Unit)? = null,
    /** 长按条目回调（末尾可选参数，默认 null；未传时完全等同旧行为）。 */
    private val onLongClick: ((Song) -> Unit)? = null,
    /**
     * 行尾按钮回调（末尾可选参数，默认 null）。
     *
     * 行尾按钮的**图标、无障碍描述、点击行为**均由本回调是否传入在 `bind()` 内一并派生，
     * 因此三者永远不会脱节：
     *  - 传入 onMore → 显示「⋮」+ contentDescription「更多」+ 点击回调 onMore(song, 按钮自身)（供调用方弹 PopupMenu）；
     *  - 未传 onMore（默认）→ 显示旧的「下载图标」+ contentDescription「下载」+ 点击直接下载，与旧行为完全等价。
     *
     * 第二参数是行尾按钮 View 本身，供调用方作为弹出菜单的锚点。
     */
    private val onMore: ((Song, View) -> Unit)? = null
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
        private val duration: TextView = v.findViewById(R.id.duration)
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
            duration.text = if (s.durationSec > 0) formatDuration(s.durationSec) else ""
            loader.load(s.coverUrl, cover)
            itemView.setOnClickListener { onClick(s) }
            // 仅在有长按回调时消费事件（返回 true）；无回调时返回 false，完全等同旧行为
            itemView.setOnLongClickListener {
                onLongClick?.invoke(s)
                onLongClick != null
            }
            // 行尾按钮的「图标」与「点击行为」由 onMore 是否传入派生，保证二者永不脱节：
            // 传了 onMore → ⋮ + 弹出更多菜单；未传（如远程歌单页）→ 完全保持旧的「下载图标 + 点击直接下载」
            if (onMore != null) {
                more.setImageResource(R.drawable.ic_more_vert)
                more.contentDescription = "更多"
                more.setOnClickListener { v -> onMore.invoke(s, v) }
            } else {
                more.setImageResource(R.drawable.ic_download)
                more.contentDescription = "下载"
                more.setOnClickListener { onDownload?.invoke(s) }
            }
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
