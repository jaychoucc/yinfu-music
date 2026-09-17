package com.soundtrack.music.ui

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.soundtrack.music.R
import com.soundtrack.music.adapter.MyPlaylistAdapter
import com.soundtrack.music.data.LocalPlaylist
import com.soundtrack.music.data.PlaylistModels
import com.soundtrack.music.data.PlaylistStore
import com.soundtrack.music.import_.ImportResult
import com.soundtrack.music.import_.PlaylistImporter
import com.soundtrack.music.util.MiniImageLoader
import com.soundtrack.music.util.PlaylistNameDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 我的歌单列表页（S3）。
 *
 *  - 默认歌单恒置顶，自建歌单按创建时间倒序
 *  - 顶栏「+」新建、「⭳」导入网易云歌单；自建行可重命名 / 删除（二次确认）
 *  - 点击行进入 S4 详情页；「导入失败歌曲」内置歌单进入失败列表模式
 *  - onResume 重读：从详情页返回刷新歌曲数
 */
class MyPlaylistsActivity : AppCompatActivity() {

    private lateinit var store: PlaylistStore
    private lateinit var adapter: MyPlaylistAdapter
    private lateinit var miniPlayer: MiniPlayerController
    private var importJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_my_playlists)
        store = PlaylistStore.get(this)

        miniPlayer = MiniPlayerController(this, findViewById(R.id.mini_player))
        miniPlayer.bind()

        val btnBack = findViewById<ImageButton>(R.id.btn_back)
        val btnNew = findViewById<ImageButton>(R.id.btn_new_playlist)
        val btnImport = findViewById<ImageButton>(R.id.btn_import_playlist)
        val recycler = findViewById<RecyclerView>(R.id.recycler_playlists)

        btnBack.setOnClickListener { finish() }
        btnNew.setOnClickListener {
            PlaylistNameDialog.showNew(this, store) { reload() }
        }
        btnImport.setOnClickListener { showImportDialog() }

        recycler.layoutManager = LinearLayoutManager(this)
        adapter = MyPlaylistAdapter(
            loader = MiniImageLoader(this),
            onClick = { p -> openDetail(p) },
            onRename = { p -> PlaylistNameDialog.showRename(this, store, p) { reload() } },
            onDelete = { p -> confirmDelete(p) },
            coverOf = { p -> store.coverUrlOf(p.id) },
            countOf = { p ->
                // 「导入失败歌曲」内置歌单本身不放歌曲条目，计数走聚合的失败条目
                if (p.id == PlaylistModels.FAILED_PLAYLIST_ID) store.allMisses().size else p.count
            }
        )
        recycler.adapter = adapter

        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val all = store.playlists()
        // 默认歌单恒置顶；「导入失败歌曲」内置歌单第二；自建歌单按创建时间倒序
        val def = all.firstOrNull { it.id == PlaylistModels.DEFAULT_PLAYLIST_ID }
        val failed = all.firstOrNull { it.id == PlaylistModels.FAILED_PLAYLIST_ID }
        val custom = all.filter { !it.builtin }.sortedByDescending { it.createdAt }
        val ordered = buildList {
            if (def != null) add(def)
            if (failed != null) add(failed)
            addAll(custom)
        }
        adapter.setData(ordered)
    }

    private fun openDetail(p: LocalPlaylist) {
        val intent = Intent(this, MyPlaylistDetailActivity::class.java).apply {
            putExtra(PlaylistModels.EXTRA_LOCAL_PLAYLIST_ID, p.id)
            putExtra(PlaylistModels.EXTRA_LOCAL_PLAYLIST_NAME, p.name)
        }
        startActivity(intent)
    }

    private fun confirmDelete(p: LocalPlaylist) {
        AlertDialog.Builder(this)
            .setTitle("删除歌单")
            .setMessage("确定要删除「${p.name}」吗？其中的歌曲会一并移除。")
            .setPositiveButton("删除") { _, _ ->
                store.deletePlaylist(p.id)
                reload()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // —— 网易云歌单导入 ——

    /** 导入入口对话框：粘贴链接或 ID → 校验 → 进度对话框。 */
    private fun showImportDialog() {
        if (importJob?.isActive == true) {
            toast("已有导入正在进行中")
            return
        }
        val edit = EditText(this).apply {
            hint = "粘贴网易云歌单链接或 ID"
            val pad = (14 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(this)
            .setTitle("导入网易云歌单")
            .setView(edit)
            .setPositiveButton("导入") { _, _ ->
                val raw = edit.text.toString().trim()
                if (PlaylistImporter.extractPlaylistId(raw) == null) {
                    toast("链接格式不正确")
                    return@setPositiveButton
                }
                startImport(raw)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 执行导入：不可取消的进度对话框（按钮「取消」可取消协程），
     * 完成后弹结果对话框，有失败条目时附「查看失败列表」按钮跳到 import_failed 详情页。
     */
    private fun startImport(urlOrId: String) {
        // 协程外创建并 show，保证 UI 线程上创建对话框
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("正在导入歌单")
            .setMessage("正在解析歌单…")
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ -> importJob?.cancel() }
            .create()
        progressDialog.show()

        importJob = lifecycleScope.launch {
            // runCatching 连 CancellationException 一并捕获，靠下面的 isActive 判取消分支
            val outcome = runCatching {
                PlaylistImporter.importNeteasePlaylist(
                    context = this@MyPlaylistsActivity,
                    urlOrId = urlOrId,
                    onProgress = { done, total, title ->
                        if (!isFinishing && !isDestroyed && progressDialog.isShowing) {
                            progressDialog.setMessage("$done/$total · 正在匹配《$title》")
                        }
                    },
                    onPlaylistCreated = { }
                )
            }
            if (progressDialog.isShowing) progressDialog.dismiss()
            if (!isActive) {
                // 用户取消
                toast("已取消导入")
                if (!isFinishing && !isDestroyed) reload()
                return@launch
            }
            if (isFinishing || isDestroyed) return@launch
            val result = outcome.getOrNull()
            if (result == null) {
                toast("歌单解析失败，可能是链接错误或需要登录")
                reload()
                return@launch
            }
            showImportResult(result)
            reload()
        }
    }

    /** 导入完成结果对话框；有失败条目时附「查看失败列表」。 */
    private fun showImportResult(result: ImportResult) {
        // 重复导入时 matched 只统计「本次新增」，已存在的部分单独列 sketched，避免用户误以为重复入单
        val matchedLine = if (result.total > 0) {
            "成功 ${result.matched}/${result.total} 首" +
                if (result.total - result.matched - result.misses.size > 0)
                    "（${result.total - result.matched - result.misses.size} 首已存在，已跳过）"
                else ""
        } else "空歌单"
        val builder = AlertDialog.Builder(this)
            .setTitle("导入完成")
            .setMessage(
                "歌单「${result.playlistName}」\n$matchedLine" +
                    if (result.misses.isNotEmpty()) "\n失败 ${result.misses.size} 首" else ""
            )
        if (result.misses.isNotEmpty()) {
            builder.setPositiveButton("查看失败列表") { _, _ ->
                startActivity(
                    Intent(this, MyPlaylistDetailActivity::class.java).apply {
                        putExtra(PlaylistModels.EXTRA_LOCAL_PLAYLIST_ID, PlaylistModels.FAILED_PLAYLIST_ID)
                        putExtra(PlaylistModels.EXTRA_LOCAL_PLAYLIST_NAME, PlaylistModels.FAILED_PLAYLIST_NAME)
                    }
                )
            }
            builder.setNegativeButton("关闭", null)
        } else {
            builder.setPositiveButton("好的", null)
        }
        builder.show()
    }

    private fun toast(msg: String) {
        if (!isFinishing && !isDestroyed) {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
