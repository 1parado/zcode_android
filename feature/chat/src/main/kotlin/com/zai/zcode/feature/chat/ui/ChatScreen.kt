package com.zai.zcode.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zai.zcode.core.design.LocalZcodeColors
import com.zai.zcode.core.design.ZcodeShape
import com.zai.zcode.core.design.ZcodeSpacing
import com.zai.zcode.core.design.ZcodeType
import com.zai.zcode.core.design.components.EmptyState
import com.zai.zcode.core.design.components.LiveDot
import com.zai.zcode.core.design.components.ZcodeComposer
import com.zai.zcode.core.design.components.ZcodeStatus
import com.zai.zcode.core.design.jellyClickable
import com.zai.zcode.core.network.ZcodeConnection
import com.zai.zcode.core.network.stateLabel
import com.zai.zcode.feature.chat.ChatLoadState
import com.zai.zcode.feature.chat.ChatRepository
import com.zai.zcode.feature.chat.ChatViewModel
import kotlinx.coroutines.launch

/**
 * 对话页：行流（LazyColumn）+ 底部输入栏。
 * 流式渲染依赖 row.delta append 的细粒度归约快照 —— 行 key 稳定，只有变化的行重组。
 */
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onWatchScreen: () -> Unit,
    onOpenSession: (String) -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsState()
    // 修复依据（真机 v0.2.20「会话页一直转圈」）：光有 rows 无法区分「在读」
    // 与「打开了一个还没有内容的会话」，必须把加载态和列表一起看。
    val loadState by viewModel.loadState.collectAsState()
    // 修复依据（真机 v0.2.21「不知道自己在看哪个会话」）：顶栏要显示会话标题，
    // 以及"电脑端断开了"这件事——会话页此前完全不看连接态，断线时唯一的线索是
    // 内容不再增长，用户会以为是模型卡住了。
    val title by viewModel.title.collectAsState()
    val connState by viewModel.connectionState.collectAsState()
    val listState = rememberLazyListState()
    val zc = LocalZcodeColors.current
    // 模型选择弹层（官方 mobile composer 的模型选择入口语义）
    var showModelPicker by remember { mutableStateOf(false) }
    // Git 分支切换弹层
    var showBranchPicker by remember { mutableStateOf(false) }
    // 终端弹层（官方切换面板「终端」tab 的移动端形态）
    var showTerminal by remember { mutableStateOf(false) }
    var showReview by remember { mutableStateOf(false) }
    // 侧边栏（用户需求 2026-10-19：终端/审查不占顶栏，收进一个侧边栏图标，
    // 点击后从右侧滑入面板，单独点开审查或终端——对齐官方 side pane 的
    // mobileOverlay 抽屉模式）
    var showSidePane by remember { mutableStateOf(false) }

    // 是否已停在底部：决定「跟随新内容」还是「累计未读」。
    // derivedStateOf 让它只在跨越阈值时变，滚动过程中不触发额外重组。
    // 阈值 -1：头部 history_header 占一个 index，最后一行是 totalItemsCount-2。
    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            last == null || last.index >= listState.layoutInfo.totalItemsCount - 1
        }
    }
    var pendingCount by remember { mutableIntStateOf(0) }
    var settledCount by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(ui.rows.size) {
        if (ui.rows.isEmpty()) return@LaunchedEffect
        if (atBottom) {
            // +1：头部 history_header 占一个 index（见 atBottom 注释）
            listState.scrollToItem(ui.rows.size)
        } else if (ui.rows.size > settledCount) {
            // 修复依据（spec §5.3）：用户在翻历史时不把视图拽走，改为累计未读数
            pendingCount += ui.rows.size - settledCount
        }
        settledCount = ui.rows.size
    }

    // 上滑加载更早历史：滚到顶（firstVisibleItemIndex==0 且停稳）触发，对齐官方
    // loadOlder 的"滑到窗口首行再拉"节奏。collectIsDistinctFrom 无法直接用，
    // 用 derivedStateOf 只在跨入顶部时触发一次。
    val hasMoreHistory by viewModel.hasMoreHistory.collectAsState()
    val atTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && ui.rows.isNotEmpty() }
    }
    LaunchedEffect(atTop) {
        if (atTop && hasMoreHistory) viewModel.loadOlder()
    }
    // fork 成功 → 导航到子会话（官方 SessionPane 拿到 ack.sessionId 原地切换）
    LaunchedEffect(Unit) {
        ForkEventBus.events.collect { childId ->
            if (childId.isNotBlank()) onOpenSession(childId)
        }
    }

    LaunchedEffect(atBottom) {
        if (atBottom) pendingCount = 0
    }

    Box(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxSize()) {
            val gitBranches by viewModel.gitBranches.collectAsState()
            ChatTopBar(
                title = title,
                connState = connState,
                isRunning = ui.isRunning,
                gitBranches = gitBranches,
                onBack = onBack,
                onWatchScreen = onWatchScreen,
                onPickModel = { showModelPicker = true },
                onPickBranch = { showBranchPicker = true },
                // toggle 语义（用户反馈）：点图标开、再点一次（或点遮罩）关
                onOpenSidePane = { showSidePane = !showSidePane },
            )
            if (showTerminal) {
                TerminalSheet(
                    viewModel = viewModel,
                    onDismiss = { viewModel.closeTerminal(); showTerminal = false },
                )
            }
            if (showReview) {
                ReviewSheet(
                    viewModel = viewModel,
                    onDismiss = { showReview = false },
                )
            }
            // 侧边栏：从右侧滑入（AnimatedVisibility 滑动 + 淡入，200ms 内克制动画）
            SidePane(
                viewModel = viewModel,
                visible = showSidePane,
                onDismiss = { showSidePane = false },
                onOpenTerminal = {
                    showSidePane = false
                    showTerminal = true
                },
                onOpenReview = {
                    showSidePane = false
                    viewModel.refreshReview()
                    showReview = true
                },
            )
            if (showBranchPicker) {
                BranchPickerSheet(
                    viewModel = viewModel,
                    onDismiss = { showBranchPicker = false },
                )
            }
            if (showModelPicker) {
                ModelPickerSheet(
                    viewModel = viewModel,
                    onDismiss = { showModelPicker = false },
                )
            }
            ChatRowList(
                rows = ui.rows,
                listState = listState,
                loadState = loadState,
                hasMoreHistory = hasMoreHistory,
                onRetry = viewModel::retrySubscribe,
                imageContext = viewModel.sessionId to viewModel.connectionForUi,
                viewModel = viewModel,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            // 上下文用量条（usage.contextWindow）：>80% 变琥珀提醒，>95% 变红。
            ui.contextUsage?.let { ratio ->
                ContextUsageBar(ratio)
            }
            // 子代理进度卡（state.subagents.running）：并行任务可见。
            if (ui.subagents.isNotEmpty()) {
                SubagentsCard(ui.subagents, ui.subagentsEnded)
            }
            // 队列卡（state.queue.items）：人不在电脑前的排队指令，可删/移到队尾。
            if (ui.queue.isNotEmpty()) {
                QueueCard(
                    items = ui.queue,
                    autoDrain = ui.queueAutoDrain,
                    onEdit = viewModel::editQueueItem,
                    onMoveToEnd = viewModel::moveQueueItemToEnd,
                    onDelete = viewModel::deleteQueueItem,
                    onToggleAutoDrain = viewModel::setAutoDrain,
                )
            }
            // 审批 Dock 挂在输入栏上方：命令卡住时用户视线本来就在底部，
            // 而且它与「正在输入什么」是同一块注意力区域，不该分开放。
            ApprovalDock(
                interactions = ui.pendingInteractions,
                resolvingId = viewModel.resolvingId,
                onSubmit = { id, optionId, freeText ->
                    viewModel.resolveInteraction(id, optionId, freeText)
                },
            )
            viewModel.resolveError?.let { msg ->
                Text(
                    msg,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = viewModel::dismissResolveError)
                        .padding(horizontal = ZcodeSpacing.screenPadding, vertical = ZcodeSpacing.sm),
                    style = ZcodeType.uiXs,
                    color = zc.error,
                )
            }
            // 回合失败横幅（用户报「发消息一直没回复」的可见性修复）：电脑端模型请求
            // 失败（网络/provider 异常）时 phase=error 且无 assistantText 行——不提示
            // 就是静默空白，用户以为没发出去。横幅点名失败 + 重试动作。
            if (ui.turnFailed && !ui.isRunning) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ZcodeSpacing.screenPadding, vertical = ZcodeSpacing.xs)
                        .clip(ZcodeShape.inner)
                        .background(zc.error.copy(alpha = 0.12f))
                        .padding(horizontal = ZcodeSpacing.md, vertical = ZcodeSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "电脑端回复失败（模型请求出错），请重试或在电脑端查看详情",
                        style = ZcodeType.uiXs,
                        color = zc.error,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            // 权限模式 + 思考档位快捷行（官方 composer toolbar 语义：输入栏上方
            // 两个下拉 chip，真实作用于电脑端会话）
            ComposerConfigBar(viewModel = viewModel, onPickModel = { showModelPicker = true })
            ChatComposer(viewModel = viewModel, isRunning = ui.isRunning)
        }

        // 一键到底（用户要求）：不在底部就显示——有新消息时带计数，平时是「↓ 最新」。
        // 触控目标 48dp（docs/ui-design.md §7 触控规范），悬浮于输入栏上方。
        if (!atBottom && ui.rows.isNotEmpty()) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 72.dp)
                    .jellyClickable(
                        onClick = {
                            scope.launch {
                                // 修复依据（用户报「点了不到底部」）：列表头部有 history_header
                                // 项，行索引整体 +1，最后一行的 index 是 rows.size 而非
                                // rows.size-1——旧落点差一行，atBottom 阈值判定也跟着错位。
                                listState.scrollToItem(ui.rows.size)
                                pendingCount = 0
                            }
                        },
                        onClickLabel = "回到底部",
                    )
                    .background(zc.card, ZcodeShape.input)
                    .padding(horizontal = ZcodeSpacing.md, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("↓", style = ZcodeType.uiSm, color = zc.text)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (pendingCount > 0) "$pendingCount 条新消息" else "最新",
                        style = ZcodeType.uiSm,
                        color = zc.text,
                    )
                }
            }
        }
    }
}

/**
 * 会话顶栏：返回 / 会话标题 + 连接态 / 看电脑。
 *
 * 触控重设计（用户报「顶栏很难点到」+ 官方移动端参考）：TextButton 的触达区只有
 * 文字本身（约 32×20dp），远低于 44dp 移动触控最低标准。改为 48dp 图标按钮
 * （Material3 IconButton 默认 48dp 触达），图标 + contentDescription 可访问性。
 * 标题居中占剩余宽度，状态副标题语义不变。
 */
@Composable
private fun ChatTopBar(
    title: String?,
    connState: ZcodeConnection.State,
    isRunning: Boolean,
    gitBranches: com.zai.zcode.core.protocol.v4.ZcodeProtocolV4.GitBranchList?,
    onBack: () -> Unit,
    onWatchScreen: () -> Unit,
    onPickModel: () -> Unit,
    onPickBranch: () -> Unit,
    onOpenSidePane: () -> Unit,
) {
    val zc = LocalZcodeColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = ZcodeSpacing.xs, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 返回：48dp 图标钮（箭头），触达区是文字钮的 3 倍
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "返回",
                tint = zc.text,
            )
        }
        // 标题区吃掉剩余宽度
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = ZcodeSpacing.xs),
        ) {
            Text(
                title ?: "…",
                style = ZcodeType.uiMd,
                color = if (title == null) zc.textTertiary else zc.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Git 分支行（官方 desktop 顶栏同位：当前分支名，点击弹切换菜单；
            // 非 git 仓库 isRepository=false 不显示）
            if (gitBranches?.isRepository == true && gitBranches.currentBranch != null) {
                Row(
                    Modifier
                        .clip(ZcodeShape.control)
                        .clickable(onClick = onPickBranch)
                        .padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.AccountTree,
                        contentDescription = null,
                        modifier = Modifier.size(11.dp),
                        tint = zc.textTertiary,
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        gitBranches.currentBranch!!,
                        style = ZcodeType.uiXs,
                        color = zc.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (connState !is ZcodeConnection.State.Ready) {
                Text(
                    stateLabel(connState),
                    style = ZcodeType.uiXs,
                    color = zc.attention,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (isRunning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveDot(ZcodeStatus.Running)
                    Spacer(Modifier.width(6.dp))
                    Text("运行中", style = ZcodeType.uiXs, color = zc.textTertiary)
                }
            }
        }
        // 模型选择入口（官方 mobile composer 语义）：48dp 图标钮
        IconButton(onClick = onPickModel) {
            Icon(
                Icons.Rounded.Memory,
                contentDescription = "选择模型",
                tint = zc.text,
            )
        }
        // 侧边栏：48dp 图标钮（用户需求：终端/审查收进侧边栏，点击从右侧滑出）
        IconButton(onClick = onOpenSidePane) {
            Icon(
                Icons.AutoMirrored.Rounded.List,
                contentDescription = "侧边面板（审查/终端）",
                tint = zc.text,
            )
        }
        // 看电脑：48dp 图标钮（显示器图标）
        IconButton(onClick = onWatchScreen) {
            Icon(
                Icons.Rounded.DesktopWindows,
                contentDescription = "看电脑",
                tint = zc.text,
            )
        }
    }
}

/**
 * 侧边栏（用户需求 2026-10-19）：点击顶栏图标后从右侧滑入的面板，列出
 * 「审查」「终端」两个入口（对齐官方 side pane 的 mobileOverlay 抽屉模式：
 * 手机上不占屏内空间，抽屉滑入滑出）。选中后关闭抽屉并打开对应内容。
 */
@Composable
private fun SidePane(
    viewModel: com.zai.zcode.feature.chat.ChatViewModel,
    visible: Boolean,
    onDismiss: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenReview: () -> Unit,
) {
    val zc = LocalZcodeColors.current
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.slideInHorizontally(
            animationSpec = androidx.compose.animation.core.tween(200),
        ) { it } + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutHorizontally(
            animationSpec = androidx.compose.animation.core.tween(180),
        ) { it } + androidx.compose.animation.fadeOut(),
        modifier = Modifier.fillMaxSize(),
    ) {
        // 全屏遮罩：点空白处关闭（抽屉语义）
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(onClick = onDismiss),
        ) {
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxWidth(0.62f)
                    .fillMaxHeight()
                    .background(zc.base)
                    .clickable(enabled = false) { } // 阻止穿透关闭
                    .padding(ZcodeSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(ZcodeSpacing.sm),
            ) {
                Text("打开标签页", style = ZcodeType.uiLg, color = zc.text)
                Text("选择要在面板中打开的标签。", style = ZcodeType.uiXs, color = zc.textTertiary)
                Spacer(Modifier.height(ZcodeSpacing.sm))
                SidePaneItem(
                    icon = { Icon(Icons.Rounded.RateReview, null, Modifier.size(20.dp), tint = zc.text) },
                    label = "审查",
                ) { onOpenReview() }
                SidePaneItem(
                    icon = { Icon(Icons.Rounded.Terminal, null, Modifier.size(20.dp), tint = zc.text) },
                    label = "终端",
                ) { onOpenTerminal() }
            }
        }
    }
}

/** 侧边栏条目：圆角卡 + 图标 + 文案（官方「打开标签页」选择器的移动端形态）。 */
@Composable
private fun SidePaneItem(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
) {
    val zc = LocalZcodeColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ZcodeShape.input)
            .background(zc.card)
            .clickable(onClick = onClick)
            .padding(horizontal = ZcodeSpacing.md, vertical = ZcodeSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(ZcodeSpacing.md))
        Text(label, style = ZcodeType.uiMd, color = zc.text)
    }
}

/**
 * 模型选择弹层：打开时拉取 model-selection.getView，点选后发 switchModelConfig。
 * 对齐官方 mobile 模型选择器（provider · model 两级扁平成一列，当前选中打勾）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerSheet(viewModel: ChatViewModel, onDismiss: () -> Unit) {
    val zc = LocalZcodeColors.current
    LaunchedEffect(Unit) { viewModel.openModelPicker() }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = ZcodeSpacing.screenPadding)) {
            Text("选择模型", style = ZcodeType.uiLg, color = zc.text)
            Spacer(Modifier.height(ZcodeSpacing.sm))
            when {
                viewModel.modelLoading -> Text(
                    "正在读取可用模型…",
                    style = ZcodeType.uiSm,
                    color = zc.textTertiary,
                    modifier = Modifier.padding(vertical = ZcodeSpacing.lg),
                )
                viewModel.modelOptions.isEmpty() -> Text(
                    "没有读到可用模型（电脑端 provider 未配置）",
                    style = ZcodeType.uiSm,
                    color = zc.textTertiary,
                    modifier = Modifier.padding(vertical = ZcodeSpacing.lg),
                )
                else -> androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(viewModel.modelOptions.size) { index ->
                        val option = viewModel.modelOptions[index]
                        val selected = viewModel.currentModel?.modelId == option.modelId &&
                            viewModel.currentModel?.providerId == option.providerId
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(ZcodeShape.control)
                                .then(
                                    if (selected) Modifier.background(zc.panel)
                                    else Modifier
                                )
                                .clickable {
                                    viewModel.selectModel(option)
                                    onDismiss()
                                }
                                .padding(horizontal = ZcodeSpacing.md, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                // 长模型名（provider · model）两行完整展示——
                                // 单行 Ellipsis 时用户看不到全名、也容易点不中目标项
                                Text(
                                    option.label,
                                    style = ZcodeType.uiMd,
                                    color = zc.text,
                                    maxLines = 2,
                                )
                            }
                            if (selected) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = "当前模型",
                                    modifier = Modifier.size(18.dp),
                                    tint = zc.running,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(ZcodeSpacing.lg))
        }
    }
}

@Composable
private fun ChatRowList(
    rows: List<ChatRepository.ChatRowKind>,
    listState: LazyListState,
    loadState: ChatLoadState,
    hasMoreHistory: Boolean,
    onRetry: () -> Unit,
    imageContext: Pair<String, ZcodeConnection>,
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(ZcodeSpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 顶部历史分页指示：到顶后显示加载态或"已到最早"
        if (rows.isNotEmpty() && loadState == ChatLoadState.Ready) {
            item(key = "history_header", contentType = "historyHeader") {
                Text(
                    if (hasMoreHistory) "上滑加载更早消息…" else "已经是最早的消息了",
                    style = ZcodeType.uiXs,
                    color = LocalZcodeColors.current.textTertiary,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        items(
            items = rows,
            key = { it.rowId },
            // 修复依据（v0.2.17）：不声明 contentType 时 LazyColumn 把所有行当同一类，
            // 回收池里「工具卡」和「整段 Markdown」互相错配，滚出屏再滚回来每一行都要
            // 重新测量与重建 Composition —— 快速滑动时的掉帧主要来自这里。
            contentType = { row -> contentTypeOf(row) },
        ) { row ->
            CompositionLocalProvider(LocalChatImageContext provides imageContext) {
                ChatRowView(row, viewModel)
            }
        }
        // 空列表不再一律「加载会话…」——零行有两种完全不同的含义。
        if (rows.isEmpty()) {
            item(key = "empty_state", contentType = "emptyState") {
                ChatEmptyState(loadState = loadState, onRetry = onRetry)
            }
        }
    }
}

/**
 * 会话内容区的空态：**在等** / **读不出来** / **这个会话真的还没有内容** 三件事必须分开说。
 *
 * 修复依据（真机 v0.2.20「会话页一直转圈」）：原先零行一律渲染「加载会话…」，而
 * `subscribeConversation` 依赖桌面 runtime（existing-only，桌面没起就读不出来），
 * 订阅失败后既不重试也不置态，于是「手机先开、电脑后开」这个最常见顺序下会话页
 * 永远停在转圈——用户既不知道该等，也不知道该去电脑上做什么。列表页 v0.2.19 已经
 * 把这三态分开表达，会话页必须跟上同一套语义。
 */
@Composable
private fun ChatEmptyState(loadState: ChatLoadState, onRetry: () -> Unit) {
    val zc = LocalZcodeColors.current
    when (loadState) {
        ChatLoadState.Loading -> Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = ZcodeSpacing.screenPadding, vertical = ZcodeSpacing.lg),
        ) {
            // 骨架而不是一行文字：内容形状在加载时就定下来，首帧到达不发生高度跳变。
            repeat(3) { i ->
                Row(Modifier.fillMaxWidth()) {
                    if (i % 2 == 1) Spacer(Modifier.weight(1f))
                    Column(Modifier.weight(if (i % 2 == 1) 0.86f else 0.94f)) {
                        Box(
                            Modifier
                                .fillMaxWidth(if (i == 1) 0.62f else 0.46f)
                                .height(11.dp)
                                .background(zc.input, RoundedCornerShape(6.dp)),
                        )
                        Spacer(Modifier.height(7.dp))
                        Box(
                            Modifier
                                .fillMaxWidth(if (i == 2) 0.78f else 0.9f)
                                .height(11.dp)
                                .background(zc.input, RoundedCornerShape(6.dp)),
                        )
                    }
                }
                Spacer(Modifier.height(ZcodeSpacing.lg))
            }
        }

        ChatLoadState.RuntimeUnavailable -> EmptyState(
            title = "这个会话还没读出来",
            description = "电脑端的这个工作区还没有运行。在电脑上打开它，这里会自动出现",
            modifier = Modifier.fillMaxWidth().padding(top = ZcodeSpacing.xxl),
            action = { TextButton(onClick = onRetry) { Text("立即重试") } },
        )

        ChatLoadState.Error -> EmptyState(
            title = "读取会话失败",
            description = "连接正常但内容没读出来，重试通常可恢复",
            modifier = Modifier.fillMaxWidth().padding(top = ZcodeSpacing.xxl),
            action = { TextButton(onClick = onRetry) { Text("重试") } },
        )

        ChatLoadState.Ready -> EmptyState(
            title = "还没有消息",
            description = "在下面写下第一条指令",
            modifier = Modifier.fillMaxWidth().padding(top = ZcodeSpacing.xxl),
        )
    }
}

/**
 * 行 → 复用池标识。同一类型的行才能互相复用已组合的内容，
 * 因此粒度必须到行类型而非统一一个值。
 */
private fun contentTypeOf(row: ChatRepository.ChatRowKind): String = when (row) {
    is ChatRepository.ChatRowKind.AssistantText -> "assistantText"
    is ChatRepository.ChatRowKind.UserInput -> "userInput"
    is ChatRepository.ChatRowKind.Reasoning -> "reasoning"
    is ChatRepository.ChatRowKind.ToolCall -> "toolCall"
    is ChatRepository.ChatRowKind.TurnHeader -> "turnHeader"
    is ChatRepository.ChatRowKind.Subagent -> "subagent"
    is ChatRepository.ChatRowKind.Artifact -> "artifact"
    is ChatRepository.ChatRowKind.HookInvocation -> "hookInvocation"
    is ChatRepository.ChatRowKind.TimelineMarker -> "timelineMarker"
    is ChatRepository.ChatRowKind.Unknown -> "unknown"
}

@Composable
private fun JumpToLatestButton(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // 已被「一键到底」统一按钮取代（不在底部即显示），保留占位避免历史引用断裂。
}

/**
 * 输入栏（spec §5.3 状态机）：
 * - 运行中且无文字 → ■ 停止
 * - 其余 → ↑ 发送（有文字才可点）
 *
 * 不锁输入：等待审批 / 运行中都允许继续打字，发送即排队（是不会冻结用户手的设计，
 * 与「先想好再一次性发」相反——远程操控场景里用户常常已经在打下一条指令）。
 *
 * 修复依据（真机 v0.2.22）：形状/配色改走 [ZcodeComposer]，与新建会话页共用同一份
 * 组件。用户从草稿页发出第一条消息后原地进入本页，输入框若换了圆角与按钮位置，
 * 这一瞬间会被读成"跳到了另一个应用"。
 */
@Composable
private fun ChatComposer(viewModel: ChatViewModel, isRunning: Boolean) {
    val zc = LocalZcodeColors.current
    val context = androidx.compose.ui.platform.LocalContext.current
    // 附件选择（官方 attachmentPut 上传链路的移动端入口）：系统文件选择器 → 上传
    val pickFile = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 读字节 + 猜 mime，交 ViewModel 走 v4 上传事务。
        // 文件名从 contentResolver 查（OpenableColumns.DISPLAY_NAME）——GetContent 的
        // uri.lastPathSegment 常是纯数字内容 ID，不是真实文件名。
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return@rememberLauncherForActivityResult
        val name = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "attachment"
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        viewModel.uploadAttachment(name, mime, bytes) { }
    }
    // `/` 触发技能面板（官方 SlashCommandPlugin 语义：行首 / + 无空白 query）。
    // 选中后把草稿替换为 "/name "，随消息提交由电脑端 CLI 展开执行。
    val query = viewModel.draft
    val showSlashPanel = query.startsWith("/") && !query.contains(' ') && query.length >= 1
    LaunchedEffect(showSlashPanel) {
        if (showSlashPanel) {
            // 命令目录走 workspace-config 推送流（官方数据源，零冷启动）；
            // 技能目录仍懒加载 getSkillReferenceCatalog（可能拉起 CLI，慢）
            viewModel.subscribeWorkspaceConfig()
            viewModel.loadCatalog()
        }
    }
    Column(Modifier.fillMaxWidth()) {
        // 待上传附件 chips（官方 composer 的附件缩略行）
        viewModel.uploadError?.let {
            Text(
                it,
                style = ZcodeType.uiXs,
                color = zc.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.dismissUploadError() }
                    .padding(bottom = 4.dp),
            )
        }
        if (viewModel.pendingAttachments.isNotEmpty() || viewModel.uploadingAttachment) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(ZcodeSpacing.xs),
            ) {
                viewModel.uploadingAttachment.takeIf { it }?.let {
                    Box(
                        Modifier
                            .clip(ZcodeShape.input)
                            .background(zc.card)
                            .padding(horizontal = ZcodeSpacing.sm, vertical = 4.dp),
                    ) {
                        Text("上传中…", style = ZcodeType.uiXs, color = zc.textTertiary)
                    }
                }
                viewModel.pendingAttachments.forEach { att ->
                    Row(
                        Modifier
                            .clip(ZcodeShape.input)
                            .background(zc.card)
                            .clickable { viewModel.removeAttachment(att.ref) }
                            .padding(horizontal = ZcodeSpacing.sm, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            att.fileName,
                            style = ZcodeType.uiXs,
                            color = zc.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 120.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("×", style = ZcodeType.uiXs, color = zc.textTertiary)
                    }
                }
            }
        }
        Box {
            ZcodeComposer(
                value = viewModel.draft,
                onValueChange = viewModel::onDraftChange,
                onSend = viewModel::sendWithAttachments,
                // 运行中且用户还没打下一条 → 给停止；一旦开始打字，用户意图就变成"再说一句"
                onStop = if (isRunning && viewModel.draft.isBlank()) viewModel::stop else null,
                sendEnabled = (viewModel.draft.isNotBlank() || viewModel.pendingAttachments.isNotEmpty()) && !viewModel.sending,
                leadingAction = { AttachButton(onClick = { pickFile.launch("*/*") }) },
            )
            if (showSlashPanel) {
                // 面板悬浮在输入框上方（Box 内 BottomCenter 对齐，官方 MentionPanel
                // 也是 portal 到输入区上方）
                SlashSuggestionPanel(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    query = query.substring(1),
                    items = viewModel.catalogItems,
                    loading = viewModel.catalogLoading,
                    error = viewModel.catalogError,
                    onPick = { item ->
                        viewModel.onDraftChange("/${item.name} ")
                    },
                    onDismiss = { viewModel.onDraftChange("") },
                )
            }
        }
    }
}

/**
 * `/` 技能建议面板（官方 slashCommandPanelSections 的移动端形态）：
 * skills/plugins 目录按 query 过滤，输入框上方浮动显示，点击插入。
 */
@Composable
private fun SlashSuggestionPanel(
    modifier: Modifier = Modifier,
    query: String,
    items: List<ChatViewModel.CatalogItem>,
    loading: Boolean,
    error: String?,
    onPick: (ChatViewModel.CatalogItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val zc = LocalZcodeColors.current
    // 官方 MentionPanel 不限量、面板整体可滚；这里留量级上限防极端目录撑爆
    val filtered = remember(query, items) {
        items.filter { it.name.contains(query, ignoreCase = true) }.take(40)
    }
    if (filtered.isEmpty() && !loading && error == null) return
    Column(
        modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .clip(ZcodeShape.card)
            .background(zc.base)
            // 修复依据（用户报「不能上下滑、无法取消」）：命令+技能目录可能 20+ 条，
            // 固定高度内必须可滚动；高度上限 320dp 防遮满输入区。
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState())
            .padding(vertical = ZcodeSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        if (loading && filtered.isEmpty()) {
            Text(
                "加载技能目录…",
                style = ZcodeType.uiXs,
                color = zc.textTertiary,
                modifier = Modifier.padding(horizontal = ZcodeSpacing.md, vertical = ZcodeSpacing.sm),
            )
        }
        // 失败/空目录可见（此前「闪一下没内容」失败与空目录不可区分）
        error?.let {
            Text(
                it,
                style = ZcodeType.uiXs,
                color = zc.attention,
                modifier = Modifier.padding(horizontal = ZcodeSpacing.md, vertical = ZcodeSpacing.sm),
            )
        }
        filtered.forEach { item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(item) }
                    .padding(horizontal = ZcodeSpacing.md, vertical = ZcodeSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "/${item.label}",
                        style = ZcodeType.uiSm,
                        color = zc.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.description.isNotBlank()) {
                        Text(
                            item.description,
                            style = ZcodeType.uiXs,
                            color = zc.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        // 显式收起：点「收起」清空触发器（/ 前缀没了，面板自然消失）——用户报
        // 「怎么取消这个卡片」时只能靠退格删除，手势不明。
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onDismiss)
                .padding(horizontal = ZcodeSpacing.md, vertical = ZcodeSpacing.xs),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text("收起", style = ZcodeType.uiXs, color = zc.running)
        }
    }
}

/** composer 附件入口（官方 composer 的 + 按钮：系统文件选择器 → v4 上传事务）。 */
@Composable
private fun AttachButton(onClick: () -> Unit) {
    Icon(
        Icons.Rounded.AttachFile,
        contentDescription = "添加附件",
        modifier = Modifier
            .size(40.dp)
            .padding(8.dp)
            .clickable(onClick = onClick),
        tint = LocalZcodeColors.current.textTertiary,
    )
}

/**
 * 输入栏上方的配置行：权限模式 + 思考档位 + 模型入口。
 *
 * 对齐官方 mobile composer toolbar（V4ComposerToolbar）：模式下拉（计划模式/
 * 变更前确认/自主模式/完全访问）、思考档位下拉（低/中/高/极高/最高）、模型入口。
 * 全部走 v4 命令真实作用于电脑端会话（switchCollaborationMode / switchModelConfig）。
 */
@Composable
private fun ComposerConfigBar(viewModel: ChatViewModel, onPickModel: () -> Unit) {
    val zc = LocalZcodeColors.current
    var showModeMenu by remember { mutableStateOf(false) }
    var showThoughtMenu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = ZcodeSpacing.md, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(ZcodeSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 权限模式 chip
        Box {
            ConfigChip(
                icon = Icons.Rounded.Policy,
                text = viewModel.modeOptions.firstOrNull { it.mode == viewModel.currentMode }?.label
                    ?: "变更前确认",
                onClick = { showModeMenu = true },
            )
            DropdownMenu(expanded = showModeMenu, onDismissRequest = { showModeMenu = false }) {
                viewModel.modeOptions.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(option.label, style = ZcodeType.uiSm, color = zc.text)
                                Text(option.description, style = ZcodeType.uiXs, color = zc.textTertiary)
                            }
                        },
                        leadingIcon = {
                            if (viewModel.currentMode == option.mode) {
                                Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = zc.running)
                            }
                        },
                        onClick = {
                            viewModel.switchMode(option.mode)
                            showModeMenu = false
                        },
                    )
                }
            }
        }
        // 思考档位 chip
        Box {
            ConfigChip(
                icon = Icons.Rounded.Psychology,
                text = viewModel.currentThought?.let(viewModel::thoughtLabel) ?: "中",
                onClick = { showThoughtMenu = true },
            )
            DropdownMenu(expanded = showThoughtMenu, onDismissRequest = { showThoughtMenu = false }) {
                // 只显示当前模型支持的档位（服务端 optionSpecs；GLM 等不支持 xhigh
                // 的模型不再显示会被拒绝的选项——「选极高跳最高」的根因）
                val levels = viewModel.currentModel?.supportedThoughtLevels
                    ?.ifEmpty { viewModel.thoughtOptions }
                    ?: viewModel.thoughtOptions
                levels.forEach { level ->
                    DropdownMenuItem(
                        text = { Text(viewModel.thoughtLabel(level), style = ZcodeType.uiSm, color = zc.text) },
                        leadingIcon = {
                            if (viewModel.currentThought == level) {
                                Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = zc.running)
                            }
                        },
                        onClick = {
                            viewModel.switchThought(level)
                            showThoughtMenu = false
                        },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        // 模型入口（点击打开 ModelPickerSheet）：底部弹层滚动列表——DropdownMenu
        // 高度受限，长模型名（provider·model 两行）会被裁掉导致「选不了」。
        ConfigChip(
            icon = Icons.Rounded.Memory,
            text = viewModel.currentModel?.modelId?.substringAfterLast('/')?.take(18) ?: "模型",
            onClick = onPickModel,
        )
    }
}

@Composable
private fun ConfigChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    val zc = LocalZcodeColors.current
    Row(
        modifier
            .clip(ZcodeShape.control)
            .background(if (emphasized) zc.panel else zc.input)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = if (emphasized) zc.queued else zc.textSecondary,
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text,
            style = ZcodeType.uiXs,
            color = if (emphasized) zc.text else zc.textSecondary,
            maxLines = 1,
        )
        Spacer(Modifier.width(2.dp))
        Icon(
            Icons.Rounded.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = zc.textTertiary,
        )
    }
}

// ── 上下文用量条 / 子代理进度卡 / 队列卡 ──

/**
 * 上下文窗口用量条（usage.contextWindow / autoCompactThresholdTokens）。
 * 阈值配色：<80% 静默（tertiary），80-95% 琥珀（attention），>95% 红（error）——
 * 快满时提醒但不打扰：一行 4dp 细条 + 百分比，不放卡片。
 */
@Composable
private fun ContextUsageBar(ratio: Float) {
    val zc = LocalZcodeColors.current
    val color = when {
        ratio > 0.95f -> zc.error
        ratio > 0.80f -> zc.attention
        else -> zc.textTertiary
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = ZcodeSpacing.screenPadding, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(zc.input),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(ratio)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "上下文 ${(ratio * 100).toInt()}%",
            style = ZcodeType.uiXs,
            color = color,
        )
    }
}

/**
 * 子代理进度卡（state.subagents.running）：可折叠，默认收起为一行摘要——
 * 并行任务多时全展开会淹没对话流。
 */
@Composable
private fun SubagentsCard(subagents: List<ChatRepository.SubagentSummary>, endedTotal: Int) {
    val zc = LocalZcodeColors.current
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = ZcodeSpacing.screenPadding, vertical = 2.dp)
            .clip(ZcodeShape.inner)
            .background(zc.card)
            .padding(horizontal = ZcodeSpacing.md, vertical = ZcodeSpacing.sm),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveDot(
                if (subagents.any { it.status == "running" }) ZcodeStatus.Running else ZcodeStatus.Queued,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "${subagents.size} 个子代理运行中" + if (endedTotal > 0) " · 已完成 $endedTotal" else "",
                style = ZcodeType.uiSm,
                color = zc.text,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                modifier = Modifier.size(16.dp),
                tint = zc.textTertiary,
            )
        }
        if (expanded) {
            subagents.forEach { sub ->
                Row(
                    Modifier.fillMaxWidth().padding(top = ZcodeSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when (sub.status) {
                            "blocked" -> "⛔"
                            "waiting" -> "⏸"
                            else -> "▶"
                        },
                        style = ZcodeType.uiXs,
                        color = zc.textTertiary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            sub.title,
                            style = ZcodeType.uiSm,
                            color = zc.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        sub.summary?.let {
                            Text(
                                it,
                                style = ZcodeType.uiXs,
                                color = zc.textTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 队列卡（state.queue.items）：排队消息列表，每条可编辑/移到队尾/删除，
 * 顶部显示 autoDrain 状态与切换（停止后队列暂停时是恢复入口）。
 */
@Composable
private fun QueueCard(
    items: List<ChatRepository.QueueItem>,
    autoDrain: Boolean,
    onEdit: (String, String) -> Unit,
    onMoveToEnd: (String) -> Unit,
    onDelete: (String) -> Unit,
    onToggleAutoDrain: (Boolean) -> Unit,
) {
    val zc = LocalZcodeColors.current
    var editingId by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = ZcodeSpacing.screenPadding, vertical = 2.dp)
            .clip(ZcodeShape.inner)
            .background(zc.card)
            .padding(horizontal = ZcodeSpacing.md, vertical = ZcodeSpacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "队列 · ${items.size} 条",
                style = ZcodeType.uiSm,
                color = zc.text,
                modifier = Modifier.weight(1f),
            )
            // autoDrain 状态切换：停止后队列暂停，这里一键恢复
            Text(
                if (autoDrain) "自动消化中" else "已暂停 · 点恢复",
                style = ZcodeType.uiXs,
                color = if (autoDrain) zc.textTertiary else zc.attention,
                modifier = Modifier.clickable { onToggleAutoDrain(!autoDrain) },
            )
        }
        items.forEach { item ->
            Column(Modifier.fillMaxWidth().padding(top = ZcodeSpacing.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.text,
                        style = ZcodeType.uiSm,
                        color = zc.text,
                        maxLines = if (editingId == item.queueItemId) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "置底",
                        style = ZcodeType.uiXs,
                        color = zc.queued,
                        modifier = Modifier
                            .clickable { onMoveToEnd(item.queueItemId) }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                    Text(
                        "删除",
                        style = ZcodeType.uiXs,
                        color = zc.error,
                        modifier = Modifier
                            .clickable { onDelete(item.queueItemId) }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
                if (editingId == item.queueItemId) {
                    androidx.compose.material3.OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        trailingIcon = {
                            IconButton(onClick = {
                                if (editText.isNotBlank()) onEdit(item.queueItemId, editText.trim())
                                editingId = null
                            }) {
                                Icon(Icons.Rounded.Check, "保存", modifier = Modifier.size(18.dp), tint = zc.running)
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * Git 分支切换弹层（官方 useGitBranchSwitcher 的移动端形态）：
 * 当前分支打勾，点选其他分支即切换（switchBranch 成功后重拉刷新）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun BranchPickerSheet(viewModel: ChatViewModel, onDismiss: () -> Unit) {
    val zc = LocalZcodeColors.current
    val git = viewModel.gitBranches.collectAsState().value
    // 对齐官方 GitBranchSwitcher：搜索分支 + 创建并检出新分支 + Git 图谱入口
    var query by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }
    var showGraph by remember { mutableStateOf(false) }
    val graph by remember(viewModel.gitGraph.size) { derivedStateOf { viewModel.gitGraph } }

    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = ZcodeSpacing.screenPadding)) {
            if (showGraph) {
                // ── Git 图谱（官方 GitGraphDialog 的移动端简化：单列时间线）──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showGraph = false }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = zc.text)
                    }
                    Text("Git 图谱", style = ZcodeType.uiLg, color = zc.text)
                }
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                ) {
                    items(graph.size) { index ->
                        val commit = graph[index]
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .background(zc.running, CircleShape),
                                )
                                if (index < graph.size - 1) {
                                    Box(
                                        Modifier
                                            .width(2.dp)
                                            .height(28.dp)
                                            .background(zc.input),
                                    )
                                }
                            }
                            Spacer(Modifier.width(ZcodeSpacing.sm))
                            Column {
                                Text(
                                    commit.subject,
                                    style = ZcodeType.uiSm,
                                    color = zc.text,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    commit.refs.filter { it.second == "branch" }
                                        .joinToString(" ") { it.first }
                                        .ifEmpty { commit.hash.take(7) } + " · ${commit.authorName}",
                                    style = ZcodeType.uiXs,
                                    color = zc.textTertiary,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    if (viewModel.gitGraphLoading) {
                        item {
                            Row(Modifier.padding(ZcodeSpacing.md), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = zc.textTertiary)
                                Spacer(Modifier.width(ZcodeSpacing.sm))
                                Text("加载中…", style = ZcodeType.uiXs, color = zc.textTertiary)
                            }
                        }
                    }
                }
                LaunchedEffect(Unit) { viewModel.loadGitGraph(reset = true) }
            } else {
                Text("切换分支", style = ZcodeType.uiLg, color = zc.text)
                Spacer(Modifier.height(ZcodeSpacing.sm))
                // 搜索分支（官方分支选择器顶部搜索框）
                androidx.compose.material3.OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("搜索分支", style = ZcodeType.uiSm, color = zc.textTertiary) },
                    shape = ZcodeShape.input,
                )
                Spacer(Modifier.height(ZcodeSpacing.sm))
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val branches = git?.branches.orEmpty()
                        .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
                    items(branches.size) { index ->
                        val branch = branches[index]
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(ZcodeShape.control)
                                .then(if (branch.isCurrent) Modifier.background(zc.panel) else Modifier)
                                .clickable {
                                    if (!branch.isCurrent) viewModel.switchBranch(branch.name)
                                    onDismiss()
                                }
                                .padding(horizontal = ZcodeSpacing.md, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                branch.name,
                                style = ZcodeType.uiMd,
                                color = zc.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (branch.isCurrent) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = "当前分支",
                                    modifier = Modifier.size(18.dp),
                                    tint = zc.running,
                                )
                            }
                        }
                    }
                    if (branches.isEmpty() && query.isNotBlank()) {
                        item {
                            Text(
                                "没有匹配「$query」的分支",
                                style = ZcodeType.uiSm,
                                color = zc.textTertiary,
                                modifier = Modifier.padding(vertical = ZcodeSpacing.md),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(ZcodeSpacing.sm))
                // 创建并检出新分支（官方 footer 按钮）
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(ZcodeShape.control)
                        .clickable { showCreate = !showCreate }
                        .padding(horizontal = ZcodeSpacing.md, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp), tint = zc.textSecondary)
                    Spacer(Modifier.width(ZcodeSpacing.sm))
                    Text("创建并检出新分支...", style = ZcodeType.uiSm, color = zc.textSecondary)
                }
                if (showCreate) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.OutlinedTextField(
                            value = createName,
                            onValueChange = { createName = it; createError = null },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("新分支名", style = ZcodeType.uiSm, color = zc.textTertiary) },
                            shape = ZcodeShape.input,
                        )
                        Spacer(Modifier.width(ZcodeSpacing.sm))
                        androidx.compose.material3.TextButton(
                            enabled = createName.isNotBlank() && !creating,
                            onClick = {
                                creating = true
                                viewModel.createBranch(createName) { ok ->
                                    creating = false
                                    if (ok) onDismiss() else createError = "创建失败（分支名非法或已存在）"
                                }
                            },
                        ) {
                            Text("创建", color = if (createName.isNotBlank()) zc.running else zc.textTertiary)
                        }
                    }
                    createError?.let {
                        Text(it, style = ZcodeType.uiXs, color = zc.error)
                    }
                }
                Spacer(Modifier.height(ZcodeSpacing.xs))
                // Git 图谱入口（官方分支选择器 footer 菜单）
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(ZcodeShape.control)
                        .clickable { showGraph = true }
                        .padding(horizontal = ZcodeSpacing.md, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.AccountTree, null, Modifier.size(18.dp), tint = zc.textSecondary)
                    Spacer(Modifier.width(ZcodeSpacing.sm))
                    Text("Git 图谱", style = ZcodeType.uiSm, color = zc.textSecondary)
                }
            }
            Spacer(Modifier.height(ZcodeSpacing.lg))
        }
    }
}

/**
 * 终端弹层（官方切换面板「终端」tab 的移动端形态，第一阶段）：
 * - 输出区：等宽 TextView 自动滚动（ANSI 转义剥离后纯文本）；
 * - 命令行：底部输入，回车即发送（terminalWrite 带 \r）。
 * 完整 xterm 渲染（颜色/光标控制）为后续迭代。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TerminalSheet(viewModel: ChatViewModel, onDismiss: () -> Unit) {
    val zc = LocalZcodeColors.current
    var cmd by remember { mutableStateOf("") }
    val output = viewModel.terminalOutput
    // 输出按行拆分（LazyColumn 行级复用，长输出不卡）；key 用行号
    val lines = remember(output) { output.split("\n") }
    val listState = rememberLazyListState()
    // 用户上滑看历史时暂停自动贴底（回到底部恢复跟随）
    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            last == null || last.index >= listState.layoutInfo.totalItemsCount - 2
        }
    }

    // 进入时创建 PTY（LaunchedEffect 只跑一次）
    LaunchedEffect(Unit) { viewModel.openTerminal() }
    // 输出增长且用户在底部时贴底
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty() && atBottom) {
            listState.scrollToItem(lines.size - 1)
        }
    }

    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = ZcodeSpacing.screenPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("终端", style = ZcodeType.uiLg, color = zc.text)
                Spacer(Modifier.weight(1f))
                Text(
                    if (viewModel.terminalReady) "已连接电脑端 shell" else "连接中…",
                    style = ZcodeType.uiXs,
                    color = if (viewModel.terminalReady) zc.running else zc.textTertiary,
                )
                // 关闭面板只隐藏（shell 保留、重开接着用——对齐官方 detach 不 dispose）；
                // 这里提供显式销毁：真正结束电脑端 shell 进程。
                androidx.compose.material3.TextButton(onClick = {
                    viewModel.destroyTerminal()
                    onDismiss()
                }) {
                    Text("销毁", style = ZcodeType.uiXs, color = zc.error)
                }
            }
            Spacer(Modifier.height(ZcodeSpacing.sm))
            // 输出区：LazyColumn 可滚动（用户报「clear 后看不到内容/不能往下滑」——
            // 原 Column+Text 超过 420dp 上限即截断）。黑色终端底（终端惯例）+ 等宽浅字。
            if (lines.isEmpty() || (lines.size == 1 && lines[0].isEmpty())) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp, max = 420.dp)
                        .clip(ZcodeShape.inner)
                        .background(Color(0xFF16181D))
                        .padding(ZcodeSpacing.sm),
                ) {
                    if (viewModel.terminalError != null) {
                        Text(
                            "终端创建失败：" + (viewModel.terminalError ?: "") +
                                "\n请确认电脑端 ZCode 已打开且版本支持终端，关闭本面板重试。",
                            style = ZcodeType.mono.copy(fontFamily = FontFamily.Monospace),
                            color = Color(0xFFE06C75),
                        )
                    } else {
                        Text(
                            if (viewModel.terminalReady) "（终端就绪，下方输入命令）" else "正在电脑端启动 shell…",
                            style = ZcodeType.mono.copy(fontFamily = FontFamily.Monospace),
                            color = Color(0xFF9AA0AA),
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp, max = 420.dp)
                        .clip(ZcodeShape.inner)
                        .background(Color(0xFF16181D)),
                    contentPadding = PaddingValues(ZcodeSpacing.sm),
                ) {
                    items(lines.size) { i ->
                        Text(
                            lines[i],
                            style = ZcodeType.mono.copy(fontFamily = FontFamily.Monospace),
                            color = Color(0xFFD7DAE0),
                        )
                    }
                }
            }
            Spacer(Modifier.height(ZcodeSpacing.sm))
            // 命令行
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.OutlinedTextField(
                    value = cmd,
                    onValueChange = { cmd = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("输入命令…", style = ZcodeType.uiSm, color = zc.textTertiary) },
                    textStyle = ZcodeType.mono.copy(fontFamily = FontFamily.Monospace),
                    shape = ZcodeShape.input,
                )
                Spacer(Modifier.width(ZcodeSpacing.sm))
                IconButton(
                    onClick = {
                        if (cmd.isNotBlank()) {
                            viewModel.sendTerminalCommand(cmd)
                            cmd = ""
                        }
                    },
                    enabled = cmd.isNotBlank() && viewModel.terminalReady,
                ) {
                    Icon(Icons.Rounded.ArrowUpward, "执行", tint = zc.text)
                }
            }
            Spacer(Modifier.height(ZcodeSpacing.lg))
        }
    }
}

