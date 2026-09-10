package com.soundtrack.music.source

/**
 * 内置全部 57 个音源清单（与 musicdl 的客户端一一对应）。
 *
 * 每个源都由 Kotlin 原生实现，手机端完全独立，不依赖电脑端。
 * 由于各站的开放程度不同，用 [Cap] 标注真实能力，UI 必须诚实展示：
 *  - FULL    可搜索、可播放
 *  - PREVIEW 可搜索，但只能播放平台提供的试听片段（完整音轨需订阅/鉴权）
 *  - SEARCH  可搜索，但拿不到可播放地址（多为音频存在夸克网盘、或需 yt-dlp 级反混淆）
 *  - AUTH    需要用户自备账号凭据（token/cookie/app secret），未配置时不可用
 */
object BuiltinSources {

    enum class Cap { FULL, PREVIEW, SEARCH, AUTH }

    data class Meta(
        val id: String,
        val label: String,
        val group: String,       // core / cn / radio / overseas
        val native: Boolean,     // 是否有 Kotlin 原生实现（false 表示尚未移植）
        val cap: Cap = Cap.FULL
    )

    val ALL: List<Meta> = listOf(
        // --- 主力（core）---
        Meta("migu", "咪咕音乐", "core", native = true),
        Meta("netease", "网易云音乐", "core", native = true),
        Meta("kuwo", "酷我音乐", "core", native = true),
        Meta("qq", "QQ音乐", "core", native = true),
        Meta("kugou", "酷狗音乐", "core", native = true),
        Meta("qianqian", "千千音乐", "core", native = true),
        // --- 国内站点 / 聚合源（cn）---
        Meta("myfreemp3", "MyFreeMP3", "cn", native = true),
        Meta("gdstudio", "GD音乐台", "cn", native = true),
        Meta("gequbao", "歌曲宝", "cn", native = true),
        Meta("gequhai", "歌曲海", "cn", native = true),
        Meta("fivesing", "5sing原创", "cn", native = true),
        Meta("bilibili", "B站音频", "cn", native = true),
        Meta("yinyuedao", "音乐岛", "cn", native = true, cap = Cap.SEARCH),   // 音频在夸克网盘
        Meta("yinyueku", "音乐库", "cn", native = true),
        Meta("xiageba", "下歌吧", "cn", native = true, cap = Cap.SEARCH),     // 音频在夸克网盘
        Meta("xiaobai", "小白音乐", "cn", native = true),
        Meta("bodian", "波点音乐", "cn", native = true),
        Meta("soda", "汽水音乐", "cn", native = true),
        Meta("mitu", "米兔音乐", "cn", native = true),
        Meta("jbsou", "JBSou", "cn", native = true),
        Meta("tunehub", "TuneHub", "cn", native = true),
        Meta("mp3juice", "MP3Juice", "cn", native = true),
        Meta("itingwa", "爱听蛙", "cn", native = true),
        Meta("htqyy", "HTQYY音乐", "cn", native = true),
        Meta("kkws", "KKWS音乐", "cn", native = true, cap = Cap.SEARCH),      // 音频在夸克网盘
        Meta("liziyy", "栗子YY", "cn", native = true, cap = Cap.SEARCH),      // 音频在夸克网盘
        Meta("mgmp3", "MGMP3", "cn", native = true),
        Meta("sgogo", "Sgogo音乐", "cn", native = true),
        Meta("twot58", "2T58音乐", "cn", native = true),
        Meta("xmfwav", "XMFWAV", "cn", native = true),
        Meta("fangpi", "Fangpi音乐", "cn", native = true),
        Meta("buguyy", "不菇音乐", "cn", native = true),
        Meta("fivesong", "FiveSong", "cn", native = true, cap = Cap.SEARCH),  // 音频在夸克网盘
        Meta("livepoo", "LivePOO", "cn", native = true),
        Meta("moov", "MOOV音乐", "cn", native = true),
        Meta("lrts", "LRTS电台", "cn", native = true),
        // --- 播客电台（radio）---
        Meta("lizhi", "荔枝FM", "radio", native = true),
        Meta("qingting", "蜻蜓FM", "radio", native = true, cap = Cap.SEARCH), // 播放需账号 access_token
        Meta("ximalaya", "喜马拉雅", "radio", native = true),
        // --- 海外平台（overseas）---
        Meta("youtube", "YouTube Music", "overseas", native = true, cap = Cap.SEARCH), // 播放链接需 yt-dlp 级反混淆
        Meta("soundcloud", "SoundCloud", "overseas", native = true),
        Meta("spotify", "Spotify", "overseas", native = true, cap = Cap.AUTH),
        Meta("apple", "Apple Music", "overseas", native = true, cap = Cap.PREVIEW),    // 仅 30 秒试听
        Meta("itunes", "iTunes", "overseas", native = true),
        Meta("deezer", "Deezer", "overseas", native = true, cap = Cap.PREVIEW),        // 仅 30 秒试听
        Meta("tidal", "TIDAL", "overseas", native = true, cap = Cap.AUTH),
        Meta("qobuz", "Qobuz", "overseas", native = true, cap = Cap.AUTH),
        Meta("joox", "JOOX", "overseas", native = true),
        Meta("jiosaavn", "JioSaavn", "overseas", native = true),
        Meta("jamendo", "Jamendo", "overseas", native = true),
        Meta("audius", "Audius", "overseas", native = true),
        Meta("ccmixter", "ccMixter", "overseas", native = true),
        Meta("opengameart", "OpenGameArt", "overseas", native = true),
        Meta("wikimedia", "维基共享", "overseas", native = true),
        Meta("streetvoice", "StreetVoice", "overseas", native = true),
        Meta("suno", "Suno AI", "overseas", native = true, cap = Cap.AUTH),
        Meta("fma", "Free Music Archive", "overseas", native = true)
    )

    val BY_ID: Map<String, Meta> = ALL.associateBy { it.id }

    fun groupLabel(group: String): String = when (group) {
        "core" -> "主力音源"
        "cn" -> "国内聚合"
        "radio" -> "播客电台"
        "overseas" -> "海外平台"
        else -> group
    }

    /** 能力标签，直接展示给用户 */
    fun capLabel(cap: Cap): String = when (cap) {
        Cap.FULL -> "可搜可播"
        Cap.PREVIEW -> "仅试听片段"
        Cap.SEARCH -> "仅搜索 · 不可播"
        Cap.AUTH -> "需配置账号"
    }

    /** 该源是否能真正播放（用于搜索结果过滤/提示） */
    fun canPlay(meta: Meta): Boolean = meta.native && (meta.cap == Cap.FULL || meta.cap == Cap.PREVIEW)

    /** 默认勾选：已适配且可播放的源 */
    val DEFAULT_ACTIVE: Set<String> = ALL.filter { canPlay(it) }.map { it.id }.toSet()
}
