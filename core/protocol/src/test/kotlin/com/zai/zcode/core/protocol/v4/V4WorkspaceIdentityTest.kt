package com.zai.zcode.core.protocol.v4

import com.zai.zcode.core.protocol.rpc.ChannelClient
import com.zai.zcode.core.protocol.rpc.RpcProtocol
import com.zai.zcode.core.protocol.rpc.RpcRequestType
import com.zai.zcode.core.protocol.rpc.RpcResponseType
import com.zai.zcode.core.protocol.rpc.RpcMessage
import com.zai.zcode.core.protocol.rpc.RpcSerialization
import com.zai.zcode.core.protocol.rpc.asBooleanOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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

    /** 剥掉 13 字节 RPC 物理帧头，解出 [header, body] 两段。 */
    private fun headerAndBody(frame: ByteArray): Pair<JsonArray, JsonElement?> {
        val (header, body) = RpcSerialization.deserializeTwoSegments(
            frame.copyOfRange(RpcProtocol.HEADER_LENGTH, frame.size),
        )
        // deserializeTwoSegments 返回 Pair<Any?, Any?>，需显式收窄到 JsonElement
        return Pair(header as JsonArray, body as? JsonElement)
    }

    private fun initializedClient(sent: MutableList<ByteArray>): ChannelClient =
        ChannelClient(CoroutineScope(Dispatchers.Unconfined)).apply {
            onSend = { sent.add(it) }
            onMessage(
                RpcMessage(
                    RpcProtocol.MSG_REGULAR, 0, 0,
                    RpcSerialization.serializeTwoSegments(
                        buildJsonArray { add(JsonPrimitive(RpcResponseType.INITIALIZE)) },
                        null,
                    ),
                ),
            )
        }

    /**
     * 触发一次无应答的 RPC 请求，收集已发出的请求帧。
     * 服务端不会应答，request() 会挂起，故用 async + 延时后取消，
     * 只取「已经写到 socket 上的那一帧」。
     */
    private fun collectSentWhile(
        block: suspend ZcodeProtocolV4.() -> Unit,
    ): List<Pair<JsonArray, JsonElement?>> {
        val sent = mutableListOf<ByteArray>()
        val channel = initializedClient(sent)
        val protocol = ZcodeProtocolV4(
            CoroutineScope(Dispatchers.Unconfined), channel, "client-1", "3.6.5",
        )
        return runBlocking {
            val deferred = async { protocol.block() }
            delay(50)
            deferred.cancel()
            sent.map { headerAndBody(it) }
        }
    }

    /**
     * 取某方法的订阅参数。
     * 修复依据（真机 CI 39049995217 ClassCastException）：proxy-channel 约定
     * `channel.call(propKey, [args...])`——RPC body 段是**参数数组**，params 对象
     * 在数组第 0 位，不是裸 JsonObject（见 ChannelClient.send 的 wireArgs）。
     */
    private fun subscribeParamsOf(
        frames: List<Pair<JsonArray, JsonElement?>>,
        method: String,
    ): JsonObject {
        val matches = frames.filter { (header, _) ->
            header.size > 3 &&
                header[0].jsonPrimitive.content.toInt() == RpcRequestType.PROMISE &&
                header[3].jsonPrimitive.content == method
        }
        assertEquals(1, matches.size)
        return (matches[0].second as JsonArray).first() as JsonObject
    }

    @Test
    fun `subscribe carries identity bound by attachFrameListener`() {
        val frames = collectSentWhile {
            attachFrameListener(workspacePath = "/ws", workspaceIdentity = "ssh://host/ws")
            subscribeSessionsIndex("/ws")
        }
        // subscribe 未显式传 identity：必须自动带上 attach 时绑定的值
        val params = subscribeParamsOf(frames, V4.METHOD_SESSIONS_INDEX_SUBSCRIBE)
        assertEquals("/ws", params["workspacePath"]!!.jsonPrimitive.content)
        assertEquals("ssh://host/ws", params["workspaceIdentity"]!!.jsonPrimitive.content)
        // zemote 实测：手机远控观察者必须带 existing-only，否则缺省会拉起新 CLI 进程
        assertEquals("existing-only", params["runtimePolicy"]!!.jsonPrimitive.content)
    }

    @Test
    fun `subscribeConversation also inherits bound identity`() {
        val frames = collectSentWhile {
            attachFrameListener(workspacePath = "/ws", workspaceIdentity = "ssh://host/ws")
            subscribeConversation("/ws", "sess-1")
        }
        val params = subscribeParamsOf(frames, V4.METHOD_CONVERSATION_SUBSCRIBE)
        assertEquals("ssh://host/ws", params["workspaceIdentity"]!!.jsonPrimitive.content)
        assertEquals("sess-1", params["sessionId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no identity bound keeps params identity-free`() {
        val frames = collectSentWhile { subscribeSessionsIndex("/ws") }
        // 未 attach：不注入 identity（本地 LAN 场景 path 即 key）
        val params = subscribeParamsOf(frames, V4.METHOD_SESSIONS_INDEX_SUBSCRIBE)
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

    @Test
    fun `clientHello promises workflowRunDeltas only when host declared it`() {
        // host 宣告 workflowRunDeltas 时才许诺：capabilities 是嵌套对象，
        // 且服务端对 clientHello 是 strict 校验，未宣告而许诺会整条解析失败。
        val withCaps = buildClientHello("client-1", "3.6.5", true)
        val caps = withCaps["capabilities"] as JsonObject
        assertEquals(true, caps["workflowRunDeltas"].asBooleanOrNull())
        assertEquals("mobileApp", withCaps["clientKind"]!!.jsonPrimitive.content)
    }

    @Test
    fun `workspaceKeyOf prefers trimmed identity over path`() {
        // 修复依据（真机 v0.2.14「创建会话失败」）：createSession payload.workspaceId
        // 必须是 workspaceKey 而非裸 workspacePath。对齐 zemote main_shell.dart:18。
        assertEquals("ssh://host/ws", workspaceKeyOf("/ws", "ssh://host/ws"))
        // 服务端归一做的是 identity.trim()：带空白的 identity 同样优先且要去空白
        assertEquals("ssh://host/ws", workspaceKeyOf("/ws", "  ssh://host/ws  "))
        // 空白 identity 视为缺席，回落 path
        assertEquals("/ws", workspaceKeyOf("/ws", "   "))
        assertEquals("/ws", workspaceKeyOf("/ws", null))
        assertEquals("/ws", workspaceKeyOf("/ws", ""))
    }
}
