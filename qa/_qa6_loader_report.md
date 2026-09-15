# QA6 独立回归报告 —— P2 异步封面串图（过期结果覆盖）修复 + 陈旧注释

- 仓库根：`C:\Users\b5311\WorkBuddy\2026-09-15-10-37-12\yinfu-music`
- 被测产物：`dist\yinfu-music-1.0.0-20260915.1807.apk`
  - 独立核对：**9,709,095 字节**、`LastWriteTime 2026-09-15 18:08:08`
  - SHA256 = `87050C9242DCF8241F679E240EE41ED41BC15B6EECEE2ABA9E238CC0506A18BF`（与工程师声称一致）
  - `dist\` 内**仅此一份 APK**（旧 `…1750` 已删，`Get-ChildItem dist` 仅 1 项）
- 立场：以"能否证伪"为准，全部证据本轮新生成（`qa\_qa6_*`）；**未改动任何产品源码**（`android/app/src/main/**` 仅新增 QA 守卫，未触碰）。

---

## TL;DR

- **路由判定：NoOne（无需回工程师修）**。功能契约全部通过；唯一新发现是一个 **P3 非阻断残留**（播放页**模糊背景**用的 `loadBitmapAsync` 未纳入本次守卫，属既存、范围外）。
- 套件：`local_playlist_check.py` **122 PASS / 0 FAIL / 1 SKIP**（基线 114 + 我新增 8 条 C.18~C.22）；`scan_nested_comments.py` **CLEAN（108/108）**；`preview_guard.py` **44 PASS / 0 FAIL**。
- 状态机对抗仿真：**GOOD 全 11 PASS**；**BAD_PREFIX / BAD_GUARD_AFTER 在 S1/S1b/S2/S3/S6 被证伪**（仿真有鉴别力）。
- 产物级：`MiniImageLoader` 类里 `WeakHashMap`+`synchronizedMap` 与 `pendingUrl` 字段确在；`load` 回调守卫"取→比→早退"指令**早于** `setImageBitmap`。
- 本轮**只有 2 个源文件被动过**（`MiniImageLoader.kt`、`SearchFragment.kt`，均 18:07:04），无第三处越界。
- **无 P0**（无崩溃 / 无数据损坏 / 功能可用）。

---

## 逐契约结论

| 契约 | 结论 | 关键证据 |
|---|---|---|
| **C-M1** 过期守卫语义（源码） | **PASS** | 见 §1 |
| **C-M2** 状态机对抗仿真 | **PASS** | 见 §2（真值表） |
| **C-M3** 产物级证明（dexdump） | **PASS** | 见 §3 |
| **C-M4** 11 处调用点零改动 | **PASS** | 见 §4（mtime + 接口双证据） |
| **C-M5** SearchFragment 只动注释 | **PASS（附基线说明）** | 见 §5 |
| **C-M6** 既有成果未被碰坏 | **PASS** | 见 §6 |
| 套件/扫描器 | **PASS** | 见 §7 |
| 对抗性验证 1（是否误伤正常路径） | **PASS — 不误伤** | 见 §8 |

---

## §1 C-M1 · 过期守卫语义（源码断言，全部成立）

文件：`android/app/src/main/java/com/soundtrack/music/util/MiniImageLoader.kt`

| 断言 | 结果 | 源码位置 |
|---|---|---|
| 新增 import `java.util.Collections` / `java.util.WeakHashMap` | ✔ | L14 / L15 |
| 字段**弱键**：`pendingUrl = Collections.synchronizedMap(WeakHashMap<ImageView, String>())` | ✔ | L37 |
| `load` 签名未变：`fun load(url: String, view: ImageView, placeholderRes: Int? = null)` | ✔ | L39 |
| 空 url 分支：`pendingUrl.remove(view)` **早于** `return` | ✔ | L43 < L44 |
| 非空分支：`pendingUrl[view] = url` **早于** 任何 `setImageBitmap` | ✔ | 登记 L47 < 同步 set L50 / 异步 set L59 |
| 异步回调：`if (pendingUrl[view] != url) return@withContext` **早于** `view.setImageBitmap(it)` | ✔ | 守卫 L57 < set L59 |
| `loadBitmap`/`loadBitmapAsync`/`md5`/`memCache.sizeOf` 未动 | ✔ | L69 / L81 / L122 / L25 |

> **为何必须弱键**：`pendingUrl` 的 key 是 `ImageView`，value 是 url。若用强键（普通 `HashMap`），则**只要 loader 活着，每个被登记过的 View 都不会被回收** —— RecyclerView 滑过的成百上千个 View、以及它们所属 Activity 的视图树会长期滞留，形成实质内存泄漏。`WeakHashMap` 让 key 只被弱引用，View 一旦不再被视图树/回调强引用即自动消失，条目随之蒸发。value 是 `String`（url），key 存活期间强持有，体量可忽略（见 §8.4）。

---

## §2 C-M2 · 状态机级对抗仿真（核心证据）

脚本：`qa\_qa6_loader_sim.py`（逐字复刻 `load` + 回调语义：`pendingUrl` 映射 + 同步命中 + 异步回调 + 守卫），原始输出 `qa\_qa6_loader_sim.txt`。

三种实现喂进同一仿真：
- `GOOD`＝修复后；`BAD_PREFIX`＝修复前（无 pendingUrl / 无守卫）；`BAD_GUARD_AFTER`＝守卫存在但**写在 setImageBitmap 之后**（顺序反=等于没修）。

### 真值表（该 View 最终显示 + 是否出现过期写入）

| 交错场景 | GOOD 终态 | BAD_PREFIX 终态 | BAD_GUARD_AFTER 终态 |
|---|---|---|---|
| **S1** 经典串图（R_A 先回，R_B 随后） | **B**（丢弃 A） | B，但**曾写入 A**（脏写）→ FAIL | B，但曾写入 A → FAIL |
| **S1b** 晚到覆盖（R_B 先回，R_A 晚到） | **B** | **A**（错封面，原始缺陷现象）→ FAIL | A → FAIL |
| **S2** 同步命中 + 晚到（B 同步 set，R_A 随后） | **B** | **A** → FAIL | A → FAIL |
| **S3** 空 url 撤销（R_A 随后） | **无封面（占位保留）** | **A** → FAIL | A → FAIL |
| **S4** 同 url 重绑（不得误伤） | **A** | A（通过） | A（通过） |
| **S5** 正常单请求（回归） | **A** | A（通过） | A（通过） |
| **S6** 同 View 连切 3 首乱序 C/A/B（最新 C 胜） | **C**，丢弃 A、B | **B** → FAIL | B → FAIL |

- **可证伪性判定**：GOOD 7/7 PASS；`BAD_PREFIX`、`BAD_GUARD_AFTER` 在 **S1/S1b/S2/S3/S6 全部被证伪**（终态错或出现脏写），S4/S5 通过。仿真**并非"好实现能过、坏实现也能过"** —— 具备鉴别力。`_qa6_loader_sim.txt` 汇总 **11 PASS / 0 FAIL，exit=0**。
- 说明：题面场景 1 写的是"R_A 先返回 → 终态 B"。该交错下**仅比终态**两种实现都得 B，故我把判据强化为"**任何过期写入都算 FAIL**"（对 BAD 报 FAIL，符合"必须丢弃 A"）；并额外补了 **S1b**（R_B 先回、R_A 晚到）直接复现工程师自述的"晚到覆盖"原始现象，BAD 的终态即错成 A，判别最干净。

---

## §3 C-M3 · 产物级证明（dexdump，取自新 APK）

APK 解包（`qa\_qa6_dexextract.py`）：`MiniImageLoader` 定义在 **classes4.dex**（唯一同时含 `WeakHashMap`+`synchronizedMap`）。反汇编：`build-tools\android-sdk\build-tools\34.0.0\dexdump.exe -d qa\_qa6_dexr\classes4.dex`。

**(a) 字段/初始化（`MiniImageLoader`）** — 命中原文：
```
Instance fields:
    #2  name: 'pendingUrl'   type: 'Ljava/util/Map;'   access: 0x0012 (PRIVATE FINAL)
<init>:
002cb4: |0044: new-instance v0, Ljava/util/WeakHashMap;            // type@005d
002cb8: |0046: invoke-direct {v0}, Ljava/util/WeakHashMap;.<init>:()V
002cbe: |0049: check-cast v0, Ljava/util/Map;
002cc2: |004b: invoke-static {v0}, Ljava/util/Collections;.synchronizedMap:(Ljava/util/Map;)Ljava/util/Map;  // method@00bf
002cca: |004f: iput-object v0, v4, ...MiniImageLoader;.pendingUrl:Ljava/util/Map;   // field@001f
```

**(b) `load` 方法**：签名 `(Ljava/lang/String;Landroid/widget/ImageView;Ljava/lang/Integer;)V`。空 url 分支与登记：
```
002d48: |0022: invoke-interface {v0, v13}, Ljava/util/Map;.remove:(...)   ← 先 remove(view)
002d4e: |0025: return-void                                              ← 再 return
002d5e: |002d: invoke-interface {v0, v13, v12}, Ljava/util/Map;.put:(...)  ← pendingUrl[view]=url 登记
002d82: |003f: invoke-virtual {v13, v1}, ImageView;.setImageBitmap:(...)  ← 同步命中路径 set（在登记之后）
```

**(c) 异步回调守卫 `MiniImageLoader$load$3$1.invokeSuspend`（决定性）** —— "取→比→早退"**早于** `setImageBitmap`：
```
002330: |0016: invoke-static access$getPendingUrl$p(...)Ljava/util/Map;   ← 取 pendingUrl
00233c: |001c: invoke-interface {v1, v2}, Ljava/util/Map;.get:(...)       ← 取 pendingUrl[view]
002348: |0022: invoke-static {v1, v2}, Intrinsics;.areEqual:(...)Z        ← 与当前请求 url 比较
002350: |0026: if-nez v1, 002b                                           ← 不等则跳过 set
002358: |002a: return-object v1                                          ← ★早退（丢弃过期结果）
002368: |0032: invoke-virtual {v2, v1}, ImageView;.setImageBitmap:(...)   ← 只有相等才 set
```
> 判据：**早退指令（0x0028~0x002a）在 `setImageBitmap`（0x0032）之前**。顺序正确。

**(d) 未误改**：`loadBitmapAsync` 仍在且签名未变 `(Ljava/lang/String;Lkotlin/jvm/functions/Function1;)V`；`loadBitmap` 两个重载、`md5` 均在。命中原文见 `qa\_qa6_dexdump_hits.txt` §[7]。

> grep 原始命令：见 `qa\_qa6_dexdump_hits.txt`（对 `qa\_qa6_dexdump_classes4.txt` 定向 `Select-String`，含上述所有命中行与行号）。

---

## §4 C-M4 · 11 处调用点零改动（双证据）

**(a) 接口判据**：全仓 `android/app/src/main` 内 `\.load(` 命中**恰 11 处**，且经括号配平解析**全部为二元形态** `loader.load(url, cover)`（实参形式未变）；`load` / `loadBitmapAsync` 签名未变（套件 C.21 = PASS，`calls=11 binary_ok=True`）。11 处：
```
ToplistCardAdapter.kt:55   NewSongAdapter.kt:50      SongAdapter.kt:90
HomeFragment.kt:145        PlaylistPickAdapter.kt:62  MyPlaylistAdapter.kt:61
PlaylistCardAdapter.kt:54  PlaylistSongAdapter.kt:66 MiniPlayerController.kt:93
PlayerActivity.kt:132      PlaylistDetailActivity.kt:102
```

**(b) mtime 判据**（`qa\_qa6_mtimes.txt`）：11 个调用点文件的 mtime **全部 < 18:00:00**（本轮窗口起点）：
```
11:01:16 ToplistCardAdapter.kt | 11:24:08 NewSongAdapter.kt  | 17:28:46 SongAdapter.kt
11:24:13 HomeFragment.kt       | 17:49:38 PlaylistPickAdapter.kt | 17:49:31 MyPlaylistAdapter.kt
11:01:16 PlaylistCardAdapter.kt| 11:21:23 PlaylistSongAdapter.kt | 11:01:16 MiniPlayerController.kt
17:23:49 PlayerActivity.kt     | 17:28:15 PlaylistDetailActivity.kt
（仅 MiniImageLoader.kt 与 SearchFragment.kt = 18:07:04）
```

**(c) 越界扫描**（`qa\_qa6_touched.txt`）：`android/app/src` 全树 177 个文件中，mtime ≥ 18:00:00 的**只有 2 个**：
```
2026-09-15 18:07:04  \android\app\src\main\java\com\soundtrack\music\ui\SearchFragment.kt
2026-09-15 18:07:04  \android\app\src\main\java\com\soundtrack\music\util\MiniImageLoader.kt
```
→ **无第 3 个文件被动过，无越界。**

---

## §5 C-M5 · SearchFragment 只动注释（PASS，附基线说明）

**先声明一个环境事实**：本仓 **HEAD = `27fd493`（2026-09-10）早于整个"本地歌单"功能**；该功能（含 SearchFragment 的 `showSongMenu` 等）至今**未提交**（任务 #32「提交 git 基线」仍 pending）。

因此题面给的判据 *"`git diff` 里只允许出现注释行"* **无法字面成立** —— `git diff -- SearchFragment.kt` 必然包含整段**未提交的功能代码**（不是本轮改的）。这不是本轮越界，而是**没有 round-start 基线**导致的混同。为此我用**可与基线无关的独立判据**证明"本轮只改注释"：

1. **用户可见字面量未改**：`"已加入我喜欢的音乐"`、`"已从我喜欢的音乐移除"` 两串**原样存在**（半角引号未变）——套件 C.22a PASS。
2. **实现体未变**：`PlaylistStore.get(requireContext()).toggleIn(PlaylistModels.DEFAULT_PLAYLIST_ID, song)` + 双文案 Toast 仍在——C.22c PASS；`SongAdapter` 接线/`onMore`/`showSongMenu` 结构由既有 **C.11 守卫仍 PASS**。
3. **陈旧承诺确已改**：「与播放页红心操作保持完全一致」**已消失**，改为「与本地歌单系统的默认歌单收藏一致」——C.22b PASS。
4. **字节码级差分（决定性，跨基线）**：把**新 APK** 里 SearchFragment 的结构指纹与**上一轮产物（1729 APK，17:57 采集的结构 dump）**逐方法比对（`qa\_qa6_sf_bytecode_cmp.py` → `qa\_qa6_sf_bytecode_cmp.txt`）：
   ```
   OLD methods=48   NEW methods=48      方法签名集一致: True   （无方法增删改名）
   num(registers/ins/outs/insns size) mismatch methods: 0      （代码尺寸指纹全同）
   offset-sequence MISMATCH methods: 0                          （指令布局逐条相同）
   line-number deltas observed (per method): [0, 3]             （行号仅 {0, +3} 平移）
   RESULT: CONSISTENT —— 行号仅整体平移 +3 行（= 注释增删 3 行），代码未变
   ```
   > 注解：`toggleFavoriteToDefault` 的 KDoc 由 4 行扩到 7 行（旧「文案与播放页红心…一致」1 行 → 新「文案与本地歌单系统…一致」+ 空行 + 2 行"注：…"），恰 +3 行；**该 KDoc 之前的方法 Δ=0，之后的方法 Δ=+3**，且所有方法 offset 布局与代码尺寸指纹**逐条不变** —— 这正是"**仅增删 3 行注释、零代码改动**"的充分形态（注释不参与编译）。
   > 该差分用的"上一轮 dump"是**原样捕获的结构数据**，仅作**差分参照**（非主证据）；主证据是第 1~3 条源码级断言。

**结论：SearchFragment 本轮仅动 KDoc 注释，实现与字符串一字未改 → PASS。**（题面 `git diff` 判据因缺基线不适用，已改以字节码差分等更强手段满足。）

---

## §6 C-M6 · 既有成果未被碰坏（全 PASS）

- **B.1 / B.2（相对 HEAD 逐字节）**（`qa\_qa6_blob.txt`）：
  ```
  PlaylistDetailActivity.kt      HEAD=bad158ba855f1c395b95ebef0a60f4039ba97781  work=同  → SAME ✔
  res/layout/activity_playlist_detail.xml  HEAD=10545f018bc63d29590d844effe64095489b3bb8  work=同 → SAME ✔
  ```
- **C.10（"点+崩退"修复仍在新 APK）**：`dexdump classes4.dex` →
  `com.soundtrack.music.util.PlaylistNameDialog.inflateInput:(Landroid/content/Context;)Lkotlin/Pair;`，体内 `TuplesKt.to(root, editText)` → **返回类型仍是 `Lkotlin/Pair;`**（不是 EditText）。✔
- **C.12~C.17（歌单封面契约）**：套件全绿。其中两个 adapter 的**三段式顺序**仍成立：
  - `C.14d MyPlaylistAdapter` clear@117 < load@229，`!isNullOrBlank` 判空包裹 = True ✔
  - `C.15d PlaylistPickAdapter` clear@77 < load@189，判空包裹 = True ✔
  - **未受本轮 `load` 内部改动连带**：两 adapter 的调用形态（清残留→判空→`loader.load(coverUrl, cover)`）与 `C.17a/b/c` 接线一字未动。
- **`SongAdapter.kt` / `NewSongAdapter.kt` / `res/layout/item_song.xml` 未被动过**：mtime 分别为 17:28:46 / 11:24:08 / 11:01:16（均 < 18:00 本轮窗口）；`item_song.xml` blob 与 HEAD **SAME**。
  - 注：`SongAdapter.kt` / `NewSongAdapter.kt` 相对 HEAD 为 `DIFF`（**属未提交的本地歌单功能**，非本轮改动）；本轮 mtime 判据已排除本轮触碰。

---

## §7 既有套件 / 扫描器（本轮重跑）

| 套件 | 结果 | 文件 |
|---|---|---|
| `qa/local_playlist_check.py` | **122 PASS / 0 FAIL / 1 SKIP**（exit 0） | `qa\_qa6_suite.txt` |
| `qa/scan_nested_comments.py` | **CLEAN**，scanned 108/108，problem 0 | `qa\_qa6_scan.txt` |
| `qa/preview_guard.py` | **44 PASS / 0 FAIL** | `qa\_qa6_preview.txt` |
| （基线对照） | 改动前 114 PASS / 0 FAIL / 1 SKIP | — |

- 唯一 **SKIP** 仍是 `C.13d`（`PlaylistStore.kt` 未跟踪、无 HEAD 基线，属环境事实）——**非静默 PASS**。
- 新增守卫 **8 条**（见下），套件项数 114 → **122**，仍 **0 FAIL**。

---

## §8 对抗性验证结论（真结论）

### 8.1 守卫会不会误伤正常路径？→ **不会（明确结论）**
- 守卫只挡"**完成时该 View 当前期望的 url ≠ 本请求 url**"的结果；对"当前期望 == 本请求"一律放行。
- 覆盖并通过的场景：**S5**（单请求，放行）、**S4**（同 url 重绑，放行，且不产生 stale）、**S6**（同 View 连切 3 首，**最新一首胜**，仅丢弃旧两首）。均 PASS。
- 真实业务对应：`MiniPlayerController`（`cover.setImageDrawable(null)` 后 `loader.load(song.coverUrl, cover)`，随切歌在同一 ImageView 反复换 url）、`PlayerActivity`（`loader.load(song.coverUrl, coverView)`）—— 切歌时**新版封面照常显示**，只是**旧歌的晚到封面被丢弃**（正是期望）。
- **误伤的唯一理论条件**是"当前期望登记被意外清空/改变"，而唯一的清空路径是显式 `load("")`，唯一的改变路径是后续 `load(新url)`——两者都意味"旧结果本就不该显示"。**无误伤。**

### 8.2 并发安全（`synchronizedMap(WeakHashMap)`）
- 单次 `get/put/remove` 均在 `Collections.synchronizedMap` 的同一把互斥锁内原子完成。
- "写登记（`load`）"与"回调读（`get`）"是两次独立操作，其间允许被另一次 `load` 改写——这正是**期望语义**（以最后一次登记为准），**无需额外同步**。
- **无 `ConcurrentModificationException` 风险**：源码中 `pendingUrl` 只有 `remove` / `put` / `get` 三个**单点**操作，**不存在任何对它的遍历/迭代**（已逐行确认 `MiniImageLoader.kt` 无 `for/iterator/values()/entrySet()`）。复合的"读-改-写"也未出现（`load` 里 `remove` 与 `put` 分属互斥分支）。
- **可见性**：`load` 在调用线程（RecyclerView `onBindViewHolder`/主线程）写，回调在 `Dispatchers.Main` 读；同一 `Main` 线程 + `synchronizedMap` 双重保证可见性。（`loadBitmap` 的网络/解码在 `Dispatchers.IO`，但 `pendingUrl` 的读写都不在 IO 线程。）

### 8.3 弱键副作用（会不会"登记被 GC 清掉→误判过期→丢弃本该显示的图"？）→ **不可达**
- 当回调真正执行时，被 set 的 View 由 **lambda 闭包强持有**（`MiniImageLoader$load$3$1.$view` 是实例字段，dexdump 已见 `$view:Landroid/widget/ImageView;`），从请求发出到回调落地**全程强引用**该 View。
- 且该 View 若仍显示在界面上，必被**父视图树强引用**。二者叠加 ⇒ **登记条目在其"尚有意义"的窗口内不可能被 WeakHashMap 清除**。
- 条目被清除只可能发生在 View 已不可达（已从视图树移除且无回调持有时），此时对应的结果本就无需应用。**不会因此误丢应显示的图。**

### 8.4 内存/泄漏
- value 为 `String`（url），仅在 key（View）存活期间强持有；单个 url 数十字节级，且条目随 View 回收**自动消失**，**不构成泄漏**，可忽略。

### 8.5 残留观察（P3，非阻断，范围外）
- 播放页**模糊背景**走的是 `PlayerActivity.kt:135 loader.loadBitmapAsync(song.coverUrl){…}` —— 该 API **本轮未加守卫**（方法签名与实现未动，属既存行为）。极端快速连切时，**旧歌的模糊背景**仍可能在晚到后覆盖新歌背景（`bgView` 被复用）。**注意：这不是本轮缺陷，也不是回归**（`loadBitmapAsync` 本就无守卫、非本轮范围）；仅提示后续若要求"背景也不串"，需对该 API 施加同类守卫。
- 另一既有无害项：`res/layout/item_song.xml` 的静态 `contentDescription="更多"` 每次 bind 被覆盖（上一轮已记录，非本轮）。

---

## §9 新增 QA 守卫（C.18 ~ C.22）

落地于 `qa/local_playlist_check.py` 新增 **`C3.`** 段（`# C3. 本轮竞态修复契约`），共 **8 条 `check()`**，在既有 `check()/check_skip()` 与可证伪风格内：

| 守卫 | 断言 | 结果 |
|---|---|---|
| **C.18** | `pendingUrl = Collections.synchronizedMap(WeakHashMap<ImageView,String>())` + 两处 import（弱键语义） | PASS |
| **C.19** | `load` 空 url 分支：`pendingUrl.remove(view)` 早于 `return` | PASS |
| **C.20** | 登记早于任何 `setImageBitmap`；回调守卫比较早于回调内 `setImageBitmap`（**下标严格比较**） | PASS |
| **C.20'** | **可证伪自证**：删掉守卫那一行 → C.18-C.20 必须 FAIL（非空断言） | PASS |
| **C.21** | 全仓 `.load(` 恰 11 处且均二元形态 + `load`/`loadBitmapAsync` 签名未变 | PASS |
| **C.22a** | SearchFragment 两个用户可见字符串字面量原样存在 | PASS |
| **C.22b** | 陈旧承诺消失、新 KDoc 出现 | PASS |
| **C.22c** | `toggleFavoriteToDefault` 实现体未变 | PASS |

> `C.20'` 内嵌可证伪：把真实源码里的 `if (pendingUrl[view] != url) return@withContext` 删掉，断言函数 `_q6_loader_guard` 必须返回 False；实测 FAIL（守卫具证伪力）。
> 仿真层可证伪见 §2（`qa\_qa6_loader_sim.py`，GOOD 全绿 / BAD 被证伪）。

---

## §10 SKIP / UNVERIFIED（显式列出，绝不静默 PASS）

| 项 | 状态 | 原因 |
|---|---|---|
| C.13d `PlaylistStore.kt` 相对 HEAD 纯新增 | **SKIP**（既有） | 该文件未跟踪，无 HEAD 基线可逐行比对（环境事实） |
| 真机/模拟器运行时行为（菜单真弹出、切歌真不串封面等） | **UNVERIFIED** | 本机无真机/无模拟器、沙箱内不跑 gradle；结论仅建立在**源码语义 + 产物字节码 + 资源表 + 逻辑仿真**之上 |
| C-M5 的"`git diff` 仅注释行"字面判据 | **不适用（非 SKIP 的通过）** | HEAD 早于整段未提交功能，diff 必然含功能码；已改以**字节码级差分**（§5.4）+ 源码级断言满足 |

---

## §11 遗留问题定级

- **P0（崩溃 / 数据损坏 / 功能不可用）：无。**
- **P1/P2：无。**
- **P3（非阻断观察）**：
  1. 播放页模糊背景 `loadBitmapAsync` 未纳入守卫（既存、范围外，见 §8.5）。
  2. `item_song.xml` 静态 `contentDescription="更多"` 成死属性（上轮已记）。

---

## §12 证据文件清单（本轮全部新生成，`qa/`）

- `_qa6_loader_report.md`（本报告）
- `_qa6_loader_sim.py` / `_qa6_loader_sim.txt`（状态机对抗仿真 + 真值表）
- `_qa6_dexextract.py` / `_qa6_dexprobe.txt`（APK 解包定位）
- `_qa6_dexdump_classes4.txt` / `_qa6_dexdump_classes8.txt` / `_qa6_dexdump_hits.txt`（dexdump 原始与命中摘录）
- `_qa6_sf_bytecode_cmp.py` / `_qa6_sf_bytecode_cmp.txt`（SearchFragment 跨产物结构指纹差分）
- `_qa6_git.txt` / `_qa6_gitx.txt` / `_qa6_head_sf.txt` / `_qa6_diff_sf.txt` / `_qa6_diff_mil.txt` / `_qa6_diffstat.txt`（git 状态、diff、HEAD 对照）
- `_qa6_blob.txt`（HEAD vs 工作区 blob 哈希）
- `_qa6_mtimes.txt` / `_qa6_touched.txt`（mtime 取证 / 越界扫描）
- `_qa6_dist.txt` / `_qa6_apkhunt.txt` / `_qa6_apkhunt2.txt`（产物与旧 APK 搜证）
- `_qa6_suite.txt` / `_qa6_scan.txt` / `_qa6_preview.txt` / `_qa6_tools.txt`（套件重跑与工具链）
- 守卫落地：`qa/local_playlist_check.py`（+C.18~C.22，属 QA 授权改动范围）

---

## 附录 A · `git diff -- SearchFragment.kt`（完整，逐字）

> 说明：因 HEAD(`27fd493`) 早于整段**未提交**的本地歌单功能，此 diff **必然包含功能代码**（非本轮改动）；"只允许注释行"的字面判据不适用。本轮仅注释的证据见 §5.1~§5.4。

```diff
diff --git a/android/app/src/main/java/com/soundtrack/music/ui/SearchFragment.kt b/android/app/src/main/java/com/soundtrack/music/ui/SearchFragment.kt
index b0fd0df..c0f6207 100644
--- a/android/app/src/main/java/com/soundtrack/music/ui/SearchFragment.kt
+++ b/android/app/src/main/java/com/soundtrack/music/ui/SearchFragment.kt
@@ -10,6 +10,7 @@ import android.widget.EditText
 import android.widget.TextView
 import android.widget.Toast
 import androidx.fragment.app.Fragment
+import androidx.appcompat.widget.PopupMenu
 import androidx.lifecycle.lifecycleScope
 import androidx.recyclerview.widget.LinearLayoutManager
 import androidx.recyclerview.widget.RecyclerView
@@ -17,6 +18,8 @@ import com.google.android.material.chip.Chip
 import com.google.android.material.chip.ChipGroup
 import com.soundtrack.music.R
 import com.soundtrack.music.adapter.SongAdapter
+import com.soundtrack.music.data.PlaylistModels
+import com.soundtrack.music.data.PlaylistStore
 import com.soundtrack.music.data.PrefsStore
 import com.soundtrack.music.download.DownloadManager
 import com.soundtrack.music.model.Song
@@ -58,7 +61,9 @@ class SearchFragment : Fragment() {
         recycler.layoutManager = LinearLayoutManager(requireContext())
         adapter = SongAdapter(loader,
             onClick = { song -> playSong(song) },
-            onDownload = { song -> downloadSong(song) }
+            onDownload = { song -> downloadSong(song) },
+            onLongClick = { song -> openAddToPlaylist(song) },
+            onMore = { song, anchor -> showSongMenu(song, anchor) }
         )
         recycler.adapter = adapter
 
@@ -310,4 +315,56 @@ class SearchFragment : Fragment() {
             Toast.makeText(requireContext(), if (uri != null) "已下载" else "下载失败", Toast.LENGTH_SHORT).show()
         }
     }
+
+    /** 长按搜索结果 → 打开「加入歌单」选择器（AC-15）。 */
+    private fun openAddToPlaylist(song: Song) {
+        AddToPlaylistSheet.newInstance(song)
+            .show(parentFragmentManager, "add_to_playlist")
+    }
+
+    /**
+     * 行尾「⋮」→ 弹出歌曲操作菜单：加入歌单 / 下载 / 收藏。
+     *
+     * 用 `androidx.appcompat.widget.PopupMenu`（非 `android.widget` 版），保证菜单主题与 AppCompat 一致。
+     * 回调只捕获 [song]（值语义），**不持有 ViewHolder / itemView 引用**，避免 RecyclerView 复用隐患；
+     * 菜单锚点用传入的 [anchor]（即被点击的行尾按钮）。
+     */
+    private fun showSongMenu(song: Song, anchor: View) {
+        val popup = PopupMenu(requireContext(), anchor)
+        popup.menu.add(0, MENU_ID_ADD_TO_PLAYLIST, 0, "加入歌单")
+        popup.menu.add(0, MENU_ID_DOWNLOAD, 1, "下载")
+        popup.menu.add(0, MENU_ID_FAVORITE, 2, "收藏")
+        popup.setOnMenuItemClickListener { item ->
+            when (item.itemId) {
+                MENU_ID_ADD_TO_PLAYLIST -> openAddToPlaylist(song)
+                MENU_ID_DOWNLOAD -> downloadSong(song)
+                MENU_ID_FAVORITE -> toggleFavoriteToDefault(song)
+            }
+            true
+        }
+        popup.show()
+    }
+
+    /**
+     * 在默认歌单（「我喜欢的音乐」）中增 / 删这首歌，并 Toast 提示结果。
+     * 文案与本地歌单系统的默认歌单收藏一致（"已加入我喜欢的音乐" / "已从我喜欢的音乐移除"），确保跨屏体验统一。
+     *
+     * 注：播放页红心自 2026-09-15 起改为「打开加入歌单面板」，不再直接 toggle，
+     * 故此处不再声称与播放页红心的行为一致。
+     */
+    private fun toggleFavoriteToDefault(song: Song) {
+        val added = PlaylistStore.get(requireContext())
+            .toggleIn(PlaylistModels.DEFAULT_PLAYLIST_ID, song)
+        Toast.makeText(
+            requireContext(),
+            if (added) "已加入我喜欢的音乐" else "已从我喜欢的音乐移除",
+            Toast.LENGTH_SHORT
+        ).show()
+    }
+
+    private companion object {
+        const val MENU_ID_ADD_TO_PLAYLIST = 1
+        const val MENU_ID_DOWNLOAD = 2
+        const val MENU_ID_FAVORITE = 3
+    }
 }
\ No newline at end of file
```

> 关键：本轮新增的两行（`注：播放页红心…` 与 `故此处不再声称…`）与改写的 `文案与本地歌单系统的默认歌单收藏一致…` 行**全在 KDoc 注释内**（`*` 前缀）；`toggleFavoriteToDefault` 的实现体与两个字符串字面量在这份 diff 里**与上一轮一致**（字节码差分已证零代码变化，见 §5.4）。

---

**IS_PASS: YES**
