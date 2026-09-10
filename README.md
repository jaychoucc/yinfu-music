# 音符 —— 跨端音乐搜索与播放

> **电脑端**（网页 / Flask）和 **手机端**（Android / Kotlin）双端实现：58 + 57 个音乐源，
> 完全本地运行，无需登录、无需付费会员、无需服务端代理。

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF.svg)](https://kotlinlang.org)
[![Python](https://img.shields.io/badge/Python-3.13-3776AB.svg)](https://python.org)
[![musicdl](https://img.shields.io/badge/powered%20by-musicdl-FF6B6B.svg)](https://github.com/CharlesPikachu/musicdl)

## 它做什么

把开源 [musicdl](https://github.com/CharlesPikachu/musicdl) 的全部音乐源客户端
**以两种方式** 重新实现：

| 端 | 实现 | 音源 | 关键栈 |
|---|---|---:|---|
| **电脑端** `web/` | 直接调用 `musicdl` Python 库的客户端 | **58** | Python 3.13 + Flask + SSE + 原生 HTML/CSS/JS |
| **手机端** `android/` | 把每个客户端**手写移植**成 Kotlin | **57**（5sing 原创未实现） | Kotlin 1.9.24 + Media3 ExoPlayer + OkHttp |

两端都**完全本地运行**：电脑端是 Flask 服务 + 浏览器，不需要云；手机端是 APK，不需要服务端代理、不需要登录、不需要付费会员。

---

## 📱 手机端（`android/`）

- **57 个音源，本地原生** — 所有解析都在手机上跑，无中间层、无后端
- **四档能力如实标注** — 可搜可播 / 仅试听片段 / 仅搜索 · 不可播 / 需配置账号，UI 不虚标
- **网易云风格首页** — 横幅 + 推荐歌单 / 新歌速递 / 排行榜 三个横向 Rail，点进详情真实可播
- **沉浸式播放页** — Media3 ExoPlayer + 模糊封面背景 + 同步歌词 + 长按复制
- **跨源回退** — 解析播放地址失败时，自动用其他已启用源搜"同名歌"补位
- **下载入库** — 真实下载音频到 `Music/Soundtrack/` 目录，通过 MediaStore 入库

### 音源覆盖（v1.8.0）

| 类别 | 数量 | 实际可用 |
|---|---:|---|
| 主力（咪咕/网易云/酷我/QQ/酷狗/千千） | 6 | 6 可搜可播 |
| 国内聚合（音乐库/歌曲宝/.../5sing 原创等 30 个） | 30 | 24 可搜可播 · 5 仅搜索（夸克网盘）· 1 未实现（5sing 原创） |
| 播客电台（荔枝/蜻蜓/喜马拉雅） | 3 | 2 可搜可播 · 1 仅搜索（需 access_token） |
| 海外平台（YouTube/SoundCloud/Spotify/.../FMA） | 18 | 11 可搜可播 · 2 仅试听（Apple/Deezer 30 秒）· 4 需账号 · 1 仅搜索（YouTube） |
| **总计** | **57** | **43 可搜可播 · 7 仅搜索 · 2 仅试听 · 4 需账号 · 1 未实现** |

完整说明见 [docs/source-porting-report.md](docs/source-porting-report.md)。

### 快速开始

到 [Releases](../../releases) 下载最新的 `音符-note-vX.X.X-debug.apk`，传到手机安装。

从源码构建：

```bash
git clone <your-repo-url> yinfu-music
cd yinfu-music/android
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug
# APK 生成在 app/build/outputs/apk/debug/app-debug.apk
```

详细见 [USAGE.md](USAGE.md)。

---

## 💻 电脑端（`web/`）

- **58 个音源，SSE 逐源异步上屏** — 哪个源先完成哪个源先显示，35 秒兜底超时
- **底部播放条 + 歌词抽屉** — 当前行高亮、自动滚动、点击跳转
- **键盘快捷键** — `Space` 播放/暂停、`← →` 切歌、`↑ ↓` 调音量
- **零安装** — 浏览器打开 `http://127.0.0.1:58652` 即用

### 音源覆盖

直接使用 musicdl 库全部 58 个 `MusicClient`，搜索调用 `MusicClient(music_sources=[...]).search(keyword)`。
每个源独立线程并发（各持独立 client 实例，避免 rich 进度条污染 stdout）。
详见 [web/README.md](web/README.md)。

### 快速开始

```bash
cd web
pip install flask requests musicdl
python server.py
# 浏览器打开 http://127.0.0.1:58652
```

Windows 环境下直接双击 `web/run.bat` 即可（脚本会自动启动并打开浏览器）。

---

## 🗂 项目结构

```
yinfu-music/
├── android/                  ← 📱 手机端：Android Studio 项目根（Kotlin）
│   ├── app/src/main/java/com/soundtrack/music/
│   │   ├── MainActivity / HomeFragment / SearchFragment / MineFragment / PlayerActivity / PlaylistDetailActivity
│   │   ├── source/           ← 57 个 *MusicSource.kt（手写移植 musicdl 客户端）
│   │   ├── home/             ← 首页 NetEase 风格 API + Repository
│   │   ├── player/           ← PlayerRepository / PlayerService / LrcParser
│   │   ├── download/         ← DownloadManager
│   │   ├── adapter/          ← 列表适配器
│   │   ├── model/            ← Song 数据类
│   │   └── util/             ← MiniImageLoader / Net (含内存 CookieJar) / Formatters
│   └── build.gradle.kts
├── web/                      ← 💻 电脑端：Flask + 原生 HTML/JS 网页
│   ├── server.py             ← 后端（SSE 逐源异步、音频代理、歌词、下载）
│   ├── static/               ← 前端三件套（index.html / style.css / app.js）
│   ├── probe_sources.py      ← 源可用性探测脚本
│   ├── run.bat               ← Windows 一键启动
│   └── README.md             ← 电脑端专属说明
├── ref/                      ← 早期 v1.5 之前电脑端 Python 移植核心 6 源（历史遗产）
├── ref_all/                  ← musicdl 上游 57 源 Python 客户端（移植对照参考）
├── docs/                     ← 项目文档（架构 / PRD / 移植报告 / UML）
├── deliverables/             ← 已发布的 APK 归档
├── .gitignore
├── LICENSE
├── README.md                 ← 你正在看
└── USAGE.md                  ← 手机端使用手册
```

---

## 🛠 技术栈

| 端 | 组件 | 选型 |
|---|---|---|
| 📱 | 语言 | Kotlin 1.9.24 |
| 📱 | 构建 | Gradle 8.2 + AGP 8.1.4 |
| 📱 | UI | Material Components + ViewBinding + 自实现图片加载 |
| 📱 | 播放 | **Media3 ExoPlayer 1.0.2** + MediaSessionService 前台服务 |
| 📱 | 网络 | OkHttp 4.12.0（**内置内存 CookieJar**） |
| 📱 | 并发 | Kotlinx Coroutines 1.8.1 |
| 💻 | 语言 | Python 3.13 |
| 💻 | Web 框架 | Flask 3.x |
| 💻 | 实时通信 | Server-Sent Events（流式逐源上屏） |
| 💻 | 数据源 | musicdl 2.x 库（直接调 `MusicClient.search()`） |
| 💻 | 前端 | 原生 HTML5 + CSS3 + ES6（无框架） |
| 💻 | 音频 | `<audio>` + MediaSession API |

---

## 🧠 几个值得一提的设计决定

1. **`Song` 全部字段 `var`**（手机端） — 音源普遍是"搜索给元数据 → 解析链接时再回填 `ext` / 时长 / 封面"的延迟富化流程。可变字段是模型层面的支持。
2. **内存 `CookieJar` 自实现**（手机端） — 多个聚合源要求"先访问列表页拿会话 cookie，再 POST 直链接口"。OkHttp 自带接口，按 host 隔离，不引入新依赖。
3. **跨源回退解析**（手机端） — 点歌时 `PlayerRepository` 优先用歌曲本身的音源；失败再用其他已启用源搜"同名同歌手"补位。**不会自动跳歌名**。
4. **能力四档而不二档**（手机端） — 不只标"已适配/未适配"。SEARCH-only / AUTH-only 的源在 UI 上明确告诉用户"能搜不能播"或"需配置账号"，避免失望。
5. **诚实胜过好看**（双端） — 拿不到播放地址就 `null`，无凭据就 `emptyList()`，从不伪装成功。
6. **手机端不依赖电脑端** — 即使两端在仓库里并列，手机 APK 完全独立运行（已对照 `ref_all/` 把所有解析移植到了 Kotlin）。
7. **电脑端不依赖云端** — Flask 启动后只用本地服务，调用 musicdl 库直连各源 API，不需要部署。

---

## 📖 文档

- **使用说明**：[USAGE.md](USAGE.md) — 手机端装 APK 后怎么用、每页能做什么、常见问题
- **电脑端说明**：[web/README.md](web/README.md)
- **架构设计**：[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — 模块划分、关键流程、UML 图
- **产品需求**：[docs/PRD.md](docs/PRD.md) — 用户故事、范围与非范围
- **音源移植报告**：[docs/source-porting-report.md](docs/source-porting-report.md) — 手机端 57 个源逐个说明、四档能力、4 个根因级编译修复
- **类图**：[docs/diagram-class.mmd](docs/diagram-class.mmd)
- **时序图**：[docs/diagram-sequence.mmd](docs/diagram-sequence.mmd)

---

## ⚠️ 免责声明

本项目为开源学习作品，**仅用于技术研究与个人娱乐**。

- 所有音频、歌词、封面版权归原作者及各音乐平台所有
- 不内置任何盗版内容，所有解析结果均来自公开 API
- 请勿用于商业用途或公开分发
- 下载后请在 24 小时内删除
- 开发者不对使用本项目产生的任何法律/版权纠纷负责

参考同类项目 [lyswhut/lx-music-desktop](https://github.com/lyswhut/lx-music-desktop) 的协议精神。

---

## 🙏 致谢

- [CharlesPikachu/musicdl](https://github.com/CharlesPikachu/musicdl) — 全部 57 个音源 Python 实现的原作者（电脑端直接调用；手机端手写移植）
- [lyswhut/lx-music-mobile](https://github.com/lyswhut/lx-music-mobile) — 手机端首页设计灵感
- [Media3](https://github.com/androidx/media) — 现代化的 ExoPlayer
- [OkHttp](https://github.com/square/okhttp) — 可靠的 HTTP 客户端

---

## License

Apache 2.0 + 附加条款（见 [LICENSE](LICENSE)）。