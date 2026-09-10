package com.soundtrack.music.source

import com.soundtrack.music.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Qobuz 音乐源（手机端本地调用）。
 *
 * 重要限制（务必注意）：
 * Python 端 ref_all/qobuz.py 的搜索与解析都依赖 QobuzMusicClientUtils 里硬编码的
 * SEARCH_APP_ID / SEARCH_APP_SECRET（以及 PARSE_APP_ID / X-Session-Id 流程）。
 * 该工具类（..utils.qobuzutils）并未随 ref_all 一起打包，本项目里没有这份密钥。
 * 没有 app_id + request_sig 签名，Qobuz 的 catalog/search 接口会直接拒绝（401/403），
 * 移动端也无法在无密钥的情况下复刻，更【不能伪造】。
 *
 * 因此本实现是「诚实的空实现」：
 *   - search() 直接返回 emptyList()，并在注释说明需要 app_id/secret；
 *   - resolvePlayUrl() 返回 null；
 *   - fetchLyric() 返回 null。
 * 若后续要启用，需把 SEARCH_APP_ID/SEARCH_APP_SECRET（及解析用的 PARSE_APP_ID）以
 * 安全方式注入，再照 Python 端实现签名与 X-Session-Id 流程，不在本批范围内。
 */
class QobuzMusicSource : MusicSource {
    override val id = "qobuz"
    override val label = "Qobuz"

    override suspend fun search(keyword: String, pageSize: Int): List<Song> = withContext(Dispatchers.IO) {
        // 需要 app_id/secret 签名，未打包、移动端无法无密钥搜索。返回空，绝不伪造。
        emptyList()
    }

    override suspend fun resolvePlayUrl(song: Song): String? = withContext(Dispatchers.IO) {
        // 缺少 app_id/secret，无法取得真实播放链接
        null
    }

    override suspend fun fetchLyric(song: Song): String? = withContext(Dispatchers.IO) {
        null
    }
}
