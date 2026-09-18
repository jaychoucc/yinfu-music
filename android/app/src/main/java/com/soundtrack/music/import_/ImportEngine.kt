package com.soundtrack.music.import_

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 导入状态广播中心（进程内单例）。
 *
 * **为什么需要它**：导入最早跑在 [com.soundtrack.music.ui.MyPlaylistsActivity] 的
 * `lifecycleScope` 里，页面被系统回收 / 用户切走，导入协程随之被取消，
 * 已匹配的歌曲条目会停在某个中间数（如 244/657），既不继续也不写 misses。
 * 把导入挪进前台 Service 后，Service 与 UI 需要一个都不持有对方引用的地方交换进度，
 * 本对象就是这个通道：Service 写 [state]，UI 收 [state]。
 *
 * **状态机**：
 *  - null：空闲（没有导入在进行，也没有待消费的结果）
 *  - running=true：导入中；UI 刷新进度条
 *  - running=false 且 result!=null：完成；UI 弹结果框，随后 [consume] 清空
 *  - running=false 且 cancelled=true：用户取消；UI 提示后 [consume] 清空
 */
object ImportEngine {

    /**
     * 一次导入的实时快照（不可变，UI 只读）。
     *
     * @param running 导入是否仍在进行
     * @param total 原曲总数
     * @param matched 本次新增匹配成功的歌曲数
     * @param skipped 歌单内已存在、直接跳过的歌曲数（重复导入）
     * @param missed 未能匹配到任何可播放音源的歌曲数
     * @param title 最近一首处理完的歌名（供 UI 显示「正在匹配《x》」）
     * @param playlistName 目标本地歌单名（解析阶段可能为空）
     * @param result 导入完成时的结果；取消或失败时为 null
     * @param cancelled 是否被用户取消
     * @param failed 歌单抓取/解析失败（链接错误或需要登录）时非空
     */
    data class State(
        val running: Boolean = true,
        val total: Int = 0,
        val matched: Int = 0,
        val skipped: Int = 0,
        val missed: Int = 0,
        val title: String = "",
        val playlistName: String = "",
        val result: ImportResult? = null,
        val cancelled: Boolean = false,
        val failed: Boolean = false
    ) {
        /** 已处理数（用于进度条分子）。 */
        val done: Int get() = matched + skipped + missed
    }

    private val _state = MutableStateFlow<State?>(null)

    /** 当前导入状态；null 表示空闲。UI 只应读这个流。 */
    val state: StateFlow<State?> = _state

    /** 是否正在导入（供「导入」按钮判断是恢复弹窗还是开新导入）。 */
    fun isRunning(): Boolean = _state.value?.running == true

    /** Service 在开始一次导入前调用，重置为初始运行态。 */
    fun start() {
        _state.value = State()
    }

    /** Service 每首歌处理完回调，更新计数。 */
    fun update(
        total: Int,
        matched: Int,
        skipped: Int,
        missed: Int,
        title: String,
        playlistName: String
    ) {
        _state.value = State(
            running = true,
            total = total,
            matched = matched,
            skipped = skipped,
            missed = missed,
            title = title,
            playlistName = playlistName
        )
    }

    /** Service 导入完成时调用。 */
    fun finish(result: ImportResult?) {
        val prev = _state.value
        _state.value = State(
            running = false,
            total = result?.total ?: prev?.total ?: 0,
            matched = result?.matched ?: prev?.matched ?: 0,
            skipped = if (result != null) result.total - result.matched - result.misses.size else prev?.skipped ?: 0,
            missed = result?.misses?.size ?: prev?.missed ?: 0,
            title = prev?.title.orEmpty(),
            playlistName = result?.playlistName ?: prev?.playlistName.orEmpty(),
            result = result,
            cancelled = false,
            failed = result == null
        )
    }

    /** 用户在通知或对话框里取消导入时调用（已匹配的歌曲仍然保留）。 */
    fun cancel() {
        val prev = _state.value ?: return
        _state.value = prev.copy(running = false, cancelled = true, result = null)
    }

    /** UI 消费完终态（结果框已弹 / 取消提示已给）后清空，回到空闲。 */
    fun consume() {
        _state.value = null
    }
}
