#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""生成第三轮集中证据 _qa3_apk_verify.txt"""
import io, os, re

root = r"C:\Users\b5311\WorkBuddy\2026-09-15-10-37-12\yinfu-music"
qa = os.path.join(root, "qa")

def read(p):
    with io.open(p, "r", encoding="utf-8", errors="replace") as f:
        return f.read().splitlines()

res = read(os.path.join(qa, "_qa3_aapt2_resources.txt"))
tree = read(os.path.join(qa, "_qa3_xmltree.txt"))

out = []
def E(s=""): out.append(s)

E("=" * 100)
E("QA(Edward) 第三轮 APK 产物核验 — 限宽档 sw->w 改造")
E("APK: android/app/build/outputs/apk/debug/yinfu-music-1.0.0-20260915.1422.apk (9,705,232 B)")
E("=" * 100)
E()
E("### A. aapt2 dump resources — player_ctrl_* 四个 dimen 的全部 config (产物表)")
E("-" * 100)
inb = False
for ln in res:
    s = ln.rstrip()
    if re.search(r"resource 0x[0-9a-f]+ dimen/player_ctrl_", s):
        inb = True; E(s.strip()); continue
    if inb:
        if re.match(r"\s+\(\S*\)\s+[\d.]+dp", s): E("    " + s.strip())
        else: inb = False
E()
E("观察: 三个按钮 dimen 仅 () / (w360dp) 两档; row_margin_h 为 ()0 / (w600dp)120 / (w800dp)220。")
E("      无任何 (sw600dp)/(sw800dp) 列; row_margin_h 的 (w360dp)0 因与默认同值被 aapt2 去重删除。")
E()
E("### B. packageDebugResources 合并产物(权威, 进 APK 的 res) — packaged_res/debug")
E("-" * 100)
for sub, exp in [("values-w600dp-v13\\values-w600dp-v13.xml", "w600dp"),
                 ("values-w800dp-v13\\values-w800dp-v13.xml", "w800dp"),
                 ("values-w360dp-v13\\values-w360dp-v13.xml", "w360dp"),
                 ("values\\values.xml", "default")]:
    p = os.path.join(root, "android", "app", "build", "intermediates", "packaged_res", "debug", sub)
    E("[%s] %s" % (exp, sub))
    if os.path.exists(p):
        for ln in read(p):
            if "player_ctrl" in ln: E("    " + ln.strip())
    else:
        E("    (missing)")
E()
E("### C. aapt dump xmltree res/layout/activity_player.xml — 关键属性(产物)")
E("-" * 100)
ctx = None
for ln in tree:
    st = ln.strip()
    if st.startswith("E: "): ctx = st
    if st.startswith("A: ") and any(k in st for k in (
        "layout_marginStart", "layout_marginEnd", "layout_weight(0x01010181)",
        "gravity(0x010100af)", "android:id")):
        E("%-30s | %s" % (ctx, st))
E()
E("注: 按钮 width/height 为 @0x7f07032x (dimen 引用); 行 marginStart/End=@0x7f070325(row_margin_h);")
E("    0x7f070324=small, 0x7f070322=mid, 0x7f070323=play; Space 的 0x1=0dp, weight 0x3f800000=1.0")

with io.open(os.path.join(qa, "_qa3_apk_verify.txt"), "w", encoding="utf-8") as f:
    f.write("\n".join(out) + "\n")
print("written", len(out))
