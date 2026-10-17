package com.zai.zcode.core.protocol.v4

import com.zai.zcode.core.protocol.rpc.asObjectOrNull
import kotlinx.serialization.json.JsonObject

/**
 * sessions-index 归约器（对齐 sessions-index.ts）：
 * snapshot 整表替换；delta = session.upserted（conflation key = sessionId）/ session.removed。
 * 无序集合，排序是展示层逻辑。
 */
class SessionsIndexReducer {

    data class SessionsIndexState(
        val workspaceId: String,
        val sessions: Collection<JsonObject>,
        val seq: Long,
        val logEpoch: String?,
    )

    private val sessionsById = LinkedHashMap<String, JsonObject>()
    private var workspaceId: String = ""
    private var seq: Long = 0
    private var logEpoch: String? = null

    fun apply(frame: TopicFrame): Boolean {
        if (V4.parseSessionsIndexTopic(frame.topic) == null) return false
        if (frame.toSeq <= seq) return false

        when (frame.payload["kind"].asStringOrNull()) { // 修复依据（P3）：改 asStringOrNull
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
        seq = frame.toSeq
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
