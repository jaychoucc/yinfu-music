package com.soundtrack.music.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.soundtrack.music.R
import com.soundtrack.music.home.ToplistCard
import com.soundtrack.music.util.MiniImageLoader

/**
 * 首页"排行榜"横向 Rail。卡片上只显示封面 + 名字，前 3 首预览在详情页展开。
 */
class ToplistCardAdapter(
    private val loader: MiniImageLoader,
    private val onClick: (ToplistCard) -> Unit
) : RecyclerView.Adapter<ToplistCardAdapter.VH>() {

    private val items = mutableListOf<ToplistCard>()

    fun setData(list: List<ToplistCard>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_toplist_card, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val cover: ImageView = v.findViewById(R.id.top_cover)
        private val name: TextView = v.findViewById(R.id.top_name)
        private val preview: TextView = v.findViewById(R.id.top_preview)

        fun bind(card: ToplistCard) {
            name.text = card.name
            // 榜单预览：接口只给歌名+歌手（无 id），展示为多行文本
            val pv = card.previews
            if (pv.isEmpty()) {
                preview.visibility = View.GONE
            } else {
                preview.visibility = View.VISIBLE
                preview.text = pv.joinToString("\n")
            }
            loader.load(card.coverUrl, cover)
            itemView.setOnClickListener { onClick(card) }
        }
    }
}