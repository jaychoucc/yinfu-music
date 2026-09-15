package com.soundtrack.music

import android.app.Application
import com.soundtrack.music.data.PlaylistStore
import com.soundtrack.music.player.PlayerRepository
import com.soundtrack.music.source.SourceResolver
import com.soundtrack.music.util.CrashGuard

class SoundtrackApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(this)
        SourceResolver.init(this)
        // 直链失效自动重解析成功后，把新地址回填到本地歌单（一次写入 → 所有引用该歌的歌单同时生效）。
        // 只挂一次；未挂接时 PlayerRepository 行为与旧版完全一致。
        PlayerRepository.get(this).onUrlRefreshed = { song, url ->
            PlaylistStore.get(this).updatePlayUrl(song, url)
        }
    }
}
