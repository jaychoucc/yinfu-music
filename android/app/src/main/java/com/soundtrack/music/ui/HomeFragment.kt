package com.soundtrack.music.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
 */
class HomeFragment : Fragment() {

    private lateinit var loader: MiniImageLoader
    private lateinit var playlistAdapter: PlaylistCardAdapter
    private lateinit var newSongAdapter: NewSongAdapter
    private lateinit var toplistAdapter: ToplistCardAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loader = MiniImageLoader(requireContext())

        val banner = view.findViewById<ImageView>(R.id.banner)
        banner.clipToOutline = true

        val loadingHint = view.findViewById<TextView>(R.id.loading_hint)

        // Rail 1：推荐歌单
        val recommendRail = view.findViewById<RecyclerView>(R.id.recommend_rail)
        recommendRail.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        playlistAdapter = PlaylistCardAdapter(loader) { card -> openPlaylistDetail(card) }
        recommendRail.adapter = playlistAdapter

        // Rail 2：新歌速递
        val newSongRail = view.findViewById<RecyclerView>(R.id.newsong_rail)
        newSongRail.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        newSongAdapter = NewSongAdapter(loader) { song -> playSingle(song) }
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
                return@launch
            }

            // 设置三组 adapter
            playlistAdapter.setData(bundle.recommend)
            newSongAdapter.setData(bundle.newSongs)
            toplistAdapter.setData(bundle.toplists)

            // 全空才显示"空"提示；只要有一个有数据就不显示
            if (bundle.recommend.isEmpty() && bundle.newSongs.isEmpty() && bundle.toplists.isEmpty()) {
                loadingHint.visibility = View.VISIBLE
                loadingHint.text = "暂无首页数据，请稍后再试"
            } else {
                loadingHint.visibility = View.GONE
            }
        }
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
}