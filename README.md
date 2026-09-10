# 音符 —— 移动端

> 一个 **完全独立** 的安卓音乐 App：57 个跨源搜索、本地原生播放、网易云风格首页。
> 手机端不需要电脑代理、不需要登录、不需要付费会员。

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF.svg)](https://kotlinlang.org)
[![Min SDK](https://img.shields.io/badge/minSdk-24-3DDC84.svg)](https://developer.android.com)
[![musicdl](https://img.shields.io/badge/powered%20by-musicdl-FF6B6B.svg)](https://github.com/CharlesPikachu/musicdl)

## 它做什么

把开源 [musicdl](https://github.com/CharlesPikachu/musicdl) 的 **57 个音乐源客户端** 全部移植成 Kotlin，
在 Android 手机上**完全独立**地搜索、播放、下载来自咪咕 / 网易云 / 酷我 / QQ / Spotify / SoundCloud / 维基共享 / 5sing …… 的音乐。


## ✨ 特性

- **57 个音源，本地原生** — 所有解析都在手机上跑，无中间层、无后端
- **四档能力如实标注** — 可搜可播 / 仅试听片段 / 仅搜索 · 不可播 / 需配置账号，UI 不虚标
- **网易云风格首页** — 横幅 + 推荐歌单 / 新歌速递 / 排行榜 三个横向 Rail，点进详情真实可播
- **沉浸式播放页** — Media3 ExoPlayer + 模糊封面背景 + 同步歌词 + 长按复制
- **跨源回退** — 解析播放地址失败时，自动用其他已启用源搜"同名歌"补位
- **下载入库** — 真实下载音频到 `Music/Soundtrack/` 目录，通过 MediaStore 入库

## 📊 音源覆盖（v1.8.0）

| 类别 | 数量 | 实际可用 |
|---|---:|---|
| 主力（咪咕/网易云/酷我/QQ/酷狗/千千） | 6 | 6 可搜可播 |
| 国内聚合（音乐库/歌曲宝/.../5sing 原创等 30 个） | 30 | 24 可搜可播 · 5 仅搜索（夸克网盘）· 1 未实现（5sing 原创） |
| 播客电台（荔枝/蜻蜓/喜马拉雅） | 3 | 2 可搜可播 · 1 仅搜索（需 access_token） |
| 海外平台（YouTube/SoundCloud/Spotify/.../FMA） | 18 | 11 可搜可播 · 2 仅试听（Apple/Deezer 30 秒）· 4 需账号 · 1 仅搜索（YouTube） |
| **总计** | **57** | **43 可搜可播 · 7 仅搜索 · 2 仅试听 · 4 需账号 · 1 未实现** |

完整说明见 [docs/source-porting-report.md](docs/source-porting-report.md)。

## 🛠 技术栈

| 组件 | 选型 |
|---|---|
| 语言 | Kotlin 1.9.24 |
| 构建 | Gradle 8.2 + AGP 8.1.4 |
| UI | Material Components + ViewBinding + 自实现图片加载 |
| 播放 | **Media3 ExoPlayer 1.0.2** + MediaSessionService 前台服务 |
| 网络 | OkHttp 4.12.0（**内置内存 CookieJar**） |
| 并发 | Kotlinx Coroutines 1.8.1 |
| 依赖注入 | 无（手工持有，单 Activity + Fragment） |

## 🚀 快速开始

### 安装已编译的 APK

到 [Releases](../../releases) 页面下载最新的 `音符-note-vX.X.X-debug.apk`，传到手机安装。

> 也可以直接用仓库自带的 APK：见 `deliverables/software-musicdl-dual/apk/`。

### 从源码构建

需要：
- JDK 17（推荐 17.0.5+，project 已附带 `build-tools/jdk/`，但 GitHub 仓库不包含此目录）
- Android SDK（API 33 platform + build-tools 34.0.0）
- Gradle 8.2（**已自带 wrapper**：`./gradlew` 即可；也可使用本地安装的 8.2）

```bash
git clone <your-repo-url> soundtrack
cd soundtrack/android
# 让 gradle 找到 Android SDK
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug      # 或 ../build-tools/gradle-8.2/bin/gradle.bat assembleDebug --no-daemon
# APK 生成在 app/build/outputs/apk/debug/app-debug.apk
```

## 📖 文档

- **使用说明**：[USAGE.md](USAGE.md) — 装到手机后怎么用、每页能做什么、常见问题
- **架构设计**：[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — 模块划分、关键流程、UML 图
- **产品需求**：[docs/PRD.md](docs/PRD.md) — 用户故事、范围与非范围
- **音源移植报告**：[docs/source-porting-report.md](docs/source-porting-report.md) — 57 个源的逐个说明、四档能力、4 个根因级编译修复
- **类图**：[docs/diagram-class.mmd](docs/diagram-class.mmd)
- **时序图**：[docs/diagram-sequence.mmd](docs/diagram-sequence.mmd)

## 🗂 项目结构

```
.
├── android/                  ← Android Studio 项目根
│   ├── app/
│   │   └── src/main/java/com/soundtrack/music/
│   │       ├── MainActivity / HomeFragment / SearchFragment / MineFragment / PlayerActivity / PlaylistDetailActivity
│   │       ├── source/       ← 57 个 *MusicSource.kt（核心实现）
│   │       ├── home/         ← 首页 NetEase 风格 API + Repository
│   │       ├── player/       ← PlayerRepository / PlayerService / LrcParser
│   │       ├── download/     ← DownloadManager
│   │       ├── adapter/      ← 列表适配器
│   │       ├── model/        ← Song 数据类
│   │       └── util/         ← MiniImageLoader / Net (含内存 CookieJar) / Formatters
│   ├── build.gradle.kts
│   └── settings.gradle.kts
├── ref_all/                  ← musicdl 全部 57 个 Python 客户端（移植参考源）
├── docs/                     ← 项目文档
├── deliverables/             ← 已发布的 APK 归档
├── .gitignore
├── LICENSE
├── README.md
└── USAGE.md
```

## 🧠 几个值得一提的设计决定

1. **`Song` 全部字段 `var`** — 音源普遍是"搜索给元数据 → 解析链接时再回填 `ext` / 时长 / 封面"的延迟富化流程。可变字段是模型层面的支持。
2. **内存 `CookieJar` 自实现** — 多个聚合源要求"先访问列表页拿会话 cookie，再 POST 直链接口"。OkHttp 自带接口，按 host 隔离，不引入新依赖。
3. **跨源回退解析** — 点歌时 `PlayerRepository` 优先用歌曲本身的音源；失败再用其他已启用源搜"同名同歌手"补位。**不会自动跳歌名**。
4. **能力四档而不二档** — 不只标"已适配/未适配"。SEARCH-only / AUTH-only 的源在 UI 上明确告诉用户"能搜不能播"或"需配置账号"，避免失望。
5. **诚实胜过好看** — 拿不到播放地址就 `null`，无凭据就 `emptyList()`，从不伪装成功。

## ⚠️ 免责声明

本项目为开源学习作品，**仅用于技术研究与个人娱乐**。

- 所有音频、歌词、封面版权归原作者及各音乐平台所有
- 不内置任何盗版内容，所有解析结果均来自公开 API
- 请勿用于商业用途或公开分发
- 下载后请在 24 小时内删除
- 开发者不对使用本项目产生的任何法律/版权纠纷负责

参考同类项目 [lyswhut/lx-music-desktop](https://github.com/lyswhut/lx-music-desktop) 的协议精神。

## 🙏 致谢

- [CharlesPikachu/musicdl](https://github.com/CharlesPikachu/musicdl) — 全部 57 个音源 Python 实现的原作者
- [lyswhut/lx-music-mobile](https://github.com/lyswhut/lx-music-mobile) — 首页设计灵感
- [Media3](https://github.com/androidx/media) — 现代化的 ExoPlayer
- [OkHttp](https://github.com/square/okhttp) — 可靠的 HTTP 客户端

## License

Apache 2.0 + 附加条款（见 [LICENSE](LICENSE)）。
