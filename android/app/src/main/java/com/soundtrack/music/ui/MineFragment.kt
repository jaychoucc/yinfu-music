package com.soundtrack.music.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.soundtrack.music.R
import com.soundtrack.music.adapter.DownloadAdapter
import com.soundtrack.music.data.PrefsStore
import com.soundtrack.music.download.DownloadManager
import com.soundtrack.music.source.BuiltinSources
import com.soundtrack.music.source.SourceResolver
import kotlinx.coroutines.launch

class MineFragment : Fragment() {

    private lateinit var adapter: DownloadAdapter
    private lateinit var dm: DownloadManager
    private lateinit var prefs: PrefsStore

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_mine, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dm = DownloadManager(requireContext())
        prefs = PrefsStore(requireContext())
        adapter = DownloadAdapter()
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_downloads)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        view.findViewById<Button>(R.id.btn_clear_cache).setOnClickListener {
            clearCache()
        }

        view.findViewById<Button>(R.id.btn_source_status).setOnClickListener {
            showSourceStatus()
        }
        view.findViewById<Button>(R.id.btn_favorite_playlist).setOnClickListener {
            startActivity(Intent(requireContext(), FavoritePlaylistActivity::class.java))
        }

        loadDownloads()
    }

    override fun onResume() {
        super.onResume()
        loadDownloads()
        view?.findViewById<TextView>(R.id.source_status_text)?.text = sourceStatusText()
    }

    private fun sourceStatusText(): String {
        val total = BuiltinSources.ALL.size
        val ready = BuiltinSources.ALL.count { it.native }
        val playable = BuiltinSources.ALL.count { BuiltinSources.canPlay(it) }
        return "已适配 $ready / $total 个音源 · 其中可搜可播 $playable 个"
    }

    private fun showSourceStatus() {
        val msg = buildString {
            for (cap in BuiltinSources.Cap.values()) {
                val list = BuiltinSources.ALL.filter { it.native && it.cap == cap }
                if (list.isNotEmpty()) {
                    append("${BuiltinSources.capLabel(cap)}（${list.size}）：\n")
                    list.forEach { append("  • ${it.label}\n") }
                    append("\n")
                }
            }
            val pending = BuiltinSources.ALL.filter { !it.native }
            if (pending.isNotEmpty()) {
                append("未适配（${pending.size}）：\n")
                pending.forEach { append("  • ${it.label}\n") }
                append("  搜索时自动跳过\n")
            }
        }
        AlertDialog.Builder(requireContext())
            .setTitle("音源适配进度")
            .setMessage(msg)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun loadDownloads() {
        lifecycleScope.launch {
            adapter.setData(dm.queryDownloads())
        }
    }

    private fun clearCache() {
        try {
            requireContext().cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            Toast.makeText(requireContext(), "缓存已清除", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "清除失败", Toast.LENGTH_SHORT).show()
        }
    }
}
