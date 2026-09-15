# 本地歌单系统 —— 共同上下文（主理人维护）

> 本文件是本次协作的**唯一事实来源**。所有成员（产品经理 / 架构师 / 工程师 / QA）以本文件为准，
> 不要各自重新推断项目现状。仓库根目录：`C:\Users\b5311\WorkBuddy\2026-09-15-10-37-12\yinfu-music`

## 1. 需求（用户原话）

> 创建歌单系统，默认歌单"我喜欢的音乐"，在播放按钮点击小红心添加到"我喜欢的音乐"，再次点击从歌单取消。
> 歌单里面要有歌名，歌手，图片，来自哪个音源（包括解析后的音源地址），下次从歌单点进去能直接播放，就不用再解析音源。

## 2. 已确认的范围决策（用户已拍板，不得更改）

| 决策点 | 结论 |
|---|---|
| 歌单系统深度 | **支持多张自建歌单**：除内置默认歌单「我喜欢的音乐」外，支持新建 / 重命名 / 删除歌单，歌曲可通过「加入歌单」选择器加入任意歌单，**一首歌可同时属于多张歌单** |
| 直链过期策略 | **缓存优先 + 失效自动重解析**：进入歌单点歌时若条目已缓存播放直链则直接播放、完全不解析；若播放失败（直链过期等），自动重新解析一次，并用新地址**回填歌单条目**，用户无感 |
| 默认歌单 | 「我喜欢的音乐」为内置歌单，**不可删除、不可重命名**，始终存在 |

## 3. 当前代码现状（已核实，含具体位置）

### 3.1 已具备的能力（不需要重复造）

- **红心按钮已存在但无逻辑**：`android/app/src/main/res/layout/activity_player.xml` 第 97–103 行有
  `@+id/btn_favorite`（`ImageButton`，`src=@drawable/ic_favorite`），`PlayerActivity.kt` 中**完全没有引用它**。
- **实心/空心红心图标**：`res/drawable/ic_favorite.xml`（实心）已存在；**空心 `ic_favorite_border.xml` 不存在，需要新增**。
- **"有直链就不再解析"的机制已存在**：`player/PlayerRepository.kt`
  - `Song.hasPlayUrl`（`model/Song.kt` 第 28 行）= `playUrl.isNotBlank() && playUrl.startsWith("http")`
  - `playAt(index)` 第 207–212 行：`if (song.hasPlayUrl) { setMediaAndPlay(song.playUrl); return }` —— **命中即零解析**
  - `resolvePlayableUrl()` 第 274 / 311 行会把解析到的地址**回填到 `song.playUrl`**（对象与队列/currentSong 共享引用）
  - `play(list, startIndex)` 会整队列入队并 `persistToPrefs()`
- **已有的持久化范式**：`data/PrefsStore.kt`（`SharedPreferences`）、`PlayerRepository` 自己用 JSON 存队列。
  本项目**不使用 Room / 数据库**，本地歌单也应沿用 `SharedPreferences + org.json`。

### 3.2 现有列表 UI（可复用，但不够用）

- `adapter/SongAdapter.kt` + `res/layout/item_song.xml`：
  已展示 封面(`cover`)、歌名(`title`)、歌手+专辑(`artist`)、音源标签(`source`)、品质(`quality`)、时长(`duration`)、下载按钮(`btn_more`)。
  **不展示"解析后的音源地址"** —— 这是本需求新增的展示项。
- 音源中文名解析：`SongAdapter` 第 65–67 行
  `BuiltinSources.BY_ID[s.source]?.label ?: DIRECT_LABEL[s.source] ?: s.source`（`source/BuiltinSources.kt`）。

### 3.3 ⚠️ 命名冲突（必须避开）

- `ui/PlaylistDetailActivity.kt` + `res/layout/activity_playlist_detail.xml` **已被占用**：
  它是**网易云远程歌单/排行榜详情页**（靠 `HomeRepository.loadPlaylistDetail(playlistId)` 联网取数）。
  **本地歌单的新页面必须使用不同类名与布局名**（建议 `MyPlaylistsActivity` / `MyPlaylistDetailActivity`）。
- `adapter/PlaylistCardAdapter.kt` + `item_playlist_card.xml`、`ToplistCardAdapter.kt` + `item_toplist_card.xml`
  同样是首页远程歌单卡片，**不要复用**。

### 3.4 其它可复用件

- `ui/MiniPlayerController.kt` + `layout_mini_player.xml`：底部迷你播放条，`MiniPlayerController(this, view).bind()`。
  现有 `PlaylistDetailActivity` / `SearchFragment` 都用它，新页面建议同样挂上，保证返回后仍能回到播放页。
- `util/MiniImageLoader.kt`：`loader.load(url, imageView)`，封面加载。
- `ui/MineFragment.kt` + `res/layout/fragment_mine.xml`：「我的」页，已有 `btn_clear_cache` / `btn_source_status`
  两个 `Button`，是本地歌单入口的**推荐落点**。
- `SoundtrackApp.kt`：Application 类，若要全局单例可参考 `PlayerRepository.get(context)` 的写法。
- `util/CrashGuard.kt`：全局崩溃兜底。

### 3.5 播放错误回调（重解析回填的接入点）

- `PlayerRepository.attachPlayerListener()` 第 120–125 行 `onPlayerError(error: PlaybackException)`：
  当前只写 `_error.value`，**不自动跳歌**。直链失效的自动重解析可以在此处或经其暴露的事件挂接。
- `PlayerRepository` 是 `private constructor` + `companion object.get(context)` 单例，
  对外暴露 `player / queue / currentSong / isPlaying / loading / error / position / duration` 等 `StateFlow`。

## 4. 工程约束

- **本机无法编译**：没有 JDK、Android SDK、Gradle（已实测确认 `java`/`javac`/`kotlinc` 均不存在，
  `android/gradlew` 也不在仓库里）。因此：
  - 工程师**必须**在无编译验证的条件下，靠严格的代码走查保证可编译（类型、导入、资源 id、Kotlin 语法）；
  - QA **不做编译/真机测试**，改为"静态验证 + 逻辑仿真"，与仓库既有 QA 风格保持一致
    （参见 `qa/preview_guard.py`、`qa/search_experience.py` 等：纯 Python 标准库、输出 PASS/FAIL 计数与报告 md）。
- **代码风格**：Kotlin 官方风格；注释用中文、说明"为什么"而不是"是什么"；
  `Song` 的字段是 `var`（延迟富化流程依赖），**不要改动 `Song` 的现有字段语义**。
- **不要引入新依赖**（无网络下载能力，`build.gradle.kts` 改动风险高）。
  统一用 `SharedPreferences` + `org.json`（Android 内置）。
- **不要修改** `ref/`、`ref_all/`、`web/`。
- 工作目录：仓库当前在 `main` 分支的**干净工作区**上直接改（不提交、不建分支，用户自行 review `git diff`）。

## 5. 待交付产物落位约定

| 阶段 | 产物 |
|---|---|
| 产品经理 | `docs/PRD-local-playlist.md` |
| 架构师 | `docs/ARCHITECTURE-local-playlist.md` |
| 工程师 | `android/app/src/main/**` 源码 + `CHANGELOG.md` 追加一条 |
| QA | `qa/local_playlist_check.py` + `qa/local_playlist_report.md` |
