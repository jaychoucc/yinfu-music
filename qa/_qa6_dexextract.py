# -*- coding: utf-8 -*-
"""QA6 · 从新 APK 抽取 classes*.dex 并定位 MiniImageLoader 所在 dex（纯标准库）。"""
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APK = os.path.join(ROOT, "dist", "yinfu-music-1.0.0-20260915.1807.apk")
OUTDIR = os.path.join(ROOT, "qa", "_qa6_dexr")

os.makedirs(OUTDIR, exist_ok=True)

want = [b"MiniImageLoader", b"WeakHashMap", b"synchronizedMap",
        b"PlaylistNameDialog", b"inflateInput", b"SearchFragment"]
hits = {w: [] for w in want}
names = []
with zipfile.ZipFile(APK) as z:
    for n in z.namelist():
        if n.startswith("classes") and n.endswith(".dex"):
            names.append(n)
            data = z.read(n)
            dst = os.path.join(OUTDIR, os.path.basename(n))
            with open(dst, "wb") as f:
                f.write(data)
            for w in want:
                if w in data:
                    hits[w].append(n)

print("APK :", APK)
print("dex :", names)
for w in want:
    print("  %-20s -> %s" % (w.decode(), hits[w]))
sys.exit(0)
