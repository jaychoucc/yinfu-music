package com.soundtrack.music.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.soundtrack.music.R
import com.soundtrack.music.data.LocalPlaylist
import com.soundtrack.music.util.MiniImageLoader

/**
 * 我的歌单列表（S3）条目适配器。
 *
 * 默认歌单（`builtin=true`）隐藏「重命名 / 删除」入口（Store 侧另有防御性拒绝，双保险）；
 * 行首封面由 [coverOf] 按「歌单内最新加入那首歌的封面」口径解析，无封面则保留 XML 上的渐变占位。
 */
class MyPlaylistAdapter(
    private val loader: MiniImageLoader,
    private val onClick: (LocalPlaylist) -> Unit,
    private val onRename: (LocalPlaylist) -> Unit,
    private val onDelete: (LocalPlaylist) -> Unit,
    /** 歌单封面 URL 解析器（末尾可选，默认 null = 不加载封面、保留渐变占位）。口径由调用方给出。 */
    private val coverOf: ((LocalPlaylist) -> String?)? = null
) : RecyclerView.Adapter<MyPlaylistAdapter.VH>() {

    private val items = mutableListOf<LocalPlaylist>()

    fun setData(list: List<LocalPlaylist>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_my_playlist, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val name: TextView = v.findViewById(R.id.name)
        private val count: TextView = v.findViewById(R.id.count)
        private val cover: ImageView = v.findViewById(R.id.cover)
        private val btnRename: TextView = v.findViewById(R.id.btn_rename)
        private val btnDelete: TextView = v.findViewById(R.id.btn_delete)

        fun bind(p: LocalPlaylist) {
            name.text = p.name
            count.text = "${p.count} 首"
            // 封面：先清掉复用残留（ViewHolder 复用会把上一行的图带过来），
            // 清空后露出 XML 上的渐变占位背景；有封面再异步加载覆盖上去。
            cover.setImageDrawable(null)
            val coverUrl = coverOf?.invoke(p)
            if (!coverUrl.isNullOrBlank()) loader.load(coverUrl, cover)
            if (p.builtin) {
                // 默认歌单不可改名不可删：入口整体隐藏
                btnRename.visibility = View.GONE
                btnDelete.visibility = View.GONE
            } else {
                btnRename.visibility = View.VISIBLE
                btnDelete.visibility = View.VISIBLE
            }
            itemView.setOnClickListener { onClick(p) }
            btnRename.setOnClickListener { onRename(p) }
            btnDelete.setOnClickListener { onDelete(p) }
        }
    }
}
