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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import com.zai.zcode.core.design.LocalZcodeColors
import com.zai.zcode.core.design.ZcodeShape
import com.zai.zcode.core.design.ZcodeSpacing
import com.zai.zcode.core.design.ZcodeType
import com.zai.zcode.core.design.components.DeviceChip
import com.zai.zcode.core.design.components.EmptyState
import com.zai.zcode.core.design.components.FilterChip
import com.zai.zcode.core.design.components.ZcodeStatus
import com.zai.zcode.core.design.components.StatusPill
import com.zai.zcode.feature.connect.ui.stateLabel
import com.zai.zcode.feature.sessions.SessionsOrdering
import com.zai.zcode.feature.sessions.SessionsRepository
import com.zai.zcode.feature.sessions.SessionsViewModel
import com.zai.zcode.feature.sessions.SessionFilter
import com.zai.zcode.feature.sessions.SessionsListState
import java.time.LocalDate
import java.time.ZoneId

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
    val filter = viewModel.filter
    val creating = viewModel.creating
    val createError = viewModel.createError
    val zc = LocalZcodeColors.current

    Column(Modifier.fillMaxSize()) {
        // 顶栏：标题 + 设备 Chip + 设置
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZcodeSpacing.screenPadding, vertical = ZcodeSpacing.md),
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
            DeviceChip(
                deviceName = currentWorkspace?.label
                    ?: currentWorkspace?.path?.substringAfterLast('\\')?.substringAfterLast('/')
                    ?: "这台电脑",
                onClick = { showWorkspacePicker = true },
            )
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Rounded.Settings, contentDescription = "设置", tint = zc.textSecondary)
            }
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

        // 筛选行：全部 / 需要你 / 运行中
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZcodeSpacing.screenPadding),
            horizontalArrangement = Arrangement.spacedBy(ZcodeSpacing.sm),
        ) {
            SessionFilter.entries.forEach { f ->
                FilterChip(label = f.label, selected = filter == f, onClick = { viewModel.onFilterSelected(f) })
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { viewModel.createSession(onOpenSession) }) {
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
                onCreate = { viewModel.createSession(onOpenSession) },
                creating = creating,
            )
        } else {
            val nowEpochDay = LocalDate.now().toEpochDay()
            val sections = SessionsOrdering.groupByRecency(sessions, { it }, nowEpochDay)
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    // 修复依据（spec §5.2 离线/不可用态）：runtime 不在时列表保留但降饱和，
                    // 而不是清空。用户在手机上看的是"上次见到的任务"，清空等于骗他任务没了。
                    .then(if (listState == SessionsListState.RuntimeUnavailable) Modifier.staleList() else Modifier),
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
                            modifier = Modifier.padding(vertical = ZcodeSpacing.xs),
                        )
                    }
                    items(
                        section.items.size,
                        key = { i -> section.items[i].sessionId },
                        contentType = { "taskCard" },
                    ) { i ->
                        val item = section.items[i]
                        TaskCard(
                            item = item,
                            relativeTime = SessionsOrdering.relativeTime(item.lastActivityAt),
                            absoluteTime = item.lastActivityAt?.let { formatAbsoluteTime(it) },
                            onClick = {
                                viewModel.markSeen(item.sessionId)
                                onOpenSession(item.sessionId)
                            },
                        )
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
 * 陈旧列表降饱和（docs/ui-design.md §5.2 离线态）。
 * 只降 alpha 不改布局，既是"这份数据可能过期"的信号，也不把内容推走重排。
 * alpha() 是非组合 API，可在纯函数里构造 Modifier 常量。
 */
private fun Modifier.staleList(): Modifier = this.alpha(0.55f)

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
            }
            itemsIndexed(workspaces, key = { _, ws -> ws.path }) { _, ws ->
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
