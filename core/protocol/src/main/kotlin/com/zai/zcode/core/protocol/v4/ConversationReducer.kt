package com.zai.zcode.core.protocol.v4

import kotlinx.serialization.json.JsonObject

/**
 * 会话状态归约器（纯 JVM，可单测）。
 *
 * 施加语义（10-protocol-spec §4.5）：
 * - snapshot 帧：整表替换 rows + state，fromSeq=0，(fromSeq, toSeq] 记账。
 * - deltas 帧：封闭 op 集合；row.appended 尾部追加、row.upserted 按 rowId 整行替换、
 *   row.removed 删除 rowId >= fromRowId、row.delta 只对 streaming 行的 4 个流式路径 append、
 *   state.updated 键级整体替换（键内绝不深合并）。
 * - 未知 op 丢弃（宽容）；服务端保证不变量，客户端可断言。
 */
class ConversationReducer {

    /** 归约后的会话投影。rows 按 rowId 升序。 */
    data class ConversationState(
        val sessionId: String,
        val rows: List<JsonObject> = emptyList(),
        val state: JsonObject? = null,
        /** 已确认水位。 */
        val seq: Long = 0,
        val logEpoch: String? = null,
    )

    private val rowsById = LinkedHashMap<Long, JsonObject>()
    private var state: JsonObject? = null
    private var seq: Long = 0
    private var logEpoch: String? = null

    /**
     * seq 空洞回调：deltas 帧的 fromSeq 与本地水位对不上时触发，请求层应发
     * resyncConversationV4 整表重放。
     *
     * 修复依据（真机 v0.2.15「聊天流偶发停在半句 / 工具结果不回来」）：zemote
     * ConversationState.applyFrame（conversation.dart:1351-1354）在 deltas 分支同样做
     * `if (fromSeq != seq) { onGap(); return; }`。Android 原实现只判 `toSeq <= seq`
     * 去重：丢帧后水位停在旧值，后续 deltas 被当成"合法旧帧"静默丢弃，而流式
     * append 又必须以完整前序为基础——表现就是消息永远停在半句且不报错。
     */
    var onSeqGap: (() -> Unit)? = null

    /** 施加一个已重组的 logical 帧。返回 true 表示状态有变化。 */
    fun apply(frame: TopicFrame): Boolean {
        if (!frame.topic.startsWith("conversation/")) return false
        val kind = frame.payload["kind"].asStringOrNull()
        // 修复依据(真机 v0.2.11"新建会话后 加载会话… 永挂"):原实现无条件
        // `frame.toSeq <= seq` 即丢弃,而 seq 初值 0——全新空会话的 initial snapshot
        // toSeq=0 被静默丢弃,rows 永远为空。对齐 TS conversationProjectionStore:
        // snapshot 是整表替换语义,永远施加(重复同帧幂等),deltas 才做旧帧判定。
        if (kind == "snapshot") {
            val snapshot = frame.snapshotObject() ?: return false
            rowsById.clear()
            for (row in snapshot.rowsOrEmpty()) {
                val id = RowView(row).rowId ?: continue
                rowsById[id] = row
            }
            // snapshot 是整表替换语义,state/logEpoch 应一并替换
            // （缺 state 键 = 无 state，不应保留旧值）。
            state = snapshot["state"]?.asObjectOrNull()
            logEpoch = snapshot["logEpoch"]?.asStringOrNull()
            if (frame.toSeq > seq) seq = frame.toSeq
            return true
        }
        if (frame.toSeq <= seq) return false // 旧帧（乱序/重复）丢弃

        when (kind) {
            "deltas" -> {
                // 修复依据（真机 v0.2.15）：见 [onSeqGap] 的注释——必须在推进 seq 之前
                // 判定空洞，且空洞帧不能算"已应用"（否则本地状态与水位同步漂移）。
                if (frame.fromSeq != seq) {
                    onSeqGap?.invoke()
                    return false
                }
                for (deltaEl in frame.deltas() ?: return false) {
                    val delta = deltaEl as? JsonObject ?: continue
                    applyDelta(delta)
                }
            }
            else -> return false
        }
        seq = frame.toSeq
        return true
    }

    /**
     * 批量灌入历史行（rowsRange 分页）。
     *
     * 修复依据（真机 21:05「点进去没有之前的聊天内容」）：冷订阅 initial 快照不含
     * rows（服务端历史行走 rowsRange 分页，桌面端 loadOlder 语义），手机端拉回分页后
     * 需要与实时归约状态合并——按 rowId 覆盖插入（rowsRange 的行是权威历史快照，
     * 但同 rowId 的本地行可能已收到更新的 delta），seq/logEpoch 不动（由快照帧记账）。
     */
    fun seedRows(rows: List<JsonObject>) {
        for (row in rows) {
            val id = RowView(row).rowId ?: continue
            // 只填补缺失的行：本地已有同 id 行说明已收到该行后续 delta，本地更新
            val existing = rowsById[id]
            if (existing == null || RowView(row).createdAtSeq.let { it != null && it > (RowView(existing).createdAtSeq ?: 0L) }) {
                rowsById[id] = row
            }
        }
    }

    private fun applyDelta(delta: JsonObject) {
        when (val view = ConversationDeltaView.from(delta)) {
            is ConversationDeltaView.RowAppended -> {
                val id = RowView(view.row).rowId ?: return
                rowsById[id] = view.row
            }
            is ConversationDeltaView.RowUpserted -> {
                val id = RowView(view.row).rowId ?: return
                rowsById[id] = view.row
            }
            is ConversationDeltaView.RowRemoved -> {
                val it = rowsById.entries.iterator()
                while (it.hasNext()) {
                    if (it.next().key >= view.fromRowId) it.remove()
                }
            }
            is ConversationDeltaView.RowDelta -> appendDelta(view)
            is ConversationDeltaView.StateUpdated -> {
                val base = state
                state = if (base == null) view.patch else base.mergeKeys(view.patch)
            }
            is ConversationDeltaView.PluginUi,
            is ConversationDeltaView.WorkflowRunUpdated,
            is ConversationDeltaView.WorkflowRunRemoved,
            ConversationDeltaView.Unknown -> Unit // live-only 或未知：不改快照
        }
    }

    /**
     * 流式文本 append。path ∈ {"text","inputText","output.text","summaryText"}；
     * output.text 进 toolCall 的 output.text 嵌套。
     */
    private fun appendDelta(view: ConversationDeltaView.RowDelta) {
        val row = rowsById[view.rowId] ?: return
        val updated = when (view.path) {
            "text", "inputText", "summaryText" -> row.appendStringKey(view.path, view.append)
            "output.text" -> {
                val output = row["output"]?.asObjectOrNull() ?: return
                (row + ("output" to output.appendStringKey("text", view.append)))
            }
            else -> return
        }
        rowsById[view.rowId] = updated
    }

    /** 当前投影快照。 */
    fun snapshot(sessionId: String): ConversationState {
        val rows = rowsById.entries.sortedBy { it.key }.map { it.value }
        return ConversationState(sessionId, rows, state, seq, logEpoch)
    }

    fun reset() {
        rowsById.clear()
        state = null
        seq = 0
        // 修复依据（P2-12）：reset 须同时清 logEpoch，否则新连接同 topic 旧 epoch 残留。
        logEpoch = null
    }
}

// ── JsonObject 工具 ──

// 修复原因：`JsonObject + Pair` 走 Map.plus 返回 Map<String, JsonElement>，
// 需要用 buildJsonObject 保持 JsonObject 类型。
private operator fun JsonObject.plus(pair: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject =
    kotlinx.serialization.json.buildJsonObject {
        this@plus.forEach { (k, v) -> put(k, v) }
        put(pair.first, pair.second)
    }

/** 键级整体替换合并：patch 里在场的键替换，缺席键保持（绝不深合并）。 */
fun JsonObject.mergeKeys(patch: JsonObject): JsonObject {
    var result = this
    for ((k, v) in patch) result = result + (k to v)
    return result
}

/** 追加字符串键的值（原值缺省视为空串）。 */
fun JsonObject.appendStringKey(key: String, append: String): JsonObject {
    val existing = (this[key] as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content ?: ""
    return this + (key to kotlinx.serialization.json.JsonPrimitive(existing + append))
}

private fun JsonObject.rowsOrEmpty(): List<JsonObject> =
    (this["rows"] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { it as? JsonObject } ?: emptyList()

// 简化引用：把 asObjectOrNull/asStringOrNull 引入本文件作用域
private fun kotlinx.serialization.json.JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

private fun kotlinx.serialization.json.JsonElement?.asStringOrNull(): String? =
    (this as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf { it !is kotlinx.serialization.json.JsonNull && it.isString }?.content
