package com.soundtrack.music.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.soundtrack.music.R

/**
 * 搜索历史列表适配器。
 *
 * 数据源是纯粹的字符串关键词列表（最近在前，≤ 20 条，由 [com.soundtrack.music.data.PrefsStore]
 * 保序存储）。继承 [ListAdapter] 走 [DiffUtil] 做局部刷新，删除 / 新增时不会整列表闪烁；
 * 数据量极小（≤ 20），DiffUtil 计算开销可忽略，且不引入任何新依赖。
 *
 * 两个点击回调互不干扰：
 *  - 整行（[VH.itemView]）点击 → [onClick]，用该词发起搜索；
 *  - 行尾 ✕（[VH.btnDelete]）点击 → [onDelete]，删单条。
 * ✕ 按钮单独设置了自己的 OnClickListener，覆盖父布局 itemView 的点击事件，
 * 点击落在 ✕ 上时不会冒泡到整行（Android 事件分发：子 View 消费了点击，父布局不再收到）。
 */
class SearchHistoryAdapter(
    private val onClick: (String) -> Unit,
    private val onDelete: (String) -> Unit
) : ListAdapter<String, SearchHistoryAdapter.VH>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_history, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val keyword: TextView = v.findViewById(R.id.history_keyword)
        internal val btnDelete: ImageView = v.findViewById(R.id.btn_delete_history)

        fun bind(text: String) {
            keyword.text = text
            // 整行点击 = 用该词搜索
            itemView.setOnClickListener { onClick(text) }
            // ✕ 点击 = 删单条；独立监听器覆盖 itemView 的点击，不会冒泡
            btnDelete.setOnClickListener { onDelete(text) }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(oldItem: String, newItem: String): Boolean = oldItem == newItem

            override fun areContentsTheSame(oldItem: String, newItem: String): Boolean = oldItem == newItem
        }
    }
}
