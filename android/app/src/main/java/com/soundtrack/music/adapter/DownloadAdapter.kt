package com.soundtrack.music.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.soundtrack.music.R
import com.soundtrack.music.download.DownloadManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloadAdapter : RecyclerView.Adapter<DownloadAdapter.VH>() {
    private val items = mutableListOf<DownloadManager.DownloadItem>()

    fun setData(list: List<DownloadManager.DownloadItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val title: TextView = v.findViewById(R.id.title)
        private val time: TextView = v.findViewById(R.id.time)

        fun bind(item: DownloadManager.DownloadItem) {
            title.text = item.name
            time.text = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.added * 1000))
        }
    }
}
