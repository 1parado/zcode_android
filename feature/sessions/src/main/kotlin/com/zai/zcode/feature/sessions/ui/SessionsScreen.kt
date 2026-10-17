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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
            DeviceChip("这台电脑")
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Rounded.Settings, contentDescription = "设置", tint = zc.textSecondary)
            }
        }

        // 筛选行：全部 / 需要你 / 运行中
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZcodeSpacing.screenPadding),
            horizontalArrangement = Arrangement.spacedBy(ZcodeSpacing.sm),
        ) {
            SessionFilter.entries.forEach { f ->
                FilterChip(label = f.label, selected = filter == f, onClick = { viewModel.setFilter(f) })
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

        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (connState is com.zai.zcode.core.network.ZcodeConnection.State.Ready) {
                    EmptyState(
                        title = "暂无任务",
                        description = "创建一个新会话开始",
                        action = {
                            TextButton(onClick = { viewModel.createSession(onOpenSession) }) {
                                Text(if (creating) "创建中…" else "新会话")
                            }
                        },
                    )
                } else {
                    EmptyState(title = "等待连接…", description = "连接到电脑后这里会显示任务")
                }
            }
        } else {
            val nowEpochDay = LocalDate.now().toEpochDay()
            val sections = SessionsOrdering.groupByRecency(sessions, { it }, nowEpochDay)
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
