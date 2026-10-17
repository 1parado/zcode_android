package com.zai.zcode.core.common

/**
 * clientId 桥接口：连接握手与命令信封必须使用同一 clientId（不一致会被
 * fault.command.clientMismatch 拒绝）。实现由 app 层 DI 提供单例。
 */
interface ClientIdProvider {
    val clientId: String
}
