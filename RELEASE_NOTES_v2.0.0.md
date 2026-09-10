# v2.0.0 Release Notes

> Copy-paste-ready Markdown. Paste this into GitHub: Repository → Releases → "Draft a new release" → "v2.0.0" → paste into "Describe this release".

## Highlights

**第一版完全可用的安卓端** —— 此前 `v1.x` 只有 4 个直连源 + 通知栏控制，本版把"放一首歌播完整首"这一条 user journey 走通：首页数据、播放队列、迷你条控制、前台服务合规、歌词拉取、试听片段识别全部修好。同时把项目的中文品牌名由「声轨 Soundtrack」正式落定为「音符 Note」（英文包名 / 类名 / 资源名保持不动，避免破坏已签名 APK）。

---

## ⚠ Breaking Change

### 🔤 品牌中文名重命名：`声轨 Soundtrack` → `音符 Note`

仅修改中文书写层（产品对外可见的字面）：

| 类别 | 调整 | 保留不动 |
|---|---|---|
| 安卓端桌面 label | `app_name=音符` | `applicationId=com.soundtrack.music`（已签名 APK runtime 绑定） |
| 主题资源 ID | — | `Theme.Soundtrack` |
| Application 类 | — | `com.soundtrack.music.SoundtrackApp` |
| 下载目录 | — | `Music/Soundtrack/`（MediaStore 相对路径，避免已下载歌曲断链） |
| 副标英文品牌 | — | 「Note · powered by musicdl」前的 `Note` 替代 `Soundtrack` 作为副标 |

完整变更清单见仓库根目录的 [`CHANGELOG.md`](../../CHANGELOG.md)。

---

## ✨ 安卓端 Added（11 项新增）

1. **首页「发现好音乐」动态 banner**：从静态 drawable 改为推荐歌单头条卡片（封面 + 标题 + 副标题 + 角标），点击跳歌单详情；空推荐走清封面 + GONE 兜底
2. **首页推荐歌单 Rail 去重**：`recommend.drop(1)` 防止与 banner 重复显示（空/单元素/多元素三档边界已处理）
3. **悬浮迷你播放器**：所有主页 + 详情页可见的底部 66dp 条，封面 + 标题/歌手 + 上一首/播放暂停/下一首 + 关闭
4. **前台播放服务合规化**：`PlayerService.onCreate` 同步 `startForeground(NOTIFICATION_ID=1001)` + 自维护 `PlaybackNotification`（3 按钮 + PendingIntent 由 `PendingIntentFactory` 统一创建）
5. **APK 版本号可区分**：`versionName=1.0.0-yyyyMMdd.HHmm`、`versionCode=yyyyMMddHH`，构建时刻 `Asia/Shanghai`
6. **搜索剔除失效音源**：搜索阶段 6 s 并发解析探测 + `AtomicInteger` 计数 + 7 s `join` 兜底 + 失败时弹「已过滤 N 首不可播放」
7. **歌词跨源回退**：主源 8 s + 跨源 netease/migu 各 4 s 搜同名歌；`ConcurrentHashMap<String, Job>` 守 songId
8. **新版 `build.gradle.kts`**：版本号生成 + APK 重命名 doLast
9. **新增文件 `MiniPlayerController.kt`**：迷你条控制器，跨 Activity 共享状态
10. **新增文件 `PlaybackNotification.kt` + `PendingIntentFactory.kt`**：前台服务通知与 PendingIntent 工厂
11. **新增 `bg_banner_badge.xml` / `bg_mini_cover.xml` / `ic_close.xml` / `layout_mini_player.xml`**：配套 drawable + layout

## 🔧 Changed

- `FALLBACK_SOURCES` 顺序：`netease` 挪到末位 → 跨源回退优先 migu / kuwo / qq / kugou / myfreemp3
- `NeteaseMusicSource.resolveUrl`：每命中 quality 后做"试听探测"（256KB Range GET + MPEG sync word 密度，< 600 真音频帧视为预览），失败 `continue` 试下一 quality
- `android/build.bat` 输出同步到 `yinfu-music-{versionName}.apk`

## 🐛 Fixed（10 项根因修复）

- 首页「发现好音乐」无数据（被静态 drawable 替代）
- 首页「推荐歌单」/「排行榜」无数据（网易云 `toplist/detail` 根字段为 `list`）
- 首页「新歌速递」无封面（字段 `ar`/`al`/`dt` → `artists`/`album`/`duration`）
- 首页歌名显示 `img1v1id`（`HomeModels.trackToSong` 字段对齐）
- 播放中切歌无反应（`PlayerRepository.resolveToken` + cancel 旧 job）
- 播放过程闪退（`LyricAdapter.setActive` 下标保护 + `PlayerService.onDestroy` 不再 `player.release()` + `MiniImageLoader` 不再 `runBlocking`）
- 单曲队列播完闪退（`STATE_ENDED` 改 Handler.post + 二次守卫 + 跨源回退 `song.playUrl` 写回）
- 播放后无法回进播放页（`PlayerActivity` `singleTop` + 全局迷你控制器）
- 28 秒前台服务自动退出（`ForegroundServiceDidNotStartInTimeException`）
- 首页全部歌曲 9~28 秒提前 `STATE_ENDED`（A+B 方案：试听探测 + 跨源回退）

---

## 🚀 Upgrade 指南

- **已安装 v1.x 的用户**：覆盖安装即可（`applicationId` 不变）
- **已下载的歌曲**：`Music/Soundtrack/` 路径未变，无需迁移
- **开发者**：本地 `git pull` 后构建新版 APK

## 📦 安装

```bash
# 拉取最新代码
git pull origin main

# 用项目自带的 Android 工具链构建（已签名在仓库内 build-tools/）
cd android
../build-tools/gradle-8.2/bin/gradle.bat assembleDebug --no-daemon

# 产物（已带版本号）
ls -lh app/build/outputs/apk/debug/yinfu-music-*.apk
```

## 📚 文档

- [`README.md`](../../README.md) — 双端总览
- [`docs/PRD.md`](../../docs/PRD.md) — 产品需求文档（已重命名为「音符 Note」）
- [`docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md) — 系统设计（已重命名）
- [`docs/diagram-class.mmd`](../../docs/diagram-class.mmd) — 类图
- [`docs/diagram-sequence.mmd`](../../docs/diagram-sequence.mmd) — 时序图
- [`USAGE.md`](../../USAGE.md) — 使用说明（已重命名）
- [`CHANGELOG.md`](../../CHANGELOG.md) — 完整修订记录（本文件同源）

---

## 🙏 致谢

- [CharlesPikachu/musicdl](https://github.com/CharlesPikachu/musicdl) — 网页端 57 个音源的聚合本实现
- 网易云音乐公开 API
- 咪咕 / 酷我 / QQ 音乐 / 酷狗 / MyFreeMP3 公开接口
- ExoPlayer / Media3
- Material Components / AndroidX

## 📝 Full Changelog

See [CHANGELOG.md](../../CHANGELOG.md) `[2.0.0]` 段。
