package com.zai.zcode.core.protocol.v4

import com.zai.zcode.core.protocol.rpc.asIntOrNull
import com.zai.zcode.core.protocol.rpc.asLongOrNull
import com.zai.zcode.core.protocol.rpc.asObjectOrNull
import com.zai.zcode.core.protocol.rpc.asStringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * V4 协议常量与轻量模型（对齐 packages/shared/src/zcode-protocol-v4/ 目录）。
 *
 * 建模决策：rows / deltas / snapshots 字段极多且持续演进（TS 端 zod 也大量 optional +
 * passthrough），Kotlin 侧一律以 JsonObject 宽容承载 + 类型化访问器读取核心字段。
 * 只有 wire 外壳（TopicWireFrame，字段封闭且被重组算法逐字段消费）用强类型。
 */
object V4 {
    const val WIRE_PROTOCOL_VERSION = 3
    const val CHANNEL_ZCODE_AGENT = "zcode-agent"

    /**
     * 任务索引通道（channels.ts:110 `ZCodeTask: "zcode-task"`）。
     *
     * 修复依据（真机 v0.2.19「看不到工作区下已有的会话」）：zcode-agent 的 sessions-index
     * 是**运行时**视图（`runtimePolicy:"existing-only"`，桌面 CLI 没起来就没有数据），
     * 而 zcode-task 的 listTasks 读的是落盘索引（taskIndexRepo.listTaskMetas → SQLite），
     * 不依赖 CLI 进程。参考实现 task_home_page.dart:317 与官方
     * taskListMembershipSets.ts:217 都以 listTasks 为列表左表，sessions-index 只做富化。
     */
    const val CHANNEL_ZCODE_TASK = "zcode-task"

    /**
     * 模型选择通道（channels.ts:126 ModelSelection: "model-selection"）。
     * 官方手机端模型选择器走此通道 getView() 拉可用 provider/model 列表
     * （providerFacadeServices.ts:97），切换用 sendConversationCommandV4 的
     * switchModelConfig 命令（command.ts:251 {provider, model, thought}）。
     */
    const val CHANNEL_MODEL_SELECTION = "model-selection"
    const val METHOD_MODEL_SELECTION_VIEW = "getView"

    /** 本地使用统计（channels.ts:134 UsageStats: "usage-stats"）。 */
    const val CHANNEL_USAGE_STATS = "usage-stats"
    const val METHOD_APP_USAGE_SNAPSHOT = "getAppUsageSnapshot"

    /** OAuth 账号会话（channels.ts:122 OAuth: "oauth"）。 */
    const val CHANNEL_OAUTH = "oauth"
    const val METHOD_OAUTH_CACHED_SESSION = "restoreCachedSession"
    const val METHOD_LIST_TASKS = "listTasks"
    const val METHOD_LIST_PINNED_TASKS = "listPinnedTasks"
    const val METHOD_LIST_ARCHIVED_TASKS = "listArchivedTasks"

    /** 任务卡长按菜单（官方 zcodeTaskServiceAdapter: archiveTask/deleteTask/setTaskPinned）。 */
    const val METHOD_ARCHIVE_TASK = "archiveTask"
    const val METHOD_DELETE_TASK = "deleteTask"
    const val METHOD_SET_TASK_PINNED = "setTaskPinned"

    // PROTOCOL_V4_LIMITS（客户端关心的子集）
    const val MAX_FRAME_BYTES = 1024 * 1024
    const val ASSEMBLY_MAX_BYTES = 16 * 1024 * 1024
    const val ASSEMBLY_MAX_FRAGMENTS = 1024
    const val ROWS_RANGE_MAX_LIMIT = 200

    // V4 服务面方法名（对齐 zcodeAgent.ts 的 IZCodeAgentService；经 toService Proxy
    // 机制，方法名直接作为 zcode-agent 通道上的 call/listen name，不是 v4 开头的路径）。
    const val METHOD_HELLO = "helloConversationV4"
    const val METHOD_INITIALIZE = "initializeConversationV4"
    const val METHOD_CONVERSATION_SUBSCRIBE = "subscribeConversationV4"
    const val METHOD_CONVERSATION_RESYNC = "resyncConversationV4"
    const val METHOD_CONVERSATION_UNSUBSCRIBE = "unsubscribeConversationV4"
    const val METHOD_CONVERSATION_ROWS_RANGE = "conversationRowsRangeV4"
    const val METHOD_COMMAND = "sendConversationCommandV4"
    const val METHOD_COMMANDS_QUERY = "queryConversationCommandsV4"
    const val METHOD_SESSIONS_INDEX_SUBSCRIBE = "subscribeSessionsIndexV4"
    const val METHOD_SESSIONS_INDEX_UNSUBSCRIBE = "unsubscribeSessionsIndexV4"
    /**
     * 修复依据（真机 v0.2.15「列表偶发缺失 / 越用越少」）：zemote
     * _SubscriptionBase._resyncMethod = 'resyncSessionsIndexV4'
     * （conversation.dart:1293），服务端 zcodeAgentService.ts:6417 确有该 RPC。
     * Android 原先没有退订/重同步入口，检测到 seq 空洞后无从恢复。
     */
    const val METHOD_SESSIONS_INDEX_RESYNC = "resyncSessionsIndexV4"

    /**
     * 服务端「runtime 未运行」错误码（zcodeAgent.ts:210 / zcodeAgentService.ts:939）。
     * existing-only 订阅在没有活跃 CLI 时必抛；桌面 UI 侧按此 code 进入 dormant 等待，
     * 不把它当普通失败（sessionsIndexStore.ts:63）。
     */
    const val RUNTIME_UNAVAILABLE_CODE = "ZCODE_AGENT_RUNTIME_UNAVAILABLE"

    // V4 下行事件（on* 方法 → channel.listen(propKey, workspaceTarget)）。
    const val EVENT_CONVERSATION_FRAME = "onDynamicConversationFrame"
    // 修复依据（P0-4）：sessions-index 有独立的动态事件，漏挂会收不到帧。
    const val EVENT_SESSIONS_INDEX_FRAME = "onDynamicSessionsIndexFrame"

    /** COMMANDS_REQUIRING_BASE_REVISION（CAS 信封必带 baseRevision）。 */
    val COMMANDS_REQUIRING_BASE_REVISION = setOf(
        "applyFileRewind", "forkAssistant", "editUserQuery", "retryTurn",
        "setAssistantFeedback", "sendQueuedNow", "editQueueItem", "reorderQueueItem",
        "deleteQueueItem", "setAutoDrain", "switchModelConfig", "switchCollaborationMode",
        "setFollowupMode", "pauseGoal", "resumeGoal",
    )

    /** ROW_TARGETING_COMMANDS（还需 baseLogEpoch）。 */
    val ROW_TARGETING_COMMANDS = setOf(
        "applyFileRewind", "forkAssistant", "editUserQuery", "retryTurn",
        "setAssistantFeedback", "setHighspeedMetrics",
    )

    // ── topic 工具 ──

    fun conversationTopic(sessionId: String): String = "conversation/$sessionId"
    fun sessionsIndexTopic(workspaceId: String): String = "sessions-index/$workspaceId"

    fun parseSessionsIndexTopic(topic: String): String? =
        if (topic.startsWith("sessions-index/") && topic.length > "sessions-index/".length) {
            topic.removePrefix("sessions-index/")
        } else null

    fun parseConversationTopic(topic: String): String? =
        if (topic.startsWith("conversation/") && topic.length > "conversation/".length) {
            topic.removePrefix("conversation/")
        } else null
}

/** wire checksum：算法恒为 crc32，值为 8 位小写 hex。 */
data class TopicWireChecksum(val value: String)

/**
 * V4 物理 wire 帧（对齐 wire.ts 的 TopicWireFrame 判别联合）。
 * logical payload 只在 complete 分支出现（JsonObject），fragment 分支只带分片字节。
 */
sealed class TopicWireFrame {
    abstract val wireVersion: Int
    abstract val deliveryKind: String // initial | online | recovery
    abstract val logicalFrameId: String
    abstract val logicalFrameOrdinal: Long
    abstract val topic: String
    abstract val subscriptionId: String

    data class Complete(
        override val wireVersion: Int,
        override val deliveryKind: String,
        override val logicalFrameId: String,
        override val logicalFrameOrdinal: Long,
        override val topic: String,
        override val subscriptionId: String,
        /** logical 帧（TopicFrame），保持 JsonObject 宽容承载。 */
        val frame: JsonObject,
    ) : TopicWireFrame()

    data class Fragment(
        override val wireVersion: Int,
        override val deliveryKind: String,
        override val logicalFrameId: String,
        override val logicalFrameOrdinal: Long,
        override val topic: String,
        override val subscriptionId: String,
        val fragmentIndex: Int,
        val fragmentCount: Int,
        val logicalBytes: Long,
        val checksum: TopicWireChecksum,
        val dataBase64: String,
    ) : TopicWireFrame()

    companion object {
        /** 宽容解析：形状不对返回 null（调用方记 typed fault 后丢弃）。 */
        fun fromJson(obj: JsonObject): TopicWireFrame? {
            val wireVersion = obj["wireVersion"].asIntOrNull() ?: return null
            val kind = obj["kind"].asStringOrNull() ?: return null
            val deliveryKind = obj["deliveryKind"].asStringOrNull() ?: return null
            val logicalFrameId = obj["logicalFrameId"].asStringOrNull()?.takeIf { it.isNotEmpty() } ?: return null
            val ordinal = obj["logicalFrameOrdinal"].asLongOrNull() ?: return null
            val topic = obj["topic"].asStringOrNull()?.takeIf { it.isNotEmpty() } ?: return null
            val subscriptionId = obj["subscriptionId"].asStringOrNull()?.takeIf { it.isNotEmpty() } ?: return null
            return when (kind) {
                "complete" -> {
                    val frame = obj["frame"].asObjectOrNull() ?: return null
                    Complete(wireVersion, deliveryKind, logicalFrameId, ordinal, topic, subscriptionId, frame)
                }
                "fragment" -> {
                    val fragmentIndex = obj["fragmentIndex"].asIntOrNull() ?: return null
                    val fragmentCount = obj["fragmentCount"].asIntOrNull() ?: return null
                    val logicalBytes = obj["logicalBytes"].asLongOrNull() ?: return null
                    val checksum = obj["checksum"].asObjectOrNull() ?: return null
                    val algorithm = checksum["algorithm"].asStringOrNull() ?: return null
                    val value = checksum["value"].asStringOrNull() ?: return null
                    val dataBase64 = obj["dataBase64"].asStringOrNull() ?: return null
                    if (algorithm != "crc32") return null
                    Fragment(
                        wireVersion, deliveryKind, logicalFrameId, ordinal, topic, subscriptionId,
                        fragmentIndex, fragmentCount, logicalBytes,
                        TopicWireChecksum(value), dataBase64,
                    )
                }
                else -> null
            }
        }
    }
}

/**
 * logical topic 帧（createTopicFrameSchema 五件套）。
 * payload 保持 JsonObject，由按 topic 类型分发的 reducer 施加。
 */
data class TopicFrame(
    val topic: String,
    val subscriptionId: String,
    val fromSeq: Long,
    val toSeq: Long,
    val sentAt: Long,
    /** {kind:"snapshot", snapshot:{...}} 或 {kind:"deltas", deltas:[...]} */
    val payload: JsonObject,
) {
    val isSnapshot: Boolean get() = payload["kind"] == JsonPrimitive("snapshot")

    fun snapshotObject(): JsonObject? =
        payload["snapshot"]?.asObjectOrNull()

    fun deltas(): JsonArray? =
        (payload["deltas"] as? JsonArray)

    companion object {
        fun fromJson(obj: JsonObject): TopicFrame? {
            val topic = obj["topic"].asStringOrNull() ?: return null
            val subscriptionId = obj["subscriptionId"].asStringOrNull() ?: return null
            val fromSeq = obj["fromSeq"].asLongOrNull() ?: return null
            val toSeq = obj["toSeq"].asLongOrNull() ?: return null
            val sentAt = obj["sentAt"].asLongOrNull() ?: 0L
            val payload = obj["payload"]?.asObjectOrNull() ?: return null
            val kind = payload["kind"].asStringOrNull() ?: return null
            if (kind != "snapshot" && kind != "deltas") return null
            return TopicFrame(topic, subscriptionId, fromSeq, toSeq, sentAt, payload)
        }
    }
}

// ── Hello / ClientHello（JsonObject 承载 + 构造辅助）──

class HelloMessage(val raw: JsonObject) {
    val protocolVersion: Int? get() = raw["protocolVersion"].asIntOrNull()
    val connectionId: String? get() = raw["connectionId"].asStringOrNull()
    val clientMode: String? get() = raw["clientMode"].asStringOrNull()
    val deliveryProfile: String? get() = raw["deliveryProfile"].asStringOrNull()
    val serverTime: Long? get() = raw["serverTime"].asLongOrNull()

    /** hello.capabilities.workflowRunDeltas（缺席/非 true = 不支持）。 */
    val workflowRunDeltas: Boolean
        get() = raw["capabilities"].asObjectOrNull()?.get("workflowRunDeltas")
            .asBooleanOrFalse()

    companion object {
        fun fromJson(obj: JsonObject): HelloMessage? =
            // 修复依据（P3）：`?.toString()` 产生带引号形式 `"\"hello\""`，比较虽能跑但脆，
            // 统一用 asStringOrNull（评审 2026-10-17 P3 第 1 条）。
            if (obj["kind"].asStringOrNull() == "hello") HelloMessage(obj) else null
    }
}

private fun JsonElement?.asBooleanOrFalse(): Boolean =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toBooleanStrictOrNull() == true

/**
 * clientHello 构造（clientKind="mobileApp"；workflowRunDeltas 只在 host 支持时许诺）。
 * capabilities 在服务端是 .strict() 校验：只能带 host hello 宣告过的键。
 */
fun buildClientHello(
    clientId: String,
    appVersion: String,
    hostWorkflowRunDeltas: Boolean,
): JsonObject = buildJsonObject {
    put("kind", "clientHello")
    put("protocolVersion", V4.WIRE_PROTOCOL_VERSION)
    put("clientId", clientId)
    put("clientKind", "mobileApp")
    put("appVersion", appVersion)
    if (hostWorkflowRunDeltas) {
        // 单向许诺：只有 hello 里见到 true 才能带，否则老 Host strict 解析整条失败。
        put("capabilities", buildJsonObject { put("workflowRunDeltas", true) })
    }
}

/** workspace 目标（ZCodeAgentWorkspaceTarget；服务端按 workspaceIdentity.trim()||workspacePath 归一）。 */
fun buildWorkspaceTarget(
    workspacePath: String,
    workspaceIdentity: String? = null,
): JsonObject = buildJsonObject {
    put("workspacePath", workspacePath)
    if (!workspaceIdentity.isNullOrBlank()) put("workspaceIdentity", workspaceIdentity)
}

/**
 * workspaceKey 归一：服务端 key = workspaceIdentity?.trim() || workspacePath
 * （AGENTS.md「Workspace Identity」）。
 *
 * 修复依据（真机 v0.2.14「创建会话失败 / 会话建到别的工作区」）：createSession 的
 * payload.workspaceId 必须是这个 key，而不是裸 workspacePath。对齐 zemote
 * lib/ui/main_shell.dart:18 的 workspaceKeyOf（注释明写 "key = workspaceIdentity?.trim()
 * || workspacePath"，mirrors web client 的 HC()）；桌面 useDraftSessionPrewarm.ts:76
 * 同样填 workspaceKey。远程链路 identity 与 path 不同值，填 path 会让新会话落到
 * 另一个工作区（sessions-index topic 也跟着分叉，新会话不出现在当前列表）。
 */
fun workspaceKeyOf(workspacePath: String, workspaceIdentity: String? = null): String {
    val identity = workspaceIdentity?.trim().orEmpty()
    if (identity.isNotEmpty()) return identity
    return workspacePath.trim()
}

/**
 * subscribeConversationV4 参数（ZCodeAgentConversationSubscribeParams = SessionTarget + base/visibility）。
 * 不携带 connectionId/clientMode/topic——那些由 host 从 workspacePath+sessionId 注入 wire identity。
 */
fun buildConversationSubscribeParams(
    workspacePath: String,
    sessionId: String,
    workspaceIdentity: String? = null,
    baseLogEpoch: String? = null,
    baseSeq: Long? = null,
    visibility: String? = "foreground",
): JsonObject = buildJsonObject {
    put("workspacePath", workspacePath)
    if (!workspaceIdentity.isNullOrBlank()) put("workspaceIdentity", workspaceIdentity)
    put("sessionId", sessionId)
    if (baseLogEpoch != null && baseSeq != null) {
        put("base", buildJsonObject {
            put("logEpoch", baseLogEpoch)
            put("seq", baseSeq)
        })
    }
    if (visibility != null) put("visibility", visibility)
}

/**
 * subscribeSessionsIndexV4 参数（ZCodeAgentSessionsIndexSubscribeParams，无 sessionId 维度）。
 * 修复依据(真机 v0.2.13"会话列表零内容"): zemote 对同一 zcode host 的实测做法——订阅
 * 参数带 runtimePolicy:"existing-only"(复用已运行的 CLI runtime,不为观察者拉起新进程,
 * 缺 runtime 只进入 dormant 而不是 start-if-needed 缺省拉起)。Android 与 zemote 同为
 * 手机远控观察者,应带同一策略。
 */
fun buildSessionsIndexSubscribeParams(
    workspacePath: String,
    workspaceIdentity: String? = null,
    visibility: String? = "foreground",
): JsonObject = buildJsonObject {
    put("workspacePath", workspacePath)
    if (!workspaceIdentity.isNullOrBlank()) put("workspaceIdentity", workspaceIdentity)
    if (visibility != null) put("visibility", visibility)
    put("runtimePolicy", "existing-only")
}

/**
 * resync 参数（conversationResyncParamsSchema + scope）。
 *
 * 修复依据（真机 v0.2.15「列表偶发缺失」）：zemote conversation.dart:943-968 在 seq
 * 空洞时用 `resync*V4({...scope, subscriptionId, ...args, base:{logEpoch,seq}})`
 * 请求服务端重放；base 只在已有 logEpoch 时发（schema 里 base 是 nullable）。
 * conversation 侧 zemote 额外带 forceSnapshot（:1015），sessions-index 侧不带（:1301）。
 */
fun buildResyncParams(
    subscriptionId: String,
    workspacePath: String,
    workspaceIdentity: String? = null,
    baseLogEpoch: String? = null,
    baseSeq: Long? = null,
    forceSnapshot: Boolean? = null,
    runtimePolicy: String? = null,
): JsonObject = buildJsonObject {
    put("workspacePath", workspacePath)
    if (!workspaceIdentity.isNullOrBlank()) put("workspaceIdentity", workspaceIdentity)
    put("subscriptionId", subscriptionId)
    if (runtimePolicy != null) put("runtimePolicy", runtimePolicy)
    if (forceSnapshot != null) put("forceSnapshot", forceSnapshot)
    if (baseLogEpoch != null && baseSeq != null) {
        put("base", buildJsonObject {
            put("logEpoch", baseLogEpoch)
            put("seq", baseSeq)
        })
    }
}

/** subscribe ACK（{ack:{subscriptionId, mode, logEpoch}}）。 */
class SubscribeAck(val raw: JsonObject) {
    val subscriptionId: String? get() = raw["ack"].asObjectOrNull()?.get("subscriptionId").asStringOrNull()
    val mode: String? get() = raw["ack"].asObjectOrNull()?.get("mode").asStringOrNull()
    val logEpoch: String? get() = raw["ack"].asObjectOrNull()?.get("logEpoch").asStringOrNull()
}

// ── 命令信封构造 ──

/** commandEnvelope 构造（对齐 commandEnvelopeSchema；CAS 命令必须带 baseRevision）。 */
fun buildCommandEnvelope(
    commandId: String,
    clientId: String,
    sessionId: String?,
    type: String,
    payload: JsonObject,
    baseRevision: Long? = null,
    baseLogEpoch: String? = null,
    // 修复依据（P1-7）：TS command.ts:352 `issuedAt: z.number()` 是必填键（值可为 0 但键必须在）；
    // 原实现把 0 当"不填"会因缺键被 zod 拒绝。缺省取当前时间。
    issuedAt: Long = System.currentTimeMillis(),
): JsonObject = buildJsonObject {
    put("commandId", commandId)
    put("clientId", clientId)
    if (sessionId != null) put("sessionId", sessionId) else put("sessionId", JsonNull)
    if (baseRevision != null) put("baseRevision", baseRevision)
    if (baseLogEpoch != null) put("baseLogEpoch", baseLogEpoch)
    put("type", type)
    put("payload", payload)
    put("issuedAt", issuedAt)
}

fun buildSendTextPayload(
    text: String,
    inputOrigin: String = "mobile",
    requestedDelivery: String? = null,
): JsonObject = buildJsonObject {
    put("text", text)
    put("inputOrigin", inputOrigin)
    if (requestedDelivery != null) put("requestedDelivery", requestedDelivery)
}

fun buildCreateSessionPayload(
    workspaceId: String,
    firstInputText: String,
): JsonObject = buildJsonObject {
    put("workspaceId", workspaceId)
    put("firstInput", buildJsonObject {
        put("text", firstInputText)
    })
}

// ── commandAck ──

class CommandAck(val raw: JsonObject) {
    val commandId: String? get() = raw["commandId"].asStringOrNull()
    val status: String? get() = raw["status"].asStringOrNull()
    val reasonCode: String? get() = raw["reasonCode"].asStringOrNull()
    val message: String? get() = raw["message"].asStringOrNull()
    val revisionAtDecision: Long? get() = raw["revisionAtDecision"].asLongOrNull()
    val result: JsonObject? get() = raw["result"].asObjectOrNull()
}

// ── rows / deltas 的类型化访问器（JsonObject 宽容承载）──

/** ConversationRow 的轻量视图（kind 判别 + 常用字段）。 */
class RowView(val raw: JsonObject) {
    val kind: String? get() = raw["kind"].asStringOrNull()
    val rowId: Long? get() = raw["rowId"].asLongOrNull()
    val turnId: String? get() = raw["turnId"].asStringOrNull()
    val createdAt: Long? get() = raw["createdAt"].asLongOrNull()
    val createdAtSeq: Long? get() = raw["createdAtSeq"].asLongOrNull()

    // assistantText / reasoning / userInput
    val text: String? get() = raw["text"].asStringOrNull()
    val state: String? get() = raw["state"].asStringOrNull()
    val model: String? get() = raw["model"].asStringOrNull()

    // toolCall
    val toolName: String? get() = raw["toolName"].asStringOrNull()
    val toolCallId: String? get() = raw["toolCallId"].asStringOrNull()
    val inputText: String? get() = raw["inputText"].asStringOrNull()
    /** toolCall 用：inputStreaming / pendingApproval / running / success / error / cancelled。 */
    val status: String? get() = raw["status"].asStringOrNull()

    // subagent
    val childSessionId: String? get() = raw["childSessionId"].asStringOrNull()
    val subagentType: String? get() = raw["subagentType"].asStringOrNull()
    val summaryText: String? get() = raw["summaryText"].asStringOrNull()

    // userInput
    val inputOrigin: String? get() = raw["inputOrigin"].asStringOrNull()

    // turnHeader：每轮文件改动摘要（rows.ts fileChanges）。只有 fileChanges 存在才有意义。
    val fileChangeAdditions: Int? get() = raw["fileChanges"].asObjectOrNull()?.get("additions").asIntOrNull()
    val fileChangeDeletions: Int? get() = raw["fileChanges"].asObjectOrNull()?.get("deletions").asIntOrNull()
    val fileChangeFiles: Int? get() = raw["fileChanges"].asObjectOrNull()?.get("files").asIntOrNull()

    // artifact
    val displayName: String? get() = raw["displayName"].asStringOrNull()
    val artifactType: String? get() = raw["artifactType"].asStringOrNull()
    val sizeBytes: Long? get() = raw["sizeBytes"].asLongOrNull()

    // hookInvocation
    val hookEventName: String? get() = raw["hookEventName"].asStringOrNull()
    val hookCount: Int? get() = raw["hookCount"].asIntOrNull()

    /**
     * timelineMarker 的显示骨架：marker 是一组 discriminated union，`type` 之后各类型字段不同。
     * UI 只需 type + 少量文案字段；拿不到就按 type 渲染标题，不让 marker 决定行的生死。
     */
    val markerType: String? get() = raw["marker"].asObjectOrNull()?.get("type").asStringOrNull()
    val markerObjective: String? get() = raw["marker"].asObjectOrNull()?.get("objective").asStringOrNull()
    val markerOutcome: String? get() = raw["marker"].asObjectOrNull()?.get("outcome").asStringOrNull()
    val markerToModel: String? get() = raw["marker"].asObjectOrNull()?.get("toModel").asStringOrNull()
    val markerDetail: String? get() = raw["marker"].asObjectOrNull()?.get("detail").asStringOrNull()
    val markerIteration: Int? get() = raw["marker"].asObjectOrNull()?.get("iteration").asIntOrNull()
    /** marker 泳道：assistantWork / turnTailBoundary / lightBoundary（落位由服务端裁决）。 */
    val lane: String? get() = raw["lane"].asStringOrNull()

    /** turnHeader 的权威工时（排除等待），用于「已工作 12s」折叠卡。 */
    val activeMs: Long? get() = raw["activeMs"].asLongOrNull()
}

/**
 * ConversationDelta 的判别访问。op 集合封闭（10-protocol-spec §4.5）；
 * 未知 op 返回 Unknown（丢弃，不崩）。
 */
sealed class ConversationDeltaView {
    class RowAppended(val row: JsonObject) : ConversationDeltaView()
    class RowUpserted(val row: JsonObject) : ConversationDeltaView()
    class RowRemoved(val fromRowId: Long) : ConversationDeltaView()
    class RowDelta(val rowId: Long, val path: String, val append: String) : ConversationDeltaView()
    class StateUpdated(val patch: JsonObject) : ConversationDeltaView()
    class PluginUi(val op: String, val raw: JsonObject) : ConversationDeltaView()
    class WorkflowRunUpdated(val raw: JsonObject) : ConversationDeltaView()
    class WorkflowRunRemoved(val raw: JsonObject) : ConversationDeltaView()
    object Unknown : ConversationDeltaView()

    companion object {
        fun from(delta: JsonObject): ConversationDeltaView = when (val op = delta["op"].asStringOrNull()) {
            "row.appended" -> delta["row"].asObjectOrNull()?.let { RowAppended(it) } ?: Unknown
            "row.upserted" -> delta["row"].asObjectOrNull()?.let { RowUpserted(it) } ?: Unknown
            "row.removed" -> delta["fromRowId"].asLongOrNull()?.let { RowRemoved(it) } ?: Unknown
            "row.delta" -> {
                val rowId = delta["rowId"].asLongOrNull()
                val path = delta["path"].asStringOrNull()
                val append = delta["append"].asStringOrNull()
                if (rowId != null && path != null && append != null) {
                    RowDelta(rowId, path, append)
                } else Unknown
            }
            "state.updated" -> delta["patch"].asObjectOrNull()?.let { StateUpdated(it) } ?: Unknown
            "pluginUi.resourceUpdated", "pluginUi.resourceListChanged",
            "pluginUi.instanceClosed", "pluginUi.appToolCall",
            -> PluginUi(op, delta)
            "workflowRun.updated" -> WorkflowRunUpdated(delta)
            "workflowRun.removed" -> WorkflowRunRemoved(delta)
            else -> Unknown
        }
    }
}

// ── sessions-index 视图 ──

class SessionSummaryView(val raw: JsonObject) {
    val sessionId: String? get() = raw["sessionId"].asStringOrNull()
    val workspaceId: String? get() = raw["workspaceId"].asStringOrNull()
    val title: String? get() = raw["title"].asStringOrNull()
    val phase: String? get() = raw["phase"].asStringOrNull()
    val sessionEnded: Boolean get() = raw["sessionEnded"] == JsonPrimitive(true)
    val lastActivityAt: Long? get() = raw["lastActivityAt"].asLongOrNull()
    val lastAssistantPreview: String? get() = raw["lastAssistantPreview"].asStringOrNull()
    val pendingInteractionSummary: JsonObject? get() = raw["pendingInteractionSummary"].asObjectOrNull()
    // Phase B（docs/ui-design.md）：wire optional 字段投影，旧 CLI 缺席 → 降级值
    val hasBackgroundWork: Boolean get() = raw["hasBackgroundWork"] == JsonPrimitive(true)
    val createdAt: Long? get() = raw["createdAt"].asLongOrNull()
}

/**
 * zcode-task 的 ZCodeTaskMeta 视图（validation.ts:1627 zcodeTaskMetaSchema 的子集）。
 *
 * 修复依据（真机 v0.2.19）：这是**落盘索引**行——会话关掉、CLI 退出、电脑重启之后
 * 它还在。sessions-index 的 SessionSummaryView 相反，是 CLI runtime 的内存视图。
 * 两者 taskId/sessionId 是同一个值（参考实现 mergeWorkspaceSessionTasks 直接按
 * taskId 对齐，task_home_page.dart:73 把 sessionId 写成 taskId）。
 *
 * 只投影列表用得到的字段；schema 演进加字段不影响这里（JsonObject 宽容承载）。
 */
class TaskMetaView(val raw: JsonObject) {
    val taskId: String? get() = raw["taskId"].asStringOrNull()
    val title: String? get() = raw["title"].asStringOrNull()
    /** 落盘状态：running | completed | error（zcodeTaskPersistStatusSchema）。 */
    val status: String? get() = raw["status"].asStringOrNull()
    val createdAt: Long? get() = raw["createdAt"].asLongOrNull()
    val updatedAt: Long? get() = raw["updatedAt"].asLongOrNull()
    val model: String? get() = raw["model"].asStringOrNull()
    val unreadAt: Long? get() = raw["unreadAt"].asLongOrNull()
    val workspacePath: String? get() = raw["workspacePath"].asStringOrNull()
    val workspaceIdentity: String? get() = raw["workspaceIdentity"].asStringOrNull()
}

/** sessions-index delta（op 集合封闭：session.upserted / session.removed）。 */
sealed class SessionsIndexDeltaView {
    class SessionUpserted(val session: JsonObject) : SessionsIndexDeltaView()
    class SessionRemoved(val sessionId: String) : SessionsIndexDeltaView()
    object Unknown : SessionsIndexDeltaView()

    companion object {
        fun from(delta: JsonObject): SessionsIndexDeltaView = when (val op = delta["op"].asStringOrNull()) {
            "session.upserted" ->
                delta["session"].asObjectOrNull()?.let { SessionUpserted(it) } ?: Unknown
            "session.removed" ->
                delta["sessionId"].asStringOrNull()?.let { SessionRemoved(it) } ?: Unknown
            else -> Unknown
        }
    }
}

// ── JSON 辅助 ──

/** 解析 JSON 文本为 JsonObject；失败返回 null。 */
fun JsonObject.Companion.parseOrNull(text: String): JsonObject? = try {
    com.zai.zcode.core.common.AppJson.parseToJsonElement(text).jsonObject
} catch (_: Exception) {
    null
}

/** 元素转 JsonObject（顶层非对象返回 null）。 */
fun JsonElement.toJsonObjectOrNull(): JsonObject? = (this as? JsonObject)
