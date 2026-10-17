package com.zai.zcode.feature.sessions

import com.zai.zcode.core.common.ZLog
import com.zai.zcode.core.network.ZcodeConnection
import com.zai.zcode.core.protocol.rpc.PromiseErrorException
import com.zai.zcode.core.protocol.rpc.asIntOrNull
import com.zai.zcode.core.protocol.v4.SessionSummaryView
import com.zai.zcode.core.protocol.v4.SessionsIndexReducer
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

private const val TAG = "SessionsRepository"

/**
 * 列表加载态：区分「空」与「没加载出来」。
 *
 * 修复依据（真机 v0.2.17「看不到工作区下已有的会话」）：sessions-index 用的是
 * `runtimePolicy:"existing-only"`——桌面没有为该 workspace 运行 CLI 时，服务端直接抛
 * `ZCODE_AGENT_RUNTIME_UNAVAILABLE`（zcodeAgentService.ts:939，注释明写「绝不为观察者
 * 拉起新进程」）。原实现 catch 后只打一行 warn 就把列表置空，UI 显示「暂无任务」，
 * 用户看到的是"我的历史会话没了"，实际是"电脑端 runtime 没起来"。两者必须分开表达。
 */
enum class SessionsListState {
    /** 首次订阅尚未返回。 */
    Loading,

    /** 订阅成功（列表可能为空，那是真的没有会话）。 */
    Ready,

    /** 电脑端该工作区的 runtime 没有运行——等它起来后会自动重订阅。 */
    RuntimeUnavailable,

    /** 其它订阅/重同步错误。 */
    Error,
}

/** 订阅重试退避（末位即稳态间隔）：runtime 冷启动常见耗时在 10s 量级。 */
private val SUBSCRIBE_RETRY_DELAYS_MS = longArrayOf(2_000L, 5_000L, 15_000L, 30_000L, 60_000L)

/**
 * 会话列表数据源：持有 [SessionsIndexReducer]，把 V4 sessions-index 帧归约成
 * StateFlow<List<SessionSummaryView>> 供 UI 消费。
 *
 * 生命周期：connection Ready 后由 [onProtocolReady] 接线（挂 reducer、重订阅）；
 * 断开时 [onProtocolClosed] 清 reducer 但保留最后列表（弱信号比白屏好）。
 */
class SessionsRepository(
    private val scope: CoroutineScope,
    private val connection: ZcodeConnection,
) {
    data class SessionItem(
        val sessionId: String,
        val title: String,
        val phase: String?,
        val ended: Boolean,
        val lastActivityAt: Long?,
        val preview: String?,
        // Phase B 扩展（docs/ui-design.md §5.2 任务卡状态）
        val createdAt: Long? = null,
        /** 待决审批数（pendingInteractionSummary.permissionCount）。 */
        val permissionCount: Int = 0,
        /** 待决用户输入数（pendingInteractionSummary.userInputCount）。 */
        val userInputCount: Int = 0,
        /** 有后台工作（后台 bash / 工作流运行）。 */
        val hasBackgroundWork: Boolean = false,
        // 本地事实（SessionLocalStateStore 注入）
        val pinned: Boolean = false,
        /** 未读：lastActivityAt > 本地 lastSeenActivityAt。 */
        val unread: Boolean = false,
    ) {
        val hasPendingInteraction: Boolean get() = permissionCount + userInputCount > 0
    }

    private val reducer = SessionsIndexReducer().apply {
        onSeqGap = { resyncSessionsIndex() }
    }
    /** resync 单飞：并发空洞帧只打一次重同步请求。 */
    private val resyncMutex = kotlinx.coroutines.sync.Mutex()
    private var sessionsSubscriptionId: String? = null
    // 本地 pinned/seen 注入后的合并视图（wire 列表 + 本地状态）
    private val _sessions = MutableStateFlow<List<SessionItem>>(emptyList())
    val sessions: StateFlow<List<SessionItem>> = _sessions.asStateFlow()

    /** 列表加载态（[SessionsListState]）：空列表与"没加载出来"是两件事。 */
    private val _listState = MutableStateFlow(SessionsListState.Loading)
    val listState: StateFlow<SessionsListState> = _listState.asStateFlow()

    /** 订阅重试退避阶梯下标；成功订阅后归零。 */
    private var retryDelayIndex = 0
    /** 退避重试协程：同一时刻只允许一个在跑（重订阅时取消旧的）。 */
    private var retryJob: Job? = null
    /** 已成功订阅过一次：骨架屏只在首次出现，重试期间保留旧列表不闪。 */
    private var hasDeliveredSnapshot = false

    /** ViewModel 接线本地状态源（手工 DI：SessionsRepository 由 VM 手工构造）。 */
    fun attachLocalState(store: SessionLocalStateStore, scope: CoroutineScope) {
        localStoreRef = store
        scope.launch {
            store.states.collect { locals ->
                localStates = locals
                republish()
            }
        }
    }

    @Volatile private var localStoreRef: SessionLocalStateStore? = null
    @Volatile private var localStates: Map<String, SessionLocalStateStore.LocalState> = emptyMap()

    /** 打开会话：记录已读水位（未读蓝点消除）。 */
    suspend fun markSeen(sessionId: String) {
        val activity = _sessions.value.firstOrNull { it.sessionId == sessionId }?.lastActivityAt ?: return
        localStoreRef?.markSeen(sessionId, activity)
    }

    /** 置顶/取消置顶。 */
    suspend fun setPinned(sessionId: String, pinned: Boolean) {
        localStoreRef?.setPinned(sessionId, pinned)
    }

    private val _workspacePath = MutableStateFlow<String?>(null)
    val workspacePath: StateFlow<String?> = _workspacePath.asStateFlow()

    /**
     * 当前 workspace 的 identity（远程链路贯穿传递，AGENTS.md「Workspace Identity」）。
     * 修复依据（真机 v0.2.14）：以前只持有 path，subscribe 依赖 ZcodeProtocolV4 门面
     * 的 boundWorkspaceIdentity 兜底。兜底在「selectWorkspace 换了 workspace 但
     * protocol 实例未变」时仍指向旧 identity，订阅登记与 fan-out 目标错位。
     */
    @Volatile private var workspaceIdentity: String? = null

    // 修复依据(真机日志 19:30 bridge degrade→reopen 后"加载会话…"永挂):openBridge
    // 会创建全新 ZcodeProtocolV4 实例,原 subscribedWorkspace 布尔式判定把新实例当成
    // 已订阅,导致重连后会话列表订阅永不重发。改为按协议实例身份+workspace 联合判定。
    private var subscribedProtocol: ZcodeProtocolV4? = null
    private var subscribedWorkspace: String? = null

    fun start() {
        scope.launch {
            connection.protocol.collect { p ->
                if (p != null) onProtocolReady(p) else onProtocolClosed()
            }
        }
    }

    /** workspacePath 确定后调用；只在变化时重订阅。 */
    fun setWorkspace(path: String, identity: String? = null) {
        if (_workspacePath.value == path && workspaceIdentity == identity) return
        _workspacePath.value = path
        workspaceIdentity = identity
        reducer.reset()
        subscribedProtocol = null // 触发重订阅
        subscribedWorkspace = null
        // 换工作区 = 换一张列表：旧列表与旧水位都不再适用，骨架屏要重新出现。
        hasDeliveredSnapshot = false
        republish()
        connection.protocol.value?.let { subscribe(it, path) }
    }

    /**
     * 用户点了「重试」：丢掉退避阶梯立刻重订阅一次。
     * 机器 succeeded 之外的所有态都能重试——runtime-unavailable 等的是桌面，
     * Error 等的是网络，两者用户侧动作都是「再试一次」。
     */
    fun retrySubscribe() {
        val p = connection.protocol.value ?: return
        val ws = _workspacePath.value ?: return
        retryJob?.cancel()
        retryJob = null
        retryDelayIndex = 0
        subscribedProtocol = null
        subscribedWorkspace = null
        subscribe(p, ws)
    }

    private fun onProtocolReady(p: ZcodeProtocolV4) {
        val ws = _workspacePath.value ?: return // 等 UI 选定 workspace 再订阅
        p.onSessionsIndexFrame = { frame, _ -> applyFrame(frame) }
        subscribe(p, ws)
    }

    private fun onProtocolClosed() {
        reducer.reset()
        subscribedProtocol = null
        subscribedWorkspace = null
        sessionsSubscriptionId = null
        retryJob?.cancel()
        retryJob = null
        retryDelayIndex = 0
        hasDeliveredSnapshot = false
        _listState.value = SessionsListState.Loading
        // 保留 _sessions 显示旧数据 + 顶栏显示重连状态
    }

    private fun subscribe(p: ZcodeProtocolV4, ws: String) {
        // 新协议实例(bridge reopen 重建)必须重订阅,即使 workspace 未变
        if (subscribedProtocol === p && subscribedWorkspace == ws) return
        subscribedProtocol = p
        subscribedWorkspace = ws
        retryJob?.cancel()
        retryJob = null
        retryDelayIndex = 0
        _listState.value = if (hasDeliveredSnapshot) SessionsListState.Ready else SessionsListState.Loading
        requestSubscribe(p, ws)
    }

    /**
     * 一次订阅尝试；失败后按退避阶梯重试（[scheduleSubscribeRetry]）。
     *
     * 修复依据（真机 v0.2.17「看不到工作区下已有的会话」）：existing-only 在桌面没有
     * 活跃 runtime 时必然失败一次，而 runtime 是用户打开电脑端/切到该工作区后才起来的。
     * 一次性订阅意味着"手机先开、电脑后开"这个最常见的顺序永远列表为空。桌面
     * windowHostSessionsIndexObserver.ts:300 就是 dormant + 等 runtime 起来重订阅，
     * 这里对齐同一语义（没有 lifecycle 事件可挂，退避重试是等价的兜底）。
     */
    private fun requestSubscribe(p: ZcodeProtocolV4, ws: String) {
        scope.launch {
            try {
                val ack = p.subscribeSessionsIndex(ws, workspaceIdentity)
                sessionsSubscriptionId = ack.subscriptionId
                retryDelayIndex = 0
                ZLog.i(TAG, "subscribed sessions-index for $ws")
                // 注意：此时还没收到 snapshot，不能置 Ready——UI 骨架屏要留到首帧到达。
                // snapshot 由 applyFrame → publish 置位。
            } catch (e: Exception) {
                if (!isRuntimeUnavailable(e)) {
                    ZLog.w(TAG, "sessions-index subscribe failed: ${e.message}")
                }
                subscribedProtocol = null
                subscribedWorkspace = null
                scheduleSubscribeRetry(ws, e)
            }
        }
    }

    /**
     * 订阅失败退避重试。runtime unavailable 是"可以等"的稳态，退避到 60s 后仍在重试；
     * 其它错误重试同样有意义（桌面短暂不可达），但 UI 按错误态呈现。
     */
    private fun scheduleSubscribeRetry(ws: String, error: Exception) {
        val unavailable = isRuntimeUnavailable(error)
        _listState.value = if (unavailable) SessionsListState.RuntimeUnavailable else SessionsListState.Error
        // 修复依据（spec §5.2 离线态 + 真机 v0.2.17）：runtime 不在时**不清列表**。
        // 会话是落盘的实体，runtime 停了它们还在；上一份快照除了 phase 之外依然准确，
        // 清空等于把"加载中"谎报成"没有任务"。UI 侧降饱和表达陈旧，这里保留数据。
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            while (true) {
                val delayMs = SUBSCRIBE_RETRY_DELAYS_MS[
                    retryDelayIndex.coerceAtMost(SUBSCRIBE_RETRY_DELAYS_MS.lastIndex)
                ]
                retryDelayIndex++
                delay(delayMs)
                val p = connection.protocol.value ?: continue
                // 退避期间 workspace 可能已切换：只重试当前 workspace。
                if (_workspacePath.value != ws) return@launch
                try {
                    val ack = p.subscribeSessionsIndex(ws, workspaceIdentity)
                    subscribedProtocol = p
                    subscribedWorkspace = ws
                    sessionsSubscriptionId = ack.subscriptionId
                    retryDelayIndex = 0
                    ZLog.i(TAG, "sessions-index subscribe recovered for $ws")
                    return@launch
                } catch (e: Exception) {
                    if (!isRuntimeUnavailable(e)) {
                        ZLog.w(TAG, "sessions-index subscribe retry failed: ${e.message}")
                    }
                    _listState.value = if (isRuntimeUnavailable(e)) {
                        SessionsListState.RuntimeUnavailable
                    } else {
                        SessionsListState.Error
                    }
                }
            }
        }
    }

    /**
     * 服务端「runtime 未运行」识别：code === ZCODE_AGENT_RUNTIME_UNAVAILABLE。
     * 桌面（sessionsIndexStore.ts:63、windowHostSessionsIndexObserver.ts:33）按 code 判，
     * 不按 message 匹配——message 是英文展示串且会变，code 是协议约定。
     * 老桌面不回 code 时回退到 message 前缀匹配。
     */
    private fun isRuntimeUnavailable(e: Exception): Boolean {
        val code = (e as? PromiseErrorException)?.code
        if (code != null) return code == V4.RUNTIME_UNAVAILABLE_CODE
        return e.message?.contains("runtime is not running", ignoreCase = true) == true
    }

    /**
     * seq 空洞 → resync 重放（[SessionsIndexReducer.onSeqGap]）。
     * 修复依据（真机 v0.2.15）：检测到空洞只是第一步，必须有重放入口，否则列表
     * 会安静地停在少条目的状态。单飞 + 节流：同一时刻只跑一次，失败后由下一次
     * 空洞帧再触发，避免网络抖动时打出风暴。
     */
    private fun resyncSessionsIndex() {
        val p = connection.protocol.value ?: return
        val subId = sessionsSubscriptionId ?: return
        val ws = _workspacePath.value ?: return
        if (!resyncMutex.tryLock()) return
        scope.launch {
            try {
                val snap = reducer.snapshot()
                p.resyncSessionsIndex(
                    subscriptionId = subId,
                    workspacePath = ws,
                    workspaceIdentity = workspaceIdentity,
                    baseLogEpoch = snap.logEpoch,
                    baseSeq = snap.seq,
                )
                ZLog.i(TAG, "sessions-index resync requested (seq=${snap.seq})")
            } catch (e: Exception) {
                ZLog.w(TAG, "sessions-index resync failed: ${e.message}")
            } finally {
                resyncMutex.unlock()
            }
        }
    }

    private fun applyFrame(frame: TopicFrame) {
        if (reducer.apply(frame)) publish()
    }

    private fun publish() {
        // 首帧到达才算列表真的可用（ACK 成功但零帧是另一类故障，不能提前撤骨架屏）。
        if (!hasDeliveredSnapshot) {
            hasDeliveredSnapshot = true
            _listState.value = SessionsListState.Ready
        }
        republish()
    }

    /** wire 快照 + 本地 pinned/seen 合并重发布（两源各自到达时都触发）。 */
    private fun republish() {
        val st = reducer.snapshot()
        _sessions.value = st.sessions.mapNotNull { raw ->
            val v = SessionSummaryView(raw)
            val id = v.sessionId ?: return@mapNotNull null
            // pendingInteractionSummary：wire 上 optional（旧 CLI 缺席 → 降级为 0）
            val summary = v.pendingInteractionSummary
            val permission = summary?.get("permissionCount").asIntOrNull() ?: 0
            val userInput = summary?.get("userInputCount").asIntOrNull() ?: 0
            val local = localStates[id]
            val activity = v.lastActivityAt
            SessionItem(
                sessionId = id,
                title = v.title?.takeIf { it.isNotBlank() } ?: "未命名会话",
                phase = v.phase,
                ended = v.sessionEnded,
                lastActivityAt = activity,
                preview = v.lastAssistantPreview,
                createdAt = v.createdAt,
                permissionCount = permission,
                userInputCount = userInput,
                hasBackgroundWork = v.hasBackgroundWork,
                pinned = local?.pinned == true,
                // 未读派生（spec §会话列表）：lastActivityAt > 本地已读水位；
                // 无已读记录视为未读（首次见到即未读）
                unread = activity != null && activity > (local?.lastSeenActivityAt ?: 0L),
            )
        }.sortedByDescending { it.lastActivityAt ?: 0L }
    }
}
