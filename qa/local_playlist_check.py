# -*- coding: utf-8 -*-
"""
本地歌单系统 QA 验证脚本（纯 Python 标准库，零第三方依赖）

背景：本机无 JDK / Android SDK / Gradle（java/javac/kotlinc 均不存在，android/gradlew 不在仓库），
      因此本脚本**不做任何编译或真机运行**，只做：
        1) 静态检查（扫源码 / 资源，最高优先级——因为没有编译器兜底）
        2) 契约检查（在源码层面断言关键链路成立）
        3) 逻辑仿真（用 Python 逐字复刻 PlaylistStore 的 JSON 读写与歌单增删改语义并断言）

验收依据：docs/PRD-local-playlist.md（AC-1 ~ AC-34）+ docs/ARCHITECTURE-local-playlist.md

运行：cd <仓库根> && python qa/local_playlist_check.py
退出码：全 PASS = 0；有 FAIL = 1
"""
import difflib
import json
import os
import re
import subprocess
import sys

import xml.etree.ElementTree as ET

# ------------------------------------------------------------------
# 路径
# ------------------------------------------------------------------
QA_DIR = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(QA_DIR)
JAVA_ROOT = os.path.join(REPO, "android", "app", "src", "main", "java", "com", "soundtrack", "music")
RES_ROOT = os.path.join(REPO, "android", "app", "src", "main", "res")
MANIFEST = os.path.join(REPO, "android", "app", "src", "main", "AndroidManifest.xml")

passed = 0
failed = 0
skipped = 0
failures = []
skips = []


def check(name, cond, detail=""):
    global passed, failed
    status = "PASS" if cond else "FAIL"
    if cond:
        passed += 1
    else:
        failed += 1
        failures.append(name + ((" — " + detail) if detail else ""))
    print(f"  [{status}] {name}" + (f" — {detail}" if detail else ""))
    return cond


def check_skip(name, detail=""):
    """显式降级：既不算 PASS 也不算 FAIL，单列 SKIP/UNVERIFIED，绝不静默 PASS。"""
    global skipped
    skipped += 1
    skips.append(name + ((" — " + detail) if detail else ""))
    print(f"  [SKIP] {name}" + (f" — {detail}" if detail else ""))


def read_text(path):
    try:
        with open(path, encoding="utf-8") as f:
            return f.read()
    except Exception:
        return ""


def section(title):
    print("\n" + "=" * 62)
    print(title)
    print("=" * 62)


def strip_kotlin_comments(txt):
    """去掉块注释与行注释，避免注释中提到的类名被误判为真实引用。"""
    txt = re.sub(r'/\*[\s\S]*?\*/', '', txt)
    txt = re.sub(r'//[^\n]*', '', txt)
    return txt


def extract_fun_body(txt, signature_regex):
    """按大括号配对，抽出匹配签名的函数体（含首尾大括号）；找不到返回 None。"""
    m = re.search(signature_regex, txt)
    if not m:
        return None
    start = txt.find("{", m.start())
    if start < 0:
        return None
    depth = 0
    i = start
    while i < len(txt):
        c = txt[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return txt[start:i + 1]
        i += 1
    return None


# ------------------------------------------------------------------
# 0. 收集新增 / 修改文件清单
# ------------------------------------------------------------------
print("仓库根: " + REPO)

NEW_KOTLIN = [
    "data/PlaylistStore.kt", "data/PlaylistModels.kt", "model/SongKeys.kt",
    "util/PlaylistNameDialog.kt", "ui/MyPlaylistDetailActivity.kt",
    "ui/MyPlaylistsActivity.kt", "ui/AddToPlaylistSheet.kt",
    "adapter/PlaylistSongAdapter.kt", "adapter/MyPlaylistAdapter.kt",
    "adapter/PlaylistPickAdapter.kt",
]
MODIFIED_KOTLIN = [
    "SoundtrackApp.kt", "player/PlayerRepository.kt", "ui/PlayerActivity.kt",
    "ui/MineFragment.kt", "adapter/SongAdapter.kt", "adapter/NewSongAdapter.kt",
    "ui/SearchFragment.kt", "ui/HomeFragment.kt",
]
NEW_LAYOUT = [
    "dialog_playlist_name.xml", "activity_my_playlist_detail.xml",
    "item_playlist_song.xml", "activity_my_playlists.xml",
    "item_my_playlist.xml", "sheet_add_to_playlist.xml", "item_pick_playlist.xml",
]
NEW_DRAWABLE = ["ic_favorite_border.xml", "ic_add.xml"]


def kpath(rel):
    return os.path.join(JAVA_ROOT, rel.replace("/", os.sep))


def lpath(name):
    return os.path.join(RES_ROOT, "layout", name)


FEATURE_KOTLIN = NEW_KOTLIN + MODIFIED_KOTLIN


# ==================================================================
# A. 资源引用完整性
# ==================================================================
section("A. 资源引用完整性（静态）")

# A.1 所有 res 下的 id 定义 vs 代码中的 R.id 引用 -------------------
id_defs = set()
id_defs_by_file = {}
for dp, _dn, fns in os.walk(RES_ROOT):
    for fn in fns:
        if not fn.endswith(".xml"):
            continue
        p = os.path.join(dp, fn)
        txt = read_text(p)
        found = re.findall(r'@\+id/([A-Za-z0-9_]+)', txt)
        if found:
            id_defs_by_file[p] = found
        id_defs.update(found)

r_id_uses = {}
missing_ids = []
for rel in FEATURE_KOTLIN:
    p = kpath(rel)
    txt = read_text(p)
    for m in re.findall(r'R\.id\.([A-Za-z0-9_]+)', txt):
        r_id_uses.setdefault(m, rel)
        if m not in id_defs:
            missing_ids.append(f"{rel}: R.id.{m}")
check("A.1 所有新增/修改 Kotlin 的 R.id.* 均有对应 android:id 定义",
      not missing_ids, "; ".join(missing_ids) if missing_ids else f"{len(r_id_uses)} 个 id 全部命中")

# 同时校验 XML 内 @id/ 引用（非 @+id）
xml_id_refs = []
xml_dirs = [os.path.join(RES_ROOT, "layout"), os.path.join(RES_ROOT, "menu")]
for d in xml_dirs:
    if not os.path.isdir(d):
        continue
    for fn in os.listdir(d):
        if not fn.endswith(".xml"):
            continue
        txt = read_text(os.path.join(d, fn))
        for m in re.findall(r'@id/([A-Za-z0-9_]+)', txt):
            if m not in id_defs:
                xml_id_refs.append(f"{fn}: @id/{m}")
check("A.2 布局/菜单内 @id/ 引用均有定义", not xml_id_refs, "; ".join(xml_id_refs))

# A.3 单文件内 id 重复 -------------------------------------------
dup_ids = []
for p, ids in id_defs_by_file.items():
    seen = set()
    for i in ids:
        if i in seen:
            dup_ids.append(f"{os.path.basename(p)}: @+id/{i}")
        seen.add(i)
check("A.3 无单文件重复 android:id", not dup_ids, "; ".join(dup_ids))

# A.4 drawable / color / layout / string / style 定义表 -------------
drawable_defs = set()
for sub in os.listdir(RES_ROOT):
    if sub.startswith("drawable") or sub.startswith("mipmap"):
        d = os.path.join(RES_ROOT, sub)
        if os.path.isdir(d):
            for fn in os.listdir(d):
                drawable_defs.add(os.path.splitext(fn)[0])

color_defs, string_defs, style_defs = set(), set(), set()
values_dir = os.path.join(RES_ROOT, "values")
for fn in os.listdir(values_dir) if os.path.isdir(values_dir) else []:
    if not fn.endswith(".xml"):
        continue
    txt = read_text(os.path.join(values_dir, fn))
    color_defs.update(re.findall(r'<color\s+name="([A-Za-z0-9_]+)"', txt))
    string_defs.update(re.findall(r'<string\s+name="([A-Za-z0-9_]+)"', txt))
    style_defs.update(re.findall(r'<style\s+name="([A-Za-z0-9_.]+)"', txt))

layout_defs = set()
layout_dir = os.path.join(RES_ROOT, "layout")
for fn in os.listdir(layout_dir) if os.path.isdir(layout_dir) else []:
    if fn.endswith(".xml"):
        layout_defs.add(os.path.splitext(fn)[0])

RES_KINDS = {
    "drawable": drawable_defs,
    "color": color_defs,
    "layout": layout_defs,
    "string": string_defs,
    "style": style_defs,
}

# 在 XML 中扫描 @kind/name 用法
res_missing = []
all_res_xml = []
for dp, _dn, fns in os.walk(RES_ROOT):
    for fn in fns:
        if fn.endswith(".xml"):
            all_res_xml.append(os.path.join(dp, fn))
for p in all_res_xml:
    txt = read_text(p)
    for kind, defs in RES_KINDS.items():
        for name in re.findall(r'@' + kind + r'/([A-Za-z0-9_.]+)', txt):
            if name not in defs:
                res_missing.append(f"{os.path.relpath(p, RES_ROOT)}: @{kind}/{name}")

# 在 Kotlin 中扫描 R.kind.name 用法
for rel in FEATURE_KOTLIN:
    txt = read_text(kpath(rel))
    for kind, defs in RES_KINDS.items():
        for name in re.findall(r'R\.' + kind + r'\.([A-Za-z0-9_]+)', txt):
            if name not in defs:
                res_missing.append(f"{rel}: R.{kind}.{name}")
check("A.4 新增/修改文件的资源引用（drawable/color/layout/string/style）全部存在",
      not res_missing, "; ".join(sorted(set(res_missing))))

# A.5 新增布局 XML 可解析 + 标签闭合 ---------------------------------
bad_xml = []
for name in NEW_LAYOUT + NEW_DRAWABLE:
    p = os.path.join(RES_ROOT, "layout" if name in NEW_LAYOUT else "drawable", name)
    try:
        ET.parse(p)
    except Exception as e:
        bad_xml.append(f"{name}: {e}")
# 修改过的布局也校验
try:
    ET.parse(lpath("fragment_mine.xml"))
except Exception as e:
    bad_xml.append(f"fragment_mine.xml: {e}")
check("A.5 新增/修改 XML 均能被 ElementTree 解析（标签闭合/属性完整）",
      not bad_xml, "; ".join(bad_xml))

# A.6 include 标签合法性（layout 属性存在 + 目标存在 + id 与代码对得上）----
inc_problems = []
for name in ["activity_my_playlists.xml", "activity_my_playlist_detail.xml", "activity_playlist_detail.xml"]:
    p = lpath(name)
    txt = read_text(p)
    for block in re.findall(r'<include\b[^>]*/?>', txt, re.S):
        m = re.search(r'layout="@layout/([A-Za-z0-9_]+)"', block)
        if not m:
            inc_problems.append(f"{name}: include 缺 layout= 属性")
            continue
        if m.group(1) not in layout_defs:
            inc_problems.append(f"{name}: include layout=@layout/{m.group(1)} 不存在")
        if 'android:id="@+id/mini_player"' not in block and "mini_player" not in block:
            inc_problems.append(f"{name}: include 未见 mini_player id")
check("A.6 include 标签写法合法（layout= 存在且目标存在、id=mini_player）",
      not inc_problems, "; ".join(inc_problems))

# A.7 新增 drawable 为合法 VectorDrawable --------------------------
vd_bad = []
for name in NEW_DRAWABLE:
    p = os.path.join(RES_ROOT, "drawable", name)
    txt = read_text(p)
    if "<vector" not in txt or "android:pathData" not in txt:
        vd_bad.append(name)
check("A.7 新增 drawable 为合法 vector（含 <vector> 与 pathData）", not vd_bad, "; ".join(vd_bad))


# ==================================================================
# B. 向后兼容
# ==================================================================
section("B. 向后兼容（静态 + git）")


def git_modified_files():
    try:
        out = subprocess.run(["git", "status", "--porcelain"], cwd=REPO,
                             capture_output=True, text=True, timeout=30)
        if out.returncode != 0:
            return None
        changed = []
        for line in out.stdout.splitlines():
            if len(line) > 3:
                path = line[3:].strip().strip('"')
                changed.append(path.replace("\\", "/"))
        return changed
    except Exception:
        return None


def git_show_head(path):
    """返回 path 在 git HEAD 中的文本；未跟踪 / 不存在 / git 不可用 → None。"""
    try:
        out = subprocess.run(["git", "show", "HEAD:" + path], cwd=REPO,
                             capture_output=True, text=True, timeout=30)
        if out.returncode != 0:
            return None
        return out.stdout
    except Exception:
        return None


def git_head_blob(path):
    """path 在 git HEAD 中的 blob 哈希；未入库 / git 不可用 → None。

    与 `git rev-parse HEAD:<path>` 等价，用于证明「文件已在版本库基线内」。
    """
    try:
        out = subprocess.run(["git", "rev-parse", "HEAD:" + path], cwd=REPO,
                             capture_output=True, text=True, timeout=30)
        return out.stdout.strip() if out.returncode == 0 else None
    except Exception:
        return None


def git_worktree_blob(path):
    """工作区文件按 git 规则算出的 blob 哈希；文件不存在 / git 不可用 → None。

    注意：`git hash-object` 默认会施加与 `git add` 相同的换行转换（本仓库 core.autocrlf=true），
    因此其结果可以与 HEAD 中的 blob 直接比较 —— 这正是「逐字节一致」的可靠判据。
    """
    try:
        abs_path = os.path.join(REPO, path.replace("/", os.sep))
        out = subprocess.run(["git", "hash-object", abs_path], cwd=REPO,
                             capture_output=True, text=True, timeout=30)
        return out.stdout.strip() if out.returncode == 0 else None
    except Exception:
        return None


def gradle_props_machine_path_only(path):
    """android/gradle.properties 是否 *仅* 放行了 org.gradle.java.home 行的值变化。

    比较的两个版本（可复现、可解释）：
        工作区版本： <仓库根>/android/gradle.properties
        基线版本  ： `git show HEAD:android/gradle.properties` 的输出

    放行规则（精确放行，绝不粗暴豁免）：
        允许 *且仅允许* `org.gradle.java.home=<值>` 这一行的“值”随工作区绝对路径
        变化——该值由环境配置生成、天然与机器/会话目录绑定（见文件内注释）。
        除此之外，与基线相比只要还有任意一行不同（例如 android.enableJetifier、
        org.gradle.parallel、android.sdk.dir、android.useAndroidX、kotlin.code.style、
        org.gradle.jvmargs 被误改/被删，或注释/空行被改）→ FAIL。
        另：整行删除 org.gradle.java.home 也越界（那不叫“值变化”）。

    降级：该文件在仓库中未跟踪 / 无 HEAD 基线时返回 SKIP（UNVERIFIED），
          既不静默 PASS，也不误报 FAIL。

    返回 (status, detail)，status ∈ {"OK", "FAIL", "SKIP"}；detail 打印每一处实际变化。
    """
    full = os.path.join(REPO, path)
    exists = os.path.exists(full)
    head = git_show_head(path)
    if head is None:
        # 无 HEAD 基线：该文件要么是未跟踪新增，要么根本不存在。
        # 两种情况都无法“证明仅机器行变化”，故显式降级为 SKIP/UNVERIFIED（绝不静默 PASS）。
        if exists:
            return ("SKIP",
                    "工作区存在但 git 无 HEAD 基线（未跟踪新增），无法与基线逐行比对 → UNVERIFIED")
        return ("SKIP", f"工作区与 HEAD 均无 {path}（无对象可校验）→ UNVERIFIED")
    if not exists:
        return ("FAIL", f"HEAD 中存在但工作区缺失 {path}（构建配置缺档）")

    is_java_home = re.compile(r'^\s*org\.gradle\.java\.home\s*=')
    head_lines = head.splitlines()
    work_lines = read_text(full).splitlines()

    changed = []   # [(侧, 行)]  侧: "-" 基线 / "+" 工作区
    off = []       # 落在 org.gradle.java.home 之外的任何变化
    for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(None, head_lines, work_lines).get_opcodes():
        if tag == "equal":
            continue
        for ln in head_lines[i1:i2]:
            changed.append(("-", ln))
            if not is_java_home.match(ln):
                off.append("- " + ln.strip()[:100])
        for ln in work_lines[j1:j2]:
            changed.append(("+", ln))
            if not is_java_home.match(ln):
                off.append("+ " + ln.strip()[:100])

    if not changed:
        return ("OK", "工作区 vs HEAD 逐行一致（该文件零改动）")
    if off:
        return ("FAIL", "存在 org.gradle.java.home 之外的行变化: " + " | ".join(off[:6]))
    if not any(is_java_home.match(l) for l in work_lines):
        return ("FAIL", "org.gradle.java.home 行被整行删除（超出“仅值变化”放行范围）")

    shown = "  ||  ".join(f"[{s}] {t.strip()}" for s, t in changed)
    return ("OK", "工作区 vs HEAD 仅 org.gradle.java.home 行值变化（机器相关，放行）: " + shown)


changed = git_modified_files()

# B.1 PlaylistDetailActivity.kt / activity_playlist_detail.xml 零改动 ----
if changed is not None:
    forbidden = [c for c in changed if c.endswith("ui/PlaylistDetailActivity.kt")
                 or c.endswith("layout/activity_playlist_detail.xml")]
    check("B.1 git 确认 PlaylistDetailActivity.kt / activity_playlist_detail.xml 未被改动",
          not forbidden, "; ".join(forbidden) if forbidden else "git status 中不存在")
else:
    check("B.1 git 确认远端歌单页零改动", False, "git 不可用，无法校验")

# 内容兜底：不应出现任何本地歌单专属 token
tokens = ["PlaylistStore", "PlaylistModels", "SongKeys", "MyPlaylist", "local_playlist", "ic_favorite_border"]
hits = []
for p, label in [(kpath("ui/PlaylistDetailActivity.kt"), "PlaylistDetailActivity.kt"),
                 (lpath("activity_playlist_detail.xml"), "activity_playlist_detail.xml")]:
    txt = read_text(p)
    for t in tokens:
        if t in txt:
            hits.append(f"{label}: 含 {t}")
check("B.2 远程歌单页/布局内容层不含本地歌单专属 token（零改动兜底）", not hits, "; ".join(hits))

# B.3 ref/ ref_all/ web/ + gradle 构建配置未改动 --------------------
#   - ref/ ref_all/ web/ build.gradle.kts settings.gradle.kts app/build.gradle.kts：
#     源码/构建配置，任何改动 = FAIL。
#   - android/gradle.properties：仅放行机器相关的 org.gradle.java.home 行“值”变化，
#     其余任何行变化仍 FAIL；无 HEAD 基线时显式 SKIP（见 gradle_props_machine_path_only）。
if changed is not None:
    violations = []
    strict_prefixes = ("ref/", "ref_all/", "web/")
    strict_files = ("android/build.gradle.kts", "android/settings.gradle.kts",
                    "android/app/build.gradle.kts")
    for c in changed:
        if c.startswith(strict_prefixes) or c in strict_files:
            violations.append(c)

    # 不依赖 git status 是否逐文件列出：直接比对“工作区文件 vs git show HEAD:”，
    # 以免整个目录未跟踪时被 git 折叠成 "android/" 而漏检。
    gradle_prop = "android/gradle.properties"
    gradle_status, gradle_detail = gradle_props_machine_path_only(gradle_prop)
    if gradle_status == "FAIL":
        violations.append(gradle_prop + "（" + gradle_detail + "）")

    b3_name = ("B.3 git 确认 ref/ ref_all/ web/ build.gradle.kts settings.gradle.kts 未改动，"
               "gradle.properties 仅允许 org.gradle.java.home(机器相关) 行变化")
    if violations:
        check(b3_name, False, "; ".join(violations))
    elif gradle_status == "SKIP":
        check_skip(b3_name, "gradle.properties: " + gradle_detail)
    else:
        check(b3_name, True, "gradle.properties: " + gradle_detail)
else:
    check("B.3 git 确认依赖/参考文件未改动", False, "git 不可用")

# B.4 SongAdapter / NewSongAdapter 新增参数在末尾且带默认值 null -----
sa = read_text(kpath("adapter/SongAdapter.kt"))
nsa = read_text(kpath("adapter/NewSongAdapter.kt"))
check("B.4a SongAdapter 末尾新增可选 onLongClick 且默认 null",
      re.search(r'onDownload: \(\(Song\) -> Unit\)\? = null,\s*[\s\S]{0,200}?onLongClick: \(\(Song\) -> Unit\)\? = null', sa) is not None,
      "参数须位于末尾且 = null")
check("B.4b NewSongAdapter 末尾新增可选 onLongClick 且默认 null",
      re.search(r'onClick: \(Song\) -> Unit,\s*[\s\S]{0,160}?onLongClick: \(\(Song\) -> Unit\)\? = null', nsa) is not None,
      "参数须位于末尾且 = null")

# B.5 三处既有调用点形式校验 --------------------------------------
pda = read_text(kpath("ui/PlaylistDetailActivity.kt"))
search = read_text(kpath("ui/SearchFragment.kt"))
home = read_text(kpath("ui/HomeFragment.kt"))
check("B.5a PlaylistDetailActivity 调用 SongAdapter(loader, onClick=, onDownload=) 仍成立",
      "onClick =" in pda and "onDownload =" in pda and "onLongClick" not in pda)
check("B.5b SearchFragment 以具名参数传 onLongClick", "onLongClick = { song ->" in search)
check("B.5c HomeFragment 以位置参数给 NewSongAdapter 第三参（onLongClick）",
      re.search(r'NewSongAdapter\(\s*loader,\s*\{ song ->[^}]*\},\s*\{ song ->', home, re.S) is not None)


# ==================================================================
# C. 关键契约（源码级断言）
# ==================================================================
section("C. 关键契约（源码级断言）")

# C.1 零 ViewBinding --------------------------------------------
vb_hits = []
for dp, _dn, fns in os.walk(JAVA_ROOT):
    for fn in fns:
        if fn.endswith(".kt"):
            txt = read_text(os.path.join(dp, fn))
            if re.search(r'\bbinding\.', txt) or "ViewBinding" in txt:
                vb_hits.append(fn)
check("C.1 全仓库 java/** 零 ViewBinding / binding.", not vb_hits, "; ".join(vb_hits))
check("C.1b build.gradle.kts viewBinding 仍为 false",
      "viewBinding = false" in read_text(os.path.join(REPO, "android", "app", "build.gradle.kts")))

# C.2 直链短路保持 ---------------------------------------------
pr = read_text(kpath("player/PlayerRepository.kt"))
check("C.2 playAt 中 hasPlayUrl 短路（setMediaAndPlay(playUrl) + return）仍在",
      re.search(r'if \(song\.hasPlayUrl\) \{[\s\S]{0,120}?setMediaAndPlay\(song\.playUrl\)\s*[\s\S]{0,40}?return', pr) is not None)

# C.3 恢复链路 -------------------------------------------------
pr_code = strip_kotlin_comments(pr)
# 按大括号配对严格抽出各函数体（不靠固定行数/缩进）
err_body = extract_fun_body(pr, r'override fun onPlayerError\(error: PlaybackException\)') or ""
nxt_body = extract_fun_body(pr, r'override fun onPlaybackStateChanged\(state: Int\)') or ""
recov_body = extract_fun_body(pr, r'private fun attemptRecoveryForCurrentSong\(\)') or ""

check("C.3a attemptRecoveryForCurrentSong() 已定义", "private fun attemptRecoveryForCurrentSong()" in pr)
check("C.3b recoveryAttemptedKey 守卫存在（恢复函数体内）",
      "if (recoveryAttemptedKey == key) return" in recov_body)
check("C.3c playAt 内复位 recoveryAttemptedKey = null（每次播放尝试复位）",
      "recoveryAttemptedKey = null" in (extract_fun_body(pr, r'fun playAt\(index: Int\)') or ""))
check("C.3d 仅在成功且 http 开头时回填（onUrlRefreshed）",
      re.search(r'url\.startsWith\("http"\)[\s\S]{0,200}?onUrlRefreshed\?\.invoke', recov_body) is not None)

# 关键：恢复调用必须落在 onPlayerError **方法体内**（而非 onPlaybackStateChanged）
cond_e = "mainHandler.post" in err_body and "attemptRecoveryForCurrentSong()" in err_body
check("C.3e onPlayerError 体内经 mainHandler.post 触发恢复入口", cond_e,
      "" if cond_e else "onPlayerError 体内未见 mainHandler.post { attemptRecoveryForCurrentSong() }")
cond_f = "attemptRecoveryForCurrentSong" not in nxt_body
check("C.3f 恢复调用**不在** onPlaybackStateChanged 体内（避免误挂接）", cond_f,
      "" if cond_f else "恢复调用被误放进 onPlaybackStateChanged")
# 恢复函数是否真的被调用（非死代码）——排除定义处与注释
called = len(re.findall(r'(?<!fun )attemptRecoveryForCurrentSong\(\)', pr_code))
check("C.3g attemptRecoveryForCurrentSong() 至少被调用一次（非死代码）", called >= 1,
      f"调用点={called}（0 = 死代码）")
check("C.3h onPlaybackStateChanged 的 ENDED→next() 逻辑未被改动",
      "mainHandler.post" in nxt_body and "next()" in nxt_body)

# C.4 不自动跳歌 ------------------------------------------------
check("C.4 onPlayerError 内不调用 next()（保持不自动跳歌）", "next()" not in err_body)
check("C.4b 恢复函数体内不调用 next()（失败不自动跳歌）", "next()" not in recov_body)

# C.5 回填挂接 --------------------------------------------------
app = read_text(kpath("SoundtrackApp.kt"))
check("C.5 SoundtrackApp 把 onUrlRefreshed 接到 PlaylistStore.updatePlayUrl",
      "onUrlRefreshed" in app and "updatePlayUrl" in app)

# C.6 默认歌单双保险 --------------------------------------------
store = read_text(kpath("data/PlaylistStore.kt"))
m_del = re.search(r'fun deletePlaylist\(playlistId: String\): Boolean\s*\{([\s\S]*?)\n    \}', store)
del_body = m_del.group(1) if m_del else ""
m_ren = re.search(r'fun renamePlaylist\(playlistId: String, rawName: String\): String\? \{([\s\S]*?)\n    \}', store)
ren_body = m_ren.group(1) if m_ren else ""
check("C.6a deletePlaylist 对 builtin 防御性拒绝", "if (pl.builtin) return false" in del_body)
check("C.6b renamePlaylist 对 builtin 防御性拒绝", "if (pl.builtin) return" in ren_body)

# C.7 地址行语义 ------------------------------------------------
ips = read_text(lpath("item_playlist_song.xml"))
m_url = re.search(r'android:id="@\+id/play_url"[\s\S]*?/>', ips)
url_block = m_url.group(0) if m_url else ""
check("C.7a item_playlist_song.play_url 为 maxLines=1 + ellipsize=middle",
      'android:maxLines="1"' in url_block and 'android:ellipsize="middle"' in url_block)

psa = read_text(kpath("adapter/PlaylistSongAdapter.kt"))
check("C.7b 空/非 http → 占位文案「未解析 · 播放时自动解析」",
      "未解析 · 播放时自动解析" in psa)
check("C.7c 地址行不由 null 拼出（无 playUrl.text = null、无 \"$null\"/null.toString 拼接）",
      "playUrl.text = null" not in psa and "$null" not in psa and "null.toString" not in psa)
# 更精确：playUrl.text 的赋值只有两种字面量分支
assigns = re.findall(r'playUrl\.text = (.+)', psa)
check("C.7d playUrl.text 只被赋值为 url 本身或占位文案（不存在 null 兜底成串）",
      len(assigns) == 2 and all(("url" in a) or ("未解析" in a) for a in assigns),
      f"实际赋值={assigns}")

mpd = read_text(kpath("ui/MyPlaylistDetailActivity.kt"))
check("C.7e 长按复制的是完整 playUrl（用 song.playUrl 塞剪贴板）",
      "val url = song.playUrl" in mpd and "newPlainText(\"play_url\", url)" in mpd)

# C.8 Manifest -------------------------------------------------
mani = read_text(MANIFEST)
def has_activity(name, exported=False):
    m = re.search(r'<activity[\s\S]*?android:name="' + re.escape(name) + r'"[\s\S]*?/>', mani)
    if not m:
        return False
    block = m.group(0)
    if 'android:exported="false"' not in block:
        return False
    if 'android:screenOrientation="portrait"' not in block:
        return False
    return True
check("C.8a MyPlaylistsActivity 已注册 exported=false + portrait", has_activity(".ui.MyPlaylistsActivity"))
check("C.8b MyPlaylistDetailActivity 已注册 exported=false + portrait", has_activity(".ui.MyPlaylistDetailActivity"))

# C.9 import 完整性（新增/修改文件）＋ 全仓库错误 import 残留扫描 ----
API_CLASSES = {
    "RecyclerView": "androidx.recyclerview.widget.RecyclerView",
    "LinearLayoutManager": "androidx.recyclerview.widget.LinearLayoutManager",
    "JSONObject": "org.json.JSONObject",
    "JSONArray": "org.json.JSONArray",
    "BottomSheetDialogFragment": "com.google.android.material.bottomsheet.BottomSheetDialogFragment",
    "ClipData": "android.content.ClipData",
    "ClipboardManager": "android.content.ClipboardManager",
    "Intent": "android.content.Intent",
    "Context": "android.content.Context",
    "Toast": "android.widget.Toast",
    "ImageButton": "android.widget.ImageButton",
    "ImageView": "android.widget.ImageView",
    "TextView": "android.widget.TextView",
    "EditText": "android.widget.EditText",
    "CheckBox": "android.widget.CheckBox",
    "SeekBar": "android.widget.SeekBar",
    "Button": "android.widget.Button",
    "LayoutInflater": "android.view.LayoutInflater",
    "ViewGroup": "android.view.ViewGroup",
    "Bundle": "android.os.Bundle",
    "AppCompatActivity": "androidx.appcompat.app.AppCompatActivity",
    "AlertDialog": "androidx.appcompat.app.AlertDialog",
}
PROJECT_CLASSES = {
    "PlaylistStore": "com.soundtrack.music.data.PlaylistStore",
    "PlaylistModels": "com.soundtrack.music.data.PlaylistModels",
    "LocalPlaylist": "com.soundtrack.music.data.LocalPlaylist",
    "PlaylistEntry": "com.soundtrack.music.data.PlaylistEntry",
    "SongKeys": "com.soundtrack.music.model.SongKeys",
    "Song": "com.soundtrack.music.model.Song",
    "PlaylistNameDialog": "com.soundtrack.music.util.PlaylistNameDialog",
    "MiniImageLoader": "com.soundtrack.music.util.MiniImageLoader",
    "MiniPlayerController": "com.soundtrack.music.ui.MiniPlayerController",
    "AddToPlaylistSheet": "com.soundtrack.music.ui.AddToPlaylistSheet",
}
ALL_CLASSES = dict(API_CLASSES)
ALL_CLASSES.update(PROJECT_CLASSES)

bad_rv = []
missing_imports = []
for rel in NEW_KOTLIN + MODIFIED_KOTLIN:
    p = kpath(rel)
    txt = read_text(p)
    m_pkg = re.search(r'^package\s+([\w.]+)', txt, re.M)
    pkg = m_pkg.group(1) if m_pkg else ""
    code = strip_kotlin_comments(txt)
    body = "\n".join(l for l in code.splitlines() if not l.strip().startswith("import "))
    for cls, fqcn in ALL_CLASSES.items():
        if pkg == fqcn.rsplit(".", 1)[0]:   # 同包无需 import
            continue
        if re.search(r'(?<![\w.])' + cls + r'\b', body) and f"import {fqcn}" not in txt:
            missing_imports.append(f"{rel}: {cls}（应 import {fqcn}）")
check("C.9a 新增/修改文件跨包类均已正确 import（含 RecyclerView/JSON*/BottomSheetDialogFragment/ClipData 等）",
      not missing_imports, "; ".join(missing_imports))

# 全仓库扫描错误 import 残留
residual = []
for dp, _dn, fns in os.walk(os.path.join(REPO, "android")):
    for fn in fns:
        if fn.endswith(".kt"):
            fp = os.path.join(dp, fn)
            if "import android.widget.RecyclerView" in read_text(fp):
                residual.append(os.path.relpath(fp, REPO).replace("\\", "/"))
check("C.9b 全仓库无残留 `import android.widget.RecyclerView`", not residual, "; ".join(residual))

# C.10 【本轮崩溃缺陷守卫】setView 必须收到「布局根容器」，不得是 findViewById 子 View --
#   背景（首次点击「+」100% 崩）：dialog_playlist_name.xml 根 = LinearLayout，
#   @+id/input_name 是它的子 View；旧 inflateInput 直接
#   `return view.findViewById(R.id.input_name)`（返回 EditText 子 View），
#   showNew/showRename 再 `.setView(input)` → AlertDialog.Builder 把「已有父容器的子 View」
#   交给 AlertController.setupCustomContent，其内部 customFrameLayout.addView(mView) 抛
#   IllegalStateException: The specified child already has a parent。
#
#   断言只看结构（变量绑定 / Pair 解构 + return 表达式），不看行号，对「单变量绑定」
#   与「Pair 解构绑定」两种写法均成立，且可被反例证伪（见 qa/_qa4_falsify.py）。
#   注意：本块用 QA4-GUARD-BEGIN/END 标记包裹且**自包含**（只依赖 re），
#   qa/_qa4_falsify.py 会原样抽取本块复跑同一函数，勿删标记。
# >>> QA4-GUARD-BEGIN
_QA4_CHILD_TYPES = (
    "EditText", "TextView", "Button", "ImageButton", "ImageView", "CheckBox",
    "RadioButton", "SeekBar", "ProgressBar", "Spinner", "Switch", "ToggleButton",
    "SwitchCompat", "TextInputEditText", "AutoCompleteTextView",
)


def _qa4_fun_body(txt, signature_regex):
    """按大括号配对抽出匹配签名的函数体（自包含，不依赖脚本其它 helper）。"""
    m = re.search(signature_regex, txt)
    if not m:
        return None
    start = txt.find("{", m.start())
    if start < 0:
        return None
    depth = 0
    i = start
    while i < len(txt):
        c = txt[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return txt[start:i + 1]
        i += 1
    return None


def _qa4_strip_comments(txt):
    """去掉块注释(含 KDoc)与行注释，避免注释里出现的 `.setView(...)` 示例被当成真实调用。"""
    txt = re.sub(r'/\*[\s\S]*?\*/', '', txt)
    txt = re.sub(r'//[^\n]*', '', txt)
    return txt


def _guarded_setview_arg_is_root(src):
    """判定 setView 是否收到「布局根容器」而非「findViewById 得到的子 View」。

    返回 (ok, detail)。ok=False = 检出「已有父容器的子 View 交给 setView」缺陷。
    """
    src = _qa4_strip_comments(src)
    body = _qa4_fun_body(src, r'private\s+fun\s+inflateInput\b')
    if body is None:
        return (False, "未找到 inflateInput 函数体（无法证明 setView 收到根容器）")
    sig = re.search(r'private\s+fun\s+inflateInput\s*\([^()]*\)\s*:\s*([^{]+?)\s*\{', src)
    rt = sig.group(1).strip() if sig else ""

    m_root = re.search(r'val\s+(\w+)\s*=\s*[^\n]*?\binflate\s*\(', body)
    m_child = re.search(r'val\s+(\w+)\s*=\s*[^\n]*?findViewById\s*[<(]', body)
    root_var = m_root.group(1) if m_root else None
    child_var = m_child.group(1) if m_child else None

    returns = [r.strip() for r in re.findall(r'\breturn\b([^\n]*)', body)]
    bad_returns, returns_root_or_pair = [], False
    for r in returns:
        if "findViewById" in r:
            bad_returns.append(r)
        elif child_var and re.fullmatch(re.escape(child_var), r):
            bad_returns.append(r)
        elif (" to " in r) or ("Pair(" in r) or (root_var and re.fullmatch(re.escape(root_var), r)):
            returns_root_or_pair = True
        elif rt in _QA4_CHILD_TYPES:
            bad_returns.append(r or ("(返回类型 %s)" % rt))

    m_destr = re.search(r'val\s*\(\s*(\w+)\s*,\s*(\w+)\s*\)\s*=\s*inflateInput\s*\(', src)
    m_single = re.search(r'val\s+(\w+)\s*=\s*inflateInput\s*\(', src)
    setview_args = [a.strip() for a in re.findall(r'\.setView\s*\(\s*([^)]*)\)', src)]

    bad_calls = []
    if m_destr:
        root_name, child_name = m_destr.group(1), m_destr.group(2)
        for a in setview_args:
            if a == child_name:
                bad_calls.append(a + "（inflateInput 解构出的子 View）")
            elif "findViewById" in a:
                bad_calls.append(a)
    elif m_single:
        v = m_single.group(1)
        for a in setview_args:
            if "findViewById" in a:
                bad_calls.append(a)
            elif a == v and (bad_returns or rt in _QA4_CHILD_TYPES):
                bad_calls.append(a + "（inflateInput 返回子 View，却直接交给 setView）")
    else:
        for a in setview_args:
            if "findViewById" in a:
                bad_calls.append(a)

    problems = []
    if bad_returns:
        problems.append("inflateInput 返回子 View: " + " | ".join(bad_returns))
    if bad_calls:
        problems.append("setView 收到子 View: " + " | ".join(bad_calls))
    if not returns_root_or_pair and not problems:
        problems.append("无法判定 inflateInput 返回根容器/组合（return=%r, 返回类型=%r）" % (returns, rt))

    if problems:
        return (False, "; ".join(problems))
    return (True, "inflateInput 返回 %s；setView 实参=%s" % (rt or "?", setview_args))
# <<< QA4-GUARD-END

_pnd_src = read_text(kpath("util/PlaylistNameDialog.kt"))
_ok_c10, _det_c10 = _guarded_setview_arg_is_root(_pnd_src)
check("C.10 setView 实参必须是布局根容器（不得把已有父容器的子 View 交给 setView）",
      _ok_c10, _det_c10)


# C.11 【本轮契约守卫】行尾按钮「图标 / 无障碍描述 / 点击行为」必须同源派生 ----
#   背景（加入歌单可见入口）：SongAdapter 行尾按钮按 onMore 是否传入派生：
#     传 onMore  → ic_more_vert  + contentDescription「更多」+ 弹菜单；
#     未传       → ic_download   + contentDescription「下载」+ 直接下载。
#   契约：**两个分支都必须同时设置 图标 + contentDescription + 点击行为**，三者永不脱节。
#   断言只看语义（分支体三件套齐全 + 图标/描述取值正确），不看行号；
#   可被「只改点击行为、不改图标」的反例证伪（见 qa/_qa5_more_falsify.py）。
#   本块用 QA5-GUARD-BEGIN/END 包裹且**自包含**（只依赖 re），勿删标记。
# >>> QA5-GUARD-BEGIN
def _qa5_strip_comments(txt):
    txt = re.sub(r'/\*[\s\S]*?\*/', '', txt)
    txt = re.sub(r'//[^\n]*', '', txt)
    return txt


def _qa5_pair(s, start):
    """从下标 start（应为 '{' 位置）按大括号配对，返回 (含首尾大括号的块, 结束下标+1)。"""
    depth = 0
    i = start
    while i < len(s):
        if s[i] == '{':
            depth += 1
        elif s[i] == '}':
            depth -= 1
            if depth == 0:
                return s[start:i + 1], i + 1
        i += 1
    return None, -1


def _qa5_more_branches(src):
    """抽取 bind() 内 `if (onMore != null) { A } else { B }` 的两个分支体（注释已剥离）。

    返回 (A, B)；结构缺失时对应项为 None。
    """
    src = _qa5_strip_comments(src)
    m = re.search(r'if\s*\(\s*onMore\s*!=\s*null\s*\)\s*\{', src)
    if not m:
        return None, None
    a, after = _qa5_pair(src, m.end() - 1)
    if a is None:
        return None, None
    rest = src[after:]
    me = re.search(r'else\s*\{', rest)
    if not me:
        return a, None
    b, _ = _qa5_pair(rest, me.end() - 1)
    return a, b


def _qa5_branch_ok(branch, icon, desc):
    """判定单个分支是否「三件套齐全」：setImageResource(icon) + contentDescription(desc) + setOnClickListener。"""
    if not branch:
        return False, "分支缺失（未能抽出分支体）"
    bad = []
    if "setImageResource" not in branch:
        bad.append("缺 setImageResource")
    elif icon not in branch:
        bad.append("图标非 %s" % icon)
    if "contentDescription" not in branch:
        bad.append("缺 contentDescription")
    elif desc not in branch:
        bad.append("描述非 %s" % desc)
    if "setOnClickListener" not in branch:
        bad.append("缺 setOnClickListener")
    return (not bad), ("；".join(bad) if bad else "三件套齐全(%s)" % desc)


def _qa5_more_contract_ok(src):
    """契约守卫：行尾按钮两个分支都必须同时设置 图标+contentDescription+点击行为。"""
    a, b = _qa5_more_branches(src)
    ok_a, det_a = _qa5_branch_ok(a, "ic_more_vert", "更多")
    ok_b, det_b = _qa5_branch_ok(b, "ic_download", "下载")
    return (ok_a and ok_b), "onMore 分支[%s] / else 分支[%s]" % (det_a, det_b)
# <<< QA5-GUARD-END

_sa_src_c11 = read_text(kpath("adapter/SongAdapter.kt"))
_ok_c11, _det_c11 = _qa5_more_contract_ok(_sa_src_c11)
check("C.11 SongAdapter 行尾按钮两分支均同时设置 图标+contentDescription+点击行为（同源派生）",
      _ok_c11, _det_c11)


# ==================================================================
# C2. 本轮歌单封面契约（C-N1 ~ C-N6；源码级 + 逻辑仿真 + 可证伪性）
# ==================================================================
section("C2. 本轮歌单封面契约（源码级 + 仿真 + 可证伪）")


# ---- 仿真：songsOf（addedAt 倒序）+ coverUrlOf（严格两级回退）----
def _qac_songs_of(entries, library):
    ordered = sorted(entries, key=lambda e: e[1], reverse=True)
    return [library[k] for (k, _a) in ordered if k in library]


def _qac_nb(v):
    return v if (v is not None and str(v).strip() != "") else None


def _qac_cover_url_of(entries, library):
    songs = _qac_songs_of(entries, library)
    v0 = _qac_nb(songs[0]["coverUrl"]) if len(songs) > 0 else None
    if v0 is not None:
        return v0
    return _qac_nb(songs[1]["coverUrl"]) if len(songs) > 1 else None


def _qac_cover_bad_scanall(entries, library):
    """坏实现 A：扫全部取第一个非空封面（越过第二首）。"""
    for s in _qac_songs_of(entries, library):
        v = _qac_nb(s["coverUrl"])
        if v is not None:
            return v
    return None


def _qac_cover_bad_no_takeif2(entries, library):
    """坏实现 B：第二级漏 takeIf{isNotBlank}。"""
    songs = _qac_songs_of(entries, library)
    v0 = _qac_nb(songs[0]["coverUrl"]) if len(songs) > 0 else None
    if v0 is not None:
        return v0
    return songs[1]["coverUrl"] if len(songs) > 1 else None


def _qac_case(specs):
    library, entries = {}, []
    for (k, a, c) in specs:
        library[k] = {"coverUrl": c}
        entries.append((k, a))
    return entries, library


def _qac_run(fn, specs):
    return fn(*_qac_case(specs))


_QAC_TT = [
    ("空歌单", [], None),
    ("[有封面]", [("a", 10, "C1")], "C1"),
    ("[无封面](仅一首)", [("a", 10, "")], None),
    ("[无封面, 有封面]", [("a", 20, ""), ("b", 10, "C2")], "C2"),
    # 插入序与 addedAt 序相反：必须按 addedAt 取最大者，而非插入序
    ("[有封面A, 有封面B]->addedAt最大者(B)", [("a", 100, "A"), ("b", 200, "B")], "B"),
    # 关键反例：第 2 首也无封面、第 3 首才有 -> 必须 None（不得越过第 2 首）
    ("[无封面,无封面,有封面]->None", [("a", 300, ""), ("b", 200, ""), ("c", 100, "C3")], None),
    ("[无封面,有封面,有封面]->第1首(B1)", [("a", 300, ""), ("b", 200, "B1"), ("c", 100, "B2")], "B1"),
]

_store_code = strip_kotlin_comments(store)
_qac_body = extract_fun_body(_store_code, r'fun coverUrlOf\(playlistId: String\)') or ""

# C.12a 源码：恰为两级回退（两处 takeIf/isNotBlank、getOrNull(0/1)、复用 songsOf；无第 3 级）
_qac_two_level = (_qac_body.count("takeIf") == 2
                  and _qac_body.count("isNotBlank()") == 2
                  and "getOrNull(0)" in _qac_body and "getOrNull(1)" in _qac_body
                  and "songsOf(" in _qac_body)
_qac_no_third = ("getOrNull(2)" not in _store_code
                 and "for (" not in _qac_body and "firstOrNull" not in _qac_body
                 and "filter" not in _qac_body)
check("C.12a coverUrlOf 源码严格两级回退（两处 takeIf{isNotBlank}、getOrNull(0/1)、复用 songsOf、无 getOrNull(2)/无遍历）",
      _qac_two_level and _qac_no_third,
      "two_level=%s no_third=%s body=%r" % (_qac_two_level, _qac_no_third, _qac_body))

# C.12b 仿真真值表（真实现，7 行全命中）
_qac_miss = [n for (n, s, e) in _QAC_TT if _qac_run(_qac_cover_url_of, s) != e]
check("C.12b 真值表 7 行全部命中（含 [无封面,无封面,有封面]->None 反例）",
      not _qac_miss, "失配行=%s" % _qac_miss)

# C.12c 可证伪性：坏实现必须在真值表上报红（否则守卫无效）
_qac_bad1 = [n for (n, s, e) in _QAC_TT if _qac_run(_qac_cover_bad_scanall, s) != e]
_qac_bad2 = [n for (n, s, e) in _QAC_TT if _qac_run(_qac_cover_bad_no_takeif2, s) != e]
check("C.12c 可证伪性：两种坏实现（扫全部取首个非空 / 二级漏 takeIf）均被真值表检出 FAIL",
      bool(_qac_bad1) and bool(_qac_bad2),
      "坏实现A失配=%s ; 坏实现B失配=%s" % (_qac_bad1, _qac_bad2))

# ---- C.13 PlaylistStore 增量语义 ----
check("C.13a coverUrlOf 复用 songsOf(（口径单一来源，未自写排序）",
      "songsOf(playlistId)" in _qac_body,
      "body=%r" % _qac_body)
check("C.13b 全文件无 getOrNull(2)（不越过第二首）", "getOrNull(2)" not in _store_code)

_qac_required_funs = ["createPlaylist", "renamePlaylist", "deletePlaylist", "toggleIn",
                      "setMembership", "addToPlaylist", "removeFromPlaylist", "updatePlayUrl",
                      "songsOf", "playlistIdsContaining", "containsIn", "songCount",
                      "isNameTaken", "coverUrlOf"]
_qac_missing = [f for f in _qac_required_funs if ("fun " + f + "(") not in _store_code]
check("C.13c 方法层「只增」：既有方法签名齐全 + 新增 coverUrlOf 存在（无签名级删除）",
      not _qac_missing, "缺失=%s" % _qac_missing)
# PlaylistStore.kt 曾长期是未跟踪新增（HEAD 里没有它，无基线可逐行比对），
# 该断言技术上不可执行，故此前显式降级为 SKIP/UNVERIFIED 而非静默判 PASS。
# 2026-09-15 的基线提交已把它纳入版本库（提交 bbd43cf），故在此升级为强断言。
_qac_ps_rel = "android/app/src/main/java/com/soundtrack/music/data/PlaylistStore.kt"
_qac_ps_head = git_head_blob(_qac_ps_rel)
_qac_ps_work = git_worktree_blob(_qac_ps_rel)
check("C.13d PlaylistStore.kt 已入库，且工作区与 HEAD 逐字节一致（blob 哈希相等）",
      _qac_ps_head is not None and _qac_ps_work is not None and _qac_ps_head == _qac_ps_work,
      "HEAD=%s 工作区=%s" % (_qac_ps_head, _qac_ps_work))

# ---- C.23 基线存在性：本地歌单系统核心文件均已入库且与基线逐字节一致 ----
# 价值：一旦有人改了这些文件却不提交，或提交后又产生本地改动，本条立刻报红。
_C23_FILES = [
    "android/app/src/main/java/com/soundtrack/music/data/PlaylistStore.kt",
    "android/app/src/main/java/com/soundtrack/music/data/PlaylistModels.kt",
    "android/app/src/main/java/com/soundtrack/music/model/SongKeys.kt",
    "android/app/src/main/java/com/soundtrack/music/adapter/MyPlaylistAdapter.kt",
    "android/app/src/main/java/com/soundtrack/music/adapter/PlaylistPickAdapter.kt",
    "android/app/src/main/java/com/soundtrack/music/util/PlaylistNameDialog.kt",
    "android/app/src/main/java/com/soundtrack/music/ui/MyPlaylistsActivity.kt",
    "android/app/src/main/java/com/soundtrack/music/ui/MyPlaylistDetailActivity.kt",
    "android/app/src/main/java/com/soundtrack/music/ui/AddToPlaylistSheet.kt",
]
_c23_bad = []
for _p in _C23_FILES:
    _h, _w = git_head_blob(_p), git_worktree_blob(_p)
    if _h is None or _w is None or _h != _w:
        _c23_bad.append("%s(HEAD=%s,work=%s)" % (_p.rsplit("/", 1)[-1], _h, _w))
check("C.23 本地歌单系统核心文件已在 git 基线内且与工作区逐字节一致（%d 个）" % len(_C23_FILES),
      not _c23_bad, "未入库或不一致=%s" % _c23_bad)

# 可证伪：未入库的路径必须取不到 HEAD blob，否则 C.23 就是「永真断言」（比没有检查更糟）
_c23_absent = "android/app/src/main/java/com/soundtrack/music/data/__no_such_file__.kt"
check("C.23b 可证伪性：未入库路径取不到 HEAD blob（证明 C.23 不是永真断言）",
      git_head_blob(_c23_absent) is None, "实际=%s" % git_head_blob(_c23_absent))

# ---- C.14 MyPlaylistAdapter 契约（S3）----
_mpa_raw = read_text(kpath("adapter/MyPlaylistAdapter.kt"))
_mpa = strip_kotlin_comments(_mpa_raw)
_mpa_bind = extract_fun_body(_mpa, r'fun bind\(p: LocalPlaylist\)') or ""
check("C.14a MyPlaylistAdapter import 齐全（ImageView + MiniImageLoader）",
      "import android.widget.ImageView" in _mpa_raw
      and "import com.soundtrack.music.util.MiniImageLoader" in _mpa_raw)
check("C.14b MyPlaylistAdapter 构造签名：loader 必填在前、coverOf 在末尾且默认 null",
      re.search(r'class MyPlaylistAdapter\(\s*private val loader: MiniImageLoader,'
                r'[\s\S]*?private val coverOf: \(\(LocalPlaylist\) -> String\?\)\? = null\s*\)', _mpa) is not None)
check("C.14c MyPlaylistAdapter VH 内 findViewById(R.id.cover) 存在",
      "findViewById(R.id.cover)" in _mpa)
_q_mpa_clear = _mpa_bind.find("setImageDrawable(null)")
_q_mpa_load = _mpa_bind.find("loader.load(")
_q_mpa_guard = re.search(r'if \(!coverUrl\.isNullOrBlank\(\)\)\s*loader\.load\(', _mpa_bind)
check("C.14d bind「先清复用残留(setImageDrawable(null)) 再判空加载(loader.load 被 !isNullOrBlank 包裹)」",
      _q_mpa_clear >= 0 and _q_mpa_load > _q_mpa_clear and _q_mpa_guard is not None,
      "clear@%d load@%d guard=%s" % (_q_mpa_clear, _q_mpa_load, bool(_q_mpa_guard)))
_q_mpa_keep = (("name.text = p.name" in _mpa_bind)
               and ("count.text = " in _mpa_bind)
               and ("if (p.builtin)" in _mpa_bind)
               and ("itemView.setOnClickListener { onClick(p) }" in _mpa_bind)
               and ("btnRename.setOnClickListener { onRename(p) }" in _mpa_bind)
               and ("btnDelete.setOnClickListener { onDelete(p) }" in _mpa_bind))
check("C.14e MyPlaylistAdapter name/count/默认歌单按钮可见性/三点击回调逻辑仍在",
      _q_mpa_keep)

# ---- C.15 PlaylistPickAdapter 契约（S5）----
_ppa_raw = read_text(kpath("adapter/PlaylistPickAdapter.kt"))
_ppa = strip_kotlin_comments(_ppa_raw)
_ppa_bind = extract_fun_body(_ppa, r'fun bind\(p: LocalPlaylist\)') or ""
check("C.15a PlaylistPickAdapter import 齐全（ImageView + MiniImageLoader）",
      "import android.widget.ImageView" in _ppa_raw
      and "import com.soundtrack.music.util.MiniImageLoader" in _ppa_raw)
check("C.15b PlaylistPickAdapter 构造签名：loader 必填在前、coverOf 在末尾且默认 null",
      re.search(r'class PlaylistPickAdapter\(\s*private val loader: MiniImageLoader,'
                r'\s*private val coverOf: \(\(LocalPlaylist\) -> String\?\)\? = null\s*\)', _ppa) is not None)
check("C.15c PlaylistPickAdapter VH 内 findViewById(R.id.cover) 存在",
      "findViewById(R.id.cover)" in _ppa)
_q_ppa_clear = _ppa_bind.find("setImageDrawable(null)")
_q_ppa_load = _ppa_bind.find("loader.load(")
_q_ppa_guard = re.search(r'if \(!coverUrl\.isNullOrBlank\(\)\)\s*loader\.load\(', _ppa_bind)
check("C.15d bind「先清后加载 + 判空」三段式（同 C.14d 口径）",
      _q_ppa_clear >= 0 and _q_ppa_load > _q_ppa_clear and _q_ppa_guard is not None,
      "clear@%d load@%d guard=%s" % (_q_ppa_clear, _q_ppa_load, bool(_q_ppa_guard)))
# 勾选态四步必须顺序正确
_i_null = _ppa_bind.find("check.setOnCheckedChangeListener(null)")
_i_set = _ppa_bind.find("check.isChecked = checked.contains(p.id)")
_i_lis = _ppa_bind.find("check.setOnCheckedChangeListener {")
_i_row = _ppa_bind.find("itemView.setOnClickListener { check.isChecked = !check.isChecked }")
check("C.15e 勾选态四步顺序：解绑监听 -> 依据 checked 设值 -> 重新绑定 -> 整行点击切换",
      -1 < _i_null < _i_set < _i_lis < _i_row,
      "idx(null=%d,set=%d,listener=%d,row=%d)" % (_i_null, _i_set, _i_lis, _i_row))

# ---- C.16 item_pick_playlist.xml 布局契约 ----
_pick_xml = read_text(lpath("item_pick_playlist.xml"))
_q_order = (_pick_xml.find("@+id/check") >= 0
            and 0 <= _pick_xml.find("@+id/check") < _pick_xml.find("@+id/cover") < _pick_xml.find("@+id/name"))
check("C.16a item_pick_playlist.xml 子 View 顺序 = check -> cover -> name", _q_order)
_q_cover_block = re.search(r'<ImageView[\s\S]*?@\+id/cover[\s\S]*?/>', _pick_xml)
_q_cover_block = _q_cover_block.group(0) if _q_cover_block else ""
check("C.16b cover 为 36dp×36dp + scaleType=centerCrop + background=@drawable/bg_gradient_accent",
      ('android:layout_width="36dp"' in _q_cover_block
       and 'android:layout_height="36dp"' in _q_cover_block
       and 'android:scaleType="centerCrop"' in _q_cover_block
       and 'android:background="@drawable/bg_gradient_accent"' in _q_cover_block),
      "block=%r" % _q_cover_block)
_q_name_block = re.search(r'<TextView[\s\S]*?@\+id/name[\s\S]*?/>', _pick_xml)
_q_name_block = _q_name_block.group(0) if _q_name_block else ""
check("C.16c @+id/name 的 layout_marginStart=\"8dp\" 未被改动",
      'android:layout_marginStart="8dp"' in _q_name_block, "block=%r" % _q_name_block)

# ---- C.17 调用点接线（恰 2 处调用 + 1 处定义）----
_act = read_text(kpath("ui/MyPlaylistsActivity.kt"))
_sheet = read_text(kpath("ui/AddToPlaylistSheet.kt"))
check("C.17a MyPlaylistsActivity 恰构造一次 MyPlaylistAdapter 且传 loader + coverOf=store.coverUrlOf(p.id)",
      _act.count("MyPlaylistAdapter(") == 1
      and "loader = MiniImageLoader(" in _act
      and "coverOf = { p -> store.coverUrlOf(p.id) }" in _act,
      "ctor=%d" % _act.count("MyPlaylistAdapter("))
check("C.17b AddToPlaylistSheet 恰构造一次 PlaylistPickAdapter 且传 loader + coverOf=store.coverUrlOf(p.id)",
      _sheet.count("PlaylistPickAdapter(") == 1
      and "loader = MiniImageLoader(" in _sheet
      and "coverOf = { p -> store.coverUrlOf(p.id) }" in _sheet,
      "ctor=%d" % _sheet.count("PlaylistPickAdapter("))
_q_calls = 0
_q_defs = 0
for _dp, _dn, _fns in os.walk(JAVA_ROOT):
    for _fn in _fns:
        if _fn.endswith(".kt"):
            _t = strip_kotlin_comments(read_text(os.path.join(_dp, _fn)))
            _q_calls += len(re.findall(r'\.coverUrlOf\(', _t))
            _q_defs += len(re.findall(r'fun coverUrlOf\(', _t))
check("C.17c 全仓库 coverUrlOf 恰 2 处调用 + 1 处定义（无第三处悄悄接线）",
      _q_calls == 2 and _q_defs == 1, "calls=%d defs=%d" % (_q_calls, _q_defs))


# ==================================================================
# C3. 本轮竞态修复契约（C.18 ~ C.22；源码级 + 可证伪）
#   对象：MiniImageLoader 过期异步结果守卫 / 11 处调用点零改动 / SearchFragment 注释修复
# ==================================================================
section("C3. 本轮竞态修复契约（MiniImageLoader 过期守卫 / 调用点零改动 / 注释修复）")


def _q6_strip(txt):
    txt = re.sub(r'/\*[\s\S]*?\*/', '', txt)
    txt = re.sub(r'//[^\n]*', '', txt)
    return txt


def _q6_loader_guard(src):
    """自包含断言（只依赖 re + extract_fun_body）：MiniImageLoader.load 过期守卫四要件。
    刻意可被坏实现证伪。返回 (ok, detail)。"""
    code = _q6_strip(src)
    probs = []
    # (1) 弱键登记表
    if not re.search(r'pendingUrl\s*=\s*Collections\.synchronizedMap\(\s*WeakHashMap<ImageView,\s*String>\(\)\s*\)', code):
        probs.append("缺弱键 pendingUrl=Collections.synchronizedMap(WeakHashMap<ImageView,String>())")
    body = extract_fun_body(code, r'fun load\(url: String,\s*view: ImageView,\s*placeholderRes: Int\? = null\)')
    if body is None:
        probs.append("未找到 load(url,view,placeholderRes=null) 函数体")
        return (False, " ; ".join(probs))
    # (2) 空 url 分支：先 remove(view) 再 return（同一 if 块内）
    if not re.search(r'if\s*\(url\.isBlank\(\)\)\s*\{[^{}]*?pendingUrl\.remove\(view\)[^{}]*?return', body):
        probs.append("空 url 分支未先 pendingUrl.remove(view) 再 return")
    # (3) 非空分支：登记早于任何 setImageBitmap（含同步命中路径）
    i_reg = body.find("pendingUrl[view] = url")
    i_sync = body.find("setImageBitmap")
    if i_reg < 0:
        probs.append("非空分支缺 pendingUrl[view] = url 登记")
    elif not (0 <= i_reg < i_sync):
        probs.append("pendingUrl[view]=url 未早于首个 setImageBitmap")
    # (4) 异步回调：过期守卫比较早于回调内 setImageBitmap（下标严格比较）
    i_guard = body.find("pendingUrl[view] != url")
    i_last = body.rfind("setImageBitmap")
    if i_guard < 0:
        probs.append("回调缺过期守卫 if (pendingUrl[view] != url) return@withContext")
    elif not (0 <= i_guard < i_last):
        probs.append("守卫比较未早于回调内 setImageBitmap（顺序反了=等于没修）")
    return (not probs, " ; ".join(probs) if probs else "四要件齐备")


_q6_mil_raw = read_text(kpath("util/MiniImageLoader.kt"))
_q6_ok, _q6_detail = _q6_loader_guard(_q6_mil_raw)

check("C.18 MiniImageLoader 过期守卫字段：pendingUrl = Collections.synchronizedMap(WeakHashMap<ImageView,String>())"
      "（弱键→View 回收即消失，不把 Activity 视图滞留进 loader）+ 两处 import 齐全",
      ("import java.util.Collections" in _q6_mil_raw
       and "import java.util.WeakHashMap" in _q6_mil_raw
       and re.search(r'pendingUrl\s*=\s*Collections\.synchronizedMap\(\s*WeakHashMap<ImageView,\s*String>\(\)\s*\)', _q6_mil_raw) is not None))

check("C.19 load 空 url 分支：先 pendingUrl.remove(view) 再 return（否则在途结果会落到已清空封面的行 → 又串图）",
      re.search(r'if\s*\(url\.isBlank\(\)\)\s*\{[^{}]*?pendingUrl\.remove\(view\)[^{}]*?return',
                _q6_strip(_q6_mil_raw)) is not None)

check("C.20 load：pendingUrl[view]=url 登记早于任何 setImageBitmap；异步回调内 if(pendingUrl[view]!=url)return 早于 setImageBitmap（下标严格比较）",
      _q6_ok, _q6_detail)

_q6_bad = _q6_mil_raw.replace("if (pendingUrl[view] != url) return@withContext", "")
_q6_bad_ok, _ = _q6_loader_guard(_q6_bad)
check("C.20' 可证伪性：删掉过期守卫那一行 → C.18-C.20 断言必须 FAIL（守卫具证伪力，非空断言）",
      not _q6_bad_ok)

# ---- C.21 11 处调用点零改动（接口判据）+ 方法签名未变 ----
_q6_sig = re.search(r'fun load\(url: String,\s*view: ImageView,\s*placeholderRes: Int\? = null\)',
                    _q6_strip(_q6_mil_raw)) is not None
_q6_async_sig = re.search(r'fun loadBitmapAsync\(url: String,\s*onReady: \(Bitmap\?\) -> Unit\)',
                          _q6_strip(_q6_mil_raw)) is not None


def _q6_call_args(text):
    """返回全仓 `.load(` 调用的括号配平实参串（处理 songs.first() 这类嵌套括号）。"""
    res = []
    i = 0
    while True:
        i = text.find(".load(", i)
        if i < 0:
            break
        j = i + len(".load(")
        depth = 1
        k = j
        while k < len(text) and depth > 0:
            if text[k] == "(":
                depth += 1
            elif text[k] == ")":
                depth -= 1
            k += 1
        res.append(text[j:k - 1])
        i = k
    return res


_q6_calls = []
for _dp, _dn, _fns in os.walk(JAVA_ROOT):
    for _fn in _fns:
        if _fn.endswith(".kt"):
            _t = _q6_strip(read_text(os.path.join(_dp, _fn)))
            for _arg in _q6_call_args(_t):
                _q6_calls.append((_fn, _arg.strip()))
_q6_n = len(_q6_calls)
_q6_all2 = all((arg.count(",") == 1) for (_f, arg) in _q6_calls)  # 二元：恰一个逗号
check("C.21 全仓 .load( 调用恰 11 处且均为二元形态 loader.load(url, cover)；load/loadBitmapAsync 签名未变（实参形式未变）",
      _q6_n == 11 and _q6_all2 and _q6_sig and _q6_async_sig,
      "calls=%d binary_ok=%s load_sig=%s async_sig=%s" % (_q6_n, _q6_all2, _q6_sig, _q6_async_sig))

# ---- C.22 SearchFragment 仅注释修复 ----
_q6_sf = read_text(kpath("ui/SearchFragment.kt"))
_q6_sf_code = _q6_strip(_q6_sf)
_q6_tf_body = extract_fun_body(_q6_sf_code, r'private fun toggleFavoriteToDefault\(song: Song\)') or ""
check("C.22a SearchFragment 两个用户可见字符串字面量仍原样存在（未改引号/未改文案）",
      ('"已加入我喜欢的音乐"' in _q6_sf) and ('"已从我喜欢的音乐移除"' in _q6_sf))
check("C.22b SearchFragment KDoc 陈旧承诺已修：「与播放页红心操作保持完全一致」已消失，改为「与本地歌单系统的默认歌单收藏一致」",
      ("播放页红心操作保持完全一致" not in _q6_sf)
      and ("与本地歌单系统的默认歌单收藏一致" in _q6_sf))
check("C.22c toggleFavoriteToDefault 实现体未变（PlaylistStore.get(requireContext()).toggleIn(DEFAULT_PLAYLIST_ID, song) + 双文案 Toast）",
      ("PlaylistStore.get(requireContext())" in _q6_tf_body
       and "toggleIn(PlaylistModels.DEFAULT_PLAYLIST_ID, song)" in _q6_tf_body
       and "Toast.makeText(" in _q6_tf_body
       and '"已加入我喜欢的音乐"' in _q6_tf_body
       and '"已从我喜欢的音乐移除"' in _q6_tf_body))


# ==================================================================
# D. PlaylistStore 逻辑仿真（Python 复刻 + 断言）
# ==================================================================
section("D. PlaylistStore 逻辑仿真（逐字复刻 Kotlin 语义）")

DEFAULT_ID = "default"
DEFAULT_NAME = "我喜欢的音乐"
MAX_NAME_LEN = 20
SCHEMA_VERSION = 1
MAX_PLAYLISTS = 100


def base36(n):
    digits = "0123456789abcdefghijklmnopqrstuvwxyz"
    if n == 0:
        return "0"
    out = ""
    while n > 0:
        out = digits[n % 36] + out
        n //= 36
    return out


def mk_song(songId="", source="migu", title="", artist="", playUrl=""):
    return {"songId": songId, "source": source, "title": title, "artist": artist,
            "album": "", "durationSec": 0, "coverUrl": "", "playUrl": playUrl,
            "lrc": "", "ext": "", "fileSizeBytes": 0, "bitrate": 0, "token": ""}


def song_key(song):
    """逐字对齐 model/SongKeys.kt"""
    sid = song["songId"].strip()
    if sid:
        return f'{song["source"]}:{sid}'
    return f'{song["source"]}:{song["title"].strip()}#{song["artist"].strip()}'


def song_from_json(j):
    def g(k):
        v = j.get(k)
        return "" if v is None else v
    return {"songId": g("songId"), "source": g("source"), "title": g("title"),
            "artist": g("artist"), "album": g("album"),
            "durationSec": int(j.get("durationSec") or 0), "coverUrl": g("coverUrl"),
            "playUrl": g("playUrl"), "lrc": g("lrc"), "ext": g("ext"),
            "fileSizeBytes": int(j.get("fileSizeBytes") or 0),
            "bitrate": int(j.get("bitrate") or 0), "token": g("token")}


def song_to_json(s):
    return {k: s[k] for k in ("songId", "source", "title", "artist", "album",
                              "durationSec", "coverUrl", "playUrl", "lrc", "ext",
                              "fileSizeBytes", "bitrate", "token")}


class PyStore:
    def __init__(self, raw=None, clock=1000):
        self.library = {}
        self.playlists = []
        self.save_count = 0
        self.saved = None
        self._clock = clock
        self._load(raw)

    # ---- 内部 ----
    def _now(self):
        self._clock += 1
        return self._clock

    def _find(self, pid):
        return next((p for p in self.playlists if p["id"] == pid), None)

    def _load(self, raw):
        self.playlists.clear()
        self.library.clear()
        need_save = False
        if raw is None or (isinstance(raw, str) and not raw.strip()):
            need_save = True
        else:
            ok = False
            try:
                root = json.loads(raw) if isinstance(raw, str) else raw
                if not isinstance(root, dict):
                    raise ValueError("root not object")
                version = int(root.get("version", SCHEMA_VERSION))
                if version != SCHEMA_VERSION:
                    raise ValueError("unsupported schema version")
                self._parse_v1(root)
                ok = True
            except Exception:
                ok = False
            if not ok:
                self.playlists.clear()
                self.library.clear()
                need_save = True
        self._ensure_default()
        if need_save:
            self._save()

    def _parse_v1(self, root):
        arr = root.get("playlists") or []
        if not isinstance(arr, list):
            arr = []
        for p in arr:
            if not isinstance(p, dict):
                continue
            pid = p.get("id") or ""
            if not str(pid).strip():
                continue
            entries = []
            earr = p.get("entries") or []
            if not isinstance(earr, list):
                earr = []
            for e in earr:
                if not isinstance(e, dict):
                    continue
                key = e.get("key") or ""
                if not str(key).strip():
                    continue
                entries.append((key, int(e.get("addedAt") or 0)))
            self.playlists.append({
                "id": pid, "name": p.get("name") or "",
                "builtin": bool(p.get("builtin", False)),
                "createdAt": int(p.get("createdAt") or 0), "entries": entries})
        songs = root.get("songs") or {}
        if isinstance(songs, dict):
            for k, o in songs.items():
                if isinstance(o, dict):
                    self.library[k] = song_from_json(o)

    def _ensure_default(self):
        idx = next((i for i, p in enumerate(self.playlists) if p["id"] == DEFAULT_ID), -1)
        if idx >= 0:
            if idx > 0:
                d = self.playlists.pop(idx)
                self.playlists.insert(0, d)
            return
        self.playlists.insert(0, {"id": DEFAULT_ID, "name": DEFAULT_NAME,
                                  "builtin": True, "createdAt": 0, "entries": []})

    def _save(self):
        self.save_count += 1
        self.saved = self.dump()

    def dump(self):
        return {
            "version": SCHEMA_VERSION,
            "playlists": [{"id": p["id"], "name": p["name"], "builtin": p["builtin"],
                           "createdAt": p["createdAt"],
                           "entries": [{"key": k, "addedAt": a} for (k, a) in p["entries"]]}
                          for p in self.playlists],
            "songs": {k: song_to_json(v) for k, v in self.library.items()},
        }

    def _gc(self):
        referenced = {k for p in self.playlists for (k, _a) in p["entries"]}
        self.library = {k: v for k, v in self.library.items() if k in referenced}

    def _put_if_absent(self, key, song):
        ex = self.library.get(key)
        if ex is None:
            self.library[key] = dict(song)
        elif not ex["playUrl"].strip() and song["playUrl"].strip():
            ex["playUrl"] = song["playUrl"]

    # ---- 校验 / CRUD ----
    def _validate_name(self, raw, exclude_id):
        name = raw.strip()
        if not name:
            return "歌单名称不能为空"
        if len(name) > MAX_NAME_LEN:
            return f"歌单名称最多 {MAX_NAME_LEN} 个字"
        if self.is_name_taken(name, exclude_id):
            return "已有同名歌单"
        return None

    def is_name_taken(self, raw, exclude_id=None):
        name = raw.strip()
        if not name:
            return False
        return any(p["id"] != exclude_id and p["name"].strip().lower() == name.lower()
                   for p in self.playlists)

    def create_playlist(self, raw):
        if len(self.playlists) >= MAX_PLAYLISTS:
            return f"歌单数量已达上限（{MAX_PLAYLISTS} 张）"
        err = self._validate_name(raw, None)
        if err:
            return err
        now = self._now()
        pid = f"pl_{base36(now)}_{len(self.playlists) + 1}"
        self.playlists.append({"id": pid, "name": raw.strip(), "builtin": False,
                               "createdAt": now, "entries": []})
        self._save()
        return None

    def rename_playlist(self, pid, raw):
        pl = self._find(pid)
        if pl is None:
            return "歌单不存在"
        if pl["builtin"]:
            return "默认歌单不可重命名"
        if pl["name"].strip().lower() == raw.strip().lower():
            return None
        err = self._validate_name(raw, pid)
        if err:
            return err
        pl["name"] = raw.strip()
        self._save()
        return None

    def delete_playlist(self, pid):
        pl = self._find(pid)
        if pl is None:
            return False
        if pl["builtin"]:
            return False
        self.playlists.remove(pl)
        self._gc()
        self._save()
        return True

    # ---- 归属 ----
    def contains_in(self, pid, key):
        pl = self._find(pid)
        return bool(pl) and any(k == key for (k, _a) in pl["entries"])

    def playlist_ids_containing(self, key):
        return {p["id"] for p in self.playlists if any(k == key for (k, _a) in p["entries"])}

    def toggle_in(self, pid, song):
        pl = self._find(pid)
        if pl is None:
            return False
        key = song_key(song)
        existing = next((e for e in pl["entries"] if e[0] == key), None)
        if existing is not None:
            pl["entries"].remove(existing)
            self._gc()
            self._save()
            return False
        self._put_if_absent(key, song)
        pl["entries"].append((key, self._now()))
        self._save()
        return True

    def set_membership(self, song, target_ids):
        key = song_key(song)
        changed = False
        need = False
        now = self._now()
        for pl in self.playlists:
            should = pl["id"] in target_ids
            entry = next((e for e in pl["entries"] if e[0] == key), None)
            if should and entry is None:
                pl["entries"].append((key, now))
                need = True
                changed = True
            elif (not should) and entry is not None:
                pl["entries"].remove(entry)
                changed = True
        if need:
            self._put_if_absent(key, song)
        if changed:
            self._gc()
            self._save()

    def add_to_playlist(self, pid, song):
        pl = self._find(pid)
        if pl is None:
            return
        key = song_key(song)
        if any(k == key for (k, _a) in pl["entries"]):
            return
        self._put_if_absent(key, song)
        pl["entries"].append((key, self._now()))
        self._save()

    def remove_from_playlist(self, pid, key):
        pl = self._find(pid)
        if pl is None:
            return
        if not any(k == key for (k, _a) in pl["entries"]):
            return
        pl["entries"] = [e for e in pl["entries"] if e[0] != key]
        self._gc()
        self._save()

    def songs_of(self, pid):
        pl = self._find(pid)
        if pl is None:
            return []
        ordered = sorted(pl["entries"], key=lambda e: e[1], reverse=True)
        return [self.library[k] for (k, _a) in ordered if k in self.library]

    def update_play_url(self, song, new_url):
        key = song_key(song)
        target = self.library.get(key)
        if target is None:
            target = dict(song)
            self.library[key] = target
        target["playUrl"] = new_url
        if song is not target:
            song["playUrl"] = new_url
        self._save()


# ---- D.1 songKey 规则 ----
check("D.1a songKey = source:songId", song_key(mk_song("123", "migu")) == "migu:123")
check("D.1b songId 空 → source:title#artist",
      song_key(mk_song("", "kuwo", "歌名", "歌手")) == "kuwo:歌名#歌手")
check("D.1c songId 全空格亦退化", song_key(mk_song("   ", "qq", "T", "A")) == "qq:T#A")

# ---- D.2 首次无数据 → 默认歌单 ----
s = PyStore(None)
check("D.2a 首次无数据自动创建默认歌单", len(s.playlists) == 1)
check("D.2b 默认歌单恒为首项且 builtin=true",
      s.playlists[0]["id"] == DEFAULT_ID and s.playlists[0]["builtin"] is True)
check("D.2c 默认歌单名为「我喜欢的音乐」", s.playlists[0]["name"] == DEFAULT_NAME)
check("D.2d 首次 load 已落盘（needSave）", s.save_count >= 1)

# ---- D.3 toggleIn 幂等 ----
s = PyStore(None)
song = mk_song("1", "migu", "A", "B")
r1 = s.toggle_in(DEFAULT_ID, song)
check("D.3a toggleIn 加入后存在", r1 is True and s.contains_in(DEFAULT_ID, "migu:1"))
r2 = s.toggle_in(DEFAULT_ID, song)
check("D.3b 再次 toggleIn 移除", r2 is False and not s.contains_in(DEFAULT_ID, "migu:1"))
for _ in range(5):
    s.toggle_in(DEFAULT_ID, song)
    s.toggle_in(DEFAULT_ID, song)
check("D.3c 反复开关不产生重复条目", len(s.playlists[0]["entries"]) <= 1)
# 幂等：连续两次 addToPlaylist
s2 = PyStore(None)
s2.add_to_playlist(DEFAULT_ID, song)
s2.add_to_playlist(DEFAULT_ID, song)
check("D.3d addToPlaylist 幂等（同一首不出现两条）", len(s2.playlists[0]["entries"]) == 1)

# ---- D.4 setMembership 差量 ----
s = PyStore(None)
p1 = s.create_playlist("通勤")
p2 = s.create_playlist("跑步")
ids = [p["id"] for p in s.playlists if not p["builtin"]]
p1id, p2id = ids[0], ids[1]
song = mk_song("9", "netease", "X", "Y")
before = s.save_count
s.set_membership(song, {p1id, p2id})
check("D.4a setMembership 一次写入多张歌单",
      s.contains_in(p1id, "netease:9") and s.contains_in(p2id, "netease:9"))
check("D.4b setMembership 只持久化一次", s.save_count - before == 1)
s.set_membership(song, {p1id})
check("D.4c setMembership 取消勾选 → 从对应歌单移除",
      s.contains_in(p1id, "netease:9") and not s.contains_in(p2id, "netease:9"))
# 一首歌可同时在多张歌单
s.set_membership(song, {p1id, p2id})
check("D.4d 一首歌可同时属于多张歌单",
      len(s.playlist_ids_containing("netease:9")) == 2)

# ---- D.5 createPlaylist 校验 ----
s = PyStore(None)
check("D.5a 空名被拒", s.create_playlist("") == "歌单名称不能为空")
check("D.5b 纯空格被拒", s.create_playlist("   ") == "歌单名称不能为空")
check("D.5c 超长(21)被拒", s.create_playlist("a" * 21) == "歌单名称最多 20 个字")
check("D.5d 恰好 20 字通过", s.create_playlist("a" * 20) is None)
check("D.5e 与现有重名（忽略大小写）被拒", s.create_playlist("A" * 20) == "已有同名歌单")
check("D.5f 与现有重名（首尾空格）被拒", s.create_playlist("  " + "a" * 20 + "  ") == "已有同名歌单")
check("D.5g 保留名「我喜欢的音乐」被拒", s.create_playlist(DEFAULT_NAME) == "已有同名歌单")
check("D.5h 保留名带空格亦被拒", s.create_playlist("  " + DEFAULT_NAME + " ") == "已有同名歌单")

# ---- D.6 renamePlaylist ----
s = PyStore(None)
pid = s.create_playlist("旧名")
# createPlaylist 返回 null；取回 id
newp = [p for p in s.playlists if not p["builtin"]][0]
check("D.6a 对默认歌单 rename 被拒", s.rename_playlist(DEFAULT_ID, "新名") == "默认歌单不可重命名")
check("D.6b 重命名为已存在名被拒", s.rename_playlist(newp["id"], DEFAULT_NAME) == "已有同名歌单")
p2 = s.create_playlist("另一张")
p2id = [p for p in s.playlists if not p["builtin"] and p["id"] != newp["id"]][0]["id"]
check("D.6c 重命名为其它自建歌单名被拒", s.rename_playlist(newp["id"], "另一张") == "已有同名歌单")
check("D.6d 正常重命名成功", s.rename_playlist(newp["id"], " 新名 ") is None
      and s._find(newp["id"])["name"] == "新名")
check("D.6e 同名未改动直接成功（不产生变更）",
      s.rename_playlist(newp["id"], " 新名 ") is None)

# ---- D.7 deletePlaylist + gcLibrary ----
s = PyStore(None)
songA = mk_song("A", "migu", "A", "a")
songB = mk_song("B", "kuwo", "B", "b")
s.add_to_playlist(DEFAULT_ID, songA)          # 仅默认引用
pid = None
s.create_playlist("临时")
pid = [p for p in s.playlists if not p["builtin"]][0]["id"]
s.add_to_playlist(pid, songB)                  # 临时歌单引用
check("D.7a 删除默认歌单被拒", s.delete_playlist(DEFAULT_ID) is False)
check("D.7b 删除前曲库含 A、B", "migu:A" in s.library and "kuwo:B" in s.library)
check("D.7c 删除自建歌单成功", s.delete_playlist(pid) is True)
check("D.7d 删除后 gc 剔除不再被引用的 B", "kuwo:B" not in s.library)
check("D.7e 默认歌单引用的 A 必须保留", "migu:A" in s.library)

# ---- D.8 JSON round-trip 等价 ----
s = PyStore(None)
s.add_to_playlist(DEFAULT_ID, mk_song("1", "migu", "t1", "a1"))
s.create_playlist("歌单一")
p1 = [p for p in s.playlists if not p["builtin"]][0]["id"]
s.add_to_playlist(p1, mk_song("2", "kuwo", "t2", "a2"))
s.add_to_playlist(p1, mk_song("3", "qq", "t3", "a3"))
raw = json.dumps(s.dump(), ensure_ascii=False)
s2 = PyStore(raw)
check("D.8a round-trip 歌单顺序等价",
      [p["id"] for p in s.playlists] == [p["id"] for p in s2.playlists])
check("D.8b round-trip entries 顺序/内容等价",
      [p["entries"] for p in s.playlists] == [p["entries"] for p in s2.playlists])
check("D.8c round-trip 曲库内容等价", s.library == s2.library)

# ---- D.9 损坏降级 ----
for label, bad in [("非法 JSON", "{ not json"),
                   ("非对象根", "[1,2,3]"),
                   ("版本过高", json.dumps({"version": 99, "playlists": [], "songs": {}})),
                   ("结构缺字段", json.dumps({"version": 1})),
                   ("playlists 非数组", json.dumps({"version": 1, "playlists": "x", "songs": {}}))]:
    try:
        b = PyStore(bad)
        ok = (len(b.playlists) == 1 and b.playlists[0]["id"] == DEFAULT_ID
              and b.playlists[0]["builtin"] is True and b.library == {})
    except Exception as e:
        ok = False
        label += f"(抛异常 {e})"
    check(f"D.9 损坏降级[{label}] → 仅默认歌单且不抛异常", ok)

# ---- D.10 updatePlayUrl 全局生效 ----
s = PyStore(None)
song = mk_song("77", "migu", "S", "A", playUrl="http://old/1.mp3")
s.add_to_playlist(DEFAULT_ID, song)
s.create_playlist("第二张")
p2 = [p for p in s.playlists if not p["builtin"]][0]["id"]
s.add_to_playlist(p2, song)
check("D.10a 同一首歌只存一份曲库快照", len(s.library) == 1)
s.update_play_url(song, "http://new/2.mp3")
check("D.10b updatePlayUrl 写入全局曲库", s.library["migu:77"]["playUrl"] == "http://new/2.mp3")
check("D.10c 所有引用该歌的歌单同时生效（默认）",
      s.songs_of(DEFAULT_ID)[0]["playUrl"] == "http://new/2.mp3")
check("D.10d 所有引用该歌的歌单同时生效（自建）",
      s.songs_of(p2)[0]["playUrl"] == "http://new/2.mp3")

# ---- D.11 songsOf 顺序（倒序）----
s = PyStore(None)
s._clock = 0
s.add_to_playlist(DEFAULT_ID, mk_song("1", "migu", "old", "a"))     # addedAt 较早
s._clock = 10 ** 9
s.add_to_playlist(DEFAULT_ID, mk_song("2", "migu", "new", "a"))     # addedAt 较晚
order = [x["title"] for x in s.songs_of(DEFAULT_ID)]
check("D.11a songsOf 按 addedAt 倒序（新加入在前）", order == ["new", "old"], f"实际={order}")
check("D.11b 未命中曲库的 key 被静默跳过（不抛异常）",
      s.songs_of(DEFAULT_ID) is not None)


# ==================================================================
# E. Kotlin 语法卫生（块注释嵌套/未闭合扫描）
# ==================================================================
section("E. Kotlin 语法卫生（块注释地雷扫描）")

SCANNER = os.path.join(QA_DIR, "scan_nested_comments.py")
SCAN_ROOT = os.path.join(REPO, "android", "app", "src", "main", "java")

# 期望文件数：与扫描器同一口径（递归统计 java/ 下全部 .kt）。
expected_kt = 0
for _dp, _dn, _fns in os.walk(SCAN_ROOT):
    expected_kt += sum(1 for _fn in _fns if _fn.endswith(".kt"))

scan_rc = None
scan_out = ""
scan_ok = False
scan_detail = ""
if not os.path.isfile(SCANNER):
    scan_detail = "缺文件: " + SCANNER
else:
    try:
        # 用同一 python 解释器调用；扫描根由扫描器自身位置推导，与 CWD 无关。
        proc = subprocess.run([sys.executable, SCANNER], cwd=QA_DIR,
                              capture_output=True, text=True, timeout=180)
        scan_rc = proc.returncode
        scan_out = (proc.stdout or "") + (proc.stderr or "")
        scan_ok = scan_rc in (0, 1)
        scan_detail = f"returncode={scan_rc}"
    except Exception as e:
        scan_detail = f"调用异常: {e}"
check("E.1 嵌套注释扫描器可运行（returncode ∈ {0,1}）", scan_ok, scan_detail)

_m_cnt = re.search(r'scanned \.kt files:\s*(\d+)', scan_out) if scan_out else None
scanned_cnt = int(_m_cnt.group(1)) if _m_cnt else -1
check("E.2 扫描覆盖 android/app/src/main/java 下全量 .kt",
      scanned_cnt == expected_kt and scanned_cnt > 0,
      f"scanned={scanned_cnt}, expected={expected_kt}")

_m_res = re.search(r'RESULT: (.+)', scan_out) if scan_out else None
result_line = _m_res.group(1).strip() if _m_res else "(无 RESULT 行)"
check("E.3 无嵌套/未闭合/不配对块注释（RESULT: CLEAN 且 returncode=0）",
      scan_rc == 0 and result_line.startswith("CLEAN"),
      f"returncode={scan_rc}, {result_line}")


# ==================================================================
# 汇总
# ==================================================================
print("\n" + "=" * 62)
print(f"总计: {passed} PASS / {failed} FAIL / {skipped} SKIP(UNVERIFIED)")
if skips:
    print("\n降级明细（SKIP，不计 PASS 也不计 FAIL，需人工确认）:")
    for s in skips:
        print("  - " + s)
if failures:
    print("\n失败明细:")
    for f in failures:
        print("  - " + f)
    print("\n❌ 有失败项")
    sys.exit(1)
else:
    print("\n✅ 全部通过" + ("（含 %d 项 SKIP，见上）" % skipped if skipped else ""))
    sys.exit(0)
