package com.zai.zcode.core.protocol.rpc

/**
 * RPC 物理协议常量与枚举（对齐 packages/rpc/src/protocol.ts 与 channels.shared.ts）。
 *
 * 物理帧 13 字节头：
 * ```
 * offset 0 : type          (u8)
 * offset 1 : id            (u32 BE)
 * offset 5 : ack           (u32 BE)
 * offset 9 : length        (u32 BE) —— 后随 body 字节数
 * ```
 * 一次 write = header + body；接收端必须等整帧到齐才消费（防 TCP/WS 粘包切半）。
 */
object RpcProtocol {
    const val HEADER_LENGTH = 13

    // ProtocolMessageType
    const val MSG_REGULAR: Int = 1
    const val MSG_CONTROL: Int = 2
    const val MSG_ACK: Int = 3
    const val MSG_DISCONNECT: Int = 5
    const val MSG_REPLAY_REQUEST: Int = 6
    const val MSG_PAUSE: Int = 7
    const val MSG_RESUME: Int = 8
    const val MSG_KEEP_ALIVE: Int = 9
}

/** 序列化数据的类型标签（packages/rpc/src/serialization.ts 的 DataType）。 */
object RpcDataType {
    const val UNDEFINED: Int = 0
    const val STRING: Int = 1
    const val BUFFER: Int = 2
    const val VSBUFFER: Int = 3
    const val ARRAY: Int = 4
    const val OBJECT: Int = 5 // JSON
    const val INT: Int = 6 // VQL 整数
}

/** Channel 层请求类型（channels.shared.ts RequestType）。 */
object RpcRequestType {
    const val PROMISE: Int = 100
    const val PROMISE_CANCEL: Int = 101
    const val EVENT_LISTEN: Int = 102
    const val EVENT_DISPOSE: Int = 103
}

/** Channel 层响应类型（channels.shared.ts ResponseType）。 */
object RpcResponseType {
    const val INITIALIZE: Int = 200
    const val PROMISE_SUCCESS: Int = 201
    const val PROMISE_ERROR: Int = 202
    const val PROMISE_ERROR_OBJ: Int = 203
    const val EVENT_FIRE: Int = 204
}

/**
 * 一条已解码的 RPC 消息（半条消息 = header；此处为 header+body 都到齐后的完整形态）。
 * body 保持原始字节，由上层 Channel 按消息类型反序列化。
 */
class RpcMessage(
    val type: Int,
    val id: Int,
    val ack: Int,
    val body: ByteArray,
) {
    override fun toString(): String =
        "RpcMessage(type=$type,id=$id,ack=$ack,len=${body.size})"
}

/** 帧格式错误。 */
class RpcProtocolException(message: String) : Exception(message)

/**
 * 帧解码器（对齐 protocol.ts 的 ChunkStream + readMessages）：
 * 流式喂入 socket 字节，输出完整 RpcMessage。
 * 关键不变量：header 13 字节全部到齐且 body 全部到齐才消费，否则保留缓冲等下一块。
 *
 * 修复依据（P1-3/P2-4）：
 * - length ≥ 2^31 时 readUInt32BE 返回负 Int，原实现 `HEADER_LENGTH + length < buffer.size`
 *   误成立 → NegativeArraySizeException 崩掉读循环；且帧无上限会无限积累缓冲。
 *   现 length < 0 或 > [MAX_FRAME_BYTES]（1 MiB）抛 [RpcProtocolException]，由调用方断开连接。
 * - 原 ArrayDeque<Byte> 逐字节装箱，1 MiB 帧百万级 Boxed Byte；改为块队列 + peek/copy。
 */
class RpcFrameDecoder {
    /** 帧长上限（对齐 TS CHUNK 读取侧的 1 MiB 限制，与 V4.MAX_FRAME_BYTES 一致）。 */
    companion object {
        const val MAX_FRAME_BYTES = 1024 * 1024
    }

    private class Block(val bytes: ByteArray, val from: Int, val to: Int) {
        val size: Int get() = to - from
    }

    private val blocks = ArrayDeque<Block>()
    private var buffered = 0

    /** 喂入一段字节；返回本次凑齐的所有完整帧。坏帧长抛 [RpcProtocolException]。 */
    fun feed(chunk: ByteArray): List<RpcMessage> {
        if (chunk.isNotEmpty()) {
            blocks.addLast(Block(chunk, 0, chunk.size))
            buffered += chunk.size
        }
        val messages = ArrayList<RpcMessage>()
        while (true) {
            if (buffered < RpcProtocol.HEADER_LENGTH) break
            val header = peek(RpcProtocol.HEADER_LENGTH)
            val type = header[0].toInt() and 0xff
            val id = readUInt32BE(header, 1)
            val ack = readUInt32BE(header, 5)
            val length = readUInt32BE(header, 9)
            // 负值 = ≥2^31；超过 1 MiB 一律视为协议错误（调用方应断开连接）。
            if (length < 0 || length > MAX_FRAME_BYTES) {
                throw RpcProtocolException("frame length out of range: $length")
            }
            if (buffered < RpcProtocol.HEADER_LENGTH + length) break
            skip(RpcProtocol.HEADER_LENGTH)
            val body = ByteArray(length)
            readInto(body, 0, length)
            messages.add(RpcMessage(type, id, ack, body))
        }
        return messages
    }

    private fun peek(count: Int): ByteArray {
        // 只读不消费：沿块队列扫描，不移动游标。
        val out = ByteArray(count)
        var done = 0
        var bi = 0
        var bo = blockOffset
        while (done < count) {
            val block = blocks[bi]
            val avail = block.size - bo
            val take = minOf(avail, count - done)
            block.bytes.copyInto(out, done, block.from + bo, block.from + bo + take)
            done += take
            bi++
            bo = 0
        }
        return out
    }

    private var blockOffset = 0

    private fun readInto(out: ByteArray, outStart: Int, count: Int) {
        var written = 0
        while (written < count) {
            val block = blocks.first()
            val avail = block.size - blockOffset
            val take = minOf(avail, count - written)
            block.bytes.copyInto(out, outStart + written, block.from + blockOffset, block.from + blockOffset + take)
            written += take
            blockOffset += take
            buffered -= take // 消费即扣减，供 feed 循环的水位判断使用
            if (blockOffset == block.size) {
                blocks.removeFirst()
                blockOffset = 0
            }
        }
    }

    private fun skip(count: Int) {
        var left = count
        while (left > 0) {
            val block = blocks.first()
            val avail = block.size - blockOffset
            if (left < avail) {
                blockOffset += left
                buffered -= left
                left = 0
            } else {
                left -= avail
                blocks.removeFirst()
                buffered -= avail
                blockOffset = 0
            }
        }
    }
}

/** u32 BE 读取（header 字段用）。 */
private fun readUInt32BE(bytes: ByteArray, offset: Int): Int {
    var v = 0
    for (i in 0 until 4) v = (v shl 8) or (bytes[offset + i].toInt() and 0xff)
    return v
}

/** 帧编码：header + body 一次成型。 */
fun encodeRpcMessage(type: Int, id: Int, ack: Int, body: ByteArray): ByteArray {
    val out = ByteArray(RpcProtocol.HEADER_LENGTH + body.size)
    out[0] = type.toByte()
    writeUInt32BE(out, 1, id)
    writeUInt32BE(out, 5, ack)
    writeUInt32BE(out, 9, body.size)
    body.copyInto(out, RpcProtocol.HEADER_LENGTH)
    return out
}

private fun writeUInt32BE(out: ByteArray, offset: Int, value: Int) {
    out[offset] = ((value ushr 24) and 0xff).toByte()
    out[offset + 1] = ((value ushr 16) and 0xff).toByte()
    out[offset + 2] = ((value ushr 8) and 0xff).toByte()
    out[offset + 3] = (value and 0xff).toByte()
}
