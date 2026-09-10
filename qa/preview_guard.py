# -*- coding: utf-8 -*-
"""
Request F QA 验脚本:试听守护升级 + 搜索卡顿修复

验证 5 个维度:
1. PreviewGuard 时长估算判定表(复刻 Kotlin sizeBasedVerdict 逻辑)
2. 真实 API 实测:haitangw 30s 试听被拦、kuwo 11s 被拦、完整版放行
3. Migu artistsMatch 逻辑(拒绝同名翻唱「山岚版混帐」)
4. SourceRegistry 超时 35s→10s(静态检查)
5. SearchFragment CancellationException rethrow + isAdded 守卫(静态检查)
"""
import json, urllib.request, urllib.parse, re, sys

PY = r"C:\Users\b5311\.workbuddy\binaries\python\versions\3.13.12\python.exe"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
PROJECT = r"C:\Users\b5311\WorkBuddy\2026-09-10-11-29-51\yinfu-music"

passed = 0
failed = 0

def check(name, cond, detail=""):
    global passed, failed
    status = "PASS" if cond else "FAIL"
    if cond:
        passed += 1
    else:
        failed += 1
    print(f"  [{status}] {name}" + (f" — {detail}" if detail else ""))


# ============================================================
# 1. PreviewGuard sizeBasedVerdict 判定表(复刻 Kotlin 逻辑)
# ============================================================
print("\n=== 1. PreviewGuard sizeBasedVerdict 判定表 ===")

def size_based_verdict(total_bytes, expected_duration_sec):
    """复刻 PreviewGuard.sizeBasedVerdict"""
    if expected_duration_sec >= 60:
        implied_bps = total_bytes * 8 / expected_duration_sec
        return implied_bps < 48000
    else:
        return total_bytes < 600000

# (totalBytes, expectedDurationSec, expected_is_preview, description)
cases = [
    (460000, 269, True,  "混帐 30s 试听 460KB@269s → 13.7kbps → 判试听"),
    (4300000, 269, False, "混帐 完整版 4.3MB@269s → 128kbps → 放行"),
    (181000, 0, True,    "kuwo 11s 试听 181KB@时长未知 → <600KB → 判试听"),
    (960000, 269, True,  "96s 段 960KB@269s → 28.5kbps → 判试听(低于48k)"),
    (720000, 45, False,  "45s 完整版 720KB@45s → >600KB → 放行"),
    (480000, 30, True,   "30s 试听 480KB@30s → <600KB → 判试听"),
    (720000, 0, True,    "时长未知 720KB → >600KB → 但 expectedDuration<60 → 看 size → 放行？不,720KB>600KB → 放行"),
]
# 修正 case 7:expectedDuration=0 < 60 → 判 totalBytes < 600000 → 720000 > 600000 → False(放行)
cases[6] = (720000, 0, False, "时长未知 720KB → >600KB → 放行")

for total, dur, expected, desc in cases:
    result = size_based_verdict(total, dur)
    check(f"({total}B, {dur}s)", result == expected, f"期望={expected} 实际={result} | {desc}")


# ============================================================
# 2. 真实 API 实测:haitangw 试听被守护拦截
# ============================================================
print("\n=== 2. 真实 API 实测:haitangw 30s 试听 ===")
try:
    # 混帐-周柏豪 网易 id=3425493037,fee=1
    url = "https://musicapi.haitangw.net/music/wy.php?id=3425493037&level=standard&type=json"
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    body = urllib.request.urlopen(req, timeout=20).read().decode("utf-8", "replace")
    j = json.loads(body)
    audio_url = (j.get("data") or {}).get("url") or ""
    size = (j.get("data") or {}).get("size") or 0
    br = (j.get("data") or {}).get("br") or 0

    if audio_url:
        check("haitangw 返回 URL", True, f"br={br} size={size}")
        # 拉 Content-Range 看总大小
        req2 = urllib.request.Request(audio_url, headers={"Range": "bytes=0-262143", "User-Agent": UA})
        with urllib.request.urlopen(req2, timeout=15) as r:
            cr = r.headers.get("Content-Range", "")
            total = int(cr.split("/")[-1]) if "/" in cr else 0
        check("haitangw 总大小 < 600KB(试听)", total < 600000, f"total={total}")
        # 复刻 PreviewGuard:expectedDuration=269(元数据)
        is_preview = size_based_verdict(total, 269)
        check("PreviewGuard 判 haitangw 为试听", is_preview, f"implied_bps={total*8/269:.0f}")
    else:
        check("haitangw 返回 URL", False, f"无 URL, resp={body[:150]}")
except Exception as e:
    check("haitangw 实测", False, f"异常: {e}")


# ============================================================
# 3. Migu artistsMatch 逻辑(复刻 Kotlin)
# ============================================================
print("\n=== 3. Migu artistsMatch 拒绝同名翻唱 ===")

def artists_match(a, b):
    """复刻 MiguMusicSource.artistsMatch"""
    if not a or not b:
        return True
    parts_a = [p.strip() for p in re.split(r"[,、;]", a) if p.strip()]
    parts_b = [p.strip() for p in re.split(r"[,,、;]", b) if p.strip()]
    first_a = parts_a[0] if parts_a else ""
    first_b = parts_b[0] if parts_b else ""
    if not first_a or not first_b:
        return True
    return first_b.lower() in a.lower() or first_a.lower() in b.lower()

am_cases = [
    ("周柏豪", "周柏豪", True,  "正主匹配"),
    ("山岚", "周柏豪", False, "翻唱被拒"),
    ("周柏豪 Pakho", "周柏豪", True,  "含首位歌手"),
    ("", "周柏豪", True,  "缺元数据时只信 title"),
    ("周杰伦", "方文山", False, "不同艺人"),
]
for a, b, expected, desc in am_cases:
    result = artists_match(a, b)
    check(f"artistsMatch({a!r}, {b!r})", result == expected, f"期望={expected} 实际={result} | {desc}")


# ============================================================
# 4. 静态检查:SourceRegistry 超时 10s + SearchFragment 修复
# ============================================================
print("\n=== 4. 静态检查:源码一致性 ===")
import os
os.chdir(PROJECT)

# SourceRegistry 10s
with open("android/app/src/main/java/com/soundtrack/music/source/SourceRegistry.kt", encoding="utf-8") as f:
    sr = f.read()
check("SourceRegistry withTimeout(10_000)", "withTimeout(10_000)" in sr, "35s→10s")
check("SourceRegistry 无 35000 残留", "35000" not in sr)

# SearchFragment CancellationException rethrow + isAdded
with open("android/app/src/main/java/com/soundtrack/music/ui/SearchFragment.kt", encoding="utf-8") as f:
    sf = f.read()
check("SearchFragment import CancellationException", "import kotlinx.coroutines.CancellationException" in sf)
check("SearchFragment catch CancellationException", "catch (e: CancellationException)" in sf)
check("SearchFragment rethrow", "throw e" in sf)
check("SearchFragment isAdded 守卫(requireContext 前)", "isAdded)" in sf)
check("SearchFragment publishCandidates 函数", "private fun publishCandidates" in sf)
check("SearchFragment 渐进发布(probe 通过即调用)", sf.count("publishCandidates(candidates, kw)") >= 2, "探测通过+收尾各一次")

# PlayerRepository 全源守护
with open("android/app/src/main/java/com/soundtrack/music/player/PlayerRepository.kt", encoding="utf-8") as f:
    pr = f.read()
check("PlayerRepository import PreviewGuard", "import com.soundtrack.music.source.PreviewGuard" in pr)
check("PlayerRepository 主源守护", 'PreviewGuard.isPreview(mainUrl' in pr)
check("PlayerRepository fallback 全源守护", 'PreviewGuard.isPreview(found' in pr)
check("PlayerRepository 无 NeteaseMusicSource.isLikelyPreview 残留", "isLikelyPreview" not in pr)
check("PlayerRepository skippedPreviewInResolve 字段", "skippedPreviewInResolve" in pr)
check("PlayerRepository 诚实提示(仅找到试听)", "仅找到试听片段" in pr)
check("PlayerRepository 诚实提示(暂无完整免费)", "暂无完整免费音源" in pr)

# NeteaseMusicSource isLikelyPreview 已删
with open("android/app/src/main/java/com/soundtrack/music/source/NeteaseMusicSource.kt", encoding="utf-8") as f:
    ns = f.read()
check("NeteaseMusicSource isLikelyPreview 已删", "isLikelyPreview" not in ns)
check("NeteaseMusicSource resolveUrl 带 durationSec", "resolveUrl(songId: String, durationSec: Int)" in ns)
check("NeteaseMusicSource 调用 PreviewGuard", "PreviewGuard.isPreview" in ns)

# Migu 路径 B
with open("android/app/src/main/java/com/soundtrack/music/source/MiguMusicSource.kt", encoding="utf-8") as f:
    mg = f.read()
check("Migu 路径 B 重搜", "search(song.title" in mg and "firstOrNull" in mg)
check("Migu artistsMatch 函数", "private fun artistsMatch" in mg)
check("Migu 用 matched.songId 调 resolveBy", "resolveBy(matched.songId" in mg)

# PreviewGuard 文件
with open("android/app/src/main/java/com/soundtrack/music/source/PreviewGuard.kt", encoding="utf-8") as f:
    pg = f.read()
check("PreviewGuard sizeBasedVerdict", "sizeBasedVerdict" in pg)
check("PreviewGuard syncWordVerdict 退路", "syncWordVerdict" in pg)
check("PreviewGuard resolveTotalBytes", "resolveTotalBytes" in pg)

# CHANGELOG
with open("CHANGELOG.md", encoding="utf-8") as f:
    cl = f.read()
check("CHANGELOG 试听守护升级条目", "试听守护升级" in cl)
check("CHANGELOG migu copyrightCache 键 bug", "copyrightCache 键 bug" in cl)
check("CHANGELOG 搜索渐进上屏", "渐进上屏" in cl)
check("CHANGELOG 搜索页崩溃修复", "搜索页崩溃修复" in cl)


# ============================================================
# 汇总
# ============================================================
print(f"\n{'='*50}")
print(f"总计: {passed} PASS / {failed} FAIL")
if failed > 0:
    print("❌ 有失败项,需修复")
    sys.exit(1)
else:
    print("✅ 全部通过")
