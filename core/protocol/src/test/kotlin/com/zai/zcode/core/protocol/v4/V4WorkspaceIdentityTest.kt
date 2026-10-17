package com.zai.zcode.core.protocol.v4

import com.zai.zcode.core.protocol.rpc.ChannelClient
import com.zai.zcode.core.protocol.rpc.RpcProtocol
import com.zai.zcode.core.protocol.rpc.RpcRequestType
import com.zai.zcode.core.protocol.rpc.RpcSerialization
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * workspaceIdentity 贯穿传递回归。
 *
 * 修复依据（真机 v0.2.13「订阅 ACK 成功，但会话列表零内容 / 创建会话失败」）：
 * 事件监听 attachFrameListener 带 workspaceIdentity，而 subscribe 与 sendCommand 由上层
 * Repository 调用时不带 —— 服务端 workspaceKey 归一为 `identity.trim() || path`，
 * 于是订阅登记在 identity key、帧监听挂在 path key，connectionScope 的 routedEventKey
 * 对不上，帧永不 fan-out（ACK 能到、零帧）。AGENTS.md：远程链路必须贯穿传递。
 */
class V4WorkspaceIdentityTest {

    private fun headerArgOf(frame: ByteArray): Pair<JsonArray, JsonElement?> {
        val (header, body) = RpcSerialization.deserializeTwoSegments(
            frame.copyOfRange(RpcProtocol.HEADER_LENGTH, frame.size),
        )
        return (header as JsonArray) to body
    }

    private fun clientAndSent(): Pair<ChannelClient, java.util.Collections.ArrayList<ByteArray>> {
        val sent = java.util.Collections.ArrayList<ByteArray>()
        val client = ChannelClient(CoroutineScope(Dispatchers.Unconfined))
        client.onSend = { sent.add(it) }
        // Initialize → IDLE，否则 request() 的 check(IDLE) 抛异常
        client.onMessage(
            com.zai.zcode.core.protocol.rpc.RpcMessage(
                RpcProtocol.MSG_REGULAR, 0, 0,
                RpcSerialization.serializeTwoSegments(
                    kotlinx.serialization.json.buildJsonArray {
                        add(JsonPrimitive(com.zai.zcode.core.protocol.rpc.RpcResponseType.INITIALIZE))
                    },
                    null,
                ),
            ),
        )
        return client to sent
    }

    @Test
    fun `subscribe carries identity bound by attachFrameListener`() = runBlocking {
        val (channel, sent) = clientAndSent()
        val protocol = ZcodeProtocolV4(
            CoroutineScope(Dispatchers.Unconfined), channel, "client-1", "3.6.5",
        )
        protocol.attachFrameListener(workspacePath = "/ws", workspaceIdentity = "ssh://host/ws")

        // subscribe 未显式传 identity：必须自动带上 attach 时绑定的值
        val deferred = kotlinx.coroutines.async { protocol.subscribeSessionsIndex("/ws") }
        // 无服务端应答，request 会挂起；只需取到已发出的请求帧
        kotlinx.coroutines.delay(50)
        deferred.cancel()

        val subscribeFrames = sent.map { headerArgOf(it) }
            .filter { (h, _) -> h[0].jsonPrimitive.content.toInt() == RpcRequestType.PROMISE &&
                h[3].jsonPrimitive.content == V4.METHOD_SESSIONS_INDEX_SUBSCRIBE }
        assertEquals(1, subscribeFrames.size)
        val params = subscribeFrames[0].second as JsonObject
        assertEquals("/ws", params["workspacePath"]!!.jsonPrimitive.content)
        assertEquals("ssh://host/ws", params["workspaceIdentity"]!!.jsonPrimitive.content)
        // zemote 实测：手机远控观察者必须带 existing-only，否则缺省会拉起新 CLI 进程
        assertEquals("existing-only", params["runtimePolicy"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no identity bound keeps params identity-free`() = runBlocking {
        val (channel, sent) = clientAndSent()
        val protocol = ZcodeProtocolV4(
            CoroutineScope(Dispatchers.Unconfined), channel, "client-1", "3.6.5",
        )
        // 未 attach：不注入 identity（本地 LAN 场景 path 即 key）
        val deferred = kotlinx.coroutines.async { protocol.subscribeSessionsIndex("/ws") }
        kotlinx.coroutines.delay(50)
        deferred.cancel()

        val params = sent.map { headerArgOf(it) }
            .first { (h, _) -> h[3].jsonPrimitive.content == V4.METHOD_SESSIONS_INDEX_SUBSCRIBE }
            .second as JsonObject
        assertNull(params["workspaceIdentity"])
        assertEquals("/ws", params["workspacePath"]!!.jsonPrimitive.content)
    }

    @Test
    fun `clientHello reports 3_x appVersion`() {
        // 修复依据（zemote 实测注释）：appVersion 发 0.x 会被服务端按旧客户端禁用
        // V4 能力（如 sessions-index）。握手续必须伪装 3.x。
        val hello = buildClientHello("client-1", "3.6.5", false)
        assertEquals("3.6.5", hello["appVersion"]!!.jsonPrimitive.content)
        assertEquals("clientHello", hello["kind"]!!.jsonPrimitive.content)
        assertEquals("mobileApp", hello["clientKind"]!!.jsonPrimitive.content)
        assertEquals(V4.WIRE_PROTOCOL_VERSION, hello["protocolVersion"]!!.jsonPrimitive.content.toInt())
        // host 未宣告 workflowRunDeltas 时不得许诺（服务端 capabilities 是 strict 校验）
        assertNull(hello["capabilities"])
    }
}
