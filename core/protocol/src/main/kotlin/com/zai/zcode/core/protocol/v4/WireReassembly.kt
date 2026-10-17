package com.zai.zcode.core.protocol.v4

import com.zai.zcode.core.common.AppJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** CRC-32（poly 0xedb88320，init/final 0xffffffff），输出 8 位小写 hex（对齐 wire-binary.ts）。 */
fun crc32WireBytes(bytes: ByteArray): String {
    var crc = 0xffffffffL.toInt()
    for (b in bytes) {
        crc = crc xor (b.toInt() and 0xff)
        repeat(8) {
            crc = (crc ushr 1) xor (if (crc and 1 != 0) 0xedb88320.toInt() else 0)
        }
    }
    return String.format("%08x", (crc xor 0xffffffffL.toInt()).toLong() and 0xffffffffL)
}

/** 重组结果（对齐 wire-reassembly.ts 的 ReassembleTopicWireFramesResult）。 */
sealed class ReassembleResult {
    data class Complete(val frame: TopicFrame, val deliveryKind: String) : ReassembleResult()
    data class Incomplete(val logicalFrameId: String, val missingIndexes: List<Int>) : ReassembleResult()
    data class Rejected(val reasonCode: String) : ReassembleResult()
}

/**
 * V4 物理 wire 重组（逐字对齐 packages/shared/src/zcode-protocol-v4/wire-reassembly.ts）：
 * 所有 fragment 只进入 staging；bytes、checksum、UTF-8、JSON 与信封匹配全部通过后，
 * 才产出一个可原子 apply 的 logical frame。
 *
 * 无并发 staging 状态机（那种由上层订阅装配器负责），这里是纯函数：
 * 同一 logical frame 的全部分片一次性传入。
 */
object WireReassembly {

    const val FAULT_INVALID_PAYLOAD = "proto.invalidPayload"

    fun reassemble(wires: List<TopicWireFrame>): ReassembleResult {
        if (wires.isEmpty()) return ReassembleResult.Rejected("proto.frameAssemblyEmpty")
        val first = wires.first()

        if (first is TopicWireFrame.Complete) {
            if (wires.size != 1 || !frameMatchesEnvelope(first.frame, first)) {
                return ReassembleResult.Rejected("proto.frameAssemblyMetadataMismatch")
            }
            // 修复依据（P1-5）：complete 分支也须做 16 MiB 上限校验——只限制 fragment
            // 会允许接收端绕过权威状态上限（对齐 TS wire-reassembly.ts:60-67）。
            if (AppJson.encodeToString(JsonElement.serializer(), first.frame)
                    .toByteArray(Charsets.UTF_8).size > V4.ASSEMBLY_MAX_BYTES
            ) {
                return ReassembleResult.Rejected("proto.frameAssemblyTooLarge")
            }
            val frame = TopicFrame.fromJson(first.frame)
                ?: return ReassembleResult.Rejected(FAULT_INVALID_PAYLOAD)
            return ReassembleResult.Complete(frame, first.deliveryKind)
        }
        val firstFrag = first as TopicWireFrame.Fragment

        if (firstFrag.fragmentCount > V4.ASSEMBLY_MAX_FRAGMENTS) {
            // 元数据先于字节总量校验，阻断恶意 fragmentCount 制造无界 missingIndexes。
            return ReassembleResult.Rejected("proto.frameFragmentCountExceeded")
        }
        if (firstFrag.logicalBytes > V4.ASSEMBLY_MAX_BYTES) {
            return ReassembleResult.Rejected("proto.frameAssemblyTooLarge")
        }

        val fragments = HashMap<Int, ByteArray>()
        var decodedByteTotal = 0L
        for (wire in wires) {
            val frag = wire as? TopicWireFrame.Fragment
                ?: return ReassembleResult.Rejected("proto.frameAssemblyMetadataMismatch")
            if (
                frag.logicalFrameId != firstFrag.logicalFrameId ||
                frag.logicalFrameOrdinal != firstFrag.logicalFrameOrdinal ||
                frag.deliveryKind != firstFrag.deliveryKind ||
                frag.topic != firstFrag.topic ||
                frag.subscriptionId != firstFrag.subscriptionId ||
                frag.fragmentCount != firstFrag.fragmentCount ||
                frag.logicalBytes != firstFrag.logicalBytes ||
                frag.checksum.value != firstFrag.checksum.value ||
                frag.fragmentCount > frag.logicalBytes ||
                frag.fragmentIndex < 0 ||
                frag.fragmentIndex >= frag.fragmentCount
            ) {
                return ReassembleResult.Rejected("proto.frameAssemblyMetadataMismatch")
            }
            val decoded = decodeWireBase64(frag.dataBase64)
                ?: return ReassembleResult.Rejected("proto.frameAssemblyInvalidBase64")
            val previous = fragments[frag.fragmentIndex]
            if (previous != null) {
                if (!previous.contentEquals(decoded)) {
                    return ReassembleResult.Rejected("proto.frameAssemblyFragmentConflict")
                }
                continue
            }
            val nextTotal = decodedByteTotal + decoded.size
            if (nextTotal > V4.ASSEMBLY_MAX_BYTES) {
                return ReassembleResult.Rejected("proto.frameAssemblyTooLarge")
            }
            if (nextTotal > firstFrag.logicalBytes) {
                return ReassembleResult.Rejected("proto.frameAssemblyLengthMismatch")
            }
            fragments[frag.fragmentIndex] = decoded
            decodedByteTotal = nextTotal
        }

        val missing = (0 until firstFrag.fragmentCount).filter { it !in fragments }
        if (missing.isNotEmpty()) {
            return ReassembleResult.Incomplete(firstFrag.logicalFrameId, missing)
        }
        if (decodedByteTotal != firstFrag.logicalBytes) {
            return ReassembleResult.Rejected("proto.frameAssemblyLengthMismatch")
        }

        val logical = ByteArray(decodedByteTotal.toInt())
        var offset = 0
        for (index in 0 until firstFrag.fragmentCount) {
            val frag = fragments[index]!!
            frag.copyInto(logical, offset)
            offset += frag.size
        }
        if (crc32WireBytes(logical) != firstFrag.checksum.value) {
            return ReassembleResult.Rejected("proto.frameAssemblyChecksumMismatch")
        }

        val decoded = try {
            // 修复依据（P2-7）：String 构造按 UTF-8 替换无效序列，frameAssemblyInvalidUtf8
            // 永不触发；改用 CharsetDecoder REPORT 严格校验（对齐 TS TextDecoder fatal 语义）。
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(logical))
                .toString()
        } catch (_: Exception) {
            return ReassembleResult.Rejected("proto.frameAssemblyInvalidUtf8")
        }
        val value = JsonObject.parseOrNull(decoded)
            ?: return ReassembleResult.Rejected("proto.frameAssemblyInvalidJson")
        if (!frameMatchesEnvelope(value, firstFrag)) {
            return ReassembleResult.Rejected("proto.frameAssemblyMetadataMismatch")
        }
        val frame = TopicFrame.fromJson(value)
            ?: return ReassembleResult.Rejected(FAULT_INVALID_PAYLOAD)
        return ReassembleResult.Complete(frame, firstFrag.deliveryKind)
    }

    private fun frameMatchesEnvelope(frame: JsonObject, wire: TopicWireFrame): Boolean =
        stringOf(frame, "topic") == wire.topic &&
            stringOf(frame, "subscriptionId") == wire.subscriptionId

    private fun stringOf(obj: JsonObject, key: String): String? {
        val el = obj[key]
        return (el as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
    }

    /** base64 解码（严格 alphabet + padding 校验，对齐 decodeWireBase64）。 */
    fun decodeWireBase64(value: String): ByteArray? = try {
        if (!BASE64_REGEX.matches(value)) null else Base64.Default.decode(value)
    } catch (_: Exception) {
        null
    }

    private val BASE64_REGEX = Regex("^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$")
}
