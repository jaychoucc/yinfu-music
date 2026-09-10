# 音符 note · 全音源 Kotlin 原生移植报告（v1.7.0）

## 一句话结论

把 musicdl 的 **57 个音源客户端全部从 Python 移植成了 Kotlin**，手机端**完全独立**——不再依赖电脑端代理。
其中 **56 个已完成并通过编译**，1 个（5sing原创）因分批遗漏仍在补做。

按真实能力分四档，**UI 上如实展示，不虚标**：

| 档位 | 数量 | 含义 |
|---|---:|---|
| 可搜可播 | 43 | 搜索 + 解析真实播放地址，全部走通 |
| 仅试听片段 | 2 | Apple Music、Deezer —— 公开 API 只能给 30 秒预览 |
| 仅搜索 · 不可播 | 7 | 能搜到歌，但拿不到可播地址 |
| 需配置账号 | 4 | 无账号凭据时不可用 |

---

## 前置发现：参考源码有 24 个是空的

开工前核对发现 `ref_all/` 里 24 个 `.py` 文件大小 14 字节，内容就是 `404: Not Found` —— 上次下载用的路径不对。
已从 `CharlesPikachu/musicdl`（master 分支）按正确路径重新拉取并补齐：

- `musicdl/modules/audiobooks/` → itunes、lizhi、lrts、qingting、ximalaya
- `musicdl/modules/thirdpartysites/` → buguyy、fangpi、fivesong、gequbao、gequhai、htqyy、itingwa、kkws、livepoo、liziyy、mgmp3、mitu、sgogo、twot58、xiageba、xmfwav、yinyuedao、yinyueku（18 个）

同时对账发现两处清单错误并修正：

- **删除 `build`** —— musicdl 里根本不存在这个客户端
- **补登 `kugou`、`qianqian`、`fma`** —— 3 个真实存在但之前漏登记的源

---

## 关键技术决策（4 个改动干掉了约 80 个编译错误）

首轮全量编译报了约 120 个错误，**绝大多数是同一个根因的重复**，逐个改文件是错的。按根因收口：

### 1. `break/continue in inline lambdas` —— 开编译器开关（消灭 40+ 错误）
Kotlin 1.9 里这是实验特性。20 个音源文件都在 `forEach/let` 里用了 `continue`。
在 `app/build.gradle.kts` 全局开启，而不是改写 20 个文件的循环：
```kotlin
freeCompilerArgs += listOf("-XXLanguage:+BreakContinueInInlineLambdas")
```

### 2. `Val cannot be reassigned` —— 改数据模型（消灭 30+ 错误）
约 25 个源在解析出播放链接后要回填 `ext` / `fileSizeBytes` / `durationSec` / `coverUrl` / `album`，
但 `Song` 里只有 `playUrl`、`lrc` 是 `var`。
这是**模型设计不支持"延迟富化元数据"**，不是 25 个文件的错。已把 `Song` 全部字段改为 `var`。

### 3. `this.id` 在 `withContext` 里解析不到（A 批 7 个文件）
`withContext(Dispatchers.IO) { }` 的 lambda 接收者是 `CoroutineScope`，块内 `this` **不是音源类**。
改为 `this@XxxMusicSource.id`。

### 4. 零散错误
- `RegexOption.A or RegexOption.B` —— 枚举没有 `or` 运算符，改 `setOf(A, B)`
- OkHttp 4 移除了 `MediaType.parse()` / `HttpUrl.parse()`，改 `toMediaTypeOrNull()` / `toHttpUrl()`
- `when (n) { >= 3 -> }` 不合法，改 `when { n >= 3 -> }`
- 块体函数缺 `return`；`NodeList` 长度是属性 `length` 不是方法
- `RegexOption.IGNORECASE` → `IGNORE_CASE`

### 顺带修的运行时问题：会话 Cookie
`gequhai` / `gequbao` / `fangpi` / `jbsou` 这类站的直链接口要求"先访问列表页拿会话 cookie，再 POST 取直链"，
不带 cookie 会返回空。给共享 OkHttpClient 加了**内存 CookieJar**（按 host 隔离，不引入新依赖）。

---

## 各源明细

### 主力（core，6 个，全部可搜可播）
咪咕、网易云、酷我、QQ音乐、酷狗、千千

### 国内聚合（cn，30 个）
可搜可播 24 个：MyFreeMP3、GD音乐台、歌曲宝、歌曲海、B站音频、音乐库、小白音乐、波点音乐、汽水音乐、米兔音乐、JBSou、TuneHub、MP3Juice、爱听蛙、HTQYY、MGMP3、Sgogo、2T58、XMFWAV、Fangpi、不菇、LivePOO、MOOV、LRTS

仅搜索 5 个（音频全在**夸克网盘**，手机端无夸克解析能力）：音乐岛、下歌吧、KKWS、栗子YY、FiveSong

未适配 1 个：5sing原创（补做中）

### 播客电台（radio，3 个）
荔枝FM、喜马拉雅（可搜可播）；蜻蜓FM（仅搜索 —— 播放地址需账号 access_token 做 HMAC-MD5 签名）

### 海外平台（overseas，18 个）
可搜可播 11 个：SoundCloud、iTunes、JOOX、JioSaavn、Jamendo、Audius、ccMixter、OpenGameArt、维基共享、StreetVoice、Free Music Archive

仅试听 2 个：Apple Music、Deezer（公开 API 只给 30 秒预览，完整音轨需订阅 / ARL + Blowfish 解密）

仅搜索 1 个：YouTube Music（InnerTube 公开 API 能搜；播放链接需 yt-dlp 级 JS 反混淆，移动端无法复刻）

需配置账号 4 个：Spotify（OAuth / sp_dc）、TIDAL（access_token）、Qobuz（app_id + secret）、Suno AI（auth_token）

---

## 关于"不造假"的取舍

移植过程中明确要求：**宁可诚实返回空，也不写假装能用的实现**。

具体做法：
- 拿不到播放地址 → `resolvePlayUrl` 返回 `null`，并在文件头注释写清原因
- 无凭据 → `search` 返回 `emptyList()`（Spotify、Qobuz、Suno、TIDAL）
- 无歌词接口 → `fetchLyric` 返回 `null`，不伪造

这些源在音源管理弹窗里标注为「需配置账号」或「仅搜索 · 不可播」，用户一眼能看出差别，
不会出现"勾了却搜不到 / 搜到却播不了"的困惑。
