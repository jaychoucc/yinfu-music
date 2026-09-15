package com.soundtrack.music.util

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.soundtrack.music.R
import com.soundtrack.music.data.LocalPlaylist
import com.soundtrack.music.data.PlaylistModels
import com.soundtrack.music.data.PlaylistStore

/**
 * 新建 / 重命名歌单弹窗（S6）。
 *
 * 采用 `AlertDialog + EditText`（与 `MineFragment.showSourceStatus` 一致的 `AlertDialog.Builder` 范式）；
 * 校验不通过时**不关闭弹窗**，把错误写进 `EditText.error`。
 */
object PlaylistNameDialog {

    /**
     * 名称校验（P0-7 / P0-8）：
     * 去首尾空格 → 非空 → 长度 ≤ [`PlaylistModels.MAX_NAME_LEN`] → 不与其它歌单重名（忽略大小写）。
     * 通过返回 null，否则返回错误文案。「我喜欢的音乐」由默认歌单占用，故天然被拒（AC-11）。
     */
    fun validate(raw: String, store: PlaylistStore, excludeId: String? = null): String? {
        val name = raw.trim()
        if (name.isEmpty()) return "歌单名称不能为空"
        if (name.length > PlaylistModels.MAX_NAME_LEN) return "歌单名称最多 ${PlaylistModels.MAX_NAME_LEN} 个字"
        if (store.isNameTaken(name, excludeId)) return "已有同名歌单"
        return null
    }

    /**
     * 新建歌单弹窗。
     *
     * @param onDone 创建成功后回调，参数为**新歌单 id**（供 S5 选择器自动勾选，AC-19）；失败不回调。
     */
    fun showNew(ctx: Context, store: PlaylistStore, onDone: (String?) -> Unit = {}) {
        val (root, input) = inflateInput(ctx)
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("新建歌单")
            .setView(root)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val raw = input.text.toString()
                val err = validate(raw, store, null)
                if (err != null) {
                    input.error = err
                    return@setOnClickListener
                }
                val result = store.createPlaylist(raw)
                if (result != null) {
                    input.error = result
                    return@setOnClickListener
                }
                // 新建的歌单名唯一，可安全按名反查其 id
                val created = store.playlists()
                    .firstOrNull { it.name.trim().equals(raw.trim(), ignoreCase = true) }
                onDone(created?.id)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /** 重命名歌单弹窗（预填当前名）；默认歌单不应调用此方法。 */
    fun showRename(ctx: Context, store: PlaylistStore, playlist: LocalPlaylist, onDone: () -> Unit = {}) {
        val (root, input) = inflateInput(ctx)
        input.setText(playlist.name)
        input.setSelection(input.text.length)
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("重命名歌单")
            .setView(root)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val raw = input.text.toString()
                // 排除自身，避免「与原名相同」被误判为重名
                val err = validate(raw, store, playlist.id)
                if (err != null) {
                    input.error = err
                    return@setOnClickListener
                }
                val result = store.renamePlaylist(playlist.id, raw)
                if (result != null) {
                    input.error = result
                    return@setOnClickListener
                }
                onDone()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /**
     * 加载弹窗自定义布局，返回**根容器**与其内部的 `EditText`。
     *
     * `AlertDialog.Builder.setView(...)` 会把传入的 View 交给 `AlertController.contentPanel.addView(...)`；
     * 若传入的是已有父容器的子 View（如 `findViewById(R.id.input_name)` 的结果）会抛
     * `IllegalStateException: The specified child already has a parent`。因此这里必须把**根容器**交给
     * `setView`，`EditText` 仅用于读写文本与错误提示。
     */
    private fun inflateInput(ctx: Context): Pair<View, EditText> {
        val root = LayoutInflater.from(ctx).inflate(R.layout.dialog_playlist_name, null)
        val input = root.findViewById<EditText>(R.id.input_name)
        return root to input
    }
}
