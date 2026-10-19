package com.zai.zcode.feature.chat

import com.zai.zcode.core.common.ZLog
import com.zai.zcode.core.network.ZcodeConnection
import com.zai.zcode.core.protocol.rpc.PromiseErrorException
import com.zai.zcode.core.protocol.rpc.asArrayOrNull
import com.zai.zcode.core.protocol.rpc.asBooleanOrNull
import com.zai.zcode.core.protocol.rpc.asIntOrNull
import com.zai.zcode.core.protocol.rpc.asLongOrNull
import com.zai.zcode.core.protocol.rpc.asObjectOrNull
import com.zai.zcode.core.protocol.rpc.asStringOrNull
import com.zai.zcode.core.protocol.v4.RowView
import com.zai.zcode.core.protocol.v4.TopicFrame
import com.zai.zcode.core.protocol.v4.V4
import com.zai.zcode.core.protocol.v4.ZcodeProtocolV4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
// 修复依据（CI run 62）：buildJsonObject 内的 put(String, String) 来自 kotlinx.serialization
// 的 JsonObjectBuilder.put 扩展，漏 import 时编译器把 String 当成 JsonElement 报错
// （ZcodeProtocolV4.sendCommand 里同一陷阱已注明）。
import kotlinx.serialization.json.put

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
 * 会话订阅退避阶梯（末位即稳态间隔）。
 * 与 [com.zai.zcode.feature.sessions.SessionsRepository] 同一组量级：runtime 冷启动
 * 常见耗时在 10s 量级，间隔拉到 60s 时才算进入"慢慢等"。
 */
private val SUBSCRIBE_RETRY_DELAYS_MS = longArrayOf(2_000L, 5_000L, 15_000L, 30_000L, 60_000L)

/**
 * ACK 之后等首帧的宽限期。
 *
 * 本地 bridge 下一个 sessionId 的快照通常在数百毫秒内到达；超过这个时间还没有任何
 * 帧，基本可以判定这次订阅建立了但内容不会来（服务端推流侧失败），应当重订阅而不是
 * 让用户对着骨架屏等下去。给 8s 而不是 2s：relay 链路 + 桌面冷启动读盘的往返需要这个量级。
 */
// 修复依据（真机 21:21 订阅 120s 期间的"读取失败"误报）：grace 必须覆盖订阅超时窗口，
// 否则订阅还在等 ACK，UI 已经按"失败"渲染。对齐服务端冷恢复最坏耗时 + 余量。
private const val SNAPSHOT_GRACE_MS = 150_000L

/** 顶栏标题截断长度：一行宽，超出的部分交给 Text 的 Ellipsis。 */
private const val TITLE_MAX_CHARS = 60

/**
 * 会话页加载态：区分「还没加载出来」与「这个会话是空的」。
 *
 * 修复依据（真机 v0.2.20「会话页一直转圈」）：原先 [ChatRepository.attach] 订阅失败
 * 只打一行 warn 就把 `attachedProtocol` 置 null，**不重试也不置任何态**——UI 侧
 * `rows.isEmpty()` 一律渲染「加载会话…」，于是「电脑端 runtime 没起来」和「打开一个
 * 还没有消息的会话」共用一张永挂的转圈页，用户既不知道该等也不知道该重试。
 * 列表页 v0.2.19 已经把这两件事分开表达，会话页必须跟上。
 */
enum class ChatLoadState {
    /** 首次订阅尚未收到首帧。 */
    Loading,

    /** 至少收到过一帧（可能零行，那是这个会话真的还没有内容）。 */
    Ready,

    /**
     * 这台电脑上该工作区的 runtime 没运行，且会话历史也读不出来。
     * 会话是只读恢复：runtime 起来后自动重试，用户也可以在电脑端打开该工作区。
     */
    RuntimeUnavailable,

    /** 其它订阅/读取错误。 */
    Error,
}

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
    /**
     * 行类型集合与服务端 rows.ts 的 discriminatedUnion 一一对应（9 种）。
     * UI 侧用 when(row) 穷尽分支，编译期保证每种行都有渲染路径；
     * 服务端加新行类型时旧版 App 降级为 Unknown 展示，不静默丢行。
     */
    sealed interface ChatRowKind {
        val rowId: Long
        val streaming: Boolean

        data class UserInput(
            override val rowId: Long,
            override val streaming: Boolean,
            val text: String,
            val inputOrigin: String?,
        ) : ChatRowKind

        data class AssistantText(
            override val rowId: Long,
            override val streaming: Boolean,
            val text: String,
            val model: String?,
        ) : ChatRowKind

        data class Reasoning(
            override val rowId: Long,
            override val streaming: Boolean,
            val text: String,
        ) : ChatRowKind

        data class ToolCall(
            override val rowId: Long,
            override val streaming: Boolean,
            val toolName: String,
            val inputText: String?,
            val status: String?,
        ) : ChatRowKind

        /**
         * turnHeader 骨架：一轮的边界标签 + 权威工时 + 每轮文件改动摘要。
         * fileChanges 齐全才算 diff 卡片可用，缺任一计数就不谎报（spec §5.3）。
         */
        data class TurnHeader(
            override val rowId: Long,
            val state: String?,
            val activeMs: Long?,
            val fileChanges: FileChanges? = null,
        ) : ChatRowKind {
            /** turnHeader 是整个轮的容器标签，不做行内流式指示。 */
            override val streaming: Boolean get() = false

            data class FileChanges(val additions: Int, val deletions: Int, val files: Int)
        }

        data class Subagent(
            override val rowId: Long,
            override val streaming: Boolean,
            val subagentType: String?,
            val summaryText: String?,
            val childSessionId: String?,
        ) : ChatRowKind

        data class Artifact(
            override val rowId: Long,
            val displayName: String?,
            val artifactType: String?,
            val sizeBytes: Long?,
        ) : ChatRowKind {
            override val streaming: Boolean get() = false
        }

        data class HookInvocation(
            override val rowId: Long,
            override val streaming: Boolean,
            val hookEventName: String?,
            val hookCount: Int?,
        ) : ChatRowKind

        data class TimelineMarker(
            override val rowId: Long,
            val markerType: String?,
            val lane: String?,
            val text: String?,
        ) : ChatRowKind {
            override val streaming: Boolean get() = false
        }

        /** 未知 kind：不丢行，原样展示服务端下发的内容（label 为降级文案）。 */
        data class Unknown(
            override val rowId: Long,
            override val streaming: Boolean,
            val label: String,
        ) : ChatRowKind
    }

    data class ChatUi(
        val rows: List<ChatRowKind> = emptyList(),
        val revision: Long? = null,
        val phase: String? = null,
        /** 会话 state 原文（config 投影读 mode/thoughtLevel 用）。 */
        val state: JsonObject? = null,
        /**
         * 消息队列（state.queue.items，queueStateSchema）。
         * 远程操控核心：人不在电脑前时后续指令排队，需要看/改/删。
         */
        val queue: List<QueueItem> = emptyList(),
        /** 队列是否自动消化（false = 停止后暂停，需 setAutoDrain 恢复）。 */
        val queueAutoDrain: Boolean = true,
        /**
         * 运行中的子代理（state.subagents.running，runningSubagentSummarySchema）。
         * 可折叠进度卡：并行任务时用户需要知道电脑上同时跑着什么。
         */
        val subagents: List<SubagentSummary> = emptyList(),
        /** 子代理累计已结束数（endedTotal）。 */
        val subagentsEnded: Int = 0,
        /**
         * 上下文窗口用量（state.usage.contextWindow，0.0~1.0；null = 快照没带）。
         * 输入栏上方进度条：快满时提醒（服务端 autoCompactThresholdTokens 换算）。
         */
        val contextUsage: Float? = null,
        /** phase 是封闭枚举（sessionPhaseSchema：draft、prewarming、running、
         *  completedSuccess、completedInterrupted、error），UI 运行态判定不猜子串（评审 P3）。 */
        val isRunning: Boolean = false,
        /**
         * 最近一个回合是否失败（phase == "error"）。
         * 修复依据（真机 22:21「发消息一直没回复」现场）：电脑端模型 API 请求失败
         * （cli log turn.failed / model_retry network_error），手机端只看到 phase=error
         * 而没有 assistantText 行——静默空白让用户以为是没发出去。失败必须可见。
         */
        val turnFailed: Boolean = false,
        /**
         * 待决交互（state.pendingInteractions）。手机不在电脑前时命令卡在这里，
         * 这是远程操控最核心的一块屏幕——列表为空则不渲染 Dock。
         */
        val pendingInteractions: List<PendingInteraction> = emptyList(),
        /**
         * 会话标题（state.meta.title，snapshot.ts sessionMetaStateSchema）。
         * null = 服务端还没给标题且本地也推不出来，UI 显示占位而不是编一个。
         *
         * 修复依据（真机 v0.2.21「不知道自己在看哪个会话」）：顶栏原先只有「返回 /
         * 看电脑」两个按钮，从列表点进来之后屏幕上没有任何东西指明这是哪个会话——
         * 一次点错要滑到底部靠内容辨认。标题是会话页最便宜的方位感。
         */
        val title: String? = null,
    )

    /**
     * 一个待决交互（snapshot.ts pendingInteractionSchema 投影）。
     *
     * **安全铁律**：[options] 只承载服务端下发的 optionId/label/kind，客户端绝不
     * 自己拼 optionId（硬编码 "allow"/"deny" 在新版服务端上会静默变成别的语义）。
     * 服务端没给 options（旧 CLI）时列表为空，UI 显示"请在电脑端处理"而不是伪造按钮。
     */
    data class PendingInteraction(
        val interactionId: String,
        val kind: String,
        val prompt: String,
        val detail: String? = null,
        /** 工具名（permission 时为被拦下的工具）。 */
        val toolName: String? = null,
        val options: List<InteractOption> = emptyList(),
        /** 允许自由文本（userInput.freeText / permission.freeText）。 */
        val freeText: Boolean = false,
        /** 敏感输入（userInput.sensitive）：不入草稿、不落历史。 */
        val sensitive: Boolean = false,
    )

    /** 一个可点选项：optionId 是服务端事实，label 是给人看的。 */
    data class InteractOption(
        val optionId: String,
        val label: String,
        /** permission：allowOnce / allowAlways / deny / custom；userInput 为 null。 */
        val kind: String? = null,
    )

    /** 队列条目（queueItemSchema 投影：kind=sendText 才显示为消息，compact 等维护意图跳过）。 */
    data class QueueItem(
        val queueItemId: String,
        val text: String,
        /** queued / reserved / promoting。 */
        val state: String,
    )

    /** 运行中子代理摘要（runningSubagentSummarySchema 投影）。 */
    data class SubagentSummary(
        val childSessionId: String,
        val subagentType: String,
        val title: String,
        val summary: String?,
        /** running / waiting / blocked。 */
        val status: String,
    )

    private val reducer = com.zai.zcode.core.protocol.v4.ConversationReducer().apply {
        // 修复依据（真机 v0.2.15）：空洞必须闭环到重放，否则聊天流会安静地停在半句
        onSeqGap = { resyncConversation() }
    }
    private val _ui = MutableStateFlow(ChatUi())
    val ui: StateFlow<ChatUi> = _ui.asStateFlow()

    /** 加载态：UI 用它区分「在等」/「没加载出来」/「这个会话真的没有内容」。 */
    private val _loadState = MutableStateFlow(ChatLoadState.Loading)
    val loadState: StateFlow<ChatLoadState> = _loadState.asStateFlow()

    /** 订阅重试退避阶梯下标；成功订阅后归零。 */
    private var retryDelayIndex = 0
    /** 「零行快照 + 非零水位」矛盾快照的重订阅计数（上限 3，防真空会话死循环）。 */
    /** 退避重试协程：重订阅时取消旧的，同一时刻只有一个在跑。 */
    private var retryJob: Job? = null
    /** 已收到过首帧：重试期间保留已渲染的行，不把内容区刷回骨架屏。 */
    private var hasDeliveredSnapshot = false
    /**
     * ACK 成功但迟迟不来快照的兜底协程。
     * 修复依据（真机 v0.2.20「会话页一直转圈」）：订阅 ACK 成功不等于内容到了——
     * 服务端 ack 之后还要推快照帧，这一步失败时客户端处于"订阅已建立、零行"
     * 的状态，[hasDeliveredSnapshot] 永远为 false，UI 骨架屏就一直挂着。
     */
    private var snapshotGraceJob: Job? = null

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
                    onProtocolClosed()
                    return@collect
                }
                attach(p)
            }
        }
    }

    /** 连接断开：保留已渲染的行（弱信号比白屏好），但把加载态退回"在等"。 */
    private fun onProtocolClosed() {
        attachedProtocol = null
        conversationSubscriptionId = null
        retryJob?.cancel()
        retryJob = null
        retryDelayIndex = 0
        snapshotGraceJob?.cancel()
        snapshotGraceJob = null
        hasDeliveredSnapshot = false
        _loadState.value = ChatLoadState.Loading
    }

    /**
     * 用户点了「重试」：丢掉退避阶梯立刻重订阅一次。
     * 机器 succeeded 之外的所有态都能重试——runtime-unavailable 等的是电脑端，
     * Error 等的是网络，两者用户侧动作都是「再试一次」。
     */
    fun retrySubscribe() {
        val p = connection.protocol.value ?: return
        retryJob?.cancel()
        retryJob = null
        retryDelayIndex = 0
        attachedProtocol = null
        subscribe(p)
    }

    private fun attach(p: ZcodeProtocolV4) {
        if (attachedProtocol === p) return
        attachedProtocol = p
        p.onConversationFrame = { frame, _ -> onFrame(frame) }
        subscribe(p)
        startWatchdog()
    }

    /**
     * 一次订阅尝试；失败后按退避阶梯重试（[scheduleSubscribeRetry]）。
     *
     * 修复依据（真机 v0.2.20「会话页一直转圈」）：手机先开、电脑后开是最常见的顺序，
     * 而 conversation 订阅同样是"桌面没有活跃 runtime 就读不出来"。一次性订阅意味着
     * 这个顺序下会话页永远停在转圈。列表页 v0.2.19 已经用同一套退避解决，这里对齐。
     */
    private fun subscribe(p: ZcodeProtocolV4) {
        retryJob?.cancel()
        retryJob = null
        retryDelayIndex = 0
        // 已经收到过首帧就不要退回 Loading：重订阅期间把用户正在读的对话刷成骨架屏，
        // 是比"少了一点实时更新"严重得多的体验损失。
        if (!hasDeliveredSnapshot) _loadState.value = ChatLoadState.Loading
        scope.launch {
            try {
                // 重连恢复：带上次水位重订阅，服务端按 replayable 补发；无水位则全新快照
                ZLog.i(TAG, "subscribe request: path=$workspacePath id=$sessionId identity=$workspaceIdentity baseSeq=$lastSeq")
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
                startSnapshotGrace()
                ZLog.i(TAG, "subscribed conversation/$sessionId")
                // 注意：此时还没收到 snapshot，不能置 Ready——首帧到达才算数
                // （ACK 成功但零帧是另一类故障，提前撤骨架屏会变成空白页）。
            } catch (e: Exception) {
                if (!isRuntimeUnavailable(e)) {
                    ZLog.w(TAG, "subscribe failed: ${e.message}")
                }
                // 关键：订阅失败后必须把 attachedProtocol 置回 null，否则下一次
                // attach(同一实例) 会被 `=== p` 判定为已订阅而直接跳过，重连后
                // conversation 订阅永不重发（真机 19:30 bridge degrade→reopen 复现）。
                attachedProtocol = null
                conversationSubscriptionId = null
                scheduleSubscribeRetry(e)
            }
        }
    }

    /**
     * ACK 之后等首帧的兜底。
     *
     * 修复依据（真机 v0.2.20「会话页一直转圈」）：`subscribeConversation` 的 ACK 成功
     * 只代表服务端接受了订阅，快照帧是之后单独推的。这一步静默失败时客户端
     * 「已订阅 + 零行」，[hasDeliveredSnapshot] 永远 false，UI 骨架屏无限期挂着，
     * 而没有任何逻辑会再发一次订阅。这里给 ACK 一个 grace 期：超时仍无帧就
     * 按失败处理重订阅，让转圈页有一个可以自行脱离的出口。
     */
    private fun startSnapshotGrace() {
        snapshotGraceJob?.cancel()
        if (hasDeliveredSnapshot) return
        snapshotGraceJob = scope.launch {
            delay(SNAPSHOT_GRACE_MS)
            if (hasDeliveredSnapshot) return@launch
            ZLog.w(TAG, "conversation/$sessionId acked but no frame in ${SNAPSHOT_GRACE_MS}ms, resubscribing")
            val p = connection.protocol.value ?: return@launch
            attachedProtocol = null
            subscribe(p)
        }
    }

    /**
     * 订阅失败退避重试。runtime unavailable 是"可以等"的稳态，退避到 60s 后仍在重试。
     *
     * 只保留一份旧内容：失败时**不清行**。会话是落盘的实体，runtime 停了它还在；
     * 上一份快照除了实时状态依然准确，清空等于把"读不出来"谎报成"空会话"。
     */
    private fun scheduleSubscribeRetry(error: Exception) {
        if (!hasDeliveredSnapshot) {
            _loadState.value = if (isRuntimeUnavailable(error)) {
                ChatLoadState.RuntimeUnavailable
            } else {
                ChatLoadState.Error
            }
        }
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            while (true) {
                val delayMs = SUBSCRIBE_RETRY_DELAYS_MS[
                    retryDelayIndex.coerceAtMost(SUBSCRIBE_RETRY_DELAYS_MS.lastIndex)
                ]
                retryDelayIndex++
                delay(delayMs)
                val p = connection.protocol.value ?: continue
                try {
                    val ack = p.subscribeConversation(
                        workspacePath = workspacePath,
                        sessionId = sessionId,
                        workspaceIdentity = workspaceIdentity,
                        baseLogEpoch = lastLogEpoch,
                        baseSeq = lastSeq.takeIf { it > 0 },
                    )
                    attachedProtocol = p
                    p.onConversationFrame = { frame, _ -> onFrame(frame) }
                    conversationSubscriptionId = ack.subscriptionId
                    retryDelayIndex = 0
                    startSnapshotGrace()
                    ZLog.i(TAG, "conversation subscribe recovered for $sessionId")
                    startWatchdog()
                    return@launch
                } catch (e: Exception) {
                    if (!isRuntimeUnavailable(e)) {
                        ZLog.w(TAG, "conversation subscribe retry failed: ${e.message}")
                    }
                    if (!hasDeliveredSnapshot) {
                        _loadState.value = if (isRuntimeUnavailable(e)) {
                            ChatLoadState.RuntimeUnavailable
                        } else {
                            ChatLoadState.Error
                        }
                    }
                }
            }
        }
    }

    /**
     * 服务端「runtime 未运行」识别：code === ZCODE_AGENT_RUNTIME_UNAVAILABLE。
     * 桌面按 code 判、不按 message 匹配——message 是英文展示串且会变，code 是协议约定。
     * 老桌面不回 code 时回退到 message 前缀匹配（与列表页同一判据）。
     */
    private fun isRuntimeUnavailable(e: Exception): Boolean {
        val code = (e as? PromiseErrorException)?.code
        if (code != null) return code == V4.RUNTIME_UNAVAILABLE_CODE
        return e.message?.contains("runtime is not running", ignoreCase = true) == true
    }

    private fun onFrame(frame: TopicFrame) {
        // 任何到达的帧都算"有动静"：看门狗只关心静默，不关心是否被 apply（重复旧帧
        // 同样说明链路活着）。
        lastFrameAtMs = System.nanoTime()
        // 修复依据（真机 20:34 现场日志「会话内容看过之后又变成没有消息」）：
        // resync（recovery 快照）返回了 rows=0/state=null 的空快照，reducer 整表替换
        // 语义把本地已有的 3 行对话清空，屏幕从"有内容"跳到"还没有消息"。
        // 电脑端 CLI 正在跑长工具调用时本就静默（无 token 推送），看门狗按设计请求
        // 重放，但服务端此刻快照为空——那是"重放时刻的瞬时事实"，不是"会话没有内容"。
        // 防御：本地已有非空行时，空快照不应用；改走全新订阅（initial 快照由 CLI
        // 恢复历史 Session 生成，已证实带完整行）。只有全新订阅也返回空，才接受
        // "会话真的没有内容"。
        if (frame.isSnapshot) {
            val snap = frame.snapshotObject()
            // 行数提取对齐 rowsWindowSchema：行数组在 rows.window（修复根因见
            // ConversationReducer.rowsOrEmpty 注释），rows 直接是数组的旧形状兜底。
            val snapRows = snap?.get("rows")?.let { r ->
                (r as? JsonObject)?.get("window").asArrayOrNull()?.size
                    ?: (r as? kotlinx.serialization.json.JsonArray)?.size
            } ?: -1
            val currentRows = reducer.snapshot(sessionId).rows.size
            ZLog.i(TAG, "snapshot frame: fromSeq=${frame.fromSeq} toSeq=${frame.toSeq} rows=$snapRows local=$currentRows")
            // 防御：快照带行（官方语义，尾部 60 行）时正常应用即可，历史已在其中。
            // 零行快照且本地有内容：不应用（resync 瞬时空快照防御，20:34 现场）。
            if (snapRows == 0 && currentRows > 0) {
                ZLog.w(TAG, "empty snapshot for non-empty session ($currentRows rows), ignore")
                publish()
                return
            }
            // 「rows 缺失（-1）或零行 + 高水位」：服务端投影还没装载行（CLI 冷恢复中），
            // 快照照常应用，行表由 rowsRange 回填兜底（21:35 现场就是这条路径）。
            if (snapRows <= 0 && frame.toSeq > 0L) {
                val applied = reducer.apply(frame)
                ZLog.d(TAG, "onFrame snapshot(no rows) applied=$applied, backfilling history via rowsRange")
                if (applied) publish()
                loadHistoryBackfill()
                return
            }
        }
        val applied = reducer.apply(frame)
        ZLog.d(TAG, "onFrame topic=${frame.topic} applied=$applied")
        if (applied) publish()
    }

    /**
     * 历史行回填/上滑加载：rowsRange 从窗口首行向前拉一页 seed 进 reducer。
     *
     * 对齐官方 conversationProjectionStore.loadOlder（游标 = 当前窗口首行 rowId，
     * 单飞防重入）。首屏快照只带尾部 60 行（snapshotTailWindowRows），滑到顶时
     * UI 调 [loadOlder] 继续向上翻；快照零行（CLI 冷恢复中）时作为首屏兜底拉一次。
     */
    private var historyBackfillJob: kotlinx.coroutines.Job? = null
    private fun loadHistoryBackfill() = loadOlderInternal()

    /** UI 上滑到顶时调用：向上翻一页历史。返回是否有更多（供 UI 显示"没有更早的消息"）。 */
    fun loadOlder() {
        loadOlderInternal()
    }

    private fun loadOlderInternal() {
        if (historyBackfillJob?.isActive == true) return
        historyBackfillJob = scope.launch {
            try {
                val p = connection.protocol.value ?: return@launch
                // 游标 = 当前已加载的最小 rowId；无行（首屏兜底）时从尾部拉
                val beforeRowId = reducer.snapshot(sessionId).rows
                    .minOfOrNull { RowView(it).rowId ?: Long.MAX_VALUE }
                    ?.takeIf { it != Long.MAX_VALUE }
                val page = p.conversationRowsRange(
                    sessionId = sessionId,
                    workspacePath = workspacePath,
                    workspaceIdentity = workspaceIdentity,
                    beforeRowId = beforeRowId,
                )
                if (page == null) {
                    ZLog.w(TAG, "history loadOlder: rowsRange returned non-object")
                    return@launch
                }
                reducer.seedRows(page.rows)
                ZLog.i(TAG, "history loadOlder: before=$beforeRowId got=${page.rows.size} hasMore=${page.hasMore}")
                if (page.rows.isNotEmpty()) {
                    // 拿到了可渲染的历史行，撤掉骨架屏
                    if (!hasDeliveredSnapshot) {
                        hasDeliveredSnapshot = true
                        snapshotGraceJob?.cancel()
                        snapshotGraceJob = null
                        _loadState.value = ChatLoadState.Ready
                    }
                    publish()
                }
                _hasMoreHistory.value = page.hasMore
            } catch (e: Exception) {
                ZLog.w(TAG, "history loadOlder failed: ${e.message}")
            }
        }
    }

    /** 是否还有更早的历史（rowsRange.hasMore 投影；UI 到顶提示用）。 */
    private val _hasMoreHistory = MutableStateFlow(true)
    val hasMoreHistory: StateFlow<Boolean> = _hasMoreHistory.asStateFlow()

    // ── 模型选择（官方 mobile 语义：model-selection.getView + switchModelConfig）──

    /** 可选模型扁平列表（provider × model），null = 读取失败（UI 显示不可选）。 */
    suspend fun loadModelOptions(): List<ModelOption> {
        val p = connection.protocol.value ?: return emptyList()
        return runCatching {
            val view = p.modelSelectionView() ?: return emptyList()
            view.providers.flatMap { provider ->
                provider.models.map { m ->
                    ModelOption(
                        providerId = provider.providerId,
                        providerName = provider.providerName,
                        modelId = m.modelId,
                        label = if (provider.providerName.isBlank()) m.name else "${provider.providerName} · ${m.name}",
                        supportedThoughtLevels = m.supportedThoughtLevels,
                    )
                }
            }
        }.getOrElse {
            ZLog.w(TAG, "model selection view failed: ${it.message}")
            emptyList()
        }
    }

    /** 当前选中（preferredSelection）；读取失败返回 null（UI 显示默认）。 */
    suspend fun loadCurrentSelection(): ModelOption? {
        val p = connection.protocol.value ?: return null
        return runCatching {
            val view = p.modelSelectionView() ?: return null
            val sel = view.preferred ?: return null
            ModelOption(sel.providerId, "", sel.modelId, sel.modelId)
        }.getOrElse { null }
    }

    /** 切换本会话模型（switchModelConfig；thought 沿用官方必填形状，默认 medium）。 */
    suspend fun switchModel(providerId: String, modelId: String, thought: String = "medium"): Boolean =
        sendCasCommand("switchModelConfig", provider = providerId, model = modelId, thought = thought)

    /**
     * 切换协作模式（switchCollaborationMode）。
     * 官方四档（zh-CN 文案）：plan=计划模式, guarded=变更前确认, yolo=自主模式, build=完全访问。
     */
    suspend fun switchMode(mode: String): Boolean =
        sendCasCommand("switchCollaborationMode", mode = mode)

    /**
     * 切换思考档位：复用 switchModelConfig（官方语义：thought 是模型配置的附属，
     * useDraftConfigControl.handleDraftSelectThought 与 model 一起写回同一命令）。
     *
     * 修复依据（用户报「切完档位/模型后回复完又变回默认、电脑端也没变」）：
     * 此处此前读 preferredSelection——那是**全局默认偏好**，不是本会话当前选型。
     * 会话已切到模型 X 后再切档位，命令仍以 GLM 的名义发出，把会话模型**切回
     * GLM**——这正是「变回默认」的机制。当前选型必须读会话快照
     * state.config.modelSelection（CLI 投影权威），preferred 只作最后兜底。
     */
    suspend fun switchThought(thought: String): Boolean {
        val snap = reducer.snapshot(sessionId)
        val sel = snap.topLevel?.get("state")?.asObjectOrNull()?.get("config")?.asObjectOrNull()
            ?.get("modelSelection")?.asObjectOrNull()
            ?: snap.state?.get("config")?.asObjectOrNull()
                ?.get("modelSelection")?.asObjectOrNull()
        val provider = sel?.get("providerId").asStringOrNull()
        val model = sel?.get("modelId").asStringOrNull()
        return if (provider != null && model != null) {
            switchModel(provider, model, thought)
        } else {
            // 快照还没到（刚进会话）：退全局偏好
            val cur = loadCurrentSelection() ?: return false
            switchModel(cur.providerId, cur.modelId, thought)
        }
    }

    // ── 队列管理（editQueueItem / reorderQueueItem / deleteQueueItem / setAutoDrain）──
    // 对齐 command.ts:210-217。全部是 CAS 命令（COMMANDS_REQUIRING_BASE_REVISION）。

    /** 编辑排队消息文本。 */
    suspend fun editQueueItem(queueItemId: String, newText: String): Boolean =
        sendCasCommand("editQueueItem", queueItemId = queueItemId, newText = newText)

    /** 调整排队顺序：移到 beforeQueueItemId 之前；null = 移到队尾。 */
    suspend fun reorderQueueItem(queueItemId: String, beforeQueueItemId: String?): Boolean =
        sendCasCommand("reorderQueueItem", queueItemId = queueItemId, beforeQueueItemId = beforeQueueItemId)

    /** 删除排队消息。 */
    suspend fun deleteQueueItem(queueItemId: String): Boolean =
        sendCasCommand("deleteQueueItem", queueItemId = queueItemId)

    /** 恢复/暂停队列自动消化。 */
    suspend fun setAutoDrain(autoDrain: Boolean): Boolean =
        sendCasCommand("setAutoDrain", autoDrain = autoDrain)

    /** 队列投影：只保留 sendText 消息（compact 等维护意图不进 UI 列表）。 */
    private fun projectQueue(state: JsonObject?): List<QueueItem> {
        val items = state?.get("queue")?.asObjectOrNull()
            ?.get("items").asArrayOrNull() ?: return emptyList()
        return items.mapNotNull { el ->
            val o = el.asObjectOrNull() ?: return@mapNotNull null
            val kind = o["kind"].asStringOrNull() ?: return@mapNotNull null
            if (kind != "sendText") return@mapNotNull null
            val id = o["queueItemId"].asStringOrNull() ?: return@mapNotNull null
            QueueItem(
                queueItemId = id,
                text = o["text"].asStringOrNull().orEmpty(),
                state = o["dispatch"]?.asObjectOrNull()?.get("state").asStringOrNull() ?: "queued",
            )
        }
    }

    /** 子代理投影（runningSubagentSummarySchema）。 */
    private fun projectSubagents(state: JsonObject?): List<SubagentSummary> {
        val running = state?.get("subagents")?.asObjectOrNull()
            ?.get("running").asArrayOrNull() ?: return emptyList()
        return running.mapNotNull { el ->
            val o = el.asObjectOrNull() ?: return@mapNotNull null
            val id = o["childSessionId"].asStringOrNull() ?: return@mapNotNull null
            SubagentSummary(
                childSessionId = id,
                subagentType = o["subagentType"].asStringOrNull() ?: "subagent",
                title = o["title"].asStringOrNull() ?: "子代理",
                summary = o["summary"].asStringOrNull(),
                status = o["status"].asStringOrNull() ?: "running",
            )
        }
    }

    /**
     * 上下文用量投影：usedTokens / maxTokens（autoCompactThresholdTokens 存在时以它
     * 为分母——用户关心的是"距自动压缩还有多少"，不是"距硬上限"）。
     */
    private fun projectContextUsage(state: JsonObject?): Float? {
        val cw = state?.get("usage")?.asObjectOrNull()
            ?.get("contextWindow")?.asObjectOrNull() ?: return null
        val used = cw["usedTokens"].asLongOrNull() ?: return null
        val threshold = cw["autoCompactThresholdTokens"].asLongOrNull()
        val max = cw["maxTokens"].asLongOrNull() ?: return null
        val denom = if (threshold != null && threshold > 0) threshold else max
        if (denom <= 0) return null
        return (used.toFloat() / denom).coerceIn(0f, 1f)
    }

    /**
     * CAS 命令统一发送（对齐官方 zcodeV4HostCommand.sendHostCasCommandV4）：
     * switchModelConfig / switchCollaborationMode 在 COMMANDS_REQUIRING_BASE_REVISION
     * 里，信封必须带 baseRevision；服务端并发裁决返回 stale 时用 revisionAtDecision
     * 重试（最多 4 次）。此前不带 baseRevision 直接被拒（用户报「改不了」的根因）。
     */
    private suspend fun sendCasCommand(
        type: String,
        provider: String? = null,
        model: String? = null,
        thought: String? = null,
        mode: String? = null,
        queueItemId: String? = null,
        newText: String? = null,
        beforeQueueItemId: String? = null,
        autoDrain: Boolean? = null,
    ): Boolean {
        val p = connection.protocol.value ?: return false
        return runCatching {
            var baseRevision = _ui.value.revision ?: 0L
            repeat(4) {
                val payload = buildJsonObject {
                    if (provider != null) put("provider", provider)
                    if (model != null) put("model", model)
                    if (thought != null) put("thought", thought)
                    if (mode != null) put("mode", mode)
                    if (queueItemId != null) put("queueItemId", queueItemId)
                    if (newText != null) put("newText", newText)
                    // beforeQueueItemId 是 nullable 语义（null=移到队尾），键必须在场
                    if (type == "reorderQueueItem") put("beforeQueueItemId", beforeQueueItemId)
                    if (autoDrain != null) put("autoDrain", autoDrain)
                }
                val envelope = com.zai.zcode.core.protocol.v4.buildCommandEnvelope(
                    commandId = java.util.UUID.randomUUID().toString(),
                    clientId = clientId,
                    sessionId = sessionId,
                    type = type,
                    payload = payload,
                    baseRevision = baseRevision,
                )
                val ack = p.sendCommand(workspacePath, envelope, workspaceIdentity, timeoutMs = 30_000)
                when {
                    ack.status == "accepted" -> return true
                    // stale：并发修订，用服务端裁决时的 revision 重试
                    ack.status == "stale" && ack.revisionAtDecision != null -> {
                        baseRevision = ack.revisionAtDecision!!
                    }
                    else -> {
                        ZLog.w(TAG, "$type rejected: ${ack.status} ${ack.reasonCode ?: ""} ${ack.message ?: ""}")
                        return false
                    }
                }
            }
            ZLog.w(TAG, "$type stale after 4 attempts")
            false
        }.getOrElse {
            ZLog.w(TAG, "$type failed: ${it.message}")
            false
        }
    }

    // ── Git 分支（官方 useGitBranchSwitcher：workspace 级，一次拉取缓存）──

    private val _gitBranches = MutableStateFlow<ZcodeProtocolV4.GitBranchList?>(null)
    val gitBranches: StateFlow<ZcodeProtocolV4.GitBranchList?> = _gitBranches.asStateFlow()

    /** 拉取本地分支（进入会话时调用一次；非 git 仓库时 isRepository=false）。 */
    suspend fun loadGitBranches() {
        val p = connection.protocol.value ?: return
        _gitBranches.value = runCatching {
            p.gitLocalBranches(workspacePath, workspaceIdentity)
        }.getOrElse {
            ZLog.w(TAG, "git branches failed: ${it.message}")
            null
        }
    }

    /** 切换分支（成功后重拉刷新当前分支名）。 */
    suspend fun switchBranch(name: String): Boolean {
        val p = connection.protocol.value ?: return false
        val ok = runCatching {
            p.gitSwitchBranch(workspacePath, name, workspaceIdentity)
        }.getOrElse {
            ZLog.w(TAG, "switchBranch failed: ${it.message}")
            false
        }
        if (ok) loadGitBranches()
        return ok
    }

    /** 一个可选模型项（选择器列表行）。 */
    data class ModelOption(
        val providerId: String,
        val providerName: String,
        val modelId: String,
        val label: String,
        /** 该模型支持的思考档位（服务端 optionSpecs.reasoningLevel.values）。 */
        val supportedThoughtLevels: List<String> = emptyList(),
    )

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
        // 诊断（真机 20:32 现象：帧持续 applied=true 但屏幕空白）：行数必须落日志，
        // 区分「快照/增量到达但归约后零行」与「有行但 UI 没渲染」。
        ZLog.d(TAG, "publish rows=${st.rows.size} seq=${st.seq} state=${st.state != null}")
        // 首帧到达才算列表真的可用（ACK 成功但零帧是另一类故障，不能提前撤骨架屏）。
        if (!hasDeliveredSnapshot) {
            hasDeliveredSnapshot = true
            snapshotGraceJob?.cancel()
            snapshotGraceJob = null
            _loadState.value = ChatLoadState.Ready
            // 拿到了可渲染的行：矛盾快照的重试计数归零（可能是换会话后复用本仓库实例）。
        }
        val revision = st.state?.get("revision")
        _ui.value = ChatUi(
            rows = st.rows.map { row -> toChatRow(row) },
            // 修复依据（P2-6，评审 2026-10-17）：复用 rpc 包 canonical helper，
            // 不再手写 (x as? JsonPrimitive)?.content 解析
            revision = revision.asLongOrNull(),
            phase = st.state?.get("phase").asStringOrNull(),
            state = st.state,
            // 修复依据（P3，评审 2026-10-17）：phase 是封闭枚举，运行态在此映射，
            // 不在 UI 里 contains("run") 猜子串
            isRunning = st.state?.get("phase").asStringOrNull()
                ?.let { it == "draft" || it == "prewarming" || it == "running" } == true,
            turnFailed = st.state?.get("phase").asStringOrNull() == "error",
            // 修复依据（真机「电脑端有审批条手机端没有」）：pendingInteractions/queue/
            // subagents/usage 是快照顶层键 + state.updated patch 顶层键，不在 state
            // 嵌套对象里。topLevel 优先，state 兜底（兼容旧读法）。
            queue = projectQueue(st.topLevel ?: st.state),
            queueAutoDrain = (st.topLevel ?: st.state)?.get("queue")?.asObjectOrNull()
                ?.get("autoDrain")?.let { it == kotlinx.serialization.json.JsonPrimitive(true) } ?: true,
            subagents = projectSubagents(st.topLevel ?: st.state),
            subagentsEnded = (st.topLevel ?: st.state)?.get("subagents")?.asObjectOrNull()
                ?.get("endedTotal").asIntOrNull() ?: 0,
            contextUsage = projectContextUsage(st.topLevel ?: st.state),
            pendingInteractions = projectPendingInteractions(st.topLevel ?: st.state),
            title = projectTitle(st.state, st.rows),
        )
    }

    /**
     * 展示标题：优先 state.meta.title，取不到时退回首条用户输入。
     *
     * 两段降级是必要的而不是冗余：
     * 1. `meta.title` 由桌面端生成或用户改名写入（titleSource: generated|custom），
     *    新会话在首轮结束前它是空串——此时顶栏不能显示空白。
     * 2. 退回首条用户输入而非「新会话」：远程操控场景里用户是靠"我刚才说的那句"
     *    认会话的，用户自己的第一句话比任何占位文案都更像标题。
     * 3. 两者都没有（还在 Loading / 桌面没起）就返回 null，让 UI 显示占位，
     *    不编造内容——编造的标题比没有标题更容易误导。
     *
     * 截断到 60 字：用户常常一次贴几十行输入，整段进 StateFlow 会让顶栏在流式过程中
     * 每帧重组一次，而它只有一行宽（Text 自身还会 Ellipsis）。
     */
    private fun projectTitle(state: JsonObject?, rows: List<JsonObject>): String? {
        val fromMeta = state?.get("meta").asObjectOrNull()
            ?.get("title").asStringOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (fromMeta != null) return fromMeta.take(TITLE_MAX_CHARS)
        val firstUser = rows.firstOrNull { RowView(it).kind == "userInput" }
            ?.let { RowView(it).text }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        return firstUser?.replace('\n', ' ')?.take(TITLE_MAX_CHARS)
    }

    /**
     * state.pendingInteractions → [PendingInteraction]（snapshot.ts:311 的投影）。
     *
     * 宽容解析：单个交互结构不合预期就跳过它，不至于让整份快照（连同已经解析好的
     * 其他交互）一起消失。用户宁可少看一个审批卡，也不该看到空白页。
     */
    private fun projectPendingInteractions(state: JsonObject?): List<PendingInteraction> {
        val list = state?.get("pendingInteractions").asArrayOrNull() ?: return emptyList()
        return list.mapNotNull { el ->
            val raw = el.asObjectOrNull() ?: return@mapNotNull null
            val id = raw["interactionId"].asStringOrNull() ?: return@mapNotNull null
            val kind = raw["kind"].asStringOrNull() ?: return@mapNotNull null
            val payload = raw["payload"].asObjectOrNull() ?: return@mapNotNull null
            when (kind) {
                "permission" -> PendingInteraction(
                    interactionId = id,
                    kind = kind,
                    prompt = payload["summary"].asStringOrNull()
                        ?: payload["toolName"].asStringOrNull()
                        ?: "需要授权",
                    toolName = payload["toolName"].asStringOrNull(),
                    options = projectPermissionOptions(payload),
                    freeText = payload["freeText"].asBooleanOrNull() == true,
                )

                "userInput" -> PendingInteraction(
                    interactionId = id,
                    kind = kind,
                    prompt = payload["prompt"].asStringOrNull() ?: "需要你输入",
                    toolName = payload["toolName"].asStringOrNull(),
                    options = projectUserInputOptions(payload),
                    freeText = payload["freeText"].asBooleanOrNull() == true,
                    sensitive = payload["sensitive"].asBooleanOrNull() == true,
                )

                // workspaceHookReview 的载荷形状与审批不同（review items 而非 options），
                // 手机端不猜它的语义——给出可读提示，由用户在桌面处理。
                else -> PendingInteraction(
                    interactionId = id,
                    kind = kind,
                    prompt = when (kind) {
                        "workspaceHookReview" -> "Hook 审查需要确认"
                        else -> "需要你的确认"
                    },
                )
            }
        }
    }

    /**
     * permission options（permissionRequestPayloadSchema）：optionId/label/kind。
     * fullAccessOption 是 additive 的独立能力（snapshot.ts:246），服务端给了才追加，
     * 客户端绝不自己合成——"全权放行"是一次性把权限交出去，只能由服务端定义。
     */
    private fun projectPermissionOptions(payload: JsonObject): List<InteractOption> {
        val options = payload["options"].asArrayOrNull()?.mapNotNull { el ->
            val o = el.asObjectOrNull() ?: return@mapNotNull null
            val optionId = o["optionId"].asStringOrNull() ?: return@mapNotNull null
            InteractOption(
                optionId = optionId,
                label = o["label"].asStringOrNull() ?: optionId,
                kind = o["kind"].asStringOrNull(),
            )
        } ?: emptyList()
        val fullAccess = payload["fullAccessOption"].asObjectOrNull()?.let { o ->
            val optionId = o["optionId"].asStringOrNull() ?: return@let null
            InteractOption(
                optionId = optionId,
                label = o["label"].asStringOrNull() ?: "放行全部",
                kind = o["kind"].asStringOrNull(),
            )
        }
        return if (fullAccess == null) options else options + fullAccess
    }

    /** userInput options（{optionId,label}）；AskUserQuestion 的多题走 questions 不用这里。 */
    private fun projectUserInputOptions(payload: JsonObject): List<InteractOption> =
        payload["options"].asArrayOrNull()?.mapNotNull { el ->
            val o = el.asObjectOrNull() ?: return@mapNotNull null
            val optionId = o["optionId"].asStringOrNull() ?: return@mapNotNull null
            InteractOption(optionId, o["label"].asStringOrNull() ?: optionId)
        } ?: emptyList()

    /**
     * 解决一个待决交互（resolveInteraction，command.ts:219）。
     *
     * 修复依据（远程操控核心场景）：人不在电脑前，命令卡在审批上就永远不动了。
     * 这一步是产品存在的意义所在。
     *
     * 幂等：服务端先到先得，晚到返回 reasonCode=proto.alreadyResolved（command.ts:220
     * 注释）。那不是失败——说明有人在别处（桌面 / 另一个客户端）已经处理了，
     * 这里按成功返回，UI 撤掉卡片即可，不该弹"失败"。
     *
     * @param optionId 服务端下发的选项 id（[InteractOption.optionId]）
     * @param freeText 自由文本答案（userInput/freeText 场景）
     */
    suspend fun resolveInteraction(
        interactionId: String,
        optionId: String? = null,
        freeText: String? = null,
    ): Boolean {
        val p = connection.protocol.value ?: return false
        return runCatching {
            val answer = buildJsonObject {
                if (optionId != null) put("optionId", optionId)
                if (freeText != null) put("freeText", freeText)
            }
            val envelope = com.zai.zcode.core.protocol.v4.buildCommandEnvelope(
                commandId = java.util.UUID.randomUUID().toString(),
                clientId = clientId,
                sessionId = sessionId,
                type = "resolveInteraction",
                payload = buildJsonObject {
                    put("interactionId", interactionId)
                    put("answer", answer)
                },
            )
            val ack = p.sendCommand(workspacePath, envelope, workspaceIdentity)
            if (ack.status == "accepted") return@runCatching true
            // alreadyResolved：别人先处理了。按成功对待——卡片该消失，不是该报错。
            if (ack.reasonCode?.endsWith("alreadyResolved") == true) {
                ZLog.i(TAG, "interaction $interactionId already resolved elsewhere")
                return@runCatching true
            }
            ZLog.w(TAG, "resolveInteraction rejected: ${ack.status} ${ack.reasonCode ?: ""}")
            false
        }.getOrElse {
            ZLog.w(TAG, "resolveInteraction failed: ${it.message}")
            false
        }
    }

    /**
     * 行投影：服务端 RowView → 封闭的行模型。
     *
     * streaming 判据：state 字段非终态（对齐 TS 侧 streaming row 语义）。
     * 未知 kind 降级为 [ChatRowKind.Unknown] 而非丢弃 —— 服务端加行类型时，
     * 旧版 App 应当展示「看不懂的一行」而不是悄悄把回复吃掉。
     */
    private fun toChatRow(raw: kotlinx.serialization.json.JsonObject): ChatRowKind {
        val v = RowView(raw)
        // 修复依据（真机 01:10 截图：已结束会话末尾挂着绿色呼吸点）：state 缺失的行
        // 不能视为 streaming——历史恢复的行没有 state 字段，旧判定 `null != done/error`
        // 走 else 分支恒 false 是对的，但部分历史行带中间态 state（inputStreaming 等
        // 落盘时的瞬时值），恢复后永远显示"流式中"。streaming 必须同时满足
        // 会话整体在运行（publish 侧 isRunning 已判 phase），行级只信终态判定。
        val streaming = ui.value.isRunning &&
            v.state?.let { it != "done" && it != "error" && it != "aborted" } == true
        val rowId = v.rowId ?: -1
        return when (v.kind) {
            "userInput" -> ChatRowKind.UserInput(rowId, streaming, v.text.orEmpty(), v.inputOrigin)
            "assistantText" -> ChatRowKind.AssistantText(rowId, streaming, v.text.orEmpty(), v.model)
            "reasoning" -> ChatRowKind.Reasoning(rowId, streaming, v.text.orEmpty())
            "toolCall" -> ChatRowKind.ToolCall(rowId, streaming, v.toolName ?: "tool", v.inputText, v.status)
            "turnHeader" -> ChatRowKind.TurnHeader(rowId, v.state, v.activeMs, fileChangesOf(v))
            "subagent" -> ChatRowKind.Subagent(rowId, streaming, v.subagentType, v.summaryText, v.childSessionId)
            "artifact" -> ChatRowKind.Artifact(rowId, v.displayName, v.artifactType, v.sizeBytes)
            "hookInvocation" -> ChatRowKind.HookInvocation(rowId, streaming, v.hookEventName, v.hookCount)
            "timelineMarker" -> ChatRowKind.TimelineMarker(rowId, v.markerType, v.lane, markerText(v))
            else -> ChatRowKind.Unknown(rowId, streaming, v.kind ?: "unknown")
        }
    }

    /** 三个计数必须齐全才构成摘要卡，缺任一都不谎报「0 个文件已更改」。 */
    private fun fileChangesOf(v: RowView): ChatRowKind.TurnHeader.FileChanges? {
        val additions = v.fileChangeAdditions ?: return null
        val deletions = v.fileChangeDeletions ?: return null
        val files = v.fileChangeFiles ?: return null
        return ChatRowKind.TurnHeader.FileChanges(additions, deletions, files)
    }

    /**
     * marker 文案：payload 是 discriminated union，按 type 挑字段。
     * 取不到就用 type 本身——marker 只是时间线分隔，不需要为了它引入整套 union 模型。
     */
    private fun markerText(v: RowView): String? = when (v.markerType) {
        "goalSet" -> v.markerObjective
        "checkpointRestored" -> v.markerDetail ?: "已恢复检查点"
        "forkCreated" -> v.markerDetail ?: "已创建分支会话"
        "retryNotice" -> v.markerDetail ?: "重试中"
        // modelChange（rows.ts:364）：官方渲染为轻边界「provider · model · 档位」，
        // 而非裸 modelId 文本——此前落到 else 兜底只显示 toModel，丢 provider 与档位。
        "modelChange" -> listOfNotNull(
            v.markerToProvider?.substringBeforeLast("/")?.takeIf { it.isNotBlank() },
            v.markerToModel?.substringAfterLast("/")?.takeIf { it.isNotBlank() },
            v.markerToThought?.takeIf { it.isNotBlank() },
        ).joinToString(" · ").takeIf { it.isNotBlank() }
        else -> v.markerObjective ?: v.markerDetail ?: v.markerOutcome ?: v.markerToModel ?: v.markerType
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
            // 修复依据（AGENTS.md「Workspace Identity」）：远程链路必须显式带 identity，
            // 不能靠 protocol 的 boundWorkspaceIdentity 兜底——换 workspace 而实例
            // 未重建时兜底值是旧的，命令会落到另一个工作区。
            val ack = p.sendCommand(workspacePath, envelope, workspaceIdentity)
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
            val ack = p.sendCommand(workspacePath, envelope, workspaceIdentity)
            ack.status == "accepted"
        }.getOrElse {
            ZLog.w(TAG, "stop failed: ${it.message}")
            false
        }
    }
}
