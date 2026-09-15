# QA 验证报告（第 2 轮·最终）—— 本地歌单系统（Android 端）

> QA 工程师：严过关（Yan） · 团队：`software-yinfu-playlist`
> 被验证对象：工程师交付的本地歌单系统（新增 19 / 修改 11 个文件）；**第 2 轮：含 3 处阻断缺陷的修复**
> 验收依据：`docs/PRD-local-playlist.md`（AC-1 ~ AC-34）、`docs/ARCHITECTURE-local-playlist.md`
> 验证脚本：`qa/local_playlist_check.py`（纯 Python 标准库，零第三方依赖）
> 报告日期：2026-09-15 ｜ 轮次：**Round 2（终）**

---

## 0. 验证方式与能力边界声明（必读）

**本机不存在编译与运行条件**，已实测确认：无 JDK、无 Android SDK、无 Gradle；`java` / `javac` / `kotlinc`
均不存在；`android/gradlew` 不在仓库内。因此本次验证**没有编译、没有运行 instrumented test、没有上真机/模拟器**。

全部结论仅由三种手段得出：**① 静态检查**（扫源码与资源）＋ **② 契约检查**（源码级断言，按大括号配对严格圈定函数体）
＋ **③ 逻辑仿真**（Python 逐字复刻 `PlaylistStore` 语义）。

> **诚实声明**：`静态可证 PASS` 仅指"该 AC 对应的源码逻辑在静态层面已正确落地"，**不等于**"真机已通过"。
> `无法静态验证`（仅 AC-32，需真机杀进程重启）如实标注，**未**写成通过。

---

## 1. 脚本执行结果（第 2 轮）

```
命令：python qa/local_playlist_check.py   （工作目录 = 仓库根）
退出码：0
合计：87 PASS / 0 FAIL
```

分组：A 资源引用完整性 7/7 ｜ B 向后兼容 8/8 ｜ C 关键契约 27/27 ｜ D 逻辑仿真 44/44。

> 脚本在本轮**新增/加强**了下列断言（把上一轮的口头结论固化为可复跑检查）：
> - `C.3e/C.3f`：恢复调用必须落在 `onPlayerError` **方法体内**、且**不得**落在 `onPlaybackStateChanged` 体内
>   （按首尾大括号配对圈定函数体，不依赖固定行号/缩进）；`C.3g` 非死代码（调用点 ≥ 1）；`C.3h` 既有 ENDED→next() 未被扰动。
> - `C.9a/C.9b`：新增/修改文件跨包类 import 完整性（RecyclerView / LinearLayoutManager / JSONObject / JSONArray /
>   BottomSheetDialogFragment / ClipData / ClipboardManager / Intent / Context / Toast / 各类 Widget / Bundle /
>   AppCompatActivity / AlertDialog ＋ 项目类）；全仓库 `import android.widget.RecyclerView` 残留扫描。

与第 1 轮对比：`81 PASS / 4 FAIL` → **`87 PASS / 0 FAIL`**（原 4 条 FAIL **全部转 PASS**，新增 6 条断言亦全 PASS）。

---

## 2. 第 1 轮 4 条 FAIL 的复验结论（独立核对，不采信转述）

| 缺陷 | 修复动作 | 独立核实结论 | 证据 |
|---|---|---|---|
| **B-1** `PlayerActivity.kt` 缺 import | 补 `PlaylistModels` / `PlaylistStore` / `SongKeys` | ✅ **已修复**。`:25 PlaylistModels`、`:26 PlaylistStore`、`:29 SongKeys` 齐备，与使用点（251/252/261）匹配 | 人工读文件 + 脚本 `C.9a` PASS |
| **B-2** `MyPlaylistsActivity.kt:6` 错误 import | 改为 `androidx.recyclerview.widget.RecyclerView` | ✅ **已修复**。现为 `:9 androidx.recyclerview.widget.RecyclerView` | 人工读文件 + `C.9b` 全仓库残留=0 |
| **B-3** 恢复入口未挂接 | `onPlayerError` 末尾加 `mainHandler.post { attemptRecoveryForCurrentSong() }` | ✅ **已修复，且落点正确**。调用在 **`onPlayerError`（135–142）体内第 141 行**；**不在** `onPlaybackStateChanged`（117–127）体内；`attemptRecoveryForCurrentSong()` 调用点=1（非死代码） | 脚本 `C.3e`/`C.3f`/`C.3g` PASS |
| **FIX-4** CHANGELOG 措辞 | FIX-1 落地后与代码一致 | ✅ **核实通过**。CHANGELOG 宣称"`onPlayerError` 末尾挂接恢复入口"现与代码一致（不再与实现背离），且 `git diff PlayerRepository.kt` 现有对应 hunk | 人工核对 diff |

> 特别复核：上一轮根因是工程师曾把 `onPlaybackStateChanged` 里的 post 误认为挂接。本轮脚本 `C.3f` 已把
> "恢复调用不得出现在 `onPlaybackStateChanged` 体内"固化为断言并 PASS，确认**未**再犯同一错误。

---

## 3. 修复是否引入回归 —— 行为推演（第 3 节三项行为差异）

### 3.1 恢复风暴 → ✅ **可接受（已被 guard 挡住，不会循环）**

推演（`recoveryAttemptedKey` 守卫）：

1. `playAt()` 开头复位 `recoveryAttemptedKey = null`（本次"播放尝试"允许重解析一次）。
2. 直链失效 → `onPlayerError` → post → `attemptRecoveryForCurrentSong()`：`key = SongKeys.of(song)`，
   `recoveryAttemptedKey != key` → 置 `recoveryAttemptedKey = key` → 强制重解析一次。
3. 分支 A：解析得到有效 `http` 地址 → `setMediaAndPlay(url)`。若该地址**仍失效** → ExoPlayer 再次 `onPlayerError`
   → 再次进入恢复 → `recoveryAttemptedKey == key` → **立即 return**（不再解析）。⇒ 每个播放尝试**最多 1 次**重解析。
4. 分支 B：解析失败（null / 非 http）→ 只置 `_error`，**不** `setMediaAndPlay` → 不会再次触发 `onPlayerError`。
5. `setMediaAndPlay` 由 `runCatching` 包裹，同步异常只置 `_error`、不触发 `onPlayerError`。

**结论**：不存在"错误→恢复→再失败→再恢复"的无限循环；仅用户主动重新点播（`playAt` 复位 guard）才允许下一轮。
符合架构 R4 的设计意图。

### 3.2 非死链错误也触发一次强制重解析 → ⚠️ **建议改进**

- 现状：无网络 / DNS 失败 / DRM 失败 / 格式不支持 / 404 / 解码失败等**同样**会进 `onPlayerError`，
  现在都会触发 `resolvePlayableUrl(song, force = true)`。该方法绕过缓存直链，主源 `withTimeoutOrNull(15s)` +
  跨源回退预算 `FALLBACK_BUDGET_MS = 24s`，最坏约 **~39s** 处于 `_loading=true`（UI 显示"解析中"），随后失败提示。
- 与旧版差异：旧版仅立即 `_error` 提示，不重解析。对"永久性不可恢复"的错误，这次重解析**不可能成功**，属无谓耗时/耗电。
- 与 PRD 关系：PRD 5.7（无网络）只要求"不崩溃 + 明确提示"，额外重解析**不违反** AC，但体验不佳。
- **判定：建议改进**。建议在 `onPlayerError` 内对明显不可恢复的错误（按 `errorCodeName` 的网络/DRM/格式类，
  或 `ConnectivityManager` 判定离线）跳过恢复，直接提示。

### 3.3 `_error` 瞬时抖动 → ⚠️ **建议改进**

- 现状：`onPlayerError` 先写 `_error.value = "播放失败: …"`（`:138`），下一轮主线程消息里 recovery 才将 `_error` 置空、`_loading=true`（`attemptRecoveryForCurrentSong` `:288-289`）。
- `PlayerActivity` 对 `repo.error` 的采集是"非空即弹 Toast"（`LENGTH_LONG`）。⇒ 顺序是：**先弹「播放失败」Toast**，
  随后才"重解析中 → 续播"。
- **后果**：即便恢复成功、音乐无缝续播，用户仍会先看到一次「播放失败」Toast，与 PRD 目标 G3「让直链失效对用户**无感**」相悖。
- **判定：建议改进**（**不阻断** AC-29/30/31，因为"自动重解析并继续播放"仍成立）。建议：恢复路径改走 `_loading`
  通道、或在 `onPlayerError` 里"将尝试恢复时不写 `_error`"。

### 3.4 既有语义未被破坏 → ✅ 确认

- `onPlayerError` **仍不调用 `next()`**（脚本 `C.4` PASS）；恢复函数体内亦**不调用 `next()`**（`C.4b` PASS）。
- `onPlaybackStateChanged` 的 `ENDED → next()` 逻辑**未被改动**（`C.3h` PASS）。
- 直链短路（`playAt` 命中 `hasPlayUrl` 即 `setMediaAndPlay(playUrl)` + `return`）**仍在**（`C.2` PASS）。

---

## 4. 顺带复核：import 完整性（主动找同类问题）

- 脚本 `C.9a` 覆盖**全部新增 + 修改** `.kt`，逐类校验跨包 import：
  `RecyclerView`、`LinearLayoutManager`、`JSONObject`、`JSONArray`、`BottomSheetDialogFragment`、`ClipData`、
  `ClipboardManager`、`Intent`、`Context`、`Toast`、`ImageButton`、`ImageView`、`TextView`、`EditText`、
  `CheckBox`、`SeekBar`、`Button`、`LayoutInflater`、`ViewGroup`、`Bundle`、`AppCompatActivity`、`AlertDialog`
  ＋ 项目类（`PlaylistStore`/`PlaylistModels`/`SongKeys`/`Song`/`LocalPlaylist`/`PlaylistEntry`/`PlaylistNameDialog`/
  `MiniImageLoader`/`MiniPlayerController`/`AddToPlaylistSheet`）。**结果：全部 PASS，未发现新的 import 类缺陷。**
- 脚本 `C.9b` 全仓库扫描 `import android.widget.RecyclerView`：**残留 0**。
- **结论：未发现第 1 轮之外的同类新增缺陷。**

---

## 5. AC 覆盖矩阵（AC-1 ~ AC-34，第 2 轮最终）

> 图例：`静态可证 PASS`＝源码逻辑已正确落地；`静态可证 FAIL`＝源码逻辑缺失/错误；`无法静态验证`＝必须编译或真机。

| AC | 结论 | 依据 / 备注 |
|---|---|---|
| AC-1 | 静态可证 PASS | `ensureDefault()` 将默认歌单置于索引 0（仿真 D.2b）；`MyPlaylistsActivity.reload()` 再次置顶 `builtin` |
| AC-2 | 静态可证 PASS | `MyPlaylistAdapter` 对 `builtin` 行 `GONE`；`deletePlaylist`/`renamePlaylist` 防御性拒绝（C.6a/C.6b） |
| AC-3 | 静态可证 PASS | `refreshFavorite()`：未收藏 → `ic_favorite_border` |
| AC-4 | 静态可证 PASS | `toggleFavorite()` → `toggleIn(default)` → `ic_favorite` + Toast |
| AC-5 | 静态可证 PASS | 再次 `toggleFavorite()` → 移除 → 恢复空心（仿真 D.3b） |
| AC-6 | 静态可证 PASS | `repo.currentSong.onEach { refreshFavorite(song) }` 随切歌刷新 |
| AC-7 | 静态可证 PASS | `songCount(id) = entries.size`，与列表条目数同源 |
| AC-8 | 静态可证 PASS | `createPlaylist` 成功落盘（D.5d），列表 `onResume` 呈现 |
| AC-9 | 静态可证 PASS | 空名/纯空格 → "歌单名称不能为空"（D.5a/D.5b） |
| AC-10 | 静态可证 PASS | 重名（忽略大小写/首尾空格）→ "已有同名歌单"（D.5e/D.5f） |
| AC-11 | 静态可证 PASS | 保留名「我喜欢的音乐」被拒（D.5g/D.5h） |
| AC-12 | 静态可证 PASS | `renamePlaylist` + 列表/详情 `onResume` 重读刷新标题 |
| AC-13 | 静态可证 PASS | `deletePlaylist` + `gcLibrary()`（D.7c/D.7d） |
| AC-14 | 静态可证 PASS | `confirmDelete()` 使用 `AlertDialog` 二次确认 |
| AC-15 | 静态可证 PASS | `SearchFragment`/`HomeFragment` 长按 → `AddToPlaylistSheet.show(...)` |
| AC-16 | 静态可证 PASS | 选择器列出全部歌单 + `playlistIdsContaining(key)` 初始化勾选态 |
| AC-17 | 静态可证 PASS | `setMembership` 一次写入多张（D.4a/D.4d） |
| AC-18 | 静态可证 PASS | 取消勾选 → 移除（D.4c） |
| AC-19 | 静态可证 PASS | `showNew` 回调返回新 id → 自动勾选（保留既有勾选） |
| AC-20 | 静态可证 PASS | `btn_cancel`/点外部 → 仅 `dismiss()`，不写入 |
| AC-21 | 静态可证 PASS | `toggleIn`/`setMembership`/`addToPlaylist` 以 `songKey` 去重（D.3c/D.3d） |
| AC-22 | 静态可证 PASS | `item_playlist_song.xml` 含 cover/title/duration/artist/source/play_url，适配器全绑定 |
| AC-23 | 静态可证 PASS | `BuiltinSources.BY_ID[s.source]?.label`（`migu`→「咪咕音乐」）→ `DIRECT_LABEL` → source |
| AC-24 | 静态可证 PASS | `play_url`：`maxLines=1` + `ellipsize=middle`（C.7a） |
| AC-25 | 静态可证 PASS | `copyUrl()` 用**完整** `song.playUrl` 塞剪贴板（C.7e） |
| AC-26 | 静态可证 PASS | 空/非 http → 「未解析 · 播放时自动解析」；`playUrl.text` 仅两种赋值，绝不由 null 拼串（C.7b/c/d） |
| AC-27 | 静态可证 PASS | `playAt` 命中 `hasPlayUrl` → `setMediaAndPlay(playUrl)` + `return`，零解析（C.2） |
| AC-28 | 静态可证 PASS | `playAt` 非缓存分支 → `resolvePlayableUrl(song)` 解析后播放 |
| **AC-29** | **静态可证 PASS**（第 1 轮 FAIL，已修复） | `onPlayerError` 体内 `mainHandler.post { attemptRecoveryForCurrentSong() }`（C.3e/C.3f/C.3g）；`force=true` 重解析 → 成功 `setMediaAndPlay(url)` 续播 |
| **AC-30** | **静态可证 PASS**（第 1 轮 FAIL，已修复） | 恢复成功分支调用 `onUrlRefreshed?.invoke(song, url)`（C.3d）→ `SoundtrackApp` 挂接 `PlaylistStore.updatePlayUrl`（C.5）→ 全局曲库回填、各歌单同时生效（D.10b/c/d） |
| **AC-31** | **静态可证 PASS**（第 1 轮 FAIL，已修复） | 恢复失败分支存在明确提示（`:306-308`）；`onPlayerError`/恢复函数**均不调用 `next()`**（C.4/C.4b） |
| AC-32 | 无法静态验证（需真机） | 持久化机制静态可证（每次变更 `save()`；round-trip 等价 D.8a~c），但"杀进程重启后完整保留"必须真机确认 |
| AC-33 | 静态可证 PASS | `load()` 全量异常捕获；非法 JSON / 非对象根 / 版本过高 / 缺字段 / 类型错 → 静默降级仅默认歌单（D.9 五项） |
| **AC-34** | **静态可证 PASS**（第 1 轮 FAIL，已修复） | 两处编译错误（B-1/B-2）已清；`PlaylistDetailActivity` 等既有文件零改动（B.1/B.2/B.3）；适配器新增参数末尾带默认值（B.4a/b）；零 ViewBinding（C.1/C.1b）；全仓库无残留错误 import（C.9b） |

**矩阵汇总（第 2 轮最终）**：
- 静态可证 PASS：**33** 条（AC-1 ~ AC-31、AC-33、AC-34）
- 静态可证 FAIL：**0** 条
- 无法静态验证（需真机）：**1** 条 —— **AC-32**（杀进程重启后完整保留）

---

## 6. 缺陷清单（第 2 轮最终状态）

| 编号 | 级别 | 描述 | 状态 |
|---|---|---|---|
| B-1 | 阻断 | `PlayerActivity.kt` 缺 `PlaylistStore`/`PlaylistModels`/`SongKeys` import → 编译失败 | ✅ **已修复**（`:25/:26/:29`） |
| B-2 | 阻断 | `MyPlaylistsActivity.kt` 错误 `import android.widget.RecyclerView` → 编译失败 | ✅ **已修复**（`:9` 改 androidx；全仓库残留 0） |
| B-3 | 阻断 | `onPlayerError` 未挂接 `attemptRecoveryForCurrentSong()`（死代码）→ 直链失效自动重解析+回填全废（AC-29/30/31） | ✅ **已修复**（`onPlayerError:141`；调用点=1；落点正确） |
| N-1 | 一般 | `PlaylistSongAdapter.kt` 复制了一份 `DIRECT_LABEL` 映射表（PRD 4.7 称"不新增映射表"） | 🔸 **仍存在**（语义等价，不阻断；建议后续收敛） |
| S-1 | 建议 | `sheet_add_to_playlist.xml:39` `RecyclerView` 的 `maxHeight` 运行时无效（与既有写法一致） | 🔸 **仍存在**（低风险；建议固定高度兜底） |
| **S-2** | 建议 | **新增**：非死链错误（无网络/DRM/格式）也会触发一次最长 ~39s 的强制重解析（§3.2） | 🆕 由 FIX-1 引入的行为差异 |
| **S-3** | 建议 | **新增**：`onPlayerError` 先写 `_error` → 用户会看到「播放失败」Toast 一闪而过，与"无感恢复"目标相悖（§3.3） | 🆕 由 FIX-1 引入的行为差异 |

**新增阻断/严重缺陷：0 条。** S-2 / S-3 为恢复链路启用后产生的**行为差异**，均判为**建议改进**，不阻断验收。

---

## 7. 智能路由判定

> **判定：`NoOne`（全部通过，可交付）**

依据：脚本第 2 轮 `87 PASS / 0 FAIL`（退出码 0）；第 1 轮 3 条阻断缺陷（B-1/B-2/B-3）经独立复验**确已修复且落点正确**；
未发现新增阻断/严重缺陷；AC 覆盖矩阵中无 `静态可证 FAIL`。

**遗留非阻断项**（不阻塞交付，建议后续迭代）：
N-1（重复映射表）、S-1（maxHeight 无效）、S-2（非死链错误触发无谓重解析）、S-3（`_error` 抖动导致"播放失败"提示闪现）。

---

## 8. 遗留风险与未覆盖面（最终）

1. **编译期语义未穷尽**：只能捕获显式可判定的错误（缺失/错误 import、资源引用、XML 合法性）。Kotlin 重载解析、
   类型推断、泛型、lambda SAM、`@Suppress` 有效性等只能在真正编译时验证——**本次未做**。
2. **运行期行为未验证**：红心图标切换、Toast 文案与时机（含 S-3 的抖动观感）、底部弹层交互、迷你条、进度条、
   协程取消竞态、SharedPreferences 真实落盘与并发、跨进程重启保留（AC-32），均**未在真机验证**。
3. **`PlaylistStore` 仿真是"语义级"而非"字节级"**：严格对齐 `PlaylistStore.kt` 的可读语义，但未覆盖 Kotlin `org.json`
   的边界行为与 `SharedPreferences.apply()` 的异步时序。
4. **AC-32** 仅证明持久化机制正确，未证明真实进程重启后的端到端保留。
5. **`songsOf` 同毫秒加入**：`addedAt` 相同则稳定排序保持插入序，属可接受行为，未真机确认视觉顺序。
6. **S-2/S-3 的观感**属真机体验问题，需真机回归确认严重程度。

---

## 9. 结论

第 2 轮回归**全部通过**：第 1 轮 3 条阻断缺陷（两处编译错误 + 恢复链路断裂）均经独立核对确认修复，且
`onPlayerError` 落点正确（不再误挂接到 `onPlaybackStateChanged`）；`CHANGELOG` 措辞与实现重新自洽；
未引入新的阻断/严重缺陷。AC 覆盖矩阵：**静态可证 PASS 33 / FAIL 0 / 无法静态验证 1（AC-32）**。

**最终路由：`NoOne`（可交付）**，并附 4 项非阻断改进建议（N-1、S-1、S-2、S-3）供后续迭代。
