#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""从已捕获的 aapt/aapt2 输出抽取关键行, 生成集中证据文件 _qa2_apk_verify.txt"""
import io, os, re

root = r"C:\Users\b5311\WorkBuddy\2026-09-15-10-37-12\yinfu-music"
qa = os.path.join(root, "qa")

def read(p):
    with io.open(p, "r", encoding="utf-8", errors="replace") as f:
        return f.read().splitlines()

res = read(os.path.join(qa, "_qa2_aapt2_resources.txt"))
tree = read(os.path.join(qa, "_qa2_xmltree.txt"))

out = []
def emit(s=""): out.append(s)

emit("=" * 100)
emit("QA(Edward) APK 产物独立核验 — 集中证据")
emit("APK: android/app/build/outputs/apk/debug/yinfu-music-1.0.0-20260915.1410.apk (9,701,956 B)")
emit("=" * 100)
emit()
emit("### A. aapt2 dump resources — player_ctrl_* 四个 dimen 的全部 config 解析值(客观产物表)")
emit("-" * 100)
inblock = False
for ln in res:
    s = ln.rstrip()
    if re.search(r"resource 0x[0-9a-f]+ dimen/player_ctrl_", s):
        inblock = True
        emit(s.strip())
        continue
    if inblock:
        if re.match(r"\s+\(\S*\)\s+[\d.]+dp", s):
            emit("    " + s.strip())
        else:
            inblock = False
emit()
emit("### B. aapt dump xmltree res/layout/activity_player.xml — 关键属性(取自产物, 非源码)")
emit("-" * 100)
keep = ("layout_width(0x010100f4)", "layout_height(0x010100f5)", "layout_weight(0x01010181)",
        "layout_marginStart", "layout_marginEnd", "gravity(0x010100af)", "android:id")
ctx = None
for ln in tree:
    st = ln.strip()
    if st.startswith("E: "):
        ctx = st
    if st.startswith("A: ") and any(k in st for k in keep):
        emit("%-30s | %s" % (ctx, st))
emit()
emit("注: 按钮 width/height 为 @0x7f0703xx (dimen 引用, 非字面 dp);")
emit("    0x7f070324=btn_small, 0x7f070322=btn_mid, 0x7f070323=btn_play, 0x7f070325=row_margin_h;")
emit("    Space 的 (type 0x5)0x1 = 0dp, weight 0x3f800000 = 1.0")

txt = "\n".join(out) + "\n"
with io.open(os.path.join(qa, "_qa2_apk_verify.txt"), "w", encoding="utf-8") as f:
    f.write(txt)
print("written", len(out), "lines")
