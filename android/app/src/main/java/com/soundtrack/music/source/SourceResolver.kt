package com.soundtrack.music.source

import android.content.Context

/**
 * 音源注册中心：手机端**完全独立**，不依赖任何外部代理。
 *
 * musicdl 的全部 57 个客户端均已移植为 Kotlin 原生实现。
 * 各源的**真实能力**由 BuiltinSources.Meta.cap 描述：
 *   FULL 可搜可播 / PREVIEW 仅试听片段 / SEARCH 仅搜索不可播 / AUTH 需配置账号
 * UI 必须如实展示，不要虚标为"已适配"就完事。
 *
 * 新增源：实现 MusicSource 类 → 加进下面的 nativeSources →
 * 在 BuiltinSources.ALL 里把对应 Meta 的 native 置 true 并标注正确的 cap。
 */
object SourceResolver {

    private val nativeSources: List<MusicSource> = listOf(
        // --- 主力（core）---
        MiguMusicSource(),
        NeteaseMusicSource(),
        KuwoMusicSource(),
        QQMusicSource(),
        KugouMusicSource(),
        QianqianMusicSource(),
        // --- 国内聚合（cn）---
        MyFreeMP3MusicSource(),
        GDStudioMusicSource(),
        GequbaoMusicSource(),
        GequhaiMusicSource(),
        FivesingMusicSource(),
        BilibiliMusicSource(),
        YinyuedaoMusicSource(),
        YinyuekuMusicSource(),
        XiagebaMusicSource(),
        XiaobaiMusicSource(),
        BodianMusicSource(),
        SodaMusicSource(),
        MituMusicSource(),
        JBSouMusicSource(),
        TuneHubMusicSource(),
        MP3JuiceMusicSource(),
        ItingwaMusicSource(),
        HtqyyMusicSource(),
        KkwsMusicSource(),
        LiziyyMusicSource(),
        Mgmp3MusicSource(),
        SgogoMusicSource(),
        Twot58MusicSource(),
        XmfwavMusicSource(),
        FangpiMusicSource(),
        BuguyyMusicSource(),
        FivesongMusicSource(),
        LivePOOMusicSource(),
        MoovMusicSource(),
        LrtsMusicSource(),
        // --- 播客电台（radio）---
        LizhiMusicSource(),
        QingtingMusicSource(),
        XimalayaMusicSource(),
        // --- 海外平台（overseas）---
        YouTubeMusicSource(),
        SoundCloudMusicSource(),
        SpotifyMusicSource(),
        AppleMusicSource(),
        ItunesMusicSource(),
        DeezerMusicSource(),
        TidalMusicSource(),
        QobuzMusicSource(),
        JooxMusicSource(),
        JiosaavnMusicSource(),
        JamendoMusicSource(),
        AudiusMusicSource(),
        CcMixterMusicSource(),
        OpenGameArtMusicSource(),
        WikimediaCommonsMusicSource(),
        StreetvoiceMusicSource(),
        SunoMusicSource(),
        FMAMusicSource(),
    )

    @Volatile
    var registry: SourceRegistry = SourceRegistry(nativeSources)
        private set

    fun init(context: Context) {
        appContextRef = context.applicationContext
        refresh()
    }

    /** 仅用于兼容旧代码，不再有 web 配置 */
    @Suppress("unused")
    fun webServerUrl(): String? = null

    @Suppress("unused")
    fun isWebConfigured(): Boolean = false

    /**
     * 按 id 解析 MusicSource：未实现的源直接返回 null。
     * 搜索/播放器据此跳过不存在的源，不会出现"勾了却搜不到"的失望。
     */
    fun resolve(id: String): MusicSource? = nativeSources.find { it.id == id }

    /** 重新构建 registry（一般在 init 时调用一次；后续加新源可重调用） */
    fun refresh() {
        registry = SourceRegistry(nativeSources)
    }

    @Suppress("unused")
    fun refreshWebSources(): Boolean = false

    fun groupDisplayName(group: String): String = BuiltinSources.groupLabel(group)

    @Volatile
    private var appContextRef: Context? = null
}
