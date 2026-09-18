# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
- 导入显示与后台化（2026-09-18）：解决两个真机问题——进度数字「从 33 跳到 41」式的跳变，以及页面被回收后导入中断（657 首歌单卡在 244）
  - **跳变根因**：旧 `onProgress(done, total, title)` 把 matched + skipped + missed 混进一个 done 数。跳过的歌（歌单内已存在，不做任何网络请求）几乎瞬间完成，遇到已导入歌单时进度数字成片跃进。现在回调拆成 `(matched, skipped, missed, total, title)` 三计数，UI 分别显示「已匹配 X · 跳过 Y · 未匹配 Z」，进度条 `setProgress(done, true)` 平滑动画承接
  - **中断根因**：导入协程原挂在 `MyPlaylistsActivity.lifecycleScope`，锁屏 / 切后台 / 系统回收页面会取消协程，已匹配条目停在中间数、misses 也不写。新增前台服务 `import_/PlaylistImportService.kt`（`foregroundServiceType="dataSync"`）：协程挂在服务级 `SupervisorJob() + Dispatchers.IO` scope，与 Activity 生命周期完全解耦；`onCreate` 同步 `startForeground`（5s ANR 门槛，同 PlayerService 的坑）；`START_NOT_STICKY` 进程被杀不重启；重复 `ACTION_START` 由 `AtomicBoolean.compareAndSet` 幂等拦截
  - **服务 ↔ UI 通道**：新增进程内单例 `import_/ImportEngine.kt`（`MutableStateFlow<State?>`）。服务写状态，页面用 `repeatOnLifecycle(STARTED)` 收集，双方互不持有引用。State 拆成 matched/skipped/missed/total/title/playlistName/result/cancelled/failed，`done = matched+skipped+missed` 只给进度条用
  - **最小化 / 恢复**：进度对话框新增「最小化」按钮（只关弹窗，服务继续跑）；「导入」按钮双重行为——`ImportEngine.state.value != null`（导入中或有未消费结果）→ 直接恢复进度弹窗，否则弹输入框发起新导入；`MyPlaylistsActivity` 改 `singleTop`，通知点按 `onNewIntent` 复用实例
  - **通知授权**：真机实测 `POST_NOTIFICATIONS` 被拒（`granted=false` 且 `USER_SET|USER_FIXED`，`dumpsys notification` 里 `numEnqueuedByApp=186 / numBlocked=186`），后台导入进度完全不可见。`startImport` 现在在 Android 13+ 先走 `ActivityResultContracts.RequestPermission` 请求（被拒仍继续导入，只是 toast 提示通知栏不显示进度，页面内弹窗与「导入」按钮恢复不受影响）
  - **通知内容**：标题带歌单名（`onPlaylistCreated(id, name)` 回调传名，修复此前从引擎空读回写导致歌单名恒为空的 bug），明细行「已匹配 X · 跳过 Y · 未匹配 Z / N · 《歌名》」+ 确定性进度条（解析阶段不确定），常驻 ongoing + 「取消」操作（`ACTION_CANCEL`）；完成态通知非常驻可划掉
  - 真机验证（2026-09-18，小米 24129PN74C）：`yinfu-music-1.0.0-20260918.0316.apk`。旧 Activity 版导入卡死在 244（28 次轮询无变化）；服务版同一歌单 **244 → 247 → 274 持续爬升，且全程锁屏（页面已销毁）仍在推进**，`dumpsys activity services` 确认 `isForeground=true foregroundId=2002 types=0x00000001`；通知授权后 `android.text` 正确渲染「已匹配 36 · 跳过 58 · 未匹配 2 / 657 · 《海口》」、标题「正在导入歌单· 抓个胖子做晚餐喜欢的音乐」
  - 约束遵守：不新增任何第三方依赖；新增文件 `ImportEngine.kt` / `PlaylistImportService.kt` / `dialog_import_progress.xml`；改 `AndroidManifest.xml`（FOREGROUND_SERVICE_DATA_SYNC 权限 + 服务注册 + singleTop）、`PlaylistImporter.kt`（回调签名）、`MyPlaylistsActivity.kt`、`gradle.properties`（修掉指向别的机器的过期 `org.gradle.java.home`）

### Changed
- 网易云歌单导入提速（2026-09-17，多线程）：匹配阶段从「4 歌并发 × 共享 8 源信号量」升级为「8 歌并发 × 每歌独立 10 源信号量」，修复 8 首歌争抢同一个全局信号量导致实际并发塌缩到 10 路的问题；新增**早退机制**——一旦出现 ≥135 分（标题精确 100 + 歌手全中 20 + 时长 ≤3s 15）的命中，仍在排队的音源直接跳过，绝大多数歌曲 1~2 个源即可定案；候选源按命中率排序（网易云本源排最前，歌单来源平台命中最高）；源实例只构建一次全局复用；单源超时 8s→5s、单歌总超时 30s→20s

### Added
- 播放器 UI 优化（2026-09-17）：播放模式切换 + 定时关闭 + 全屏可拖动歌词
  - 播放模式：`PlayerRepository` 新增 `enum PlayMode { LIST（列表播放）/ SINGLE（单曲循环）/ SHUFFLE（随机播放）}`，`cyclePlayMode()` 按 LIST→SINGLE→SHUFFLE→LIST 循环并持久化（`play_mode`）；`next()` 重写 —— SINGLE 为 `seekTo(0)+replay`（并从本轮已播集合移除当前曲，避免随机池污染）、SHUFFLE 从「未失败 + 未在本轮播过」候选中随机，全部播完才清池重洗；StateFlow 驱动按钮图标（`ic_repeat`/`ic_repeat_one`/`ic_shuffle`）与高亮色切换；`restoreFromPrefs` 恢复模式偏好
  - 定时关闭：`setSleepTimer(minutes)`（15/30/45/60 可选，持久化 `sleep_end_ms`）；位置轮询线程检测到点 → 自动 `cancelSleepTimer()` + `player.pause()` + 错误流提示「定时关闭已生效，已暂停播放」；未到点的剩余时长重启后仍生效（过期则丢弃）；按钮图标 `ic_timer`，已定时时高亮 + 对话框提供「取消定时」
  - 全屏歌词：普通播放页点击歌词区切换到全屏歌词层（`fullscreen_lyrics`，背景虚化专辑封面 + 200ms alpha 渐显），顶栏返回/标题/歌手、居中歌词列表、底部精简控制行（模式/上一首/播放/下一首/定时），后退键先退全屏；**两套歌词列表各自持有独立 `LyricAdapter` 实例**（共用同一份 `lyricLines` 数据），修复同一个 adapter 绑两个 RecyclerView 导致小屏歌词不再刷新的 bug；全屏歌词可随意拖动 —— 拖动中关自动滚动，停下 3 秒后恢复跟随
- 网易云歌单导入系统（2026-09-17）：按歌单链接导入，曲名+歌手+时长全音源最优匹配，未匹配进「导入失败歌曲」内置歌单
  - 入口：「我的」→ 我的歌单页顶部「导入网易云歌单」按钮 → 粘贴链接或纯 id（正则提取 `id=\d+`）→ 不可取消的进度对话框（`x/y · 正在匹配《title》`，「取消」直接 cancel 协程）
  - 解析：`NeteaseMusicSource.fetchPlaylist` **POST `/api/v6/playlist/detail` 优先**（根 key `playlist`，tracks 含完整 ar/al/dt），GET `/api/playlist/detail` 兜底（根 key `result`，未登录时 tracks 精简）；按导入歌单名在本地新建同名歌单
  - 匹配引擎 `import_/PlaylistImporter.kt`：两级 `Semaphore`（4 首/曲并发、8 源/曲）、单源 8s + 单曲 30s 超时；评分 = 曲名（精确 100 / 包含 60 / 否则淘汰）+ 歌手（全中 +20 / 部分 +10 / 缺失 -30）+ 时长（≤3s +15 / ≤8s +8 / ≤20s 0 / >20s 或 0 为 -10）+ 音质（无损 +3 / 高品 +2 / 标准 +1），**阈值 ≥80** 视为命中；曲名归一化（去括号后缀）与多歌手拆分提升召回
  - 失败处理：未匹配曲目（标题/歌手/时长/来源歌单）写入新建歌单的 `importMisses`，并汇总到内置「导入失败歌曲」歌单；该歌单详情页用 `ImportMissAdapter` 展示失败列表，**点击歌名直接跳搜索页并以曲名发起搜索**（顺带写入搜索历史），长按可移除单条
  - 联动：`MainActivity` 接收 `EXTRA_SEARCH_KEYWORD`（`CLEAR_TOP|SINGLE_TOP`）自动切到搜索 tab 并回填关键词；`SearchFragment` 新增 `newInstance(keyword)` / `searchKeyword(kw)`
  - 约束遵守：不新增任何第三方依赖；新增文件 `ImportMissAdapter.kt` / `PlaylistImporter.kt` / `item_import_miss.xml` / `ic_import.xml` / `ic_repeat.xml` / `ic_repeat_one.xml` / `ic_shuffle.xml` / `ic_timer.xml`
  - 提速（见上 Changed 节）：多线程匹配 + 早退。**加速比为理论推算尚未真机实测**——旧版单歌上限 30s × ⌈10/4⌉ ≈ 90s，新版早退后多数歌只需 1~2 个源往返，待真机验证后补实测数据
- 搜索历史（2026-09-16）：搜索页空态展示最近搜过的关键词（≤ 20 条，最近在前），点击词条直接回填并搜索，每条右侧 ✕ 删单条，顶部「清空」一键全删（带二次确认）
  - 数据层 `data/PrefsStore.kt`：修复既有**无序 bug** —— 旧实现 `putStringSet/getStringSet` 存取，`getStringSet` 返回 `HashSet` 无序，「最近在前」根本不成立；改为 `\n` 分隔的单一字符串保序存取。新增 `removeHistory(keyword)` 删单条；`addHistory` 去重置顶 + 空词过滤 + 内部换行替换为空格（剪贴板粘贴含换行文本会让 `\n` 分隔符切分错乱）
  - UI：`fragment_search.xml` 在源选择器与结果列表之间插入 `history_block`（标题「搜索历史」+「清空」按钮 + `recycler_history`，默认 GONE）；新建 `item_search_history.xml`（整行水波纹可点 + 右侧 `ic_close` 24dp）；新建 `adapter/SearchHistoryAdapter.kt`（`ListAdapter` + `DiffUtil` 局部刷新，零新依赖）
  - 显隐时机：首屏空态 / onResume 空态显示；`performSearch` 开始即隐藏；**搜索无结果（成功/失败分支）且无任何结果上屏时重新显示**（有结果时全程保持隐藏，不遮挡结果列表）；历史为空时整个区块隐藏
  - 交互：点词条 → 填入输入框 + 光标移末尾 + 发起搜索（`addHistory` 由 `performSearch` 统一调用，不重复）；✕ 独立 `OnClickListener` 不冒泡到整行；「清空」弹 `AlertDialog` 二次确认；首屏有历史时隐藏默认空态文案避免同屏冗余
  - 生命周期：所有 `requireContext()` 前加 `isAdded` 守护；`CancellationException` 正确 rethrow
  - 约束遵守：不新增任何第三方依赖；全程 `findViewById`；未改动 `build.gradle.kts` / `settings.gradle.kts` / `gradle.properties` / `AndroidManifest.xml` / `ref/` / `ref_all/` / `web/` / `docs/` / `qa/`
  - 真机验证（2026-09-17，小米 24129PN74C）：`yinfu-music-1.0.0-20260916.2339.apk` 全新安装，9 场景全 PASS —— 空历史隐藏 / 历史渲染序与 prefs 一致 / 点词条回填+搜索+隐藏 / 点非首位词去重置顶 / ✕ 删单条（UI+prefs 同步）/ 清空二次确认 / 取消保留 / 确定清空（键移除）/ 全程无崩溃。详见 `qa/search_history_report.md` 真机实测节
- 本地歌单系统（2026-09-15）：默认歌单「我喜欢的音乐」+ 多张自建歌单 + 播放页红心收藏 + 条目展示解析后播放地址 + 缓存直链优先秒播 + 直链失效自动重解析回填
  - 数据层：新增 `data/PlaylistStore.kt`（单例，`SharedPreferences("local_playlists")` + `org.json` 单 key 存整份 JSON，schema `version=1`）、`data/PlaylistModels.kt`、`model/SongKeys.kt`。采用**全局曲库 `songKey → Song 快照` + 歌单持有序 `songKey` 列表**布局：同一首歌在多张歌单只存一份元数据，直链回填「一次写入、全部歌单同时生效」；删除歌单后 `gcLibrary()` 回收不再被引用的条目
  - 健壮性：损坏 JSON / 未知 schema 版本 → 静默降级为「仅默认歌单 + 空曲库」并自愈落盘，绝不崩溃；首次安装自动创建默认歌单（5.9 / 5.10 / AC-33）
  - 默认歌单：内置、恒置顶、**不可删不可改名**（列表项隐藏入口 + `PlaylistStore.deletePlaylist/renamePlaylist` 防御性拒绝，双保险）
  - 播放页（S1）：`btn_favorite` 补逻辑 —— 未收藏空心（新增 `ic_favorite_border`）/ 已收藏实心（既有 `ic_favorite`），点击在默认歌单增删并 Toast，切歌随 `currentSong` 实时刷新；无播放中歌曲时空心 + 置灰 + 点击不写入（5.11）；**长按红心**打开「加入歌单」选择器（不改 `activity_player.xml`、不移 `btn_favorite`、不加常驻按钮）
  - 新增页面：「我的」页新增歌单入口（`fragment_mine.xml`，`btn_my_playlists`，`onResume` 刷新摘要）；我的歌单列表页 `MyPlaylistsActivity`（默认置顶 + 自建按创建时间倒序 + 新建/重命名/删除二次确认，改名删除用文字按钮）；歌单详情页 `MyPlaylistDetailActivity`（条目展示封面/歌名/歌手/音源中文名/**解析后播放地址**，地址行恒占一行、超长 `ellipsize=middle`、长按复制完整地址，空态区分默认/自建，挂迷你条）；「加入歌单」选择器 `AddToPlaylistSheet`（`BottomSheetDialogFragment`，多选差量一次性写入，行内新建后自动勾选，取消不写入）
  - 播放内核：`PlayerRepository.resolvePlayableUrl` 加 `force` 参数（强制忽略缓存直链）；新增 `attemptRecoveryForCurrentSong()`（经 `mainHandler.post` 切出 ExoPlayer 回调栈、复用 `resolveToken` 校验、`recoveryAttemptedKey` 保证每轮播放最多重解析一次、失败**不自动跳歌**）；`onPlayerError` 末尾挂接恢复入口；新增 `onUrlRefreshed` 由 `SoundtrackApp` 一次性挂接为 `PlaylistStore.updatePlayUrl`
  - 长按接入：搜索页 / 首页歌曲条目长按打开「加入歌单」（`SongAdapter` / `NewSongAdapter` 末尾新增可选 `onLongClick`，既有 3 处调用点零改动，`PlaylistDetailActivity` 完全未触碰）
  - 约束遵守：**不新增任何第三方依赖**；全程 `findViewById`（`viewBinding = false`）；未改动 `build.gradle.kts` / `settings.gradle.kts` / `gradle.properties` / `ref/` / `ref_all/` / `web/`

### Fixed
- 网易云歌单导入「657 首只导入 6 首」（2026-09-18，实测歌单 390231913 复现）——两个叠加 bug：
  - **截断未补全**：未登录请求 v6 `/api/v6/playlist/detail` 时 `tracks` 只返回前几首（实测 657 曲歌单只给 6 首），但 `trackIds` 是完整的 657 条；旧代码 `tracks` 非空就直接返回 6 首，剩余 651 首永久丢失。现按 `trackIds.length() > tracks 解析数` 判定截断，用 trackIds 分块（每块 200）**并行** `song/detail` 补全；v6 富元数据（ar/al/dt/picUrl）优先、补全结果只填空位不覆盖
  - **兜底路径 400**：`song/detail` 实测**必须同时传 `c`（对象数组 `[{"id":"123"}]`）与 `ids`（数字数组 `[123]`）**，旧代码只传 `c` 被服务端以 400「参数错误」拒绝，trackIds 兜底链路其实是哑的。已修正参数格式
  - 语义保留：trackIds 缺失的真空歌单仍返回空列表（导入 0 首），trackIds 存在却全块失败才返回 null 交给 GET 端点兜底
- 首页「推荐歌单 / 新歌速递 / 排行榜详情」歌曲仍播 30s 提前结束（C 方案：网易 `tracks[].fee` 字段 → metadata 级主音源路由；fee∈{1,4} 即 VIP/专辑独占，主音源从 `netease` 改为 `migu`，海糖网退到兜底链；fee∈{0,8} 或缺失仍走 `netease`，100% 向后兼容；`NeteaseMusicSource.isLikelyPreview` 试听探测保留为二道防线；实证：60 首真实推荐歌单曲目中 fee=1 (VIP) 占 19/60，全部命中 migu 接管，fee=0/8 合计 41/60 仍走 netease，A 方案 600 阈值的盲区彻底关闭）
- 部分歌曲（典型如港台/粤语艺人「混账-周柏豪」）点歌后"无法解析"提前结束（fallback 过滤 bug + 跨源回退链路扩展）：
  - **根因**：`PlayerRepository.resolvePlayableUrl` 回退循环用 `&& it.hasPlayUrl` 过滤，但所有 7 个 `MusicSource.search()` 返回的 Song `playUrl` 都是空（URL 后续 `resolvePlayUrl` 才填），导致 fallback 100% 哑炮
  - **修复**：过滤改为 `search → 真正调 src.resolvePlayUrl(matchedSong) 拿 URL` 两步式
  - **C 方案守护扩展**：fallback 命中 netease URL 时复用 `NeteaseMusicSource.isLikelyPreview`（已改 `internal`）判定 30s 试听，`continue` 跳过，避免破坏上一轮已交付的 fee∈{1,4} 不走 netease 语义
  - **跨源补搜链路扩展**：`FALLBACK_SOURCES` 加 `joox`（HK/TW/SEA 兜底）+ `apple`（iTunes 公开 search + amp-api edge 全球目录）；单源超时 `6s→8s`、总预算 `12s→24s`
  - **实证**：QA `qa/fallback_trace.py` 6 个场景全 PASS；iTunes Search API 实测"混账 周柏豪"命中 1 条；Joox Search 实测返回 30 条；C 方案 60 首曲目路由无回归（fee=1→migu 19/19、fee=0/8→netease 全保持）
- 搜索体验优化：
  - `legalizeString` 剥除 HTML 标签与实体（`<em class="hl">` 残留修复，5sing 等源 title 不再含 `<>`）
  - TuneHub qq/kuwo 搜索结果补封面：qq 用 `album.mid` 拼 `https://y.gtimg.cn/music/photo_new/T002R300x300M000{mid}.jpg`，kuwo 优先 `pic` 字段、缺则兜底 `https://img4.kuwo.cn/star/albumcover/300/{rid}.jpg`
  - 搜索结果按"相关性（title相等>title含>artist含） → 音质（无损>高品>标准） → 时长（降序）"排序（相关性放在主键避免"搜『等你下课』却先出『那些年』的无损版"的反用户体验）
  - 搜索结果列表新增时长显示（`mm:ss` 格式，`durationSec=0` 不显示）
  - 保留原有 6s 解析探测 + Toast「已过滤 N 首不可播放」，确保最终结果均为可播放
- 「混帐-周柏豪」等华纳版权歌仍 30 秒戛然而止 + 搜索「晚安」长时间转圈/退出崩溃：
  - **试听守护升级**：`isLikelyPreview` 的 MPEG sync-word 密度判定会被「完整有效的 30s 试听」骗过（haitangw 返回的网易 IoT 通道试听 480KB 全是有效帧，sync=1087 > 阈值 600）。新建 `PreviewGuard`：用 Content-Range 总大小 ÷ 元数据时长估算隐含码率，< 48kbps 判试听；时长未知时 < 600KB 判试听；拿不到大小时退回 sync-word 判定
  - **守护范围扩展**：从「仅 fallback 的 netease 命中」扩展到主源 + fallback 全部音源（实测拦下 haitangw 30s / kuwo 11s / apple 30s 试听）
  - **migu copyrightCache 键 bug**：跨源路由（fee=1 网易歌）的 `resolvePlayUrl` 用网易 id 查咪咕 contentId 缓存 100% miss；修复为按 title+artist 重搜 migu 拿自己的 contentId/copyrightId，且 title 精确匹配拒绝同名翻唱（山岚版《混帐》）
  - **诚实提示**：全链只剩试听时提示「暂无完整免费音源（试听片段已过滤）」，不再播 30 秒戛然而止
  - **搜索渐进上屏**：恢复「探测通过即上屏」体验但保留三维排序 —— 每条探测完成就重排序整体替换，不再等 `searchAll` 全部返回（单源超时 35s→10s + 渐进发布，「晚安」2-3 秒可见首批）
  - **搜索页崩溃修复**：`catch(Exception)` 吞掉 CancellationException 后在 detach 的 Fragment 上调 `requireContext()` 抛 IllegalStateException；改为取消异常 rethrow + `isAdded` 守护

## [2.0.0] - 2026-09-10

### ⚠ BREAKING — UX 重塑
- **品牌中文名重命名**：`声轨 Soundtrack` → `音符 Note`
  - 安卓端 `applicationLabel=音符`、所有文档 / 注释 / `proguard-rules.pro` /
    `build.gradle.kts` 头部注释里残留的中文 `声轨` 全量替换为 `音符`
  - 英文包名 `com.soundtrack.music`、类名 `SoundtrackApp`、
    主题 ID `Theme.Soundtrack`、下载目录 `Music/Soundtrack/`、
    英文品牌副标保持不动（仅中文书写层替换，避免破坏已签名 APK 的运行时绑定）

### Added（安卓端）
- **首页「发现好音乐」banner**：从静态 drawable 改为推荐歌单头条卡片（封面 + 标题 + 副标题 + 角标），点击跳歌单详情；空推荐走清封面 + 标题/角标/副标题 GONE
- **首页推荐歌单 Rail 去重**：banner 占用首张，Rail 改为 `recommend.drop(1)`（空/单元素/多元素三档边界已处理）
- **悬浮迷你播放器**：底部 66dp 透明条（封面 + 标题/歌手 + 上一首/播放暂停/下一首 + 关闭），所有主页 + 详情页可见；点击进 `PlayerActivity`；新文件 `MiniPlayerController.kt` / `layout_mini_player.xml` / `bg_mini_cover.xml` / `ic_close.xml`
- **前台播放服务合规化**：`PlayerService.onCreate` 同步 `startForeground(NOTIFICATION_ID=1001)`，新增 `PlaybackNotification`（`CHANNEL_ID="yinfu_playback"`，3 个按钮：上一首/播放暂停/下一首，PendingIntent 由 `PendingIntentFactory` 统一创建），彻底规避 `ForegroundServiceDidNotStartInTimeException`
- **APK 版本号可区分**：`versionName=1.0.0-yyyyMMdd.HHmm`、`versionCode=yyyyMMddHH`（构建时刻 `Asia/Shanghai`）；`assembleDebug` doLast 重命名 APK 为 `yinfu-music-{versionName}.apk`
- **搜索剔除失效音源**：`SearchFragment` 对每条候选 6 s 并发解析探测；失败静默丢弃；`AtomicInteger` 计数；搜索完成后 `withTimeoutOrNull(7_000L) { probeJobs.join() }` 兜底；>0 弹 Toast「已过滤 N 首不可播放」
- **歌词跨源回退拉取**：`PlayerActivity` 收到 `currentSong` 时若 `song.lrc.isBlank()`，主源 `withTimeoutOrNull(8_000L)` + 跨源 netease/migu 各 `withTimeoutOrNull(4_000L)` 搜同名歌；`ConcurrentHashMap<String, Job>` 守 songId 防快速切歌重复拉；最终 `runCatching` 静默吞

### Changed
- `FALLBACK_SOURCES` 顺序：`netease` 从首位挪到末位 → 主源解析优先 migu / kuwo / qq / kugou / myfreemp3
- `NeteaseMusicSource.resolveUrl`：每命中一个 quality 后做"试听探测"（256KB Range GET + MPEG sync word 密度，< 600 真音频帧视为预览）→ `continue` 试下一 quality；三个都失败 → `return null`
- `android/build.bat` 输出文件名同步到 `yinfu-music-{versionName}.apk`

### Fixed
- 首页「发现好音乐」模块无数据（被静态 drawable 替代）
- 首页「推荐歌单 / 排行榜」无数据（网易云 `/api/toplist/detail` 根字段为 `list` 而非 `listInfo`）
- 首页「新歌速递」无封面（字段 `ar`/`al`/`dt` → `artists`/`album`/`duration`）
- 首页歌名显示 `img1v1id`（`HomeModels.trackToSong` 字段对齐）
- 播放中切歌无反应（旧解析任务覆盖新歌；`PlayerRepository.resolveToken` + cancel 旧 job）
- 播放过程闪退（`LyricAdapter.setActive` 下标保护 + `PlayerService.onDestroy` 不再 `player.release()` + `MiniImageLoader` 不再 `runBlocking`）
- 单曲队列播完闪退（`STATE_ENDED` 改 Handler.post + 二次守卫 + 跨源回退 `song.playUrl` 写回；兜底避免解析失败无限循环）
- 播放后无法回进播放页（`PlayerActivity` `singleTop` + 全局迷你控制器常驻）
- 28 秒前台服务自动退出（`ForegroundServiceDidNotStartInTimeException`，根因 = `MediaSessionService` 缺 `startForeground`）
- 首页全部歌曲播放 9~28 秒提前 `STATE_ENDED`（A+B 方案：试听探测 + 跨源回退）

## [1.2.0] - 2026-09

### Added
- 安卓首页数据采集：推荐歌单点击后走「网易云 → QQ → 酷我 → 咪咕」回退链采集
- 安卓音源选择器 + 全 57 音源接入 + 异步并发采集；新增 `SourceManageActivity`
- 双端标明音源与品质（结果行带源名 + 品质徽标）

## [1.1.0] - 2026-09

### Added
- 品牌重命名立项（v1.1.0 决定把项目中文名从「声轨」改为「音符」）
- 网页端 logo 改为粉橙青渐变音符 SVG；安卓启动图标改为居中音符
- 全 57 音源接入，按 core / cn / radio / overseas 分组
- 搜索异步流式化，谁先完成谁先上屏（首批 10 s 内）

## [1.0.0] - 2026-09 (init)

### Added
- 双端（网页端 + 安卓端）音乐应用基线，基于 [CharlesPikachu/musicdl](https://github.com/CharlesPikachu/musicdl) + Flask
- 网页端：Flask SSE 流式搜索 + 音频代理 + 歌词 + 下载 + 品质字段
- 安卓端：Kotlin 原生，4 大主力源（咪咕 / 网易云 / 酷我 / QQ）
- 4 个直接源 + 53 个 web 源（统一调度 + 异步并发）
- 基础播放（ExoPlayer 1.0.2 / Media3 + 前台服务 + 通知栏控制）
- 适配 `Android 7.0+`（`minSdk=24 / targetSdk=33 / compileSdk=33`）

[Unreleased]: https://github.com/jaychoucc/yinfu-music/compare/v2.0.0...HEAD
[2.0.0]: https://github.com/jaychoucc/yinfu-music/compare/v1.2.0...v2.0.0
[1.2.0]: https://github.com/jaychoucc/yinfu-music/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/jaychoucc/yinfu-music/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/jaychoucc/yinfu-music/releases/tag/v1.0.0
