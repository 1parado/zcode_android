package com.zai.zcode.core.network

import javax.inject.Inject
import javax.inject.Singleton

/**
 * App 范围内共享的单例连接的只读门面。原先放在 feature/connect，但
 * feature/chat、feature/sessions 也要消费它 —— 下沉到 core/network
 * 避免这些 feature 反向依赖 connect（并与之构成模块环）。
 */
@Singleton
class ConnectionFacade @Inject constructor(holder: ConnectionHolder) {
    val connection: ZcodeConnection get() = holder.connection
}

@Singleton
class ConnectionHolder @Inject constructor(
    val connection: ZcodeConnection,
)
