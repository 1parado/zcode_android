package com.zai.zcode.feature.chat

import com.zai.zcode.core.common.ZLog
import com.zai.zcode.core.network.ZcodeConnection
import com.zai.zcode.core.protocol.rpc.asLongOrNull
import com.zai.zcode.core.protocol.rpc.asStringOrNull
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
        /** phase 是封闭枚举（sessionPhaseSchema：draft、prewarming、running、
         *  completedSuccess、completedInterrupted、error），UI 运行态判定不猜子串（评审 P3）。 */
        val isRunning: Boolean = false,
    )

    private val reducer = com.zai.zcode.core.protocol.v4.ConversationReducer()
    private val _ui = MutableStateFlow(ChatUi())
    val ui: StateFlow<ChatUi> = _ui.asStateFlow()

    // 修复依据(真机日志 19:30 bridge degrade→reopen 后"加载会话…"永挂):openBridge
    // 重建全新 ZcodeProtocolV4 实例,布尔 subscribed 把新实例当已订阅,重连后
    // conversation 订阅永不重发。改为按协议实例身份判定。
    private var attachedProtocol: ZcodeProtocolV4? = null
    private var lastSeq: Long = 0
    private var lastLogEpoch: String? = null

    fun start() {
        scope.launch {
            connection.protocol.collect { p ->
                if (p == null) {
                    attachedProtocol = null
                    return@collect
                }
                attach(p)
            }
        }
    }

    private fun attach(p: ZcodeProtocolV4) {
        if (attachedProtocol === p) return
        attachedProtocol = p
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
                attachedProtocol = null
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
            // 修复依据（P2-6，评审 2026-10-17）：复用 rpc 包 canonical helper，
            // 不再手写 (x as? JsonPrimitive)?.content 解析
            revision = revision.asLongOrNull(),
            phase = st.state?.get("phase").asStringOrNull(),
            // 修复依据（P3，评审 2026-10-17）：phase 是封闭枚举，运行态在此映射，
            // 不在 UI 里 contains("run") 猜子串
            isRunning = st.state?.get("phase").asStringOrNull()
                ?.let { it == "draft" || it == "prewarming" || it == "running" } == true,
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
