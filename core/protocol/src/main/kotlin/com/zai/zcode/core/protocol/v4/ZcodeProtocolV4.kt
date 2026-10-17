package com.zai.zcode.core.protocol.v4

import com.zai.zcode.core.common.ZLog
import com.zai.zcode.core.protocol.rpc.ChannelClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val TAG = "ZcodeProtocolV4"

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

    init {
        // ChannelClient EVENT_FIRE → wire 帧。payload 是单个 wire 帧的 JsonObject。
        // 修复依据（P0-4）：sessions-index 帧走 onDynamicSessionsIndexFrame 独立事件，
        // 原实现只 collect conversation 事件导致 sessions-index 永远收不到帧。
        scope.launch {
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
        scope.launch {
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

    /** 订阅会话 topic。返回 ack；initial snapshot 随后经 [onConversationFrame] 送达。 */
    suspend fun subscribeConversation(
        workspacePath: String,
        sessionId: String,
        workspaceIdentity: String? = null,
        baseLogEpoch: String? = null,
        baseSeq: Long? = null,
        visibility: String? = "foreground",
    ): SubscribeAck {
        val result = channel.request(
            V4.CHANNEL_ZCODE_AGENT,
            V4.METHOD_CONVERSATION_SUBSCRIBE,
            buildConversationSubscribeParams(
                workspacePath, sessionId, workspaceIdentity, baseLogEpoch, baseSeq, visibility,
            ),
        )
        return SubscribeAck(raw = resultAsObject(result))
    }

    /** 订阅 sessions-index topic（workspace 级，无 sessionId 维度）。 */
    suspend fun subscribeSessionsIndex(
        workspacePath: String,
        workspaceIdentity: String? = null,
        visibility: String? = "foreground",
    ): SubscribeAck {
        val result = channel.request(
            V4.CHANNEL_ZCODE_AGENT,
            V4.METHOD_SESSIONS_INDEX_SUBSCRIBE,
            buildSessionsIndexSubscribeParams(workspacePath, workspaceIdentity, visibility),
        )
        return SubscribeAck(raw = resultAsObject(result))
    }

    /** 退订（conversation 与 sessions-index 共用 unsubscribeConversationV4 形状）。 */
    suspend fun unsubscribe(subscriptionId: String, workspacePath: String, workspaceIdentity: String? = null) {
        channel.request(
            V4.CHANNEL_ZCODE_AGENT,
            V4.METHOD_CONVERSATION_UNSUBSCRIBE,
            buildJsonObject {
                put("workspacePath", workspacePath)
                if (!workspaceIdentity.isNullOrBlank()) put("workspaceIdentity", workspaceIdentity)
                put("subscriptionId", subscriptionId)
            }
        )
    }

    /**
     * 发送命令（envelope 由 [buildCommandEnvelope] 构造，clientId 必须等于握手绑定的
     * [clientId]，否则 fault.command.clientMismatch）。返回 commandAck。
     */
    suspend fun sendCommand(
        workspacePath: String,
        envelope: JsonObject,
        workspaceIdentity: String? = null,
    ): CommandAck {
        val params = buildJsonObject {
            put("workspacePath", workspacePath)
            if (!workspaceIdentity.isNullOrBlank()) put("workspaceIdentity", workspaceIdentity)
            put("envelope", envelope)
        }
        // 说明：buildJsonObject 内的 put(String, String) 来自 kotlinx.serialization.json
        // 的 JsonObjectBuilder.put 扩展，漏 import 时会误报 "String 但期望 JsonElement"。
        val result = channel.request(V4.CHANNEL_ZCODE_AGENT, V4.METHOD_COMMAND, params)
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
        // 修复依据（P3）：close 须清空回调，避免重建连接时旧回调"忘记接线"或重复触发。
        onConversationFrame = null
        onSessionsIndexFrame = null
        onAssemblyFault = null
        _phase.value = Phase.Closed
    }

    private fun resultAsObject(result: Any?): JsonObject {
        // 修复依据（P1-6）：非对象结果原来静默变成空 JsonObject，SubscribeAck 全 null 无日志。
        return result as? JsonObject ?: run {
            ZLog.w(TAG, "rpc result is not an object: ${result?.javaClass?.simpleName}")
            JsonObject(emptyMap())
        }
    }
}
