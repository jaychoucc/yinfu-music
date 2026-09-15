# -*- coding: utf-8 -*-
"""Independent static validation of activity_player.xml (QA side, throwaway)."""
import sys
import xml.etree.ElementTree as ET
from collections import Counter

NS = "{http://schemas.android.com/apk/res/android}"
PATH = r"C:\Users\b5311\WorkBuddy\2026-09-15-10-37-12\yinfu-music\android\app\src\main\res\layout\activity_player.xml"

REQUIRED = {
    "bg_blur", "btn_download", "btn_favorite", "btn_next", "btn_play",
    "btn_prev", "player_artist", "player_cover", "player_title",
    "recycler_lyrics", "seek_bar", "time_current", "time_total",
}

print("=== well-formed check ===")
try:
    tree = ET.parse(PATH)
    root = tree.getroot()
    print("WELLFORMED: OK  root=%s" % root.tag)
except Exception as e:
    print("WELLFORMED: FAIL -> %r" % e)
    sys.exit(1)

ids = []
for el in root.iter():
    v = el.get(NS + "id")
    if v:
        ids.append(v.replace("@+id/", ""))
idset = set(ids)

print("\n=== id set ===")
print("count=%d ids=%s" % (len(idset), sorted(idset)))
missing = REQUIRED - idset
extra = idset - REQUIRED
print("MISSING (required-not-found) = %s" % sorted(missing))
print("EXTRA   (found-not-required) = %s" % sorted(extra))
print("DIFF EMPTY? %s" % (len(missing) == 0 and len(extra) == 0))

print("\n=== duplicate ids ===")
dups = {k: c for k, c in Counter(ids).items() if c > 1}
print("DUPLICATES = %s" % dups)

print("\n=== Space nodes ===")
spaces = [el for el in root.iter() if el.tag == "Space"]
print("Space count = %d" % len(spaces))
for i, sp in enumerate(spaces, 1):
    w = sp.get(NS + "layout_width")
    h = sp.get(NS + "layout_height")
    wgt = sp.get(NS + "layout_weight")
    ok = (w == "0dp" and h == "0dp" and wgt == "1")
    print("  Space#%d width=%s height=%s weight=%s -> %s"
          % (i, w, h, wgt, "OK" if ok else "BAD"))

print("\n=== button sizes / margins ===")
BUTTONS = ["btn_favorite", "btn_prev", "btn_play", "btn_next", "btn_download"]
by_id = {}
for el in root.iter():
    v = el.get(NS + "id")
    if v:
        by_id[v.replace("@+id/", "")] = el
for b in BUTTONS:
    el = by_id.get(b)
    if el is None:
        print("  %s MISSING" % b)
        continue
    w = el.get(NS + "layout_width")
    h = el.get(NS + "layout_height")
    ms = el.get(NS + "layout_marginStart")
    me = el.get(NS + "layout_marginEnd")
    print("  %-13s w=%-5s h=%-5s marginStart=%s marginEnd=%s"
          % (b, w, h, ms, me))
