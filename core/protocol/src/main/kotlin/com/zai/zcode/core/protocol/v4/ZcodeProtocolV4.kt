package com.zai.zcode.core.protocol.v4

import com.zai.zcode.core.common.ZLog
import com.zai.zcode.core.protocol.rpc.ChannelClient
import com.zai.zcode.core.protocol.rpc.asArrayOrNull
import com.zai.zcode.core.protocol.rpc.asLongOrNull
import com.zai.zcode.core.protocol.rpc.asObjectOrNull
import com.zai.zcode.core.protocol.rpc.asStringOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
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
                when (event.channelName) {
                    V4.CHANNEL_ZCODE_AGENT -> handleWireNotification(event.payload)
                    // 终端事件（onDynamicData/onDynamicExit，EventListen arg=terminal id）
                    V4.CHANNEL_TERMINAL -> dispatchTerminalEvent(event.payload)
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
     * 订阅 workspace-config topic（workspace-config/<workspaceKey>）——`/` 面板
     * 「命令」分组的官方数据源（SlashCommandPlugin commands = store.slashCommands
     * ← workspaceConfigState.slashCommands）。conflated 推送流：订阅即得快照
     * （ack.snapshot.config），之后 config.updated 整体替换。零 CLI 冷启动依赖
     * ——修复依据（用户报 `/` 面板「电脑端无响应」）：此前把命令目录全压在
     * getSkillReferenceCatalog 上，该 RPC 经 getReadOnlyClient 会拉起 CLI runtime
     * （10s+），超时即无响应；官方的命令目录根本不走它。
     */
    suspend fun subscribeWorkspaceConfig(
        workspacePath: String,
        workspaceIdentity: String? = null,
        visibility: String? = "foreground",
        timeoutMs: Long = SUBSCRIBE_TIMEOUT_MS,
    ): WorkspaceConfigSubscribeAck? {
        val identity = workspaceIdentity ?: boundWorkspaceIdentity
        val result = channel.request(
            V4.CHANNEL_ZCODE_AGENT,
            V4.METHOD_WORKSPACE_CONFIG_SUBSCRIBE,
            buildJsonObject {
                put("workspacePath", workspacePath)
                if (!identity.isNullOrBlank()) put("workspaceIdentity", identity)
                if (visibility != null) put("visibility", visibility)
            },
            timeoutMs,
        )
        val obj = resultAsObject(result) ?: return null
        // ack: {ack:{subscriptionId, ...}, snapshot?:{config:{configOptions, slashCommands}}}
        val config = obj["snapshot"]?.asObjectOrNull()?.get("config")?.asObjectOrNull()
        val commands = (config?.get("slashCommands") as? JsonArray)?.mapNotNull { el ->
            (el as? JsonObject)?.let { c ->
                WorkspaceSlashCommand(
                    name = c["name"].asStringOrNull() ?: return@mapNotNull null,
                    description = c["description"].asStringOrNull() ?: "",
                    inputHint = c["inputHint"].asStringOrNull(),
                )
            }
        } ?: emptyList()
        return WorkspaceConfigSubscribeAck(
            subscriptionId = obj["ack"]?.asObjectOrNull()?.get("subscriptionId").asStringOrNull(),
            commands = commands,
        )
    }

    class WorkspaceConfigSubscribeAck(
        val subscriptionId: String?,
        val commands: List<WorkspaceSlashCommand>,
    )

    class WorkspaceSlashCommand(
        val name: String,
        val description: String,
        val inputHint: String?,
    )

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

    /**
     * 归档 / 删除 / 置顶一个任务（zcode-task 通道，官方 zcodeTaskServiceAdapter 同名方法）。
     * 参数形状与 listTasks 同族：{taskId, workspacePath, workspaceIdentity?}。
     * 操作成功后列表变更由桌面端 task_meta_changed 广播驱动 sessions-index 更新，
     * 落盘索引由手机端下次 listTasks 重拉反映。
     */
    suspend fun archiveTask(
        taskId: String,
        workspacePath: String,
        workspaceIdentity: String? = null,
        timeoutMs: Long = 15_000,
    ): Boolean = taskMutation(V4.METHOD_ARCHIVE_TASK, taskId, workspacePath, workspaceIdentity, timeoutMs)

    suspend fun deleteTask(
        taskId: String,
        workspacePath: String,
        workspaceIdentity: String? = null,
        timeoutMs: Long = 15_000,
    ): Boolean = taskMutation(V4.METHOD_DELETE_TASK, taskId, workspacePath, workspaceIdentity, timeoutMs)

    suspend fun setTaskPinned(
        taskId: String,
        workspacePath: String,
        workspaceIdentity: String? = null,
        pinned: Boolean = true,
        timeoutMs: Long = 15_000,
    ): Boolean {
        val scope = buildJsonObject {
            put("taskId", taskId)
            put("workspacePath", workspacePath)
            if (!workspaceIdentity.isNullOrBlank()) put("workspaceIdentity", workspaceIdentity)
            put("pinned", pinned)
        }
        val result = channel.request(
            V4.CHANNEL_ZCODE_TASK,
            V4.METHOD_SET_TASK_PINNED,
            buildJsonArray { add(scope) },
            timeoutMs,
        )
        return result != null
    }

    private suspend fun taskMutation(
        method: String,
        taskId: String,
        workspacePath: String,
        workspaceIdentity: String?,
        timeoutMs: Long,
    ): Boolean {
        val scope = buildJsonObject {
            put("taskId", taskId)
            put("workspacePath", workspacePath)
            if (!workspaceIdentity.isNullOrBlank()) put("workspaceIdentity", workspaceIdentity)
        }
        val result = channel.request(
            V4.CHANNEL_ZCODE_TASK,
            method,
            buildJsonArray { add(scope) },
            timeoutMs,
        )
        return result != null
    }

    // ── 终端（官方 ITerminalService：PTY 双向流，移动端只读查看 + 命令写入）──

    /** 终端输出回调（id → data）。由 dispatchTerminalEvent 按 id 分发。 */
    var onTerminalData: ((id: String, data: String) -> Unit)? = null
    /** 终端退出回调。 */
    var onTerminalExit: ((id: String, exitCode: Int) -> Unit)? = null

    private var terminalDataListenerId: Int? = null
    private var terminalExitListenerId: Int? = null

    /** 挂终端输出/退出事件监听（EventListen arg = terminal id，进入会话终端页时调用）。 */
    fun attachTerminalListeners(terminalId: String) {
        detachTerminalListeners()
        terminalDataListenerId = channel.listenEvent(
            V4.CHANNEL_TERMINAL, V4.EVENT_TERMINAL_DATA, terminalId,
        )
        terminalExitListenerId = channel.listenEvent(
            V4.CHANNEL_TERMINAL, V4.EVENT_TERMINAL_EXIT, terminalId,
        )
        boundTerminalId = terminalId
    }

    private var boundTerminalId: String? = null

    fun detachTerminalListeners() {
        terminalDataListenerId?.let { channel.disposeEvent(it) }
        terminalExitListenerId?.let { channel.disposeEvent(it) }
        terminalDataListenerId = null
        terminalExitListenerId = null
        boundTerminalId = null
    }

    /**
     * 创建终端（terminal.create {cols, rows, cwd?}）。
     * cwd 传 workspacePath——终端在电脑端工作区里起 shell。
     */
    suspend fun terminalCreate(
        cols: Int = 80,
        rows: Int = 24,
        cwd: String? = null,
        timeoutMs: Long = 20_000,
    ): String? {
        val params = buildJsonObject {
            put("cols", cols)
            put("rows", rows)
            if (!cwd.isNullOrBlank()) put("cwd", cwd)
        }
        val result = channel.request(V4.CHANNEL_TERMINAL, V4.METHOD_TERMINAL_CREATE, params, timeoutMs)
        val obj = result as? JsonObject ?: return null
        return obj["id"].asStringOrNull()
    }

    /** 向终端写入（命令 + 
 回车）。 */
    suspend fun terminalWrite(id: String, data: String) {
        val params = buildJsonObject {
            put("id", id)
            put("data", data)
        }
        channel.request(V4.CHANNEL_TERMINAL, V4.METHOD_TERMINAL_WRITE, params, timeoutMs = 10_000)
    }

    /** 调整终端尺寸（移动端软键盘/分屏时）。 */
    suspend fun terminalResize(id: String, cols: Int, rows: Int) {
        val params = buildJsonObject {
            put("id", id)
            put("cols", cols)
            put("rows", rows)
        }
        channel.request(V4.CHANNEL_TERMINAL, V4.METHOD_TERMINAL_RESIZE, params, timeoutMs = 10_000)
    }

    /** 释放终端（离开终端页时）。 */
    suspend fun terminalDispose(id: String) {
        detachTerminalListeners()
        runCatching {
            channel.request(
                V4.CHANNEL_TERMINAL,
                V4.METHOD_TERMINAL_DISPOSE,
                buildJsonObject { put("id", id) },
                timeoutMs = 10_000,
            )
        }
    }

    /**
     * 终端事件分发：EVENT_FIRE body 是 {id, data}/{id, exitCode}——由
     * handleWireNotification 之前的通用事件路径按事件 id 路由。此方法由
     * ChannelClient 事件流（terminal 监听 id 命中）调用。
     */
    fun dispatchTerminalEvent(payload: Any?) {
        // 官方 proxy-channel：动态事件 fire 的是**原始值**（terminal.dataEmitter.fire
        // 的是纯字符串 PTY 输出），不包 {id, ...} 信封——id 语义由 EventListen 的 arg
        // （即 boundTerminalId）承载。JsonObject 形状保留兼容（未来可能加信封）。
        when (payload) {
            is String -> {
                if (boundTerminalId != null) onTerminalData?.invoke(boundTerminalId!!, payload)
            }
            is JsonObject -> {
                val id = payload["id"].asStringOrNull() ?: return
                payload["data"].asStringOrNull()?.let { data ->
                    if (id == boundTerminalId) onTerminalData?.invoke(id, data)
                }
                payload["exitCode"].asLongOrNull()?.let { code ->
                    if (id == boundTerminalId) onTerminalExit?.invoke(id, code.toInt())
                }
            }
        }
    }

    // ── Git 分支选择（官方 useGitBranchSwitcher / gitCliRepo）──

    /**
     * 列本地分支（git 通道 getLocalBranches，参数 {workspacePath}——gitCliRepo
     * 按工作区解析 repo root，git for-each-ref refs/heads）。
     * 非 git 仓库时 branches 为空、isRepository=false。
     */
    suspend fun gitLocalBranches(
        workspacePath: String,
        workspaceIdentity: String? = null,
        timeoutMs: Long = 15_000,
    ): GitBranchList? {
        val params = buildJsonObject {
            put("workspacePath", workspacePath)
            if (!workspaceIdentity.isNullOrBlank()) put("workspaceIdentity", workspaceIdentity)
        }
        val result = channel.request(V4.CHANNEL_GIT, V4.METHOD_GIT_LOCAL_BRANCHES, params, timeoutMs)
        val obj = result as? JsonObject ?: return null
        val branches = (obj["branches"] as? JsonArray)?.mapNotNull { el ->
            (el as? JsonObject)?.let { b ->
                GitBranch(
                    name = b["name"].asStringOrNull() ?: return@mapNotNull null,
                    isCurrent = b["isCurrent"] == kotlinx.serialization.json.JsonPrimitive(true),
                )
            }
        } ?: emptyList()
        return GitBranchList(
            currentBranch = obj["currentBranchName"].asStringOrNull(),
            isRepository = branches.isNotEmpty() || obj["currentBranchName"] != null,
            branches = branches,
        )
    }

    /** 切换分支（git 通道 switchBranch {workspacePath, targetBranchName}）。 */
    suspend fun gitSwitchBranch(
        workspacePath: String,
        targetBranchName: String,
        workspaceIdentity: String? = null,
        timeoutMs: Long = 30_000,
    ): Boolean {
        val params = buildJsonObject {
            put("workspacePath", workspacePath)
            if (!workspaceIdentity.isNullOrBlank()) put("workspaceIdentity", workspaceIdentity)
            put("targetBranchName", targetBranchName)
        }
        return runCatching {
            channel.request(V4.CHANNEL_GIT, V4.METHOD_GIT_SWITCH_BRANCH, params, timeoutMs)
            true
        }.getOrDefault(false)
    }

    /**
     * 创建并检出新分支（git 通道 createBranchAndSwitch {workspacePath, branchName}）
     * ——官方分支选择器「创建并检出新分支...」同款（GitBranchSwitcher.tsx:340，
     * GitCreateBranchRequest git.ts:143-146）。返回成功与否；失败细节看 issues（忽略，
     * UI 层提示常见失败即可）。
     */
    suspend fun gitCreateBranchAndSwitch(
        workspacePath: String,
        branchName: String,
        workspaceIdentity: String? = null,
        timeoutMs: Long = 30_000,
    ): Boolean {
        val params = buildJsonObject {
            put("workspacePath", workspacePath)
            if (!workspaceIdentity.isNullOrBlank()) put("workspaceIdentity", workspaceIdentity)
            put("branchName", branchName)
        }
        return runCatching {
            channel.request(V4.CHANNEL_GIT, V4.METHOD_GIT_CREATE_BRANCH, params, timeoutMs)
            true
        }.getOrDefault(false)
    }

    /**
     * Git 提交图谱（git 通道 getCommitGraph {workspacePath, maxCount, skip}）——
     * 官方 GitGraphDialog 同一条 RPC（git.ts:100-124）。服务端只返回扁平 commit
     * 列表，图形布局由客户端计算；移动端用简化单列时间线（不画分叉连线）。
     */
    suspend fun gitCommitGraph(
        workspacePath: String,
        maxCount: Int = 50,
        skip: Int = 0,
        workspaceIdentity: String? = null,
        timeoutMs: Long = 20_000,
    ): GitCommitGraphResult? {
        val params = buildJsonObject {
            put("workspacePath", workspacePath)
            if (!workspaceIdentity.isNullOrBlank()) put("workspaceIdentity", workspaceIdentity)
            put("maxCount", maxCount)
            put("skip", skip)
        }
        val obj = runCatching {
            channel.request(V4.CHANNEL_GIT, V4.METHOD_GIT_COMMIT_GRAPH, params, timeoutMs) as? JsonObject
        }.getOrNull() ?: return null
        val commits = (obj["commits"] as? JsonArray)?.mapNotNull { el ->
            (el as? JsonObject)?.let { c ->
                GitGraphCommit(
                    hash = c["hash"].asStringOrNull() ?: return@mapNotNull null,
                    subject = c["subject"].asStringOrNull() ?: "",
                    authorName = c["authorName"].asStringOrNull() ?: "",
                    refs = (c["refs"] as? JsonArray)?.mapNotNull { r ->
                        (r as? JsonObject)?.let { rr ->
                            (rr["name"].asStringOrNull() ?: return@mapNotNull null) to
                                (rr["kind"].asStringOrNull() ?: "head")
                        }
                    } ?: emptyList(),
                )
            }
        } ?: emptyList()
        return GitCommitGraphResult(
            commits = commits,
            hasMore = obj["hasMore"] == kotlinx.serialization.json.JsonPrimitive(true),
        )
    }

    class GitGraphCommit(
        val hash: String,
        val subject: String,
        val authorName: String,
        /** (ref 名, kind: branch/remote/tag/head)。 */
        val refs: List<Pair<String, String>>,
    )

    class GitCommitGraphResult(val commits: List<GitGraphCommit>, val hasMore: Boolean)

    /**
     * git refresh（git 通道 refresh {workspacePath, includeBranchComparison}）——
     * 官方审查面板（GitPane/useGitRepository）同一条数据链路，一次拿三组文件列表：
     * unstagedChanges / stagedChanges / branchComparison（共享 GitFileChange 结构，
     * git.ts:58-71 / GitRefreshResult git.ts:250-256）。上一轮更改（last-turn）官方
     * 不走 RPC（本地快照合成），移动端先不做该 scope。
     */
    suspend fun gitRefresh(
        workspacePath: String,
        workspaceIdentity: String? = null,
        includeBranchComparison: Boolean = true,
        timeoutMs: Long = 20_000,
    ): GitRefreshResult? {
        val params = buildJsonObject {
            put("workspacePath", workspacePath)
            if (!workspaceIdentity.isNullOrBlank()) put("workspaceIdentity", workspaceIdentity)
            put("includeBranchComparison", includeBranchComparison)
        }
        val obj = runCatching {
            channel.request(V4.CHANNEL_GIT, V4.METHOD_GIT_REFRESH, params, timeoutMs) as? JsonObject
        }.getOrNull() ?: return null
        fun parseChanges(key: String): List<GitFileChange> =
            (obj[key] as? JsonArray)?.mapNotNull { el ->
                (el as? JsonObject)?.let { f ->
                    GitFileChange(
                        path = f["path"].asStringOrNull() ?: return@mapNotNull null,
                        kind = f["kind"].asStringOrNull() ?: "modified",
                        added = f["added"].asLongOrNull()?.toInt() ?: 0,
                        removed = f["removed"].asLongOrNull()?.toInt() ?: 0,
                        isStaged = f["isStaged"] == kotlinx.serialization.json.JsonPrimitive(true),
                        isUntracked = f["isUntracked"] == kotlinx.serialization.json.JsonPrimitive(true),
                    )
                }
            } ?: emptyList()
        val comparison = obj["branchComparison"]?.asObjectOrNull()?.let { bc ->
            GitBranchComparisonData(
                comparisonLabel = bc["comparisonLabel"].asStringOrNull(),
                changes = (bc["changes"] as? JsonArray)?.mapNotNull { el ->
                    (el as? JsonObject)?.let { f ->
                        GitFileChange(
                            path = f["path"].asStringOrNull() ?: return@mapNotNull null,
                            kind = f["kind"].asStringOrNull() ?: "modified",
                            added = f["added"].asLongOrNull()?.toInt() ?: 0,
                            removed = f["removed"].asLongOrNull()?.toInt() ?: 0,
                            isStaged = false,
                            isUntracked = false,
                        )
                    }
                } ?: emptyList(),
            )
        }
        return GitRefreshResult(
            currentBranch = obj["summary"]?.asObjectOrNull()?.get("branchName")?.asStringOrNull(),
            unstaged = parseChanges("unstagedChanges"),
            staged = parseChanges("stagedChanges"),
            branchComparison = comparison,
        )
    }

    /**
     * 单文件 diff（git 通道 getDiff {workspacePath, path, sourceId}）——审查面板
     * 展开文件时懒加载（官方 GitPane.loadDiffForChange 同款）。sourceId:
     * unstaged/staged/branch。返回 null = 非 JSON（老桌面端）。
     */
    suspend fun gitGetDiff(
        workspacePath: String,
        path: String,
        sourceId: String,
        workspaceIdentity: String? = null,
        timeoutMs: Long = 15_000,
    ): String? {
        val params = buildJsonObject {
            put("workspacePath", workspacePath)
            if (!workspaceIdentity.isNullOrBlank()) put("workspaceIdentity", workspaceIdentity)
            put("path", path)
            put("sourceId", sourceId)
        }
        val obj = runCatching {
            channel.request(V4.CHANNEL_GIT, V4.METHOD_GIT_GET_DIFF, params, timeoutMs) as? JsonObject
        }.getOrNull() ?: return null
        // binary/unavailable 无 patch；truncated 给截断后的 patch
        return obj["patch"].asStringOrNull()
    }

    /** GitRefreshResult 投影（git.ts GitRefreshResult 子集）。 */
    class GitRefreshResult(
        val currentBranch: String?,
        val unstaged: List<GitFileChange>,
        val staged: List<GitFileChange>,
        val branchComparison: GitBranchComparisonData?,
    )

    /** GitFileChange 投影（git.ts:58-71 子集）。 */
    class GitFileChange(
        val path: String,
        val kind: String,
        val added: Int,
        val removed: Int,
        val isStaged: Boolean,
        val isUntracked: Boolean,
    )

    // ── git 写操作（官方 GitActionMenu：commit/push/stage/unstage/discard/AI message）──

    private suspend fun gitMutation(
        method: String,
        params: JsonObject,
        timeoutMs: Long,
    ): Boolean = runCatching {
        channel.request(V4.CHANNEL_GIT, method, params, timeoutMs)
        true
    }.getOrElse { e ->
        ZLog.w(TAG, "git.$method failed: ${e.message}")
        false
    }

    fun gitRepoParams(
        workspacePath: String,
        workspaceIdentity: String?,
    ): JsonObject = buildJsonObject {
        put("workspacePath", workspacePath)
        if (!workspaceIdentity.isNullOrBlank()) put("workspaceIdentity", workspaceIdentity)
    }

    /** 暂存文件（GitPathMutationRequest {workspacePath, paths}）。 */
    suspend fun gitStagePaths(
        workspacePath: String,
        paths: List<String>,
        workspaceIdentity: String? = null,
    ): Boolean = gitMutation(
        V4.METHOD_GIT_STAGE,
        buildJsonObject {
            put("workspacePath", workspacePath)
            if (!workspaceIdentity.isNullOrBlank()) put("workspaceIdentity", workspaceIdentity)
            put("paths", buildJsonArray { paths.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        },
        timeoutMs = 30_000,
    )

    /** 取消暂存。 */
    suspend fun gitUnstagePaths(
        workspacePath: String,
        paths: List<String>,
        workspaceIdentity: String? = null,
    ): Boolean = gitMutation(
        V4.METHOD_GIT_UNSTAGE,
        buildJsonObject {
            put("workspacePath", workspacePath)
            if (!workspaceIdentity.isNullOrBlank()) put("workspaceIdentity", workspaceIdentity)
            put("paths", buildJsonArray { paths.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        },
        timeoutMs = 30_000,
    )

    /** 丢弃工作区更改（危险操作：GitDiscardPathsRequest {paths, staged?}）。 */
    suspend fun gitDiscardPaths(
        workspacePath: String,
        paths: List<String>,
        staged: Boolean = false,
        workspaceIdentity: String? = null,
    ): Boolean = gitMutation(
        V4.METHOD_GIT_DISCARD,
        buildJsonObject {
            put("workspacePath", workspacePath)
            if (!workspaceIdentity.isNullOrBlank()) put("workspaceIdentity", workspaceIdentity)
            put("paths", buildJsonArray { paths.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put("staged", staged)
        },
        timeoutMs = 30_000,
    )

    /** 提交（GitCommitRequest {message, paths?, stagedOnly?}）。返回 commitHash 或 null。 */
    suspend fun gitCommit(
        workspacePath: String,
        message: String,
        paths: List<String>? = null,
        stagedOnly: Boolean = false,
        workspaceIdentity: String? = null,
        timeoutMs: Long = 60_000,
    ): String? = runCatching {
        val params = buildJsonObject {
            put("workspacePath", workspacePath)
            if (!workspaceIdentity.isNullOrBlank()) put("workspaceIdentity", workspaceIdentity)
            put("message", message)
            if (paths != null) {
                put("paths", buildJsonArray { paths.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            }
            put("stagedOnly", stagedOnly)
        }
        val obj = channel.request(V4.CHANNEL_GIT, V4.METHOD_GIT_COMMIT, params, timeoutMs) as? JsonObject
        obj?.get("commitHash")?.asStringOrNull()
    }.getOrElse { e ->
        ZLog.w(TAG, "git.commit failed: ${e.message}")
        null
    }

    /** 推送（GitPushRequest {}）。 */
    suspend fun gitPush(
        workspacePath: String,
        workspaceIdentity: String? = null,
        timeoutMs: Long = 90_000,
    ): Boolean = gitMutation(V4.METHOD_GIT_PUSH, gitRepoParams(workspacePath, workspaceIdentity), timeoutMs)

    /** AI 生成 commit message（generateCommitMessage）。 */
    suspend fun gitGenerateCommitMessage(
        workspacePath: String,
        workspaceIdentity: String? = null,
        includeUnstaged: Boolean = false,
        timeoutMs: Long = 60_000,
    ): String? = runCatching {
        val params = buildJsonObject {
            put("workspacePath", workspacePath)
            if (!workspaceIdentity.isNullOrBlank()) put("workspaceIdentity", workspaceIdentity)
            put("includeUnstaged", includeUnstaged)
        }
        val obj = channel.request(V4.CHANNEL_GIT, V4.METHOD_GIT_GENERATE_COMMIT_MESSAGE, params, timeoutMs) as? JsonObject
        obj?.get("message")?.asStringOrNull()
    }.getOrElse { e ->
        ZLog.w(TAG, "git.generateCommitMessage failed: ${e.message}")
        null
    }

    class GitBranchComparisonData(
        val comparisonLabel: String?,
        val changes: List<GitFileChange>,
    )

    class GitBranchList(
        val currentBranch: String?,
        val isRepository: Boolean,
        val branches: List<GitBranch>,
    )

    class GitBranch(val name: String, val isCurrent: Boolean)

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

    // ── 附件上传（官方 attachmentPut 三段事务：begin → chunk* → commit，≤512KiB/块）──

    /** 当前连接的 connectionId（hello 响应；上传参数必带）。 */
    private fun currentConnectionId(): String? =
        (phase.value as? Phase.Ready)?.hello?.connectionId

    /**
     * 上传本地文件为会话附件，返回 AttachmentRef（官方 useComposerAttachments 的
     * wire 事务形态）。步骤：
     * 1. begin {connectionId, uploadId, sessionId, fileName, mime, totalBytes, totalChunks,
     *    checksum: "sha256:<hex>"}
     * 2. chunk {chunkIndex, dataBase64}（≤512KiB）
     * 3. commit → {ref}
     */
    suspend fun attachmentUpload(
        sessionId: String,
        fileName: String,
        mime: String,
        bytes: ByteArray,
        remoteSessionId: String? = null,
        workspaceIdentity: String? = null,
        workspacePath: String? = null,
        timeoutMs: Long = 30_000,
    ): String? {
        val connectionId = currentConnectionId() ?: run {
            ZLog.w(TAG, "attachment upload: no connectionId (not ready)")
            return null
        }
        val chunkMax = 512 * 1024
        val totalChunks = if (bytes.isEmpty()) 0 else (bytes.size + chunkMax - 1) / chunkMax
        val uploadId = "mob-${java.util.UUID.randomUUID()}"
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        val checksum = "sha256:" + digest.joinToString("") { "%02x".format(it) }
        return try {
            // remote workspace 路由字段（AGENTS.md「Workspace Identity」）：缺
            // remoteSessionId 时 host 无法命中远端 attachment 的 CLI，会尝试按
            // workspaceKey spawn 新进程并在 prepareRuntimeState 崩溃。
            val routing = buildJsonObject {
                if (!remoteSessionId.isNullOrBlank()) put("remoteSessionId", remoteSessionId)
                if (!workspaceIdentity.isNullOrBlank()) put("workspaceIdentity", workspaceIdentity)
                if (!workspacePath.isNullOrBlank()) put("workspacePath", workspacePath)
            }
            val begin = channel.request(
                V4.CHANNEL_ZCODE_AGENT,
                V4.METHOD_ATTACHMENT_BEGIN,
                buildJsonObject {
                    put("connectionId", connectionId)
                    put("uploadId", uploadId)
                    put("sessionId", sessionId)
                    put("fileName", fileName)
                    put("mime", mime)
                    put("totalBytes", bytes.size.toLong())
                    put("totalChunks", totalChunks.toLong())
                    put("checksum", checksum)
                    routing.forEach { (k, v) -> put(k, v) }
                },
                timeoutMs,
            ) as? JsonObject
            // 幂等重复上传时 begin 可能直接 committed
            begin?.get("ref")?.asStringOrNull()?.let { return it }
            var index = 0
            while (index < totalChunks) {
                val from = index * chunkMax
                val to = minOf(from + chunkMax, bytes.size)
                val data = bytes.copyOfRange(from, to)
                channel.request(
                    V4.CHANNEL_ZCODE_AGENT,
                    V4.METHOD_ATTACHMENT_CHUNK,
                    buildJsonObject {
                        put("connectionId", connectionId)
                        put("uploadId", uploadId)
                        put("sessionId", sessionId)
                        put("chunkIndex", index.toLong())
                        put(
                            "dataBase64",
                            kotlinx.serialization.json.JsonPrimitive(
                                java.util.Base64.getEncoder().encodeToString(data),
                            ),
                        )
                        // chunk/commit 与 begin 同一服务面（host 按**顶层**字段路由，
                        // 修复依据：begin OK 后 chunk 仍 path.join(undefined) 崩溃）
                        routing.forEach { (k, v) -> put(k, v) }
                    },
                    timeoutMs,
                )
                index++
            }
            val commit = channel.request(
                V4.CHANNEL_ZCODE_AGENT,
                V4.METHOD_ATTACHMENT_COMMIT,
                buildJsonObject {
                    put("connectionId", connectionId)
                    put("uploadId", uploadId)
                    put("sessionId", sessionId)
                    routing.forEach { (k, v) -> put(k, v) }
                },
                timeoutMs,
            ) as? JsonObject
            commit?.get("ref")?.asStringOrNull()
        } catch (e: Exception) {
            // 上传失败必须带原因上抛（PromiseErrorException 的 reasonCode/Message）——
            // 用户报「上传不上去」时此前被 runCatching 吞成 null，UI 只能显示笼统失败。
            ZLog.w(TAG, "attachment upload failed: ${e.message}")
            throw e
        }
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

    /** workspace-config 帧回调（`/` 面板命令目录整体替换）。 */
    var onWorkspaceConfigCommands: ((List<WorkspaceSlashCommand>) -> Unit)? = null

    private fun dispatchLogicalFrame(frame: TopicFrame, deliveryKind: String) {
        when {
            frame.topic.startsWith("conversation/") ->
                onConversationFrame?.invoke(frame, deliveryKind)
            frame.topic.startsWith("sessions-index/") ->
                onSessionsIndexFrame?.invoke(frame, deliveryKind)
            frame.topic.startsWith("workspace-config/") -> {
                // `/` 面板命令目录（conflated：payload.config.slashCommands 整体替换）
                val commands = frame.payload["config"]?.asObjectOrNull()
                    ?.get("slashCommands")?.let { arr ->
                        (arr as? JsonArray)?.mapNotNull { el ->
                            (el as? JsonObject)?.let { c ->
                                WorkspaceSlashCommand(
                                    name = c["name"].asStringOrNull() ?: return@mapNotNull null,
                                    description = c["description"].asStringOrNull() ?: "",
                                    inputHint = c["inputHint"].asStringOrNull(),
                                )
                            }
                        }
                    }
                if (commands != null) onWorkspaceConfigCommands?.invoke(commands)
            }
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
        onWorkspaceConfigCommands = null
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

    /**
     * 读模型选择视图（model-selection 通道 getView）。
     * 官方手机端模型选择器的数据源：providers[].models[] 扁平出可选项，
     * preferredSelection 是当前选中（官方 preferredSelection {providerId, modelId, thoughtLevel?}）。
     */
    suspend fun modelSelectionView(timeoutMs: Long = 15_000): ModelSelectionView? {
        val result = channel.request(V4.CHANNEL_MODEL_SELECTION, V4.METHOD_MODEL_SELECTION_VIEW, null, timeoutMs)
        val obj = result as? JsonObject ?: return null
        return ModelSelectionView(obj)
    }

    /** ModelSelectionView 投影（providerFacadeServices.ts ModelSelectionView 子集）。 */
    class ModelSelectionView(raw: JsonObject) {
        val providers: List<ProviderEntry> =
            (raw["providers"] as? JsonArray)?.mapNotNull { el ->
                (el as? JsonObject)?.let { ProviderEntry(it) }
            } ?: emptyList()
        val preferred: Selection? = (raw["preferredSelection"] as? JsonObject)?.let { Selection(it) }
    }

    class ProviderEntry(raw: JsonObject) {
        val providerId: String = raw["providerId"].asStringOrNull() ?: ""
        val providerName: String = raw["providerName"].asStringOrNull() ?: raw["providerId"].asStringOrNull() ?: ""
        val models: List<ModelEntry> =
            (raw["models"] as? JsonArray)?.mapNotNull { el ->
                (el as? JsonObject)?.let { ModelEntry(it) }
            } ?: emptyList()
    }

    class ModelEntry(raw: JsonObject) {
        val modelId: String = raw["modelId"].asStringOrNull() ?: ""
        val name: String =
            // RegistryModelConfigObject 的展示名：displayName/name 缺席时用 modelId
            raw["displayName"].asStringOrNull()
                ?: raw["name"].asStringOrNull()
                ?: raw["modelId"].asStringOrNull() ?: ""

        /**
         * 该模型支持的思考档位（config.optionSpecs.reasoningLevel.values）。
         * 修复依据（用户报「选极高跳到最高/选其他模型跳回 GLM-5.3」）：服务端
         * registry.ts:151 validateModelSelectionOptions 对不在档位集的 reasoningLevel
         * 直接拒绝（reasoning-level-not-supported）。UI 必须只显示当前模型支持的档位
         * （官方 thoughtLevel 下拉同语义），否则用户选了必被拒/收敛。
         */
        val supportedThoughtLevels: List<String> =
            raw["config"]?.asObjectOrNull()
                ?.get("optionSpecs")?.asObjectOrNull()
                ?.get("reasoningLevel")?.asObjectOrNull()
                ?.get("values").asArrayOrNull()
                ?.mapNotNull { it.asStringOrNull() }
                ?: emptyList()
    }

    class Selection(raw: JsonObject) {
        val providerId: String = raw["providerId"].asStringOrNull() ?: ""
        val modelId: String = raw["modelId"].asStringOrNull() ?: ""
    }

    /**
     * 读本地使用统计（usage-stats 通道 getAppUsageSnapshot，range: all/7d/30d）。
     * 返回 null = 非 JSON 对象（老桌面无此服务）。
     */
    suspend fun appUsageSnapshot(range: String = "7d", timeoutMs: Long = 15_000): AppUsageSnapshot? {
        val params = buildJsonObject { put("range", range) }
        val result = channel.request(
            V4.CHANNEL_USAGE_STATS,
            V4.METHOD_APP_USAGE_SNAPSHOT,
            params,
            timeoutMs,
        )
        val obj = result as? JsonObject ?: return null
        return AppUsageSnapshot(obj)
    }

    /**
     * 技能/插件引用目录（zcode-agent 通道 skills/referenceCatalog）——官方 composer
     * `/` 面板 skills 分组同一条 RPC（zcodeAgentService.ts:4292，参数
     * {workspace:{workspacePath,workspaceIdentity}, sessionId?}）。返回 null = 老
     * 桌面端无此方法。
     */
    suspend fun skillsReferenceCatalog(
        workspacePath: String,
        workspaceIdentity: String? = null,
        sessionId: String? = null,
        remoteSessionId: String? = null,
        timeoutMs: Long = 15_000,
    ): SkillsCatalog? {
        // 参数形状对齐官方 useSkills.ts:99-103：**顶层平铺** workspacePath/
        // workspaceIdentity/remoteSessionId/sessionId——host 服务面
        // getSkillReferenceCatalog 内部 buildWorkspaceRef(params) 自己打包。
        // 修复依据（host 日志 22:44 仍 FAIL）：此前把字段包进 workspace 对象、
        // 顶层只留 {workspace, sessionId}——host 的 getReadOnlyClient(params) 按
        // **顶层** params 解析 workspaceKey（undefined）→ 命不中已有 CLI → spawn
        // → prepareRuntimeState 拿不到 path 崩溃（path.join(undefined)）。
        val params = buildJsonObject {
            put("workspacePath", workspacePath)
            if (!workspaceIdentity.isNullOrBlank()) put("workspaceIdentity", workspaceIdentity)
            if (!remoteSessionId.isNullOrBlank()) put("remoteSessionId", remoteSessionId)
            if (!sessionId.isNullOrBlank()) put("sessionId", sessionId)
        }
        val result = runCatching {
            channel.request(
                V4.CHANNEL_ZCODE_AGENT,
                V4.METHOD_SKILLS_REFERENCE_CATALOG,
                params,
                timeoutMs,
            ) as? JsonObject
        }.getOrNull() ?: return null
        val skills = (result["skills"] as? JsonArray)?.mapNotNull { el ->
            (el as? JsonObject)?.let { s ->
                CatalogEntry(
                    id = s["id"].asStringOrNull() ?: return@mapNotNull null,
                    name = s["name"].asStringOrNull() ?: return@mapNotNull null,
                    description = s["description"].asStringOrNull() ?: "",
                    scope = s["scope"].asStringOrNull() ?: "workspace",
                    pluginName = s["pluginName"].asStringOrNull(),
                )
            }
        } ?: emptyList()
        return SkillsCatalog(skills)
    }

    class SkillsCatalog(val skills: List<CatalogEntry>)

    class CatalogEntry(
        val id: String,
        val name: String,
        val description: String,
        val scope: String,
        val pluginName: String?,
    )

    /**
     * 会话附件分块读（zcode-agent 通道 v4/conversation/attachmentRead）——官方
     * MarkdownImage 拉取 zcode-artifact:// 图片字节同一条 RPC（transport.ts:377，
     * 参数 {sessionId, ref, target, attachmentIndex, offset, limit}，≤512KiB/块）。
     * 返回 null = 读失败（ref 失效/老桌面端）。
     */
    suspend fun conversationAttachmentRead(
        sessionId: String,
        ref: String,
        rowId: String,
        attachmentIndex: Int,
        offset: Long = 0L,
        limitBytes: Int = 512 * 1024,
        timeoutMs: Long = 20_000,
    ): AttachmentChunk? {
        val params = buildJsonObject {
            put("sessionId", sessionId)
            put("ref", ref)
            put("target", buildJsonObject {
                put("rowId", rowId)
                put("entityId", rowId)
            })
            put("attachmentIndex", attachmentIndex)
            put("offset", offset)
            put("limit", limitBytes)
        }
        val obj = runCatching {
            channel.request(
                V4.CHANNEL_ZCODE_AGENT,
                V4.METHOD_CONVERSATION_ATTACHMENT_READ,
                params,
                timeoutMs,
            ) as? JsonObject
        }.getOrNull() ?: return null
        return AttachmentChunk(
            dataBase64 = obj["dataBase64"].asStringOrNull() ?: return null,
            mediaType = obj["mediaType"].asStringOrNull() ?: "image/png",
            totalBytes = obj["totalBytes"].asLongOrNull() ?: 0L,
            nextOffset = obj["nextOffset"].asLongOrNull(),
        )
    }

    class AttachmentChunk(
        val dataBase64: String,
        val mediaType: String,
        val totalBytes: Long,
        val nextOffset: Long?,
    )

    /** AppUsageSnapshot 投影（usage-stats.ts appUsageSnapshotSchema 子集）。 */
    class AppUsageSnapshot(raw: JsonObject) {
        private val summaryObj: JsonObject = raw["summary"]?.asObjectOrNull() ?: JsonObject(emptyMap())
        val totalTokens: Long = summaryObj["totalTokens"].asLongOrNull() ?: 0L
        val totalSessions: Long = summaryObj["totalSessions"].asLongOrNull() ?: 0L
        val totalTurns: Long = summaryObj["totalTurns"].asLongOrNull() ?: 0L
        val toolCallCount: Long = summaryObj["toolCallCount"].asLongOrNull() ?: 0L
        val activeDays: Long = summaryObj["activeDays"].asLongOrNull() ?: 0L
        val currentStreakDays: Long = summaryObj["currentStreakDays"].asLongOrNull() ?: 0L
        val longestStreakDays: Long = summaryObj["longestStreakDays"].asLongOrNull() ?: 0L
        val peakDayTokens: Long = summaryObj["peakDayTokens"].asLongOrNull() ?: 0L
        val avgTimeToFirstTokenMs: Long? = summaryObj["avgTimeToFirstTokenMs"].asLongOrNull()
        val favoriteModel: String? =
            summaryObj["favoriteModel"]?.asObjectOrNull()
                ?.get("modelId").asStringOrNull()

        /** 模型用量列表（appUsageModelUsageSchema：模型/token/占比），按 share 降序。 */
        val models: List<ModelUsage> =
            (raw["models"] as? JsonArray)?.mapNotNull { el ->
                (el as? JsonObject)?.let { m ->
                    ModelUsage(
                        modelId = m["modelId"].asStringOrNull() ?: "unknown",
                        totalTokens = m["totalTokens"].asLongOrNull() ?: 0L,
                        share = m["share"].asLongOrNull()?.toFloat()?.div(100f)
                            ?: (m["share"].asStringOrNull()?.toFloatOrNull() ?: 0f),
                    )
                }
            }?.sortedByDescending { it.share } ?: emptyList()

        /** 每日各模型 token（appUsageDailyModelUsageSchema，趋势图数据源）。 */
        val dailyModelUsage: List<DailyModelUsage> =
            (raw["dailyModelUsage"] as? JsonArray)?.mapNotNull { el ->
                (el as? JsonObject)?.let { d ->
                    DailyModelUsage(
                        date = d["date"].asStringOrNull() ?: return@mapNotNull null,
                        models = (d["models"] as? JsonArray)?.mapNotNull { m ->
                            (m as? JsonObject)?.let { mm ->
                                DailyModelItem(
                                    modelId = mm["modelId"].asStringOrNull() ?: "__unknown__",
                                    totalTokens = mm["totalTokens"].asLongOrNull() ?: 0L,
                                )
                            }
                        } ?: emptyList(),
                    )
                }
            }?.sortedBy { it.date } ?: emptyList()

        /**
         * 热力图（appUsageHeatmapSchema）：weeks[].days[]，每格 date + level(0-4) +
         * totalTokens。手机端沿用服务端 level（与官方 daily 视图同源），每周/累计
         * 模式由 UI 按 UsageHeatmap.tsx 同款公式本地聚合。
         */
        val heatmap: Heatmap? = raw["heatmap"]?.asObjectOrNull()?.let { h ->
            val weeks = (h["weeks"] as? JsonArray)?.mapNotNull { w ->
                (w as? JsonObject)?.let { weekObj ->
                    HeatmapWeek(
                        (weekObj["days"] as? JsonArray)?.mapNotNull { d ->
                            (d as? JsonObject)?.let { cell ->
                                HeatmapCell(
                                    date = cell["date"].asStringOrNull() ?: return@mapNotNull null,
                                    level = (cell["level"].asLongOrNull() ?: 0L).toInt().coerceIn(0, 4),
                                    totalTokens = cell["totalTokens"].asLongOrNull() ?: 0L,
                                )
                            }
                        } ?: emptyList(),
                    )
                }
            } ?: emptyList()
            Heatmap(weeks)
        }
    }

    class ModelUsage(val modelId: String, val totalTokens: Long, val share: Float)

    /** appUsageDailyModelUsageSchema：一天的各模型 token。 */
    class DailyModelUsage(val date: String, val models: List<DailyModelItem>)

    class DailyModelItem(val modelId: String, val totalTokens: Long)

    class Heatmap(val weeks: List<HeatmapWeek>)

    class HeatmapWeek(val days: List<HeatmapCell?>)

    class HeatmapCell(val date: String, val level: Int, val totalTokens: Long)

    /**
     * 读当前 OAuth 账号信息（oauth 通道 restoreCachedSession：本地缓存展示态，
     * 不做远端校验——手机远控只展示，登录态管理在桌面端）。
     */
    suspend fun cachedAccount(timeoutMs: Long = 10_000): AccountInfo? {
        val result = channel.request(V4.CHANNEL_OAUTH, V4.METHOD_OAUTH_CACHED_SESSION, null, timeoutMs)
        val obj = result as? JsonObject ?: return null
        // null 响应（未登录）可能被序列化成 JsonNull/空对象
        if (obj.keys.isEmpty()) return null
        return AccountInfo(obj)
    }

    /** UserInfo 投影（oauthProfileSchema）。 */
    class AccountInfo(raw: JsonObject) {
        val name: String = raw["name"].asStringOrNull()
            ?: raw["nickname"].asStringOrNull()
            ?: raw["username"].asStringOrNull()
            ?: raw["email"].asStringOrNull()
            ?: "已登录"
        val email: String? = raw["email"].asStringOrNull()
        val avatarUrl: String? = raw["avatarUrl"].asStringOrNull()
            ?: raw["avatar"].asStringOrNull()
            ?: raw["picture"].asStringOrNull()
    }

    private fun resultAsObject(result: Any?): JsonObject {
        // 修复依据（P1-6）：非对象结果原来静默变成空 JsonObject，SubscribeAck 全 null 无日志。
        return result as? JsonObject ?: run {
            ZLog.w(TAG, "rpc result is not an object: ${result?.javaClass?.simpleName}")
            JsonObject(emptyMap())
        }
    }
}
