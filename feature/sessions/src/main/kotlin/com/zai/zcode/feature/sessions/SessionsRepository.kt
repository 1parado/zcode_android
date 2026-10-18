package com.zai.zcode.feature.sessions

import com.zai.zcode.core.common.ZLog
import com.zai.zcode.core.network.ZcodeConnection
import com.zai.zcode.core.protocol.rpc.PromiseErrorException
import com.zai.zcode.core.protocol.rpc.asIntOrNull
import com.zai.zcode.core.protocol.v4.SessionSummaryView
import com.zai.zcode.core.protocol.v4.TaskMetaView
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
 *
 * 修复依据（真机 v0.2.19）：即使 runtime 不在，落盘索引（zcode-task.listTasks）仍能
 * 给出完整列表。所以 RuntimeUnavailable 的含义收窄为「索引也没读到」——只要索引在，
 * 列表就是 Ready 的（只是没有实时 phase）。
 */
enum class SessionsListState {
    /** 首次订阅尚未返回。 */
    Loading,

    /** 至少有一个数据源给出过列表（可能为空，那是真的没有会话）。 */
    Ready,

    /**
     * 两个数据源都不可用：sessions-index 的 runtime 没起来，且落盘索引也没读到。
     * 等电脑端起来后会自动重试。
     */
    RuntimeUnavailable,

    /** 其它订阅/读取错误。 */
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
        /**
         * 只来自落盘索引、当前没有 runtime 视图：标题与时间准确，但没有实时状态
         * （不知道它是在跑、在等审批还是已停）。UI 按"上次已知的样子"呈现，不谎报状态。
         */
        val stale: Boolean = false,
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

    /**
     * 落盘索引行（zcode-task.listTasks / listPinnedTasks），taskId → meta。
     *
     * 修复依据（真机 v0.2.19「看不到工作区下已有的会话」）：这是**权威列表**——读的是
     * SQLite（taskIndexRepo.listTaskMetas），电脑关机再开机也在。sessions-index 只提供
     * 运行时的 phase / 待决数 / 预览，是富化层。参考实现 task_home_page.dart:309 与官方
     * taskListMembershipSets.ts:217 同样以 listTasks 为左表。
     */
    @Volatile private var indexedTasks: Map<String, TaskMetaView> = emptyMap()
    /** 已归档 id：从列表剔除（参考实现 mergeWorkspaceSessionTasks 的 archivedIds）。 */
    @Volatile private var archivedIds: Set<String> = emptySet()
    /** 分区是否成功读到（false = 不可用，按官方 listWithAvailability 保留旧视图）。 */
    @Volatile private var indexAvailable: Boolean = false
    /** 索引拉取协程：workspace 切换时取消旧的。 */
    private var indexJob: Job? = null

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
        indexAvailable = false
        archivedIds = emptySet()
        // 换工作区必须连索引一起清：否则新工作区的骨架屏上会先摆着上一个工作区的
        // 任务，用户看到的是"切过去了但列表没变"，等索引回来了又整组跳变。
        indexedTasks = emptyMap()
        pinnedServerIds = emptySet()
        republish()
        connection.protocol.value?.let { p ->
            subscribe(p, path)
            loadTaskIndex(p, path)
        }
    }

    /**
     * 主动重拉一次落盘索引。
     *
     * 修复依据（真机 v0.2.19「创建会话还是不行」）：新建会话后，draft→promoted 的
     * 落盘与 sessions-index 的增量推送都不是同步发生的。等订阅推过来要几十秒，
     * 用户回到列表看不到自己刚建的会话就会判定"创建失败"。建完立刻重拉一次，
     * 让新任务当帧就在列表里出现。
     */
    fun refreshIndex() {
        val p = connection.protocol.value ?: return
        val ws = _workspacePath.value ?: return
        loadTaskIndex(p, ws)
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
        // 用户点了重试：两条路都重走一遍。索引这条路常常在 runtime 起来之前就能成功。
        loadTaskIndex(p, ws)
    }

    private fun onProtocolReady(p: ZcodeProtocolV4) {
        val ws = _workspacePath.value ?: return // 等 UI 选定 workspace 再订阅
        p.onSessionsIndexFrame = { frame, _ -> applyFrame(frame) }
        subscribe(p, ws)
        // 落盘索引与 sessions-index 并行拉：索引先把"已有的会话"摆出来，
        // sessions-index 随后补上运行时状态。两条链路互不阻塞。
        loadTaskIndex(p, ws)
    }

    private fun onProtocolClosed() {
        reducer.reset()
        subscribedProtocol = null
        subscribedWorkspace = null
        sessionsSubscriptionId = null
        retryJob?.cancel()
        retryJob = null
        indexJob?.cancel()
        indexJob = null
        retryDelayIndex = 0
        hasDeliveredSnapshot = false
        // 保留 _sessions 与 indexedTasks：断线时旧数据比白屏有用，重连后直接复现。
        // 但列表态要跟着走：断线期间既有索引也不再是"实时就绪"，UI 才不会在断线时
        // 顶着一张 Ready 的列表假装一切正常。settleIndexState 会在重连后把它拉回来。
        _listState.value = if (indexAvailable || _sessions.value.isNotEmpty()) {
            SessionsListState.RuntimeUnavailable
        } else {
            SessionsListState.Loading
        }
    }

    private fun subscribe(p: ZcodeProtocolV4, ws: String) {
        // 新协议实例(bridge reopen 重建)必须重订阅,即使 workspace 未变
        if (subscribedProtocol === p && subscribedWorkspace == ws) return
        subscribedProtocol = p
        subscribedWorkspace = ws
        retryJob?.cancel()
        retryJob = null
        retryDelayIndex = 0
        // 修复依据（真机 v0.2.19）：这里此前无条件回落到 Loading，而重订阅常常是
        // "索引已经把列表摆出来了、runtime 还没起来"时发生的——结果是用户刚看到的
        // 历史会话被骨架屏盖掉。索引在手就保持 Ready，否则才回到订阅那一路的判定。
        if (!indexAvailable) {
            _listState.value = if (hasDeliveredSnapshot) SessionsListState.Ready else SessionsListState.Loading
        }
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
     * 拉取落盘任务索引（zcode-task 三个分区并行）。
     *
     * 修复依据（真机 v0.2.19「看不到工作区下已有的会话」）：**这是列表的权威来源**。
     * sessions-index 只有 CLI runtime 活着才有数据，而用户要的"之前的会话"是落盘实体。
     * 参考实现 task_home_page.dart:309 `_load()` 并行调三个分区、各 12s、`.catchError`。
     *
     * 三个分区中只要 listTasks（主分区）成功就视为可用；pinned / archived 失败只影响
     * 置顶标记与归档剔除，不推翻主列表——对齐官方 taskListMembershipSets.ts:217 注释
     * 「任一分区 RPC 失败都不能当作权威空集发布，否则会把旧缓存整组清空」。
     */
    private fun loadTaskIndex(p: ZcodeProtocolV4, ws: String) {
        indexJob?.cancel()
        indexJob = scope.launch {
            val identity = workspaceIdentity
            val main: List<TaskMetaView> = try {
                p.listTasks(ws, identity)
            } catch (e: Exception) {
                ZLog.w(TAG, "zcode-task listTasks failed: ${e.message}")
                // 主分区不可用：保留上一份索引视图，不置空（官方 listWithAvailability）。
                settleIndexState()
                return@launch
            }
            // 置顶 / 归档是可选分区：失败时只丢这两个分区，主列表照常呈现。
            // 这里用 try/catch 而不是 runCatching——runCatching 的 lambda 不是 suspend
            // 上下文，包不住挂起调用。
            val pinned: List<TaskMetaView> = try {
                p.listPinnedTasks(ws, identity)
            } catch (e: Exception) {
                ZLog.w(TAG, "zcode-task listPinnedTasks failed: ${e.message}")
                emptyList()
            }
            val archived: List<TaskMetaView> = try {
                p.listArchivedTasks(ws, identity)
            } catch (e: Exception) {
                ZLog.w(TAG, "zcode-task listArchivedTasks failed: ${e.message}")
                emptyList()
            }

            // 退避期间 workspace 可能已切换：只接受当前 workspace 的结果。
            if (_workspacePath.value != ws) return@launch

            val pinnedIds = pinned.mapNotNull { it.taskId }.toSet()
            archivedIds = archived.mapNotNull { it.taskId }.toSet()
            indexedTasks = buildMap {
                for (t in main + pinned) {
                    val id = t.taskId ?: continue
                    put(id, t)
                }
            }
            // 整集替换而非累加：取消置顶后重拉必须让旧标记消失。
            pinnedServerIds = pinnedIds
            indexAvailable = true
            ZLog.i(TAG, "task index loaded tasks=${indexedTasks.size} archived=${archivedIds.size}")
            settleIndexState()
            republish()
        }
    }

    /** 服务端置顶 id（listPinnedTasks 给出）。 */
    @Volatile private var pinnedServerIds: Set<String> = emptySet()

    /**
     * 索引到达/失败后收口加载态。
     *
     * 修复依据（真机 v0.2.19）：列表可用的判据从「sessions-index 首帧」放宽为「任一
     * 数据源给出过列表」。runtime 没起来时索引照样能出列表，UI 不该停在骨架屏或
     * 「这个工作区还没有运行」上——那样用户仍然看不到历史会话。
     */
    private fun settleIndexState() {
        if (hasDeliveredSnapshot || indexAvailable) {
            _listState.value = SessionsListState.Ready
            return
        }
        // 两个源都还没成功：保留 sessions-index 那一路判定的态（Loading / RuntimeUnavailable
        // / Error）。订阅重试仍在跑，起来后会自动补上。
    }

    /**
     * 订阅失败退避重试。runtime unavailable 是"可以等"的稳态，退避到 60s 后仍在重试；
     * 其它错误重试同样有意义（桌面短暂不可达），但 UI 按错误态呈现。
     */
    private fun scheduleSubscribeRetry(ws: String, error: Exception) {
        val unavailable = isRuntimeUnavailable(error)
        // 修复依据（真机 v0.2.19）：索引已经给出列表时不要把它降级成错误态——
        // runtime 不在只是少了实时状态，用户要的历史会话一个不少地显示着。
        if (!indexAvailable) {
            _listState.value = if (unavailable) SessionsListState.RuntimeUnavailable else SessionsListState.Error
        }
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
                    // 同上：索引在手就不要把整屏降级。
                    if (!indexAvailable) {
                        _listState.value = if (isRuntimeUnavailable(e)) {
                            SessionsListState.RuntimeUnavailable
                        } else {
                            SessionsListState.Error
                        }
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

    /**
     * 落盘索引（权威）+ sessions-index 运行时（富化）+ 本地 pinned/seen 三源合并发布。
     *
     * 修复依据（真机 v0.2.19「看不到工作区下已有的会话」）：此前只合并后两者，而
     * sessions-index 在 runtime 不在时为空 → 用户看到"历史会话全没了"。现在以落盘
     * 索引为左表：runtime 活着时两者按 id 对齐取并集（索引给 createdAt/updatedAt，
     * sessions-index 给 phase/待决数/预览），runtime 不在时索引单独撑起整个列表。
     *
     * 对齐参考实现 mergeWorkspaceSessionTasks（task_home_page.dart:64）：索引行打底，
     * 同 id 的 session 行覆盖其运行时字段，两侧各自独有的条目都保留，按 updatedAt 倒序。
     */
    private fun republish() {
        val st = reducer.snapshot()
        val live = st.sessions.mapNotNull { raw ->
            val v = SessionSummaryView(raw)
            val id = v.sessionId ?: return@mapNotNull null
            id to v
        }.toMap()

        val merged = LinkedHashMap<String, SessionItem>()
        // 1) 落盘索引打底（权威列表；归档的剔除）
        for ((id, meta) in indexedTasks) {
            if (id in archivedIds) continue
            merged[id] = indexToItem(id, meta, live[id])
        }
        // 2) sessions-index 独有条目：新建的 draft 会话还没落盘，只有运行时看得到
        // （sessionPhaseSchema 注释：draft 是纯内存态、无 row、不落盘）。
        for ((id, v) in live) {
            if (merged.containsKey(id) || id in archivedIds) continue
            merged[id] = sessionToItem(v, indexedTasks[id])
        }
        _sessions.value = merged.values.sortedByDescending { it.lastActivityAt ?: 0L }
    }

    /** 索引行 → 列表项；[live] 存在时用它覆盖运行时字段（phase / 待决 / 预览）。 */
    private fun indexToItem(
        id: String,
        meta: TaskMetaView,
        live: SessionSummaryView?,
    ): SessionItem {
        val local = localStates[id]
        val activity = live?.lastActivityAt ?: meta.updatedAt
        val title = live?.title?.takeIf { it.isNotBlank() }
            ?: meta.title?.takeIf { it.isNotBlank() }
            ?: "未命名会话"
        return SessionItem(
            sessionId = id,
            title = title,
            // 落盘 status（running|completed|error）→ phase；runtime 在线时以 live 为准。
            phase = live?.phase ?: when (meta.status) {
                "running" -> "running"
                "error" -> "error"
                "completed" -> "completedSuccess"
                else -> null
            },
            ended = live?.sessionEnded ?: (meta.status == "completed" || meta.status == "error"),
            lastActivityAt = activity,
            preview = live?.lastAssistantPreview,
            createdAt = live?.createdAt ?: meta.createdAt,
            permissionCount = live?.pendingInteractionSummary?.get("permissionCount").asIntOrNull() ?: 0,
            userInputCount = live?.pendingInteractionSummary?.get("userInputCount").asIntOrNull() ?: 0,
            hasBackgroundWork = live?.hasBackgroundWork ?: false,
            // 置顶：服务端权威（listPinnedTasks）优先，本地标记兜底。
            pinned = id in pinnedServerIds || local?.pinned == true,
            // 未读：服务端 unreadAt 与本地水位取其一（索引行也只有 updatedAt 可比）。
            unread = activity != null && activity > (local?.lastSeenActivityAt ?: 0L),
            // 落盘行没有 runtime：不显示"运行中"这类实时状态，UI 按 stale 表达。
            stale = live == null,
        )
    }

    /** sessions-index 行 → 列表项（draft 等未落盘会话，或索引缺失时的兜底）。 */
    private fun sessionToItem(v: SessionSummaryView, meta: TaskMetaView?): SessionItem {
        val id = v.sessionId!!
        val local = localStates[id]
        val summary = v.pendingInteractionSummary
        val activity = v.lastActivityAt ?: meta?.updatedAt
        return SessionItem(
            sessionId = id,
            title = v.title?.takeIf { it.isNotBlank() } ?: meta?.title?.takeIf { it.isNotBlank() } ?: "未命名会话",
            phase = v.phase,
            ended = v.sessionEnded,
            lastActivityAt = activity,
            preview = v.lastAssistantPreview,
            createdAt = v.createdAt ?: meta?.createdAt,
            permissionCount = summary?.get("permissionCount").asIntOrNull() ?: 0,
            userInputCount = summary?.get("userInputCount").asIntOrNull() ?: 0,
            hasBackgroundWork = v.hasBackgroundWork,
            pinned = id in pinnedServerIds || local?.pinned == true,
            unread = activity != null && activity > (local?.lastSeenActivityAt ?: 0L),
            stale = false,
        )
    }
}
