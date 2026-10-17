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
        appVersion = "0.2.0",
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
