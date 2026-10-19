package com.zai.zcode.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.zai.zcode.core.network.ZcodeConnection
import com.zai.zcode.feature.chat.ui.ChatScreen
import com.zai.zcode.feature.connect.ui.ConnectScreen
import com.zai.zcode.feature.sessions.ui.SessionsScreen
import com.zai.zcode.feature.uuremote.ui.UuRemoteScreen
import com.zai.zcode.ui.SettingsScreen

/** 路由表。sessionId 作为 chat 页参数。 */
object Routes {
    const val CONNECT = "connect"
    const val SESSIONS = "sessions"
    const val CHAT = "chat/{sessionId}"
    const val UU_REMOTE = "uu_remote"
    const val SETTINGS = "settings"
    fun chat(sessionId: String) = "chat/$sessionId"
}

@Composable
fun AppNavHost(connection: ZcodeConnection) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.CONNECT) {
        composable(Routes.CONNECT) {
            ConnectScreen(
                onConnected = {
                    nav.navigate(Routes.SESSIONS) {
                        popUpTo(Routes.CONNECT) { inclusive = true }
                    }
                },
                onScanQr = { /* 扫码层已在 ConnectScreen 内部弹出，此处保留导航扩展点 */ },
            )
        }
        composable(Routes.SESSIONS) {
            SessionsScreen(
                onOpenSession = { sessionId -> nav.navigate(Routes.chat(sessionId)) },
                // 设置入口不再跳回连接/授权页：远程操控场景下"设置"是查看本机远控配置，
                // 跳回 CONNECT 会让用户误以为要重新配对。SETTINGS 是独立页，仅展示信息。
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            route = Routes.CHAT,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) {
            ChatScreen(
                onBack = { nav.popBackStack() },
                onWatchScreen = { nav.navigate(Routes.UU_REMOTE) },
                // fork 出的子会话原地导航（官方 SessionPane forkAssistant ack 后切换）
                onOpenSession = { sessionId -> nav.navigate(Routes.chat(sessionId)) },
            )
        }
        composable(Routes.UU_REMOTE) {
            UuRemoteScreen(
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                connection = connection,
                onBack = { nav.popBackStack() },
                onReconnect = { nav.navigate(Routes.CONNECT) },
            )
        }
    }
}
