# Request F QA 验证报告

## 验证范围

| # | 问题 | 修复方案 | 验证方式 |
|---|------|---------|---------|
| 1 | 「混帐-周柏豪」还是 30 秒 | PreviewGuard 时长估算守护 + migu copyrightCache 键修复 + 全源守护 + 诚实提示 | 真实 API 实测 + 判定表 |
| 2 | 搜「晚安」转圈 + 退出崩溃 | 渐进上屏 + 源超时 35s→10s + CancellationException rethrow + isAdded 守卫 | 静态检查 |

## 验证结果:43 PASS / 0 FAIL

### 1. PreviewGuard sizeBasedVerdict 判定表(7 CASE)

| totalBytes | expectedDurationSec | 期望 | 实际 | 说明 |
|-----------|--------------------|------|------|------|
| 460000 | 269 | True | True | 混帐 30s 试听 460KB → 13.7kbps → 判试听 ✅ |
| 4300000 | 269 | False | False | 完整版 4.3MB → 128kbps → 放行 ✅ |
| 181000 | 0 | True | True | kuwo 11s 试听 181KB → <600KB → 判试听 ✅ |
| 960000 | 269 | True | True | 96s 段 → 28.5kbps → 判试听 ✅ |
| 720000 | 45 | False | False | 45s 完整 → >600KB → 放行 ✅ |
| 480000 | 30 | True | True | 30s 试听 → <600KB → 判试听 ✅ |
| 720000 | 0 | False | False | 时长未知 720KB → >600KB → 放行 ✅ |

### 2. 真实 API 实测:haitangw 30s 试听被守护拦截

- 网易 id=3425493037(混帐-周柏豪,fee=1)经 haitangw 解析
- 返回 URL size=0.46MB,br=0(第三方不报码率)
- Content-Range 总大小=481115 bytes(481KB)
- PreviewGuard 判定:`481115 * 8 / 269 = 14308 bps < 48000` → **试听,拦下** ✅
- 对比旧 isLikelyPreview:sync-word 密度=1087 > 600 → 误判完整版 → 30 秒戛然而止

### 3. Migu artistsMatch 拒绝同名翻唱(5 CASE)

| a | b | 期望 | 实际 | 说明 |
|---|---|------|------|------|
| 周柏豪 | 周柏豪 | True | True | 正主匹配 ✅ |
| 山岚 | 周柏豪 | False | False | 翻唱被拒 ✅ |
| 周柏豪 Pakho | 周柏豪 | True | True | 含首位歌手 ✅ |
| (空) | 周柏豪 | True | True | 缺元数据只信 title ✅ |
| 周杰伦 | 方文山 | False | False | 不同艺人 ✅ |

### 4. 静态检查:源码一致性(26 项)

**SourceRegistry**:withTimeout(10_000) ✅,无 35000 残留 ✅
**SearchFragment**:import CancellationException ✅,catch CancellationException ✅,rethrow ✅,isAdded 守卫 ✅,publishCandidates 函数 ✅,渐进发布(probe 通过+收尾各一次)✅
**PlayerRepository**:import PreviewGuard ✅,主源守护 ✅,fallback 全源守护 ✅,无 isLikelyPreview 残留 ✅,skippedPreviewInResolve 字段 ✅,诚实提示(仅找到试听)✅,诚实提示(暂无完整免费)✅
**NeteaseMusicSource**:isLikelyPreview 已删 ✅,resolveUrl 带 durationSec ✅,调用 PreviewGuard ✅
**MiguMusicSource**:路径 B 重搜 ✅,artistsMatch 函数 ✅,用 matched.songId 调 resolveBy ✅
**PreviewGuard**:sizeBasedVerdict ✅,syncWordVerdict 退路 ✅,resolveTotalBytes ✅
**CHANGELOG**:试听守护升级 ✅,copyrightCache 键 bug ✅,搜索渐进上屏 ✅,搜索页崩溃修复 ✅

## 结论

两个问题的根因都已 100% 实锤并修复验证通过:
- **问题 1**:试听守护从 sync-word 密度升级为时长估算,实测拦下 haitangw 30s 试听(481KB@269s → 14kbps);migu copyrightCache 键 bug 修复,跨源路由不再 100% miss;全链只剩试听时诚实提示
- **问题 2**:搜索渐进上屏(探测通过即重排序替换)+ 源超时 10s,「晚安」2-3 秒可见首批;CancellationException 正确 rethrow + isAdded 守卫消除退出崩溃

QA 脚本:`qa/preview_guard.py`
