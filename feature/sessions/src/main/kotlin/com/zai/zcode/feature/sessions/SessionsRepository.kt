package com.zai.zcode.feature.sessions

import com.zai.zcode.core.common.ZLog
import com.zai.zcode.core.network.ZcodeConnection
import com.zai.zcode.core.protocol.rpc.asIntOrNull
import com.zai.zcode.core.protocol.v4.SessionSummaryView
import com.zai.zcode.core.protocol.v4.SessionsIndexReducer
import com.zai.zcode.core.protocol.v4.TopicFrame
import com.zai.zcode.core.protocol.v4.ZcodeProtocolV4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "SessionsRepository"

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

    private val reducer = SessionsIndexReducer()
    // 本地 pinned/seen 注入后的合并视图（wire 列表 + 本地状态）
    private val _sessions = MutableStateFlow<List<SessionItem>>(emptyList())
    val sessions: StateFlow<List<SessionItem>> = _sessions.asStateFlow()

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
    fun setWorkspace(path: String) {
        if (_workspacePath.value == path) return
        _workspacePath.value = path
        reducer.reset()
        subscribedProtocol = null // 触发重订阅
        subscribedWorkspace = null
        connection.protocol.value?.let { subscribe(it, path) }
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
        // 保留 _sessions 显示旧数据 + 顶栏显示重连状态
    }

    private fun subscribe(p: ZcodeProtocolV4, ws: String) {
        // 新协议实例(bridge reopen 重建)必须重订阅,即使 workspace 未变
        if (subscribedProtocol === p && subscribedWorkspace == ws) return
        subscribedProtocol = p
        subscribedWorkspace = ws
        scope.launch {
            try {
                p.subscribeSessionsIndex(ws)
                ZLog.i(TAG, "subscribed sessions-index for $ws")
            } catch (e: Exception) {
                ZLog.w(TAG, "sessions-index subscribe failed: ${e.message}")
                subscribedProtocol = null
                subscribedWorkspace = null
            }
        }
    }

    private fun applyFrame(frame: TopicFrame) {
        if (reducer.apply(frame)) publish()
    }

    private fun publish() {
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
