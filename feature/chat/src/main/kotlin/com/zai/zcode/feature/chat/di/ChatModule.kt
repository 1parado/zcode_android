package com.zai.zcode.feature.chat.di

import com.zai.zcode.feature.chat.ClientIdProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.UUID
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ChatModule {
    /**
     * clientId 与 ZcodeConnection 握手所用的一致（信封 clientId 不一致会被
     * fault.command.clientMismatch 拒绝）。同一 @Provides 作用域内 UUID 只生成一次。
     */
    @Provides
    @Singleton
    fun clientIdProvider(): ClientIdProvider = object : ClientIdProvider {
        override val clientId: String = UUID.randomUUID().toString()
    }
}
