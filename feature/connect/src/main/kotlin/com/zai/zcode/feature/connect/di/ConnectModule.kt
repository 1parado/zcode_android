package com.zai.zcode.feature.connect.di

import com.zai.zcode.core.common.ClientIdProvider
import com.zai.zcode.core.network.ZcodeConnection
import com.zai.zcode.feature.connect.ConnectionSettingsStore
import com.zai.zcode.feature.connect.SecureTokenStore
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
object ConnectModule {

    @Provides
    @Singleton
    @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun connection(
        @AppScope scope: CoroutineScope,
        clientIdProvider: ClientIdProvider,
    ): ZcodeConnection = ZcodeConnection(
        scope,
        // 说明：连接握手的 clientId 与命令信封 clientId 必须同源（initializeConversationV4
        // 绑定后不一致会被 fault.command.clientMismatch 拒绝），故统一由 ClientIdProvider 提供。
        clientIdProvider.clientId,
        // 修复依据(真机 v0.2.13"订阅 ACK 成功但会话列表零内容"):zemote 对同一 zcode host
        // 的实测注释——clientHello.appVersion 发 0.x 会导致服务端按旧客户端禁用 V4 能力
        // (如 sessions-index),必须伪装 3.x(zemote 硬编码 '3.6.5')。本 App 真实版本号
        // 仅用于 relay 配对/展示,不进 V4 握手。
        appVersion = "3.6.5",
    )

    // 修复依据（P2-1，评审 2026-10-17）：删除 ConnectionHolder + ConnectionFacade 双层
    // 包装。ZcodeConnection 已是 @Singleton 状态机门面，feature 直接注入它。

    // 修复 CI Dagger/MissingBinding：两个 Store 需要构造注入，补 @Provides 绑定
    @Provides
    @Singleton
    fun connectionSettingsStore(
        @dagger.hilt.android.qualifiers.ApplicationContext context: Context,
    ): ConnectionSettingsStore = ConnectionSettingsStore(context)

    @Provides
    @Singleton
    fun secureTokenStore(
        @dagger.hilt.android.qualifiers.ApplicationContext context: Context,
    ): SecureTokenStore = SecureTokenStore(context)
}
