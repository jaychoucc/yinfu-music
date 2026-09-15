# QA 独立回归验证报告（第三轮 / 挑刺视角）— 限宽档 sw → w 改造

- 仓库：`C:\Users\b5311\WorkBuddy\2026-09-15-10-37-12\yinfu-music`
- 验证者：Edward（QA）·独立重算/独立取证，不采信任何一方结论
- 产物：`android\app\build\outputs\apk\debug\yinfu-music-1.0.0-20260915.1422.apk`
  - 实测 **9,705,232 B**、LastWrite **14:22:23**（与工程师声明一致；较上一轮 9,701,956 略增）
- 环境：Bash 已坏，全程 PowerShell；`py`=Python 3.14.5；无设备/模拟器

---

## 0. 路由判定

**NoOne（全部通过）**。未发现源码 Bug、未发现测试代码 Bug。附 3 条非阻断观察（§6）。

---

## 1. 产物级 config 列表核验（证据 `_qa3_apk_verify.txt` §A）

`aapt2 dump resources <新APK>` 实测：

```
resource dimen/player_ctrl_btn_mid      () 48 | (w360dp) 52
resource dimen/player_ctrl_btn_play     () 56 | (w360dp) 68
resource dimen/player_ctrl_btn_small    () 40 | (w360dp) 44
resource dimen/player_ctrl_row_margin_h ()  0 | (w600dp) 120 | (w800dp) 220
```

- ✅ **无任何 sw600dp / sw800dp 列残留**（全表 22 处 `sw…dp` 全部来自 Material/AndroidX 库资源：`design_snackbar`、`mtrl_layout_snackbar`、`Widget.Design.TabLayout` 等，**无一属于 `player_ctrl_*` 或本 app 资源**；`sw800dp` 全表 0 处）。
- ✅ 权威合并产物 `packaged_res/debug`（真正进 APK 的 res）确认 app 只有 4 档：`values`(默认全 4 dimen) / `values-w360dp-v13`(全 4) / `values-w600dp-v13`(**仅 margin_h=120**) / `values-w800dp-v13`(**仅 margin_h=220**)。

### 「margin_h 的 `(w360dp) 0dp` 列缺失」独立判断 —— 工程师解释**成立**
- 机制：`values-w360dp\dimens.xml` **确实**定义了 `player_ctrl_row_margin_h=0dp`（读源文件 + `packaged_res/values-w360dp-v13` 均为 0dp），与默认档 0dp **同值**；aapt2 默认的**资源去重**会删除「取值与某个更弱档完全相同、且该更弱档在被删档命中时必然也命中」的条目。此处 `default` 恒命中且同值 0dp ⇒ w360dp 的该条目被删。
- **是否影响解析：否。** 论证「窗口宽 500dp 时 margin_h = ?」：候选 = `default(0)`、`w600dp(需≥600，不含)`、`w800dp(不含)` → 命中 default → **0dp**。即便 w360dp 列存在，500≥360 会命中它 → 也是 **0dp**。两种情形结果相同 ⇒ 无副作用。
- **与上一轮 sw800dp 去重是否同类**：**是同一类机制**——都是「某 config 条目取值等同于一个『更弱且必然匹配』的 config ⇒ 可安全删除」。两轮都**不改变任何设备的解析结果**（去重只删「结果无差异」的条目）。差别仅在删除对象（上轮删 sw800dp 的按钮 44/52/68；本轮删 w360dp 的 margin 0），安全性性质一致。

---

## 2. 独立复算（证据 `_qa3_recompute.txt`）

模型：`行可用宽=(W−48)−2×margin_h`；`按钮体=2s+2m+p`；`4段间距=(行可用宽−按钮体)/4`；`单侧裁切=(按钮体−行可用宽)/2`。

### 表1 本轮新设计（w 档）
| 窗口宽 | margin命中 | margin_h | 行可用宽 | 按钮体 | 4段间距 | 裁切 |
|---|---|---|---|---|---|---|
| 320 | default | 0 | 272 | 232 | 10.00 | 0 |
| 360 | default | 0 | 312 | 260 | 13.00 | 0 |
| **400** | default | 0 | **352** | 260 | **23.00** | **0** |
| 480 | default | 0 | 432 | 260 | 43.00 | 0 |
| 599 | default | 0 | 551 | 260 | 72.75 | 0 |
| 600 | w600dp | 120 | 312 | 260 | 13.00 | 0 |
| 700 | w600dp | 120 | 412 | 260 | 38.00 | 0 |
| **799** | w600dp | 120 | **511** | 260 | **62.75** | 0 |
| 800 | w800dp | 220 | 312 | 260 | 13.00 | 0 |
| 900 | w800dp | 220 | 412 | 260 | 38.00 | 0 |
| 1200 | w800dp | 220 | 712 | 260 | 113.00 | 0 |

> **799dp 独立复算 = 行可用宽 511 / 间距 62.75**。派单原值「431 / 42.75」有误（若 margin 误用 160 或算错减数可得 431）；**工程师的 511/62.75 正确**。我按 799−48=751，751−2×120=511，(511−260)/4=62.75 得到同值。

### 表2 与上一版 sw 方案对比（全屏）
**全屏竖屏下 availableWidth == smallestWidth ⇒ sw 方案与 w 方案逐档完全相同**（320/360/480/599/600/700/799/800/900/1200 全部一致）。即本轮改造在**全屏场景零行为变化**。

### 表3 平板分屏场景（设备 sw=600，窗口被压缩）— 本改动真正解决的问题
| 分屏窗口宽 | 旧 sw 方案(设备sw=600) | 新 w 方案 |
|---|---|---|
| **400** | margin=120 行112 **裁 74/侧** | margin=0(default) 行352 **裁 0/侧** |
| 480 | margin=120 行192 裁 34/侧 | margin=0 行432 裁 0/侧 |
| 500 | margin=120 行212 裁 24/侧 | margin=0 行452 裁 0/侧 |
| 550 | margin=120 行262 裁 0/侧 | margin=0 行502 裁 0/侧 |
| 600 | margin=120 行312 裁 0/侧 | margin=120(w600dp) 行312 裁 0/侧 |

> **「分屏 400dp 窗口：74dp/侧 → 0」**：独立复算确认。

---

## 3. 不变式严格论证 + 找反例（本轮核心）

### 3.1 不变式：「命中 w600dp/w800dp 边距档」⟹「行可用宽 ≥ 312 > 260」
- 若 `600 ≤ W < 800`：margin=120 → 行宽 `W−48−240 = W−288 ≥ 600−288 = 312`。
- 若 `W ≥ 800`：margin=220 → 行宽 `W−48−440 = W−488 ≥ 800−488 = 312`。
- 边界 `W=600`、`W=800` 均恰好 = 312。`312 > 260` ⇒ 4 段间距 `≥ (312−260)/4 = 13 > 0` ⇒ **恒不裁切**。
- **数值扫描** W=0..3000：命中边距档却 `行可用宽<260` 的反例 **0 个**。∎

### 3.2 找反例：是否存在会裁切的窗口宽度？
- **存在，但仅在 `W ≤ 279dp`**（属 default 档，窗口<360）：临界值 **W=280dp**（W≤279 裁切；W=280 恰好放下，间距 0；W>280 放下）。
  - 例：W=220 裁 30/侧；W=240 裁 20/侧；W=260 裁 10/侧；W=279 裁 0.5/侧。
- **该裁切与 margin 档无关**（margin 档已严格证明恒不裁切）；它是「按钮体 232 + 外 padding 48 = 280 > W」的物理下限，**本轮改动既未引入也未加重**（与第一/二轮一致）。
- **可达性**：`W ≤ 279` 是否能在真机出现，取决于平台允许的最小窗口宽（自由窗口/极窄分屏）。本仓库 manifest **未声明** `minWidth`/`minSdk` 约束窗口下限，故理论上自由窗口可触及；**但无真机验证，标为推断**。
- **结论**：就本轮要解决的「平板分屏裁切」而言——**未找到反例，且已论证 margin 档下不存在**。全局唯一裁切区（W≤279）与本改动无关。

### 3.3 安全性是否已完全脱离 sw？
- **是。** 边距命中（`w600dp`/`w800dp`）与按钮尺寸（`w360dp`）全部只由 **availableWidth(w)** 决定，与设备 `sw` 无关。
- 验证场景：**手机 sw=360，桌面/自由窗口拉宽到 800dp** → 命中 `w800dp` margin=220、按钮 `w360dp`=(44/52/68) → 行可用宽 312、间距 13、**裁 0** → 安全。
- 反向场景：**平板 sw=600 分屏到 400dp** → margin 回 default 0 → 行 352 → 间距 23 → 安全（§2 表3）。
- 两者均由 `w` 单一维度决定 ⇒ **不再依赖 sw**。

---

## 4. 改动范围核实（证据 `_qa3_scope.txt`、`_qa3_apk_info.txt`、`_qa3_merged_dirs.txt`）

- res 顶层目录 = `values / values-w360dp / values-w600dp / values-w800dp`（**无 sw600dp、sw800dp**）。
- **`values-sw600dp`、`values-sw800dp` 目录本体已删除**：`Test-Path` 均为 `False`（不是留空目录）。
- 本轮时间窗(14:15–14:30)内 `src\main` **仅 2 个文件**：`values-w600dp\dimens.xml`(14:21:45)、`values-w800dp\dimens.xml`(14:21:53)。
- `activity_player.xml`(14:09:51)、`values\dimens.xml`(14:09:30)、`values-w360dp\dimens.xml`(14:09:30) **mtime 仍是上一轮**，本轮未写 ✅。
- **`.kt` 无改动**：java 树内最新 mtime = `SearchFragment.kt` 13:17:16（早于本轮），`PlayerActivity.kt` 11:33:12。
- **源码字符串残留**：`android\` 源码树（排除 build）内 `sw600dp|sw800dp` **0 处**。仅 `qa\` 下我前两轮历史报告含该字符串（属正常，非源码）。
- 两个新文件均**只定义 `player_ctrl_row_margin_h`**（读文件 + `packaged_res` 双证），与设计一致。

---

## 5. 结构完整性（源码侧 `_qa3_structure.txt` + 产物侧 `_qa3_xmltree.txt`）
- XML well-formed；13 id 差集为空、无重复；按钮顺序 fav→prev→play→next→dl；5 按钮均 width==height 且同一 dimen 引用。
- 控制行 `gravity=center`(0x11)、`marginStart=marginEnd=@0x8f...`(=row_margin_h)；4×Space 全 `0dp/0dp/weight=1.0`。
- 产物 `xmltree` 与源码一致（按钮 `@0x7f07032x`、行 margin `@0x7f070325`）。

---

## 6. 套件复跑（CWD=`C:\`；证据 `_qa3_suite_*.txt`）
| 套件 | 期望 | 实测 | 判定 |
|---|---|---|---|
| `py qa\local_playlist_check.py` | 90 PASS / 0 FAIL / 0 SKIP | **90 PASS / 0 FAIL / 0 SKIP**，✓ 全部通过 | ✓ |
| `py qa\scan_nested_comments.py` | CLEAN / 108 | **scanned 108，problem 0/108，RESULT: CLEAN** | ✓ |
| `py qa\preview_guard.py` | 44 PASS / 0 FAIL | **44 PASS / 0 FAIL**，✓ 全部通过 | ✓ |

未为绿灯改动任何源码或弱化断言。preview_guard 第 2 节为真连网，结果为当次快照。

---

## 7. 非阻断观察（供决策，非本轮 Bug）
1. **W ≤ 279dp 仍裁切**：如前所述属物理下限、非本改动引入；若产品确需覆盖极窄自由窗口，可再引入更小按钮档（本轮范围外）。
2. **`w600dp→w800dp` 边界处间距跳变**：799dp 间距 62.75 → 800dp 间距 13（≈50dp 跳变）。这是「封顶边距在 800dp 生效」的必然副作用，且与上一版 sw 方案在该点行为**完全相同**（非回归）；仅提示视觉上宽度跨过 800dp 时按钮组会突然收紧。
3. **去重维护陷阱（同类风险延续）**：若将来把 `values-w600dp`/`values-w800dp` 的 margin 值改成与默认（0dp）**同值**，会被再次去重删除、导致该档**静默失效**。建议该两档 margin 值保持与默认不同并加注释（现状 120/220 已满足）。

---

## 8. 未验证项（如实声明）
无设备/模拟器：
1. **真实渲染/像素**：所有间距与裁切为**按尺计算**，未真机测量。
2. **触摸热区/误触**：未实测。
3. **`w`（availableWidth）在多窗口下确实随窗口宽变化**：依据 `availableWidth` 语义与 `wNNNdp` 限定符定义推断，**非真机实测**。
4. **`match_parent + marginStart/End` 在垂直 LinearLayout + `gravity=center_horizontal` 下收缩而非裁切**：依 `LinearLayout.measureChildWithMargins`（计入左右 margin，match_parent 子宽=父内容宽−margin）推断为**收缩**，未真机验证。
5. **W≤279dp 可达性**：取决于平台最小窗口宽，未真机验证。

---

## 9. 证据文件（`qa\`，均 `_qa3_*`，未覆盖前两轮）
`_qa3_final_report.md`、`_qa3_apk_verify.txt`、`_qa3_aapt2_resources.txt`、`_qa3_xmltree.txt`、`_qa3_apk_info.txt`、`_qa3_scope.txt`、`_qa3_merged_dirs.txt`、`_qa3_merge_detail.txt`、`_qa3_recompute.txt`、`_qa3_structure.txt`、`_qa3_suite_local.txt`、`_qa3_suite_scan.txt`、`_qa3_suite_preview.txt`、脚本 `_qa3_recompute.py`/`_qa3_extract.py`。
