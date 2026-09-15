package com.soundtrack.music.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.soundtrack.music.R
import com.soundtrack.music.adapter.NewSongAdapter
import com.soundtrack.music.adapter.PlaylistCardAdapter
import com.soundtrack.music.adapter.ToplistCardAdapter
import com.soundtrack.music.home.HomeRepository
import com.soundtrack.music.home.PlaylistCard
import com.soundtrack.music.home.ToplistCard
import com.soundtrack.music.player.PlayerRepository
import com.soundtrack.music.util.MiniImageLoader
import kotlinx.coroutines.launch

/**
 * 首页"发现"页：三个横向 Rail（推荐歌单 / 新歌速递 / 排行榜）。
 * 数据由 HomeRepository 一次性并行拉取；失败时只用 loading_hint 文字提示，绝不 toast。
 *
 * 顶部"发现好音乐"区域现在是一张推荐歌单头条卡片：封面 + 标题 + 角标 + 副标题，
 * 取自 `HomeRepository.loadHome().recommend[0]`；点击跳详情页。
 */
class HomeFragment : Fragment() {

    private lateinit var loader: MiniImageLoader
    private lateinit var playlistAdapter: PlaylistCardAdapter
    private lateinit var newSongAdapter: NewSongAdapter
    private lateinit var toplistAdapter: ToplistCardAdapter

    // 顶部推荐头条相关 View
    private lateinit var banner: LinearLayout
    private lateinit var bannerCover: ImageView
    private lateinit var bannerTitle: TextView
    private lateinit var bannerBadge: TextView
    private lateinit var bannerSub: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loader = MiniImageLoader(requireContext())

        // 顶部推荐头条：LinearLayout 容器 + 封面/标题/角标/副标题
        banner = view.findViewById(R.id.banner)
        bannerCover = view.findViewById(R.id.banner_cover)
        bannerTitle = view.findViewById(R.id.banner_title)
        bannerBadge = view.findViewById(R.id.banner_badge)
        bannerSub = view.findViewById(R.id.banner_sub)

        val loadingHint = view.findViewById<TextView>(R.id.loading_hint)

        // Rail 1：推荐歌单
        val recommendRail = view.findViewById<RecyclerView>(R.id.recommend_rail)
        recommendRail.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        playlistAdapter = PlaylistCardAdapter(loader) { card -> openPlaylistDetail(card) }
        recommendRail.adapter = playlistAdapter

        // Rail 2：新歌速递
        val newSongRail = view.findViewById<RecyclerView>(R.id.newsong_rail)
        newSongRail.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        newSongAdapter = NewSongAdapter(
            loader,
            { song -> playSingle(song) },
            { song -> openAddToPlaylist(song) }
        )
        newSongRail.adapter = newSongAdapter

        // Rail 3：排行榜
        val toplistRail = view.findViewById<RecyclerView>(R.id.toplist_rail)
        toplistRail.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        toplistAdapter = ToplistCardAdapter(loader) { card -> openToplistDetail(card) }
        toplistRail.adapter = toplistAdapter

        // 加载首页
        loadingHint.visibility = View.VISIBLE
        loadingHint.text = "正在加载首页…"
        lifecycleScope.launch {
            val bundle = try {
                HomeRepository().loadHome()
            } catch (t: Throwable) {
                // 任何异常都吞掉，UI 仅用文字提示，不 toast
                loadingHint.visibility = View.VISIBLE
                loadingHint.text = "首页数据加载失败，请检查网络"
                bindBanner(emptyList())
                return@launch
            }

            // 设置三组 adapter
            // 推荐 Rail：去掉 banner 已经展示的那一张（recommend[0]），避免重复。
            //  - 当 recommend 只有 1 张时 drop(1) = emptyList() → banner 显示这张，Rail 空（适配器 setData(emptyList) 正常）。
            //  - 当 recommend 为空时 drop(1) = emptyList() → 两侧都走空状态分支。
            // 不动 HomeRepository.loadHome()，让 UI 层决定怎么分。
            playlistAdapter.setData(bundle.recommend.drop(1))
            newSongAdapter.setData(bundle.newSongs)
            toplistAdapter.setData(bundle.toplists)

            // 顶部推荐头条卡片
            bindBanner(bundle.recommend)

            // 全空才显示"空"提示；只要有一个有数据就不显示
            if (bundle.recommend.isEmpty() && bundle.newSongs.isEmpty() && bundle.toplists.isEmpty()) {
                loadingHint.visibility = View.VISIBLE
                loadingHint.text = "暂无首页数据，请稍后再试"
            } else {
                loadingHint.visibility = View.GONE
            }
        }
    }

    /**
     * 绑定顶部"推荐歌单头条"卡片。
     *
     *  - 取第一张推荐歌单（`recommend[0]`）作为头条；
     *  - 封面用 MiniImageLoader 异步加载（先显示底层渐变背景占位）；
     *  - 标题 = 歌单 name；副标题 = copywriter，空时回退 name；
     *  - 右上角角标固定"每日推荐"；
     *  - 点击 → 跳歌单详情（`is_toplist=false`）。
     *
     *  空列表时：清掉封面图，仅显示占位渐变；标题/角标/副标题全部隐藏；点击无效。
     */
    private fun bindBanner(recommend: List<PlaylistCard>) {
        val head = recommend.firstOrNull()
        if (head == null) {
            bannerCover.setImageDrawable(null)
            bannerTitle.visibility = View.GONE
            bannerBadge.visibility = View.GONE
            bannerSub.visibility = View.GONE
            banner.setOnClickListener(null)
            banner.isClickable = false
            return
        }

        loader.load(head.coverUrl, bannerCover)
        bannerTitle.text = head.name
        bannerTitle.visibility = View.VISIBLE
        bannerBadge.visibility = View.VISIBLE
        // 副标题：copywriter 优先；空时用 name 兜底，避免出现"标题下面是空"
        bannerSub.text = if (head.copywriter.isNotBlank()) head.copywriter else head.name
        bannerSub.visibility = View.VISIBLE
        banner.isClickable = true
        banner.setOnClickListener { openPlaylistDetail(head) }
    }

    private fun openPlaylistDetail(card: PlaylistCard) {
        val intent = Intent(requireContext(), PlaylistDetailActivity::class.java).apply {
            putExtra("playlist_id", card.id)
            putExtra("playlist_name", card.name)
            putExtra("is_toplist", false)
        }
        startActivity(intent)
    }

    private fun openToplistDetail(card: ToplistCard) {
        val intent = Intent(requireContext(), PlaylistDetailActivity::class.java).apply {
            putExtra("playlist_id", card.id)
            putExtra("playlist_name", card.name)
            putExtra("is_toplist", true)
        }
        startActivity(intent)
    }

    /**
     * 新歌单首直接播放：构造只含这一首的队列，从 index 0 起播。
     */
    private fun playSingle(song: com.soundtrack.music.model.Song) {
        PlayerRepository.get(requireContext()).play(listOf(song), 0)
        startActivity(Intent(requireContext(), PlayerActivity::class.java))
    }

    /** 长按首页新歌 → 打开「加入歌单」选择器（AC-15）。 */
    private fun openAddToPlaylist(song: com.soundtrack.music.model.Song) {
        AddToPlaylistSheet.newInstance(song)
            .show(parentFragmentManager, "add_to_playlist")
    }
}