# 音符 Note · 交付总览（v1.2.0）

> 基于开源项目 [CharlesPikachu/musicdl](https://github.com/CharlesPikachu/musicdl) 的双端音乐应用：网页端 + 安卓端（已产出可安装 APK）。

## v1.2.0 增量（3 项需求）
1. **安卓首页数据采集**：推荐歌单点击后走"网易云 → QQ → 酷我 → 咪咕"回退链采集，全部失败再走 web 代理（若已配置服务端），保证首页不再空数据。
2. **安卓音源选择器 + 全音源 + 异步采集**：搜索页新增"音源管理"按钮 → 弹 SourceManageActivity 勾选/取消任意音源（4 个直接源 + 全部 57 个 web 源分组展示）；搜索时按源异步并发采集，谁先返回谁先上屏；同时**修复了播放时歌名轮询查询所有歌曲的 bug**（PlayerRepository 解析失败时的无限 next 循环）。
3. **双端标明音源与品质**：网页端与安卓端搜索结果行都显示「源名（中文）+ 品质徽标（无损/高品质/标准）」。

## 4 项更新（v1.0.0 → v1.1.0）
1. **项目改名（v1.1.0 立项 → v2.0.0 落地）**：安卓 `app_name=音符`、品牌标识与文档字面统一替换为「音符 Note」；英文包名 `com.soundtrack.music`、类名 `SoundtrackApp` / `Theme.Soundtrack`、下载目录 `Music/Soundtrack/` 与英文品牌副标保持不动（仅中文书写层替换）
2. **图标统一**：网页端 logo 改为粉橙青渐变音符 SVG（替代原频谱柱条），安卓端启动图标改为居中音符（108dp viewport 安全区）
3. **全音源接入**：从仅 4 个扩展为 musicdl 支持的 57 个音源（覆盖 4 大主流 + 国内聚合 + 播客电台 + 18 个海外平台），前端音源栏按 core/cn/radio/overseas 分组渲染
4. **搜索异步流式**：服务端为每个音源独立线程并发搜索（不再等待全源完成），谁先完成谁先上屏（最快 4-10s 即可见首批结果，慢源在 1-3 分钟陆续补充）

## 端到端实测（v1.1.0）
- **网页搜索 "周杰伦"** 启用 12 个快速源，10 个返回真实带播放地址的结果，1 个超时，1 个零结果，总计 **77 条 result 事件**流式上屏
- **音频流代理** Range 206 Partial Content，content-type `audio/mpeg`，文件头 ID3
- **LRC 歌词** 同步返回（`[00:01.00]晴天 - 周杰伦`）
- **APK** 9.3MB，application-label `音符`，启动图标为居中音符，启动 Activity `com.soundtrack.music.ui.MainActivity`

## 启动方法

### 网页端
```bash
# 依赖：flask, requests, musicdl（v2.x）
python -B server.py
# 浏览器打开 http://127.0.0.1:58652
```
首次搜索全源约 1-3 分钟；启用 1-2 个快速源（咪咕 / MyFreeMP3）即可在 10 秒内出结果。

### 安卓端
直接安装 `deliverables/software-musicdl-dual/apk/音符-note-v1.1.0-debug.apk`。
真机要求 Android 7.0+（minSdk 24）。

## 离线构建链
本机网络无法访问 `dl.google.com` / `gradle.org` / `npm`，构建链由我手工组装在 `build-tools/`：
- JDK17：`build-tools/jdk/jdk-17.0.20.1+1`（清华 Adoptium 镜像）
- Gradle 8.2：`build-tools/gradle-8.2`（腾讯镜像）
- Android SDK：`build-tools/android-sdk`（platform-33 / build-tools 33.0.1+34.0.0 / platform-tools 35）
- Maven 仓库：`https://mirrors.cloud.tencent.com/nexus/repository/maven-public/`

重新编译：
```bash
cd android
export JAVA_HOME="C:/Users/jay/WorkBuddy/2026-09-09-11-22-17/build-tools/jdk/jdk-17.0.20.1+1"
../build-tools/gradle-8.2/bin/gradle.bat assembleDebug --no-daemon
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

## 文件清单
| 路径 | 说明 |
|---|---|
| `web/server.py` | Flask 后端（per-source 异步流式搜索 + 音频代理 + 歌词 + 下载 + 品质字段） |
| `web/static/index.html`、`style.css`、`app.js` | 暗色音符 UI，58 源动态音源栏，结果行带源名+品质徽标 |
| `web/probe_sources.py` / `probe_results.json` | 全 58 源并发可用性探测脚本与结果 |
| `web/run.bat` | 一键启动 |
| `android/` | Kotlin 原生工程，AGP 8.1.4 / compileSdk 33 / minSdk 24 |
| `android/.../ui/SourceManageActivity.kt` | 音源管理页（勾选直接源 + 57 个 web 源） |
| `android/.../source/WebProxySource.kt` | 代理 Flask SSE 的 web 音源 |
| `deliverables/software-musicdl-dual/apk/音符-note-v1.2.0-debug.apk` | 最新 APK |
| `deliverables/software-musicdl-dual/PRD.md` | 产品需求文档 |
| `deliverables/software-musicdl-dual/ARCHITECTURE.md` | 架构设计与技术选型 |
| `build-tools/` | 离线构建链 |

## 已知限制
- 仅供技术学习；音频版权归属各平台
- APK 为 debug 签名，发布需自备 keystore
- 5 个海外源（YouTube/Spotify/Deezer/TIDAL/Qobuz）需要登录态或地区限制，本地网络下返回零结果或超时
- 部分聚合源需额外配置（如 `quark_parser_config`），未配置会主动报错

## 关键坑点记录
- `musicdl.MusicClient.search` 是全源同步方法；要逐源异步必须每源建一个独立 client 实例
- SSE 中要正确实现"谁先完成谁先推送"：用 `queue.Queue` 做线程间消息总线，主协程 `get(timeout=1.0)` 循环即可
- 多线程同时 `_suppress_musicdl_output` 共享单个 devnull 文件句柄会触发 "I/O operation on closed file"，必须加锁 + 每次新建句柄
- musicdl 完整四源搜索实测 60-90s（库内并发），逐源异步改造后用户体验提升到首批 10s 内出结果
- Windows Git Bash 调试时 `curl -o /c/...` 写文件偶尔无声失败；统一走 Python `urllib` 最稳
