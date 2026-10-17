package com.zai.zcode.feature.chat

import com.zai.zcode.core.common.ZLog
import com.zai.zcode.core.network.ZcodeConnection
import com.zai.zcode.core.protocol.rpc.asLongOrNull
import com.zai.zcode.core.protocol.rpc.asStringOrNull
import com.zai.zcode.core.protocol.v4.RowView
import com.zai.zcode.core.protocol.v4.TopicFrame
import com.zai.zcode.core.protocol.v4.ZcodeProtocolV4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

private const val TAG = "ChatRepository"

/**
 * 静默看门狗：与 zemote conversation.dart:1055-1075 同形状（10s 巡检 / 20s 静默阈值）。
 *
 * 修复依据（真机 v0.2.15「切后台回来消息停在半句」）：手机进程被挂起期间帧丢失不会
 * 产生空洞告警（后续帧可能一个都没到），只能靠"该有动静却长时间没动静"来发现。
 * 仅在会话处于运行态或存在 streaming 行时巡检，避免对已结束会话打出无意义的重放。
 */
private const val WATCHDOG_PERIOD_MS = 10_000L
private const val WATCHDOG_QUIET_MS = 20_000L

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
    /**
     * 当前 workspace 的 identity（AGENTS.md「Workspace Identity」：远程链路贯穿传递）。
     * 修复依据（真机 v0.2.15「新建会话后聊天页一直转圈」）：ChatRepository 原先没有
     * identity，订阅/发命令都走 [ZcodeProtocolV4] 的 boundWorkspaceIdentity 兜底；
     * 换 workspace 而 protocol 实例未重建时兜底值是旧的，服务端按
     * `identity.trim() || path` 归一后登记在别的 key 上，帧永不 fan-out。
     */
    private val workspaceIdentity: String? = null,
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

    private val reducer = com.zai.zcode.core.protocol.v4.ConversationReducer().apply {
        // 修复依据（真机 v0.2.15）：空洞必须闭环到重放，否则聊天流会安静地停在半句
        onSeqGap = { resyncConversation() }
    }
    private val _ui = MutableStateFlow(ChatUi())
    val ui: StateFlow<ChatUi> = _ui.asStateFlow()

    /** resync 单飞：并发空洞帧 / 看门狗重复触发时只打一次重同步请求。 */
    private val resyncMutex = Mutex()
    private var conversationSubscriptionId: String? = null
    /** 最近一次收到帧的时间戳（看门狗静默判定用）。 */
    @Volatile private var lastFrameAtMs: Long = 0L
    @Volatile private var watchdogRunning: Boolean = false

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
                    conversationSubscriptionId = null
                    return@collect
                }
                attach(p)
            }
        }
    }

    private fun attach(p: ZcodeProtocolV4) {
        if (attachedProtocol === p) return
        attachedProtocol = p
        p.onConversationFrame = { frame, _ -> onFrame(frame) }
        scope.launch {
            try {
                // 重连恢复：带上次水位重订阅，服务端按 replayable 补发；无水位则全新快照
                val ack = p.subscribeConversation(
                    workspacePath = workspacePath,
                    sessionId = sessionId,
                    workspaceIdentity = workspaceIdentity,
                    baseLogEpoch = lastLogEpoch,
                    baseSeq = lastSeq.takeIf { it > 0 },
                )
                // 修复依据（真机 v0.2.15）：resync 必须带 subscriptionId
                // （zemote _resync 的 body，conversation.dart:943-968），
                // 原先丢掉 ack 就无从重放。
                conversationSubscriptionId = ack.subscriptionId
                ZLog.i(TAG, "subscribed conversation/$sessionId")
            } catch (e: Exception) {
                ZLog.w(TAG, "subscribe failed: ${e.message}")
                attachedProtocol = null
                conversationSubscriptionId = null
            }
        }
        startWatchdog()
    }

    private fun onFrame(frame: TopicFrame) {
        // 任何到达的帧都算"有动静"：看门狗只关心静默，不关心是否被 apply（重复旧帧
        // 同样说明链路活着）。
        lastFrameAtMs = System.nanoTime()
        if (reducer.apply(frame)) publish()
    }

    /**
     * seq 空洞 → resyncConversationV4 整表重放（[ConversationReducer.onSeqGap]）。
     * 单飞：同一时刻只跑一次，失败后由下一帧空洞或看门狗再触发。
     */
    private fun resyncConversation() {
        val p = connection.protocol.value ?: return
        val subId = conversationSubscriptionId ?: return
        if (!resyncMutex.tryLock()) return
        scope.launch {
            try {
                val snap = reducer.snapshot(sessionId)
                p.resyncConversation(
                    subscriptionId = subId,
                    workspacePath = workspacePath,
                    workspaceIdentity = workspaceIdentity,
                    baseLogEpoch = snap.logEpoch,
                    baseSeq = snap.seq,
                )
                ZLog.i(TAG, "conversation resync requested (seq=${snap.seq})")
            } catch (e: Exception) {
                ZLog.w(TAG, "conversation resync failed: ${e.message}")
            } finally {
                resyncMutex.unlock()
            }
        }
    }

    /** 静默看门狗：运行态下长时间没帧则请求重放（zemote conversation.dart:1055）。 */
    private fun startWatchdog() {
        if (watchdogRunning) return
        watchdogRunning = true
        lastFrameAtMs = System.nanoTime()
        scope.launch {
            while (attachedProtocol != null) {
                delay(WATCHDOG_PERIOD_MS)
                val p = attachedProtocol ?: break
                if (conversationSubscriptionId == null) continue
                val quietMs = (System.nanoTime() - lastFrameAtMs) / 1_000_000L
                if (quietMs < WATCHDOG_QUIET_MS) continue
                // 只在"应该有动静"时巡检：运行态或有 streaming 行；已结束会话静默是正常的
                val ui = _ui.value
                if (!ui.isRunning && ui.rows.none { it.streaming }) continue
                if (p !== attachedProtocol) continue
                ZLog.i(TAG, "watchdog: quiet ${quietMs}ms, requesting resync")
                resyncConversation()
            }
            watchdogRunning = false
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
