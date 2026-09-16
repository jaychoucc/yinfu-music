package com.soundtrack.music.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.soundtrack.music.R
import com.soundtrack.music.data.PlaylistModels

class MainActivity : AppCompatActivity() {

    /** 底部迷你播放器：退出播放页后仍能从这里回到播放页 */
    private lateinit var miniPlayer: MiniPlayerController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        miniPlayer = MiniPlayerController(this, findViewById(R.id.mini_player))
        miniPlayer.bind()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> showFragment(HomeFragment())
                R.id.nav_search -> showFragment(SearchFragment())
                R.id.nav_mine -> showFragment(MineFragment())
            }
            true
        }

        // 从「导入失败歌曲」详情页跳来：切到搜索 Tab 并把关键词带给 SearchFragment
        val searchKeyword = intent?.getStringExtra(PlaylistModels.EXTRA_SEARCH_KEYWORD)
        if (!searchKeyword.isNullOrBlank()) {
            bottomNav.selectedItemId = R.id.nav_search
            showFragment(SearchFragment.newInstance(searchKeyword))
        } else if (savedInstanceState == null) {
            showFragment(HomeFragment())
        }
    }

    /**
     * 复用已存在的 MainActivity 实例（FLAG_ACTIVITY_SINGLE_TOP）时走这里。
     * 同样切到搜索 Tab 并带关键词。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val keyword = intent.getStringExtra(PlaylistModels.EXTRA_SEARCH_KEYWORD)
        if (!keyword.isNullOrBlank()) {
            findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = R.id.nav_search
            showFragment(SearchFragment.newInstance(keyword))
        }
    }

    private fun showFragment(fragment: androidx.fragment.app.Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commitAllowingStateLoss()
    }

    /** 供 SearchFragment 跳转：切到「我的」Tab 配置 web 服务端 */
    fun switchToMineTab() {
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        bottomNav.selectedItemId = R.id.nav_mine
    }
}
