package com.zai.zcode.feature.connect.di

import com.zai.zcode.core.common.ClientIdProvider
import com.zai.zcode.core.network.ConnectionHolder
import com.zai.zcode.core.network.ZcodeConnection
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
        appVersion = "0.1.0",
    )

    @Provides
    @Singleton
    fun connectionHolder(conn: ZcodeConnection): ConnectionHolder = ConnectionHolder(conn)
}
