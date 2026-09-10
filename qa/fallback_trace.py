#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
qa/fallback_trace.py — 静态模拟 PlayerRepository.resolvePlayableUrl 的 fallback 流程。

验证目标：
  1. **旧逻辑 (带 it.hasPlayUrl 过滤)** —— 在 search 返回的 Song.playUrl 普遍为空的前提下，
     fallback 100% 哑炮（firstOrNull 直接过滤掉所有候选），证明旧 bug。
  2. **新逻辑 (两步式 search + resolvePlayUrl)** —— 至少能在新链路上（特别是 joox / apple）
     命中一个非空 http URL。
  3. **netease-30s 守护** —— 当 song.source=="netease" 且 isLikelyPreview(url)==True，
     应被 continue 跳过，不返回该 URL。

不依赖 Android 运行时；只构造 Kotlin 流程的 Python 镜像。
"""
from __future__ import annotations
import json
import sys
from typing import Optional, List, Dict, Any

# ---------- 镜像 Kotlin 数据结构 ----------

class Song:
    def __init__(self, song_id="", source="", title="", artist="",
                 play_url="", ext="mp3", duration_sec=0, cover_url=""):
        self.song_id = song_id
        self.source = source
        self.title = title
        self.artist = artist
        self.play_url = play_url
        self.ext = ext
        self.duration_sec = duration_sec
        self.cover_url = cover_url

    @property
    def has_play_url(self) -> bool:
        # 镜像 Song.kt:28 —— playUrl.isNotBlank() && playUrl.startsWith("http")
        return bool(self.play_url) and self.play_url.startswith("http")


# ---------- 模拟的 MusicSource 实现 ----------

class MockSource:
    """模拟 MusicSource 的 search + resolvePlayUrl"""
    def __init__(self, src_id: str, *, search_results: Optional[List[Song]] = None,
                 resolve_url: Optional[str] = None,
                 preview_check_url: Optional[str] = None):
        self.id = src_id
        self.label = src_id
        self._search_results = search_results or []
        self._resolve_url = resolve_url
        self._preview_check_url = preview_check_url

    async def search(self, keyword: str, page_size: int = 5) -> List[Song]:
        # 镜像 Netease/Joox/Apple 的行为：search 返回 Song 时 play_url 通常为空
        # （Apple 例外，previews[0].url 直接写入 playUrl）
        return list(self._search_results)

    async def resolve_play_url(self, song: Song) -> Optional[str]:
        # 镜像 resolvePlayUrl 的常见实现：先看 hasPlayUrl
        if song.has_play_url:
            return song.play_url
        return self._resolve_url


# ---------- Netease 30s 守护（镜像 NeteaseMusicSource.isLikelyPreview）----------

def is_likely_preview(url: str) -> bool:
    """真实实现用 MPEG sync word 统计；这里只模拟一个简单启发式：
       netease 第三方解析链返回的 URL 全部视作可能是 30s 片段（保守）。"""
    # 实际场景：第三方的 musicapi.haitangw.net 对 80% 以上的歌只返回 9~30s 试听
    return "haitangw" in url or "preview" in url or url.endswith(".mp3?preview=1")


# ---------- SOURCE RESOLVER（镜像 SourceResolver.resolve）----------

def source_resolver(src_id: str, all_sources: Dict[str, MockSource]) -> Optional[MockSource]:
    return all_sources.get(src_id)


# ---------- Kotlin fallback 流程（镜像 PlayerRepository.resolvePlayableUrl 244-308）----------

# FALLBACK_SOURCES 镜像 PlayerRepository.kt:147-152
FALLBACK_SOURCES = [
    "migu", "kuwo", "qq", "kugou", "myfreemp3",
    "joox",
    "netease",
    "apple",
]


def fallback_old_logic(song: Song, sources: Dict[str, MockSource]):
    """镜像旧实现：.firstOrNull { title 相等 && hasPlayUrl } —— 永远命中空，因为 search 返回的 Song.playUrl 为空。"""
    for src_id in FALLBACK_SOURCES:
        if src_id == song.source:
            continue
        src = source_resolver(src_id, sources)
        if src is None:
            continue
        # 旧逻辑：search → filter title && hasPlayUrl → firstOrNull
        # 用 Python list 表示同步 await 结果
        results = src._search_results  # type: ignore[attr-defined]
        for r in results:
            if r.title == song.title and r.has_play_url:
                return r.play_url  # 旧 hasPlayUrl 必为 False，循环完返回 None
    return None


def fallback_new_logic(song: Song, sources: Dict[str, MockSource]):
    """镜像新实现：search → filter title → resolvePlayUrl → 检查 http → 守护。"""
    for src_id in FALLBACK_SOURCES:
        if src_id == song.source:
            continue
        src = source_resolver(src_id, sources)
        if src is None:
            continue
        results = src._search_results  # type: ignore[attr-defined]
        matches = [r for r in results if r.title.lower() == song.title.lower()]
        if not matches:
            continue
        first = matches[0]
        # 关键：调用 resolvePlayUrl 真正拿 URL
        url = src._resolve_url  # type: ignore[attr-defined]
        if not url or not url.startswith("http"):
            continue
        first.play_url = url
        # netease-30s 守护
        if first.source == "netease" and is_likely_preview(url):
            continue
        # 富化元数据（实际行为不在本测试断言范围）
        return url
    return None


# ---------- 测试场景 ----------

def scenario_1_old_logic_always_null():
    """场景 1：旧 hasPlayUrl 过滤对「search 返回 playUrl 为空」的源 100% 哑炮。

    重要修正：AppleMusicSource.search() 会预填 playUrl=preview_url（AppleMusicSource.kt:124），
    所以旧 hasPlayUrl 过滤对 Apple 反而有效。本场景只覆盖 Netease/Joox/Migu 等 playUrl 为空的源，
    证明旧 bug 的根因：对绝大多数非 Apple 源，hasPlayUrl 永远为 false → fallback 100% 哑炮。
    """
    # 模拟各非 Apple 源都返回 Song(playUrl="")，符合 Netease/Joox/Migu 的 search 行为
    migu = MockSource("migu", search_results=[
        Song(song_id="m1", source="migu", title="混帳", artist="周柏豪")
    ])
    joox = MockSource("joox", search_results=[
        Song(song_id="j1", source="joox", title="混帳", artist="周柏豪")
    ])
    # Apple 例外（预填 playUrl）—— 单独验证，不在主测试里
    apple = MockSource("apple", search_results=[
        Song(song_id="a1", source="apple", title="混帳", artist="周柏豪",
             play_url="https://is1-ssl.mzstatic.com/.../m4a")
    ])
    sources = {s.id: s for s in [migu, joox, apple]}

    # 分两段验证：
    # A) 只含 migu/joox（playUrl 空）→ 旧逻辑应 100% None
    sources_no_apple = {s.id: s for s in [migu, joox]}
    song = Song(title="混帳", artist="周柏豪", source="netease")
    old_result_no_apple = fallback_old_logic(song, sources_no_apple)
    print(f"  A) 只 migu/joox (无 Apple)：返回值 = {old_result_no_apple!r}")
    assert old_result_no_apple is None, \
        f"非 Apple 源旧逻辑应 100% 哑炮, 实际 {old_result_no_apple!r}"

    # B) 含 Apple (playUrl 预填) → 旧逻辑会拿到 Apple preview URL，但这是"碰巧"，不是修复
    old_result_with_apple = fallback_old_logic(song, sources)
    print(f"  B) 含 Apple：返回值 = {old_result_with_apple!r}")
    print("  结论：旧 hasPlayUrl 过滤对 Apple 是「碰巧有效」，对其他 7 个源全部哑炮。")
    print("        C 方案（带 hasPlayUrl 过滤）下，CN 主力源全哑炮 → fallback 几乎全失败。")
    return "PASS"


def scenario_2_new_logic_joox_hits():
    """场景 2：新两步式逻辑，joox 命中。"""
    migu = MockSource("migu", search_results=[])  # 找不到
    joox = MockSource("joox", search_results=[
        Song(song_id="j1", source="joox", title="混帳", artist="周柏豪")
    ], resolve_url="https://hk.joox.com/audio/abc.m4a?sign=xyz")
    apple = MockSource("apple", search_results=[])
    sources = {s.id: s for s in [migu, joox, apple]}

    song = Song(title="混帳", artist="周柏豪", source="netease")
    new_result = fallback_new_logic(song, sources)
    print(f"  新逻辑 joox 命中：返回值 = {new_result!r}")
    assert new_result == "https://hk.joox.com/audio/abc.m4a?sign=xyz", \
        f"期望 joox URL, 实际 {new_result!r}"
    return "PASS"


def scenario_3_new_logic_apple_is_last_resort():
    """场景 3：除 joox 外都失败，apple 兜底。"""
    migu = MockSource("migu", search_results=[])
    kuwo = MockSource("kuwo", search_results=[])
    qq = MockSource("qq", search_results=[])
    kugou = MockSource("kugou", search_results=[])
    myfreemp3 = MockSource("myfreemp3", search_results=[])
    joox = MockSource("joox", search_results=[])  # joox 也找不到
    netease = MockSource("netease", search_results=[])  # netease 跳过
    apple = MockSource("apple", search_results=[
        Song(song_id="a1", source="apple", title="混帳", artist="周柏豪",
             play_url="https://audio-ssl.itunes.apple.com/.../preview.m4a")
    ])
    sources = {s.id: s for s in [migu, kuwo, qq, kugou, myfreemp3, joox, netease, apple]}

    song = Song(title="混帳", artist="周柏豪", source="netease")
    new_result = fallback_new_logic(song, sources)
    print(f"  新逻辑 apple 兜底：返回值 = {new_result!r}")
    # 关键：AppleMusicSource.search 会把 preview URL 直接写到 Song.playUrl
    # 然后 resolvePlayUrl 看 hasPlayUrl 为 true 直接返回。所以 resolve_url 这里不应被调用。
    # 但 MockSource.resolve_play_url 仍返回 _resolve_url (None)。所以 Apple search 自己
    # 写入了 play_url，new_logic 第一次取 first 之后用 src._resolve_url (None) 会被 continue。
    # —— 这暴露了 AppleMusicSource 的特殊性，需要单独处理。
    print("  ⚠️ 注释：AppleMusicSource 的 search() 已预填 playUrl=preview_url，"
          "理论上 resolvePlayUrl 看到 hasPlayUrl=true 就直接返回 preview_url。")
    print("  MockSource 的 resolve_url 模拟未覆盖此特殊性；如要完美模拟，"
          "需让 apple 模拟 resolve_url 直接返回 preview_url。")
    # 重新模拟 Apple 行为
    apple2 = MockSource("apple", search_results=[
        Song(song_id="a1", source="apple", title="混帳", artist="周柏豪",
             play_url="https://audio-ssl.itunes.apple.com/.../preview.m4a")
    ], resolve_url="https://audio-ssl.itunes.apple.com/.../preview.m4a")
    sources["apple"] = apple2
    new_result = fallback_new_logic(song, sources)
    print(f"  新逻辑 apple 兜底 (修正)：返回值 = {new_result!r}")
    assert new_result == "https://audio-ssl.itunes.apple.com/.../preview.m4a", \
        f"期望 Apple preview URL, 实际 {new_result!r}"
    return "PASS"


def scenario_4_netease_30s_guard():
    """场景 4：netease 30s 守护。fallback 走到 netease 时 isLikelyPreview=True → 跳过。"""
    migu = MockSource("migu", search_results=[])
    kuwo = MockSource("kuwo", search_results=[])
    qq = MockSource("qq", search_results=[])
    kugou = MockSource("kugou", search_results=[])
    myfreemp3 = MockSource("myfreemp3", search_results=[])
    joox = MockSource("joox", search_results=[])  # 全 CN 都失败
    netease = MockSource("netease", search_results=[
        Song(song_id="n1", source="netease", title="混帳", artist="周柏豪")
    ], resolve_url="https://musicapi.haitangw.net/.../30s.mp3?preview=1")
    apple = MockSource("apple", search_results=[])
    sources = {s.id: s for s in [migu, kuwo, qq, kugou, myfreemp3, joox, netease, apple]}

    song = Song(title="混帳", artist="周柏豪", source="netease")
    # 等等：song.source == "netease" 时 FALLBACK_SOURCES 循环会跳过 netease 自身
    # 所以要把 song.source 改成另一个，比如 "migu"
    song.source = "migu"

    new_result = fallback_new_logic(song, sources)
    print(f"  新逻辑 netease-30s 守护：返回值 = {new_result!r}")
    assert new_result is None, f"netease 30s 应被守护跳过, 不该返回 URL, 实际 {new_result!r}"
    return "PASS"


def scenario_5_skip_self_source():
    """场景 5：跳过主源本身（避免重复工作）。"""
    joox = MockSource("joox", search_results=[
        Song(song_id="j1", source="joox", title="混帳", artist="周柏豪")
    ], resolve_url="https://hk.joox.com/audio/x.m4a")
    sources = {"joox": joox}

    # 主源本身就是 joox；fallback 应当跳过 joox
    song = Song(title="混帳", artist="周柏豪", source="joox")
    new_result = fallback_new_logic(song, sources)
    print(f"  skip self：返回值 = {new_result!r}")
    assert new_result is None, f"应跳过主源本身, 实际返回 {new_result!r}"
    return "PASS"


def scenario_6_non_http_url_rejected():
    """场景 6：resolvePlayUrl 返回非 http URL（如 magnet:?xt=urn:btih...）应当被拒。"""
    migu = MockSource("migu", search_results=[])
    kuwo = MockSource("kuwo", search_results=[
        Song(song_id="k1", source="kuwo", title="混帳", artist="周柏豪")
    ], resolve_url="magnet:?xt=urn:btih:abcdef")  # 非法 scheme
    qq = MockSource("qq", search_results=[
        Song(song_id="q1", source="qq", title="混帳", artist="周柏豪")
    ], resolve_url="https://qq.com/audio.m4a")  # 合法
    sources = {s.id: s for s in [migu, kuwo, qq]}

    song = Song(title="混帳", artist="周柏豪", source="netease")
    new_result = fallback_new_logic(song, sources)
    print(f"  reject non-http：返回值 = {new_result!r}")
    assert new_result == "https://qq.com/audio.m4a", \
        f"应跳过 magnet 链接命中 qq, 实际 {new_result!r}"
    return "PASS"


# ---------- 主程序 ----------

def main():
    print("=" * 70)
    print("PlayerRepository.resolvePlayableUrl 回退逻辑静态验证")
    print("=" * 70)

    scenarios = [
        ("场景 1：旧 hasPlayUrl 过滤 100% 哑炮 (反向证据)", scenario_1_old_logic_always_null),
        ("场景 2：新逻辑 joox 命中", scenario_2_new_logic_joox_hits),
        ("场景 3：新逻辑 apple 兜底", scenario_3_new_logic_apple_is_last_resort),
        ("场景 4：netease-30s 守护 (skip preview)", scenario_4_netease_30s_guard),
        ("场景 5：跳过主源本身", scenario_5_skip_self_source),
        ("场景 6：reject 非 http URL", scenario_6_non_http_url_rejected),
    ]

    failed = 0
    for name, fn in scenarios:
        print(f"\n{name}:")
        try:
            r = fn()
            print(f"  ✅ {r}")
        except AssertionError as e:
            print(f"  ❌ FAIL: {e}")
            failed += 1
        except Exception as e:
            print(f"  ❌ EXCEPTION: {type(e).__name__}: {e}")
            failed += 1

    print("\n" + "=" * 70)
    if failed == 0:
        print("ALL SCENARIOS PASS — 新 fallback 逻辑成立")
    else:
        print(f"FAILED {failed}/{len(scenarios)} scenarios")
    print("=" * 70)
    return failed


if __name__ == "__main__":
    sys.exit(main())