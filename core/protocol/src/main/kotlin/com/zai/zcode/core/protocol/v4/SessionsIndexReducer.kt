package com.zai.zcode.core.protocol.v4

import com.zai.zcode.core.protocol.rpc.asObjectOrNull
import kotlinx.serialization.json.JsonObject

/**
 * sessions-index 归约器（对齐 sessions-index.ts + zemote SessionsIndexState）：
 * snapshot 整表替换；delta = session.upserted / session.removed。
 * 无序集合，排序是展示层逻辑。
 *
 * 空洞语义：deltas 帧的 fromSeq 必须等于本地水位 seq，否则中间有帧丢失，本地状态
 * 已不可信——只能整帧丢弃并请求 resync（不允许"带洞继续"）。
 */
class SessionsIndexReducer {

    data class SessionsIndexState(
        val workspaceId: String,
        val sessions: Collection<JsonObject>,
        val seq: Long,
        val logEpoch: String?,
    )

    /** seq 空洞回调：请求层收到后应发 resyncSessionsIndexV4 重放。 */
    var onSeqGap: (() -> Unit)? = null

    private val sessionsById = LinkedHashMap<String, JsonObject>()
    private var workspaceId: String = ""
    private var seq: Long = 0
    private var logEpoch: String? = null

    fun apply(frame: TopicFrame): Boolean {
        if (V4.parseSessionsIndexTopic(frame.topic) == null) return false
        val kind = frame.payload["kind"].asStringOrNull()
        // 修复依据（真机 v0.2.15「resync 后仍空」）：snapshot 是整表替换语义，必须
        // 永远施加，不能被 toSeq<=seq 拦在门外——resync 重放的 snapshot 往往带着与
        // 本地陈旧水位相等甚至更小的 toSeq（服务端按 base 重放同一单调(seq)），
        // 原实现只在 conversation 侧做了这条（ConversationReducer:40），sessions
        // 侧漏了，导致 resync 的救命 snapshot 被静默丢弃。
        if (kind != "snapshot" && frame.toSeq <= seq) return false

        when (kind) { // 修复依据（P3）：改 asStringOrNull
            "snapshot" -> {
                val snapshot = frame.snapshotObject() ?: return false
                sessionsById.clear()
                workspaceId = snapshot["workspaceId"]?.asStringOrNull() ?: workspaceId
                logEpoch = snapshot["logEpoch"]?.asStringOrNull() ?: logEpoch
                for (session in snapshot.sessionsOrEmpty()) {
                    val id = SessionSummaryView(session).sessionId ?: continue
                    sessionsById[id] = session
                }
            }
            "deltas" -> {
                // 修复依据（真机 v0.2.15「列表偶发缺失 / 越用越少」）：zemote
                // SessionsIndexState.applyFrame（conversation.dart:1257-1260）对 deltas
                // 做 `if (fromSeq != seq) { onGap(); return; }`。Android 原实现只判
                // `toSeq <= seq` 去重，缺 Arm 空洞检测：丢帧后本地水位停在旧值，
                // 后续 deltas 被"合法丢弃"，表现为会话列表悄悄少了条目且再也不回来。
                // 注意必须在推进 seq 之前判定，并且此类帧不能算"已应用"。
                if (frame.fromSeq != seq) {
                    onSeqGap?.invoke()
                    return false
                }
                // 修复依据（P1-10）：单条 delta 坏时不可 return false 中途退出——
                // 此前已应用的条目 + 不推进的水位会造成状态与 seq 不一致。
                // 与 conversation 侧一致：坏条目按 Unknown 容忍跳过，继续处理其余。
                for (deltaEl in frame.deltas() ?: return false) {
                    val delta = deltaEl as? JsonObject ?: continue
                    when (val view = SessionsIndexDeltaView.from(delta)) {
                        is SessionsIndexDeltaView.SessionUpserted -> {
                            val id = SessionSummaryView(view.session).sessionId ?: continue
                            sessionsById[id] = view.session
                        }
                        is SessionsIndexDeltaView.SessionRemoved ->
                            sessionsById.remove(view.sessionId)
                        SessionsIndexDeltaView.Unknown -> Unit
                    }
                }
            }
            else -> return false
        }
        // 单调递增：只会比以前更新 (fromSeq, toSeq] 记账推进，重放的 snapshot 若
        // toSeq 更小不倒退水位——否则下一个 delta 会被判成空洞，resync 自我循环。
        if (frame.toSeq > seq) seq = frame.toSeq
        return true
    }

    fun snapshot(): SessionsIndexState =
        SessionsIndexState(workspaceId, sessionsById.values.toList(), seq, logEpoch)

    fun reset() {
        sessionsById.clear()
        seq = 0
        // 修复依据（P2-12 同族）：reset 须同时清 logEpoch/workspaceId。
        logEpoch = null
        workspaceId = ""
    }
}

private fun kotlinx.serialization.json.JsonElement?.asStringOrNull(): String? =
    (this as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf { it !is kotlinx.serialization.json.JsonNull && it.isString }?.content

private fun JsonObject.sessionsOrEmpty(): List<JsonObject> =
    (this["sessions"] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { it as? JsonObject } ?: emptyList()
