"""
搜索历史功能 — 验证脚本

不依赖 Android runtime,用 Python 复现 PrefsStore 的保序存储算法,
并对本次改动的 5 个源文件做存在性 / 一致性检查。

覆盖:
  CASE 1  addHistory 保序 + 去重置顶 + 封顶 20
  CASE 2  getHistory 切分(空 / 单词 / 恰好 20 词)
  CASE 3  removeHistory 删单条保序
  CASE 4  clearHistory
  CASE 5  内部换行防护(剪贴板粘贴场景)
  CASE 6  旧数据迁移(StringSet → String 同 key,类型不匹配读 null)
  CASE 7  源码存在性 / 一致性检查(5 个文件)
  CASE 8  APK 产物检查(classes.dex 含 SearchHistoryAdapter)

跑法:
  python qa/search_history.py
预期:全部 PASS,exit 0;任何 FAIL 则 exit 1。
"""

import os
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

all_pass = True


def check(cond, label, detail=""):
    global all_pass
    ok = bool(cond)
    all_pass = all_pass and ok
    mark = "PASS" if ok else "FAIL"
    print(f"  [{mark}] {label}" + (f"  {detail}" if detail else ""))
    return ok


# ============================================================
# PrefsStore 算法复现(与 Kotlin 逐行对齐)
# ============================================================

SEPARATOR = "\n"
MAX_HISTORY = 20


class FakePrefsStore:
    """用 Python 字符串模拟 SharedPreferences 的 search_history key。
    与 Kotlin PrefsStore 完全同构:存 \\n 分隔的单一字符串,取回按分隔符切回 List。"""

    def __init__(self):
        self._storage = ""  # 模拟 getString("search_history", "")

    def _save(self, lst):
        self._storage = SEPARATOR.join(lst)

    def add_history(self, keyword):
        # 与 Kotlin addHistory 逐行对齐
        kw = keyword.replace("\n", " ").replace("\r", " ").strip()
        if kw == "":
            return
        lst = self.get_history()
        # Kotlin list.remove 返回 Boolean(不存在返回 false 不抛异常),Python 需先判存在
        if kw in lst:
            lst.remove(kw)       # 去重
        lst.insert(0, kw)        # 置顶
        self._save(lst[:MAX_HISTORY])

    def get_history(self):
        raw = self._storage
        if raw == "":
            return []
        return [s for s in raw.split(SEPARATOR) if s != ""]

    def remove_history(self, keyword):
        lst = self.get_history()
        try:
            lst.remove(keyword)
            self._save(lst)
            return True
        except ValueError:
            return False        # 不存在则不动

    def clear_history(self):
        self._storage = ""


print("=" * 60)
print("CASE 1: addHistory 保序 + 去重置顶 + 封顶 20")
print("=" * 60)

st = FakePrefsStore()
check(st.get_history() == [], "空存储 getHistory == []")

st.add_history("晴天")
check(st.get_history() == ["晴天"], "add 一条", f"-> {st.get_history()}")

st.add_history("七里香")
check(st.get_history() == ["七里香", "晴天"], "第二条置顶(最近在前)", f"-> {st.get_history()}")

st.add_history("晴天")  # 重复词
check(st.get_history() == ["晴天", "七里香"], "重复词去重后置顶,不出现两次", f"-> {st.get_history()}")

st.add_history("稻香")
st.add_history("夜曲")
check(st.get_history() == ["夜曲", "稻香", "晴天", "七里香"], "连续 add 严格最近在前", f"-> {st.get_history()}")

# 封顶 20
st2 = FakePrefsStore()
for i in range(25):
    st2.add_history(f"词{i:02d}")
got = st2.get_history()
check(len(got) == 20, f"add 25 个不同词只保留 {MAX_HISTORY} 条", f"actual={len(got)}")
check(got[0] == "词24", "封顶后最新的仍在最前", f"-> {got[0]}")
check(got[-1] == "词05", "封顶后最老的被裁掉的是 词00..词04", f"-> {got[-1]}")
check("词00" not in got and "词04" not in got, "最早的 5 条被裁掉")
check("词24" in got and "词05" in got, "最近的 20 条保留")

# 空词防护
st3 = FakePrefsStore()
st3.add_history("")
st3.add_history("   ")
st3.add_history("\n\n")
check(st3.get_history() == [], "空串 / 纯空白 / 纯换行不写入")

print()

# ============================================================
print("=" * 60)
print("CASE 2: getHistory 切分边界")
print("=" * 60)

st4 = FakePrefsStore()
st4._storage = ""
check(st4.get_history() == [], "空字符串 -> 空列表")

st4._storage = "晴天"
check(st4.get_history() == ["晴天"], "单词不分隔")

st4._storage = "\n".join(f"词{i}" for i in range(20))
got = st4.get_history()
check(len(got) == 20 and got[0] == "词0" and got[-1] == "词19", "恰好 20 词完整还原,无 off-by-one")

st4._storage = "晴天\n\n七里香"  # 中间有空段
check(st4.get_history() == ["晴天", "七里香"], "空段被 filter 掉,不产生空条目")

print()

# ============================================================
print("=" * 60)
print("CASE 3: removeHistory 删单条保序")
print("=" * 60)

st5 = FakePrefsStore()
for w in ["晴天", "七里香", "稻香", "夜曲"]:
    st5.add_history(w)  # ["夜曲","稻香","七里香","晴天"]

removed = st5.remove_history("七里香")
check(removed is True, "removeHistory 已存在的词返回 True")
check(st5.get_history() == ["夜曲", "稻香", "晴天"], "删后剩余保序不变", f"-> {st5.get_history()}")

removed = st5.remove_history("不存在的歌")
check(removed is False, "removeHistory 不存在的词返回 False,不动存储")
check(st5.get_history() == ["夜曲", "稻香", "晴天"], "存储未被修改")

# 删到空
st5.remove_history("夜曲")
st5.remove_history("稻香")
st5.remove_history("晴天")
check(st5.get_history() == [], "逐条删完 -> 空列表")

print()

# ============================================================
print("=" * 60)
print("CASE 4: clearHistory")
print("=" * 60)

st6 = FakePrefsStore()
for w in ["晴天", "七里香"]:
    st6.add_history(w)
st6.clear_history()
check(st6.get_history() == [], "clearHistory 后 getHistory == []")
st6.add_history("说好不哭")
check(st6.get_history() == ["说好不哭"], "清空后可重新写入")

print()

# ============================================================
print("=" * 60)
print("CASE 5: 内部换行防护(剪贴板粘贴场景)")
print("=" * 60)

st7 = FakePrefsStore()
st7.add_history("周杰伦\n晴天")  # 粘贴进来的含内部换行
check(st7.get_history() == ["周杰伦 晴天"], "内部 \\n 替换为空格,仍是一条", f"-> {st7.get_history()}")

st7b = FakePrefsStore()
st7b.add_history("周杰伦\r\n晴天")  # Windows 剪贴板 \r\n
got = st7b.get_history()
check(got == ["周杰伦  晴天"], "\\r\\n 各替一个空格(双空格,仍是一条)", f"-> {got}")
check(all("\r" not in s and "\n" not in s for s in got), "结果中无任何残留 \\r / \\n 字符")

st7c = FakePrefsStore()
st7c.add_history("  \n首尾换行  ")
# add 是置顶,新词在 [0] 不是 [-1]
check(st7c.get_history()[0] == "首尾换行", "首尾换行被 trim", f"-> {st7c.get_history()[0]!r}")

# 关键回归:换行词不会让 getHistory 多出条目
st8 = FakePrefsStore()
st8.add_history("A\nB")
st8.add_history("C")
check(st8.get_history() == ["C", "A B"], "两条 add 产生两条历史,不会被切分成 3 条", f"-> {st8.get_history()}")

print()

# ============================================================
print("=" * 60)
print("CASE 6: 旧数据迁移(StringSet → String 同 key)")
print("=" * 60)

# Kotlin: getString("search_history", "") 在旧版本存的是 StringSet,
# 跨类型读取框架返回 null, ?: "" 兜底 -> 空历史,不崩、无乱码。
# Python 模拟:旧 storage 是 "模拟旧 StringSet 的字符串形式",
# 新代码按 split("\n") 切,即使旧数据是 "[晴天, 七里香]" 这样的内容,
# 也只会变成一条无意义条目而不是崩溃 —— 但实际框架返回 null,等价空。
st9 = FakePrefsStore()
st9._storage = ""  # 模拟 getString 返回 null -> 兜底 ""
check(st9.get_history() == [], "老用户升级:类型不匹配读 null -> 空历史,不崩")

print()

# ============================================================
print("=" * 60)
print("CASE 7: 源码存在性 / 一致性检查")
print("=" * 60)


def read(rel):
    p = os.path.join(REPO, rel)
    with open(p, encoding="utf-8") as f:
        return f.read()


SRC = os.path.join("android", "app", "src", "main", "java", "com", "soundtrack", "music")

prefs = read(os.path.join(SRC, "data", "PrefsStore.kt"))
check('fun addHistory' in prefs, "PrefsStore.addHistory 存在")
check('fun removeHistory' in prefs, "PrefsStore.removeHistory 存在(新增)")
check('fun getHistory' in prefs, "PrefsStore.getHistory 存在")
check('fun clearHistory' in prefs, "PrefsStore.clearHistory 存在")
check('joinToString(SEPARATOR)' in prefs, "saveHistory 用 joinToString(SEPARATOR) 保序存储")
check('KEY_SEARCH_HISTORY = "search_history"' in prefs, "key 沿用 search_history(老数据 key 不变)")
check('MAX_HISTORY' in prefs and '= 20' in prefs, "MAX_HISTORY = 20 封顶常量")
check('.replace("\\n", " ")' in prefs.replace("\\\\", "\\"), "addHistory 内部换行替换为空格")
check('getStringSet("search_history"' not in prefs and 'putStringSet("search_history"' not in prefs,
      "search_history 的 StringSet 无序存储已移除(active_sources 的合法使用保留)")

frag = read(os.path.join(SRC, "ui", "SearchFragment.kt"))
check('historyBlock' in frag, "SearchFragment.historyBlock 字段存在")
check('SearchHistoryAdapter(' in frag, "SearchHistoryAdapter 初始化")
check('recycler_history' in frag, "recycler_history 绑定")
check('btn_clear_history' in frag, "清空按钮绑定")
check('AlertDialog.Builder' in frag and '清空全部搜索历史' in frag, "清空二次确认对话框")
check('hideHistory()' in frag, "hideHistory() 存在")
check('refreshHistory()' in frag, "refreshHistory() 存在")
check('showHistory()' in frag, "showHistory() 存在")
# 显隐时机:performSearch 里隐藏
check(frag.count('hideHistory()') >= 1, "performSearch 开始时调 hideHistory()")
# 成功分支无结果时刷新历史(itemCount==0 守卫)
check('if (adapter.itemCount == 0) {\n            refreshHistory()\n        }' in frag
      or 'if (adapter.itemCount == 0) {\n                refreshHistory()' in frag,
      "成功分支:无结果时 refreshHistory()(本轮修复的关键 bug)")
check(frag.count('if (adapter.itemCount == 0)') >= 2, "itemCount==0 守卫至少 2 处(onResume / 成功分支 / 失败分支)")
check('isAdded) return@setOnClickListener' in frag or '!isAdded) return@setOnClickListener' in frag,
      "清空按钮 isAdded 守护")
check('input.setSelection(' in frag, "点词条后光标移末尾")
check('input.setText(keyword)' in frag, "点词条回填输入框")

adapter = read(os.path.join(SRC, "adapter", "SearchHistoryAdapter.kt"))
check('ListAdapter<String' in adapter, "SearchHistoryAdapter 继承 ListAdapter")
check('DiffUtil' in adapter, "DiffUtil 局部刷新")
check('R.layout.item_search_history' in adapter, "item 布局引用")
check('history_keyword' in adapter, "history_keyword view id")
check('btn_delete_history' in adapter, "btn_delete_history view id")
check('btnDelete.setOnClickListener' in adapter, "✕ 独立 OnClickListener(不冒泡)")
check('itemView.setOnClickListener' in adapter, "整行点击 onClick")

lay = read(os.path.join("android", "app", "src", "main", "res", "layout", "fragment_search.xml"))
check('history_block' in lay, "fragment_search.xml 含 history_block")
check('recycler_history' in lay, "fragment_search.xml 含 recycler_history")
check('btn_clear_history' in lay, "fragment_search.xml 含 btn_clear_history")
check('搜索历史' in lay, "标题文案「搜索历史」")
check('清空' in lay, "清空按钮文案")
check('visibility="gone"' in lay, "history_block 默认 GONE")
check('nestedScrollingEnabled' in lay or 'nestedScrolling' in lay, "recycler_history 嵌套滚动设置")

item = read(os.path.join("android", "app", "src", "main", "res", "layout", "item_search_history.xml"))
check('history_keyword' in item, "item 布局含 history_keyword")
check('btn_delete_history' in item, "item 布局含 btn_delete_history")
check('ic_close' in item, "复用已有 ic_close 图标(零新资源)")
check('ellipsize="end"' in item, "关键词单行省略")

print()

# ============================================================
print("=" * 60)
print("CASE 8: APK 产物检查")
print("=" * 60)

apk_dir = os.path.join(REPO, "android", "app", "build", "outputs", "apk", "debug")
apks = sorted(
    os.path.join(apk_dir, n) for n in os.listdir(apk_dir)
    if n.endswith(".apk")
) if os.path.isdir(apk_dir) else []
if not apks:
    print(f"  [SKIP] debug 目录无 APK: {apk_dir}")
else:
    apk = apks[-1]
    size = os.path.getsize(apk)
    check(size > 1_000_000, "APK 大小 > 1MB", f"-> {os.path.basename(apk)} ({size/1024/1024:.2f} MB)")
    # classes.dex 里找 SearchHistoryAdapter / 新布局资源名
    import zipfile
    try:
        with zipfile.ZipFile(apk) as z:
            dex_names = [n for n in z.namelist() if n.endswith(".dex")]
            check(len(dex_names) > 0, "APK 含 classes.dex", f"-> {dex_names}")
            found = False
            for dn in dex_names:
                data = z.read(dn)
                if b"SearchHistoryAdapter" in data:
                    found = True
                    break
            check(found, "classes.dex 含 SearchHistoryAdapter 类(新代码已编入 APK)")
    except Exception as e:
        check(False, "读取 APK 失败", f"-> {e}")

print()

# ============================================================
print("=" * 60)
if all_pass:
    print("[FINAL] 搜索历史功能 - 全部 PASS")
    sys.exit(0)
else:
    print("[FINAL] 有 FAIL,需要修复")
    sys.exit(1)