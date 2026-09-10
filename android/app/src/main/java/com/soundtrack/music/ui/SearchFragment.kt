package com.soundtrack.music.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.soundtrack.music.R
import com.soundtrack.music.adapter.SongAdapter
import com.soundtrack.music.data.PrefsStore
import com.soundtrack.music.download.DownloadManager
import com.soundtrack.music.model.Song
import com.soundtrack.music.player.PlayerRepository
import com.soundtrack.music.source.SourceResolver
import com.soundtrack.music.util.MiniImageLoader
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchFragment : Fragment() {

    private lateinit var adapter: SongAdapter
    private lateinit var loader: MiniImageLoader
    private lateinit var prefs: PrefsStore
    private lateinit var emptyText: TextView
    private lateinit var sourceChips: ChipGroup
    private var searchJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_search, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loader = MiniImageLoader(requireContext())
        prefs = PrefsStore(requireContext())

        emptyText = view.findViewById(R.id.empty_text)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_results)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = SongAdapter(loader,
            onClick = { song -> playSong(song) },
            onDownload = { song -> downloadSong(song) }
        )
        recycler.adapter = adapter

        sourceChips = view.findViewById(R.id.source_chips)
        setupSourceChips()

        val input = view.findViewById<EditText>(R.id.search_input)
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch(input.text.toString())
                true
            } else false
        }
    }

    /**
     * 动态渲染源选择器：从 BuiltinSources 读取所有已适配（native=true）的源，
     * 附加"音源管理"按钮。完全手机端本地，零电脑端依赖。
     */
    private fun setupSourceChips() {
        sourceChips.removeAllViews()
        val ready = com.soundtrack.music.source.BuiltinSources.ALL
            .filter { it.native && it.id in setOf("migu", "netease", "kuwo", "qq") }
        val active = prefs.activeSources
        ready.forEach { meta ->
            val chip = Chip(requireContext()).apply {
                text = meta.label
                isCheckable = true
                isChecked = active.contains(meta.id)
                tag = meta.id
                setOnCheckedChangeListener { _, checked ->
                    val set = prefs.activeSources.toMutableSet()
                    if (checked) set.add(meta.id) else set.remove(meta.id)
                    if (set.isEmpty()) set.add(meta.id)  // 至少保留一个
                    prefs.activeSources = set
                }
            }
            sourceChips.addView(chip)
        }

        // 音源管理按钮：显示已适配/总源进度
        val nativeCount = com.soundtrack.music.source.BuiltinSources.ALL.count { it.native }
        val manageChip = Chip(requireContext()).apply {
            text = "音源管理（$nativeCount/${com.soundtrack.music.source.BuiltinSources.ALL.size}）"
            isCheckable = false
            tag = "manage"
            setOnClickListener { openSourceManager() }
        }
        sourceChips.addView(manageChip)
    }

    private fun openSourceManager() {
        // 直接弹出底部音源勾选弹窗（不依赖电脑端，直接源即可勾选）
        val sheet = SourceManageSheet().apply {
            setOnChanged { setupSourceChips() }
        }
        sheet.show(parentFragmentManager, "source_manage")
    }

    override fun onResume() {
        super.onResume()
        // 从音源管理页返回后刷新 chips
        setupSourceChips()
    }

    /**
     * 搜索：完全手机端本地执行，仅跑已适配的原生源，
     * 不依赖任何电脑端 / 代理 / 外网。
     */
    private fun performSearch(keyword: String) {
        if (keyword.isBlank()) return
        prefs.lastSearchKeyword = keyword
        prefs.addHistory(keyword)
        adapter.clear()
        emptyText.text = "搜索中…"
        searchJob?.cancel()

        // 搜索仅跑**已适配**的源（手机端本地，不依赖任何外部服务）
        val allActive = prefs.activeSources.toList().ifEmpty { listOf("migu") }
        val directIds = allActive.filter { SourceResolver.resolve(it) != null }

        searchJob = lifecycleScope.launch {
            try {
                // 搜索结果只展示**可播放**的源（FULL + PREVIEW），过滤掉仅搜索 / 需配置的源，
                // 避免出现"搜到了但点不动"的体验黑洞。
                val playableIds = com.soundtrack.music.source.BuiltinSources.ALL
                    .filter { com.soundtrack.music.source.BuiltinSources.canPlay(it) }
                    .map { it.id }.toSet()
                SourceResolver.registry.searchAll(keyword, directIds,
                    onEach = { song ->
                        if (song.source !in playableIds) return@searchAll
                        adapter.add(song)
                        emptyText.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
                    },
                    onSourceDone = { _, _, _ -> }
                )
                emptyText.text = when {
                    adapter.itemCount > 0 -> ""
                    directIds.isEmpty() -> "请在音源管理里启用至少一个音源"
                    else -> "未找到结果（夸克网盘/需登录的源已自动过滤）"
                }
                emptyText.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "搜索失败：${e.message}", Toast.LENGTH_SHORT).show()
                emptyText.text = "搜索失败"
            }
        }
    }

    private fun playSong(song: Song) {
        val repo = PlayerRepository.get(requireContext())
        val list = adapter.getItems()
        val index = list.indexOfFirst { it.songId == song.songId && it.source == song.source }
        if (index >= 0) {
            repo.play(list, index)
        } else {
            repo.play(listOf(song), 0)
        }
        startActivity(Intent(requireContext(), PlayerActivity::class.java))
    }

    private fun downloadSong(song: Song) {
        lifecycleScope.launch {
            val uri = DownloadManager(requireContext()).download(song)
            Toast.makeText(requireContext(), if (uri != null) "已下载" else "下载失败", Toast.LENGTH_SHORT).show()
        }
    }
}
