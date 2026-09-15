# -*- coding: utf-8 -*-
"""
_qa5_more_falsify.py —— 「行尾按钮图标/描述/点击同源派生」契约断言的可证伪自证。

做法（与 qa/_qa4_falsify.py 同范式）：
  1) 从 qa/local_playlist_check.py 里**原样抽取** QA5-GUARD-BEGIN/END 之间的断言块；
  2) 用 exec 得到同一个 _qa5_more_contract_ok 函数；
  3) 用同一函数分别跑：
       正例 = 真实的 adapter/SongAdapter.kt           → 期望 PASS
       反例 = 内存中的「只改点击行为、不改图标」变体   → 期望 FAIL
  绝不写入仓库业务源码；反例仅存在于内存字符串。

运行：cd <仓库根> && python qa/_qa5_more_falsify.py
退出码：正例 PASS 且反例 FAIL = 0；否则 = 1
"""
import os
import re
import sys

QA_DIR = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(QA_DIR)
SONG_ADAPTER = os.path.join(
    REPO, "android", "app", "src", "main", "java", "com", "soundtrack", "music",
    "adapter", "SongAdapter.kt",
)
SUITE = os.path.join(QA_DIR, "local_playlist_check.py")


def _extract_guard_block(path):
    """从套件里原样截取 QA5-GUARD-BEGIN/END 之间的断言块（含函数定义）。"""
    with open(path, encoding="utf-8") as f:
        txt = f.read()
    m = re.search(r'# >>> QA5-GUARD-BEGIN\n([\s\S]*?)\n# <<< QA5-GUARD-END', txt)
    if not m:
        raise SystemExit("FATAL: 未在 %s 找到 QA5-GUARD-BEGIN/END 标记块" % path)
    return m.group(1)


def main():
    block = _extract_guard_block(SUITE)
    # 抽取块自包含、只依赖 re；注入 re 供 exec 后的函数使用（正反例共用同一函数）
    ns = {"re": re}
    exec(compile(block, "<extracted-QA5-GUARD>", "exec"), ns)
    fn = ns.get("_qa5_more_contract_ok")
    if fn is None:
        raise SystemExit("FATAL: 抽取块内未定义 _qa5_more_contract_ok")

    with open(SONG_ADAPTER, encoding="utf-8") as f:
        real = f.read()

    # 反例：只改「点击行为」，不动「图标」——
    # 从 onMore 分支里删掉 setImageResource(ic_more_vert) 一行，其余（contentDescription/
    # setOnClickListener）保持不变。这正模拟「改了行为却忘了同步图标」的脱节缺陷。
    mutant, n = re.subn(
        r'\n[ \t]*more\.setImageResource\(R\.drawable\.ic_more_vert\)',
        '',
        real,
    )
    print("抽取的断言块长度: %d 字符" % len(block))
    print("反例构造：从 onMore 分支删除 setImageResource 行，共改动 %d 处" % n)

    ok_real, det_real = fn(real)
    ok_mut, det_mut = fn(mutant)

    print("")
    print("[正例] 真实 SongAdapter.kt           -> %s  (%s)" % ("PASS" if ok_real else "FAIL", det_real))
    print("[反例] 只改点击行为/不改图标的变体    -> %s  (%s)" % ("PASS" if ok_mut else "FAIL", det_mut))
    print("")
    verdict = (ok_real is True) and (ok_mut is False)
    print("可证伪自证: " + ("PASS（正例通过、反例失败，断言具证伪力）" if verdict else "FAIL（断言无法区分正反例）"))
    sys.exit(0 if verdict else 1)


if __name__ == "__main__":
    main()
