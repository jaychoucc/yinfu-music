# -*- coding: utf-8 -*-
"""Kotlin 注释地雷扫描器。

背景：Kotlin 的块注释 /* */ 是可嵌套的（与 Java/C 不同）。
因此块注释内部再出现 "/*" 会开启一层嵌套注释，导致原本用来闭合的 "*/"
只关掉内层，外层注释一直不闭合 -> 编译器报 Unclosed comment，并吞掉文件剩余内容。

本脚本逐字符扫描每个 .kt 文件，维护一个词法状态机，区分：
  - 普通代码
  - 行注释 //...
  - 块注释 /* ... */（可嵌套）
  - 普通字符串 "..."（含转义）
  - 原始字符串 \"\"\"...\"\"\"
  - 字符字面量 '...'
并输出两类问题：
  A. 块注释内部出现的 "/*"（嵌套注释 / NESTED）
  B. 文件结束时块注释未闭合（UNCLOSED）；以及 /* 与 */ 计数不配对（MISMATCH）

字符串/字符/行注释里的 /* 不会误报。

路径语义（关键，供其它脚本从任意工作目录调用）：
  默认扫描根 = 本脚本所在目录的上一级 + android/app/src/main/java
             （即 <仓库根>/android/app/src/main/java）
  默认报告文件 = <脚本所在目录>/scan_nested_comments_report.txt（即 qa/ 下）
  可用命令行参数或 --root / --out 显式覆盖，绝不依赖当前工作目录（CWD）。

退出码（供 CI / 其它脚本调用）：
  0 = CLEAN（无嵌套/未闭合/不配对块注释）
  1 = PROBLEMS（发现任一问题文件）
stdout 末行输出机器可读结论：`RESULT: CLEAN ...` 或 `RESULT: PROBLEMS <n>`。
"""
import io
import os
import sys

# 本脚本所在目录（= qa/）；仓库根为其上一级。
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(SCRIPT_DIR)
# 默认扫描根：与 CWD 无关，纯由脚本自身位置推导。
DEFAULT_ROOT = os.path.normpath(
    os.path.join(SCRIPT_DIR, "..", "android", "app", "src", "main", "java")
)
# 默认报告输出：固定写到 qa/ 下，不回写仓库根。
DEFAULT_OUT = os.path.join(SCRIPT_DIR, "scan_nested_comments_report.txt")


def parse_args(argv):
    """解析参数，兼容“位置参数”与“--root/--out”两种写法。

    支持：
        [root] [out]
        --root <path>  --out <path>
        --root=<path>  --out=<path>
    返回 (root, out)；缺失项用 None 表示（由调用方回落到默认值）。
    """
    root = None
    out = None
    positional = []
    i = 0
    while i < len(argv):
        arg = argv[i]
        if arg == "--root" and i + 1 < len(argv):
            root = argv[i + 1]
            i += 2
            continue
        if arg == "--out" and i + 1 < len(argv):
            out = argv[i + 1]
            i += 2
            continue
        if arg.startswith("--root="):
            root = arg.split("=", 1)[1]
            i += 1
            continue
        if arg.startswith("--out="):
            out = arg.split("=", 1)[1]
            i += 1
            continue
        positional.append(arg)
        i += 1

    if root is None and len(positional) >= 1:
        root = positional[0]
    if out is None and len(positional) >= 2:
        out = positional[1]
    return root, out


def scan_file(path):
    with io.open(path, "r", encoding="utf-8", errors="replace") as f:
        text = f.read()

    # 逐字符状态机
    n = len(text)
    i = 0
    line = 1
    col = 1

    # state: CODE / LINE_COMMENT / BLOCK_COMMENT / STRING / RAWSTRING / CHAR
    state = "CODE"
    block_depth = 0            # 当前块注释嵌套深度
    block_start_line = 0       # 最外层块注释的起始行
    nested_events = []         # 记录块注释内的 /* 位置
    open_count = 0             # 代码态遇到的 "/*" 次数
    close_count = 0            # 代码态遇到的 "*/" 次数

    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        nxt2 = text[i + 2] if i + 2 < n else ""

        if c == "\n":
            if state == "LINE_COMMENT":
                state = "CODE"
            line += 1
            col = 1
            i += 1
            continue

        if state == "CODE":
            if c == "/" and nxt == "/":
                state = "LINE_COMMENT"
                i += 2
                col += 2
                continue
            if c == "/" and nxt == "*":
                open_count += 1
                block_depth = 1
                block_start_line = line
                state = "BLOCK_COMMENT"
                i += 2
                col += 2
                continue
            if c == '"' and nxt == '"' and nxt2 == '"':
                state = "RAWSTRING"
                i += 3
                col += 3
                continue
            if c == '"':
                state = "STRING"
                i += 1
                col += 1
                continue
            if c == "'":
                state = "CHAR"
                i += 1
                col += 1
                continue
            i += 1
            col += 1
            continue

        if state == "LINE_COMMENT":
            i += 1
            col += 1
            continue

        if state == "BLOCK_COMMENT":
            if c == "/" and nxt == "*":
                # 块注释内部再次出现 /* -> 嵌套注释（地雷）
                nested_events.append((line, col))
                block_depth += 1
                i += 2
                col += 2
                continue
            if c == "*" and nxt == "/":
                block_depth -= 1
                i += 2
                col += 2
                if block_depth == 0:
                    state = "CODE"
                    close_count += 1
                continue
            i += 1
            col += 1
            continue

        if state == "STRING":
            if c == "\\":
                i += 2
                col += 2
                continue
            if c == '"':
                state = "CODE"
            i += 1
            col += 1
            continue

        if state == "RAWSTRING":
            if c == '"' and nxt == '"' and nxt2 == '"':
                state = "CODE"
                i += 3
                col += 3
                continue
            i += 1
            col += 1
            continue

        if state == "CHAR":
            if c == "\\":
                i += 2
                col += 2
                continue
            if c == "'":
                state = "CODE"
            i += 1
            col += 1
            continue

    unclosed = (state == "BLOCK_COMMENT")
    mismatch = (open_count != close_count)
    return {
        "nested": nested_events,
        "unclosed": unclosed,
        "block_start_line": block_start_line,
        "open": open_count,
        "close": close_count,
        "mismatch": mismatch,
    }


def main(argv=None):
    if argv is None:
        argv = sys.argv[1:]

    arg_root, arg_out = parse_args(argv)
    # 关键：默认扫描根由脚本自身位置推导，不依赖 CWD。
    root = arg_root if arg_root else DEFAULT_ROOT
    out_path = arg_out if arg_out else DEFAULT_OUT

    out = []
    kt_files = []
    for dirpath, _dirnames, filenames in os.walk(root):
        for fn in filenames:
            if fn.endswith(".kt"):
                kt_files.append(os.path.join(dirpath, fn))
    kt_files.sort()

    total = len(kt_files)
    problem_files = 0
    out.append("=== Kotlin nested-comment scan ===")
    out.append("root: %s" % os.path.abspath(root))
    out.append("scanned .kt files: %d" % total)
    out.append("")

    for path in kt_files:
        r = scan_file(path)
        if r["nested"] or r["unclosed"] or r["mismatch"]:
            problem_files += 1
            out.append("[PROBLEM] %s" % path)
            if r["unclosed"]:
                out.append("    UNCLOSED block comment (started at line %d)" % r["block_start_line"])
            out.append("    open /* = %d, close */ = %d" % (r["open"], r["close"]))
            for (ln, cl) in r["nested"]:
                out.append("    NESTED /* inside block comment at line %d col %d" % (ln, cl))
            out.append("")

    out.append("---------------------------------------")
    out.append("problem files: %d / %d" % (problem_files, total))
    if problem_files == 0:
        out.append("RESULT: CLEAN (no nested/unclosed/mismatched block comments)")
    else:
        out.append("RESULT: PROBLEMS %d" % problem_files)

    data = "\n".join(out)

    # 报告固定写到 qa/（脚本同目录），不回写仓库根。目录不存在则创建。
    out_dir = os.path.dirname(os.path.abspath(out_path))
    if out_dir and not os.path.isdir(out_dir):
        os.makedirs(out_dir, exist_ok=True)
    with io.open(out_path, "w", encoding="utf-8") as f:
        f.write(data)

    print(data)

    # 退出码语义：0 = CLEAN，1 = 有命中（供 CI / 其它脚本消费）。
    return 0 if problem_files == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
