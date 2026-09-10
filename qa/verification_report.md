# QA 独立回归验证报告 (Round 2) — Request C 30s 试听 metadata 路由修复

- 验证者:严过关 (QA Engineer)
- 被测改动:`android/app/src/main/java/com/soundtrack/music/home/NetEaseHomeApi.kt`(+21/-1)
- 基线:HEAD `dbf597f`,tag `v2.0.0`
- **结论:PASS**(Round 1 的 V4.2/V4.3 失败已修复,无回归)
- 证据:`qa/route_verify.py`(读真实 Kotlin `when` 求值)、`qa/cache/playlists_60.json`

---

## R1. when 决策表(读真实代码)—— PASS

从 `NetEaseHomeApi.kt` 解析出的真实分支:

```
val st = t.optInt("st", ...)   行 present: False   ← 已删除
val fee = t.optInt("fee", ...) 行 present: True
val source = when {
    fee == 1 || fee == 4  -> "migu"
    else                  -> "netease"
}
```

| fee 输入 | 命中分支 | 期望 source | 实际 source | 通过? |
|---|---|---|---|---|
| -1(缺失/负值) | else | netease | netease | ✓ |
| 0 | else | netease | netease | ✓ |
| 1 | fee 分支 | migu | migu | ✓ |
| 4 | fee 分支 | migu | migu | ✓ |
| 8 | else | netease | netease | ✓ |
| 99 / 未知 | else | netease | netease | ✓ |

> 缺字段 → `optInt(...,0)` → fee=0 → netease,100% 向后兼容。脚本按**真实 Kotlin 语义**(默认 0)求值,不再用旧版写死逻辑。

## R2. 真实路由结果 —— PASS

`qa/route_verify.py` 改为**读取生产 Kotlin 源码**解析 `when` 分支再求值(非写死)。数据缓存于 `qa/cache/playlists_60.json`(60 首真实曲目)。

```
st present 60/60 ; raw st dist = {0: 60}          ← 佐证「st=0 全命中,不能路由」
raw fee dist = {0: 11, 1: 19, 8: 30}
simulated route distribution: {'migu': 19, 'netease': 41}

fee=1 count=19 -> ['migu']
fee=0 count=11 -> ['netease']
fee=8 count=30 -> ['netease']
```

| 断言 | 结果 |
|---|---|
| V4.1 fee=1 → migu (≥8) | **PASS**(19 首) |
| V4.2 fee=0 → netease (≥2) | **PASS**(11 首) ← Round 1 曾 FAIL |
| V4.3 fee=8 → netease (≥3) | **PASS**(30 首) ← Round 1 曾 FAIL |

> 本轮抽样 fee 分布与 Round 1 略有差异(推荐歌单轮换);不影响断言。

## R3. 文件边界 —— PASS

```
$ git diff --stat
 .../com/soundtrack/music/home/NetEaseHomeApi.kt | 22 +++++++++++++++++++++-
 1 file changed, 21 insertions(+), 1 deletion(-)

$ git diff --stat v2.0.0..HEAD -- '*.kt'
(空)

$ git status --porcelain
 M android/app/src/main/java/com/soundtrack/music/home/NetEaseHomeApi.kt
?? qa/route_verify.py
?? qa/verification_report.md
```

- 全仓**仅 1 个 .kt 改动** ✓
- `.workbudby/` 拼错目录**已删除**(`ls` 找不到)✓
- `.workbuddy/` 保留 13 个文件 ✓
- 无残留 `"st"` / `val st` 引用(`grep` = none)✓

## R4. 编译 —— PASS

```
$ gradle --offline --no-daemon :app:compileDebugKotlin
> Task :app:compileDebugKotlin
BUILD SUCCESSFUL in 43s
14 actionable tasks: 7 executed, 7 up-to-date
```

## 附带问题(独立列出)

1. docblock 现已自洽:⚠️ 说明「本端点 st=0 是 60/60 全命中,不能拿来路由」,准确 ✓。
2. `NetEaseHomeApi.kt:186-187` 称 `source="migu"` 会「主解析器第一发击中咪咕完整流」——措辞已从上一版的错误「海糖网」改为「咪咕」,与 `MiguMusicSource` 实际实现一致 ✓。
3. 无其它残留脏产物;`Sourcery`/`detekt` 未在构建链中启用,无告警。

## 总评:PASS
- R1 决策表 ✓ / R2 V4.1·V4.2·V4.3 ✓ / R3 边界 ✓ / R4 编译 ✓
- Round 1 的两个失败项(V4.2/V4.3)均已转为 PASS,`st` 分支与相关行彻底移除,无回归。
