# -*- coding: utf-8 -*-
"""
_qa4_falsify.py —— C.10 守卫断言的「可证伪」自证脚本

目的：证明 local_playlist_check.py 里那条 C.10 守卫断言**真的会红**，不是永远为真的幽灵断言。

做法：
  1) 从 qa/local_playlist_check.py 中**原样抽取** QA4-GUARD-BEGIN/END 之间的守卫代码块
     （因此复跑的确实是套件里的同一个函数，不是复制粘贴的另一份）；
  2) 用修复前的错误写法（内联字符串副本：inflateInput 返回 EditText 子 View +
     setView(子 View)）跑它 → 必须返回 FAIL；
  3) 用仓库里真实的、已修复的 PlaylistNameDialog.kt 跑它 → 必须返回 PASS。

注：反例只以**内联字符串**存在，绝不写回仓库业务源码。
运行：python qa/_qa4_falsify.py   （退出码 0 = 自证通过；1 = 自证失败）
"""
import os
import re
import sys

QA_DIR = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(QA_DIR)
CHECK_PY = os.path.join(QA_DIR, "local_playlist_check.py")
TARGET = os.path.join(REPO, "android", "app", "src", "main", "java",
                      "com", "soundtrack", "music", "util", "PlaylistNameDialog.kt")

BEGIN = "# >>> QA4-GUARD-BEGIN"
END = "# <<< QA4-GUARD-END"


def extract_guard_block(path):
    src = open(path, encoding="utf-8").read()
    b = src.index(BEGIN)
    b = src.index("\n", b) + 1
    e = src.index(END)
    return src[b:e]


# ---- 反例：修复前的错误写法（内联字符串副本，仅存在于本脚本内存中） ----
PREFIX_BAD = '''package com.soundtrack.music.util

import android.content.Context
import android.view.LayoutInflater
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.soundtrack.music.R
import com.soundtrack.music.data.LocalPlaylist
import com.soundtrack.music.data.PlaylistStore

object PlaylistNameDialog {

    fun showNew(ctx: Context, store: PlaylistStore, onDone: (String?) -> Unit = {}) {
        val input = inflateInput(ctx)
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("新建歌单")
            .setView(input)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
    }

    fun showRename(ctx: Context, store: PlaylistStore, playlist: LocalPlaylist, onDone: () -> Unit = {}) {
        val input = inflateInput(ctx)
        input.setText(playlist.name)
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("重命名歌单")
            .setView(input)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
    }

    private fun inflateInput(ctx: Context): EditText {
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_playlist_name, null)
        return view.findViewById(R.id.input_name)
    }
}
'''


def main():
    block = extract_guard_block(CHECK_PY)
    ns = {"re": re}
    exec(compile(block, "<qa4-guard-extracted>", "exec"), ns)
    guard = ns["_guarded_setview_arg_is_root"]

    real = open(TARGET, encoding="utf-8").read()
    ok_bad, det_bad = guard(PREFIX_BAD)
    ok_real, det_real = guard(real)

    print("=" * 62)
    print("同一个守卫函数 _guarded_setview_arg_is_root() 的两次运行：")
    print("=" * 62)
    print("[反例] 修复前错误写法（内联字符串）      -> %s" % ("PASS" if ok_bad else "FAIL"))
    print("       detail: %s" % det_bad)
    print("       (期望：FAIL)")
    print()
    print("[正例] 仓库真实已修复 PlaylistNameDialog.kt -> %s" % ("PASS" if ok_real else "FAIL"))
    print("       detail: %s" % det_real)
    print("       (期望：PASS)")
    print()
    ok = (ok_bad is False) and (ok_real is True)
    print("自证结论: %s" % ("OK —— 守卫可被证伪（反例 FAIL / 正例 PASS）" if ok
                          else "FAILED —— 守卫不可证伪或误报"))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
