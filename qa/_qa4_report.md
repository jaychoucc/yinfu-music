# QA4 独立回归报告 —— 「我的歌单 + 首次点击必崩」修复

- 项目：音符 yinfu-music（Android）
- 本轮对象：`PlaylistNameDialog.kt` 的 `inflateInput` 修复
- 产物：`android/app/build/outputs/apk/debug/yinfu-music-1.0.0-20260915.1700.apk`（9,705,348 B，mtime 17:01:16）
- 复核姿态：以「能不能证伪」为准，独立重推 + 独立取证 + 证明新增守卫断言会红
- 记录时间：2026-09-15 17:1x

---

## ⚠️ 首先声明：本轮无法验证的项（不淡化）

1. **无真机 / 模拟器** —— 「弹窗真的能弹出、能输入、能创建成功、首次点击不再崩」这类
   **运行时行为无法验证**。崩因链是依 **AndroidX 编译字节码语义 + 崩溃栈 + 改前后字节码对比**
   推导而来，不是运行时实测。
2. **原始设备 `crash.log` 在本机不存在**（全盘搜索 `*crash*` 只找到源码 `CrashGuard.kt` 与其编译产物）。
   `CrashGuard` 把日志写到 `context.getExternalFilesDir(null)`，属**设备运行时目录**，故仓库里没有是正常的。
   → 「4 次完全相同的栈」是 team-lead **转述**，我**无法独立复读原始日志**。
   我独立确认的是 `util/CrashGuard.kt` 的**机制**（先写盘、再委托默认 handler → 进程被杀 → 观感「退回上一级」），见 §3。
3. **未能读到 AlertController 的 Java 源码**（gradle 缓存里根本没有 appcompat 的 `*-sources.jar`）。
   改用 **javap 反汇编编译产物** 取证，见 §3。

---

## 逐项验证结论

### 1【最重要】修复真的进了 APK 产物（字节码级） —— ✅ 通过

**基础判据**：APK mtime `17:01:16` **晚于** `PlaylistNameDialog.kt` mtime `17:00:15` → 通过。

**字节码级取证**（`dexdump.exe -d`，build-tools/34.0.0）：
APP 类在 `classes4.dex`（`classes8.dex` 只是调用方）。`Lcom/soundtrack/music/util/PlaylistNameDialog;`：

- **`inflateInput` 的返回类型是 `Lkotlin/Pair;`**（指令 `Lkotlin/TuplesKt;.to(...)` 后 return）
  —— 这是新代码独有特征，旧写法是 `Landroid/widget/EditText;`。**最直接的判别成立。**
  locals 亦为 `root Landroid/view/View;` / `input Landroid/widget/EditText;`。
- `showNew`：`inflateInput(...)Lkotlin/Pair;` → `Pair.component1` → `check-cast Landroid/view/View;`(=root)
  → `AlertDialog$Builder.setView:(Landroid/view/View;)` 的实参 = **该 root**；`component2` → `EditText`(=input)。
- `showRename`：同样 `component1`(=root) 交给 `setView`。
- **`getFirst`/`component1` 取 Pair 成员的调用确实出现**（`Lkotlin/Pair;.component1`）。

→ 结论：**APK 里就是新代码**，不是「只改了源码没重打包」。证据：`qa/_qa4_bytecode.txt`。

### 2【最重要】原写法「必然崩」的因果链独立重建 + 守卫断言可证伪 —— ✅ 通过

**布局事实（产物侧）**：`aapt dump xmltree <APK> res/layout/dialog_playlist_name.xml`
→ 根 `E: LinearLayout`（含 `android:padding=0x1401`=20dip），下一级 `E: EditText`，
`android:id=@0x7f0a0141`；`aapt dump --values resources` 反查 `0x7f0a0141 = com.soundtrack.music:id/input_name`。
即 **`input_name` 确是 LinearLayout 的子 View，且 id 存在、类型为 EditText**。证据：`qa/_qa4_apk_aapt.txt`。

**AlertController 因果链**：无 sources jar（已如实声明）。改用 **javap -p -c 反汇编** `appcompat-1.6.1-runtime.jar`：
- `setupView()`：取 `R$id.customPanel`(ViewGroup) → 调 `setupCustomContent(customPanel)`。
- `setupCustomContent(ViewGroup)`：`mView` 非空时取 `R$id.custom`(FrameLayout)，偏移 109 处
  **`FrameLayout.addView(mView, ViewGroup$LayoutParams(-1,-1))`** —— 把 `Builder.setView()` 存的 View
  作为子 View 塞进 custom 容器；若该 View 已有父容器 → `ViewGroup.addViewInner` 抛
  `IllegalStateException: The specified child already has a parent`。**与崩溃栈行号吻合。**
证据：`qa/_qa4_alertcontroller.txt` + `qa/_qa4_alertcontroller_javap.txt`。

**新增守卫断言（C.10）**：写进 `qa/local_playlist_check.py` 的 C 区（契约区），命名
> `C.10 setView 实参必须是布局根容器（不得把已有父容器的子 View 交给 setView）`

设计：只看**结构**（`inflateInput` 的 `return` 表达式 + `setView` 实参的变量来源），
不认行号；对「单变量绑定」与「Pair 解构绑定」两种写法都成立；先剥离注释，避免被 KDoc 里的
`.setView(...)` 示例误伤。

**证伪自证（同一个函数，两次运行）** —— 反例 FAIL / 正例 PASS，**均为实测原始输出**：
```
[反例] 修复前错误写法（内联字符串）      -> FAIL
       detail: inflateInput 返回子 View: view.findViewById(R.id.input_name);
               setView 收到子 View: input（inflateInput 返回子 View，却直接交给 setView） | ...
[正例] 仓库真实已修复 PlaylistNameDialog.kt -> PASS
       detail: inflateInput 返回 Pair<View, EditText>；setView 实参=['root', 'root']
自证结论: OK —— 守卫可被证伪（反例 FAIL / 正例 PASS）
```
反例只以**内联字符串**存在（`qa/_qa4_falsify.py`），**未改动任何业务源码**。
该脚本从套件里**原样抽取** QA4-GUARD 块再跑，证明用的是**同一个函数**，不是复制粘贴的另一份。
证据：`qa/_qa4_falsify.py`、`qa/_qa4_falsify.txt`。

### 3 `AlertController`/`CrashGuard` 因果链取证结论 —— ✅（方式已如实说明）

- AlertController：**没有 sources jar，未读到 Java 源码**；用 **javap 字节码**证实 `addView(mView)`。见 §2。
- CrashGuard（「退回上一级」错觉来源）：独立读 `util/CrashGuard.kt` 确认 —— 先 `file.appendText(stack)`
  写 `crash.log`，**再** `default?.uncaughtException(...)` 委托默认 handler（进程被杀）；
  且 `SoundtrackApp.kt:12` 有 `CrashGuard.install(this)`，机制确在生效。

### 4 套件复跑（CWD=C:\，证明与 CWD 无关） —— ✅ 通过

| 套件 | 结果 | 期望 | 判定 |
|---|---|---|---|
| `qa\local_playlist_check.py` | **91 PASS / 0 FAIL / 0 SKIP**，exit 0 | 新增 1 条守卫后由 90→91 | ✅ 实际 91 |
| `qa\scan_nested_comments.py` | **CLEAN，scanned 108 .kt**，exit 0 | CLEAN / 108 | ✅ |
| `qa\preview_guard.py` | **44 PASS / 0 FAIL**，exit 0 | 44 / 0（第2节真连网，天然波动） | ✅ |

基线对照：加守卫**前**实跑为 **90 PASS / 0 FAIL / 0 SKIP**；加守卫**后**为 **91**。
证据：`qa/_qa4_suite_local.txt`、`_qa4_suite_scan.txt`、`_qa4_suite_preview.txt`。

### 5 改动范围 / 上轮产物未被触碰 —— ✅ 通过

- `android\app\src\main` 在 **16:50–17:05** 窗口内被修改的文件 **命中数 = 1**，即
  `java\com\soundtrack\music\util\PlaylistNameDialog.kt`（17:00:15）。.kt 按 mtime 倒序 Top1 也是它。
- 上轮 UI 产物 mtime **逐一对上、一致=True**：
  `activity_player.xml`=14:09:51、`values\dimens.xml`=14:09:30、`values-w360dp\dimens.xml`=14:09:30、
  `values-w600dp\dimens.xml`=14:21:45、`values-w800dp\dimens.xml`=14:21:53；
  `dialog_playlist_name.xml`=11:20:41（本轮未动）。
- （说明）`git status` 显示 `PlaylistNameDialog.kt` 为**未跟踪 `??`**，无 HEAD 基线 → 本轮**无法用 git diff 取证**，
  故以 **mtime** 为准。证据：`qa/_qa4_scope.txt`。

### 6 完备性抽查 + 负向思考 —— ✅ 通过

**全仓 `.kt` 扫描**（`qa/_qa4_scope.txt`）：
- `.setView(` 真实调用 **仅 2 处**，都在 `PlaylistNameDialog.kt`（:43、:78），**均为 `setView(root)`**；另 1 处是 KDoc 文字。
- `addView` 真实调用 **仅 2 处**，都在 `SearchFragment.kt`（:100 `addView(chip)`、:111 `addView(manageChip)`）；另 1 处是 KDoc 文字。
- `inflate(...)` 的 `attachToRoot=true` **命中 = 0**。

**独立抽查 ≥3 处同类写法（结论：无第二处同类缺陷）**：
1. `SearchFragment.kt:100/111` —— `chip`/`manageChip` 均为 `Chip(requireContext()).apply{...}` **即时 new** 的对象（无父容器）；
   且 `setupSourceChips()` **开头就 `sourceChips.removeAllViews()`**，反复调用也不会重复挂同一实例 → 安全。
2. `SourceManageSheet.kt:117–118` —— `i.inflate(R.layout.item_source_header|item_source, parent, false)` → `attachToRoot=false`，返回 View 无父容器 → 安全。
3. `adapter\MyPlaylistAdapter.kt:32`（及 NewSong/Song/PlaylistSong/PlaylistPick/Download/Lyric/Toplist/PlaylistCard 各 Adapter）
   —— 统一 `inflate(R.layout.x, parent, false)` → 安全。
4. 各 Fragment/Sheet `onCreateView: inflater.inflate(layout, container, false)` → 安全。
5. `PlaylistNameDialog.kt:113` 自身 `inflate(layout, null)` —— root **不带父容器**，正是交给 `setView` 的正确写法。

**三个入口都在同一根因下修好 + 签名未变**：
- `MyPlaylistsActivity.kt:45`（+新建 → `showNew`）、`:51`（重命名 → `showRename`）、
  `AddToPlaylistSheet.kt:67`（面板内新建 → `showNew`）。
- 字节码方法签名逐字未变：
  `showNew:(Landroid/content/Context;Lcom/soundtrack/music/data/PlaylistStore;Lkotlin/jvm/functions/Function1;)V`、
  `showRename:(...;Lcom/soundtrack/music/data/LocalPlaylist;Lkotlin/jvm/functions/Function0;)V`。
  `classes8.dex` 里三个调用点与签名**完全匹配** → **调用方确实无需改**（工程师结论成立）。

**通读改后全文，确认未夹带**（源码 + 字节码双证）：
- `validate()` 逻辑/文案未变（trim→空「歌单名称不能为空」→>20「歌单名称最多 20 个字」→重名「已有同名歌单」→null）；
- `input.error` 校验路径未变；`createPlaylist`→按名反查 id→`onDone(created?.id)`→`dialog.dismiss()` 顺序未变；
- 无新增 `requestFocus`/软键盘/尺寸代码；**未用 `removeView` 拆父容器**那种错修（`inflateInput` 仍是
  `inflate(layout, null)` + `findViewById`，只把「返回什么」从子 View 改成 `root to input`）。
- 仅新增了一段说明「必须传根容器」的 KDoc（工程师已披露）。

**负向思考三问**：
1. **padding 20dp 是否仍生效？** —— **生效（且是修复的附带收益）**。`aapt` 显示 padding 值 `0x1401`
   按 Android 复杂维度解码 = unit=DIP、mantissa=20 → **20dp**。旧写法把 EditText 子 View 直接交给 `setView`，
   带 padding 的 LinearLayout 根本没被挂进对话框（而且先崩了）；新写法把 **LinearLayout 根**交给 `setView`，
   其 `padding=20dp` 作用在内容上 → 恢复正常内边距。
2. **同一 `root` 实例会被 `setView` 两次吗？连点「+」会否「同一 View 被 addView 两次」？** —— **不会**。
   `showNew`/`showRename` 每次调用都**重新执行** `inflateInput(ctx)`，即每次 `LayoutInflater.inflate(...)`
   **新建 root 实例**；字节码里 `setView` 在每个 Builder 上**只调一次**。连点「+」得到的是**两个彼此独立**的
   AlertDialog（各自一套 root），不存在「同一 View 二次 addView」。
3. **`input_name` 若 id 写错/不存在会 NPE 吗？** —— **本仓库不会**。源码 `@+id/input_name` 存在；产物侧
   `0x7f0a0141 = id/input_name`，xmltree 中 EditText 子节点确实引用它；字节码 `findViewById(R.id.input_name)`
   后 `check-cast → EditText`（类型匹配，无 ClassCastException）。id 命中且类型正确 → 不会返回 null → 不会 NPE。

---

## 判定：路由 → **NoOne（全通过）**

- 未发现**源码 Bug**（修复正确、完备、无夹带、无越权改动）→ 不报 Engineer。
- 未发现**测试代码 Bug**（新增守卫断言可运行、可证伪、正例 PASS、反例 FAIL）→ 无需自修。
- 结论：**回归通过**。遗留的只是「无真机 → 运行时行为未验证」这一客观边界（见开头声明），
  属环境限制而非代码缺陷，建议在有设备时补一次真机冒烟（点「+」新建 / 重命名 / 面板内新建 三条路径）。

---

## 产物（证据文件，均在 `qa\` 下，未覆盖前几轮 `_qa_*`/`_qa2_*`/`_qa3_*`）

| 文件 | 内容 |
|---|---|
| `_qa4_bytecode.txt` | dexdump 反汇编：inflateInput 返回 `Lkotlin/Pair;`；showNew/showRename 的 `setView(component1=root)`；valida te 语义 |
| `_qa4_apk_aapt.txt` | aapt xmltree（根 LinearLayout + 子 EditText/input_name）与 id 反查 |
| `_qa4_alertcontroller.txt` | AlertController 因果链（javap 字节码），含「无 sources jar」声明 |
| `_qa4_alertcontroller_javap.txt` | AlertController 完整 javap -p -c 原始输出 |
| `_qa4_falsify.py` | 证伪脚本：抽取套件里同一守卫函数，跑反例/正例 |
| `_qa4_falsify.txt` | 证伪原始输出（反例 FAIL / 正例 PASS） |
| `_qa4_suite_local.txt` | `local_playlist_check.py` 全量输出（91/0/0） |
| `_qa4_suite_scan.txt` | `scan_nested_comments.py` 输出（CLEAN/108） |
| `_qa4_suite_preview.txt` | `preview_guard.py` 输出（44/0） |
| `_qa4_scope.txt` | mtime 范围复核 + 上轮产物未动 + setView/addView/inflate 全仓扫描 + git 说明 |

> 本轮我仅新增/修改 `qa\` 下的文件（`local_playlist_check.py` 增加 C.10 守卫；新增 `_qa4_*.py/.txt`），
> **未改动任何业务源码**，**未提交 git**。
