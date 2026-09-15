package com.soundtrack.music.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.soundtrack.music.R
import com.soundtrack.music.data.LocalPlaylist
import com.soundtrack.music.util.MiniImageLoader

/**
 * 「加入歌单」选择器（S5）复选适配器。
 *
 * 勾选态在 adapter 内部维护（`checked`），整行点击即切换勾选；`checkedIds()` 供确认时一次性写入。
 * 行首封面由 [coverOf] 按「歌单内最新加入那首歌的封面」口径解析，无封面则保留 XML 上的渐变占位。
 */
class PlaylistPickAdapter(
    private val loader: MiniImageLoader,
    /** 歌单封面 URL 解析器（末尾可选，默认 null = 不加载封面、保留渐变占位）。口径由调用方给出。 */
    private val coverOf: ((LocalPlaylist) -> String?)? = null
) : RecyclerView.Adapter<PlaylistPickAdapter.VH>() {

    private val items = mutableListOf<LocalPlaylist>()
    private val checked = linkedSetOf<String>()

    /** 重新灌入歌单列表并按 [checkedIds] 初始化勾选态。 */
    fun setData(list: List<LocalPlaylist>, checkedIds: Set<String>) {
        items.clear()
        items.addAll(list)
        checked.clear()
        checked.addAll(checkedIds)
        notifyDataSetChanged()
    }

    fun checkedIds(): Set<String> = checked.toSet()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_pick_playlist, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val check: CheckBox = v.findViewById(R.id.check)
        private val cover: ImageView = v.findViewById(R.id.cover)
        private val name: TextView = v.findViewById(R.id.name)

        fun bind(p: LocalPlaylist) {
            name.text = p.name
            // 封面：先清掉复用残留（ViewHolder 复用会把上一行的图带过来），
            // 清空后露出 XML 上的渐变占位背景；有封面再异步加载覆盖上去。
            cover.setImageDrawable(null)
            val coverUrl = coverOf?.invoke(p)
            if (!coverUrl.isNullOrBlank()) loader.load(coverUrl, cover)
            // 先摘掉监听再设值，避免复用时触发旧回调
            check.setOnCheckedChangeListener(null)
            check.isChecked = checked.contains(p.id)
            check.setOnCheckedChangeListener { _, c ->
                if (c) checked.add(p.id) else checked.remove(p.id)
            }
            // 整行点击 = 切换勾选（子 CheckBox 独立可点，互不干扰）
            itemView.setOnClickListener { check.isChecked = !check.isChecked }
        }
    }
}
