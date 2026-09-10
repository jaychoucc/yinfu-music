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

    fun addHistory(keyword: String) {
        val set = linkedSetOf<String>()
        set.add(keyword)
        set.addAll(getHistory())
        prefs.edit().putStringSet("search_history", set.take(20).toSet()).apply()
    }

    fun getHistory(): List<String> {
        return prefs.getStringSet("search_history", emptySet())?.toList() ?: emptyList()
    }

    fun clearHistory() {
        prefs.edit().remove("search_history").apply()
    }

    companion object {
        /** web 服务端默认留空；用户可在「我的」页配置以启用非原生源的代理解析 */
        const val DEFAULT_WEB_SERVER = ""
    }
}
