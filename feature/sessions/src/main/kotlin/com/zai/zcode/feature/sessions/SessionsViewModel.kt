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
    /**
     * 会话列表（官方 sidebar 语义：无状态筛选 tabs——筛选由排序表达：
     * 需要你 > 运行中 > pinned > 最近活动倒序，状态用行内指示器呈现）。
     * 修复依据（用户报「全部/需要你/运行中没必要」+ 官方 workspace-grouped-tasks
     * 无 tabs 的核对结果）。
     */
    val sessions: StateFlow<List<SessionsRepository.SessionItem>> =
        allSessions.map { list -> SessionsOrdering.apply(list, SessionFilter.All) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
     * 新会话草稿：点「+」后进入的待发状态，此时**还没有**会话。
     *
     * 修复依据（真机 v0.2.19「创建会话还是不行」）：此前点「+」立刻发
     * `createSession(firstInput = null)`，服务端只建一条 deferred 空记录——
     * draft 是纯内存态，不落盘也不进 task index（zcodeTaskIndexSyncer.ts:675），
     * 于是「创建成功、列表里永远看不到」。参考实现 chat_page.dart:674 是同一
     * 语义：会话 id 为 null 时先让用户打字，首条消息随 createSession 一起发出。
     * 这里对齐：草稿期只占 UI，不发命令；[startSession] 才真正创建。
     */
    var draftSession by mutableStateOf(false)
        private set

    fun openDraftSession() {
        createError = null
        draftSession = true
        // 草稿页配置选择器需要模型列表：进入草稿页时拉取（失败静默，UI 显示空态）
        loadDraftModelOptions()
    }

    /** 草稿页可选模型（model-selection.getView 扁平投影；与 ChatRepository 同形状）。 */
    data class DraftModelOption(
        val providerId: String,
        val modelId: String,
        val label: String,
    )

    var draftModelOptions by mutableStateOf<List<DraftModelOption>>(emptyList())
        private set
    var draftModelLoading by mutableStateOf(false)
        private set
    // 草稿页选择状态：UI 是唯一写入方（点选 chip），公开 setter
    var selectedModel by mutableStateOf<DraftModelOption?>(null)
    var selectedMode by mutableStateOf<String?>(null)
    var selectedThought by mutableStateOf<String?>(null)

    val modeOptions = listOf(
        ModeOption("plan", "计划模式", "编辑前先出计划"),
        ModeOption("guarded", "变更前确认", "改动前先问我"),
        ModeOption("yolo", "自主模式", "在有风险时询问"),
        ModeOption("build", "完全访问", "减少确认次数"),
    )
    val thoughtOptions = listOf("low", "medium", "high", "xhigh", "max")

    data class ModeOption(val mode: String, val label: String, val description: String)

    fun thoughtLabel(v: String): String = when (v) {
        "low" -> "低"; "medium" -> "中"; "high" -> "高"
        "xhigh" -> "极高"; "max" -> "最高"; else -> v
    }

    private fun loadDraftModelOptions() {
        if (draftModelLoading) return
        draftModelLoading = true
        viewModelScope.launch {
            val p = connection.protocol.value
            draftModelOptions = if (p == null) emptyList() else runCatching {
                p.modelSelectionView()?.providers?.flatMap { provider ->
                    provider.models.map { m ->
                        DraftModelOption(
                            providerId = provider.providerId,
                            modelId = m.modelId,
                            label = if (provider.providerName.isBlank()) m.name
                            else "${provider.providerName} · ${m.name}",
                        )
                    }
                } ?: emptyList()
            }.getOrElse { emptyList() }
            draftModelLoading = false
        }
    }

    fun closeDraftSession() {
        draftSession = false
    }

    /**
     * 用首条消息真正创建会话（对齐 chat_page.dart:687 / session-mgmt.ts:105）。
     * 首条文本非空是硬前提——空 firstInput 只会建出不可见的 draft。
     */
    fun startSession(
        firstMessage: String,
        onCreated: (sessionId: String) -> Unit,
        providerId: String? = null,
        modelId: String? = null,
        thought: String? = null,
        mode: String? = null,
    ) {
        val text = firstMessage.trim()
        if (text.isEmpty() || creating) return
        creating = true
        createError = null
        viewModelScope.launch {
            when (val result = connection.createSession(
                firstInput = text,
                providerId = providerId,
                modelId = modelId,
                thought = thought,
                mode = mode,
            )) {
                is ZcodeConnection.CreateSessionResult.Success -> {
                    creating = false
                    draftSession = false
                    // 新会话刚从 draft 提升并落盘，等订阅推过来要几十秒；立刻重拉
                    // 一次索引，用户回到列表时它已经在那里。
                    repository.refreshIndex()
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
