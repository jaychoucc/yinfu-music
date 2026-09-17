package com.soundtrack.music.data

import android.content.Context
import android.content.SharedPreferences
import com.soundtrack.music.model.Song
import com.soundtrack.music.model.SongKeys
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地歌单仓库（单例）。
 *
 * 数据布局决策（§3.1）：**全局曲库 `songKey → Song 快照`** ＋
 * **每张歌单持有序 `songKey` 列表（含加入时间）**。
 * 好处：同一首歌在多张歌单只存一份元数据；直链回填「一次写入、全部歌单同时生效」；
 * 歌单内去重天然成立（key 出现一次即唯一）。
 *
 * 存储：`SharedPreferences("local_playlists") + org.json`，单 key 存整份 JSON（schema v1）。
 * 不引入 Room / 任何第三方库；损坏或未知版本 → 静默降级为「仅默认歌单 + 空曲库」，绝不崩溃。
 */
class PlaylistStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 全局曲库：songKey → 歌曲快照（一份，回填一次全歌单生效）。 */
    private val library = mutableMapOf<String, Song>()

    /** 所有歌单；顺序即展示顺序，默认歌单恒为首项。 */
    private val playlists = mutableListOf<LocalPlaylist>()

    /**
     * 读写锁：导入器会 8 路并发调用 [addToPlaylist]，而 [save] 会 forEach 遍历
     * playlists / entries / importMisses。不串行化会触发 ConcurrentModificationException
     * 或丢更新。所有变更方法都在此锁内「改数据 + 落盘」，通知听众放到锁外（同线程可重入无死锁风险）。
     */
    private val lock = Any()

    /** 轻量变更观察者（同屏刷新用；跨页仍以 onResume 重读为主）。 */
    private val listeners = mutableListOf<() -> Unit>()

    init {
        load()
    }

    companion object {
        /** 歌单数量上限，防止极端灌数据。 */
        const val MAX_PLAYLISTS = 100

        private const val PREFS_NAME = "local_playlists"
        private const val KEY_DATA = "data"

        @Volatile
        private var instance: PlaylistStore? = null

        fun get(context: Context): PlaylistStore = instance ?: synchronized(this) {
            instance ?: PlaylistStore(context.applicationContext).also { instance = it }
        }
    }

    // —— 读取 ——

    /** 所有歌单（默认歌单恒为首项）。返回副本，外部不可改内部状态。 */
    fun playlists(): List<LocalPlaylist> = playlists.map { p ->
        LocalPlaylist(
            id = p.id,
            name = p.name,
            builtin = p.builtin,
            createdAt = p.createdAt,
            entries = p.entries.toMutableList(),
            importMisses = p.importMisses.toMutableList()
        )
    }

    /** 歌单内的歌曲数（供「我的」页 / 列表页摘要展示）。 */
    fun songCount(playlistId: String): Int =
        playlists.firstOrNull { it.id == playlistId }?.entries?.size ?: 0

    /**
     * 歌单内的歌曲列表。
     *
     * 顺序按 [PlaylistEntry.addedAt] **倒序**（新加入的在前，§3.1 默认展示方向）；
     * 未命中曲库的 songKey 静默跳过（GC 之外不应出现，兜底防止脏 key 导致 null）。
     */
    fun songsOf(playlistId: String): List<Song> {
        val pl = playlists.firstOrNull { it.id == playlistId } ?: return emptyList()
        return pl.entries
            .sortedByDescending { it.addedAt }
            .mapNotNull { entry -> library[entry.songKey] }
    }

    /**
     * 歌单列表封面 URL（「我的歌单」列表页 / 「加入歌单」选择器行首图标）。
     *
     * 口径：取歌单内**最新加入**那首歌（复用 [songsOf] 的 addedAt 倒序，即第 0 项）的封面；
     * 该曲无封面则退取**第二首**（第 1 项）的封面；两者皆无 → null（UI 保留渐变占位）。
     * 严格两级，不继续向后回退。
     */
    fun coverUrlOf(playlistId: String): String? {
        val songs = songsOf(playlistId)
        return songs.getOrNull(0)?.coverUrl?.takeIf { it.isNotBlank() }
            ?: songs.getOrNull(1)?.coverUrl?.takeIf { it.isNotBlank() }
    }

    // —— 歌单 CRUD ——

    /** 新建歌单：成功返回 null，否则返回错误文案（空名/超长/同名/保留名/超上限）。 */
    fun createPlaylist(rawName: String): String? {
        synchronized(lock) {
            if (playlists.size >= MAX_PLAYLISTS) return "歌单数量已达上限（$MAX_PLAYLISTS 张）"
            val err = validateName(rawName, null)
            if (err != null) return err

            val name = rawName.trim()
            val now = System.currentTimeMillis()
            // 时间戳 + 序号生成稳定唯一 id（同一毫秒连续新建也不会撞）
            val id = "pl_${now.toString(36)}_${playlists.size + 1}"
            playlists.add(
                LocalPlaylist(
                    id, name, builtin = false, createdAt = now,
                    entries = mutableListOf()
                )
            )
            save()
            notifyChanged()
            return null
        }
    }

    /** 重命名歌单：成功（含「名称未变化」）返回 null，否则返回错误文案；默认歌单防御性拒绝。 */
    fun renamePlaylist(playlistId: String, rawName: String): String? {
        synchronized(lock) {
            val pl = playlists.firstOrNull { it.id == playlistId } ?: return "歌单不存在"
            // 防御性拒绝：默认歌单不可改名（UI 已隐藏入口，此处是第二道保险）
            if (pl.builtin) return "默认歌单不可重命名"
            // 名称未变化 → 直接成功、不产生变更
            if (pl.name.trim().equals(rawName.trim(), ignoreCase = true)) return null

            val err = validateName(rawName, playlistId)
            if (err != null) return err

            pl.name = rawName.trim()
            save()
            notifyChanged()
            return null
        }
    }

    /** 删除歌单：成功返回 true；默认歌单 / 不存在返回 false。删除后 GC 曲库。 */
    fun deletePlaylist(playlistId: String): Boolean {
        synchronized(lock) {
            val pl = playlists.firstOrNull { it.id == playlistId } ?: return false
            // 防御性拒绝：默认歌单不可删（UI 已隐藏入口，此处是第二道保险）
            if (pl.builtin) return false

            playlists.remove(pl)
            gcLibrary()
            save()
            notifyChanged()
            return true
        }
    }

    /** 名称是否已被占用（去首尾空格、忽略大小写）；默认歌单名「我喜欢的音乐」亦算占用。 */
    fun isNameTaken(rawName: String, excludeId: String?): Boolean {
        val name = rawName.trim()
        if (name.isEmpty()) return false
        return playlists.any { it.id != excludeId && it.name.trim().equals(name, ignoreCase = true) }
    }

    // —— 归属查询 / 变更 ——

    fun containsIn(playlistId: String, songKey: String): Boolean =
        playlists.firstOrNull { it.id == playlistId }?.entries?.any { it.songKey == songKey } ?: false

    /** 该 songKey 出现在哪些歌单（供选择器初始化勾选态）。 */
    fun playlistIdsContaining(songKey: String): Set<String> =
        playlists.filter { pl -> pl.entries.any { it.songKey == songKey } }
            .map { it.id }
            .toSet()

    /** 红心开关：在指定歌单中增 / 删这首歌；返回 true 表示操作后「已包含」。 */
    fun toggleIn(playlistId: String, song: Song): Boolean {
        val pl = playlists.firstOrNull { it.id == playlistId } ?: return false
        val key = SongKeys.of(song)
        val existing = pl.entries.firstOrNull { it.songKey == key }
        if (existing != null) {
            pl.entries.remove(existing)
            gcLibrary()
            save()
            notifyChanged()
            return false
        }
        putLibraryIfAbsent(key, song)
        pl.entries.add(PlaylistEntry(key, System.currentTimeMillis()))
        save()
        notifyChanged()
        return true
    }

    /**
     * 选择器确认：把 song 的归属**一次性置为**目标歌单集合（差量增删），只持久化一次。
     *
     * 「取消勾选」即从对应歌单移除；「勾选」即加入；未变化的歌单不动。
     */
    fun setMembership(song: Song, targetPlaylistIds: Set<String>) {
        synchronized(lock) {
            val key = SongKeys.of(song)
            var changed = false
            var needInLibrary = false
            val now = System.currentTimeMillis()

            for (pl in playlists) {
                val shouldContain = pl.id in targetPlaylistIds
                val entry = pl.entries.firstOrNull { it.songKey == key }
                when {
                    shouldContain && entry == null -> {
                        pl.entries.add(PlaylistEntry(key, now))
                        needInLibrary = true
                        changed = true
                    }
                    !shouldContain && entry != null -> {
                        pl.entries.remove(entry)
                        changed = true
                    }
                }
            }

            if (needInLibrary) putLibraryIfAbsent(key, song)
            if (changed) {
                gcLibrary()
                save()
                notifyChanged()
            }
        }
    }

    /** 追加到指定歌单（已存在则幂等跳过）。 */
    fun addToPlaylist(playlistId: String, song: Song) {
        synchronized(lock) {
            val pl = playlists.firstOrNull { it.id == playlistId } ?: return
            val key = SongKeys.of(song)
            if (pl.entries.any { it.songKey == key }) return
            putLibraryIfAbsent(key, song)
            pl.entries.add(PlaylistEntry(key, System.currentTimeMillis()))
            save()
            notifyChanged()
        }
    }

    /** 从指定歌单移除一首歌（默认歌单也允许移除；Q2 裁定）。 */
    fun removeFromPlaylist(playlistId: String, songKey: String) {
        synchronized(lock) {
            val pl = playlists.firstOrNull { it.id == playlistId } ?: return
            val removed = pl.entries.removeAll { it.songKey == songKey }
            if (!removed) return
            gcLibrary()
            save()
            notifyChanged()
        }
    }

    /**
     * 直链回填：由 PlayerRepository.onUrlRefreshed 调用。
     * 只更新全局曲库那一条 → 所有引用该歌的歌单同时生效（§7.8）。
     */
    fun updatePlayUrl(song: Song, newUrl: String) {
        synchronized(lock) {
            val key = SongKeys.of(song)
            val target = library[key] ?: song.copy().also { library[key] = it }
            target.playUrl = newUrl
            // 让当前正在播放的对象也一致（通常是同一个快照，此处为兜底）
            if (song !== target) song.playUrl = newUrl
            save()
            notifyChanged()
        }
    }

    // —— 导入失败条目（misses）——

    /**
     * 聚合所有歌单的导入失败条目（供「导入失败歌曲」内置歌单展示）。
     *
     * 「导入失败歌曲」歌单本身不放歌曲条目（[songsOf] 恒空），其内容就是这份聚合列表。
     * 顺序为歌单顺序 + 各歌单内追加顺序；歌单被删除时其 misses 随之消失（不残留孤儿数据）。
     */
    fun allMisses(): List<ImportMiss> = playlists.flatMap { it.importMisses.toList() }

    /** 指定歌单的失败条目（重复导入时用于判定该曲是否已在失败列表，避免重搜）。 */
    fun missesOf(playlistId: String): List<ImportMiss> =
        playlists.firstOrNull { it.id == playlistId }?.importMisses?.toList() ?: emptyList()

    /** 追加失败条目到指定歌单（同歌单内按全字段去重，避免重复导入同一歌单产生重复条目）。 */
    fun addMisses(playlistId: String, misses: List<ImportMiss>) {
        synchronized(lock) {
            if (misses.isEmpty()) return
            val pl = playlists.firstOrNull { it.id == playlistId } ?: return
            val toAdd = misses.filter { m -> pl.importMisses.none { it == m } }
            if (toAdd.isEmpty()) return
            pl.importMisses.addAll(toAdd)
            save()
            notifyChanged()
        }
    }

    /** 移除一条失败记录（按全字段匹配；聚合视图里同一个 miss 只会出现一次）。 */
    fun removeMiss(miss: ImportMiss) {
        synchronized(lock) {
            var changed = false
            playlists.forEach { pl ->
                if (pl.importMisses.removeAll { it == miss }) changed = true
            }
            if (changed) {
                save()
                notifyChanged()
            }
        }
    }

    /** 清空指定歌单的全部失败记录（删除歌单时其 misses 随歌单对象一起移除，通常无需单独调）。 */
    fun clearMissesOf(playlistId: String) {
        synchronized(lock) {
            val pl = playlists.firstOrNull { it.id == playlistId } ?: return
            if (pl.importMisses.isEmpty()) return
            pl.importMisses.clear()
            save()
            notifyChanged()
        }
    }

    /**
     * 保证存在 id=[PlaylistModels.FAILED_PLAYLIST_ID] 的**内置歌单**（恒在、不可删不可改名）。
     *
     * 它本身不放歌曲条目（[songsOf] 恒空），UI 通过 [allMisses] 展示其内容。
     * 在 [ensureDefault] 之后调用，加载与首次安装时各执行一次。
     */
    private fun ensureFailedPlaylist() {
        if (playlists.any { it.id == PlaylistModels.FAILED_PLAYLIST_ID }) return
        playlists.add(
            LocalPlaylist(
                id = PlaylistModels.FAILED_PLAYLIST_ID,
                name = PlaylistModels.FAILED_PLAYLIST_NAME,
                builtin = true,
                createdAt = 0L,
                entries = mutableListOf(),
                importMisses = mutableListOf()
            )
        )
    }

    // —— 变更通知（轻量，可选）——

    fun addChangeListener(l: () -> Unit) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeChangeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    private fun notifyChanged() {
        listeners.toList().forEach { runCatching { it() } }
    }

    // —— 内部：校验 / 曲库 / 持久化 ——

    /** 名称校验：去首尾空格 → 非空 → 长度 ≤ 20 → 不重名（忽略大小写）。 */
    private fun validateName(rawName: String, excludeId: String?): String? {
        val name = rawName.trim()
        if (name.isEmpty()) return "歌单名称不能为空"
        if (name.length > PlaylistModels.MAX_NAME_LEN) return "歌单名称最多 ${PlaylistModels.MAX_NAME_LEN} 个字"
        if (isNameTaken(name, excludeId)) return "已有同名歌单"
        return null
    }

    /** 仅当曲库没有该 key 时写入快照；已有则保留（避免新搜索结果的空直链覆盖已缓存的直链）。 */
    private fun putLibraryIfAbsent(key: String, song: Song) {
        val existing = library[key]
        if (existing == null) {
            library[key] = song.copy()
        } else if (existing.playUrl.isBlank() && song.playUrl.isNotBlank()) {
            existing.playUrl = song.playUrl
        }
    }

    /** 剔除不再被任何歌单引用的曲库条目（默认歌单不可删，故其引用的歌永远保留）。 */
    private fun gcLibrary() {
        val referenced = HashSet<String>()
        playlists.forEach { pl -> pl.entries.forEach { referenced.add(it.songKey) } }
        library.keys.retainAll(referenced)
    }

    /**
     * 从 SharedPreferences 载入。
     *
     * 健壮性（5.9 / 5.10 / AC-33）：
     *  - 无数据 → 首次安装，自动建默认歌单并落盘；
     *  - 任何解析异常 / 未知 schema 版本 → 降级为「仅默认歌单 + 空曲库」，静默、不崩溃，并自愈落盘。
     */
    private fun load() {
        playlists.clear()
        library.clear()
        var needSave = false

        val raw = prefs.getString(KEY_DATA, null)
        if (raw.isNullOrBlank()) {
            needSave = true
        } else {
            val ok = try {
                val root = JSONObject(raw)
                val version = root.optInt("version", PlaylistModels.SCHEMA_VERSION)
                if (version != PlaylistModels.SCHEMA_VERSION) {
                    throw IllegalArgumentException("unsupported schema version: $version")
                }
                parseV1(root)
                true
            } catch (_: Throwable) {
                false
            }
            if (!ok) {
                // 降级：丢弃损坏数据，仅保留默认歌单
                playlists.clear()
                library.clear()
                needSave = true
            }
        }

        ensureDefault()
        ensureFailedPlaylist()
        if (needSave) save()
    }

    /** 解析 schema v1 的 JSON（结构见架构文档 §3.5）。 */
    private fun parseV1(root: JSONObject) {
        val arr = root.optJSONArray("playlists") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val id = p.optString("id")
            if (id.isBlank()) continue
            val entries = mutableListOf<PlaylistEntry>()
            val eArr = p.optJSONArray("entries") ?: JSONArray()
            for (j in 0 until eArr.length()) {
                val e = eArr.optJSONObject(j) ?: continue
                val key = e.optString("key")
                if (key.isBlank()) continue
                entries.add(PlaylistEntry(key, e.optLong("addedAt", 0L)))
            }
            // 导入失败条目（schema v1 内的新增字段；老数据没有 "misses" 数组 → 空列表，向后兼容）
            val misses = mutableListOf<ImportMiss>()
            val mArr = p.optJSONArray("misses") ?: JSONArray()
            for (j in 0 until mArr.length()) {
                val m = mArr.optJSONObject(j) ?: continue
                misses.add(
                    ImportMiss(
                        title = m.optString("title"),
                        artist = m.optString("artist"),
                        durationSec = m.optInt("durationSec", 0),
                        fromPlaylistName = m.optString("fromPlaylistName")
                    )
                )
            }
            playlists.add(
                LocalPlaylist(
                    id = id,
                    name = p.optString("name"),
                    builtin = p.optBoolean("builtin", false),
                    createdAt = p.optLong("createdAt", 0L),
                    entries = entries,
                    importMisses = misses
                )
            )
        }

        val songsObj = root.optJSONObject("songs") ?: JSONObject()
        val keys = songsObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val o = songsObj.optJSONObject(key) ?: continue
            library[key] = songFromJson(o)
        }
    }

    /** 保证默认歌单存在且恒为首项。 */
    private fun ensureDefault() {
        val idx = playlists.indexOfFirst { it.id == PlaylistModels.DEFAULT_PLAYLIST_ID }
        if (idx >= 0) {
            if (idx > 0) {
                val def = playlists.removeAt(idx)
                playlists.add(0, def)
            }
            return
        }
        playlists.add(
            0,
            LocalPlaylist(
                id = PlaylistModels.DEFAULT_PLAYLIST_ID,
                name = PlaylistModels.DEFAULT_PLAYLIST_NAME,
                builtin = true,
                createdAt = 0L,
                entries = mutableListOf()
            )
        )
    }

    /** 整份 JSON 落盘（`apply()` 异步写，与既有 PrefsStore / PlayerRepository 范式一致）。 */
    private fun save() {
        try {
            val root = JSONObject()
            root.put("version", PlaylistModels.SCHEMA_VERSION)

            val arr = JSONArray()
            playlists.forEach { p ->
                val po = JSONObject()
                po.put("id", p.id)
                po.put("name", p.name)
                po.put("builtin", p.builtin)
                po.put("createdAt", p.createdAt)
                val eArr = JSONArray()
                p.entries.forEach { e ->
                    val eo = JSONObject()
                    eo.put("key", e.songKey)
                    eo.put("addedAt", e.addedAt)
                    eArr.put(eo)
                }
                po.put("entries", eArr)
                // 导入失败条目（可能为空数组；读取端缺失时按空处理）
                val mArr = JSONArray()
                p.importMisses.forEach { m ->
                    val mo = JSONObject()
                    mo.put("title", m.title)
                    mo.put("artist", m.artist)
                    mo.put("durationSec", m.durationSec)
                    mo.put("fromPlaylistName", m.fromPlaylistName)
                    mArr.put(mo)
                }
                po.put("misses", mArr)
                arr.put(po)
            }
            root.put("playlists", arr)

            val songs = JSONObject()
            library.forEach { (key, song) -> songs.put(key, songToJson(song)) }
            root.put("songs", songs)

            prefs.edit().putString(KEY_DATA, root.toString()).apply()
        } catch (_: Throwable) {
            // 本地存储异常时静默：写入失败不应影响任何功能
        }
    }

    private fun songToJson(s: Song): JSONObject = JSONObject().apply {
        put("songId", s.songId)
        put("source", s.source)
        put("title", s.title)
        put("artist", s.artist)
        put("album", s.album)
        put("durationSec", s.durationSec)
        put("coverUrl", s.coverUrl)
        put("playUrl", s.playUrl)
        put("lrc", s.lrc)
        put("ext", s.ext)
        put("fileSizeBytes", s.fileSizeBytes)
        put("bitrate", s.bitrate)
        put("token", s.token)
    }

    private fun songFromJson(j: JSONObject): Song = Song(
        songId = j.optString("songId"),
        source = j.optString("source"),
        title = j.optString("title"),
        artist = j.optString("artist"),
        album = j.optString("album"),
        durationSec = j.optInt("durationSec"),
        coverUrl = j.optString("coverUrl"),
        playUrl = j.optString("playUrl"),
        lrc = j.optString("lrc"),
        ext = j.optString("ext"),
        fileSizeBytes = j.optLong("fileSizeBytes"),
        bitrate = j.optInt("bitrate"),
        token = j.optString("token")
    )
}
