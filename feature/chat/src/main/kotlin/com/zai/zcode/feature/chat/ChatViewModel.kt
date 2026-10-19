package com.zai.zcode.feature.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zai.zcode.core.common.ClientIdProvider
import com.zai.zcode.core.network.ZcodeConnection
import com.zai.zcode.core.protocol.v4.ZcodeProtocolV4
import com.zai.zcode.core.protocol.rpc.asObjectOrNull
import com.zai.zcode.core.protocol.rpc.asStringOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    // 修复依据（P2-1，评审 2026-10-17）：删除 ConnectionFacade 双层包装，直接注入单例连接
    private val connection: ZcodeConnection,
    private val clientIds: ClientIdProvider,
) : ViewModel() {

    val sessionId: String = savedStateHandle.get<String>("sessionId").orEmpty()
    // 修复依据（P2-5）：workspace 由 ZcodeConnection.currentWorkspace 单一派生，feature 不再自推
    private val workspace = connection.currentWorkspace.value
        ?: connection.serverInfo.value?.workspaces?.firstOrNull()
    private val workspacePath: String = workspace?.path ?: ""

    private val repository = ChatRepository(
        scope = viewModelScope,
        connection = connection,
        sessionId = sessionId,
        workspacePath = workspacePath,
        clientId = clientIds.clientId,
        // 修复依据（真机 v0.2.15）：远程链路 identity 必须贯穿到会话订阅/命令，
        // 否则服务端 workspaceKey 与帧 fan-out 目标错位（AGENTS.md「Workspace Identity」）
        workspaceIdentity = workspace?.workspaceIdentity,
    )

    val ui get() = repository.ui

    /** 是否还有更早的历史（上滑分页指示）。 */
    val hasMoreHistory: StateFlow<Boolean> =
        repository.hasMoreHistory.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** 用户上滑到顶：向上翻一页历史。 */
    fun loadOlder() {
        repository.loadOlder()
    }

    // ── 模型选择 ──

    /** 可选模型列表（懒加载一次；选择器打开时才拉取）。 */
    var modelOptions by mutableStateOf<List<ChatRepository.ModelOption>>(emptyList())
        private set
    var currentModel by mutableStateOf<ChatRepository.ModelOption?>(null)
        private set
    var modelLoading by mutableStateOf(false)
        private set

    /** 打开选择器时拉取列表 + 当前选中。 */
    fun openModelPicker() {
        if (modelLoading) return
        modelLoading = true
        viewModelScope.launch {
            val options = repository.loadModelOptions()
            modelOptions = options
            currentModel = repository.loadCurrentSelection()
            modelLoading = false
        }
    }

    /**
     * 选中一个模型：切换命令成功后乐观更新本地选中。
     * 修复依据（用户报「选了都跳回 GLM-5.3」）：switchModelConfig 的 thought 必须在
     * 目标模型的档位集内（服务端 validateModelSelectionOptions 直接拒绝）。
     * 切模型时档位取「目标模型支持的档位里最接近当前档位的一个」——当前档位在
     * 新模型不支持时回落到该模型的最高档，避免整个命令被拒导致切不动。
     */
    fun selectModel(option: ChatRepository.ModelOption) {
        viewModelScope.launch {
            val curThought = currentThought
            val target = pickSupportedThought(option, curThought)
            val ok = repository.switchModel(option.providerId, option.modelId, target)
            if (ok) {
                currentModel = option
                currentThought = target
            }
        }
    }

    /** 目标模型支持的最接近档位：原档位支持则保留；否则取该模型最高一档。 */
    private fun pickSupportedThought(option: ChatRepository.ModelOption, cur: String?): String {
        val levels = option.supportedThoughtLevels
        if (levels.isEmpty()) return cur ?: "medium"
        if (cur != null && cur in levels) return cur
        // 档位序（协议 low<medium<high<xhigh<max）：取比当前低的最近档，没有就取最低
        val order = listOf("low", "medium", "high", "xhigh", "max")
        val curIdx = order.indexOf(cur)
        val supported = levels.sortedBy { order.indexOf(it) }
        return supported.lastOrNull { order.indexOf(it) <= (if (curIdx < 0) order.lastIndex else curIdx) }
            ?: supported.first()
    }

    // ── 权限模式 + 思考档位（官方 mobile composer 同款四档/推理档）──

    /** 一档协作模式（switchCollaborationMode 的 mode 值 + 中文标签）。 */
    data class ModeOption(val mode: String, val label: String, val description: String)

    // ── Git 分支选择 ──

    val gitBranches: StateFlow<ZcodeProtocolV4.GitBranchList?> =
        repository.gitBranches.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // 分支列表与会话无状态依赖，进入会话拉一次（非 git 仓库 isRepository=false，UI 隐藏）
        viewModelScope.launch { repository.loadGitBranches() }
        // 从会话快照 state 读当前模式/档位（config 投影），保证 UI 与电脑端一致
        viewModelScope.launch {
            ui.collect { snapshot ->
                snapshot.state?.get("config")?.asObjectOrNull()?.let { cfg ->
                    cfg["mode"].asStringOrNull()?.let { if (currentMode != it) currentMode = it }
                    cfg["thoughtLevel"].asStringOrNull()?.let { if (currentThought != it) currentThought = it }
                }
            }
        }
    }

    /** 当前协作模式（snapshot config 投影;读不到时 null=未知，UI 显示「默认」）。 */
    var currentMode by mutableStateOf<String?>(null)
        private set

    /** 当前思考档位。 */
    var currentThought by mutableStateOf<String?>(null)
        private set

    /** 官方四档（zh-CN 标签 + switchCollaborationMode mode 值）。 */
    val modeOptions = listOf(
        ModeOption("plan", "计划模式", "编辑前先出计划"),
        ModeOption("guarded", "变更前确认", "改动前先问我"),
        ModeOption("yolo", "自主模式", "在有风险时询问"),
        ModeOption("build", "完全访问", "减少确认次数"),
    )

    /** 推理档位（zh-CN chat.toolbar.thoughtLevel.value.*）。 */
    val thoughtOptions = listOf("low", "medium", "high", "xhigh", "max")

    fun thoughtLabel(v: String): String = when (v) {
        "low" -> "低"; "medium" -> "中"; "high" -> "高"
        "xhigh" -> "极高"; "max" -> "最高"; else -> v
    }

    fun modeLabel(v: String?): String =
        modeOptions.firstOrNull { it.mode == v }?.let { "${it.label} · ${it.description}" }
            ?: "变更前确认 · 改动前先问我"

    fun switchMode(mode: String) {
        val previous = currentMode
        currentMode = mode
        viewModelScope.launch {
            if (!repository.switchMode(mode)) currentMode = previous
        }
    }

    fun switchThought(thought: String) {
        // 当前模型不支持该档位时直接忽略（UI 应只展示支持的档位，这里是防御）：
        // 硬发会被服务端 reasoning-level-not-supported 拒绝，用户看到「跳回去」。
        val supported = currentModel?.supportedThoughtLevels
        if (supported != null && supported.isNotEmpty() && thought !in supported) return
        val previous = currentThought
        currentThought = thought
        viewModelScope.launch {
            if (!repository.switchThought(thought)) currentThought = previous
        }
    }

    // ── 队列管理 ──

    fun editQueueItem(queueItemId: String, newText: String) {
        viewModelScope.launch { repository.editQueueItem(queueItemId, newText) }
    }

    /** 移到队尾（远程操控最高频的调整：让这条最后跑）。 */
    fun moveQueueItemToEnd(queueItemId: String) {
        viewModelScope.launch { repository.reorderQueueItem(queueItemId, null) }
    }

    fun deleteQueueItem(queueItemId: String) {
        viewModelScope.launch { repository.deleteQueueItem(queueItemId) }
    }

    fun setAutoDrain(autoDrain: Boolean) {
        viewModelScope.launch { repository.setAutoDrain(autoDrain) }
    }

    /** 切换 git 分支（成功后 gitBranches 流自动刷新）。 */
    fun switchBranch(name: String) {
        viewModelScope.launch { repository.switchBranch(name) }
    }

    // ── 终端（官方 ITerminalService：create → 挂监听 → write/resize → dispose）──

    var terminalOutput by mutableStateOf("")
        private set
    var terminalReady by mutableStateOf(false)
        private set
    var terminalError by mutableStateOf<String?>(null)
        private set
    private var terminalId: String? = null

    /** 进入终端页时创建 PTY 并挂输出监听（失败可见 + 一次重试）。 */
    fun openTerminal() {
        if (terminalReady) return
        terminalError = null
        viewModelScope.launch {
            // 创建失败重试一次（relay 链路冷启动时 terminal 通道可能未就绪）
            var lastError: Exception? = null
            repeat(2) { attempt ->
                try {
                    val id = connection.protocol.value?.terminalCreate(
                        cols = 80, rows = 24, cwd = workspacePath,
                    ) ?: throw IllegalStateException("terminal create 返回空（桌面端通道未就绪或版本过旧）")
                    terminalId = id
                    connection.protocol.value?.onTerminalData = { _, data ->
                        terminalOutput += data
                            .replace(Regex("\u001B\\[[0-9;?]*[a-zA-Z]"), "")
                            .replace(Regex("\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)"), "")
                            .replace("\r", "")
                    }
                    connection.protocol.value?.onTerminalExit = { _, _ ->
                        terminalReady = false
                    }
                    connection.protocol.value?.attachTerminalListeners(id)
                    terminalReady = true
                    return@launch
                } catch (e: Exception) {
                    lastError = e
                    android.util.Log.w("ChatViewModel", "terminal create attempt ${attempt + 1} failed: ${e.message}")
                    delay(2_000)
                }
            }
            terminalError = lastError?.message ?: "终端创建失败"
        }
    }

    /** 向终端发送一行命令（自动补 CR 回车执行）。 */
    fun sendTerminalCommand(cmd: String) {
        val id = terminalId ?: return
        viewModelScope.launch {
            runCatching { connection.protocol.value?.terminalWrite(id, "$cmd\r") }
        }
    }

    /** 离开终端页：释放 PTY 与监听。 */
    fun closeTerminal() {
        val id = terminalId ?: return
        terminalReady = false
        terminalOutput = ""
        viewModelScope.launch {
            runCatching { connection.protocol.value?.terminalDispose(id) }
        }
        terminalId = null
    }

    /**
     * 会话加载态：区分「在等」/「电脑端 runtime 没起来」/「读不出来」。
     * 与 [ui] 分离是因为「零行」有两种截然不同的含义：会话真的还没有内容，
     * 和一个字都没读出来。UI 必须分开呈现。
     */
    val loadState: StateFlow<ChatLoadState> =
        repository.loadState.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatLoadState.Loading)

    /** 用户在「电脑端未运行」提示上点重试。 */
    fun retrySubscribe() {
        repository.retrySubscribe()
    }

    /**
     * 会话标题（[ChatRepository.ChatUi.title]）。null = 服务端没给且本地推不出来。
     *
     * 单独一条流而不是让 UI 从 [ui] 里取：标题在流式过程中几乎不变，而 [ui] 每帧
     * 都是新对象——不加区分地订阅会让顶栏跟着每一行 token 一起重组。
     */
    val title: StateFlow<String?> =
        repository.ui
            .map { it.title }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 连接态。会话页此前完全不看它：断线时屏幕上唯一的线索是"内容不再增长"，
     * 用户会读成模型卡住而不是网络断了。顶栏在非 Ready 时显示一行状态文案。
     */
    val connectionState: StateFlow<ZcodeConnection.State> =
        connection.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), connection.state.value)

    /** 草稿与发送态（UI 局部状态，不入协议层）。 */
    var draft by mutableStateOf("")
        private set
    var sending by mutableStateOf(false)
        private set

    init {
        repository.start()
    }

    fun onDraftChange(v: String) { draft = v }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty() || sending) return
        sending = true
        draft = ""
        viewModelScope.launch {
            val ok = repository.sendText(text)
            sending = false
            if (!ok) {
                // 修复依据（P3，评审 2026-10-17）：发送期间用户若已输入新文本，直接覆盖会
                // 静默丢新草稿；改为把失败文本换行拼回尾部，两者都保留
                draft = if (draft.isEmpty()) text else "$draft\n$text"
            }
        }
    }

    fun stop() {
        viewModelScope.launch { repository.stop() }
    }

    /**
     * 解决一个待决交互（审批放行 / 用户输入）。
     *
     * **步进而非全批**：多个待决时 UI 一次只呈现一个（[currentInteraction]），
     * 逐个确认。批量放行会把"我同意这个命令"变成"我同意接下来所有命令"，
     * 手机上一次误触的代价太大。
     *
     * @param optionId 服务端下发的选项 id，绝不由客户端合成
     */
    fun resolveInteraction(interactionId: String, optionId: String? = null, freeText: String? = null) {
        if (resolvingId != null) return // 单飞：连点两下不会发出两个 resolve
        resolvingId = interactionId
        resolveError = null
        viewModelScope.launch {
            val ok = repository.resolveInteraction(interactionId, optionId, freeText)
            resolvingId = null
            if (!ok) resolveError = "没能提交，电脑端可能已断开"
        }
    }

    /** 正在提交的交互 id（null = 空闲）。单飞 + 该卡片显示提交中。 */
    var resolvingId by mutableStateOf<String?>(null)
        private set

    /** 提交失败提示。 */
    var resolveError by mutableStateOf<String?>(null)
        private set

    fun dismissResolveError() { resolveError = null }
}
