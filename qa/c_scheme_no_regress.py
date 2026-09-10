# -*- coding: utf-8 -*-
"""
qa/c_scheme_no_regress.py — 独立验证：工程师的 fallback 改动**没有**破坏 C 方案。

策略：复用 qa/route_verify.py 的思路，
  1. 重新解析生产代码 NetEaseHomeApi.kt 的 trackToSong() `when{}` 块
  2. 把 qa/cache/playlists_60.json 的真实 60 首曲目喂进去
  3. 跑 fee 分组断言（fee=1 → migu, fee=0/8 → netease）

预期：C 方案已 commit+push，本轮只改 PlayerRepository/NeteaseMusicSource。
   NetEaseHomeApi.kt 的 trackToSong() 当 `fee ∈ {1,4} → migu; else → netease` 必须**仍然**有效。
"""
import os, re, json, sys

HERE = os.path.dirname(os.path.abspath(__file__))
KT = os.path.join(HERE, "..", "android", "app", "src", "main", "java",
                  "com", "soundtrack", "music", "home", "NetEaseHomeApi.kt")
CACHE = os.path.join(HERE, "cache", "playlists_60.json")


def opt_int(obj, key, default=0):
    if key not in obj or obj[key] is None:
        return default
    try:
        return int(obj[key])
    except (TypeError, ValueError):
        return default


def parse_kotlin_when():
    """从 NetEaseHomeApi.kt 解析 `val source = when { ... }` 的分支"""
    with open(KT, "r", encoding="utf-8") as f:
        src = f.read()
    m = re.search(r'val\s+source\s*=\s*when\s*\{', src)
    if not m:
        raise RuntimeError("could not find `val source = when {` in " + KT)
    i = m.end()
    depth = 1
    while i < len(src) and depth > 0:
        if src[i] == '{':
            depth += 1
        elif src[i] == '}':
            depth -= 1
        i += 1
    block = src[m.end():i - 1]

    branches = []
    for raw in block.splitlines():
        line = raw.split("//", 1)[0].strip()
        if not line or "->" not in line:
            continue
        cond, val = line.split("->", 1)
        cond = cond.strip()
        val = val.strip().strip('"')
        branches.append((None if cond == "else" else cond, val))
    return branches


def eval_route(branches, st, fee):
    env = {"st": st, "fee": fee}
    for cond, val in branches:
        if cond is None:
            return val
        py = cond.replace("||", " or ").replace("&&", " and ")
        py = re.sub(r'\btrue\b', 'True', py)
        py = re.sub(r'\bfalse\b', 'False', py)
        if eval(py, {"__builtins__": {}}, env):  # noqa: S307
            return val
    return None


def main():
    print("=" * 70)
    print("C 方案无回归验证 — trackToSong() 路由逻辑不变性")
    print("=" * 70)
    print(f"读取生产代码: {os.path.relpath(KT, HERE)}")

    # 1) 解析 when 块
    branches = parse_kotlin_when()
    print(f"\n解析到的 when 分支 (生产代码原文):")
    for cond, val in branches:
        print(f"  {('<else>' if cond is None else cond):28} -> {val}")

    # 2) 期望分支
    expected_branches = {("fee == 1 || fee == 4",): "migu", (None,): "netease"}
    actual_branches = set()
    for cond, val in branches:
        actual_branches.add((cond, val))
    has_migu_branch = any(val == "migu" for cond, val in branches)
    has_netease_else = any(cond is None and val == "netease" for cond, val in branches)
    print(f"\n  含 migu 分支: {has_migu_branch}")
    print(f"  含 else -> netease 分支: {has_netease_else}")

    # 3) 决策表
    print(f"\n--- 决策表 (optInt 默认 0 = 字段缺失) ---")
    print(f"  {'fee_in':<10} {'expected':<10} {'actual':<10} {'pass'}")
    shell_cases = [(-1, "netease"), (0, "netease"), (1, "migu"), (4, "migu"), (8, "netease"), (99, "netease")]
    r1_all = True
    for fee_in, exp in shell_cases:
        act = eval_route(branches, None, opt_int({"fee": fee_in}, "fee", 0))
        ok = (act == exp)
        r1_all &= ok
        print(f"  {fee_in:<10} {exp:<10} {act:<10} {'PASS' if ok else 'FAIL'}")

    # 4) 真实曲目数据
    if not os.path.exists(CACHE):
        print(f"\n未找到缓存: {CACHE}")
        print("请先跑 qa/route_verify.py 抓取数据")
        sys.exit(2)

    with open(CACHE, "r", encoding="utf-8") as f:
        tracks = json.load(f)

    print(f"\n--- R2: 真实曲目路由 (使用缓存 {len(tracks)} 首) ---")

    rows = []
    route_dist = {}
    for t in tracks:
        fee = opt_int(t, "fee", 0)
        src_r = eval_route(branches, None, fee)
        route_dist[src_r] = route_dist.get(src_r, 0) + 1
        rows.append((t.get("id"), t.get("name"), t.get("fee"), fee, src_r))

    print(f"  路由分布: {route_dist}")

    fee1 = [r for r in rows if r[2] == 1]
    fee0 = [r for r in rows if r[2] == 0]
    fee8 = [r for r in rows if r[2] == 8]
    fee4 = [r for r in rows if r[2] == 4]

    print(f"\n  fee=1 (VIP)        count={len(fee1):3d}  -> 路由集合 {sorted(set(r[4] for r in fee1))}")
    print(f"  fee=4 (专辑独占)    count={len(fee4):3d}  -> 路由集合 {sorted(set(r[4] for r in fee4))}")
    print(f"  fee=0 (免费)        count={len(fee0):3d}  -> 路由集合 {sorted(set(r[4] for r in fee0))}")
    print(f"  fee=8 (低音质)      count={len(fee8):3d}  -> 路由集合 {sorted(set(r[4] for r in fee8))}")

    ok_41 = len(fee1) >= 8 and all(r[4] == "migu" for r in fee1)
    ok_42 = len(fee0) >= 2 and all(r[4] == "netease" for r in fee0)
    ok_43 = len(fee8) >= 3 and all(r[4] == "netease" for r in fee8)
    ok_44 = len(fee4) >= 1 and all(r[4] == "migu" for r in fee4) if len(fee4) > 0 else True

    print(f"\n  V5.1 fee=1 -> migu    (>=8 命中, 全 migu): {'PASS' if ok_41 else 'FAIL'}")
    print(f"  V5.2 fee=0 -> netease (>=2 命中, 全 netease): {'PASS' if ok_42 else 'FAIL'}")
    print(f"  V5.3 fee=8 -> netease (>=3 命中, 全 netease): {'PASS' if ok_43 else 'FAIL'}")
    if len(fee4) > 0:
        print(f"  V5.4 fee=4 -> migu    (>=1 命中, 全 migu): {'PASS' if ok_44 else 'FAIL'}")

    print(f"\n{'=' * 70}")
    overall = r1_all and has_migu_branch and has_netease_else and ok_41 and ok_42 and ok_43 and ok_44
    print(f"OVERALL: {'PASS — C 方案未被本轮改动破坏' if overall else 'FAIL'}")
    print(f"{'=' * 70}")
    return 0 if overall else 1


if __name__ == "__main__":
    sys.exit(main())