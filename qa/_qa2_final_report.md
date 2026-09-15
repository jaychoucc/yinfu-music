# QA 独立回归验证报告 — 播放页控制按钮行间距适配（第二轮 / 挑刺视角）

- 仓库：`C:\Users\b5311\WorkBuddy\2026-09-15-10-37-12\yinfu-music`
- 验证者：Edward（QA）·以证伪为目标，全部结论均为独立重算/独立取证
- 产物：`android\app\build\outputs\apk\debug\yinfu-music-1.0.0-20260915.1410.apk`
  - 实测 9,701,956 B，LastWrite 14:11:16（与工程师声明一致）
- 环境：Bash 已损坏，全程 PowerShell；`py` = Python 3.14.5；无设备/模拟器（已声明未验证项见 §7）

---

## 0. 路由判定

**NoOne（全部通过）**。未发现本轮改动的源码 Bug；未发现测试代码 Bug。
附带 3 条**非阻断性**观察/风险（§5），供 Engineer / team-lead 决策，不构成本轮阻塞。

---

## 1. APK 产物核验（不读源码，直接查产物）

证据：`qa\_qa2_apk_verify.txt`、`_qa2_xmltree.txt`、`_qa2_aapt2_resources.txt`

### 1.1 布局产物 `aapt dump xmltree .../res/layout/activity_player.xml`

| 元素 | 产物中的取值 | 判定 |
|---|---|---|
| btn_favorite / btn_download | `@0x7f070324` = `dimen/player_ctrl_btn_small` | 是 dimen 引用 ✓ |
| btn_prev / btn_next | `@0x7f070322` = `dimen/player_ctrl_btn_mid` | 是 dimen 引用 ✓ |
| btn_play | `@0x7f070323` = `dimen/player_ctrl_btn_play` | 是 dimen 引用 ✓ |
| 按钮 width/height | 两侧同一 `@0x7f07xxxx` | 正方形 ✓ |
| 4×Space | `layout_width=(0x5)0x1`(=0dp)、`height=0x1`、`weight=0x3f800000`(=1.0) | 0dp+weight=1 原样保留 ✓ |
| 控制行 `LinearLayout`(line=97) | `gravity=(0x11)0x11`(=center) | ✓ |
| 控制行 | `layout_marginStart=@0x7f070325`、`layout_marginEnd=@0x7f070325`(=row_margin_h) | 存在 ✓ |

> 关键：按钮宽高在**产物**里已是**资源引用**（`@0x7f0703xx`），不是旧的 `44dp/52dp/68dp` 字面量 → 「由字面 dp 改为 @dimen」属实。

### 1.2 资源表 `aapt2 dump resources`（4 个 dimen 的全部 config 解析值）

```
resource 0x7f070322 dimen/player_ctrl_btn_mid
    () 48dp      (w360dp) 52dp      (sw600dp) 52dp
resource 0x7f070323 dimen/player_ctrl_btn_play
    () 56dp      (w360dp) 68dp      (sw600dp) 68dp
resource 0x7f070324 dimen/player_ctrl_btn_small
    () 40dp      (w360dp) 44dp      (sw600dp) 44dp
resource 0x7f070325 dimen/player_ctrl_row_margin_h
    () 0dp       (w360dp) 0dp       (sw600dp) 120dp      (sw800dp) 220dp
```

- 三个按钮 dimen **只有 3 个 config**（default/w360dp/sw600dp），**没有 sw800dp 列**；
- `row_margin_h` 有 **4 个 config**，`sw800dp=220dp` 在表里。
- 这份表是我独立 dump 的产物，与工程师描述的现象一致。

---

## 2. 「≥800dp 设备实际解析值」独立结论（本轮最值得挑刺点）

### 结论
- `player_ctrl_btn_small` → **44dp**（不是 40dp）
- `player_ctrl_row_margin_h` → **220dp**（不是 120dp）
- 即：**≥800dp 设备尺寸正确、限宽正确**。工程师的去重解释**成立**。

### 独立推理链（不采信"合理"，给出机制）
1. **匹配规则（AOSP `ResTable_config::isBetterThan`）**：比较按限定符优先级逐项进行，`smallestScreenWidthDp`(sw) **先于** `screenWidthDp`(w) 比较；且在该项上「设置了 sw 的 config」优于「未设置 sw 的 config」，同项内取值大者更优先。
   → 故优先级：`sw800dp > sw600dp > w360dp > default`。
2. **按钮**：sw800dp 条目因与 sw600dp **同值(44/52/68)**被 aapt2 默认去重删除 → 候选集只剩 `{default, w360dp, sw600dp}`；最小宽度 800dp 的设备满足 sw600dp(600≤800)，且 sw600dp 在候选集中排名最高 → **命中 sw600dp = 44/52/68**。值正确。
   - 为什么去重的方向是删 sw800dp 而非 w360dp/sw600dp？因为「任何满足 sw≥800 的设备必然也满足 sw≥600」，两者同值 → sw800dp 条目对全部设备的结果**无影响**，可安全删除；而删 w360dp 会让「w≥360 且 sw<600」的手机失真，删 sw600dp 会让「窄多窗平板(w<360,sw≥600)」退化，故保留。
3. **限宽**：`row_margin_h` 的 sw800dp=220dp 与其它档**不同值**，无法去重 → 条目保留；sw800dp 在优先级上高于 sw600dp → **命中 sw800dp = 220dp**。
4. 客观佐证：产物表里 `dimen/player_ctrl_row_margin_h` 明确含 `(sw800dp) 220.000000dp`（§1.2）。

**判定：工程师断言正确，未发现该去重导致 ≥800dp 拿到错误尺寸或错误限宽。**

---

## 3. 独立重算 11 档屏宽（证据：`qa\_qa2_spacing_table.txt`）

模型：`可用宽=W−48`（外层竖列左右各 24dp padding）；`行可用宽=可用宽−2×margin_h`；`每段间距=(行可用宽−按钮体)/4`；按钮体=`2×small+2×mid+play`；`间距<0` 即裁切，单侧被裁`=(按钮体−行可用宽)/2`。
命中规则同 §2。**注意 800dp 处按钮与 margin 命中的 config 不同**（按钮 sw600dp、margin sw800dp）。

| 屏宽 | 按钮命中档 | margin命中档 | (s/m/p) | 按钮体 | 可用宽 | 行可用宽 | 新间距 | 新单侧裁 | 旧间距(44/52/68) | 旧单侧裁 |
|---|---|---|---|---|---|---|---|---|---|---|
| 240 | default | default | 40/48/56 | 232 | 192 | 192 | **−10 裁切** | 20 | −17 裁切 | 34 |
| 280 | default | default | 40/48/56 | 232 | 232 | 232 | 0（恰好） | 0 | −7 裁切 | 14 |
| 300 | default | default | 40/48/56 | 232 | 252 | 252 | 5 | 0 | −2 裁切 | 4 |
| 320 | default | default | 40/48/56 | 232 | 272 | 272 | **10** | 0 | 3 | 0 |
| 360 | w360dp | w360dp | 44/52/68 | 260 | 312 | 312 | 13 | 0 | 13 | 0 |
| 384 | w360dp | w360dp | 44/52/68 | 260 | 336 | 336 | 19 | 0 | 19 | 0 |
| 393 | w360dp | w360dp | 44/52/68 | 260 | 345 | 345 | 21.25 | 0 | 21.25 | 0 |
| 411 | w360dp | w360dp | 44/52/68 | 260 | 363 | 363 | 25.75 | 0 | 25.75 | 0 |
| 480 | w360dp | w360dp | 44/52/68 | 260 | 432 | 432 | 43 | 0 | 43 | 0 |
| 600 | **sw600dp** | **sw600dp** | 44/52/68 | 260 | 552 | 312 | **13** | 0 | 73 | 0 |
| 800 | **sw600dp**(按钮) | **sw800dp**(margin) | 44/52/68 | 260 | 752 | 312 | **13** | 0 | 123 | 0 |

### 命中档位独立复核（易错点）
- **480dp**：`w360dp` 命中、`sw600dp` 不命中（sw<600）→ w360dp。✓
- **600dp**：`w360dp` 与 `sw600dp` 同时命中 → **sw600dp 胜**（sw 优先级高于 w）。✓
- **800dp**：`w360dp`/`sw600dp`/`sw800dp` 同时命中 → **margin 用 sw800dp**；**按钮因 sw800dp 条目被去重，退而命中 sw600dp**（同值 44/52/68）。✓

### 与上一版对比（量化改善）
- 320dp：3 → **10**（+7，挤的问题缓解）
- 280dp：裁 14/侧 → **0（恰好放下）**
- 300dp：裁 4/侧 → **间距 5（放下）**
- 240dp：裁 34/侧 → **裁 20/侧**（改善，但**仍未消除**）
- 360–480dp：与上一版**逐位相同**（未过调）
- 600dp：73 → **13**（按钮组不再被拉散）
- 800dp：123 → **13**
- 设计意图：sw600/sw800 的 margin(120/220) 使「行可用宽=312dp=(360−48)」，与 360dp 手机等价 → 宽屏按钮组宽度被**封顶**。

**交叉验证**：旧基线按「固定 44/52/68 + 无 margin」复算得 320→3、600→73、800→123，与任务书给的旧值**逐位吻合**，反证模型与基线正确。

---

## 4. 限定符优先级结论独立复核

- 工程师结论：限宽必须写 `values-sw800dp`，写 `values-w800dp` 会被 `values-sw600dp` 压掉。
- **独立判定：正确。**
- 若真写 `values-w800dp`：`row_margin_h` 表将变为 `()0 / (w360dp)0 / (sw600dp)120 / (w800dp)220`。在 sw≥600 且 w≥800 的设备上，`sw600dp`（smallestWidth 限定符，优先级 4）**永远压过** `w800dp`（availableWidth 限定符，优先级 5）→ 解析为 **120dp**，220dp **永不生效**（死值）。故用 `sw800dp` 是**必要且正确**的规避。
- 客观佐证：产物 `aapt2 dump resources` 的 config 列表里两类限定符并存（`(w360dp)` 与 `(sw600dp)/(sw800dp)`），可与 AOSP 优先级表比对。
- **偏差提示**：优先级结论本身无偏差，但要注意它只在「设备同时满足二者」时成立；本仓库 800dp 场景二者都满足，故结论成立。

---

## 5. 独立复核「只改了预期文件」+ 负向排查

### 5.1 改动范围（mtime 为主判据；证据 `_qa2_git.txt`、`_qa2_mtime_src_main.txt`）
本轮时间窗(约 14:05–14:15)内，`src\main` 下**仅有 5 个文件**被改：
```
14:09:51  activity_player.xml
14:09:30  values\dimens.xml
14:09:30  values-w360dp\dimens.xml
14:09:30  values-sw600dp\dimens.xml
14:09:30  values-sw800dp\dimens.xml
```
- **无任何 `.kt` 落入窗口**；`PlayerActivity.kt` = 11:33:12，`SearchFragment.kt` = 13:17:16，均**早于**本轮 → 本轮未动 Kotlin。✓
- `git status`：`activity_player.xml` 为 ` M`，4 个 dimens 文件 + 3 个 values-* 目录为 `??`（新增）。仓库另有**大量历史未提交**的歌单功能改动（PlayerRepository.kt / *Fragment.kt / PlayerActivity.kt / MineFragment.kt / fragment_mine.xml / qa/*.py 等）——**这些不在本轮窗口，判为本轮之外的遗留改动**。
- `git diff activity_player.xml`（对 HEAD）**混合了两轮**：HEAD 里按钮是固定 `44/52/68` 且按钮自带 `marginStart/End=24dp`、**无 Space**；工作区已换成 4×Space + dimen 引用 + 行 margin。即「防裁切重构（Space）」是**上一轮**未提交改动，本轮在此之上再改。→ **不能仅凭 git diff 划分轮次，mtime 才是判据**（已按要求区分）。

### 5.2 结构完整性（源码侧，证据 `_qa2_structure.txt`）
- XML well-formed ✓；id 计数 13、去重 13、与期望 13 集合**差集为空、无重复** ✓
- 5 按钮顺序 `favorite→prev→play→next→download` ✓；5 按钮均 width==height 且引用同一 dimen ✓
- 控制行 `gravity=center`、`marginStart/End=@dimen/...` ✓；4×Space 全为 `0dp/0dp/weight=1` ✓

### 5.3 配置错配负向排查
- **当前无混搭错配**：4 个 `values-*` 文件**都定义了全部 4 个 dimen**，且资源匹配基于「同一设备配置」逐资源解析 → 4 个 dimen 总是从同一「最优档」取（唯一例外是 800dp：按钮取 sw600dp、margin 取 sw800dp，但两者都是平板值，语义一致，非错配）。
- **容器包含关系保证不会出现「按钮回默认档但 margin 走平板档」**：margin 若命中 sw600dp，说明 sw≥600，则按钮必命中 sw600dp（≥360）；反之按钮回 default 说明 sw<360、w<360，margin 也必回 default。故不会「限宽生效而按钮没恢复原尺寸」。
- **未来风险（新增 `values-*` 只覆盖部分 dimen 会出现混搭）**：例如新增 `values-sw900dp` 只写 `player_ctrl_row_margin_h` 而不写按钮 —— sw900 设备按钮会退到 sw600dp(44/52/68)、margin 取 900 档新值。这是合法但需**有意为之**的混搭；若无意图，会出现「按钮尺寸与限宽来自不同档」的隐性错配。建议任何新档**成套写全 4 个 dimen**。

---

## 6. QA 套件复跑（CWD=`C:\`，证明与 CWD 无关；证据 `_qa2_suite_*.txt`）

| 套件 | 期望 | 实测 | 判定 |
|---|---|---|---|
| `py qa\local_playlist_check.py` | 90 PASS / 0 FAIL / 0 SKIP | **总计: 90 PASS / 0 FAIL / 0 SKIP(UNVERIFIED)，✓ 全部通过** | ✓ |
| `py qa\scan_nested_comments.py` | CLEAN / 108 .kt | **scanned .kt files: 108；problem files: 0/108；RESULT: CLEAN** | ✓ |
| `py qa\preview_guard.py` | 44 PASS / 0 FAIL | **总计: 44 PASS / 0 FAIL，✓ 全部通过**（第 2 节真连网 haitangw 实测通过：size=0.46MB、implied_bps=14308） | ✓ |

> 未为「绿灯」改动任何源码或弱化断言；preview_guard 第 2 节为真连网，本次结果为当次快照。

---

## 7. 顺带核实：`android\build.bat` 的 `pause`

- **属实**：文件（mtime 11:01:16，**非本轮**改动）第 21 行 error 分支含 `pause`，第 31 行（成功路径末尾）亦含 `pause`。
- **对非交互自动化的风险**：`pause` 阻塞等待按键 → 在 CI/无人值守环境中进程会**挂起直到超时/被强杀**；尤其第 31 行在**成功路径**也会挂起，意味着「构建成功却永不返回」。建议非交互调用改用 `run_gradle.bat`（qa\ 下已有）或设 `set CI=true` 并去除 `pause`。（仅核实，未改动。）

---

## 8. 未验证项（如实声明）

本机**无设备/模拟器**，以下为**推断**（已给出依据，但未在真机实证）：
1. **真实渲染**：11 档间距/裁切均为**按尺计算**，未在真机观测像素。
2. **触摸热区**：按钮视觉尺寸变化对可点区域/相邻误触的影响未实测。
3. **`match_parent + marginStart/End` 在垂直 LinearLayout + `gravity=center_horizontal` 下「收缩而非裁切」**：依据 LinearLayout 源码（measureChildWithMargins 计入 left/right margin，match_parent 子宽=父内容宽−margin）推断为**收缩**；未真机验证。
4. **裁切可视化**：理论单侧被裁=deficit/2（受 row 的 clipChildren 裁剪），240dp 场景未真机确认。

---

## 9. 非阻断性观察（供 Engineer / lead 决策，非本轮 Bug）

1. **240dp 仍裁切**：新默认档(40/48/56，体宽 232)在 240dp（可用 192）单侧仍被裁 20dp。相对旧版(34dp)已改善，但**未消除**；物理上 48(padding)+232 > 240，仅靠尺寸无法在 240dp 放下。若需覆盖 240dp 需再评估。
2. **480–599dp 存在「上限空洞/跳变」**：`sw600dp` 及以上才封顶；而 `481–599dp`（大屏手机/折叠展开）仍走 w360dp，间距随屏宽线性增大到 **599dp≈72.75dp**，然后在 **600dp 骤降为 13dp**（≈60dp 跳变）。该区间与上一版**行为一致（非回归）**，但「宽屏上限」在该区间实际上未生效。若目标含 480–599dp，可考虑再补一档。
3. **sw800dp 去重带来的维护陷阱**：若将来把 `values-sw800dp` 的按钮 dimen 改成与 **default 同值**(如 40/48/56)，aapt2 会因与「更弱档」同值而**再次去重**，导致 sw≥800 设备反而命中 sw600dp(44/52/68) → 修改**静默不生效**。建议：sw800dp 的按钮值需与 default 明确不同，或保留注释警示。

---

## 10. 证据文件清单（均在 `qa\`，未覆盖上一轮 `_qa_*`）

| 文件 | 内容 |
|---|---|
| `_qa2_apk_verify.txt` | APK 产物集中证据（资源表 + xmltree 关键行） |
| `_qa2_aapt2_resources.txt` | `aapt2 dump resources` 全量输出（1.6MB） |
| `_qa2_xmltree.txt` | `aapt dump xmltree` 全量输出 |
| `_qa2_apk_info.txt` | APK 大小/时间/build-tools |
| `_qa2_spacing_table.txt` | 11 档屏宽独立重算表（§3） |
| `_qa2_structure.txt` | 结构完整性复核（§5.2） |
| `_qa2_mtime_src_main.txt` | `src/main` 全量 mtime |
| `_qa2_git.txt` | git status/diff/log + 时间窗文件扫描 |
| `_qa2_diff_player.txt` | activity_player.xml 对 HEAD 的 diff + build.bat mtime |
| `_qa2_suite_local.txt` / `_qa2_suite_scan.txt` / `_qa2_suite_preview.txt` | 三套件原始输出 |
| `_qa2_recompute.py` / `_qa2_structure.py` / `_qa2_extract.py` | 复核脚本 |
