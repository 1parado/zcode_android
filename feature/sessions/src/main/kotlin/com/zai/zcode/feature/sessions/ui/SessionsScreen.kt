package com.zai.zcode.feature.sessions.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import com.zai.zcode.core.design.LocalZcodeColors
import com.zai.zcode.core.design.ZcodeShape
import com.zai.zcode.core.design.ZcodeSpacing
import com.zai.zcode.core.design.ZcodeType
import com.zai.zcode.core.design.components.DeviceChip
import com.zai.zcode.core.design.components.EmptyState
import com.zai.zcode.core.network.stateLabel
import com.zai.zcode.feature.sessions.SessionsOrdering
import com.zai.zcode.feature.sessions.SessionsRepository
import com.zai.zcode.feature.sessions.SessionsViewModel
import com.zai.zcode.feature.sessions.SessionsListState
import java.time.LocalDate

/**
 * 任务 Home（docs/ui-design.md Phase B）：设备 Chip + 筛选行 + 分组任务卡列表。
 * 列表来自 sessions-index 订阅 + 本地 pinned/seen 合并；空态给引导文案。
 */
@Composable
fun SessionsScreen(
    onOpenSession: (sessionId: String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: SessionsViewModel = hiltViewModel(),
) {
    val sessions by viewModel.sessions.collectAsState()
    val connState by viewModel.connectionState.collectAsState()
    val workspaces by viewModel.workspaces.collectAsState()
    val currentWorkspace by viewModel.currentWorkspace.collectAsState()
    var showWorkspacePicker by remember { mutableStateOf(false) }
    val creating = viewModel.creating
    val createError = viewModel.createError
    val zc = LocalZcodeColors.current

    // 修复依据（用户截图：草稿页"叠"在列表页上——顶栏/工作区卡/新建按钮同时可见，
    // 关闭按钮看起来像返回但列表还在）：草稿页必须**整屏替换**列表页。
    // 此前 if 分支在 Column 中部，return@Column 只跳过剩余内容、顶栏照常渲染。
    if (viewModel.draftSession) {
        NewSessionScreen(
            creating = creating,
            error = createError,
            onDismissError = viewModel::dismissCreateError,
            onSubmit = { text ->
                viewModel.startSession(
                    text,
                    onOpenSession,
                    providerId = viewModel.selectedModel?.providerId,
                    modelId = viewModel.selectedModel?.modelId,
                    thought = viewModel.selectedThought,
                    mode = viewModel.selectedMode,
                )
            },
            onCancel = viewModel::closeDraftSession,
            modelOptions = viewModel.draftModelOptions,
            modelLoading = viewModel.draftModelLoading,
            selectedModel = viewModel.selectedModel,
            onSelectModel = { viewModel.selectedModel = it },
            selectedMode = viewModel.selectedMode,
            onSelectMode = { viewModel.selectedMode = it },
            selectedThought = viewModel.selectedThought,
            onSelectThought = { viewModel.selectedThought = it },
            modeOptions = viewModel.modeOptions,
            thoughtOptions = viewModel.thoughtOptions,
            thoughtLabel = viewModel::thoughtLabel,
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        // 顶栏：标题 + 设置（触控重设计：DeviceChip 从顶栏挤窄的位置移出，改为
        // 下方整行工作区选择卡——用户报「工作区按钮太不好点」，被标题挤压后
        // 触达区不足 40dp；整行卡片点击面积是原来的 ~8 倍，官方移动端同样是
        // 独立的 workspace 行）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = ZcodeSpacing.screenPadding, vertical = ZcodeSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("任务", style = ZcodeType.uiXl, color = zc.text)
                Spacer(Modifier.height(2.dp))
                Text(
                    stateLabel(connState),
                    style = ZcodeType.uiXs,
                    color = if (connState is com.zai.zcode.core.network.ZcodeConnection.State.Ready) {
                        zc.textTertiary
                    } else {
                        zc.attention
                    },
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Rounded.Settings, contentDescription = "设置", tint = zc.textSecondary)
            }
        }

        // 工作区选择卡：整行可点，48dp 高，箭头示意可换。
        // 与下方筛选行留一个 lg 间距（用户截图反馈两者贴在一起）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZcodeSpacing.screenPadding)
                .padding(top = ZcodeSpacing.xs)
                .clip(ZcodeShape.card)
                .background(zc.card)
                .clickable { showWorkspacePicker = true }
                .padding(horizontal = ZcodeSpacing.md, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.DesktopWindows,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = zc.textSecondary,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("当前工作区", style = ZcodeType.uiXs, color = zc.textTertiary)
                Text(
                    currentWorkspace?.label
                        ?: currentWorkspace?.path?.substringAfterLast('\\')?.substringAfterLast('/')
                        ?: "这台电脑",
                    style = ZcodeType.uiMd,
                    color = zc.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = "切换工作区",
                modifier = Modifier.size(20.dp),
                tint = zc.textTertiary,
            )
        }

        if (showWorkspacePicker) {
            WorkspacePickerSheet(
                workspaces = workspaces,
                currentPath = currentWorkspace?.path,
                onSelect = { ws ->
                    showWorkspacePicker = false
                    viewModel.selectWorkspace(ws)
                },
                onDismiss = { showWorkspacePicker = false },
            )
        }

        // 官方 sidebar 无状态筛选 tabs（核对 workspace-grouped-tasks + 用户反馈
        // 「全部/需要你/运行中没必要」）：需要你/运行中由排序与行内指示表达。
        // 新建按钮右对齐单独一行。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZcodeSpacing.screenPadding)
                .padding(top = ZcodeSpacing.lg, bottom = ZcodeSpacing.xs),
            horizontalArrangement = Arrangement.End,
        ) {
            // 修复依据（真机 v0.2.19「创建会话还是不行」）：「+」不再直接建空会话。
            // 空会话是服务端 draft（纯内存、不落盘、不进任务索引），建了也看不见；
            // 改为进入草稿页，由用户首条消息真正创建。
            IconButton(onClick = { viewModel.openDraftSession() }) {
                Icon(Icons.Rounded.Add, contentDescription = "新建会话", tint = zc.text)
            }
        }

        createError?.let { msg ->
            Text(
                msg,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = viewModel::dismissCreateError)
                    .padding(horizontal = ZcodeSpacing.screenPadding, vertical = ZcodeSpacing.sm),
                style = ZcodeType.uiXs,
                color = zc.error,
            )
        }

        // 修复依据（真机 v0.2.17「看不到工作区下已有的会话」）：列表是否空不再单独决定渲染。
        // EmptyState("暂无任务") 把三种完全不同的事实混成一张脸——runtime 没起来在等、
        // 订阅失败、真的没会话。先看加载态，再看列表内容。
        val listState by viewModel.listState.collectAsState()
        if (!listStateLoaded(listState, sessions)) {
            SessionListPlaceholder(
                listState = listState,
                connected = connState is com.zai.zcode.core.network.ZcodeConnection.State.Ready,
                onRetry = viewModel::retrySubscribe,
                onCreate = { viewModel.openDraftSession() },
                creating = creating,
            )
        } else {
            val nowEpochDay = LocalDate.now().toEpochDay()
            val sections = SessionsOrdering.groupByRecency(sessions, nowEpochDay)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = ZcodeSpacing.screenPadding,
                    vertical = ZcodeSpacing.md,
                ),
                verticalArrangement = Arrangement.spacedBy(ZcodeSpacing.sm),
            ) {
                sections.forEach { section ->
                    item(key = "header_${section.title}", contentType = "sectionHeader") {
                        Text(
                            section.title,
                            style = ZcodeType.uiXs,
                            color = zc.textTertiary,
                            modifier = Modifier.padding(top = ZcodeSpacing.sm, bottom = ZcodeSpacing.xs),
                        )
                    }
                    items(
                        section.items.size,
                        key = { i -> section.items[i].sessionId },
                        contentType = { "taskCard" },
                    ) { i ->
                        val item = section.items[i]
                        // 长按菜单（官方 parity）：置顶/归档/删除。showMenu 按卡独立。
                        var showMenu by remember(item.sessionId) { mutableStateOf(false) }
                        var showDeleteConfirm by remember(item.sessionId) { mutableStateOf(false) }
                        TaskCard(
                            item = item,
                            relativeTime = SessionsOrdering.relativeTime(item.lastActivityAt),
                            absoluteTime = item.lastActivityAt?.let { formatAbsoluteTime(it) },
                            onClick = {
                                viewModel.markSeen(item.sessionId)
                                onOpenSession(item.sessionId)
                            },
                            onLongClick = { showMenu = true },
                            showMenu = showMenu,
                            onDismissMenu = { showMenu = false },
                            onArchive = { viewModel.archiveTask(item.sessionId) },
                            onDelete = { showDeleteConfirm = true },
                            onTogglePin = { viewModel.togglePinned(item.sessionId, item.pinned) },
                        )
                        if (showDeleteConfirm) {
                            androidx.compose.material3.AlertDialog(
                                onDismissRequest = { showDeleteConfirm = false },
                                title = { Text("删除这个会话？", style = ZcodeType.uiMd) },
                                text = { Text(
                                    "「" + item.title + "」将被永久删除，电脑端的会话记录一并移除。",
                                    style = ZcodeType.uiSm,
                                ) },
                                confirmButton = {
                                    androidx.compose.material3.TextButton(onClick = {
                                        showDeleteConfirm = false
                                        viewModel.deleteTask(item.sessionId)
                                    }) { Text("删除", color = zc.error) }
                                },
                                dismissButton = {
                                    androidx.compose.material3.TextButton(onClick = { showDeleteConfirm = false }) {
                                        Text("取消")
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 列表区域什么时候渲染内容行：[SessionsListState] 优先，空列表只是其中一种结果。
 *
 * Ready 一定渲染（空则 EmptyState 引导新建）；RuntimeUnavailable / Error 在还留有旧
 * 快照时照样渲染列表行——旧数据比一张错误页有用，用户在屏幕上能继续看到昨天的任务。
 */
private fun listStateLoaded(state: SessionsListState, sessions: List<SessionsRepository.SessionItem>): Boolean =
    state == SessionsListState.Ready || (sessions.isNotEmpty() && state != SessionsListState.Loading)

/**
 * 列表占位：Loading 给骨架卡，RuntimeUnavailable / Error 给可读说明 + 重试。
 *
 * 修复依据（真机 v0.2.17）：此前 runtime 未启动与"真的没会话"共用一张「暂无任务」卡，
 * 用户在手机上看到的是"历史会话全没了"，实际是"电脑端这个工作区还没起 runtime"——
 * 文案必须说出可执行的下一步（在电脑端打开该工作区 / 点重试），否则用户只能卸载。
 */
@Composable
private fun SessionListPlaceholder(
    listState: SessionsListState,
    connected: Boolean,
    onRetry: () -> Unit,
    onCreate: () -> Unit,
    creating: Boolean,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            // 连接本身还没通：这时 Loading 说的是"正在连电脑"，不是"正在读列表"。
            !connected -> EmptyState(
                title = "等待连接…",
                description = "连接到电脑后这里会显示任务",
            )

            listState == SessionsListState.Loading -> SessionSkeleton()

            listState == SessionsListState.RuntimeUnavailable -> EmptyState(
                title = "这个工作区还没有运行",
                description = "在电脑上打开 ZCode 并进入该工作区，列表会自动出现",
                action = {
                    TextButton(onClick = onRetry) { Text("立即重试") }
                },
            )

            listState == SessionsListState.Error -> EmptyState(
                title = "读取任务列表失败",
                description = "连接正常但列表没读出来，重试通常可恢复",
                action = {
                    TextButton(onClick = onRetry) { Text("重试") }
                },
            )

            else -> EmptyState(
                title = "暂无任务",
                description = "创建一个新会话开始",
                action = {
                    TextButton(onClick = onCreate) {
                        Text(if (creating) "创建中…" else "新会话")
                    }
                },
            )
        }
    }
}

/**
 * 骨架屏：三张占位卡而不是一行「加载中…」。
 * 修复依据（spec §5.2 骨架屏）：列表形状在加载时就定下来，内容到达时不发生高度跳变，
 * 用户不会看到"文字突然插进来"的抖动。
 */
@Composable
private fun SessionSkeleton() {
    val zc = LocalZcodeColors.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = ZcodeSpacing.screenPadding, vertical = ZcodeSpacing.md),
        verticalArrangement = Arrangement.spacedBy(ZcodeSpacing.sm),
    ) {
        repeat(3) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(zc.card, ZcodeShape.card)
                    .padding(ZcodeSpacing.md),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(0.5f)
                        .height(12.dp)
                        .background(zc.input, RoundedCornerShape(6.dp)),
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.82f)
                        .height(10.dp)
                        .background(zc.input, RoundedCornerShape(5.dp)),
                )
            }
        }
    }
}

/**
 * Workspace 选择器（用户报障 v0.2.11：Home 页只有写死的「这台电脑」，无法查看
 * 其他 workspace 下的真实会话）。ModalBottomSheet 列出 bootstrap/serverInfo 的
 * 全部 workspace，点选交给 [SessionsViewModel.selectWorkspace]（relay 模式重开 bridge）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun WorkspacePickerSheet(
    workspaces: List<com.zai.zcode.core.network.ServerInfo.Workspace>,
    currentPath: String?,
    onSelect: (com.zai.zcode.core.network.ServerInfo.Workspace) -> Unit,
    onDismiss: () -> Unit,
) {
    val zc = LocalZcodeColors.current
    // 搜索（用户要求）：53 个工作区滚动找太慢，按名称/路径前缀过滤
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, workspaces) {
        if (query.isBlank()) workspaces
        else workspaces.filter { ws ->
            ws.label?.contains(query, ignoreCase = true) == true ||
                ws.path.contains(query, ignoreCase = true)
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = zc.panel,
        shape = ZcodeShape.sheet,
    ) {
        Text(
            "选择工作区",
            style = ZcodeType.uiLg,
            color = zc.text,
            modifier = Modifier.padding(horizontal = ZcodeSpacing.screenPadding, vertical = ZcodeSpacing.sm),
        )
        // 搜索框（工作区 >5 个时显示）。
        // 美化对齐：全 App 输入语言是无边框胶囊（ZcodeShape.input 20dp 圆角 +
        // zc.input 底色），OutlinedTextField 的描边方框是表单语言的残留，与
        // 会话页输入框、配置 chip 都不一致。改用 BasicLineTextField 自绘胶囊。
        if (workspaces.size > 5) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ZcodeSpacing.screenPadding, vertical = ZcodeSpacing.sm)
                    .clip(ZcodeShape.input)
                    .background(zc.input)
                    .padding(horizontal = ZcodeSpacing.md, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = "搜索",
                    modifier = Modifier.size(18.dp),
                    tint = zc.textTertiary,
                )
                Spacer(Modifier.width(8.dp))
                androidx.compose.foundation.text.BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = ZcodeType.uiSm.copy(color = zc.text),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(zc.text),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text(
                                "搜索工作区名称或路径…",
                                style = ZcodeType.uiSm,
                                color = zc.textTertiary,
                            )
                        }
                        inner()
                    },
                )
                if (query.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { query = "" }, modifier = Modifier.size(24.dp)) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "清除",
                            modifier = Modifier.size(16.dp),
                            tint = zc.textTertiary,
                        )
                    }
                }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = ZcodeSpacing.xxl),
        ) {
            if (workspaces.isEmpty()) {
                item {
                    Text(
                        "暂无可用工作区",
                        style = ZcodeType.uiSm,
                        color = zc.textTertiary,
                        modifier = Modifier.padding(ZcodeSpacing.screenPadding),
                    )
                }
            } else if (filtered.isEmpty()) {
                item {
                    Text(
                        "没有匹配「$query」的工作区",
                        style = ZcodeType.uiSm,
                        color = zc.textTertiary,
                        modifier = Modifier.padding(ZcodeSpacing.screenPadding),
                    )
                }
            }
            itemsIndexed(filtered, key = { _, ws -> ws.path }) { _, ws ->
                val selected = ws.path == currentPath
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(ws) }
                        .background(if (selected) zc.card else Color.Transparent)
                        .padding(horizontal = ZcodeSpacing.screenPadding, vertical = ZcodeSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            ws.label ?: ws.path.substringAfterLast('\\').substringAfterLast('/'),
                            style = ZcodeType.uiMd,
                            color = if (selected) zc.text else zc.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            ws.path,
                            style = ZcodeType.uiXs,
                            color = zc.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (selected) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = "当前工作区",
                            tint = zc.running,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}
