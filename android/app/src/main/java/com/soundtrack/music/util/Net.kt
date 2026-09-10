package com.soundtrack.music.util

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object Net {

    /**
     * 进程内 CookieJar。
     *
     * 很多聚合站（gequhai / gequbao / fangpi / jbsou 等）要求"先访问列表页拿到会话 cookie，
     * 再 POST 直链接口"，不带 cookie 会返回空 data。
     * 这里用内存实现，避免引入 okhttp3-urlconnection 依赖。
     * cookie 按 host 隔离，不同音源之间不会互相污染。
     */
    private class MemoryCookieJar : CookieJar {
        private val store = ConcurrentHashMap<String, MutableSet<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            val bucket = store.getOrPut(url.host) { LinkedHashSet() }
            synchronized(bucket) {
                // 同 domain+path+name 的 cookie 覆盖更新
                for (c in cookies) {
                    bucket.removeIf { it.name == c.name && it.path == c.path }
                    if (c.expiresAt > System.currentTimeMillis()) bucket.add(c)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val bucket = store[url.host] ?: return emptyList()
            val now = System.currentTimeMillis()
            val out = ArrayList<Cookie>()
            synchronized(bucket) {
                bucket.removeIf { it.expiresAt <= now }
                for (c in bucket) {
                    if (c.matches(url)) out.add(c)
                }
            }
            return out
        }
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(MemoryCookieJar())
            .build()
    }

    @JvmStatic
    fun client(): OkHttpClient = client
}
