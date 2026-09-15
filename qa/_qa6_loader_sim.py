# -*- coding: utf-8 -*-
"""
QA6 · MiniImageLoader.load 过期异步结果守卫 —— 状态机级对抗仿真（纯标准库）

目的：不复读源码，而是**逐字复刻** `MiniImageLoader.load` 与异步回调的状态机语义
（pendingUrl 映射 + 同步命中 set + 异步回调 + 过期守卫），用真实交错序列断言
「该 View 最终显示的是谁」，并把**修复前的坏实现**喂进同一仿真做**可证伪**自证。

复刻的三种实现（语义来自源码 / 工程师自述）：
  GOOD            修复后：出现 pendingUrl 弱键登记；空 url -> pending.remove(view) 后 return；
                  非空 -> 先登记 pending[view]=url；命中 memCache 则同步 set；
                  未命中异步，回调里**先**比对 `pending[view] != url` 再 setImageBitmap。
  BAD_PREFIX      修复前：无 pendingUrl、无守卫；空 url 直接 return；命中同步 set；
                  未命中异步，回调**无条件** setImageBitmap。
  BAD_GUARD_AFTER 顺序反了的守卫：先 setImageBitmap，**再**比对（= 等于没修）。

运行：cd <仓库根> && python qa/_qa6_loader_sim.py
退出码：仿真自身期望全部成立 = 0；否则 = 1
"""
import sys

PLACEHOLDER = "PLACEHOLDER"   # 渐变占位（空封面 / 被撤销时的残留态）
BITMAP = lambda url: "BMP:" + url  # noqa: E731  模拟解码出的位图

passed = 0
failed = 0
failures = []


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print("  [PASS] " + name + ((" — " + detail) if detail else ""))
    else:
        failed += 1
        failures.append(name + ((" — " + detail) if detail else ""))
        print("  [FAIL] " + name + ((" — " + detail) if detail else ""))
    return cond


class Sim:
    """复刻 load 状态机。impl ∈ {GOOD, BAD_PREFIX, BAD_GUARD_AFTER}。"""

    def __init__(self, impl, hot=()):
        self.impl = impl
        self.hot = set(hot)        # 已在内存缓存里的 url（同步命中路径）
        self.pending = {}          # 复刻 pendingUrl: view -> 期望 url
        self.display = {}          # 复刻该 View 当前真正显示的位图/占位
        self.writes = []           # (view, applied) 逐次"真正落到 View 上"的写
        self.stale = []            # 被判过期而丢弃的 url

    # ---- 复刻 load(url, view) ----
    def load(self, view, url):
        if self.impl != "BAD_PREFIX" and url.strip() == "":
            # 修复后：空 url 先撤销期望（必须在 return 之前）
            self.pending.pop(view, None)
            self.display.setdefault(view, PLACEHOLDER)
            return None
        if self.impl == "BAD_PREFIX" and url.strip() == "":
            self.display.setdefault(view, PLACEHOLDER)
            return None
        # 非空：登记本次期望（弱键；GOOD/BAD_GUARD_AFTER 才有）
        if self.impl != "BAD_PREFIX":
            self.pending[view] = url
        self.display.setdefault(view, PLACEHOLDER)
        if url in self.hot:
            # 同步命中
            self._set(view, url)
            return None
        return ("req", view, url)

    # ---- 复刻异步回调（Dispatchers.Main 里那段）----
    def deliver(self, token):
        _, view, url = token
        if self.impl == "GOOD":
            if self.pending.get(view) != url:
                self.stale.append(url)      # 过期 -> 丢弃
                return
            self._set(view, url)
        elif self.impl == "BAD_GUARD_AFTER":
            self._set(view, url)            # 先写
            if self.pending.get(view) != url:
                self.stale.append(url)      # 再判（为时已晚）
        else:  # BAD_PREFIX
            self._set(view, url)

    def _set(self, view, url):
        self.writes.append((view, url))
        self.display[view] = BITMAP(url)

    def terminal(self, view):
        return self.display.get(view, PLACEHOLDER)


def run(impl, script, deliver_names):
    """script: [("load", view, url) | ("deliver", name)]；deliver_names: name -> token"""
    sim = Sim(impl)
    tokens = {}
    for step in script:
        if step[0] == "load":
            _, view, url = step
            tok = sim.load(view, url)
            if tok is not None:
                tokens[(view, url)] = tok
        else:
            _, name = step
            sim.deliver(tokens[name])
    return sim


# ======================================================================
# 场景定义：交错序列 + 期望（writes 序列 / 终态 / 是否丢弃过期）
# ======================================================================
# 为清晰起见每个场景单独构造（不同交错需要的 token 名不同）。
# 结果打印真值表，并对 GOOD 全部断言、对 BAD_* 断言必须被证伪。

print("=" * 70)
print("MiniImageLoader.load 过期守卫 —— 状态机仿真（GOOD vs 修复前坏实现）")
print("=" * 70)

# ---------------- 场景 1：经典串图（R_A 先返回，R_B 随后）----------------
def s1(impl):
    sim = Sim(impl)
    rA = sim.load("V", "urlA")   # 未命中，在途
    rB = sim.load("V", "urlB")   # V 被复用，未命中，在途
    sim.deliver(rA)              # R_A 先返回 -> 必须丢弃
    sim.deliver(rB)              # R_B 随后   -> 应用 B
    return sim

# ---------------- 场景 1b：经典"晚到覆盖"（R_B 先回，R_A 晚到）----------------
# 工程师自述的原始缺陷现象：行 A 的异步封面"晚到"，盖到已复用为行 B 的 View 上。
def s1b(impl):
    sim = Sim(impl)
    rA = sim.load("V", "urlA")
    rB = sim.load("V", "urlB")
    sim.deliver(rB)              # 当前期望 B 先落地
    sim.deliver(rA)              # A 晚到 -> 必须丢弃，终态必须仍是 B
    return sim

# ---------------- 场景 2：同步命中 + 晚到（最容易漏的交错）----------------
def s2(impl):
    sim = Sim(impl, hot=("urlB",))   # B 命中内存缓存
    rA = sim.load("V", "urlA")       # 在途
    sim.load("V", "urlB")            # 同步立即 set B 并登记
    sim.deliver(rA)                  # R_A 随后 -> 必须丢弃
    return sim

# ---------------- 场景 3：空 url 撤销 ----------------
def s3(impl):
    sim = Sim(impl)
    rA = sim.load("V", "urlA")       # 在途
    sim.load("V", "")                # 复用为无封面行
    sim.deliver(rA)                  # R_A 返回 -> 必须丢弃，保留占位
    return sim

# ---------------- 场景 4：同 url 重绑（守卫不得误伤）----------------
def s4(impl):
    sim = Sim(impl)
    r1 = sim.load("V", "urlA")
    r2 = sim.load("V", "urlA")       # 同 url 再绑
    sim.deliver(r1)
    sim.deliver(r2)
    return sim

# ---------------- 场景 5：正常单请求（回归：不许把正常路径挡掉）----------------
def s5(impl):
    sim = Sim(impl)
    rA = sim.load("V", "urlA")
    sim.deliver(rA)
    return sim


# ---------------- 场景 6：同一 View 连续切 3 首（A→B→C），乱序返回（对抗：不误伤）----------------
def s6(impl):
    sim = Sim(impl)
    rA = sim.load("V", "urlA")
    rB = sim.load("V", "urlB")
    rC = sim.load("V", "urlC")
    sim.deliver(rC)   # 最新一首先落地
    sim.deliver(rA)   # 最旧一首晚到
    sim.deliver(rB)   # 中间一首晚到
    return sim


SCEN = [
    ("S1  经典串图(R_A先回,R_B随后)",      s1,  [("V", "urlB")], BITMAP("urlB")),
    ("S1b 晚到覆盖(R_B先回,R_A晚到)",       s1b, [("V", "urlB")], BITMAP("urlB")),
    ("S2  同步命中+晚到(R_A随后)",          s2,  [("V", "urlB")], BITMAP("urlB")),
    ("S3  空url撤销(R_A随后)",              s3,  [],              PLACEHOLDER),
    ("S4  同url重绑(不得误伤)",             s4,  [("V", "urlA"), ("V", "urlA")], BITMAP("urlA")),
    ("S5  正常单请求(回归)",                s5,  [("V", "urlA")], BITMAP("urlA")),
    ("S6  同View连切3首乱序(最新C胜)",       s6,  [("V", "urlC")], BITMAP("urlC")),
]

print("\n--- A. 真实(修复后)实现 GOOD：全部必须 PASS ---")
good_results = {}
for name, fn, exp_writes, exp_terminal in SCEN:
    sim = fn("GOOD")
    ok_w = [w for w in sim.writes] == list(exp_writes)
    ok_t = sim.terminal("V") == exp_terminal
    ok = ok_w and ok_t
    good_results[name] = ok
    check("GOOD " + name, ok,
          "writes=%s terminal=%s(期望 %s) stale=%s" % (sim.writes, sim.terminal("V"), exp_terminal, sim.stale))

print("\n--- B. 修复前坏实现 BAD_PREFIX：S1/S1b/S2/S3 必须 FAIL，S4/S5 允许 PASS ---")
bad_prefix_fail = {}
for name, fn, exp_writes, exp_terminal in SCEN:
    sim = fn("BAD_PREFIX")
    ok = ([w for w in sim.writes] == list(exp_writes)) and (sim.terminal("V") == exp_terminal)
    bad_prefix_fail[name] = (not ok)
    print("      [%s] BAD_PREFIX %s — writes=%s terminal=%s(期望 %s)" %
          ("被证伪" if not ok else "通过", name, sim.writes, sim.terminal("V"), exp_terminal))

print("\n--- C. 守卫顺序反了 BAD_GUARD_AFTER：S1/S1b/S2/S3 也必须 FAIL（证明顺序有意义）---")
bad_after_fail = {}
for name, fn, exp_writes, exp_terminal in SCEN:
    sim = fn("BAD_GUARD_AFTER")
    ok = ([w for w in sim.writes] == list(exp_writes)) and (sim.terminal("V") == exp_terminal)
    bad_after_fail[name] = (not ok)
    print("      [%s] BAD_GUARD_AFTER %s — writes=%s terminal=%s(期望 %s)" %
          ("被证伪" if not ok else "通过", name, sim.writes, sim.terminal("V"), exp_terminal))

print("\n--- D. 可证伪性判定 ---")
must_fail = ["S1  经典串图(R_A先回,R_B随后)", "S1b 晚到覆盖(R_B先回,R_A晚到)",
             "S2  同步命中+晚到(R_A随后)", "S3  空url撤销(R_A随后)"]
check("D.1 GOOD 全部 5 场景 PASS（含回归 S4/S5）",
      all(good_results[name] for name, *_ in SCEN),
      "good_results=%s" % good_results)
check("D.2 BAD_PREFIX 在 S1/S1b/S2/S3 上全部被证伪（仿真有鉴别力）",
      all(bad_prefix_fail[n] for n in must_fail),
      "bad_prefix_fail=%s" % bad_prefix_fail)
check("D.3 BAD_GUARD_AFTER（顺序反）在 S1/S1b/S2/S3 上同样被证伪（判定必须早于 setImageBitmap）",
      all(bad_after_fail[n] for n in must_fail),
      "bad_guard_after_fail=%s" % bad_after_fail)
check("D.4 坏实现 S4/S5 PASS（不误伤正常/同url路径，只挡过期）",
      (not bad_prefix_fail["S4  同url重绑(不得误伤)"]) and (not bad_prefix_fail["S5  正常单请求(回归)"]),
      "S4_fail=%s S5_fail=%s" % (bad_prefix_fail["S4  同url重绑(不得误伤)"], bad_prefix_fail["S5  正常单请求(回归)"]))

print("\n" + "=" * 70)
print("仿真总计: %d PASS / %d FAIL" % (passed, failed))
if failures:
    for f in failures:
        print("  - " + f)
    sys.exit(1)
print("✅ 仿真期望全部成立（好实现全绿；坏实现在核心场景被证伪）")
sys.exit(0)
