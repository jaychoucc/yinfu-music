package com.soundtrack.music.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.soundtrack.music.R
import com.soundtrack.music.data.PrefsStore
import com.soundtrack.music.source.BuiltinSources
import com.soundtrack.music.source.SourceResolver

/**
 * 音源管理弹窗：底部弹出，完整展示内置的全部 57 个音源（与 PC 端一致），
 * 按「主力音源 / 国内聚合 / 播客电台 / 海外平台」分组，勾选即启用。
 */
class SourceManageSheet : BottomSheetDialogFragment() {

    private lateinit var adapter: SourceAdapter
    private var onChanged: (() -> Unit)? = null

    fun setOnChanged(cb: () -> Unit) { onChanged = cb }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View? {
        return inflater.inflate(R.layout.sheet_source_manage, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = SourceAdapter()
        recycler.adapter = adapter

        view.findViewById<Button>(R.id.btn_all).setOnClickListener { adapter.toggleAll(true) }
        view.findViewById<Button>(R.id.btn_none).setOnClickListener { adapter.toggleAll(false) }

        load()
    }

    private fun load() {
        val active = PrefsStore(requireContext()).activeSources
        val rows = mutableListOf<Row>()
        val groups = listOf("core", "cn", "radio", "overseas")
        val nativeIds = com.soundtrack.music.source.BuiltinSources.ALL.filter { it.native }.map { it.id }.toSet()
        for (g in groups) {
            val metas = BuiltinSources.ALL.filter { it.group == g }
            if (metas.isEmpty()) continue
            rows.add(Row.Header(BuiltinSources.groupLabel(g)))
            metas.forEach { m ->
                // 未适配的源在结果里显示为灰色 + 不可勾选（保留 UI 让用户知晓）
                val isAvailable = m.id in nativeIds
                rows.add(Row.Item(
                    id = m.id,
                    label = "${m.label}${if (isAvailable) "" else "  ·  未适配"}",
                    // 已适配的源如实标注真实能力：可搜可播 / 仅试听片段 / 仅搜索 / 需配置账号
                    group = if (isAvailable) BuiltinSources.capLabel(m.cap) else "未适配（搜索时跳过）",
                    checked = isAvailable && active.contains(m.id),
                    enabled = isAvailable,
                ))
            }
        }
        adapter.setData(rows)
    }

    override fun onPause() {
        super.onPause()
        val prefs = PrefsStore(requireContext())
        // 只收集已适配且勾选的源
        val checked = adapter.checkedIds()
        val finalSet = if (checked.isEmpty()) setOf("migu") else checked.toSet()
        prefs.activeSources = finalSet
        SourceResolver.refresh()
        onChanged?.invoke()
        Toast.makeText(requireContext(), "已保存：启用 ${finalSet.size} 个已适配音源", Toast.LENGTH_SHORT).show()
    }

    sealed class Row {
        data class Header(val title: String) : Row()
        data class Item(
            val id: String,
            val label: String,
            val group: String,
            val checked: Boolean,
            val enabled: Boolean = true,  // false 时显示为灰色，不响应点击
        ) : Row()
    }

    class SourceAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val rows = mutableListOf<Row>()
        private val checks = linkedMapOf<String, Boolean>()

        fun setData(list: List<Row>) {
            rows.clear(); checks.clear()
            list.forEach { if (it is Row.Item) checks[it.id] = it.checked }
            rows.addAll(list)
            notifyDataSetChanged()
        }

        fun checkedIds(): List<String> = checks.filterValues { it }.keys.toList()

        fun toggleAll(checked: Boolean) {
            checks.keys.forEach { checks[it] = checked }
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val i = LayoutInflater.from(parent.context)
            return if (viewType == 0) HeaderVH(i.inflate(R.layout.item_source_header, parent, false))
            else ItemVH(i.inflate(R.layout.item_source, parent, false))
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            when (val r = rows[position]) {
                is Row.Header -> (h as HeaderVH).bind(r.title)
                is Row.Item -> (h as ItemVH).bind(r, checks[r.id] ?: false) { checks[r.id] = it }
            }
        }

        class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
            fun bind(t: String) { (itemView as TextView).text = t }
        }

        class ItemVH(v: View) : RecyclerView.ViewHolder(v) {
            private val cb: CheckBox = v.findViewById(R.id.check)
            private val label: TextView = v.findViewById(R.id.label)
            private val sub: TextView = v.findViewById(R.id.sub)
            fun bind(r: Row.Item, checked: Boolean, onChange: (Boolean) -> Unit) {
                label.text = r.label
                sub.text = r.group
                // 未适配的源：禁用 checkbox + 灰显
                cb.isEnabled = r.enabled
                label.alpha = if (r.enabled) 1.0f else 0.5f
                sub.alpha = if (r.enabled) 1.0f else 0.5f
                cb.setOnCheckedChangeListener(null)
                cb.isChecked = checked
                cb.setOnCheckedChangeListener { _, c -> if (r.enabled) onChange(c) }
                itemView.setOnClickListener { if (r.enabled) cb.isChecked = !cb.isChecked }
            }
        }
    }
}
