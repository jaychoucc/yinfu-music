package com.soundtrack.music.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.soundtrack.music.R
import com.soundtrack.music.adapter.MyPlaylistAdapter
import com.soundtrack.music.data.LocalPlaylist
import com.soundtrack.music.data.PlaylistModels
import com.soundtrack.music.data.PlaylistStore
import com.soundtrack.music.import_.ImportEngine
import com.soundtrack.music.import_.ImportResult
import com.soundtrack.music.import_.PlaylistImportService
import com.soundtrack.music.import_.PlaylistImporter
import com.soundtrack.music.util.MiniImageLoader
import com.soundtrack.music.util.PlaylistNameDialog
import kotlinx.coroutines.launch

/**
 * 我的歌单列表页（S3）。
 *
 *  - 默认歌单恒置顶，自建歌单按创建时间倒序
 *  - 顶栏「+」新建、「⭳」导入网易云歌单；自建行可重命名 / 删除（二次确认）
 *  - 点击行进入 S4 详情页；「导入失败歌曲」内置歌单进入失败列表模式
 *  - onResume 重读：从详情页返回刷新歌曲数
 *
 * 导入与页面的关系（2026-09-18 重构）：导入跑在 [PlaylistImportService] 前台服务里，
 * 不再挂在 lifecycleScope 上，所以页面被回收 / 切后台都不会中断导入。
 * 「导入」按钮的行为是双重的：
 *  - 没有导入在跑 → 弹输入框，开始一次新导入；
 *  - 有导入在跑（或结果还没被消费）→ 直接恢复进度弹窗（即「从导入按钮恢复最大化」）。
 */
class MyPlaylistsActivity : AppCompatActivity() {

    /** 真机自检深链：`am start --es auto_import_url <链接|ID>` 直接触发导入（绕开 input tap 落点问题）。 */
    companion object {
        private const val EXTRA_AUTO_IMPORT = "auto_import_url"
    }

    private lateinit var store: PlaylistStore
    private lateinit var adapter: MyPlaylistAdapter
    private lateinit var miniPlayer: MiniPlayerController

    private var progressDialog: AlertDialog? = null
    private var progressView: android.view.View? = null
    private var resultShown = false

    /** 等通知权限结果的导入 url；授权与否都继续导入，通知只是后台进度的可见性增强。 */
    private var pendingImportUrl: String? = null
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val url = pendingImportUrl
        pendingImportUrl = null
        if (!granted) {
            // 导入照常进行，但通知栏看不到后台进度；页面内进度弹窗与「导入」按钮恢复均不受影响
            toast("未授权通知，后台导入时通知栏不显示进度，可在设置中开启")
        }
        if (url != null) startImportNow(url)
    }

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
        // 恢复最大化入口：导入中 / 有未消费结果 → 恢复进度弹窗；否则正常发起新导入
        btnImport.setOnClickListener { onImportButtonClicked() }

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

        // 真机自检：带 auto_import_url 启动时直接开始导入（不弹输入框，链路完全一致）
        intent?.getStringExtra(EXTRA_AUTO_IMPORT)?.let { url ->
            if (PlaylistImporter.extractPlaylistId(url) != null) startImport(url)
            else toast("链接格式不正确")
        }

        // 订阅导入状态：服务在后台推进时，页面打开着就实时刷弹窗；销毁后重建仍能接上
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ImportEngine.state.collect { state -> renderImportState(state) }
            }
        }
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

    /** 「导入」按钮：有导入在跑就恢复弹窗，否则弹输入框发起新导入。 */
    private fun onImportButtonClicked() {
        val state = ImportEngine.state.value
        if (state != null) {
            // 导入中（或结果尚未被消费）→ 恢复进度弹窗
            showProgressDialog()
            return
        }
        showImportUrlDialog()
    }

    /** 导入入口对话框：粘贴链接或 ID → 校验 → 交给前台服务导入。 */
    private fun showImportUrlDialog() {
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
     * 启动前台服务导入，并立刻弹出进度对话框（最小化后服务继续在后台跑）。
     * Android 13+ 先请求通知权限：后台导入的进度通知需要它才能显示。
     * 无论授权与否导入都会开始——通知只影响后台进度的可见性。
     */
    private fun startImport(urlOrId: String) {
        resultShown = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingImportUrl = urlOrId
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startImportNow(urlOrId)
        }
    }

    private fun startImportNow(urlOrId: String) {
        PlaylistImportService.start(this, urlOrId)
        showProgressDialog()
    }

    /**
     * 进度对话框：不可取消（避免误触吞掉导入），三个按钮各司其职：
     *  - 最小化：关掉弹窗，导入在服务里继续跑，通知栏仍有进度与「取消」
     *  - 取消：终止本次导入（已匹配的歌曲会保留）
     *  - 完成：由 [renderImportState] 在终态时自动 dismiss 并换结果框
     */
    private fun showProgressDialog() {
        progressDialog?.let { if (it.isShowing) return }
        val view = layoutInflater.inflate(R.layout.dialog_import_progress, null)
        val dialog = AlertDialog.Builder(this)
            .setTitle("正在导入歌单")
            .setView(view)
            .setCancelable(false)
            .setPositiveButton("最小化") { _, _ ->
                // 弹窗关掉即可，服务不受影响；通知栏点按或「导入」按钮都能再回到这个弹窗
            }
            .setNegativeButton("取消") { _, _ ->
                PlaylistImportService.cancel(this)
            }
            .create()
        progressView = view
        progressDialog = dialog
        renderImportState(ImportEngine.state.value)
        dialog.show()
    }

    /**
     * 把 [ImportEngine] 的状态渲染到进度弹窗（若正在显示）。
     *
     * 关键：进度条与「已匹配」计数只增不减，跳过的歌进「跳过」计数、不进「已匹配」，
     * 避免旧版「33 → 41」式的数字跳变。
     */
    private fun renderImportState(state: ImportEngine.State?) {
        val dialog = progressDialog
        if (state == null) {
            // 空闲：可能是别处清空了状态，弹窗不应还在
            dialog?.takeIf { it.isShowing }?.dismiss()
            progressDialog = null
            progressView = null
            return
        }
        if (!state.running) {
            // 终态：关进度弹窗、弹结果框（各生命周期下只弹一次）
            dialog?.takeIf { it.isShowing }?.dismiss()
            progressDialog = null
            progressView = null
            if (!resultShown) {
                resultShown = true
                showTerminalState(state)
            }
            return
        }
        // 运行中：弹窗没打开就不强行弹（用户可能正在看别的页面）
        if (dialog == null || !dialog.isShowing) return
        // 歌单名解析出来后把标题换成带名字的形式
        if (state.playlistName.isNotBlank()) {
            dialog.setTitle("正在导入歌单·${state.playlistName}")
        }
        val view = progressView ?: return
        val pb = view.findViewById<ProgressBar>(R.id.pb_import)
        val tvHead = view.findViewById<TextView>(R.id.tv_import_head)
        val tvDetail = view.findViewById<TextView>(R.id.tv_import_detail)

        if (state.total > 0) {
            pb.isIndeterminate = false
            pb.max = state.total
            // 平滑动画承接进度条，进一步掩盖「跳过」造成的离散跳变
            pb.setProgress(state.done, true)
            tvHead.text = "已匹配 ${state.matched} / ${state.total} 首"
        } else {
            pb.isIndeterminate = true
            tvHead.text = "已匹配 ${state.matched} 首"
        }
        val detail = buildString {
            if (state.skipped > 0) append("跳过 ${state.skipped} 首")
            if (state.missed > 0) {
                if (isNotEmpty()) append(" · ")
                append("未匹配 ${state.missed} 首")
            }
        }
        tvDetail.text = if (detail.isEmpty()) {
            if (state.title.isNotBlank()) "正在匹配《${state.title}》" else "正在解析歌单…"
        } else {
            detail + if (state.title.isNotBlank()) " · 《${state.title}》" else ""
        }
    }

    /** 终态统一出口：取消 / 失败 / 完成 三种情况分别提示，完成后清空引擎状态。 */
    private fun showTerminalState(state: ImportEngine.State) {
        when {
            state.cancelled -> {
                toast("已取消导入，已匹配的歌曲会保留")
                ImportEngine.consume()
                reload()
            }
            state.failed || state.result == null -> {
                toast("歌单解析失败，可能是链接错误或需要登录")
                ImportEngine.consume()
                reload()
            }
            else -> {
                showImportResult(state.result!!)
                // 结果框由用户关闭后再清空状态，避免通知重复弹结果
                ImportEngine.consume()
                reload()
            }
        }
    }

    /** 导入完成结果对话框；有失败条目时附「查看失败列表」。 */
    private fun showImportResult(result: ImportResult) {
        // 重复导入时 matched 只统计「本次新增」，已存在的部分单独列，避免用户误以为重复入单
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
            .setOnDismissListener { }
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
