# QA5 独立回归报告 — 歌单封面图标（S3「我的歌单」+ S5「加入歌单」选择器）

- 验证人：QA 工程师 严过关（Yan）
- 仓库根：`C:\Users\b5311\WorkBuddy\2026-09-15-10-37-12\yinfu-music`
- 被验产物：`android\app\build\outputs\apk\debug\yinfu-music-1.0.0-20260915.1750.apk`（9 708 711 byte，`versionName=1.0.0-20260915.1750`，`aapt dump badging` 实测）
- 取证方式：**离线产物级取证**（无 JDK/模拟器/真机）——源码静态断言 + Python 逻辑仿真 + APK 内 `aapt/aapt2/dexdump` 反汇编
- 环境：Bash 不可用，全程 PowerShell；stdout 常不回显 → 一律重定向到 `qa\_qa5_*.txt` 再 Read

---

## 0. 结论 TL;DR

| 项 | 结论 |
|---|---|
| **路由判定** | **NoOne**（全部可验证契约通过；未发现源码 Bug） |
| 套件最终计数 | **114 PASS / 0 FAIL / 1 SKIP(UNVERIFIED)** — **0 FAIL ✅**（基线 92/0/0，本轮 +22 项） |
| 6 个文件是否恰好 | **是**（按 mtime 本轮窗口 = 恰好这 6 个；但**无法用 git 证明**，见 §3.1） |
| B.1/B.2 blob hash | **byte 级相同 ✅**（两组 hash 完全一致） |
| C-N7 `inflateInput` 返回类型 | **`Lkotlin/Pair;` ✅**（崩溃修复仍在，来自 APK `classes4.dex`） |
| 对抗性验证 · RecyclerView 复用竞态 | **可达（reachable）**，属 **P2 局部视觉缺陷**，**非本轮引入**（`MiniImageLoader.load` 全仓既有特性），**无 P0** |
| 遗留 P0（崩溃/数据损坏/功能不可用） | **无** |
| **IS_PASS** | **YES** |

> 唯一未验证项：`PlaylistStore.kt` 的「相对 HEAD 纯新增」——该文件根本不在 `HEAD`（整份功能为**未提交工作区**），无基线可逐行 diff；已按规程显式标 **SKIP/UNVERIFIED**，不静默判 PASS。

---

## 1. 环境与基线

- HEAD：`27fd49334d355d954f9907c8fe7c71211d66c114`（`fix: 试听守护升级+搜索卡顿崩溃修复…`）
- 改前套件基线：`92 PASS / 0 FAIL / 0 SKIP`（复现一致）
- 工具：`build-tools\android-sdk\build-tools\34.0.0\{aapt,aapt2,dexdump}.exe`；`python 3.13.12`

---

## 2. 逐契约 PASS/FAIL 表

### C-N1 · 封面口径语义（最高优先级）

| 子项 | 结论 | 证据 |
|---|---|---|
| 源码严格两级回退 | **PASS** | `coverUrlOf` 体：`getOrNull(0)?.coverUrl?.takeIf{isNotBlank} ?: getOrNull(1)?.coverUrl?.takeIf{isNotBlank}`，恰 2 处 `takeIf`/`isNotBlank()`，复用 `songsOf(playlistId)`，无 `getOrNull(2)`、无遍历 |
| 真值表 7 行 | **PASS** | `qa\_qa5_cover_sim.txt`：7/7 命中 |
| 可证伪性 | **PASS** | 坏实现 A（扫全部取首个非空）、坏实现 B（二级漏 takeIf）**均被检出 FAIL** |
| APK 字节码佐证 | **PASS** | `classes8.dex` 中 `coverUrlOf` 只有 `$i$a$-takeIf-PlaylistStore$coverUrlOf$1` 与 `$2` 两个 lambda（**恰两级**，无第三级） |

真值表原文（`qa\_qa5_cover_sim.txt`）：

```
[PASS] 空歌单 — 期望=None 实际=None
[PASS] [有封面] — 期望='C1' 实际='C1'
[PASS] [无封面]（仅一首） — 期望=None 实际=None
[PASS] [无封面, 有封面] — 期望='C2' 实际='C2'
[PASS] [有封面A, 有封面B] → addedAt 最大者(B) — 期望='B' 实际='B'   ← 证按 addedAt 非插入序
[PASS] [无封面, 无封面, 有封面] → None — 期望=None 实际=None          ← 关键反例：不越过第 2 首
[PASS] [无封面, 有封面, 有封面] → 第 1 首(B1) — 期望='B1' 实际='B1'
=== 可证伪性 ===
[PASS(已证伪)] 坏实现A-扫全部取第一个非空 — 失配行=['[无封面, 无封面, 有封面] → None']
[PASS(已证伪)] 坏实现B-第二级漏 takeIf — 失配行=['[无封面, 无封面, 有封面] → None']
```

### C-N2 · `PlaylistStore.kt` 只增不改

| 子项 | 结论 | 证据 |
|---|---|---|
| 复用 `songsOf(` 而非自写排序 | **PASS** | 方法体 `val songs = songsOf(playlistId)` |
| 源码不存在 `getOrNull(2)` | **PASS** | 全文件 grep 无命中 |
| 方法层「只增」 | **PASS** | 既有 14 个方法签名齐全（createPlaylist/renamePlaylist/deletePlaylist/toggleIn/setMembership/addToPlaylist/removeFromPlaylist/updatePlayUrl/songsOf/playlistIdsContaining/containsIn/songCount/isNameTaken）+ 新增 `coverUrlOf` |
| APK 内既有方法未被删 | **PASS** | `classes8.dex` 反汇编出 `PlaylistStore` 全部既有方法 + `coverUrlOf`（`qa\_qa5_store_methods.txt`，共 41 项） |
| 「相对 HEAD 无 `-` 删除行」 | **SKIP/UNVERIFIED** | 文件不在 HEAD（未跟踪新增），`git show HEAD:<path>` → `path exists on disk, but not in 'HEAD'`，无基线可 diff |

> ⚠️ 任务原文要求「用 `git diff` 证明纯新增」——因该文件**未跟踪**，`git diff`/`git diff HEAD` 对其恒为空，该断言**在技术上不可执行**，故降级为 UNVERIFIED，并用 C.13a/b/c + APK 字节码作替代佐证。

### C-N3 · `MyPlaylistAdapter` 契约

| 子项 | 结论 | 证据 |
|---|---|---|
| import `android.widget.ImageView` + `util.MiniImageLoader` | **PASS** | 两行 import 均在 |
| 构造签名 `loader` 在前、`coverOf` 末尾默认 `null` | **PASS** | `class MyPlaylistAdapter(private val loader: MiniImageLoader, … private val coverOf: ((LocalPlaylist) -> String?)? = null)` |
| VH 内 `findViewById(R.id.cover)` | **PASS** | 存在 |
| bind 顺序：先清后判空加载 | **PASS** | `setImageDrawable(null)`@117 **早于** `loader.load(`@229，且被 `if (!coverUrl.isNullOrBlank())` 包裹 |
| name/count/按钮可见性/三回调未改 | **PASS** | 逻辑均在（presence 断言；文件未跟踪，无 HEAD 可比） |
| APK 字节码佐证 | **PASS** | `classes6.dex` 含 `MyPlaylistAdapter`，构造字段 `coverOf Lkotlin/jvm/functions/Function1;` |

### C-N4 · `PlaylistPickAdapter` 契约

| 子项 | 结论 | 证据 |
|---|---|---|
| import 齐全 | **PASS** | 同 C-N3 |
| `coverOf` 末尾默认 `null` | **PASS** | 构造签名匹配 |
| VH `R.id.cover` | **PASS** | 存在 |
| bind 三段式（先清后加载+判空） | **PASS** | `setImageDrawable(null)`@77 < `loader.load(`@189，guard 命中 |
| **勾选态四步一字未动** | **PASS** | 索引序 `null@243 → isChecked@294 → listener@347 → 整行点击@496`，严格递增 |
| APK 字节码佐证 | **PASS** | `classes6.dex` 含 `PlaylistPickAdapter` + `coverOf Lkotlin/jvm/functions/Function1;` |

### C-N5 · `item_pick_playlist.xml` 布局

| 子项 | 结论 | 证据 |
|---|---|---|
| 子 View 顺序 `check → cover → name` | **PASS** | 源码 + **打包后** `aapt2 dump xmltree` 一致（CheckBox → ImageView → TextView） |
| cover 36dp×36dp / centerCrop / `bg_gradient_accent` | **PASS** | 源码 + 打包后 `layout_width=36dp,layout_height=36dp,scaleType=6(=centerCrop),background=@0x7f08007a(=drawable/bg_gradient_accent)` |
| name `layout_marginStart="8dp"` | **PASS** | 源码 + 打包后 `layout_marginStart=8dp` |
| APK 内 `id/cover` 资源存在 | **PASS** | `aapt2 dump resources` → `resource 0x7f0a00a6 id/cover` |

打包后结构（`qa\_qa5_xmltree_pick2.txt`，hex id 已反解）：

```
E: LinearLayout (line=9)
  E: CheckBox   id=@0x7f0a008f (id/check)
  E: ImageView  id=@0x7f0a00a6 (id/cover)  bg=@0x7f08007a (bg_gradient_accent)  36dp×36dp  scaleType=6
  E: TextView   id=@0x7f0a019d (id/name)   layout_marginStart=8dp
```

### C-N6 · 两个调用点接线且不多不少

| 子项 | 结论 | 证据 |
|---|---|---|
| MyPlaylistsActivity 恰 1 次构造 + loader + coverOf | **PASS** | `ctor=1`，`loader = MiniImageLoader(this)`，`coverOf = { p -> store.coverUrlOf(p.id) }` |
| AddToPlaylistSheet 恰 1 次构造 + loader + coverOf | **PASS** | `ctor=1`，同形 |
| `coverUrlOf` 恰 2 调用 + 1 定义 | **PASS** | 全仓 `.coverUrlOf(` 出现 **2** 次、`fun coverUrlOf(` **1** 次 |

### C-N7 · 回归：上一轮崩溃修复未回退（重点）

| 子项 | 结论 | 证据 |
|---|---|---|
| APK 内 `PlaylistNameDialog.inflateInput` 返回 `Lkotlin/Pair;` | **PASS** | 见下 |

命令与命中（`qa\_qa5_inflate.txt`）：

```
> dexdump.exe qa\_qa5_dexr2\classes4.dex | Select-String "inflateInput" -Context 1,3
      #6              : (in Lcom/soundtrack/music/util/PlaylistNameDialog;)
>       name          : 'inflateInput'
        type          : '(Landroid/content/Context;)Lkotlin/Pair;'      ← 修复后特征
        access        : 0x0012 (PRIVATE FINAL)
```

`Lkotlin/Pair;` 只可能出现在修复后版本（修复前返回 `Landroid/widget/EditText;`）。修复链路（`Pair<View,EditText>` + `setView(root)`）在**已打包产物**中成立 → 点「+」不再 `IllegalStateException: The specified child already has a parent`。

---

## 3. 范围守卫（逐条结论）

### 3.1 本轮实际改动文件集

**关键：git 无法隔离「本轮」**。`git status --porcelain` / `git diff --name-only HEAD` 列出 **19 个已跟踪 M 文件 + 约 100 个未跟踪文件**——因为 HEAD 停留在早期提交，**本地歌单功能的全部源码 + 前几轮修复均未提交**。6 个声称文件**全部是未跟踪（`??`）新增**，git diff 对其恒空。

改用 **mtime 判别**（`qa\_qa5_src_touched.txt`，阈值 2026-09-15 17:45）：

```
17:49:22  …/data/PlaylistStore.kt
17:49:31  …/adapter/MyPlaylistAdapter.kt
17:49:38  …/adapter/PlaylistPickAdapter.kt
17:49:42  …/res/layout/item_pick_playlist.xml
17:49:48  …/ui/MyPlaylistsActivity.kt
17:49:55  …/ui/AddToPlaylistSheet.kt
count: 6
```

→ **本轮改动恰好 = 声称的 6 个文件，且无第 7 个**。前几轮文件 mtime 清晰分离（`SongAdapter.kt` 17:28、`item_song.xml` 17:28、`PlayerActivity.kt` 17:23、`SearchFragment.kt` 17:24、`PlaylistNameDialog.kt` 17:00，其余 11:xx），无一落进本轮窗口。

> 因此「git 集合必须恰好 6」这一字面判据 **不成立**，但根因是仓库未提交状态（环境事实），**不是工程师本轮越界**。按 mtime 判据，本轮范围正确。

### 3.2 B.1 / B.2 · 远程歌单页零改动（byte 级）

| 文件 | HEAD blob | 工作区 blob | 结论 |
|---|---|---|---|
| `ui/PlaylistDetailActivity.kt` | `bad158ba855f1c395b95ebef0a60f4039ba97781` | `bad158ba855f1c395b95ebef0a60f4039ba97781` | **相同 ✅** |
| `res/layout/activity_playlist_detail.xml` | `10545f018bc63d29590d844effe64095489b3bb8` | `10545f018bc63d29590d844effe64095489b3bb8` | **相同 ✅** |

> `PlaylistDetailActivity.kt` mtime 为 17:28（前轮）但 blob==HEAD，即**内容零变化**（仅被前轮构建/工具 touch）。

### 3.3 B.4 / B.4b / B.5 / C.11 · 行尾按钮「图标-行为同源派生」

- `adapter/SongAdapter.kt`（17:28:46）、`adapter/NewSongAdapter.kt`（11:24:08）、`res/layout/item_song.xml`（17:28:15）**均未落入本轮窗口** → 本轮未改动 ✅
- 契约仍在：套件 `C.11` PASS（onMore 分支 `ic_more_vert`+「更多」、else 分支 `ic_download`+「下载」，两分支三件套齐全）；`B.4a/B.4b` PASS（末尾可选 `onLongClick = null`）。

### 3.4 C.1 · 无 ViewBinding

套件 `C.1` PASS（全仓 `java/**` 零 `binding.`/`ViewBinding`）、`C.1b` PASS（`viewBinding = false`）。

### 3.5 E.3 · 块注释卫生

`qa\scan_nested_comments.py` → `problem files: 0 / 108`，`RESULT: CLEAN`，`exit=0`（`qa\_qa5_suite_scan.txt`）。

---

## 4. 既有 QA 套件 + 新增守卫

- `qa\local_playlist_check.py`（纯标准库）：**114 PASS / 0 FAIL / 1 SKIP**（`qa\_qa5_suite_local2.txt`，`exit=0`）
- `qa\scan_nested_comments.py`：**CLEAN / 0 problems**（108 文件全扫）
- `qa\preview_guard.py`：**44 PASS / 0 FAIL**（`exit=0`）

新增守卫（本轮固化的 C-N1~C-N6，风格沿用 `check/check_skip`，含可证伪传统）：

| 编号 | 覆盖 | 结果 |
|---|---|---|
| C.12a/b/c | C-N1 源码两级 + 真值表 7 行 + **坏实现证伪** | 3 PASS |
| C.13a/b/c/d | C-N2 复用 songsOf / 无 getOrNull(2) / 方法层只增 / HEAD 基线 | 3 PASS + **1 SKIP** |
| C.14a–e | C-N3 MyPlaylistAdapter（import/签名/R.id.cover/先清后加载/既有逻辑） | 5 PASS |
| C.15a–e | C-N4 PlaylistPickAdapter（含勾选态四步顺序） | 5 PASS |
| C.16a–c | C-N5 item_pick_playlist.xml 布局 | 3 PASS |
| C.17a–c | C-N6 调用点接线 + `coverUrlOf` 计数 | 3 PASS |

独立脚本 `qa\_qa5_cover_sim.py`（C-N1 真值表 + 证伪，可单独运行，`exit=0`）。

---

## 5. 对抗性验证

### 5.1 RecyclerView 复用竞态 —— **可达（reachable），P2，非本轮引入**

**源码契约**（`MiniImageLoader.load`）：
- 命中 `memCache` → **同步** `view.setImageBitmap(it)`；
- 未命中 → `scope.launch { loadBitmap(...) ; withContext(Main){ view.setImageBitmap(it) } }`；
- **无取消、无「该 View 当前是否仍属于本 url」的校验**。

**可达性判定：可达**。构造：行 A 的 View `V` 绑定 → 冷缓存 → 异步请求 `R_A` 在途 → 快速滑动使 `V` 被复用为行 B → `bind(B)` 清图并（若亦未命中）发起 `R_B` → **若 `R_A` 晚于 `R_B` 返回**，`R_A` 会把 A 的封面盖到正显示 B 的 `V` 上 → 显示错封面。

- **窗口大小** = `R_A` 的（网络+解码）延迟，数十 ms ~ 秒级；快速 fling 时同一 View 可在窗口内被复用多次 → 非纯理论。
- **是否自愈**：`V` 下次被 `bind`（滑回/`notifyDataSetChanged`）时会先清图再重载 → 恢复正确；且此后 `memCache` 命中走同步路径 → 正确。**但若用户停在原地不动，错误封面会持续显示**（不自愈）。
- **定级 P2（局部视觉错误，非崩溃/非数据损坏）**。
- **非本轮回归**：`loader.load(url, view)` 是全仓 11 处调用的既有 API（SongAdapter/NewSongAdapter/PlaylistSongAdapter/PlaylistCardAdapter/ToplistCardAdapter/MiniPlayerController/PlayerActivity/PlaylistDetailActivity/HomeFragment…）。本轮只是新增 2 个调用点，**继承同一既有特性**，未引入新缺陷类型。

**最小修复方案（未实施，仅建议）**：在单一收口点 `MiniImageLoader.load` 加「请求令牌」守卫——
```
// load(url, view): 发起前 view.tag = url
// 异步回到主线程后： if (view.tag == url) view.setImageBitmap(it)
```
**影响面**：仅改 `util/MiniImageLoader.kt`（约 2–3 行）；11 处调用点全部自动受益，向后兼容（只抑制过期写入，不改语义）。可选加固：`bind()` 在无封面时也显式 `cover.tag = null`，避免上一行在途请求落地到空封面行。

### 5.2 空封面 / 无网络 / 解码失败

- **URL 空**：`coverUrlOf` 返回 `null` → `bind` 的 `if (!coverUrl.isNullOrBlank())` 为假 → **不调用** `loader.load` → 保留 XML 背景 `bg_gradient_accent`（渐变占位）。
- **加载失败/解码失败**：`loadBitmap` 返回 `null` → 主线程回调 `bitmap?.let{ setImageBitmap }` 为 no-op → View 仍显示渐变占位。
- 结论：**不会留空白框、不会裂图、不崩**；`background="@drawable/bg_gradient_accent"` 是有效兜底（源码与打包后均已核）。**PASS**

### 5.3 同一首歌在多张歌单

- `songsOf(id)` 只遍历**该歌单**的 `entries`；`entries` 在**单歌单内按 `songKey` 天然去重**——`toggleIn`（存则删/无则加）、`addToPlaylist`（`any{songKey==key} return` 幂等）、`setMembership`（仅 `entry==null` 时加）三处均防重。
- 因此单歌单内一首歌**至多出现一次**；`coverUrlOf` 只在该歌单的 `songsOf` 结果内取第 0/1 首 → **封面不可能取自「不属于该歌单」的歌**（`mapNotNull` 以该歌单的 key 过滤全局曲库）。**PASS**
- 跨歌单：同一 `songKey` 可同时属于多张歌单（AC-17/18，符合需求）；全局曲库 `gcLibrary` 只回收**无人引用**的条目，不会误删在引条目。

---

## 6. 遗留问题定级

| 问题 | 级别 | 说明 |
|---|---|---|
| RecyclerView 复用导致偶发错封面 | **P2** | 局部视觉；可自愈（需重绑）；全仓既有；建议按 §5.1 修 |
| `PlaylistStore.kt` 「纯新增」无 git 基线 | **P3/流程** | 仓库整份功能未提交；建议后续提交一次基线以便回归 |
| 无其它 | — | **无 P0**（无崩溃 / 无数据损坏 / 无功能不可用） |

---

## 7. 取证文件清单（本轮 fresh，命名 `qa\_qa5_*.txt`）

| 文件 | 内容 |
|---|---|
| `_qa5_git.txt` | git status / diff --name-only / HEAD / dist 列表 |
| `_qa5_mtimes.txt` | 6 声称文件 + 前轮文件 mtime + B.1/B.2 blob hash |
| `_qa5_src_touched.txt` | 本轮窗口（≥17:45）改动文件 = 6 |
| `_qa5_baseline.txt` | git log / HEAD 是否含 PlaylistStore / 全仓 apk 列表 |
| `_qa5_cover_sim.py` / `.txt` | C-N1 真值表 + 证伪（独立脚本） |
| `_qa5_suite_local.txt` / `_qa5_suite_local2.txt` | 改良前 92 / 改良后 114 套件输出 |
| `_qa5_suite_scan.txt` / `_qa5_suite_preview.txt` | 注释扫描 CLEAN / preview_guard 44 PASS |
| `_qa5_tools.txt` | build-tools 清单 + `aapt dump badging`（versionName） |
| `_qa5_aapt2_res.txt` | `aapt2 dump resources`（`id/cover` 等） |
| `_qa5_xmltree_pick2.txt` / `_qa5_xmltree_my.txt` | 打包后 item_pick / item_my 布局结构 |
| `_qa5_inflate.txt` | `dexdump classes4.dex` → `inflateInput : (…)Lkotlin/Pair;` |
| `_qa5_dexdump_classes{4,6,8}.dex.txt` | 反汇编全文 |
| `_qa5_store_methods.txt` | `PlaylistStore` 方法表（含 coverUrlOf） |
| `_qa5_apk_classes.txt` / `_qa5_dexprobe.txt` / `_qa5_whichdex.txt` | dex 定位 |
| `_qa5_final_counts.txt` | 套件最终计数 |

> 说明：仓库内大量 `qa\_*`/`_*.txt` 为上几轮自检残留，本报告**未采信**任何旧文件；上表均为本轮重新生成。

---

## 8. 最终裁定

- **路由：NoOne** —— 未发现源码 Bug，无需回退给工程师；未发现自身测试错误。
- **套件：114 PASS / 0 FAIL / 1 SKIP → 0 FAIL ✅**
- **范围**：本轮改动 = 恰好 6 个文件（mtime 判据）；B.1/B.2 blob 相同；C-N7 修复在产物中成立。
- **对抗性 5.1：可达 / P2 / 非本轮引入**，已给最小修复方案。
- **无 P0。**
- **IS_PASS: YES**
