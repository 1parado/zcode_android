package com.zai.zcode.core.network

import com.zai.zcode.core.common.AppJson
import com.zai.zcode.core.common.ZLog
import com.zai.zcode.core.protocol.v4.crc32WireBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "RpcFrameTransport"

/**
 * rpc-frame 分片传输（对齐 zemote rpc_transport.dart，web 客户端 Xf/Zf、sp()/lp() 语义）。
 *
 * relay 的 JSON 信封有 ~1MiB 上限，bridge 上的逻辑消息（ChannelClient 值流 body——
 * 注意：此路径没有 IPC 13 字节物理帧，一个 rpc-frame 重组结果就是一个完整 body）
 * 需按 512KiB 分片、crc32 校验、接收后回 rpc-frame-ack。
 *
 * 信封字段：{zcode_type:'rpc-frame', bridgeSessionId, bridgeGeneration?, recoveryId?,
 *           seq, messageSeq, fragmentIndex, fragmentCount, messageBytes,
 *           checksum:{algorithm:'crc32', value}, dataBase64}
 */
class RpcFrameTransport(
    val bridgeSessionId: String,
    val bridgeGeneration: Int?,
    val recoveryId: String?,
    private val sendPayload: (JsonObject) -> Unit,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val MAX_MESSAGE_BYTES = 64 * 1024 * 1024
        private const val MAX_FRAGMENTS = 256
        private const val FRAGMENT_PAYLOAD_BYTES = 512 * 1024
        private const val STALE_ASSEMBLY_MS = 60_000L
    }

    /** 重组完成的逻辑消息（即 ChannelClient 的完整值流 body）。 */
    private val _messages = MutableSharedFlow<ByteArray>(
        replay = 0, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val messages: SharedFlow<ByteArray> = _messages.asSharedFlow()

    private class Assembly(
        val fragmentCount: Int,
        val messageBytes: Int,
        val checksum: String?,
        val createdAt: Long = System.currentTimeMillis(),
    ) {
        val fragments = arrayOfNulls<ByteArray>(fragmentCount)
        @Volatile var received = 0
        val isComplete: Boolean get() = received == fragmentCount
        fun assemble(): ByteArray {
            val out = ByteArray(messageBytes)
            var offset = 0
            for (f in fragments) {
                f?.copyInto(out, offset)
                offset += f!!.size
            }
            return out
        }
    }

    private val assemblies = ConcurrentHashMap<Int, Assembly>()
    private var cleanupJob: Job? = null
    private var seq = 0
    private var messageSeq = 0

    fun start() {
        cleanupJob?.cancel()
        cleanupJob = scope.launch {
            while (isActive) {
                delay(30_000)
                val now = System.currentTimeMillis()
                assemblies.entries.removeIf { now - it.value.createdAt > STALE_ASSEMBLY_MS }
            }
        }
    }

    fun dispose() {
        cleanupJob?.cancel()
        assemblies.clear()
    }

    private fun identity(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        buildJsonObject {
            put("zcode_type", "rpc-frame")
            put("bridgeSessionId", bridgeSessionId)
            bridgeGeneration?.let { put("bridgeGeneration", it) }
            recoveryId?.let { put("recoveryId", it) }
            block()
        }

    /** 分片发送一个逻辑消息（ChannelClient body）。 */
    fun sendMessage(bytes: ByteArray) {
        require(bytes.isNotEmpty()) { "empty rpc-frame message" }
        require(bytes.size <= MAX_MESSAGE_BYTES) { "rpc-frame message too large: ${bytes.size}" }
        messageSeq += 1
        val ms = messageSeq
        val checksum = crc32WireBytes(bytes)
        val fragmentCount = (bytes.size + FRAGMENT_PAYLOAD_BYTES - 1) / FRAGMENT_PAYLOAD_BYTES
        require(fragmentCount <= MAX_FRAGMENTS) { "rpc-frame fragment limit exceeded" }
        for (i in 0 until fragmentCount) {
            val start = i * FRAGMENT_PAYLOAD_BYTES
            val end = minOf(start + FRAGMENT_PAYLOAD_BYTES, bytes.size)
            val chunk = bytes.copyOfRange(start, end)
            seq += 1
            val sq = seq
            sendPayload(identity {
                put("seq", sq)
                put("messageSeq", ms)
                put("fragmentIndex", i)
                put("fragmentCount", fragmentCount)
                put("messageBytes", bytes.size)
                put("checksum", buildJsonObject {
                    put("algorithm", "crc32")
                    put("value", checksum)
                })
                put("dataBase64", Base64.getEncoder().encodeToString(chunk))
            })
        }
    }

    /**
     * 喂入一个 relay payload。是 rpc-frame(-ack) 时消费并返回 true；
     * 不是本类管辖的 payload 返回 false（由上层继续分发 bootstrap 等）。
     */
    fun acceptPayload(payload: JsonObject): Boolean {
        val type = payload["zcode_type"]?.jsonPrimitive?.contentOrNull
        if (type == "rpc-frame-ack") return true
        if (type != "rpc-frame") return false
        if (payload["bridgeSessionId"]?.jsonPrimitive?.contentOrNull != bridgeSessionId) return false

        fun intOf(key: String): Int? = payload[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val ms = intOf("messageSeq")
        val fragmentIndex = intOf("fragmentIndex")
        val fragmentCount = intOf("fragmentCount")
        val messageBytes = intOf("messageBytes")
        val dataBase64 = payload["dataBase64"]?.jsonPrimitive?.contentOrNull
        val checksum = (payload["checksum"] as? JsonObject)?.get("value")?.jsonPrimitive?.contentOrNull
        if (ms == null || fragmentIndex == null || fragmentCount == null ||
            messageBytes == null || dataBase64 == null
        ) return true

        if (fragmentCount > MAX_FRAGMENTS || messageBytes > MAX_MESSAGE_BYTES ||
            ms < 0 || fragmentCount < 1 || fragmentIndex < 0 ||
            fragmentIndex >= fragmentCount || messageBytes < 1
        ) {
            ZLog.w(TAG, "dropped oversized/invalid message $messageSeq")
            return true
        }
        val chunk = runCatching { Base64.getDecoder().decode(dataBase64) }.getOrNull()
            ?: return true
        if (chunk.size > FRAGMENT_PAYLOAD_BYTES) return true

        val existing = assemblies[ms]
        if (existing != null && (existing.fragmentCount != fragmentCount ||
                existing.messageBytes != messageBytes || existing.checksum != checksum)
        ) {
            // 同一 messageSeq 元数据不一致：前序组装已死，丢弃重来
            assemblies.remove(ms)
        }
        val assembly = assemblies.getOrPut(ms) {
            Assembly(fragmentCount, messageBytes, checksum)
        }
        if (assembly.fragments[fragmentIndex] == null) {
            assembly.fragments[fragmentIndex] = chunk
            assembly.received++
        }
        if (assembly.isComplete) {
            assemblies.remove(ms)
            val message = assembly.assemble()
            if (message.size != assembly.messageBytes) {
                ZLog.w(TAG, "message $ms size mismatch")
            } else if (assembly.checksum == null || crc32WireBytes(message) == assembly.checksum) {
                scope.launch { _messages.emit(message) }
                sendPayload(buildJsonObject {
                    put("zcode_type", "rpc-frame-ack")
                    put("bridgeSessionId", bridgeSessionId)
                    put("ackMessageSeq", ms)
                })
            } else {
                ZLog.w(TAG, "message $ms checksum mismatch")
            }
        }
        return true
    }
}

/** 从 relay payload 里取 zcode_type 的公共小工具。 */
internal fun JsonObject.zcodeType(): String? =
    (this["zcode_type"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.contentOrNull
