package com.zai.zcode.core.protocol.v4

import com.zai.zcode.core.common.ZLog
import com.zai.zcode.core.protocol.rpc.ChannelClient
import com.zai.zcode.core.protocol.rpc.asLongOrNull
import com.zai.zcode.core.protocol.rpc.asStringOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val TAG = "ZcodeProtocolV4"

/**
 * 订阅应答超时：桌面可能要先预热 session runtime（zemote conversation.dart:821）。
 * 修复依据（真机 21:21「读取会话失败」）：CLI 冷恢复大历史会话（seq 3000+）时
 * 60s 不够——连续两次订阅超时，官方对 createSession 给 90s，订阅是同量级的
 * 冷路径，对齐 120s。
 */
private const val SUBSCRIBE_TIMEOUT_MS = 120_000L

/**
 * 任务索引读取超时：读的是 SQLite，不需要等 CLI 预热，12s 足够
 * （参考实现 task_home_page.dart:317 三个分区各给 12s）。
 */
private const val TASK_LIST_TIMEOUT_MS = 12_000L

/**
 * ZCode Protocol V4 会话门面（对齐 packages/ui/src/v4/agentV4ConnectionHandshake.ts +
 * agentConversationTransport.ts 的语义）。纯 JVM，无 Android 依赖；socket 写出由
 * [ChannelClient.onSend] 注入。
 *
 * 交互时序（本类内已验证的 TS 蓝本）：
 * 1. socket 建立 → 服务端发 Initialize → ChannelClient 进入 IDLE。
 * 2. 握手两步，均在 `zcode-agent` 通道上（方法名即服务面方法名）：
 *    a. `helloConversationV4()`（无参）→ hello 响应（capabilities、connectionId…）。
 *    b. `initializeConversationV4(clientHello)` → 服务端绑定 clientId；此后
 *       commandEnvelope.clientId 必须与之一致，否则 fault.command.clientMismatch。
 * 3. 订阅：`subscribeConversationV4({workspacePath, sessionId, visibility?})` /
 *    `subscribeSessionsIndexV4({workspacePath})` → ACK{subscriptionId}。
 * 4. 帧：`listen("onDynamicConversationFrame", workspaceTarget)` 的 EventFire payload
 *    是单个 wire 帧 JsonObject → TopicWireFrame.fromJson → [V4FrameAssembler] → TopicFrame。
 */
class ZcodeProtocolV4(
    private val scope: CoroutineScope,
    private val channel: ChannelClient,
    private val clientId: String,
    private val appVersion: String,
) {
    sealed class Phase {
        /** 尚未握手。 */
        object AwaitingHello : Phase()
        /** 握手完成（clientId 已绑定），可订阅/发命令。 */
        data class Ready(val hello: HelloMessage) : Phase()
        object Closed : Phase()
    }

    private val _phase = MutableStateFlow<Phase>(Phase.AwaitingHello)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** 已重组的会话 topic 帧回调（UI 层接入 ConversationReducer）。 */
    var onConversationFrame: ((TopicFrame, String) -> Unit)? = null
    /** 已重组的 sessions-index topic 帧回调。 */
    var onSessionsIndexFrame: ((TopicFrame, String) -> Unit)? = null
    /** wire 装配故障回调（reasonCode 诊断用；帧已 fail-fast 丢弃）。 */
    var onAssemblyFault: ((reasonCode: String) -> Unit)? = null

    private val assembler = V4FrameAssembler(scope, ::dispatchLogicalFrame).apply {
        onFaultCallback = { reasonCode -> onAssemblyFault?.invoke(reasonCode) }
    }

    private var frameListenerId: Int? = null
    private var sessionsListenerId: Int? = null
    private var frameListenWorkspaceTarget: JsonObject? = null

    /**
     * attachFrameListener 绑定的 workspaceIdentity。
     * 修复依据(真机 v0.2.13"订阅 ACK 成功但零帧"):事件监听(attachFrameListener)带了
     * workspaceIdentity,而 subscribe 与 sendCommand 由上层 Repository 调用时没带,
     * 两端 workspaceKey 推导不一致(identity.trim()||path vs 纯 path)→ 订阅登记在
     * A key、帧监听挂在 B key,routedEvent 的 routedEventKey 对不上,帧永不 fan-out。
     * AGENTS.md 铁律:远程链路必须贯穿传递 workspaceIdentity。此处让门面记住 attach
     * 时绑定的 identity,所有后续 subscribe/命令在未显式指定时自动带上。
     */
    @Volatile
    private var boundWorkspaceIdentity: String? = null

    /**
     * 协议自有作用域。init 的两条常驻 collect 协程挂在它上面，[close] 时整体取消，
     * 不被外部共享 [scope] 的生命周期拖住（relay bridge 频繁重开时旧 protocol 的协程
     * 会泄漏，评审 Finding #2）。
     */
    private val job = SupervisorJob()
    private val ownScope = CoroutineScope(scope.coroutineContext + job)

    init {
        // ChannelClient EVENT_FIRE → wire 帧。payload 是单个 wire 帧的 JsonObject。
        // 修复依据（P0-4）：sessions-index 帧走 onDynamicSessionsIndexFrame 独立事件，
        // 原实现只 collect conversation 事件导致 sessions-index 永远收不到帧。
        // 修复依据（评审 Finding #2）：挂在 ownScope 而非外部 scope，close() 时随 job 取消。
        ownScope.launch {
            channel.events.collect { event ->
                // 修复依据(真机 v0.2.12"零帧"):桌面 EventFire header 不含事件名
                // (channelServer.ts 只发 [204, id]),event 为 null 是常态。此处按
                // channelName 过滤即可;帧 kind 由 dispatchLogicalFrame 按 topic 分发,
                // 两条监听(dispose 后重挂)都汇入同一入口。
                if (event.channelName == V4.CHANNEL_ZCODE_AGENT) {
                    handleWireNotification(event.payload)
                }
            }
        }
        // 连接断开（IDLE 之外进入 CLOSED）时清装配状态与监听记账。
        // 修复依据（评审 Finding #2）：挂在 ownScope，close() 时随 job 取消。
        ownScope.launch {
            channel.state.collect { state ->
                if (state == ChannelClient.State.CLOSED) {
                    assembler.reset()
                    frameListenerId = null
                    frameListenWorkspaceTarget = null
                }
            }
        }
    }

    /**
     * 握手两步法：helloConversationV4 → initializeConversationV4。
     * capabilities 只镜像 host hello 宣告的 workflowRunDeltas（strict 校验约束）。
     * 返回 hello；超时/失败抛异常。须在 channel 进入 IDLE 后调用。
     */
    suspend fun handshake(timeoutMs: Long = 15_000): HelloMessage {
        _phase.value = Phase.AwaitingHello
        val helloRaw = channel.request(V4.CHANNEL_ZCODE_AGENT, V4.METHOD_HELLO, null, timeoutMs)
        val helloObj = helloRaw as? JsonObject
            ?: throw IllegalStateException("helloConversationV4 returned non-object")
        val hello = HelloMessage.fromJson(helloObj)
            ?: throw IllegalStateException("invalid hello payload (kind != hello)")
        if (hello.protocolVersion != V4.WIRE_PROTOCOL_VERSION) {
            ZLog.w(TAG, "host protocol version ${hello.protocolVersion}, expected ${V4.WIRE_PROTOCOL_VERSION}")
        }

        val clientHello = buildClientHello(clientId, appVersion, hello.workflowRunDeltas)
        // initialize 的返回体是解析后的 clientHello（zcodeAgentService.ts），不消费。
        channel.request(V4.CHANNEL_ZCODE_AGENT, V4.METHOD_INITIALIZE, clientHello, timeoutMs)

        _phase.value = Phase.Ready(hello)
        ZLog.i(TAG, "v4 handshake complete, clientId bound: $clientId")
        return hello
    }

    /**
     * 挂上 onDynamicConversationFrame 事件监听（EventListen 带 workspaceTarget 参数）。
     * 订阅前必须调用一次；workspace 变化时重复调用以更新 fan-out 参数。
     */
    fun attachFrameListener(workspacePath: String, workspaceIdentity: String? = null) {
        val target = buildWorkspaceTarget(workspacePath, workspaceIdentity)
        boundWorkspaceIdentity = workspaceIdentity?.takeIf { it.isNotBlank() }
        frameListenerId?.let { channel.disposeEvent(it) }
        sessionsListenerId?.let { channel.disposeEvent(it) }
        // 修复依据（P0-4）：conversation 与 sessions-index 各挂一条动态事件监听。
        frameListenerId = channel.listenEvent(V4.CHANNEL_ZCODE_AGENT, V4.EVENT_CONVERSATION_FRAME, target)
        sessionsListenerId = channel.listenEvent(V4.CHANNEL_ZCODE_AGENT, V4.EVENT_SESSIONS_INDEX_FRAME, target)
        frameListenWorkspaceTarget = target
    }

    /** 断开帧监听（断线清理用；EventDispose）。 */
    fun detachFrameListener() {
        frameListenerId?.let { channel.disposeEvent(it) }
        sessionsListenerId?.let { channel.disposeEvent(it) }
        frameListenerId = null
        sessionsListenerId = null
        frameListenWorkspaceTarget = null
    }

    /**
     * 订阅会话 topic。返回 ack；initial snapshot 随后经 [onConversationFrame] 送达。
     *
     * @param timeoutMs 默认 60s：与 [subscribeSessionsIndex] 同为订阅路径，桌面同样可能
     * 先预热 runtime 才应答，15s 通道默认值会把慢应答切成取消。
     */
    suspend fun subscribeConversation(
        workspacePath: String,
        sessionId: String,
        workspaceIdentity: String? = null,
        baseLogEpoch: String? = null,
        baseSeq: Long? = null,
        visibility: String? = "foreground",
        timeoutMs: Long = SUBSCRIBE_TIMEOUT_MS,
    ): SubscribeAck {
        val result = channel.request(
            V4.CHANNEL_ZCODE_AGENT,
            V4.METHOD_CONVERSATION_SUBSCRIBE,
            buildConversationSubscribeParams(
                workspacePath,
                sessionId,
                workspaceIdentity ?: boundWorkspaceIdentity,
                baseLogEpoch,
                baseSeq,
                visibility,
            ),
            timeoutMs,
        )
        return SubscribeAck(raw = resultAsObject(result))
    }

    /**
     * 订阅 sessions-index topic（workspace 级，无 sessionId 维度）。
     *
     * @param timeoutMs 默认 60s（而非 ChannelClient 的 15s）。
     * 修复依据（真机 v0.2.14「点击工作区后一直转圈」）：zemote
     * conversation.dart:818-821 对 subscribe 单独给 60s，注释写明「桌面可能要先
     * 预热 session runtime 才应答，不能按 30s 通道默认值超时」。预热耗时超过 15s
     * 时请求被取消，表现为订阅失败、列表永远空。
     */
    suspend fun subscribeSessionsIndex(
        workspacePath: String,
        workspaceIdentity: String? = null,
        visibility: String? = "foreground",
        timeoutMs: Long = SUBSCRIBE_TIMEOUT_MS,
    ): SubscribeAck {
        val result = channel.request(
            V4.CHANNEL_ZCODE_AGENT,
            V4.METHOD_SESSIONS_INDEX_SUBSCRIBE,
            buildSessionsIndexSubscribeParams(
                workspacePath,
                workspaceIdentity ?: boundWorkspaceIdentity,
                visibility,
            ),
            timeoutMs,
        )
        return SubscribeAck(raw = resultAsObject(result))
    }

    /**
     * 读落盘任务索引（`zcode-task.listTasks`，非 pinned 非 archived 分区）。
     *
     * 修复依据（真机 v0.2.19「看不到工作区下已有的会话」）：sessions-index 是 CLI
     * runtime 的内存视图（`runtimePolicy:"existing-only"`），桌面没为该 workspace
     * 跑 CLI 时它就是空的——而用户说的"之前的会话"全都躺在落盘索引里。本方法走
     * `zcode-task` 通道读 taskIndexRepo.listTaskMetas（SQLite），**不依赖 CLI 进程**。
     * 参考实现 task_home_page.dart:317 与官方 taskListMembershipSets.ts:217 都以它
     * 为列表左表。
     *
     * 与 [listPinnedTasks] / [listArchivedTasks] 三个分区并行调用：任一分区失败由调用
     * 方按"该分区不可用"处理（官方 listWithAvailability 语义），不能当作权威空集。
     */
    suspend fun listTasks(
        workspacePath: String,
        workspaceIdentity: String? = null,
        timeoutMs: Long = TASK_LIST_TIMEOUT_MS,
    ): List<TaskMetaView> =
        requestTaskList(V4.METHOD_LIST_TASKS, workspacePath, workspaceIdentity, timeoutMs)

    /** 置顶分区（`listPinnedTasks`）。 */
    suspend fun listPinnedTasks(
        workspacePath: String,
        workspaceIdentity: String? = null,
        timeoutMs: Long = TASK_LIST_TIMEOUT_MS,
    ): List<TaskMetaView> =
        requestTaskList(V4.METHOD_LIST_PINNED_TASKS, workspacePath, workspaceIdentity, timeoutMs)

    /** 归档分区（`listArchivedTasks`）；结果用于把已归档 id 从列表剔除。 */
    suspend fun listArchivedTasks(
        workspacePath: String,
        workspaceIdentity: String? = null,
        timeoutMs: Long = TASK_LIST_TIMEOUT_MS,
    ): List<TaskMetaView> =
        requestTaskList(V4.METHOD_LIST_ARCHIVED_TASKS, workspacePath, workspaceIdentity, timeoutMs)

    private suspend fun requestTaskList(
        method: String,
        workspacePath: String,
        workspaceIdentity: String?,
        timeoutMs: Long,
    ): List<TaskMetaView> {
        val identity = workspaceIdentity ?: boundWorkspaceIdentity
        // 修复依据（参考实现 task_home_page.dart:173 _scope）：参数是 {workspacePath,
        // workspaceIdentity?} 单对象；wire 上仍包成数组（ChannelClient.send 的
        // proxy-channel 约定），与 zcode-agent 侧调用同形。
        val scope = buildJsonObject {
            put("workspacePath", workspacePath)
            if (!identity.isNullOrBlank()) put("workspaceIdentity", identity)
        }
        val result = channel.request(
            V4.CHANNEL_ZCODE_TASK,
            method,
            buildJsonArray { add(scope) },
            timeoutMs,
        )
        // 结果形状是 ZCodeTaskMeta[]；非数组（老 Host 返回对象/空）按"不可用"处理，
        // 由调用方保留旧视图——不静默吞成空列表，否则会把已有会话说成没有。
        return (result as? JsonArray)?.mapNotNull { el ->
            (el as? JsonObject)?.let { TaskMetaView(it) }?.takeIf { it.taskId != null }
        } ?: throw IllegalStateException("task list result is not an array: ${result?.javaClass?.simpleName}")
    }

    /**
     * 退订（conversation 与 sessions-index 共用 unsubscribeConversationV4 形状）。
     *
     * 修复依据（真机 v0.2.15）：zemote _SubscriptionBase 给每条订阅配独立的
     * unsubscribe/resync 方法名（conversation.dart:1290-1304），服务端
     * zcodeAgentService.ts:6412/6417 确有 sessions-index 专用的一对。用 conversation
     * 的退订去关 sessions-index 订阅，route 前缀（conversation/ vs sessions-index/）
     * 不匹配，服务端会退订到错误的路由上。
     */
    suspend fun unsubscribe(
        subscriptionId: String,
        workspacePath: String,
        workspaceIdentity: String? = null,
        sessionsIndex: Boolean = false,
    ) {
        channel.request(
            V4.CHANNEL_ZCODE_AGENT,
            if (sessionsIndex) V4.METHOD_SESSIONS_INDEX_UNSUBSCRIBE
            else V4.METHOD_CONVERSATION_UNSUBSCRIBE,
            buildJsonObject {
                put("workspacePath", workspacePath)
                if (!(workspaceIdentity ?: boundWorkspaceIdentity).isNullOrBlank()) {
                    put("workspaceIdentity", workspaceIdentity ?: boundWorkspaceIdentity)
                }
                put("subscriptionId", subscriptionId)
                if (sessionsIndex) put("runtimePolicy", "existing-only")
            },
        )
    }

    /**
     * sessions-index 重同步（resyncSessionsIndexV4）：seq 空洞后请求服务端重放。
     *
     * 修复依据（真机 v0.2.15「列表偶发缺失」）：空洞检测只是发现，没有重放入口就
     * 等于发现后什么都不做。对齐 zemote conversation.dart:1293（方法名）与
     * :1301（args 只带 runtimePolicy，不带 forceSnapshot）。
     */
    suspend fun resyncSessionsIndex(
        subscriptionId: String,
        workspacePath: String,
        workspaceIdentity: String? = null,
        baseLogEpoch: String? = null,
        baseSeq: Long? = null,
        timeoutMs: Long = SUBSCRIBE_TIMEOUT_MS,
    ): SubscribeAck {
        val result = channel.request(
            V4.CHANNEL_ZCODE_AGENT,
            V4.METHOD_SESSIONS_INDEX_RESYNC,
            buildResyncParams(
                subscriptionId = subscriptionId,
                workspacePath = workspacePath,
                workspaceIdentity = workspaceIdentity ?: boundWorkspaceIdentity,
                baseLogEpoch = baseLogEpoch,
                baseSeq = baseSeq,
                runtimePolicy = "existing-only",
            ),
            timeoutMs,
        )
        return SubscribeAck(raw = resultAsObject(result))
    }

    /**
     * conversation 重同步（resyncConversationV4）：seq 空洞 / watchdog 超时后重放。
     * zemote :1015 额外带 forceSnapshot=true（conversation 再同步按整表重语义更稳）。
     */
    suspend fun resyncConversation(
        subscriptionId: String,
        workspacePath: String,
        workspaceIdentity: String? = null,
        baseLogEpoch: String? = null,
        baseSeq: Long? = null,
        timeoutMs: Long = SUBSCRIBE_TIMEOUT_MS,
    ): SubscribeAck {
        val result = channel.request(
            V4.CHANNEL_ZCODE_AGENT,
            V4.METHOD_CONVERSATION_RESYNC,
            buildResyncParams(
                subscriptionId = subscriptionId,
                workspacePath = workspacePath,
                workspaceIdentity = workspaceIdentity ?: boundWorkspaceIdentity,
                baseLogEpoch = baseLogEpoch,
                baseSeq = baseSeq,
                forceSnapshot = true,
            ),
            timeoutMs,
        )
        return SubscribeAck(raw = resultAsObject(result))
    }

    /**
     * 发送命令（envelope 由 [buildCommandEnvelope] 构造，clientId 必须等于握手绑定的
     * [clientId]，否则 fault.command.clientMismatch）。返回 commandAck。
     *
     * @param timeoutMs 命令应答超时。
     * 修复依据（真机 v0.2.14「创建会话失败」）：createSession 走的也是本方法，而
     * 默认 15s 撑不过 CLI runtime 首次预热（zemote conversation.dart:258 给
     * createSession 单独 90s，chat_page.dart:675 还提示"首次可能需要预热"）。
     * 超时会被 [ChannelClient.request] 转成 PromiseCancel，服务端收到 cancel 后
     * 不再落 record，表现为"创建失败"。
     */
    suspend fun sendCommand(
        workspacePath: String,
        envelope: JsonObject,
        workspaceIdentity: String? = null,
        timeoutMs: Long = 15_000,
    ): CommandAck {
        val identity = workspaceIdentity ?: boundWorkspaceIdentity
        val params = buildJsonObject {
            put("workspacePath", workspacePath)
            if (!identity.isNullOrBlank()) put("workspaceIdentity", identity)
            put("envelope", envelope)
        }
        // 说明：buildJsonObject 内的 put(String, String) 来自 kotlinx.serialization.json
        // 的 JsonObjectBuilder.put 扩展，漏 import 时会误报 "String 但期望 JsonElement"。
        val result = channel.request(V4.CHANNEL_ZCODE_AGENT, V4.METHOD_COMMAND, params, timeoutMs)
        return CommandAck(raw = resultAsObject(result))
    }

    private fun handleWireNotification(payload: Any?) {
        // 修复依据（P1-6）：原 elvis 兜底 `payload as? JsonObject` 在该分支恒为 null（死代码），
        // "包一层 key 的 payload" 被静默丢弃。payload 已是单一 wire 帧 JsonObject，
        // 非 JsonObject 直接告警返回。
        val obj = payload as? JsonObject ?: run {
            ZLog.w(TAG, "wire frame event payload is not an object: ${payload?.javaClass?.simpleName}")
            return
        }
        val wire = TopicWireFrame.fromJson(obj) ?: run {
            ZLog.w(TAG, "unparseable wire frame (service routing boundary)")
            return
        }
        // 诊断：wire 帧能否正确重组，以及它属于哪个 topic（conversation/ 还是 sessions-index/）。
        ZLog.d(TAG, "wire frame topic=${wire.topic} deliveryKind=${wire.deliveryKind}")
        assembler.onWireFrame(wire)
    }

    private fun dispatchLogicalFrame(frame: TopicFrame, deliveryKind: String) {
        when {
            frame.topic.startsWith("conversation/") ->
                onConversationFrame?.invoke(frame, deliveryKind)
            frame.topic.startsWith("sessions-index/") ->
                onSessionsIndexFrame?.invoke(frame, deliveryKind)
            else -> ZLog.d(TAG, "frame for unhandled topic ${frame.topic}")
        }
    }

    fun close() {
        detachFrameListener()
        assembler.reset()
        // 修复依据（评审 Finding #2）：取消协议自有作用域，回收 init 启动的两条常驻协程，
        // 否则 relay bridge 重开时旧 protocol 的协程随外部 scope 长留（泄漏）。
        job.cancel()
        // 修复依据（P3）：close 须清空回调，避免重建连接时旧回调"忘记接线"或重复触发。
        onConversationFrame = null
        onSessionsIndexFrame = null
        onAssemblyFault = null
        _phase.value = Phase.Closed
    }

    /**
     * 行分页拉取（conversationRowsRangeV4）：取 rowId < beforeRowId 的行（缺省从尾部向前）。
     *
     * 修复依据（真机 21:05 现场日志「点进去没有之前的聊天内容」）：冷订阅的 initial
     * 快照只有 rows 键缺失/为空的 shape（snapshot keys 不含 rows），toSeq=3066 说明
     * 服务端明确知道会话有历史——历史行不走快照，桌面端靠 rowsRange 分页拉（loadOlder）。
     * 手机端此前从未实现该 RPC，打开会话只有实时增量、没有历史。
     *
     * 返回 null = 非 JSON 对象（协议异常），调用方按失败处理；hasMore=false 表示已到头。
     */
    suspend fun conversationRowsRange(
        sessionId: String,
        workspacePath: String,
        workspaceIdentity: String? = null,
        limit: Int = ROWS_RANGE_DEFAULT_LIMIT,
        beforeRowId: Long? = null,
        timeoutMs: Long = 30_000,
    ): ConversationRowsRangeResult? {
        // 修复依据（真机 21:11「history backfill failed: The "path" argument must be
        // of type string」）：rowsRange 走 getReadOnlyClient(params)，params 是
        // ZCodeAgentWorkspaceTarget——服务端要用 workspacePath 解析 runtime 的 cwd。
        // 只发 sessionId 时 Node 的 path 模块拿到 undefined 直接抛错。与 subscribe
        // 同形：workspacePath 必带，identity 有则带（AGENTS.md 贯穿传递）。
        val params = buildJsonObject {
            put("workspacePath", workspacePath)
            if (!workspaceIdentity.isNullOrBlank()) put("workspaceIdentity", workspaceIdentity)
            put("sessionId", sessionId)
            put("limit", limit)
            if (beforeRowId != null) put("beforeRowId", beforeRowId)
        }
        val result = channel.request(
            V4.CHANNEL_ZCODE_AGENT,
            V4.METHOD_CONVERSATION_ROWS_RANGE,
            params,
            timeoutMs,
        )
        val obj = result as? JsonObject ?: return null
        return ConversationRowsRangeResult(obj)
    }

    companion object {
        /** 单页行数上限（PROTOCOL_V4_LIMITS.rowsRangeMaxLimit）。 */
        const val ROWS_RANGE_DEFAULT_LIMIT = 200
    }

    /** rowsRange 结果投影（v4ConversationRowsRangeResultSchema 子集）。 */
    class ConversationRowsRangeResult(raw: JsonObject) {
        val rows: List<JsonObject> =
            (raw["rows"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val atSeq: Long = raw["atSeq"].asLongOrNull() ?: 0L
        val atLogEpoch: String? = raw["atLogEpoch"].asStringOrNull()
        val hasMore: Boolean = raw["hasMore"] == kotlinx.serialization.json.JsonPrimitive(true)
    }

    private fun resultAsObject(result: Any?): JsonObject {
        // 修复依据（P1-6）：非对象结果原来静默变成空 JsonObject，SubscribeAck 全 null 无日志。
        return result as? JsonObject ?: run {
            ZLog.w(TAG, "rpc result is not an object: ${result?.javaClass?.simpleName}")
            JsonObject(emptyMap())
        }
    }
}
