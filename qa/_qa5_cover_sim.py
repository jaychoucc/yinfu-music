# -*- coding: utf-8 -*-
"""
C-N1 独立仿真：PlaylistStore.songsOf + coverUrlOf 的「严格两级回退」语义。

纯标准库，零第三方依赖。逐字复刻 Kotlin：

    fun songsOf(playlistId: String): List<Song> =
        pl.entries.sortedByDescending { it.addedAt }.mapNotNull { library[it.songKey] }

    fun coverUrlOf(playlistId: String): String? {
        val songs = songsOf(playlistId)
        return songs.getOrNull(0)?.coverUrl?.takeIf { it.isNotBlank() }
            ?: songs.getOrNull(1)?.coverUrl?.takeIf { it.isNotBlank() }
    }

并做**可证伪性**：构造坏实现，喂进同一真值表，必须报红。

运行：python qa/_qa5_cover_sim.py    退出码：0=真实现全过且坏实现被检出；1=否则
"""
import sys

# ---------------------------------------------------------------- 被测语义
def songs_of(entries, library):
    """entries: [(songKey, addedAt)]；library: {songKey: {'coverUrl': str}}
    复刻 sortedByDescending { addedAt } + mapNotNull { library[key] }（未命中静默跳过）。"""
    ordered = sorted(entries, key=lambda e: e[1], reverse=True)
    return [library[k] for (k, _a) in ordered if k in library]


def _not_blank(v):
    """复刻 takeIf { it.isNotBlank() }：null 或纯空白 → None（即回退）。"""
    return v if (v is not None and str(v).strip() != "") else None


def cover_url_of(entries, library):
    """真实现：严格两级，不越过第二首。"""
    songs = songs_of(entries, library)
    v0 = _not_blank(songs[0]["coverUrl"]) if len(songs) > 0 else None
    if v0 is not None:
        return v0
    return _not_blank(songs[1]["coverUrl"]) if len(songs) > 1 else None


# ---------------------------------------------------------------- 坏实现（用于证伪）
def cover_url_of_bad_scanall(entries, library):
    """坏实现 A：扫全部歌曲，取第一个非空封面（越过第二首继续向后找）。"""
    for s in songs_of(entries, library):
        v = _not_blank(s["coverUrl"])
        if v is not None:
            return v
    return None


def cover_url_of_bad_no_takeif2(entries, library):
    """坏实现 B：第二级漏掉 takeIf{isNotBlank}（无封面时返回空串而非 null）。"""
    songs = songs_of(entries, library)
    v0 = _not_blank(songs[0]["coverUrl"]) if len(songs) > 0 else None
    if v0 is not None:
        return v0
    return songs[1]["coverUrl"] if len(songs) > 1 else None  # 未过滤空白


# ---------------------------------------------------------------- 真值表
def case(specs):
    """specs: [(key, addedAt, coverUrl)] -> (entries, library)"""
    library, entries = {}, []
    for (k, a, c) in specs:
        library[k] = {"coverUrl": c}
        entries.append((k, a))
    return entries, library


TRUTH_TABLE = [
    # (名称, specs, 期望)
    ("空歌单", [], None),
    ("[有封面]", [("a", 10, "C1")], "C1"),
    ("[无封面]（仅一首）", [("a", 10, "")], None),
    ("[无封面, 有封面]", [("a", 20, ""), ("b", 10, "C2")], "C2"),
    # 故意用插入序 = 加入序相反：a 先插入但 addedAt 小，b 后插入但 addedAt 大
    ("[有封面A, 有封面B] → addedAt 最大者(B)", [("a", 100, "A"), ("b", 200, "B")], "B"),
    # 关键反例：第二首也无封面，第三首才有 → 必须 None（不得取到第 3 首）
    ("[无封面, 无封面, 有封面] → None", [("a", 300, ""), ("b", 200, ""), ("c", 100, "C3")], None),
    ("[无封面, 有封面, 有封面] → 第 1 首(B1)", [("a", 300, ""), ("b", 200, "B1"), ("c", 100, "B2")], "B1"),
]


def run(fn, specs):
    return fn(*case(specs))


def main():
    ok = True
    print("=== C-N1 真值表：真实现（期望全 PASS）===")
    for (name, specs, exp) in TRUTH_TABLE:
        got = run(cover_url_of, specs)
        good = (got == exp)
        ok = ok and good
        print("  [%s] %s — 期望=%r 实际=%r" % ("PASS" if good else "FAIL", name, exp, got))

    print("\n=== 可证伪性：坏实现（期望至少 1 行 FAIL）===")
    for label, fn in (("坏实现A-扫全部取第一个非空", cover_url_of_bad_scanall),
                      ("坏实现B-第二级漏 takeIf", cover_url_of_bad_no_takeif2)):
        mism = [n for (n, s, e) in TRUTH_TABLE if run(fn, s) != e]
        detected = len(mism) > 0
        ok = ok and detected
        print("  [%s] %s — 失配行=%s" % ("PASS(已证伪)" if detected else "FAIL(未被检出)",
                                          label, mism))

    print("\n%s" % ("✅ C-N1 真实现全过 且 两种坏实现均被证伪" if ok else "❌ C-N1 未通过"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
