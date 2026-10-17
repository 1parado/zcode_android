package com.zai.zcode.core.protocol.rpc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ChannelClient 下行分发单测。重点回归：
 * 修复依据(真机 v0.2.12"订阅 ACK 成功但零帧"):桌面 channelServer.ts 对 EventFire
 * 只发两元素 header [204, id](无事件名),旧实现因取不到第三元素静默丢帧。
 */
class ChannelClientTest {

    private fun regularFrame(header: kotlinx.serialization.json.JsonArray, body: kotlinx.serialization.json.JsonElement?): ByteArray =
        RpcSerialization.serializeTwoSegments(header, body)

    private fun initializedClient(): ChannelClient =
        ChannelClient(CoroutineScope(Dispatchers.Unconfined)).apply {
            onMessage(
                RpcMessage(
                    RpcProtocol.MSG_REGULAR, 0, 0,
                    regularFrame(buildJsonArray { add(JsonPrimitive(RpcProtocol.INITIALIZE)) }, null),
                ),
            )
        }

    @Test
    fun `two-element EventFire header delivers event`() = runBlocking {
        val client = initializedClient()
        client.listenEvent("zcode-agent", "onConversationFrameV4")
        val fired = kotlinx.coroutines.CompletableDeferred<ChannelClient.EventFireData>()
        val job = launch { client.events.first { it.channelName == "zcode-agent" }.let { fired.complete(it) } }

        val payload = buildJsonArray { add(JsonPrimitive("hello")) }
        // 桌面 wire:header = [204, id] 两元素
        client.onMessage(
            RpcMessage(
                RpcProtocol.MSG_REGULAR, 0, 0,
                regularFrame(
                    buildJsonArray { add(JsonPrimitive(RpcProtocol.EVENT_FIRE)); add(JsonPrimitive(1)) },
                    payload,
                ),
            ),
        )
        val event = withTimeout(5_000) { fired.await() }
        assertEquals("zcode-agent", event.channelName)
        assertNull(event.event)
        assertEquals(payload, event.payload)
        job.cancel()
    }

    @Test
    fun `three-element EventFire header still parses event name`() = runBlocking {
        val client = initializedClient()
        client.listenEvent("zcode-agent", "onConversationFrameV4")
        val fired = kotlinx.coroutines.CompletableDeferred<ChannelClient.EventFireData>()
        val job = launch { client.events.first { it.channelName == "zcode-agent" }.let { fired.complete(it) } }

        client.onMessage(
            RpcMessage(
                RpcProtocol.MSG_REGULAR, 0, 0,
                regularFrame(
                    buildJsonArray {
                        add(JsonPrimitive(RpcProtocol.EVENT_FIRE)); add(JsonPrimitive(1)); add(JsonPrimitive("onX"))
                    },
                    buildJsonArray { add(JsonPrimitive("v")) },
                ),
            ),
        )
        val event = withTimeout(5_000) { fired.await() }
        assertEquals("onX", event.event)
        job.cancel()
    }

    @Test
    fun `unknown listener id dropped`() {
        val client = initializedClient()
        client.onMessage(
            RpcMessage(
                RpcProtocol.MSG_REGULAR, 0, 0,
                regularFrame(
                    buildJsonArray { add(JsonPrimitive(RpcProtocol.EVENT_FIRE)); add(JsonPrimitive(999)) },
                    null,
                ),
            ),
        )
        // 不抛异常、无事件即可
    }

    @Test
    fun `listenEvent serializes arg unwrapped`() {
        val client = ChannelClient(CoroutineScope(Dispatchers.Unconfined))
        var sent: ByteArray? = null
        client.onSend = { sent = it }
        val arg = buildJsonArray { add(JsonPrimitive("workspacePath")); add(JsonPrimitive("/ws")) }
        client.listenEvent("zcode-agent", "onConversationFrameV4", arg)

        val (header, body) = RpcSerialization.deserializeTwoSegments(sent!!)
        val h = header as kotlinx.serialization.json.JsonArray
        assertEquals(RpcProtocol.EVENT_LISTEN, h[0].toString().toInt())
        assertNotNull(body)
        // arg 原样序列化:body 段即 workspaceTarget 本身,不是 [workspaceTarget]
        assertEquals(arg, body)
    }

    private fun assertNull(x: Any?) {
        assertTrue(x == null)
    }
}
