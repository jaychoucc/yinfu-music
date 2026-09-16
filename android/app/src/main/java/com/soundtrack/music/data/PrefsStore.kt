package com.soundtrack.music.data

import android.content.Context
import android.content.SharedPreferences

class PrefsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("soundtrack_prefs", Context.MODE_PRIVATE)

    var lastSearchKeyword: String
        get() = prefs.getString("last_keyword", "") ?: ""
        set(value) = prefs.edit().putString("last_keyword", value).apply()

    var activeSources: Set<String>
        get() = prefs.getStringSet("active_sources", null) ?: com.soundtrack.music.source.BuiltinSources.DEFAULT_ACTIVE
        set(value) = prefs.edit().putStringSet("active_sources", value).apply()

    /** 早期版本的"电脑端 web 服务端地址"字段（保留为兼容字段，
     *  手机端当前不依赖任何电脑端，但字段保留以便老用户数据不丢） */
    @Suppress("unused")
    var webServerUrl: String
        get() = prefs.getString("web_server_url", "") ?: ""
        set(value) = prefs.edit().putString("web_server_url", value).apply()

    /**
     * 搜索历史存储：保序的单个字符串。
     *
     * 早期版本用 putStringSet / getStringSet，但 SharedPreferences 的 getStringSet
     * 返回的是 HashSet（无序），导致历史词条顺序错乱（最近搜的并不在最前）。
     * 现改为存「\n 分隔的单一字符串」，取回时按分隔符切回 List，严格保持「最近在前」。
     *
     * 注：搜索关键词本身一般不含换行，但**剪贴板粘贴**可能把含内部换行的文本
     * （如「周杰伦\n晴天」）原样塞进输入框缓冲区；\n 是存储分隔符，内部换行会让
     * getHistory 切分时把一条词拆成两条从未单独搜过的条目。故 addHistory 入口统一把
     * 内部换行替换为空格，保证分隔符安全。
     *
     * 另注：同一个 key 由 StringSet 改为 String 不会崩，旧数据被 getString 以 null 读回
     * （类型不匹配），等价于空历史，符合预期。
     */
    private fun saveHistory(list: List<String>) {
        prefs.edit().putString(KEY_SEARCH_HISTORY, list.joinToString(SEPARATOR)).apply()
    }

    /** 加入一条搜索词：去重后置顶，最近在前，最多保留 20 条。 */
    fun addHistory(keyword: String) {
        // 单行 EditText 正常输入不含换行，但剪贴板粘贴可能带入内部换行；
        // \n 是存储分隔符，内部换行会让 getHistory 切分时把一条词拆成两条。
        // 统一替换为空格，保证分隔符安全。
        val kw = keyword.replace("\n", " ").replace("\r", " ").trim()
        if (kw.isEmpty()) return
        val list = getHistory().toMutableList()
        list.remove(kw)
        list.add(0, kw)
        saveHistory(list.take(MAX_HISTORY))
    }

    /** 读取搜索历史：最近在前；无历史返回空列表。 */
    fun getHistory(): List<String> {
        val raw = prefs.getString(KEY_SEARCH_HISTORY, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(SEPARATOR).filter { it.isNotEmpty() }
    }

    /** 删除单条搜索词并保序写回；不存在则不动。 */
    fun removeHistory(keyword: String) {
        val list = getHistory().toMutableList()
        if (list.remove(keyword)) {
            saveHistory(list)
        }
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_SEARCH_HISTORY).apply()
    }

    companion object {
        private const val KEY_SEARCH_HISTORY = "search_history"
        private const val SEPARATOR = "\n"
        private const val MAX_HISTORY = 20

        /** web 服务端默认留空；用户可在「我的」页配置以启用非原生源的代理解析 */
        const val DEFAULT_WEB_SERVER = ""
    }
}
