package com.zai.zcode.core.protocol.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.ByteArrayOutputStream

/**
 * RPC 序列化（逐字对齐 packages/rpc/src/serialization.ts）。
 *
 * 格式：`[1 字节类型标签][VQL 变长长度][数据]`（数组/对象类型为逐元素递归或 JSON 文本）。
 * VQL：7bit 变长整数，低 7bit 数据 + 最高位继续位。
 *
 * ⚠️ 与 TS 一致的关键语义：**数组是逐元素原生序列化，不是 JSON 文本**；
 * 每条 RPC 消息 = serialize(header) + serialize(body) 两段连续拼接在同一物理帧里。
 *
 * Kotlin 语义类型映射：
 * - String / null / Long / ByteArray 直接映射
 * - JsonArray → 原生 ARRAY（逐元素递归）
 * - JsonObject → OBJECT（JSON 文本）
 */
object RpcSerialization {

    /** 解码游标：TS 的 IReader 等价物。 */
    class Cursor(val bytes: ByteArray, var offset: Int = 0) {
        fun remaining(): Int = bytes.size - offset
    }

    /** 解码结果：值 + 消耗字节数。 */
    class Decoded(val value: Any?, val consumed: Int)

    class BadDataException(message: String) : Exception(message)

    // ── VQL ──

    /** 读取 VQL。字节不足或溢出返回 null。 */
    fun readVql(cursor: Cursor): Long? {
        var result = 0L
        var shift = 0
        while (cursor.remaining() > 0) {
            val b = cursor.bytes[cursor.offset++].toInt() and 0xff
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 35) return null // 超 u32，坏数据（u32 最高 5*7bit）
        }
        return null
    }

    fun writeVql(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            var byte = (v and 0x7f).toInt()
            v = v ushr 7
            if (v != 0L) byte = byte or 0x80
            out.write(byte)
            if (v == 0L) break
        }
    }

    // ── 反序列化 ──

    /** 从游标处解码一个值；值内递归解析。失败抛 BadDataException。 */
    fun deserialize(cursor: Cursor): Any? {
        if (cursor.remaining() < 1) throw BadDataException("deserialize: no tag byte")
        val tag = cursor.bytes[cursor.offset++].toInt() and 0xff
        return when (tag) {
            RpcDataType.UNDEFINED -> null
            RpcDataType.STRING -> {
                val len = readVql(cursor) ?: throw BadDataException("bad string length")
                if (len > cursor.remaining()) throw BadDataException("string truncated")
                val s = String(cursor.bytes, cursor.offset, len.toInt(), Charsets.UTF_8)
                cursor.offset += len.toInt()
                s
            }
            RpcDataType.BUFFER, RpcDataType.VSBUFFER -> {
                val len = readVql(cursor) ?: throw BadDataException("bad buffer length")
                if (len > cursor.remaining()) throw BadDataException("buffer truncated")
                val b = cursor.bytes.copyOfRange(cursor.offset, cursor.offset + len.toInt())
                cursor.offset += len.toInt()
                b
            }
            RpcDataType.ARRAY -> {
                val count = readVql(cursor) ?: throw BadDataException("bad array length")
                // 修复依据（P2-5）：恶意 count 可到 u32，超剩余字节直接判坏数据。
                if (count > cursor.remaining()) throw BadDataException("array count exceeds remaining bytes")
                val items = ArrayList<Any?>(count.toInt().coerceAtMost(1024))
                repeat(count.toInt()) { items.add(deserialize(cursor)) }
                JsonArray(items.map { toJsonElement(it) })
            }
            RpcDataType.OBJECT -> {
                val len = readVql(cursor) ?: throw BadDataException("bad object length")
                if (len > cursor.remaining()) throw BadDataException("object truncated")
                val text = String(cursor.bytes, cursor.offset, len.toInt(), Charsets.UTF_8)
                cursor.offset += len.toInt()
                com.zai.zcode.core.common.AppJson.parseToJsonElement(text)
            }
            RpcDataType.INT -> readVql(cursor) ?: throw BadDataException("bad int")
            else -> throw BadDataException("unknown data type $tag")
        }
    }

    /** 便捷：从独立字节段解码。 */
    fun deserialize(bytes: ByteArray): Any? = deserialize(Cursor(bytes))

    /**
     * 便捷：解析一条完整物理帧 body（= serialize(header) + serialize(body) 两段拼接）。
     * 返回 [header, body]；剩余字节忽略。
     */
    fun deserializeTwoSegments(bytes: ByteArray): Pair<Any?, Any?> {
        val cursor = Cursor(bytes)
        val header = deserialize(cursor)
        val body = deserialize(cursor)
        return Pair(header, body)
    }

    /** 解码出的 Kotlin 值转 JsonElement（仅数组解码路径需要）。 */
    private fun toJsonElement(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is String -> JsonPrimitive(v)
        is Long -> JsonPrimitive(v)
        is Int -> JsonPrimitive(v)
        is ByteArray -> JsonPrimitive("__zcode_rpc_bytes_${v.size}") // 不应出现在协议消息里
        is JsonElement -> v
        else -> JsonPrimitive(v.toString())
    }

    // ── 序列化 ──

    fun serialize(value: Any?): ByteArray {
        val out = ByteArrayOutputStream()
        writeValue(out, value)
        return out.toByteArray()
    }

    /** 序列化 header+body 两段拼接为一个物理帧 body。 */
    fun serializeTwoSegments(header: Any?, body: Any?): ByteArray {
        val out = ByteArrayOutputStream()
        writeValue(out, header)
        writeValue(out, body)
        return out.toByteArray()
    }

    private fun writeValue(out: ByteArrayOutputStream, value: Any?) {
        when (value) {
            null -> {
                // 修复依据（P1-1）：TS serialization.ts:153-155 对 undefined 只写 1 字节 tag，
                // 多写 VQL 字节会让数组内嵌 null 时 TS 端错位解析。
                out.write(RpcDataType.UNDEFINED)
            }
            is String -> {
                out.write(RpcDataType.STRING)
                writeBytesPayload(out, value.toByteArray(Charsets.UTF_8))
            }
            is ByteArray -> {
                out.write(RpcDataType.BUFFER)
                writeBytesPayload(out, value)
            }
            is Long, is Int -> {
                val v = (value as Number).toLong()
                if (v < 0 || v > 0xffffffffL) {
                    // 修复依据（P2-6）：TS writeInt32VQL 是 32 位语义（>>> 7，最多 5 字节），
                    // 负数/超 u32 的 Long 会发出 6-10 字节编码，TS 端必坏。
                    // 降级为 OBJECT JSON fallback（TS serialization.ts:179-188 的 else 分支）。
                    out.write(RpcDataType.OBJECT)
                    writeBytesPayload(out, v.toString().toByteArray(Charsets.UTF_8))
                } else {
                    out.write(RpcDataType.INT)
                    writeVql(out, v)
                }
            }
            is JsonArray -> {
                out.write(RpcDataType.ARRAY)
                writeVql(out, value.size.toLong())
                for (el in value) writeValue(out, el)
            }
            is JsonObject -> {
                out.write(RpcDataType.OBJECT)
                writeBytesPayload(
                    out,
                    com.zai.zcode.core.common.AppJson.encodeToString(JsonElement.serializer(), value)
                        .toByteArray(Charsets.UTF_8),
                )
            }
            is JsonElement -> {
                // JsonPrimitive 归类：字符串走 STRING；
                // 布尔/浮点走 OBJECT JSON fallback（修复依据 P1-2：TS serialization.ts:175-188
                // 只对整数走 INT，其余走 JSON.stringify 分支，直接抛异常会让含
                // true/浮点的数组在客户端序列化即崩）。
                when (val p = value as? JsonPrimitive) {
                    null -> throw BadDataException("serialize: unsupported JsonElement ${value::class.simpleName}")
                    else -> if (p.isString) writeValue(out, p.content)
                    else if (p.content.toLongOrNull() != null && p.content.toLong() >= 0 && p.content.toLong() <= 0xffffffffL)
                        writeValue(out, p.content.toLong())
                    else {
                        out.write(RpcDataType.OBJECT)
                        writeBytesPayload(
                            out,
                            com.zai.zcode.core.common.AppJson.encodeToString(JsonElement.serializer(), p)
                                .toByteArray(Charsets.UTF_8),
                        )
                    }
                }
            }
            else -> throw BadDataException("serialize: unsupported type ${value::class.simpleName}")
        }
    }

    private fun writeBytesPayload(out: ByteArrayOutputStream, data: ByteArray) {
        writeVql(out, data.size.toLong())
        out.write(data)
    }
}

// ── JSON 工具扩展 ──

// 修复原因：JsonObject[key] 返回 JsonElement?（键可能缺席），扩展接收者必须是可空类型，
// 否则所有 `obj["k"].asStringOrNull()` 调用点都报 nullable receiver 错误。
fun JsonElement?.asStringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

fun JsonElement?.asIntOrNull(): Int? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toIntOrNull()

fun JsonElement?.asLongOrNull(): Long? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toLongOrNull()

fun JsonElement?.asBooleanOrNull(): Boolean? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toBooleanStrictOrNull()

fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject
fun JsonElement?.asArrayOrNull(): JsonArray? = this as? JsonArray

/** 构建辅助：DSL 风格对象。 */
fun jsonObjectOf(vararg pairs: Pair<String, JsonElement>): JsonObject = buildJsonObject {
    pairs.forEach { (k, v) -> put(k, v) }
}

fun jsonArrayOf(vararg items: Any?): JsonArray = buildJsonArray {
    items.forEach {
        add(
            when (it) {
                null -> JsonNull
                is String -> JsonPrimitive(it)
                is Int -> JsonPrimitive(it)
                is Long -> JsonPrimitive(it)
                is Boolean -> JsonPrimitive(it)
                is JsonElement -> it
                else -> throw RpcSerialization.BadDataException("jsonArrayOf: unsupported ${it::class.simpleName}")
            }
        )
    }
}
