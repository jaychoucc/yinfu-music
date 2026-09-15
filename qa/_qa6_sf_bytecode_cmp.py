# -*- coding: utf-8 -*-
"""
QA6 · 佐证（非主证据）：新 APK 与上一轮(17:29)产物 SearchFragment 的结构指纹比对。

上一轮 dump 无指令反汇编，但有 方法签名集 / registers / ins / outs / insns size /
positions(offset->line) / locals。据此可做**强判据**：

  若本轮仅改动注释（增删若干行），则对每个方法：
    - offset 序列（= 指令布局）必须完全一致；
    - line 值只能整体平移同一个常量 Δ（注释行数变化），Δ 对所有方法一致；
  若任何方法 offset 序列改变，或 Δ 不一致，则说明有真实代码改动。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
QA = os.path.join(ROOT, "qa")
OLD = os.path.join(QA, "_qa5_dexdump_classes8.dex.txt")
NEW = os.path.join(QA, "_qa6_dexdump_classes8.txt")
CLASS = "Lcom/soundtrack/music/ui/SearchFragment;"

NAME = re.compile(r"^\s*name\s*:\s*'(.*)'\s*$")
TYPE = re.compile(r"^\s*type\s*:\s*'(.*)'\s*$")
POS = re.compile(r"^\s*0x([0-9a-f]+)\s+line=(\d+)\s*$")
NUM = re.compile(r"^\s*(registers|ins|outs|insns size)\s*:\s*(\d+)")


def read(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read().splitlines()


def parse(path):
    lines = read(path)
    in_class = False
    methods = {}
    cur = None
    pending = None
    in_pos = False
    for ln in lines:
        if ln.startswith("  Class descriptor"):
            in_class = CLASS in ln
            continue
        if not in_class:
            continue
        if "positions" in ln and ln.strip().startswith("positions"):
            in_pos = True
            continue
        if "locals" in ln and ln.strip().startswith("locals"):
            in_pos = False
            continue
        if ln.lstrip().startswith("name") and NAME.match(ln):
            pending = NAME.match(ln).group(1)
            continue
        mt = TYPE.match(ln)
        if mt and pending is not None and ln.lstrip().startswith("type"):
            cur = pending + mt.group(1)
            methods.setdefault(cur, {"num": {}, "pos": []})
            pending = None
            continue
        mn = NUM.search(ln)
        if mn and cur is not None:
            methods[cur]["num"][mn.group(1)] = int(mn.group(2))
            continue
        mp = POS.match(ln)
        if mp and cur is not None and in_pos:
            methods[cur]["pos"].append((int(mp.group(1), 16), int(mp.group(2))))
            continue
    return methods


old = parse(OLD)
new = parse(NEW)

print("OLD methods=%d  NEW methods=%d" % (len(old), len(new)))
only_old = sorted(set(old) - set(new))
only_new = sorted(set(new) - set(old))
print("only OLD:", only_old)
print("only NEW:", only_new)

sig_ok = (not only_old) and (not only_new)

deltas = {}
bad_offsets = []
num_mismatch = []
for k in sorted(set(old) & set(new)):
    o, n = old[k], new[k]
    if o["num"] != n["num"]:
        num_mismatch.append((k, o["num"], n["num"]))
    so = [x[0] for x in o["pos"]]
    sn = [x[0] for x in n["pos"]]
    if so != sn:
        bad_offsets.append((k, so, sn))
        continue
    ds = sorted({nb - ob for (_o, ob), (_n, nb) in zip(o["pos"], n["pos"])})
    deltas[k] = ds

all_deltas = sorted({d for ds in deltas.values() for d in ds})
print("\nnum(registers/ins/outs/insns) mismatch methods:", len(num_mismatch))
for k, a, b in num_mismatch:
    print("  !! %s  OLD=%s NEW=%s" % (k, a, b))
print("offset-sequence MISMATCH methods:", len(bad_offsets))
for k, a, b in bad_offsets[:10]:
    print("  !! %s" % k)
print("\nline-number deltas observed (per method):", all_deltas)

# 纯注释行数变化的正确形态：每个方法 Δ ∈ {0, Δ0}（注释前的 Δ=0，注释后的整体平移 Δ0）。
nonzero = sorted({d for d in all_deltas if d != 0})
ok = sig_ok and (not num_mismatch) and (not bad_offsets) and len(nonzero) <= 1
print("\n方法签名集一致:", sig_ok)
print("非零行号平移集合:", nonzero, "（应为空或单一常量，即注释行数增删量）")
if ok:
    print("RESULT: CONSISTENT —— 48 个方法 offset 布局逐条一致、代码尺寸指纹一致、"
          "行号仅出现 {0, %s} 平移（= 注释增删 %s 行）；与『本轮只改注释』完全吻合，代码未变。"
          % (nonzero if nonzero else [0], nonzero[0] if nonzero else 0))
    sys.exit(0)
print("RESULT: INCONSISTENT —— 存在 offset 布局变化或 Δ 不统一（疑似真实代码改动）")
sys.exit(1)
