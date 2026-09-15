#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
QA(Edward) 第三轮独立重算：限宽档 sw -> w 改造。
依据：本轮源码 dimens 字面值 + 产物 aapt2 表 + Android config 匹配规则。
"""
import io, os, sys

# 本轮产物: aapt2 dump resources 得到的实际表(仅 w 限定符)
# btn: ()40/48/56  (w360dp)44/52/68
# margin_h: ()0  (w600dp)120  (w800dp)220   [w360dp 值=0 与默认同值被去重, 语义等价]
def btn(W):
    return (44, 52, 68) if W >= 360 else (40, 48, 56)

def margin_new(W):
    if W >= 800: return 220, "w800dp"
    if W >= 600: return 120, "w600dp"
    return 0, "default"

# 上一版(第二轮)sw 方案: 边距按设备 smallestWidth(sw) 分档
def margin_old_sw(W, sw):
    if sw >= 800: return 220
    if sw >= 600: return 120
    return 0

PAD = 24  # 外层竖列左右各 24dp
BODY = lambda W: (lambda s,m,p: s*2+m*2+p)(*btn(W))

def row_avail(W, mh):
    return (W - 2*PAD) - 2*mh

def gap(W, mh):
    return (row_avail(W, mh) - BODY(W)) / 4.0

def clip(W, mh):
    d = BODY(W) - row_avail(W, mh)
    return (d / 2.0) if d > 0 else 0.0

out = []
def E(s=""): out.append(s)

E("="*128)
E("QA(Edward) 第三轮独立重算 —— 限宽档 sw -> w 改造")
E("模型: 行可用宽 = (W-48) - 2*margin_h ; 按钮体 = 2s+2m+p ; 4段间距=(行可用宽-按钮体)/4 ; 裁切单侧=(按钮体-行可用宽)/2")
E("="*128)
E()

E("### 表1. 本轮新设计(w 档) —— 请求的场景")
E("-"*128)
E("%-7s %-10s %-9s %-11s %-9s %-9s %-12s %-9s" % ("窗口宽", "margin命中", "margin_h", "行可用宽", "按钮体", "4段间距", "裁切(单侧)", "结论"))
for W in [320, 360, 400, 480, 599, 600, 700, 799, 800, 900, 1200]:
    mh, tag = margin_new(W)
    ra = row_avail(W, mh)
    g = gap(W, mh); c = clip(W, mh)
    E("%-7d %-10s %-9d %-11d %-9d %-9.2f %-12.2f %-9s" % (
        W, tag, mh, ra, BODY(W), g, c, ("放得下" if c == 0 else "裁切")))
E()
E("注: 799dp -> 行可用宽 511, 间距 62.75 (独立复算, 与工程师一致; 派单原值 431/42.75 有误)")
E("    400dp(600dp平板分屏窗口): 新设计 margin=0 -> 行可用宽 352 -> 间距 23 -> 不裁切")
E()

E("### 表2. 与上一版 sw 方案的对比")
E("-"*128)
E("%-7s %-30s %-30s %-12s" % ("窗口宽", "旧sw方案(全屏, 设备sw=W)", "新w方案(全屏, w=W)", "是否变化"))
for W in [320, 360, 480, 599, 600, 700, 799, 800, 900, 1200]:
    mold = margin_old_sw(W, W); mnew, _ = margin_new(W)
    E("%-7d %-30s %-30s %-12s" % (
        W, "margin=%d 间距=%.2f" % (mold, gap(W, mold)),
        "margin=%d 间距=%.2f" % (mnew, gap(W, mnew)),
        ("相同" if mold == mnew else "不同")))
E()
E("关键: 全屏竖屏下 availableWidth == smallestWidth, 故 sw 方案与 w 方案【逐档相同】;")
E("      两者唯一差异出现在多窗口/分屏(sw != 窗口宽) 时 —— 见 表3。")
E()

E("### 表3. 平板分屏场景 (设备 sw=600, 窗口被压缩) —— 本改动真正解决的问题")
E("-"*128)
E("%-9s %-26s %-26s" % ("分屏窗口宽", "旧sw方案(设备sw=600)", "新w方案"))
for W in [400, 480, 500, 550, 599, 600]:
    mold = margin_old_sw(W, 600)          # 设备 sw=600 -> 始终命中 sw600dp 边距
    mnew, tag = margin_new(W)
    E("%-9d %-26s %-26s" % (
        W,
        "margin=%d 行%3d 裁%.0f/侧" % (mold, row_avail(W, mold), clip(W, mold)),
        "margin=%d(%s) 行%3d 裁%.0f/侧" % (mnew, tag, row_avail(W, mnew), clip(W, mnew))))
E()

E("### 不变式严格验证: 「命中 w600dp/w800dp 边距档」=> 「行可用宽 >= 312 > 260」")
E("-"*128)
bad = []
for W in range(0, 3001):
    mh, tag = margin_new(W)
    if tag in ("w600dp", "w800dp"):
        ra = row_avail(W, mh)
        if ra < 260:
            bad.append((W, tag, ra))
E("扫描 W=0..3000: 命中 w600dp/w800dp 但 行可用宽<260 的反例个数 = %d" % len(bad))
E("解析论证: 600<=W<800 -> 行=W-288>=(600-288)=312 ; W>=800 -> 行=W-488>=(800-488)=312. 边界 W=600/W=800 均=312.")
E("          312 > 260(按钮体) => 4段间距 >= (312-260)/4 = 13 > 0 => 恒不裁切。")
E()

E("### 找反例: 当前设计是否存在会裁切的窗口宽度?")
E("-"*128)
clip_W = [W for W in range(0, 1401) if clip(W, margin_new(W)[0]) > 0]
E("裁切窗口宽集合(W, 0..1400) = %s" % (
    ("区间 [%d, %d] (共 %d 个整数 dp)" % (min(clip_W), max(clip_W), len(clip_W))) if clip_W else "空"))
if clip_W:
    thr = max(clip_W) + 1
    E("  裁切临界值 = %d dp  (W <= %d 裁切; W >= %d 恰好放下并向上变宽松; W=%d 间距=0)" % (
        thr, max(clip_W), thr, thr))
    E("  说明: 裁切只发生在 default 档(窗口<360)且 W<280 的极窄窗口; 与 margin 档【无关】(margin 档已证恒不裁)。")
    for W in [220, 240, 260, 279, 280, 300, 320]:
        mh, tag = margin_new(W)
        E("    W=%-4d margin=%d(%s) 行%3d 按钮体%d 裁%.1f/侧" % (W, mh, tag, row_avail(W, mh), BODY(W), clip(W, mh)))
E()

E("### 安全性与 sw 解耦验证")
E("-"*128)
E("手机 sw=360, 桌面/自由窗口拉到宽 800dp:")
mh, tag = margin_new(800)
E("  margin_h=%d(%s) 按钮=%s 行可用宽=%d 间距=%.2f 裁=%.2f -> %s" % (
    mh, tag, btn(800), row_avail(800, mh), gap(800, mh), clip(800, mh),
    "安全" if clip(800, mh) == 0 else "裁切"))
E("结论: 边距/按钮尺寸全部只由 availableWidth(w) 决定, 与 sw 无关 -> 安全性不再依赖 sw。")
E()

txt = "\n".join(out) + "\n"
with io.open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "_qa3_recompute.txt"), "w", encoding="utf-8") as f:
    f.write(txt)
print(txt)
