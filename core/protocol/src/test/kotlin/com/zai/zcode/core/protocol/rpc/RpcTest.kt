package com.zai.zcode.core.protocol.rpc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RpcSerializationTest {

    @Test
    fun `VQL round trip single byte`() {
        val out = java.io.ByteArrayOutputStream()
        RpcSerialization.writeVql(out, 0)
        RpcSerialization.writeVql(out, 100)
        RpcSerialization.writeVql(out, 127)
        assertArrayEquals(byteArrayOf(0, 100, 127), out.toByteArray())
    }

    @Test
    fun `VQL multi byte little endian 7bit`() {
        val out = java.io.ByteArrayOutputStream()
        RpcSerialization.writeVql(out, 128) // 0x80 → [0x80, 0x01]
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x01), out.toByteArray())
        val decoded = RpcSerialization.readVql(RpcSerialization.Cursor(out.toByteArray()))
        assertEquals(128L, decoded)
    }

    @Test
    fun `VQL u32 max`() {
        val out = java.io.ByteArrayOutputStream()
        RpcSerialization.writeVql(out, 0xffffffffL)
        val decoded = RpcSerialization.readVql(RpcSerialization.Cursor(out.toByteArray()))
        assertEquals(0xffffffffL, decoded)
    }

    @Test
    fun `string round trip`() {
        val bytes = RpcSerialization.serialize("hello 世界")
        assertEquals("hello 世界", RpcSerialization.deserialize(bytes))
    }

    @Test
    fun `null serializes as undefined tag`() {
        val bytes = RpcSerialization.serialize(null)
        assertEquals(1, bytes.size)
        assertEquals(RpcDataType.UNDEFINED, bytes[0].toInt() and 0xff)
        assertNull(RpcSerialization.deserialize(bytes))
    }

    @Test
    fun `int round trip`() {
        assertEquals(42L, RpcSerialization.deserialize(RpcSerialization.serialize(42)))
        assertEquals(0L, RpcSerialization.deserialize(RpcSerialization.serialize(0L)))
    }

    @Test
    fun `json object round trip as JSON text`() {
        val obj = buildJsonObject {
            put("name", "zcode")
            put("count", 3)
        }
        val decoded = RpcSerialization.deserialize(RpcSerialization.serialize(obj))
        assertEquals(obj, decoded)
    }

    @Test
    fun `json array serializes element-wise not as JSON text`() {
        // 数组逐元素原生序列化：[STRING "a", INT 1] → tag(4) len(2) [tag(1) len(1) 'a'] [tag(6) 1]
        val arr = buildJsonArray { add(JsonPrimitive("a")); add(JsonPrimitive(1)) }
        val bytes = RpcSerialization.serialize(arr)
        // 首字节 ARRAY 标签，第二字节 VQL 元素数 2
        assertEquals(RpcDataType.ARRAY, bytes[0].toInt() and 0xff)
        assertEquals(2, bytes[1].toInt() and 0x7f)
        // 第三个字节是首元素 STRING 标签（若误当 JSON 文本会是长度或 '{'/'['）
        assertEquals(RpcDataType.STRING, bytes[2].toInt() and 0xff)
        val decoded = RpcSerialization.deserialize(bytes)
        assertEquals(arr, decoded)
    }

    @Test
    fun `two segment round trip header array + body object`() {
        val header = buildJsonArray {
            add(JsonPrimitive(RpcRequestType.PROMISE))
            add(JsonPrimitive(7))
            add(JsonPrimitive("zcode-agent"))
            add(JsonPrimitive("helloConversationV4"))
        }
        val body = buildJsonObject { put("kind", "clientHello") }
        val bytes = RpcSerialization.serializeTwoSegments(header, body)
        val (h, b) = RpcSerialization.deserializeTwoSegments(bytes)
        assertEquals(header, h)
        assertEquals(body, b)
    }

    @Test
    fun `buffer round trip`() {
        val payload = byteArrayOf(1, 2, 3, 0x7f.toByte(), 0x80.toByte())
        val decoded = RpcSerialization.deserialize(RpcSerialization.serialize(payload))
        assertTrue(decoded is ByteArray)
        assertArrayEquals(payload, decoded as ByteArray)
    }

    @Test
    fun `truncated string throws BadDataException`() {
        val bytes = RpcSerialization.serialize("hello")
        val truncated = bytes.copyOfRange(0, bytes.size - 2)
        try {
            RpcSerialization.deserialize(truncated)
            throw AssertionError("expected BadDataException")
        } catch (e: RpcSerialization.BadDataException) {
            // 预期
        }
    }
}

class RpcFrameTest {

    @Test
    fun `frame encode decode round trip`() {
        val body = "payload-bytes".toByteArray()
        val frame = encodeRpcMessage(RpcProtocol.MSG_REGULAR, 0x11223344, 0x55667788, body)
        assertEquals(RpcProtocol.HEADER_LENGTH + body.size, frame.size)
        val messages = RpcFrameDecoder().feed(frame)
        assertEquals(1, messages.size)
        val msg = messages[0]
        assertEquals(RpcProtocol.MSG_REGULAR, msg.type)
        assertEquals(0x11223344, msg.id)
        assertEquals(0x55667788, msg.ack)
        assertArrayEquals(body, msg.body)
    }

    @Test
    fun `chunked feed assembles frame across splits`() {
        val body = RpcSerialization.serializeTwoSegments(
            buildJsonArray { add(JsonPrimitive(200)) },
            buildJsonObject { put("ok", true) },
        )
        val frame = encodeRpcMessage(RpcProtocol.MSG_REGULAR, 1, 0, body)
        val decoder = RpcFrameDecoder()
        assertTrue(decoder.feed(frame.copyOfRange(0, 5)).isEmpty())
        assertTrue(decoder.feed(frame.copyOfRange(5, 12)).isEmpty())
        assertTrue(decoder.feed(frame.copyOfRange(12, frame.size / 2)).isEmpty())
        val messages = decoder.feed(frame.copyOfRange(frame.size / 2, frame.size))
        assertEquals(1, messages.size)
        assertArrayEquals(body, messages[0].body)
    }

    @Test
    fun `two frames in one chunk`() {
        val b1 = byteArrayOf(0x01)
        val b2 = byteArrayOf(0x02, 0x03)
        val chunk = encodeRpcMessage(RpcProtocol.MSG_REGULAR, 1, 0, b1) +
            encodeRpcMessage(RpcProtocol.MSG_REGULAR, 2, 1, b2)
        val messages = RpcFrameDecoder().feed(chunk)
        assertEquals(2, messages.size)
        assertEquals(1, messages[0].id)
        assertEquals(2, messages[1].id)
    }

    @Test
    fun `header byte layout type-id-ack-length BE`() {
        val frame = encodeRpcMessage(RpcProtocol.MSG_REGULAR, 0x01020304, 0, ByteArray(0x0506))
        assertEquals(RpcProtocol.MSG_REGULAR.toByte(), frame[0])
        assertEquals(0x01, frame[1].toInt() and 0xff)
        assertEquals(0x02, frame[2].toInt() and 0xff)
        assertEquals(0x03, frame[3].toInt() and 0xff)
        assertEquals(0x04, frame[4].toInt() and 0xff)
        // 修复原因：length=u32 BE 位于 offset 9-12，0x0506 的高字节 0x00 在 [9]、
        // 0x05 在 [11]、0x06 在 [12]。原断言 [9]==0x05 是列错位（实现与 TS 协议一致）。
        assertEquals(0x00, frame[9].toInt() and 0xff)
        assertEquals(0x05, frame[11].toInt() and 0xff)
        assertEquals(0x06, frame[12].toInt() and 0xff)
    }
}
