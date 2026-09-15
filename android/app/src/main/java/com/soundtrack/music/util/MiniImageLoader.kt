package com.soundtrack.music.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.*
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.WeakHashMap

/**
 * 自实现简单图片加载：内存 LRU + 磁盘目录缓存，不使用 Glide。
 */
class MiniImageLoader(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cacheDir = File(context.cacheDir, "mini_image_cache").apply { mkdirs() }

    private val memCache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * 每个 ImageView 当前「期望加载的 url」。
     *
     * 用于丢弃**过期**的异步结果：ViewHolder 被 RecyclerView 复用后，先前那次请求的 bitmap
     * 若晚于新请求返回，会把旧图盖到新行上（列表快速滑动时表现为「串封面」）。
     * 回调落地前比对本表即可把过期结果作废。
     *
     * 用 WeakHashMap 弱键：View 被回收后条目自动消失，不会滞留 Activity 的视图。
     */
    private val pendingUrl = Collections.synchronizedMap(WeakHashMap<ImageView, String>())

    fun load(url: String, view: ImageView, placeholderRes: Int? = null) {
        placeholderRes?.let { view.setImageResource(it) }
        if (url.isBlank()) {
            // 空 url = 撤销本 View 的期望；此前在途请求的结果到点后会被判为过期而丢弃
            pendingUrl.remove(view)
            return
        }
        // 登记本次期望（同一 View 反复 load 时以最后一次为准）
        pendingUrl[view] = url
        val key = md5(url)
        memCache.get(key)?.let {
            view.setImageBitmap(it)
            return
        }
        scope.launch {
            val bitmap = loadBitmap(key, url)
            withContext(Dispatchers.Main) {
                // 过期守卫：本 View 在这期间已被复用 / 换了 url → 丢弃，绝不覆盖
                if (pendingUrl[view] != url) return@withContext
                bitmap?.let {
                    view.setImageBitmap(it)
                }
            }
        }
    }

    /**
     * 同步取 bitmap。内部用 runBlocking，**会阻塞调用线程**，
     * 只能在后台线程调用；UI 线程请改用 loadBitmapAsync()。
     */
    fun loadBitmap(url: String): Bitmap? {
        if (url.isBlank()) return null
        val key = md5(url)
        memCache.get(key)?.let { return it }
        return runBlocking(Dispatchers.IO) { loadBitmap(key, url) }
    }

    /**
     * 异步取 bitmap，回调切回主线程。
     * 播放页切歌时会同步触发封面 + 模糊背景两次取图，
     * 用同步版会把主线程卡在 runBlocking 上（网络 + 解码），表现为切歌卡顿/ANR。
     */
    fun loadBitmapAsync(url: String, onReady: (Bitmap?) -> Unit) {
        if (url.isBlank()) {
            onReady(null)
            return
        }
        val key = md5(url)
        memCache.get(key)?.let {
            onReady(it)
            return
        }
        scope.launch {
            val bmp = loadBitmap(key, url)
            withContext(Dispatchers.Main) { onReady(bmp) }
        }
    }

    private fun loadBitmap(key: String, url: String): Bitmap? {
        val file = File(cacheDir, key)
        if (file.exists()) {
            try {
                BitmapFactory.decodeFile(file.absolutePath)?.let {
                    memCache.put(key, it)
                    return it
                }
            } catch (_: Exception) {
                file.delete()
            }
        }
        try {
            val req = Request.Builder().url(url).get().build()
            val resp = Net.client().newCall(req).execute()
            val body = resp.body?.bytes() ?: return null
            val bitmap = BitmapFactory.decodeByteArray(body, 0, body.size) ?: return null
            FileOutputStream(file).use { it.write(body) }
            memCache.put(key, bitmap)
            return bitmap
        } catch (_: Exception) {
            return null
        }
    }

    private fun md5(s: String): String {
        return MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
