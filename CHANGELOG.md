# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
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
