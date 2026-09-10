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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

class SearchFragment : Fragment() {

    private lateinit var adapter: SongAdapter
    private lateinit var loader: MiniImageLoader
    private lateinit var prefs: PrefsStore
    private lateinit var emptyText: TextView
    private lateinit var sourceChips: ChipGroup
    private var searchJob: Job? = null

    /**
     * 当前搜索期间发起的"解析探测"协程：每次新搜索先全部 cancel，
     * 避免上一轮的探测在 adapter.clear() 之后还在往里加过期条目。
     */
    private val probeJobs = mutableListOf<Job>()

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
     *
     * **过滤策略**：每个源每返回一条 Song，会并发起一个 6s 超时的解析探测；
     * 解析失败的 song 静默丢弃（不进入 adapter），最后统一提示"已过滤 N 首不可播放"。
     * 这样用户看到的都是**真正能播**的条目，避免点击后才 toast "解析失败"的体验黑洞。
     */
    private fun performSearch(keyword: String) {
        if (keyword.isBlank()) return
        prefs.lastSearchKeyword = keyword
        prefs.addHistory(keyword)

        // 取消上一轮搜索：searchJob 取消的是 SourceRegistry.searchAll 的总协程，
        // probeJobs 是各首独立的探测协程，也要一并清掉，避免在 clear() 之后还在往里加。
        searchJob?.cancel()
        probeJobs.forEach { it.cancel() }
        probeJobs.clear()

        adapter.clear()
        emptyText.text = "搜索中…"
        emptyText.visibility = View.VISIBLE

        // 搜索仅跑**已适配**的源（手机端本地，不依赖任何外部服务）
        val allActive = prefs.activeSources.toList().ifEmpty { listOf("migu") }
        val directIds = allActive.filter { SourceResolver.resolve(it) != null }

        // 过滤掉仅搜索 / 需配置的源（这些源点击必失败）
        val playableIds = com.soundtrack.music.source.BuiltinSources.ALL
            .filter { com.soundtrack.music.source.BuiltinSources.canPlay(it) }
            .map { it.id }.toSet()

        // 探测失败的 song 计数：原子操作 + 协程并发安全
        val filteredCount = AtomicInteger(0)

        searchJob = lifecycleScope.launch {
            try {
                SourceResolver.registry.searchAll(keyword, directIds,
                    onEach = { song ->
                        if (song.source !in playableIds) return@searchAll
                        // 每条 song 独立并发探测（≤ 6s 单首超时）；能播才加入 adapter
                        val job = lifecycleScope.launch {
                            val playable = probePlayable(song)
                            if (playable) {
                                adapter.add(song)
                                emptyText.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
                            } else {
                                filteredCount.incrementAndGet()
                            }
                        }
                        probeJobs += job
                    },
                    onSourceDone = { _, _, _ -> }
                )

                // 等待所有探测完成；最坏情况是首首都要打满 6s，但每首都并发，整体 ≤ 7s 兜底
                val snapshot = probeJobs.toList()
                withTimeoutOrNull(7_000L) {
                    snapshot.forEach { it.join() }
                }

                val n = filteredCount.get()
                if (n > 0) {
                    Toast.makeText(requireContext(), "已过滤 $n 首不可播放", Toast.LENGTH_SHORT).show()
                }

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

    /**
     * 探测某条 song 是否能解析出真实播放地址。
     *
     *  - 若 song 已经有 playUrl（部分源在 search 阶段就解析好了），直接视为可播放，省一次网络；
     *  - 否则调一次 resolvePlayUrl，整体 6s 超时；任何异常/超时都算不可播放。
     *
     *  故意不写回 song.playUrl：搜索阶段的探测可能因网络抖动失败，但点击时再解析仍有机会成功。
     */
    private suspend fun probePlayable(song: Song): Boolean {
        if (song.hasPlayUrl) return true
        return withTimeoutOrNull(6_000L) {
            runCatching {
                val src = SourceResolver.resolve(song.source)
                if (src == null) return@runCatching false
                val url = src.resolvePlayUrl(song)
                !url.isNullOrBlank() && url.startsWith("http")
            }.getOrDefault(false)
        } ?: false
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