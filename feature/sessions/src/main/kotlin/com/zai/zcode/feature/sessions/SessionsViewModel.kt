package com.zai.zcode.feature.sessions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zai.zcode.core.network.ServerInfo
import com.zai.zcode.core.network.ZcodeConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 首页筛选（docs/ui-design.md §5 FilterChip 行）。 */
enum class SessionFilter(val label: String) {
    All("全部"),
    NeedsYou("需要你"),
    Running("运行中"),
}

@HiltViewModel
class SessionsViewModel @Inject constructor(
    // 修复依据（P2-1，评审 2026-10-17）：删除 ConnectionFacade 双层包装，直接注入单例连接
    private val connection: ZcodeConnection,
    private val localStateStore: SessionLocalStateStore,
) : ViewModel() {

    private val repository = SessionsRepository(viewModelScope, connection)

    /** wire 列表 + 本地 pinned/seen 合并后的全集（未筛选）。 */
    private val allSessions: StateFlow<List<SessionsRepository.SessionItem>> =
        repository.sessions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前筛选（UI 可变，用 mutableStateOf 持有）。 */
    var filter by mutableStateOf(SessionFilter.All)
        private set
    private val _filter = MutableStateFlow(SessionFilter.All)

    /** 筛选 + 排序（需要你 > 运行中 > pinned > 时间倒序；排序逻辑在 [SessionsOrdering] 可单测）。 */
    val sessions: StateFlow<List<SessionsRepository.SessionItem>> =
        combine(allSessions, _filter) { list, f ->
            SessionsOrdering.apply(list, f)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val connectionState: StateFlow<ZcodeConnection.State> =
        connection.state

    /**
     * 列表加载态：UI 用它区分「真没有会话」/「电脑端 runtime 没起来」/「加载中」。
     * 修复依据（真机 v0.2.17）：三者此前都渲染成「暂无任务」，用户无法判断该等还是该重试。
     */
    val listState: StateFlow<SessionsListState> =
        repository.listState.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionsListState.Loading)

    /** 手动重试：用户在「电脑端未启动」提示上点重试时调用。 */
    fun retrySubscribe() {
        repository.retrySubscribe()
    }

    /** 可选 workspace 列表（来自 serverInfo/bootstrap）。 */
    val workspaces: StateFlow<List<ServerInfo.Workspace>> =
        connection.serverInfo.map { it?.workspaces ?: emptyList() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前选中 workspace（Home 页 chip 显示 + 选择器高亮）。 */
    val currentWorkspace: StateFlow<ServerInfo.Workspace?> =
        connection.currentWorkspace

    /** 用户点选 workspace：交给连接层切换（relay 会重开 bridge）。 */
    fun selectWorkspace(ws: ServerInfo.Workspace) {
        connection.selectWorkspace(ws)
    }

    init {
        repository.start()
        repository.attachLocalState(localStateStore, viewModelScope)
        // 修复依据（P2-5）：workspace 由 ZcodeConnection.currentWorkspace 单一派生，
        // 这里只消费不再自行推导（多 workspace 选择器后续版本）
        viewModelScope.launch {
            connection.currentWorkspace.collect { ws ->
                if (ws != null) repository.setWorkspace(ws.path, ws.workspaceIdentity)
            }
        }
    }

    fun onFilterSelected(f: SessionFilter) {
        filter = f
        _filter.value = f
    }

    /** 打开会话：记录已读水位（未读消除）。 */
    fun markSeen(sessionId: String) {
        viewModelScope.launch { repository.markSeen(sessionId) }
    }

    fun togglePinned(sessionId: String, pinned: Boolean) {
        viewModelScope.launch { repository.setPinned(sessionId, pinned) }
    }

    /** 创建中/失败提示（UI 局部状态）。 */
    var creating by mutableStateOf(false)
        private set
    var createError by mutableStateOf<String?>(null)
        private set

    /**
     * Android 端创建会话（用户报"应该支持在 Android 上进行会话创建"）。
     * 成功后直接打开新会话；创建成功的新会话会随 sessions-index 增量出现在列表里。
     */
    fun createSession(onCreated: (sessionId: String) -> Unit) {
        if (creating) return
        creating = true
        createError = null
        viewModelScope.launch {
            when (val result = connection.createSession(firstInput = null)) {
                is ZcodeConnection.CreateSessionResult.Success -> {
                    creating = false
                    onCreated(result.sessionId)
                }

                is ZcodeConnection.CreateSessionResult.Failure -> {
                    creating = false
                    createError = createErrorMessage(result.reason)
                }
            }
        }
    }

    /**
     * 失败文案：把服务端给的真实原因说给用户听。
     * 修复依据（真机 v0.2.17「创建会话还是不行」）：一句"请确认电脑端在线后重试"在用户
     * 已经确认在线之后毫无价值。这里按 [ZcodeConnection.CreateSessionReason] 分流——
     * runtime 不可用要等、被拒要报服务端的话、没 sessionId 是另一类故障，处置各不相同。
     */
    private fun createErrorMessage(reason: ZcodeConnection.CreateSessionReason): String = when (reason) {
        ZcodeConnection.CreateSessionReason.NotConnected ->
            "未连接到电脑，连接恢复后再试"

        ZcodeConnection.CreateSessionReason.NoWorkspace ->
            "还没有选定工作区，先在顶部选一个"

        is ZcodeConnection.CreateSessionReason.Rejected -> buildString {
            append("电脑端拒绝创建")
            reason.reasonCode?.takeIf { it.isNotBlank() }?.let { append("（$it）") }
            reason.message?.takeIf { it.isNotBlank() }?.let { append("：$it") }
        }

        ZcodeConnection.CreateSessionReason.NoSessionId ->
            "电脑端没有返回新会话 id，请更新电脑端 ZCode 后重试"

        is ZcodeConnection.CreateSessionReason.Error ->
            if (reason.code == null && reason.message.isNullOrBlank()) {
                "创建会话失败，请稍后重试"
            } else {
                buildString {
                    append("创建会话失败")
                    reason.code?.let { append("（$it）") }
                    reason.message?.takeIf { it.isNotBlank() }?.let { append("：$it") }
                }
            }
    }

    fun dismissCreateError() {
        createError = null
    }
}
