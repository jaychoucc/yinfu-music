package com.soundtrack.music.import_

import android.content.Context
import com.soundtrack.music.data.ImportMiss
import com.soundtrack.music.data.PlaylistModels
import com.soundtrack.music.data.PlaylistStore
import com.soundtrack.music.model.Song
import com.soundtrack.music.source.BuiltinSources
import com.soundtrack.music.source.NeteaseMusicSource
import com.soundtrack.music.source.SourceResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * 导入结果。
 *
 * @param playlistName 实际创建/追加的本地歌单名（网易云歌单名，超长时已截断到 [PlaylistModels.MAX_NAME_LEN]）
 * @param matched 成功匹配并加入歌单的歌曲数
 * @param total 网易云歌单解析出的原曲总数
 * @param misses 未匹配到的原曲（已写入对应歌单的 misses，「导入失败歌曲」聚合视图可见）
 */
data class ImportResult(
    val playlistName: String,
    val matched: Int,
    val total: Int,
    val misses: List<ImportMiss>
)

/**
 * 网易云歌单导入器（需求 2）。
 *
 * 流程：解析歌单链接/ID → 抓取网易云歌单 → 按歌单名创建本地歌单（同名则追加）→
 * 逐首在**全部可播放音源**中搜索候选并打分取最优 → 命中入歌单，未命中进 misses。
 *
 * 并发控制（两级信号量，避免几十个源同时请求被封）：
 *  - [MAX_SONG_CONCURRENCY]：同时匹配的歌曲数上限；
 *  - [MAX_SOURCE_CONCURRENCY]：单首歌搜索时的并发源数上限。
 *
 * 全程不抛业务异常（单首/单源失败一律吞掉）；仅 [urlOrId] 无法解析出 id 时抛 [IllegalArgumentException]
 * 供 UI 提示「链接格式不正确」。歌单抓不到（需要登录/链接错误）时返回 null。
 */
object PlaylistImporter {

    /** 匹配成功阈值：标题完全相等(100) + 歌手命中(20) + 时长接近(15) 至少要凑够 80 分。 */
    private const val MATCH_THRESHOLD = 80

    /** 同时匹配的歌曲数上限（歌曲间互相独立，4 并发足够且省电省流量）。 */
    private const val MAX_SONG_CONCURRENCY = 4

    /** 单首歌搜索全部候选源时的并发上限（可播放源有 40+ 个，全开会被风控）。 */
    private const val MAX_SOURCE_CONCURRENCY = 8

    /** 单源搜索超时（与搜索页的 10s 超时同量级）。 */
    private const val PER_SOURCE_SEARCH_TIMEOUT = 8_000L

    /** 单首歌「搜全部源」的总超时：慢源到期前返回的候选照常参与评分。 */
    private const val PER_SONG_TOTAL_TIMEOUT = 30_000L

    /** 每个源只需少量候选用于打分，拿前 5 条足够且更快。 */
    private const val SEARCH_PAGE_SIZE = 5

    /** 从链接或纯 ID 提取歌单 id；失败返回 null（UI 提示「链接格式不正确」）。 */
    private val ID_PATTERN = Regex("""[?&]id=(\d+)""")

    /**
     * 提取网易云歌单 id：支持纯数字、"https://y.music.163.com/m/playlist?id=3171524469&..." 等完整链接。
     * 优先匹配 query 参数 id=，其次把整串当纯数字解析。
     */
    fun extractPlaylistId(urlOrId: String): Long? {
        val raw = urlOrId.trim()
        if (raw.isEmpty()) return null
        ID_PATTERN.find(raw)?.let { return it.groupValues[1].toLongOrNull() }
        return raw.toLongOrNull()
    }

    /**
     * 导入网易云歌单。
     *
     * @param context 任意 Context（内部取 applicationContext 拿 PlaylistStore）
     * @param urlOrId 歌单链接或纯数字 id
     * @param onProgress 每匹配完一首回调（done/total/title），已在主线程回调，UI 直接刷文案
     * @param onPlaylistCreated 本地歌单创建/确定后回调（可用来刷新列表页）
     * @return 导入结果；歌单抓不到（链接错误 / 需要登录 / 网络失败）时返回 null
     * @throws IllegalArgumentException urlOrId 无法解析出 id（UI 提示「链接格式不正确」）
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun importNeteasePlaylist(
        context: Context,
        urlOrId: String,
        onProgress: (done: Int, total: Int, title: String) -> Unit,
        onPlaylistCreated: (playlistId: String) -> Unit
    ): ImportResult? = withContext(Dispatchers.IO) {
        // 1. 解析 id
        val playlistId = extractPlaylistId(urlOrId)
            ?: throw IllegalArgumentException("无法解析歌单 id：$urlOrId")

        // 2. 抓取网易云歌单
        val (name, tracks) = NeteaseMusicSource().fetchPlaylist(playlistId)
            ?: return@withContext null
        if (tracks.isEmpty()) {
            // 空歌单也算导入成功，只是什么都没匹配
            return@withContext ImportResult(name, matched = 0, total = 0, misses = emptyList())
        }

        val store = PlaylistStore.get(context)

        // 3. 创建本地歌单；同名已存在 → 追加到既有歌单（createPlaylist 的错误文案不直接失败）
        val localName = name.take(PlaylistModels.MAX_NAME_LEN)
        if (store.playlists().none { it.name.trim().equals(localName, ignoreCase = true) }) {
            store.createPlaylist(localName) // 校验失败的文案忽略，下一步统一按名查找
        }
        val targetId = store.playlists()
            .firstOrNull { it.name.trim().equals(localName, ignoreCase = true) }?.id
            ?: return@withContext null
        onPlaylistCreated(targetId)

        // 4. 逐首匹配（有界并发）：候选源 = 全部可播放源（FULL + PREVIEW）
        val candidateMetas = BuiltinSources.ALL.filter { BuiltinSources.canPlay(it) }
        val total = tracks.size
        val done = AtomicInteger(0)
        val misses = CopyOnWriteArrayList<ImportMiss>()
        val songSem = Semaphore(MAX_SONG_CONCURRENCY)
        val sourceSem = Semaphore(MAX_SOURCE_CONCURRENCY)

        coroutineScope {
            tracks.forEach { track ->
                launch {
                    songSem.withPermit {
                        ensureActive()
                        val best = runCatching { matchBest(track, candidateMetas, sourceSem) }.getOrNull()
                        if (best != null) {
                            // 命中：入歌单（playUrl 留空，播放时 PlayerRepository 解析）
                            runCatching { store.addToPlaylist(targetId, best) }
                        } else {
                            misses.add(
                                ImportMiss(
                                    title = track.title,
                                    artist = track.artist,
                                    durationSec = track.durationSec,
                                    fromPlaylistName = localName
                                )
                            )
                        }
                        val d = done.incrementAndGet()
                        withContext(Dispatchers.Main) { onProgress(d, total, track.title) }
                    }
                }
            }
        }

        // 5. 未匹配的落库（聚合到「导入失败歌曲」内置歌单的 allMisses 视图）
        val missList = misses.toList()
        if (missList.isNotEmpty()) runCatching { store.addMisses(targetId, missList) }

        ImportResult(
            playlistName = localName,
            matched = total - missList.size,
            total = total,
            misses = missList
        )
    }

    /**
     * 为一首原曲在全部候选源中搜索、打分，返回最高分且达到 [MATCH_THRESHOLD] 的候选；否则 null。
     *
     * 总超时 [PER_SONG_TOTAL_TIMEOUT] 兜底：慢源到期前已返回的候选照常参与评分，
     * 不会因为一个源卡死整首歌（更不会卡死整个导入）。
     */
    private suspend fun matchBest(
        track: Song,
        metas: List<BuiltinSources.Meta>,
        sourceSem: Semaphore
    ): Song? {
        val keyword = "${track.title} ${track.artist}".trim()
        if (keyword.isBlank()) return null
        val candidates = CopyOnWriteArrayList<Pair<Song, Int>>()

        withTimeoutOrNull(PER_SONG_TOTAL_TIMEOUT) {
            coroutineScope {
                metas.forEach { meta ->
                    launch {
                        runCatching {
                            sourceSem.withPermit {
                                val src = SourceResolver.resolve(meta.id) ?: return@launch
                                val list = withTimeoutOrNull(PER_SOURCE_SEARCH_TIMEOUT) {
                                    src.search(keyword, SEARCH_PAGE_SIZE)
                                } ?: return@launch
                                list.forEach { c ->
                                    score(track, c)?.let { candidates.add(c to it) }
                                }
                            }
                        }
                    }
                }
            }
        }

        return candidates.maxByOrNull { it.second }
            ?.takeIf { it.second >= MATCH_THRESHOLD }
            ?.first
    }

    /**
     * 候选打分（原始曲 → 候选 Song）。标题基础分不合格直接返回 null（淘汰）：
     *  - 标题完全相等（忽略大小写、去括号后缀/空格）→ 100；包含关系 → 60；否则淘汰
     *  - 歌手：原始歌手名全部出现在候选歌手中 → +20；部分交集 → +10；完全无交集 → -30；缺元数据 → 0
     *  - 时长：差 ≤3s → +15；≤8s → +8；≤20s → 0；>20s 或任一方时长 0 → -10
     *  - 品质：无损 +3 / 高品质 +2 / 标准 +1
     */
    private fun score(track: Song, candidate: Song): Int? {
        val t = normalizeTitle(track.title)
        val c = normalizeTitle(candidate.title)
        val base = when {
            t.isEmpty() || c.isEmpty() -> return null
            t == c -> 100
            t.contains(c) || c.contains(t) -> 60
            else -> return null // 0 分：0+20+15+3=38 < 80，不可能达标，直接淘汰省计算
        }

        var s = base

        // 歌手匹配
        val trackArtists = splitArtists(track.artist)
        val candArtists = splitArtists(candidate.artist)
        s += when {
            trackArtists.isEmpty() || candArtists.isEmpty() -> 0 // 缺元数据不奖惩
            trackArtists.all { a -> candArtists.any { it.contains(a, ignoreCase = true) } } -> 20
            trackArtists.any { a -> candArtists.any { it.contains(a, ignoreCase = true) } } -> 10
            else -> -30
        }

        // 时长匹配
        s += if (track.durationSec <= 0 || candidate.durationSec <= 0) {
            -10
        } else {
            val diff = abs(candidate.durationSec - track.durationSec)
            when {
                diff <= 3 -> 15
                diff <= 8 -> 8
                diff <= 20 -> 0
                else -> -10
            }
        }

        // 品质加权
        s += when (candidate.qualityLabel) {
            "无损" -> 3
            "高品质" -> 2
            else -> 1
        }

        return s
    }

    /** 标题归一化：去首尾空格、转小写、去括号后缀（（Live）/(伴奏)/【现场】…）、去内部空格。 */
    private fun normalizeTitle(s: String): String {
        var x = s.trim().lowercase()
        // 去掉成对括号及其内容：(...) （...） 【...】 [...]
        x = x.replace(Regex("""[\(（【\[][^)）】\]]*[\)）】\]]"""), "")
        // 去掉 " - live" 之类的分隔后缀
        x = x.replace(Regex("""\s[-—－]\s.*$"""), "")
        return x.replace(" ", "").replace("　", "")
    }

    /** 拆歌手名：按常见分隔符切分，保留非空项。 */
    private fun splitArtists(a: String): List<String> =
        a.split(",", "、", ";", "，", "/", " & ")
            .map { it.trim() }
            .filter { it.isNotBlank() }
}
