package com.soundtrack.music.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.soundtrack.music.R
import com.soundtrack.music.home.PlaylistCard
import com.soundtrack.music.util.MiniImageLoader

/**
 * 首页"推荐歌单"横向 Rail。
 */
class PlaylistCardAdapter(
    private val loader: MiniImageLoader,
    private val onClick: (PlaylistCard) -> Unit
) : RecyclerView.Adapter<PlaylistCardAdapter.VH>() {

    private val items = mutableListOf<PlaylistCard>()

    fun setData(list: List<PlaylistCard>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist_card, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val cover: ImageView = v.findViewById(R.id.card_cover)
        private val title: TextView = v.findViewById(R.id.card_title)
        private val sub: TextView = v.findViewById(R.id.card_sub)

        fun bind(card: PlaylistCard) {
            title.text = card.name
            // 副标题优先 copywriter，没有再展示播放量
            val subText = when {
                card.copywriter.isNotBlank() -> card.copywriter
                card.playCount > 0L -> "${formatPlayCount(card.playCount)} 播放"
                else -> ""
            }
            sub.text = subText
            loader.load(card.coverUrl, cover)
            itemView.setOnClickListener { onClick(card) }
        }
    }

    companion object {
        private fun formatPlayCount(n: Long): String {
            if (n <= 0) return ""
            return when {
                n >= 100_000_000 -> "${n / 100_000_000}亿"
                n >= 10_000 -> "%.1f万".format(n / 10_000.0)
                else -> n.toString()
            }
        }
    }
}