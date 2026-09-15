# QA5 独立回归报告 —— 「加入歌单」可见入口

- 仓库：`C:\Users\b5311\WorkBuddy\2026-09-15-10-37-12\yinfu-music`
- 被测产物：`android\app\build\outputs\apk\debug\yinfu-music-1.0.0-20260915.1729.apk`
  - 独立核对：**9,707,503 字节**、构建时间 **2026-09-15 17:29:32**（与工程师声称一致）
- 立场：以"能否证伪"为准，独立重推、独立取证。

---

## ⚠️ 显著声明：无法验证的运行时行为（本机无真机 / 模拟器）

本机 **没有可用真机或 Android 模拟器**。因此以下**运行时行为一律无法验证**，本轮所有结论
**仅建立在「源码语义 + 产物字节码(dexdump) + 资源表(aapt/aapt2)」之上**，请勿将其读作"已实测"：

- PopupMenu 真的弹出、三个菜单项真的可点、点「加入歌单」真的打开面板；
- 播放页红心点击真的不再直接收藏、面板真的打开、Toast 真的出现；
- 搜索页 ⋮ 点击真的弹菜单；长按直达面板真的触发；
- 任何 UI 布局/交互/无障碍在真机上的最终呈现。

---

## 路由判定：**NoOne**（功能契约全部通过）

- 三套既有套件全绿；新增契约守卫可证伪；两个"还原文件"逐字节一致；新行为与旧修复都在产物里。
- 唯一发现是一个**非阻断的注释陈旧点**（见"发现清单 #1"），属文档漂移、不影响行为，
  建议 Engineer 顺手清理，不阻断交付。

---

## 1【重点】"图标与行为同源派生"契约 —— 可证伪

**源码（`adapter/SongAdapter.kt` `bind()`）**：两个分支各自**同时**设置了三件套：

| 分支 | 图标 | contentDescription | 点击行为 |
|---|---|---|---|
| `onMore != null` | `ic_more_vert` | `"更多"` | `onMore.invoke(s, v)`（弹菜单） |
| `else` | `ic_download` | `"下载"` | `onDownload?.invoke(s)`（直接下载） |

**产物字节码（dexdump `classes6.dex` `SongAdapter$VH.bind`）**：`if-eqz v1, +0x1f`
按 `getOnMore$p()` 返回值分派，**每个分支内部都是**
`setImageResource → setContentDescription → setOnClickListener` 三连（见
`qa/_qa5_apk_bytecode.txt` §3）。即"图标语义与点击行为永不脱节"在源码与字节码两个层面均成立。

**套件新增守卫断言**（`qa/local_playlist_check.py` C.11，语义锚定、不看行号，QA5-GUARD-BEGIN/END 包裹）：
- `[PASS] C.11 SongAdapter 行尾按钮两分支均同时设置 图标+contentDescription+点击行为（同源派生）`
  — `onMore 分支[三件套齐全(更多)] / else 分支[三件套齐全(下载)]`

**证伪自证**（`qa/_qa5_more_falsify.py`，**原样抽取** C.11 断言块、正反例共用同一函数）：
```
抽取的断言块长度: 1977 字符
反例构造：从 onMore 分支删除 setImageResource 行，共改动 1 处

[正例] 真实 SongAdapter.kt           -> PASS  (onMore 分支[三件套齐全(更多)] / else 分支[三件套齐全(下载)])
[反例] 只改点击行为/不改图标的变体    -> FAIL  (onMore 分支[缺 setImageResource] / else 分支[三件套齐全(下载)])

可证伪自证: PASS（正例通过、反例失败，断言具证伪力）
```
→ 反例 = 内存字符串里把 onMore 分支的 `setImageResource(ic_more_vert)` 删掉（只改/保留行为、图标不动），
同一断言函数**必须 FAIL**，真实文件**PASS**。未写任何业务源码。

**调用方独立性**：`SongAdapter` 恰好 **2 个调用点**（源码 grep + 字节码 `.<init>` 命中 2 处）：
`SearchFragment`（显式传 `onMore`）、`PlaylistDetailActivity`（省略 `onMore`，走默认参数）。
**无第三处**，且**无位置参数构造**（两处皆具名；字节码一处是 5 参全量、一处带
`DefaultConstructorMarker` 走默认参数）。新增参数都在末尾带 `= null`，位置参数不可能错位。

---

## 2【重点】"两个文件相对 HEAD 零改动" —— 字节级 blob 证据

| 文件 | `git rev-parse HEAD:<path>` | `git hash-object <path>` | 一致 |
|---|---|---|---|
| `ui/PlaylistDetailActivity.kt` | `bad158ba855f1c395b95ebef0a60f4039ba97781` | `bad158ba855f1c395b95ebef0a60f4039ba97781` | ✅ |
| `res/layout/item_song.xml` | `54f81273bf6320837df718f76b4fd245c70a585b` | `54f81273bf6320837df718f76b4fd245c70a585b` | ✅ |

- 两文件 mtime 均为 **17:28:15**（同一秒内，一次 `git checkout --` 落盘所致）→ **mtime 变新 ≠ 内容有变**，
  blob 哈希相同已证内容逐字节等于 HEAD。
- 产物侧复核：`aapt dump xmltree` 显示 APK 内 `item_song.xml` 的 `btn_more` **`src=@0x7f0800e6=ic_download`**、
  `contentDescription="更多"`（即 HEAD 原状）。

**"没有别的文件被误还原"**：扫描 `android/app/src` 全部文件，17:20 之后仅 6 个文件被动过：
`ic_more_vert.xml`(新)、`PlayerActivity.kt`、`SearchFragment.kt`、`PlaylistDetailActivity.kt`、
`item_song.xml`、`SongAdapter.kt` —— 除两个还原文件外都是本轮应改/新增。重点检查项：
- `util/PlaylistNameDialog.kt` mtime = **17:00:15**（早于 17:28 的 checkout，未被触碰；且它是未跟踪文件，
  `git checkout --` 本就不作用于未跟踪文件）；
- 更早的 UI 适配文件 mtime 全为历史值：`SoundtrackApp.kt` 11:21、`PlayerRepository.kt` 11:33、
  `MineFragment.kt` 11:22、`HomeFragment.kt` 11:24、`NewSongAdapter.kt` 11:24、`fragment_mine.xml` 11:21、
  `activity_player.xml` 14:09 等 —— 无一被重写到 17:28。
- 结论：本轮 `git checkout --` 只碰了那 2 个文件，**无连带误还原**。

---

## 3【重点】上一轮崩溃修复仍在产物里（字节码级）

`dexdump classes4.dex`：
```
com.soundtrack.music.util.PlaylistNameDialog.inflateInput:(Landroid/content/Context;)Lkotlin/Pair;
```
`showNew`/`showRename` 两处调用点都按 `Lkotlin/Pair;` 接收。
→ **`inflateInput` 返回类型仍是 `Lkotlin/Pair;`（不是 `EditText`）**，未被 checkout 连带回退。

`res/layout/dialog_playlist_name.xml`（APK xmltree）：根 `LinearLayout`（line 6）内含 `EditText`
（line 17，`id=0x7f0a0141=input_name`）—— 结构未变。

---

## 4【重点】新行为真的进了 APK（字节码级）

- `aapt2 dump resources`：`drawable/ic_download=0x7f0800e6`、**`drawable/ic_more_vert=0x7f0800f1`** 都在资源表。
- `dexdump classes6.dex`：`SongAdapter$VH.bind` 内出现
  `sget ... R$drawable.ic_more_vert` → `setImageResource`（新图标分支），另一分支
  `sget ... R$drawable.ic_download`。
- 闭环：`dexdump classes2.dex` 的 `R$drawable` 常量 —— `ic_more_vert=2131230961`
  **= 0x7f0800f1**、`ic_download=2131230950` **= 0x7f0800e6**，与资源表 id 逐一对应。
  （本 debug 产物 R 字段未内联，取的是 `sget` 字段访问，故补取 R 类常量把字段名钉回数值 id。）
- 结论：**"运行时派生图标"的代码确实编进了产物**。

---

## 5 上一轮 UI 适配未被碰坏（回归防护）

mtimes 全为历史值：`activity_player.xml` **14:09:51**、`values/dimens.xml` **14:09:30**、
`values-w360dp/dimens.xml` **14:09:30**、`values-w600dp/dimens.xml` **14:21:45**、
`values-w800dp/dimens.xml` **14:21:53** —— 与声称一致，未被本轮触碰。

产物资源表 config 值（`aapt2 dump resources`）：

| dimen | default | w360dp | w600dp | w800dp |
|---|---|---|---|---|
| player_ctrl_btn_small | 40dp | 44dp | — | — |
| player_ctrl_btn_mid | 48dp | 52dp | — | — |
| player_ctrl_btn_play | 56dp | 68dp | — | — |
| player_ctrl_row_margin_h | 0dp | — | 120dp | 220dp |

**逐一与上一轮一致**。另：`qa/` 下**既有 `.py` 脚本本轮无一被改为**（mtimes 均在 13:17 及更早；
`local_playlist_check.py` 的 17:09 属上一轮 QA4，且我在其上新增 C.11 属本轮 QA 授权改动）。

---

## 6 套件复跑（原始数字）

| 套件 | 结果 | 说明 |
|---|---|---|
| `qa/local_playlist_check.py` | **92 PASS / 0 FAIL / 0 SKIP** | 基线 91 + 我新增 C.11 = 92（只加了 1 条 `check`） |
| `qa/scan_nested_comments.py` | **CLEAN / 108** | 与期望一致 |
| `qa/preview_guard.py` | **44 PASS / 0 FAIL** | 与期望一致（第 2 节真连网，本次无波动） |
| `qa/_qa5_more_falsify.py` | 正例 PASS / 反例 FAIL | 可证伪自证 PASS |

原始输出见 `qa/_qa5_suite_local.txt`、`_qa5_suite_scan.txt`、`_qa5_suite_preview.txt`、`_qa5_more_falsify.txt`。
**未为绿灯改动任何源码或弱化既有断言。**

---

## 7 代码级负向排查（逐条结论）

1. `SearchFragment.showSongMenu` 用的是 **`androidx.appcompat.widget.PopupMenu`**（字节码 `new-instance Landroidx/appcompat/widget/PopupMenu;`），
   非 `android.widget` 版。✅
2. 菜单回调**只捕获 `song`**：字节码 `showSongMenu$lambda$20:(SearchFragment;Song;MenuItem)Z` 无 ViewHolder/itemView，
   无 RecyclerView 复用隐患。✅
3. 菜单项 **3 个**（字节码 3 次 `Menu.add`），与 3 个 `switch` 分支（id 1/2/3）**一一对应**，无错配。✅
4. `PlaylistStore.toggleIn`：读实现确认 —— **加入返回 `true`、移除返回 `false`**（`return true`/`return false` 两处），
   故 `toggleFavoriteToDefault` 的 Toast 文案正确。✅
   - ⚠️ **文案一致性**：`SearchFragment` 用「已加入我喜欢的音乐」/「已从我喜欢的音乐移除」，与
     **PRD/架构文档一致**；但其 KDoc 声称"与播放页红心操作保持完全一致"——**播放页本轮已不再存在这两个字面量**
     （红心改为开面板 + 「已更新歌单」）。→ 见"发现清单 #1"。
5. `PlayerActivity.toggleFavorite()` 已删除且**全仓无残留**（源码 grep 0 命中 + 字节码 `toggleFavorite(` 0 命中）。
   红心"无播放中歌曲"时的 `isEnabled=false` + `alpha=0.4f` 路径仍在（`refreshFavorite` 首分支）。✅
6. `openAddToPlaylist()` 的 `setOnChanged` 内 `refreshFavorite` 与新增 Toast「已更新歌单」**都在**；
   `AddToPlaylistSheet` **仅在点「确定」时** `onChanged?.invoke()`，点「取消」只 `dismiss()` ——
   取消/点外部**不会弹 Toast**。✅
7. 搜索页 `onLongClick` 长按直达面板**仍保留**（`onLongClick = { song -> openAddToPlaylist(song) }`）。✅

---

## 8 边界与产品一致性观察（供决策，非 Bug）

- **远程歌单页 `PlaylistDetailActivity` 的运行时差异确实存在**：它复用同一 `SongAdapter` 且不传 `onMore`，
  于是走 `else` 分支，`contentDescription` 在 bind 时被设为 **"下载"**。
  对照 HEAD：旧 `SongAdapter.bind` **完全不设 contentDescription**，页面显示的是布局 XML 里的静态 **"更多"**；
  即"更多 → 下载"的取值变化属实。
  **判定：净改进。** 该行尾按钮的图标(`ic_download`)、行为(下载)、描述(下载) 现在三者一致；
  此前是"描述=更多、行为=下载"的既有无障碍错配，本轮顺手修正了它。
  唯一残留是布局 XML 的静态 `contentDescription="更多"` 成了**每次 bind 都被覆盖的死属性**（无害，可日后清理）。
- **播放页红心**：由"一点即收藏"改为"一点开面板、确认一次"，无"既收藏又弹面板"的重复行为
  （点击只调 `openAddToPlaylist()`；收藏写入只发生在面板确认后的 `setMembership`）。✅

---

## 发现清单（供 Engineer 决策）

1. **[非阻断·文档漂移]** `SearchFragment.toggleFavoriteToDefault` 的 KDoc（源码 348–351 行）声称
   "文案与***播放页红心操作***保持完全一致（已加入我喜欢的音乐 / 已从我喜欢的音乐移除）"。
   但本轮播放页红心已不再直接 toggle/不再有这两个字面量（改为开面板 + 「已更新歌单」）。
   → 该 KDoc 引用了**已删除的行为**，属陈旧承诺；字符串本身与 PRD/架构文档仍一致。
   建议改为"与 PRD 默认歌单收藏文案一致"之类的表述。**不影响任何行为，不阻断交付。**

---

## 证据文件清单（`qa/`）

- `_qa5_report.md`（本报告）
- `_qa5_blobhash.txt`（blob 逐字节比对）
- `_qa5_mtimes.txt` / `_qa5_src_touched.txt` / `_qa5_qa_py_mtimes.txt`（mtime 取证）
- `_qa5_apk_bytecode.txt`（dexdump / aapt2 / xmltree 字节码级证据汇总）
- `_qa5_apk_files.txt`（APK 解包清单）
- `_qa5_apk_xml_item_song.txt` / `_qa5_apk_xml_dialog_name.txt`（产物布局树）
- `_qa5_suite_local.txt` / `_qa5_suite_scan.txt` / `_qa5_suite_preview.txt` / `_qa5_more_falsify.txt`（套件原始输出）
- 脚本（属 QA 授权可改范围）：`local_playlist_check.py`（+C.11）、`_qa5_more_falsify.py`（新）
