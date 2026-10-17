package com.zai.zcode

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.zai.zcode.feature.uuremote.UuRemoteDeepLinkHub
import com.zai.zcode.ui.AppNavHost
// 修复：ui.theme 包内是旧壳主题 ZcodemobileTheme；feature 模块统一用 core/design 的
// ZcodeTheme，宿主也用它保证全 App 主题一致
import com.zai.zcode.core.design.ZcodeTheme
// 修复：缺 AndroidEntryPoint 导入导致 Unresolved（app 模块已含 hilt 依赖）
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 单 Activity 宿主：Compose NavHost 承载 connect → sessions → chat。
 * UU 远程回跳深链（zcode://uuremote/...）在 onNewIntent 转发给路由，
 * 由 UuRemoteDeepLinkHub 单例分发到挂起的 Deferred。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var deepLinkHub: UuRemoteDeepLinkHub

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 冷启动也走一次转发（深链拉起 App 的场景 onNewIntent 不会触发）
        forwardDeepLink(intent)
        setContent {
            ZcodeTheme {
                AppNavHost()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        forwardDeepLink(intent)
    }

    private fun forwardDeepLink(intent: Intent?) {
        deepLinkHub.router.onNewIntent(intent)
    }
}
