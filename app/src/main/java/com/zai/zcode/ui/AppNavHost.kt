package com.zai.zcode.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.zai.zcode.feature.chat.ui.ChatScreen
import com.zai.zcode.feature.connect.ui.ConnectScreen
import com.zai.zcode.feature.sessions.ui.SessionsScreen
import com.zai.zcode.feature.uuremote.ui.UuRemoteScreen

/** 路由表。sessionId 作为 chat 页参数。 */
object Routes {
    const val CONNECT = "connect"
    const val SESSIONS = "sessions"
    const val CHAT = "chat/{sessionId}"
    const val UU_REMOTE = "uu_remote"
    fun chat(sessionId: String) = "chat/$sessionId"
}

@Composable
fun AppNavHost() {
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
                onOpenSettings = {
                    nav.navigate(Routes.CONNECT) // 设置即重连页
                },
            )
        }
        composable(
            route = Routes.CHAT,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) {
            ChatScreen(
                onBack = { nav.popBackStack() },
                onWatchScreen = { nav.navigate(Routes.UU_REMOTE) },
            )
        }
        composable(Routes.UU_REMOTE) {
            UuRemoteScreen(
                onBack = { nav.popBackStack() },
            )
        }
    }
}
