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
        // collectUiState 不能在这里调用：viewModelScope 用 Dispatchers.Main.immediate，
        // launch 的协程可能在构造同步执行，而下面的 currentMode/currentThought 属性
        // 尚未初始化（MutableState null）→ NPE（真机崩溃 ChatViewModel.kt:816）。
        // 调用移到类尾 init，保证所有属性先完成初始化。
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
        lastModeSwitchAtMs = System.currentTimeMillis()
        viewModelScope.launch {
            val ok = repository.switchMode(mode)
            if (!ok) {
                // 失败回滚到服务端权威值（而不是 blind previous）：
                // previous 可能是 null（快照未到），回 null 会让 UI 显示错误的兜底文案
                currentMode = serverMode
            }
        }
    }

    /**
     * 服务端权威模式（state.config.mode，经 SessionModeChanged 事件随帧更新）。
     * 修复依据（用户报「从其他权限改计划模式要点两次才生效」）：此前 currentMode
     * 只靠 switchMode 乐观写入，从不订阅服务端 state——进入会话时 null（UI 兜底
     * 显示「变更前确认」），乐观写入后一旦命令被 stale 拒绝又回滚到 null，表现
     * 即「点了没反应」。现在 currentMode 的展示值 = 乐观值（等待确认）× 服务端
     * 帧（权威），collectUiState 在每帧 publish 时把权威值同步进来。
     */
    private var serverMode: String? = null

    /** 订阅会话状态帧：config.mode/config.thought 每次快照/增量后同步权威值。 */
    private fun collectUiState() {
        viewModelScope.launch {
            ui.collect { state ->
                val config = state.state?.get("config")?.asObjectOrNull()
                config?.get("mode")?.asStringOrNull()?.let { serverMode = it }
                config?.get("thought")?.asStringOrNull()?.let { serverThought = it }
                // 权威帧到达：若没有进行中的乐观覆盖（currentMode 与 serverMode 不同
                // 且不在等待 ack 的窗口），跟随服务端。乐观窗口判定：switchMode 发出
                // 后 3 秒内不覆盖，ack 回来（成功=服务端值与乐观一致，失败=上面回滚）
                // 后自然对齐。
                val now = System.currentTimeMillis()
                if (now - lastModeSwitchAtMs > MODE_OPTIMISTIC_WINDOW_MS) {
                    currentMode = serverMode
                    currentThought = serverThought
                }
            }
        }
    }

    private var serverThought: String? = null
    private var lastModeSwitchAtMs = 0L

    private companion object {
        /** 乐观覆盖窗口：命令 RTT 通常 <2s，3s 内不信服务端旧帧。 */
        const val MODE_OPTIMISTIC_WINDOW_MS = 3_000L
    }

    fun switchThought(thought: String) {
        // 当前模型不支持该档位时直接忽略（UI 应只展示支持的档位，这里是防御）：
        // 硬发会被服务端 reasoning-level-not-supported 拒绝，用户看到「跳回去」。
        val supported = currentModel?.supportedThoughtLevels
        if (supported != null && supported.isNotEmpty() && thought !in supported) return
        val previous = currentThought
        currentThought = thought
        lastModeSwitchAtMs = System.currentTimeMillis()
        viewModelScope.launch {
            if (!repository.switchThought(thought)) currentThought = serverThought
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
        viewModelScope.launch { repository.switchBranch(name)
        }
    }

    /** 创建并检出新分支（官方分支选择器「创建并检出新分支...」同款 RPC）。 */
    fun createBranch(name: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = runCatching {
                connection.protocol.value?.gitCreateBranchAndSwitch(
                    workspacePath = workspacePath,
                    branchName = name.trim(),
                    workspaceIdentity = workspace?.workspaceIdentity,
                )
            }.getOrNull() == true
            onResult(ok)
        }
    }

    // ── 消息级交互（feedback/retry/fork，官方 CAS 命令）──

    /** 点赞/点踩（feedback: "like"/"dislike"/null 取消）。 */
    fun toggleFeedback(rowId: Long, entityId: String, current: String?) {
        val next = when (current) {
            "like" -> null
            "dislike" -> "like"
            else -> "like"
        }
        viewModelScope.launch { repository.setAssistantFeedback(rowId, entityId, next) }
    }

    /** 点踩。 */
    fun toggleFeedbackDislike(rowId: Long, entityId: String, current: String?) {
        val next = when (current) {
            "dislike" -> null
            "like" -> "dislike"
            else -> "dislike"
        }
        viewModelScope.launch { repository.setAssistantFeedback(rowId, entityId, next) }
    }

    /** 重跑该条消息。 */
    fun retryMessage(rowId: Long, entityId: String) {
        viewModelScope.launch { repository.retryTurn(rowId, entityId) }
    }

    /** fork 新会话；成功回调 childSessionId 供导航。 */
    fun forkMessage(rowId: Long, entityId: String, onForked: (String) -> Unit) {
        viewModelScope.launch {
            repository.forkAssistant(rowId, entityId)?.let(onForked)
        }
    }

    // ── 技能/插件引用目录（官方 composer `/` 面板，skillsReferenceCatalog RPC）──

    data class CatalogItem(val name: String, val description: String, val scope: String, val pluginName: String?) {
        /** 官方面板语义：plugin scope 显示来源前缀。 */
        val label: String get() = if (pluginName != null) "$name（$pluginName）" else name
    }

    var catalogItems by mutableStateOf<List<CatalogItem>>(emptyList())
        private set
    var catalogLoading by mutableStateOf(false)
        private set
    var catalogError by mutableStateOf<String?>(null)
        private set

    /** 拉取技能+插件目录（composer 弹出面板时调用；两者合并按名称排序）。 */
    fun loadCatalog() {
        if (catalogLoading) return
        catalogLoading = true
        catalogError = null
        viewModelScope.launch {
            val result = runCatching {
                // 对齐官方 SlashCommandPlugin.tsx:86-91：带 sessionId（读 resident
                // Session 的 runtime 冻结快照）。超时给 60s——getReadOnlyClient 可能
                // 冷启动 CLI runtime（10s+ 量级），15s 会「电脑端无响应」。
                connection.protocol.value?.skillsReferenceCatalog(
                    workspacePath = workspacePath,
                    workspaceIdentity = workspace?.workspaceIdentity,
                    sessionId = sessionId.takeIf { it.isNotBlank() },
                    remoteSessionId = workspace?.remoteSessionId,
                    timeoutMs = 60_000,
                )
            }
            val skills = result.getOrNull()?.skills
            // skills 只补充分组，不覆盖 workspace-config 推送的命令目录（单一所有者）
            val skillItems = (skills ?: emptyList())
                .map { CatalogItem(it.name, it.description, it.scope, it.pluginName) }
                .sortedBy { it.name }
            catalogItems = (catalogItems + skillItems).distinctBy { it.name }.sortedBy { it.name }
            if (skills == null && catalogItems.isEmpty()) {
                catalogError = "技能目录读取失败：${result.exceptionOrNull()?.message ?: "电脑端无响应"}"
            } else if (catalogItems.isEmpty()) {
                catalogError = "当前工作区没有已启用的命令/技能"
            }
            catalogLoading = false
        }
    }

    /**
     * 订阅 workspace-config topic（`/` 面板命令目录的官方数据源）：订阅即得快照，
     * 之后 config.updated 推送整体替换。零 CLI 冷启动依赖——命令分组不再依赖
     * getSkillReferenceCatalog（那会拉起 CLI runtime 导致「无响应」）。
     */
    private var workspaceConfigSubscribed = false

    fun subscribeWorkspaceConfig() {
        if (workspaceConfigSubscribed) return
        val p = connection.protocol.value ?: return
        workspaceConfigSubscribed = true
        viewModelScope.launch {
            runCatching {
                p.onWorkspaceConfigCommands = { commands ->
                    catalogItems = commands.map { CatalogItem(it.name, it.description, "command", null) }
                    if (commands.isNotEmpty()) catalogError = null
                }
                p.subscribeWorkspaceConfig(
                    workspacePath = workspacePath,
                    workspaceIdentity = workspace?.workspaceIdentity,
                )
            }.onFailure {
                workspaceConfigSubscribed = false
            }
        }
    }

    /** Git 提交图谱（首次 50 条；官方 GitGraphDialog 同款分页参数）。 */
    var gitGraph by mutableStateOf<List<ZcodeProtocolV4.GitGraphCommit>>(emptyList())
        private set
    var gitGraphLoading by mutableStateOf(false)
        private set
    private var gitGraphHasMore = false
    private var gitGraphSkip = 0

    fun loadGitGraph(reset: Boolean = true) {
        if (gitGraphLoading) return
        if (reset) { gitGraphSkip = 0; gitGraph = emptyList() } else if (!gitGraphHasMore) return
        gitGraphLoading = true
        viewModelScope.launch {
            runCatching {
                connection.protocol.value?.gitCommitGraph(
                    workspacePath = workspacePath,
                    maxCount = 50,
                    skip = gitGraphSkip,
                    workspaceIdentity = workspace?.workspaceIdentity,
                )
            }.getOrNull()?.let { result ->
                gitGraph = if (reset) result.commits else gitGraph + result.commits
                gitGraphHasMore = result.hasMore
                gitGraphSkip += result.commits.size
            }
            gitGraphLoading = false
        }
    }

    // ── 终端（官方 ITerminalService：create → 挂监听 → write/resize → dispose）──

    var terminalOutput by mutableStateOf("")
        private set
    var terminalReady by mutableStateOf(false)
        private set
    var terminalError by mutableStateOf<String?>(null)
        private set
    private var terminalId: String? = null

    // 终端渲染器（有状态：跨 chunk 续接被截断的转义序列）。每个 PTY 一份，
    // 新建 shell 时 reset——旧 shell 的输出历史不属于新 shell（用户报「欢迎横幅
    // 重复叠加」：重建 PTY 后新横幅追加在保留了历史的旧缓冲后面）。
    private val vtRenderer = VtRenderer()

    /**
     * 进入终端页时创建 PTY 并挂输出监听（失败可见 + 一次重试）。
     *
     * 会话持久性（用户报「面板一隐藏重开就是新终端」）：PTY 由电脑端 terminalService
     * 持有（内存自增 id，dispose 前一直有效），官方桌面端的做法是「detach 不 dispose」
     * —— 关面板只断监听，PTY 继续跑。这里对齐：terminalId 已存在时只重新 attach，
     * 不再新建；只有 shell 进程真的退了（onTerminalExit）才清 id，下次进入重建。
     */
    fun openTerminal() {
        if (terminalReady) return
        terminalError = null
        // 已有存活 PTY（上次只是隐藏了面板）：直接重新挂监听复用原 shell
        val existingId = terminalId
        if (existingId != null) {
            attachTerminal(existingId)
            terminalReady = true
            return
        }
        viewModelScope.launch {
            // 创建失败重试一次（relay 链路冷启动时 terminal 通道可能未就绪）
            var lastError: Exception? = null
            repeat(2) { attempt ->
                try {
                    val id = connection.protocol.value?.terminalCreate(
                        cols = 80, rows = 24, cwd = workspacePath,
                    ) ?: throw IllegalStateException("terminal create 返回空（桌面端通道未就绪或版本过旧）")
                    terminalId = id
                    // 新 shell 新缓冲：旧历史属于已结束的 shell，渲染器状态（含跨
                    // chunk 未完成序列）一并作废——否则新横幅叠在旧内容后面
                    terminalOutput = ""
                    vtRenderer.reset()
                    attachTerminal(id)
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

    /** 挂终端输出/退出监听。shell 退出时清 id，下次进入终端页重建 PTY。 */
    private fun attachTerminal(id: String) {
        connection.protocol.value?.apply {
            onTerminalData = { _, data ->
                // 修复依据（用户报「终端莫名重复显示大量内容」）：VtRenderer.feed 返回
                // 的是**整屏渲染结果**，此前 `terminalOutput += feed(...)` 每帧把全屏
                // 追加进累积字符串——屏幕越长重复越快（每帧近乎翻倍）。改为渲染器
                // 独占状态：喂帧后把渲染结果整体发布为 terminalOutput（覆盖，不追加）。
                terminalOutput = vtRenderer.feed(data)
            }
            onTerminalExit = { _, _ ->
                terminalReady = false
                terminalId = null
            }
            attachTerminalListeners(id)
        }
    }

    /** 向终端发送一行命令（自动补 CR 回车执行）。 */
    fun sendTerminalCommand(cmd: String) {
        val id = terminalId ?: return
        // 本地回显（用户报「打 clear 终端上只有 c」）：Windows PSReadLine 在窄宽度 +
        // 无转义渲染下回显残缺（逐字符覆盖序列被 ANSI 剥离），用户看不到自己发了什么。
        // 发送同时把命令追加进本地输出流——补「我输入了什么」的可见性；真实执行
        // 结果仍来自 PTY 下行，不伪造终端事实。
        terminalOutput += "❯ $cmd\n"
        viewModelScope.launch {
            runCatching { connection.protocol.value?.terminalWrite(id, "$cmd\r") }
        }
    }

    /**
     * 离开终端页：对齐官方桌面端「detach 不 dispose」——只断监听保留 PTY，
     * 输出缓冲也保留（重开面板接着看历史）；用户销毁终端时才调 [destroyTerminal]。
     */
    fun closeTerminal() {
        terminalReady = false
        connection.protocol.value?.detachTerminalListeners()
    }

    /** 显式销毁终端（真关 tab）：dispose 电脑端 PTY 并清本地状态。 */
    fun destroyTerminal() {
        val id = terminalId ?: return
        terminalReady = false
        terminalOutput = ""
        vtRenderer.reset()
        viewModelScope.launch {
            runCatching { connection.protocol.value?.terminalDispose(id) }
        }
        terminalId = null
    }

    // ── 审查（官方 GitPane 的移动端形态：refresh 三组文件列表 + getDiff 懒加载）──

    /** 审查 scope（官方 GitChangeSourceId 子集；last-turn 走本地快照，移动端不做）。 */
    enum class ReviewScope(val id: String, val label: String) {
        UNSTAGED("unstaged", "未暂存"),
        STAGED("staged", "已暂存"),
        BRANCH("branch", "全部分支更改"),
    }

    data class ReviewFile(val path: String, val kind: String, val added: Int, val removed: Int)

    data class ReviewState(
        val scope: ReviewScope = ReviewScope.UNSTAGED,
        val loading: Boolean = false,
        val error: String? = null,
        val files: List<ReviewFile> = emptyList(),
        val branchLabel: String? = null,
        val currentBranch: String? = null,
        /** 展开文件的 diff patch（path -> patch），懒加载缓存。 */
        val diffs: Map<String, String?> = emptyMap(),
    )

    var reviewState by mutableStateOf(ReviewState())
        private set

    /** 拉取审查数据（进入面板或点刷新）。scope 切换时复用同一份 refresh 结果。 */
    fun refreshReview(scope: ReviewScope = reviewState.scope) {
        reviewState = reviewState.copy(scope = scope, loading = true, error = null, diffs = emptyMap())
        viewModelScope.launch {
            val result = runCatching {
                connection.protocol.value?.gitRefresh(
                    workspacePath = workspacePath,
                    workspaceIdentity = workspace?.workspaceIdentity,
                )
            }.getOrNull()
            reviewState = if (result == null) {
                reviewState.copy(loading = false, error = "无法读取 git 变更（确认电脑端已打开该工作区）")
            } else {
                val files = when (scope) {
                    ReviewScope.UNSTAGED -> result.unstaged
                    ReviewScope.STAGED -> result.staged
                    ReviewScope.BRANCH -> result.branchComparison?.changes ?: emptyList()
                }
                reviewState.copy(
                    loading = false,
                    files = files.map { ReviewFile(it.path, it.kind, it.added, it.removed) },
                    branchLabel = result.branchComparison?.comparisonLabel,
                    currentBranch = result.currentBranch,
                )
            }
        }
    }

    // ── 审查写操作（官方 GitActionMenu：commit/push/stage/unstage/discard/AI message）──

    var reviewBusy by mutableStateOf(false)
        private set
    var reviewResult by mutableStateOf<String?>(null)
        private set

    /** 暂存/取消暂存当前 scope 的全部文件（官方 stagePaths/unstagePaths）。 */
    fun stageAll(staged: Boolean) {
        val paths = reviewState.files.map { it.path }
        if (paths.isEmpty()) return
        reviewBusy = true
        viewModelScope.launch {
            val ok = runCatching {
                if (staged) {
                    connection.protocol.value?.gitUnstagePaths(workspacePath, paths, workspace?.workspaceIdentity)
                } else {
                    connection.protocol.value?.gitStagePaths(workspacePath, paths, workspace?.workspaceIdentity)
                }
            }.getOrNull() == true
            reviewResult = if (ok) null else "操作失败"
            reviewBusy = false
            refreshReview()
        }
    }

    /**
     * 提交：includeUnstaged=true 时先 stage 全部（官方 getCommitDialogStagePaths），
     * 再 commit {message, paths, stagedOnly: !includeUnstaged}；pushAfterCommit 跟一次 push。
     */
    fun commitReview(message: String, includeUnstaged: Boolean, pushAfterCommit: Boolean) {
        if (message.isBlank() || reviewBusy) return
        reviewBusy = true
        reviewResult = null
        viewModelScope.launch {
            val p = connection.protocol.value
            var ok = false
            if (p != null) {
                runCatching {
                    if (includeUnstaged && reviewState.scope == ReviewScope.UNSTAGED) {
                        p.gitStagePaths(
                            workspacePath,
                            reviewState.files.map { it.path },
                            workspace?.workspaceIdentity,
                        )
                    }
                    val hash = p.gitCommit(
                        workspacePath = workspacePath,
                        message = message.trim(),
                        paths = reviewState.files.map { it.path }.takeIf { it.isNotEmpty() },
                        stagedOnly = !includeUnstaged,
                        workspaceIdentity = workspace?.workspaceIdentity,
                    )
                    ok = hash != null
                    if (ok && pushAfterCommit) {
                        ok = p.gitPush(workspacePath, workspace?.workspaceIdentity)
                    }
                }
            }
            reviewResult = if (ok) "已提交${if (pushAfterCommit) "并推送" else ""}" else "提交失败"
            reviewBusy = false
            refreshReview()
        }
    }

    /** AI 生成 commit message（generateCommitMessage，官方 GitCommitDialog 同款按钮）。 */
    fun generateCommitMessage(onResult: (String?) -> Unit) {
        reviewBusy = true
        viewModelScope.launch {
            val message = runCatching {
                connection.protocol.value?.gitGenerateCommitMessage(
                    workspacePath = workspacePath,
                    workspaceIdentity = workspace?.workspaceIdentity,
                    includeUnstaged = reviewState.scope == ReviewScope.UNSTAGED,
                )
            }.getOrNull()
            reviewBusy = false
            onResult(message)
        }
    }

    /** 丢弃当前 scope 文件的工作区更改（危险操作，UI 层需确认对话框）。 */
    fun discardFiles(paths: List<String>) {
        if (paths.isEmpty() || reviewBusy) return
        reviewBusy = true
        viewModelScope.launch {
            val ok = runCatching {
                connection.protocol.value?.gitDiscardPaths(
                    workspacePath, paths, staged = false, workspaceIdentity = workspace?.workspaceIdentity,
                )
            }.getOrNull() == true
            reviewResult = if (ok) null else "丢弃失败"
            reviewBusy = false
            refreshReview()
        }
    }

    /** 展开/收起文件：展开时懒加载 diff patch（官方 loadDiffForChange 同款）。 */
    fun toggleReviewFile(path: String) {
        if (reviewState.diffs.containsKey(path)) {
            reviewState = reviewState.copy(diffs = reviewState.diffs - path)
            return
        }
        // 先置 null（loading 态），patch 到达后回填；失败置空串（展开显示「无法读取」）
        reviewState = reviewState.copy(diffs = reviewState.diffs + (path to null))
        val scopeId = reviewState.scope.id
        viewModelScope.launch {
            val patch = runCatching {
                connection.protocol.value?.gitGetDiff(
                    workspacePath = workspacePath,
                    path = path,
                    sourceId = scopeId,
                    workspaceIdentity = workspace?.workspaceIdentity,
                )
            }.getOrNull()
            reviewState = reviewState.copy(diffs = reviewState.diffs + (path to (patch ?: "")))
        }
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

    /** 图片画廊等 UI 需要直接走协议 RPC 时使用（只读共享同一连接）。 */
    val connectionForUi: ZcodeConnection get() = connection

    // ── 附件上传（官方 attachmentPut 事务；UI 挂在 composer 的 + 菜单）──

    /** bytes 是 AttachmentRef 的必填字段（attachment-ref.ts:10，.strict()）。 */
    data class PendingAttachment(
        val ref: String,
        val fileName: String,
        val mime: String,
        val bytes: Long,
    )

    var pendingAttachments by mutableStateOf<List<PendingAttachment>>(emptyList())
        private set
    var uploadingAttachment by mutableStateOf(false)
        private set
    var uploadError by mutableStateOf<String?>(null)
        private set

    /** 选中的文件经 attachmentUpload 三段事务上传，成功入 pending 列表随下条消息发送。 */
    fun uploadAttachment(fileName: String, mime: String, bytes: ByteArray, onDone: (Boolean) -> Unit) {
        if (uploadingAttachment) return
        uploadingAttachment = true
        uploadError = null
        viewModelScope.launch {
            // 前置激活（修复依据：上传报 The "path" argument must be of type string）：
            // 会话空闲后 CLI 容量去激活，attachmentBegin 的冷恢复分支（服务端
            // ensureResumed 不带 workspace 上下文）会用落盘 session.path（undefined）
            // 起 runtime 而失败。聊天页在线时订阅链路的冷恢复带 workspace、能成功
            // ——先发一次 resync 确保会话 resident，再走上传。
            runCatching { repository.ensureSessionResident() }
            val result = runCatching {
                // 上传可能触发 CLI 冷启动/冷恢复（10s+），90s 对齐 createSession
                connection.protocol.value?.attachmentUpload(
                    sessionId = sessionId,
                    fileName = fileName,
                    mime = mime,
                    bytes = bytes,
                    remoteSessionId = workspace?.remoteSessionId,
                    workspaceIdentity = workspace?.workspaceIdentity,
                    workspacePath = workspacePath,
                    timeoutMs = 90_000,
                )
            }
            uploadingAttachment = false
            val ref = result.getOrNull()
            if (ref != null) {
                pendingAttachments = pendingAttachments +
                    PendingAttachment(ref, fileName, mime, bytes.size.toLong())
            } else {
                // 失败带服务端原因（fault code/消息），不再笼统一句「上传失败」
                val cause = result.exceptionOrNull()?.message ?: "电脑端无响应"
                uploadError = "「$fileName」上传失败：$cause"
            }
            onDone(ref != null)
        }
    }

    fun removeAttachment(ref: String) {
        pendingAttachments = pendingAttachments.filter { it.ref != ref }
    }

    /** UI 点击错误提示时清除（写路径收口在 VM）。 */
    fun dismissUploadError() {
        uploadError = null
    }

    /** 发送（草稿 + 附件一起），成功后清附件。 */
    fun sendWithAttachments() {
        val text = draft.trim()
        if ((text.isEmpty() && pendingAttachments.isEmpty()) || sending) return
        sending = true
        draft = ""
        // 失败回滚用（与 send() 同一纪律：不静默丢草稿）
        val rollback = text.ifEmpty { "（附件）" }
        val attachments = pendingAttachments
        viewModelScope.launch {
            val ok = repository.sendText(
                text,
                attachments.map { AttachmentRef(it.ref, it.fileName, it.mime, it.bytes) },
            )
            if (ok) pendingAttachments = emptyList()
            sending = false
            if (!ok) {
                // 失败：草稿拼回、附件保留（用户不必重传）
                draft = if (draft.isEmpty()) rollback else "$draft\n$rollback"
            }
        }
    }

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

    /**
     * 类尾 init：所有 mutableStateOf 属性初始化完成后才启动状态帧收集。
     * 修复依据（真机崩溃）：collectUiState 原在首个 init 调用，viewModelScope 的
     * Dispatchers.Main.immediate 让协程在构造中同步执行，此时尾部属性（currentMode
     * 等）的 MutableState 还是 null，首次 emit 写入即 NPE。
     */
    init {
        collectUiState()
    }
}
