#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""QA(Edward) 结构完整性独立复核：activity_player.xml（源码 + APK 产物）"""
import io, sys, re, subprocess, os
import xml.etree.ElementTree as ET

AND = "{http://schemas.android.com/apk/res/android}"
EXPECT_IDS = {"bg_blur","btn_download","btn_favorite","btn_next","btn_play","btn_prev",
              "player_artist","player_cover","player_title","recycler_lyrics","seek_bar",
              "time_current","time_total"}
EXPECT_ORDER = ["btn_favorite","btn_prev","btn_play","btn_next","btn_download"]

root = sys.argv[1]
xml_path = os.path.join(root, "android", "app", "src", "main", "res", "layout", "activity_player.xml")
out = []
def emit(s=""): out.append(s)

emit("="*90)
emit("QA(Edward) 结构完整性复核 — activity_player.xml (源码)")
emit("="*90)

# 1) well-formed
try:
    tree = ET.parse(xml_path)
    ok_wf = True
    emit("[OK] XML well-formed (ElementTree 解析成功)")
except Exception as e:
    ok_wf = False
    emit("[FAIL] XML 解析失败: %r" % e)
    print("\n".join(out)); sys.exit(1)
r = tree.getroot()

# 2) ids
ids = []
for el in r.iter():
    v = el.get(AND + "id")
    if v:
        ids.append(v.split("/")[-1])
idset = set(ids)
dups = [i for i in ids if ids.count(i) > 1]
emit("id 总数(含重复计数)=%d, 去重后=%d" % (len(ids), len(idset)))
emit("期望 13 id 与 实际 差集: 缺=%s 多=%s" % (sorted(EXPECT_IDS - idset), sorted(idset - EXPECT_IDS)))
emit("重复 id: %s" % (sorted(set(dups)) if dups else "无"))
emit("id 全部命中: %s" % ("是" if idset == EXPECT_IDS and not dups else "否"))

# 3) 5 按钮: 顺序 + 正方形 + dimen 引用
btns = [el for el in r.iter() if (el.get(AND+"id") or "").endswith(tuple("btn_"+b for b in ["favorite","prev","play","next","download"]))]
def short(el):
    i = (el.get(AND+"id") or "").split("/")[-1]
    w = el.get(AND+"layout_width"); h = el.get(AND+"layout_height")
    return i, w, h
seq = [short(el)[0] for el in btns]
emit("")
emit("按钮出现顺序: %s" % seq)
emit("顺序正确: %s" % ("是" if seq == EXPECT_ORDER else "否"))
emit("")
emit("%-14s %-34s %-34s %s" % ("id","layout_width","layout_height","正方形(同dimen引用)"))
for el in btns:
    i,w,h = short(el)
    same = (w == h and (w or "").startswith("@dimen/"))
    emit("%-14s %-34s %-34s %s" % (i, w, h, "是" if same else "否"))

# 4) 控制行: gravity=center + marginStart/End
rows = [el for el in r.iter() if el.tag.endswith("LinearLayout") and el.get(AND+"gravity") == "center"]
emit("")
emit("gravity=center 的水平行数量 = %d" % len(rows))
if rows:
    rr = rows[0]
    emit("  行 orientation     = %s" % rr.get(AND+"orientation"))
    emit("  行 gravity         = %s" % rr.get(AND+"gravity"))
    emit("  行 layout_marginStart = %s" % rr.get(AND+"layout_marginStart"))
    emit("  行 layout_marginEnd   = %s" % rr.get(AND+"layout_marginEnd"))
# 5) Space
spaces = [el for el in r.iter() if el.tag == "Space"]
sp_ok = all(s.get(AND+"layout_width")=="0dp" and s.get(AND+"layout_height")=="0dp" and s.get(AND+"layout_weight")=="1" for s in spaces)
emit("")
emit("Space 数量 = %d ; 全部为 0dp/0dp/weight=1 : %s" % (len(spaces), "是" if sp_ok else "否"))

txt = "\n".join(out) + "\n"
with io.open(os.path.join(root,"qa","_qa2_structure.txt"), "w", encoding="utf-8") as f:
    f.write(txt)
print(txt)
