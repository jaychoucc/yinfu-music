# -*- coding: utf-8 -*-
"""
QA (Edward) ROUND-2 independent verification of Request C fix.

This version does NOT hard-code the routing logic. It READS the production Kotlin
file, extracts the `val source = when { ... }` block inside trackToSong(), parses
each branch (`<cond> -> "<value>"` / `else -> "<value>"`), and evaluates those exact
conditions against real NetEase track data.

Variable semantics match org.json optInt(..., 0): an ABSENT field -> 0.

Data source is cached to qa/cache/playlists_60.json so re-runs don't re-crawl.
"""
import os, re, json, ssl, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
KT = os.path.join(HERE, "..", "android", "app", "src", "main", "java",
                  "com", "soundtrack", "music", "home", "NetEaseHomeApi.kt")
CACHE = os.path.join(HERE, "cache", "playlists_60.json")

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36",
    "Accept": "application/json, text/plain, */*",
    "Referer": "https://music.163.com/",
    "Origin": "https://music.163.com",
    "Accept-Language": "zh-CN,zh;q=0.9",
}
CTX = ssl.create_default_context()


def http_get(url, timeout=15):
    req = urllib.request.Request(url, headers=HEADERS)
    return urllib.request.urlopen(req, context=CTX, timeout=timeout).read().decode("utf-8", "ignore")


def opt_int(obj, key, default=0):
    if key not in obj or obj[key] is None:
        return default
    try:
        return int(obj[key])
    except (TypeError, ValueError):
        return default


# ---------------- read + parse the REAL Kotlin `when` ----------------
def parse_kotlin_when():
    with open(KT, "r", encoding="utf-8") as f:
        src = f.read()
    st_line_present = bool(re.search(r'\bval\s+st\s*=\s*t\.optInt\(\s*"st"', src))
    fee_line_present = bool(re.search(r'\bval\s+fee\s*=\s*t\.optInt\(\s*"fee"', src))

    # isolate the `val source = when {` ... matching `}` block
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

    branches = []  # list of (cond_or_None_for_else, value)
    for raw in block.splitlines():
        line = raw.split("//", 1)[0].strip()
        if not line or "->" not in line:
            continue
        cond, val = line.split("->", 1)
        cond = cond.strip()
        val = val.strip().strip('"')
        branches.append((None if cond == "else" else cond, val))
    return src, st_line_present, fee_line_present, branches


def eval_route(branches, st, fee):
    env = {"st": st, "fee": fee}
    for cond, val in branches:
        if cond is None:
            return val
        py = cond.replace("||", " or ").replace("&&", " and ")
        py = re.sub(r'\btrue\b', 'True', py)
        py = re.sub(r'\bfalse\b', 'False', py)
        if eval(py, {"__builtins__": {}}, env):   # noqa: S307 (local, trusted source)
            return val
    return None


# ---------------- data ----------------
def load_tracks():
    if os.path.exists(CACHE):
        with open(CACHE, "r", encoding="utf-8") as f:
            return json.load(f)
    pl = json.loads(http_get("https://music.163.com/api/personalized/playlist?limit=6"))
    tracks = []
    for p in (pl.get("result") or [])[:6]:
        pid = p.get("id")
        if not pid:
            continue
        d = json.loads(http_get("https://music.163.com/api/v6/playlist/detail?id=%d&n=100&s=0" % pid))
        for t in ((d.get("playlist") or {}).get("tracks") or [])[:30]:
            tracks.append({k: t.get(k) for k in ("id", "name", "st", "fee")})
    os.makedirs(os.path.dirname(CACHE), exist_ok=True)
    with open(CACHE, "w", encoding="utf-8") as f:
        json.dump(tracks, f, ensure_ascii=False, indent=1)
    return tracks


def main():
    src, st_line_present, fee_line_present, branches = parse_kotlin_when()

    print("=== R1: parsed REAL `when` from NetEaseHomeApi.kt ===")
    print("  file: %s" % os.path.relpath(KT, HERE))
    print("  `val st = t.optInt(\"st\", ...)` line present : %s" % st_line_present)
    print("  `val fee = t.optInt(\"fee\", ...)` line present: %s" % fee_line_present)
    print("  parsed branches (in order):")
    for cond, val in branches:
        print("    %-28s -> %s" % (("<else>" if cond is None else cond), val))

    # decision table (R1)
    print("\n--- R1 decision table (optInt default 0 = absent) ---")
    print("  %-14s %-10s %-9s %-9s %s" % ("fee_input", "expected", "actual", "pass", "eff_fee"))
    shell_cases = [(-1, "netease"), (0, "netease"), (1, "migu"), (4, "migu"), (8, "netease"), (99, "netease")]
    r1_all = True
    for fee_in, exp in shell_cases:
        eff = opt_int({"fee": fee_in}, "fee", 0)   # -1 present as int; absent also -> 0
        act = eval_route(branches, None, eff)
        ok = (act == exp)
        r1_all &= ok
        print("  %-14s %-10s %-9s %-9s %s" % (fee_in, exp, act, "PASS" if ok else "FAIL", eff))

    # ---------------- R2: real tracks ----------------
    tracks = load_tracks()
    print("\n=== R2: real-track routing (cached %d tracks) ===" % len(tracks))
    n = len(tracks)
    st_present = sum(1 for t in tracks if t.get("st") is not None)
    st_vals, fee_vals = {}, {}
    for t in tracks:
        st_vals[t.get("st")] = st_vals.get(t.get("st"), 0) + 1
        fee_vals[t.get("fee")] = fee_vals.get(t.get("fee"), 0) + 1
    print("  st present %d/%d ; raw st dist = %s" % (st_present, n, dict(sorted(st_vals.items(), key=lambda kv: (kv[0] is None, kv[0])))))
    print("  raw fee dist = %s" % dict(sorted(fee_vals.items(), key=lambda kv: (kv[0] is None, kv[0]))))

    rows = []
    route_dist = {}
    for t in tracks:
        st = opt_int(t, "st", 0)
        fee = opt_int(t, "fee", 0)
        src_r = eval_route(branches, st, fee)
        route_dist[src_r] = route_dist.get(src_r, 0) + 1
        rows.append((t.get("id"), t.get("name"), t.get("fee"), fee, src_r))
    print("  simulated route distribution: %s" % route_dist)

    def subset(fee_target):
        return [r for r in rows if r[2] == fee_target]

    fee1, fee0, fee8 = subset(1), subset(0), subset(8)
    print("\n  fee=1 count=%d -> %s" % (len(fee1), sorted(set(r[4] for r in fee1))))
    print("  fee=0 count=%d -> %s" % (len(fee0), sorted(set(r[4] for r in fee0))))
    print("  fee=8 count=%d -> %s" % (len(fee8), sorted(set(r[4] for r in fee8))))

    ok41 = len(fee1) >= 8 and all(r[4] == "migu" for r in fee1)
    ok42 = len(fee0) >= 2 and all(r[4] == "netease" for r in fee0)
    ok43 = len(fee8) >= 3 and all(r[4] == "netease" for r in fee8)
    print("\n  V4.1 fee=1 -> migu    (>=8): %s" % ("PASS" if ok41 else "FAIL"))
    print("  V4.2 fee=0 -> netease (>=2): %s" % ("PASS" if ok42 else "FAIL"))
    print("  V4.3 fee=8 -> netease (>=3): %s" % ("PASS" if ok43 else "FAIL"))

    print("\n=== ROUND-2 VERDICT ===")
    print("  R1 parsed-when table : %s" % ("PASS" if r1_all else "FAIL"))
    print("  st line removed      : %s" % ("PASS" if not st_line_present else "FAIL"))
    print("  R2 V4.1/4.2/4.3      : %s" % ("PASS" if (ok41 and ok42 and ok43) else "FAIL"))
    print("  OVERALL              : %s" % ("PASS" if (r1_all and not st_line_present and ok41 and ok42 and ok43) else "FAIL"))

    # sample rows
    print("\n  --- sample rows (first 8) ---")
    for r in rows[:8]:
        print("    id=%-11s fee=%-3s -> %-8s %s" % (r[0], r[2], r[4], (r[1] or "")[:24]))


if __name__ == "__main__":
    main()
