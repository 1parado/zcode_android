package com.zai.zcode.core.network

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * 桌面端「网页远控」二维码（relay 直连）连接参数解析。
 *
 * 二维码形如：
 * `https://zcode.z.ai/remote/v4?sid=<device_sid>&hash=<pass_hash>&t=<ts>&mid=&name=&app_version=`
 *
 * 对齐 zemote connection_params.dart（web 客户端 zC() 语义）：
 * 要求 https/wss scheme 且 sid/hash/t 三者齐全；否则视为非 relay 配对码返回 null。
 * relay WS 端点 = `wss://<host>/ws`（有 mid 时追加 `?mid=`）。
 *
 * 安全：passHash 是 HMAC 配对证明的 key material（凭据），不得写日志（AGENTS.md）。
 */
data class RelayConnectionParams(
    val deviceSid: String,
    val passHash: String,
    val timestamp: Long,
    val deviceMid: String?,
    val deviceName: String?,
    val appVersion: String?,
    /** 原始二维码 URL（重建连接用；含凭据，不落日志/普通存储）。 */
    val source: String,
) {
    val isSecure: Boolean
        get() = source.startsWith("https") || source.startsWith("wss")

    /** relay 服务器 host（如 zcode.z.ai），仅用于展示/持久化标记。 */
    val host: String
        get() = runCatching { source.toHttpUrl().host }.getOrDefault("")

    /** relay websocket URL。对齐 web 客户端 Jc()/pen.connect()。 */
    fun relayWsUrl(): HttpUrl {
        val base = source.toHttpUrl()
        val builder = HttpUrl.Builder()
            .scheme(if (isSecure) "wss" else "ws")
            .host(base.host)
            .apply { if (base.port != 80 && base.port != 443) port(base.port) }
            .addPathSegment("ws")
        deviceMid?.let { builder.addQueryParameter("mid", it) }
        return builder.build()
    }

    companion object {
        fun parse(raw: String): RelayConnectionParams? {
            val url = runCatching { raw.trim().toHttpUrl() }.getOrNull() ?: return null
            if (url.scheme != "https" && url.scheme != "wss") return null
            val sid = url.queryParameter("sid")?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
            val hash = url.queryParameter("hash")?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
            val t = url.queryParameter("t")?.trim()?.toLongOrNull() ?: return null
            fun q(key: String): String? =
                url.queryParameter(key)?.trim()?.takeUnless { it.isEmpty() }
            return RelayConnectionParams(
                deviceSid = sid,
                passHash = hash,
                timestamp = t,
                deviceMid = q("mid"),
                deviceName = q("name"),
                appVersion = q("app_version"),
                source = raw.trim(),
            )
        }
    }
}

/** relay data 帧的出站 payload 统一从这里构造（type: data, payload: {...}）。 */
internal fun relayDataPayload(payload: JsonObject, clientTs: Long): JsonObject = buildJsonObject {
    put("type", "data")
    put("payload", payload)
    put("client_ts", clientTs)
}
