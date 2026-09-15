#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
QA(Edward) 独立重算：播放页控制按钮行 11 档屏宽间距/裁切。
不读取工程师结论，仅依据：
  - res/values*/dimens.xml 的字面值
  - activity_player.xml 的 geometry(外层 padding=24, row marginStart/End=mh, 4xSpace weight=1, gravity=center)
  - 资源限定符匹配规则(isBetterThan): smallestWidth 优先于 availableWidth
并交叉核对 aapt2 dump resources 的实际产物表。
"""
import io, sys

# ---- 各 config 的 dimen 字面值 ----
CFG = {
    "default": dict(small=40, mid=48, play=56, mh=0),
    "w360dp":  dict(small=44, mid=52, play=68, mh=0),
    "sw600dp": dict(small=44, mid=52, play=68, mh=120),
    "sw800dp": dict(small=44, mid=52, play=68, mh=220),
}

# ---- 从 aapt2 dump resources 观察到的"实际"表(含去重结果)：每个资源各自可用的 config 列表 ----
# 按钮 3 个 dimen: sw800dp 因与 sw600dp 同值被去重掉 -> 只有 default/w360dp/sw600dp
BTN_AVAIL = ["default", "w360dp", "sw600dp"]
# margin dimen: sw800dp 值(220)唯一 -> 保留 default/w360dp/sw600dp/sw800dp
MARGIN_AVAIL = ["default", "w360dp", "sw600dp", "sw800dp"]

# Device: portrait, smallestWidth == screenWidth == W (dp)
def matches(cfg, W):
    if cfg == "default":
        return True
    if cfg.startswith("sw"):
        return W >= int(cfg[2:-2])
    if cfg.startswith("w"):
        return W >= int(cfg[1:-2])
    raise ValueError(cfg)

def rank(cfg, W):
    """越大越优先。规则: 指定 sw 的 config 一律优于未指定 sw 的; 同类别越大越优先。
       依据 AOSP ResTable_config::isBetterThan: smallestScreenWidthDp 先于 screenWidthDp 比较,
       且'设置了 sw 的 config'优于'未设置 sw 的 config'。"""
    if cfg == "default":
        return (0, 0)          # 未设置任何宽度限定符
    if cfg.startswith("sw"):
        return (2, int(cfg[2:-2]))
    if cfg.startswith("w"):
        return (1, int(cfg[1:-2]))  # 设置了 availableWidth, 但无 sw
    raise ValueError(cfg)

def resolve(avail, W):
    cands = [c for c in avail if matches(c, W)]
    best = max(cands, key=lambda c: rank(c, W))
    return best

def body(small, mid, play):
    return small * 2 + mid * 2 + play

WIDTHS = [240, 280, 300, 320, 360, 384, 393, 411, 480, 600, 800]
PAD = 24  # 外层垂直 LinearLayout 左右各 24dp

lines = []
def emit(s=""):
    lines.append(s)

emit("=" * 118)
emit("QA(Edward) 独立重算表 —— 播放页控制按钮行 11 档屏宽")
emit("公式: 可用宽=W-48 ; 行可用宽=可用宽-2*mh ; 每段弹性间距=(行可用宽-按钮体)/4 ; <0 即裁切, 单侧被裁=(按钮体-行可用宽)/2")
emit("命中档位: sw(最小宽度)优先于 w(可用宽度); 同类别取较大值(更具体)")
emit("=" * 118)
emit("")
hdr = ("{:<6} {:<11} {:<8} {:<20} {:<13} {:<8} {:<9} {:<9} {:<9} {:<11} {:<9}".format(
    "屏宽", "按钮命中档", "margin档", "按钮(s/m/p)", "按钮体", "可用宽", "行可用宽", "新间距", "新单侧裁", "旧(44/52/68)", "旧单侧裁"))
emit(hdr)
emit("-" * 118)

for W in WIDTHS:
    bcfg = resolve(BTN_AVAIL, W)
    mcfg = resolve(MARGIN_AVAIL, W)
    small = CFG[bcfg]["small"]; mid = CFG[bcfg]["mid"]; play = CFG[bcfg]["play"]
    mh = CFG[mcfg]["mh"]
    b = body(small, mid, play)
    avail = W - 48
    row = avail - 2 * mh
    gap = (row - b) / 4.0
    clip = (b - row) / 2.0 if gap < 0 else 0.0
    # 旧版(上一轮): 按钮固定 44/52/68, mh=0
    ob = 44 * 2 + 52 * 2 + 68
    orow = avail
    ogap = (orow - ob) / 4.0
    oclip = (ob - orow) / 2.0 if ogap < 0 else 0.0
    status = "" if gap >= 0 else "  <== 裁切"
    emit("{:<6} {:<11} {:<8} {:<20} {:<13} {:<8} {:<9} {:<9} {:<9} {:<11} {:<9}".format(
        W, bcfg, mcfg, "%d/%d/%d" % (small, mid, play), b, avail, row,
        ("%.2f" % gap) + status, ("%.2f" % clip), "%.2f" % ogap, "%.2f" % oclip))

emit("")
emit("=" * 118)
emit("判定与说明")
emit("=" * 118)
emit("- 280dp: 新配置 gap=0.00 -> 恰好放下(4 段间距为 0, 按钮相邻但不裁切)")
emit("- 240dp: 新配置 gap<0 -> 仍裁切(单侧被裁 20dp); 相对旧版(单侧 34dp)已改善, 但未消除")
emit("- 320dp: 新 10dp(vs 旧 3dp) 改善 +7dp")
emit("- w360dp 命中区间(360-599dp): 与旧版一致(按钮组未被拉散)")
emit("- sw600dp 命中(600dp): 间距由旧 73dp -> 新 13dp")
emit("- sw800dp 命中(800dp): 间距由旧 123dp -> 新 13dp")
emit("- 设计意图: sw600/sw800 的 margin_h(120/220) 使 行可用宽 = 312dp = (360-48), 与 360dp 手机等价 -> 按钮组宽度被封顶")
emit("")

txt = "\n".join(lines) + "\n"
with io.open(sys.argv[1] if len(sys.argv) > 1 else "_qa2_spacing_table.txt", "w", encoding="utf-8") as f:
    f.write(txt)
print(txt)
