package com.soundtrack.music.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.soundtrack.music.R
import com.soundtrack.music.player.LrcParser

class LyricAdapter : RecyclerView.Adapter<LyricAdapter.VH>() {
    private val lines = mutableListOf<LrcParser.Line>()
    private var activeIndex = -1
    var onLineClick: ((Long) -> Unit)? = null

    fun setData(data: List<LrcParser.Line>) {
        lines.clear()
        lines.addAll(data)
        // 必须复位高亮下标：切歌后旧的下标会跨列表生效，
        // 导致新歌一进来就高亮在错误的行上（甚至越界）。
        activeIndex = -1
        notifyDataSetChanged()
    }

    /**
     * 高亮某一行。
     *
     * ⚠️ 旧实现无条件 `notifyItemChanged(old)`，而 activeIndex 初始为 -1，
     * 播放页每 300ms 就会带着 -1 调一次，RecyclerView 会抛 IndexOutOfBoundsException
     * （"Invalid view holder adapter position"）导致播放过程中闪退。
     * 这里同时做了位置合法性与"无变化则跳过"的兜底。
     */
    fun setActive(index: Int) {
        if (index == activeIndex) return
        val old = activeIndex
        activeIndex = index
        if (old in lines.indices) notifyItemChanged(old)
        if (index in lines.indices) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_lyric_line, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = lines.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(lines[position], position == activeIndex)
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val text: TextView = v.findViewById(R.id.lyric_text)

        fun bind(line: LrcParser.Line, active: Boolean) {
            text.text = line.text.ifBlank { "·" }
            text.setTextColor(if (active) 0xFFFFFFFF.toInt() else 0xFF9A9A9A.toInt())
            text.textSize = if (active) 18f else 14f
            text.alpha = if (active) 1f else 0.6f
            itemView.setOnClickListener { onLineClick?.invoke(line.timeMs) }
        }
    }
}
