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
        notifyDataSetChanged()
    }

    fun setActive(index: Int) {
        val old = activeIndex
        activeIndex = index
        notifyItemChanged(old)
        notifyItemChanged(activeIndex)
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
