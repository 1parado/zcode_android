package com.zai.zcode.feature.chat

import com.zai.zcode.core.common.ZLog
import com.zai.zcode.core.network.ZcodeConnection
import com.zai.zcode.core.protocol.v4.RowView
import com.zai.zcode.core.protocol.v4.TopicFrame
import com.zai.zcode.core.protocol.v4.ZcodeProtocolV4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "ChatRepository"

/**
 * 单会话对话数据源：ConversationReducer + conversation/<id> 订阅 + 发送命令。
 * 行投影直接以 protocol 的 RowView 语义渲染（UI 只认 kind/text/state）。
 */
class ChatRepository(
    private val scope: CoroutineScope,
    private val connection: ZcodeConnection,
    private val sessionId: String,
    private val workspacePath: String,
    private val clientId: String,
) {
    /** UI 行模型（从 protocol JsonObject 行裁剪出的最小投影）。 */
    data class ChatRow(
        val rowId: Long,
        val kind: String?,
        val text: String?,
        val state: String?,
        val toolName: String?,
        val inputText: String?,
        val streaming: Boolean,
    )

    data class ChatUi(
        val rows: List<ChatRow> = emptyList(),
        val revision: Long? = null,
        val phase: String? = null,
    )

    private val reducer = com.zai.zcode.core.protocol.v4.ConversationReducer()
    private val _ui = MutableStateFlow(ChatUi())
    val ui: StateFlow<ChatUi> = _ui.asStateFlow()

    private var subscribed = false
    private var lastSeq: Long = 0
    private var lastLogEpoch: String? = null

    fun start() {
        scope.launch {
            connection.protocol.collect { p ->
                if (p == null) {
                    subscribed = false
                    return@collect
                }
                attach(p)
            }
        }
    }

    private fun attach(p: ZcodeProtocolV4) {
        if (subscribed) return
        subscribed = true
        p.onConversationFrame = { frame, _ -> if (reducer.apply(frame)) publish() }
        scope.launch {
            try {
                // 重连恢复：带上次水位重订阅，服务端按 replayable 补发；无水位则全新快照
                p.subscribeConversation(
                    workspacePath = workspacePath,
                    sessionId = sessionId,
                    baseLogEpoch = lastLogEpoch,
                    baseSeq = lastSeq.takeIf { it > 0 },
                )
                ZLog.i(TAG, "subscribed conversation/$sessionId")
            } catch (e: Exception) {
                ZLog.w(TAG, "subscribe failed: ${e.message}")
                subscribed = false
            }
        }
    }

    private fun publish() {
        val st = reducer.snapshot(sessionId)
        lastSeq = st.seq
        lastLogEpoch = st.logEpoch
        val revision = st.state?.get("revision")
        _ui.value = ChatUi(
            rows = st.rows.map { row -> toChatRow(row) },
            revision = (revision as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull(),
            phase = st.state?.get("phase")?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p ->
                    p !is kotlinx.serialization.json.JsonNull
                }?.content
            },
        )
    }

    private fun toChatRow(raw: kotlinx.serialization.json.JsonObject): ChatRow {
        val v = RowView(raw)
        return ChatRow(
            rowId = v.rowId ?: -1,
            kind = v.kind,
            text = v.text,
            state = v.state,
            toolName = v.toolName,
            inputText = v.inputText,
            // streaming 判据：state 字段非终态（对齐 TS 侧 streaming row 语义）
            streaming = v.state?.let { it != "done" && it != "error" && it != "aborted" } == true,
        )
    }

    /** 发送文本（sendText 命令）。 */
    suspend fun sendText(text: String): Boolean {
        val p = connection.protocol.value ?: return false
        return runCatching {
            val envelope = com.zai.zcode.core.protocol.v4.buildCommandEnvelope(
                commandId = java.util.UUID.randomUUID().toString(),
                clientId = clientId,
                sessionId = sessionId,
                type = "sendText",
                payload = com.zai.zcode.core.protocol.v4.buildSendTextPayload(text),
                baseRevision = _ui.value.revision,
            )
            val ack = p.sendCommand(workspacePath, envelope)
            ack.status == "accepted"
        }.getOrElse {
            ZLog.w(TAG, "sendText failed: ${it.message}")
            false
        }
    }

    /** 停止当前回合（stop 命令）。 */
    suspend fun stop(): Boolean {
        val p = connection.protocol.value ?: return false
        return runCatching {
            val envelope = com.zai.zcode.core.protocol.v4.buildCommandEnvelope(
                commandId = java.util.UUID.randomUUID().toString(),
                clientId = clientId,
                sessionId = sessionId,
                type = "stop",
                payload = kotlinx.serialization.json.JsonObject(emptyMap()),
            )
            val ack = p.sendCommand(workspacePath, envelope)
            ack.status == "accepted"
        }.getOrElse {
            ZLog.w(TAG, "stop failed: ${it.message}")
            false
        }
    }
}
