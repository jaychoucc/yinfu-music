package com.soundtrack.music

import android.app.Application
import android.os.Build
import com.soundtrack.music.source.SourceResolver
import com.soundtrack.music.util.CrashGuard

class SoundtrackApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(this)
        SourceResolver.init(this)
    }
}
