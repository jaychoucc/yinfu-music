package com.soundtrack.music.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.soundtrack.music.R
import com.soundtrack.music.adapter.PlaylistPickAdapter
import com.soundtrack.music.data.PlaylistStore
import com.soundtrack.music.model.Song
import com.soundtrack.music.model.SongKeys
import com.soundtrack.music.util.MiniImageLoader
import com.soundtrack.music.util.PlaylistNameDialog

/**
 * 「加入歌单」选择器（S5，底部弹层）。
 *
 *  - 列出**全部**歌单（含默认歌单），已包含该歌的显示为已勾选（AC-16）
 *  - 一首歌可同时属于多张歌单（AC-17/18）
 *  - 确认后 `setMembership` 一次性写入（差量增删），点「取消」/点外部不产生任何写入（AC-20）
 *  - 行内「+ 新建歌单」→ 创建成功后自动勾选新歌单（AC-19）
 *
 * `setOnChanged` 范式照抄 `SourceManageSheet`。
 */
class AddToPlaylistSheet : BottomSheetDialogFragment() {

    private lateinit var adapter: PlaylistPickAdapter
    private var onChanged: (() -> Unit)? = null
    private var song: Song? = null

    fun setOnChanged(cb: () -> Unit) {
        onChanged = cb
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        song = arguments?.getSerializable(ARG_SONG) as? Song
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View? {
        return inflater.inflate(R.layout.sheet_add_to_playlist, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val store = PlaylistStore.get(requireContext())
        val target = song

        view.findViewById<TextView>(R.id.song_label).text =
            if (target != null) "${target.title} - ${target.artist}" else "未选择歌曲"

        val recycler = view.findViewById<RecyclerView>(R.id.recycler_pick_list)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = PlaylistPickAdapter(
            loader = MiniImageLoader(requireContext()),
            coverOf = { p -> store.coverUrlOf(p.id) }
        )
        recycler.adapter = adapter

        val key = target?.let { SongKeys.of(it) }
        val checkedIds = if (key != null) store.playlistIdsContaining(key) else emptySet()
        adapter.setData(store.playlists(), checkedIds)

        view.findViewById<TextView>(R.id.btn_new_in_picker).setOnClickListener {
            PlaylistNameDialog.showNew(requireContext(), store) { newId ->
                // 新建成功后：刷新列表并自动勾选新歌单（保留用户此前的勾选）
                val add = if (newId != null) setOf(newId) else emptySet()
                adapter.setData(store.playlists(), adapter.checkedIds() + add)
            }
        }

        view.findViewById<Button>(R.id.btn_confirm).setOnClickListener {
            val s = song
            if (s != null) {
                // 一次性差量写入（AC-17/18/20）
                store.setMembership(s, adapter.checkedIds())
                onChanged?.invoke()
            }
            dismiss()
        }

        // 取消 / 点外部 → 不写入任何数据（AC-20）
        view.findViewById<Button>(R.id.btn_cancel).setOnClickListener { dismiss() }
    }

    companion object {
        private const val ARG_SONG = "song"

        fun newInstance(song: Song): AddToPlaylistSheet = AddToPlaylistSheet().apply {
            arguments = Bundle().apply { putSerializable(ARG_SONG, song) }
        }
    }
}
