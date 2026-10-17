package com.zai.zcode.core.protocol.v4

import com.zai.zcode.core.common.ZLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "V4FrameAssembler"

/**
 * V4 wire 帧装配器（对齐 packages/shared/src/zcode-protocol-v4/wire-assembler.ts 的
 * 权威持续 staging 语义，Phase 1 子集）：
 *
 * - routeKey = topic + '\u0000' + subscriptionId；每个 route 独立维护 assembly。
 * - settledByRoute 记录每 route 已交付的最高 ordinal（tombstone）：旧 ordinal 的 wire 帧
 *   静默丢弃；同 ordinal 不同 logicalFrameId 产出 ordinalConflict fault。
 * - recovery 到达且 ordinal 更高时，无 fault 直接 supersede 该 route 的现存 assembly
 *   （权威替换），并过滤同批旧 online 的低 ordinal fault。
 * - fragment staging 校验：fragmentCount ≤ 1024、重复片字节一致、30s 超时。
 * - fault 后 abort 该 assembly 但保留 tombstone（服务端会以 recovery 补发）。
 *
 * 输入是经 [TopicWireFrame.fromJson] 解析的 wire 帧，输出经 [onFrame] 回调。
 */
class V4FrameAssembler(
    private val scope: CoroutineScope,
    private val onFrame: (TopicFrame, deliveryKind: String) -> Unit,
) {
    private class Staging {
        val wires = LinkedHashMap<Int, TopicWireFrame.Fragment>()
        var timer: Job? = null
    }

    private class Assembly {
        var ordinal: Long = -1
        var logicalFrameId: String? = null
        var staging: Staging? = null
        // ordinal 冲突标记（compute 内写入，compute 外消费并触发 fault）。
        var ordinalConflict: Boolean = false
    }

    /** routeKey → 当前 assembly（含 ordinal tombstone）。 */
    private val assemblies = ConcurrentHashMap<String, Assembly>()

    /** 单条 wire 帧进入装配器。 */
    fun onWireFrame(wire: TopicWireFrame) {
        val routeKey = wire.routeKey()
        val assembly = assemblies.compute(routeKey) { _, existing ->
            (existing ?: Assembly()).also {
                // ordinal tombstone：旧帧静默丢弃；同 ordinal 不同 id 是冲突。
                if (wire.logicalFrameOrdinal < it.ordinal) return@compute it
                if (wire.logicalFrameOrdinal == it.ordinal &&
                    it.logicalFrameId != null &&
                    wire.logicalFrameId != it.logicalFrameId
                ) {
                    // ordinalConflict：保留 tombstone，调用方收到 fault。
                    it.ordinalConflict = true
                    return@compute it
                }
                if (wire.logicalFrameOrdinal > it.ordinal) {
                    // 更高 ordinal：recovery 语义允许无 fault supersede；online/initial 同理
                    // （服务端保证单调），直接开新 assembly。
                    it.staging?.timer?.cancel()
                    it.staging = null
                    it.ordinal = wire.logicalFrameOrdinal
                    it.logicalFrameId = wire.logicalFrameId
                    it.ordinalConflict = false
                }
            }
        } ?: return
        if (assembly.ordinalConflict) {
            assembly.ordinalConflict = false
            ZLog.w(TAG, "ordinal conflict on route $routeKey")
            onFault("proto.frameOrdinalConflict")
            return
        }
        if (wire.logicalFrameOrdinal < assembly.ordinal) return // 旧帧静默丢弃

        when (wire) {
            is TopicWireFrame.Complete -> onLogicalReady(assembly, routeKey, listOf(wire))
            is TopicWireFrame.Fragment -> stageFragment(assembly, wire)
        }
    }

    private fun stageFragment(assembly: Assembly, frag: TopicWireFrame.Fragment) {
        if (frag.fragmentCount > V4.ASSEMBLY_MAX_FRAGMENTS) {
            onFault("proto.frameFragmentCountExceeded")
            return
        }
        val staging = assembly.staging ?: Staging().also { assembly.staging = it }
        // 逻辑帧切换（同 ordinal 内不该发生，防御性重启 staging）
        if (staging.wires.isNotEmpty()) {
            val first = staging.wires.values.first()
            if (first.logicalFrameId != frag.logicalFrameId ||
                first.topic != frag.topic ||
                first.fragmentCount != frag.fragmentCount
            ) {
                dropStaging(assembly)
                onFault("proto.frameAssemblyMetadataMismatch")
                return
            }
        }
        val existing = staging.wires[frag.fragmentIndex]
        if (existing != null) {
            if (existing.dataBase64 != frag.dataBase64) {
                // 重复片字节冲突：整组 abort（保留 tombstone）。
                dropStaging(assembly)
                onFault("proto.frameAssemblyFragmentConflict")
                return
            }
            return
        }
        staging.wires[frag.fragmentIndex] = frag
        if (staging.wires.size >= frag.fragmentCount) {
            val all = staging.wires.values.toList()
            dropStaging(assembly)
            onLogicalReady(assembly, frag.routeKey(), all)
            return
        }
        // 未收齐：启动/续期超时
        if (staging.timer == null) {
            staging.timer = scope.launch {
                delay(STAGING_TIMEOUT_MS)
                dropStaging(assembly)
                ZLog.w(TAG, "staging timeout on route ${frag.routeKey()}")
            }
        }
    }

    private fun onLogicalReady(assembly: Assembly, routeKey: String, wires: List<TopicWireFrame>) {
        when (val result = WireReassembly.reassemble(wires)) {
            is ReassembleResult.Complete -> onFrame(result.frame, result.deliveryKind)
            is ReassembleResult.Incomplete ->
                ZLog.w(TAG, "assembled but incomplete ${result.logicalFrameId} on $routeKey")
            is ReassembleResult.Rejected -> {
                ZLog.w(TAG, "assembly rejected: ${result.reasonCode} on $routeKey")
                onFault(result.reasonCode)
            }
        }
    }

    private fun dropStaging(assembly: Assembly) {
        assembly.staging?.timer?.cancel()
        assembly.staging = null
    }

    private fun onFault(reasonCode: String) {
        onFaultCallback?.invoke(reasonCode)
    }

    /** fault 回调（由门面注入）。 */
    var onFaultCallback: ((String) -> Unit)? = null

    /** 断线时清空全部 staging 与 tombstone（新连接是新一代 ordinal 空间）。 */
    fun reset() {
        assemblies.values.forEach { dropStaging(it) }
        assemblies.clear()
    }

    private fun TopicWireFrame.routeKey(): String = "$topic\u0000$subscriptionId"

    companion object {
        /** PROTOCOL_V4_LIMITS.logicalFrameAssemblyMaxConcurrent / timeout（Phase 1：并发上限由 route 数天然约束）。 */
        private const val STAGING_TIMEOUT_MS = 30_000L
    }
}
