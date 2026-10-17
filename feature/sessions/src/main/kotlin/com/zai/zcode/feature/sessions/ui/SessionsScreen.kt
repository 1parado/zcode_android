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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zai.zcode.core.design.CardCorner
import com.zai.zcode.feature.connect.ui.stateLabel
import com.zai.zcode.feature.sessions.SessionsRepository
import com.zai.zcode.feature.sessions.SessionsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话列表页：顶栏连接状态 + LazyColumn 列表。
 * 列表来自 sessions-index 订阅的归约快照；空态给引导文案。
 */
@Composable
fun SessionsScreen(
    onOpenSession: (sessionId: String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: SessionsViewModel = hiltViewModel(),
) {
    val sessions by viewModel.sessions.collectAsState()
    val connState by viewModel.connectionState.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("会话", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stateLabel(connState),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "设置",
                    modifier = Modifier.clickable(onClick = onOpenSettings),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (connState is com.zai.zcode.core.network.ZcodeConnection.State.Ready) {
                        "暂无会话，去电脑端创建一个吧"
                    } else {
                        "等待连接…"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(sessions, key = { it.sessionId }) { item ->
                    SessionCard(item = item, onClick = { onOpenSession(item.sessionId) })
                }
            }
        }
    }
}

@Composable
private fun SessionCard(item: SessionsRepository.SessionItem, onClick: () -> Unit) {
    Card(
        shape = CardCorner,
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (item.phase != null && !item.ended) {
                    PhaseDot(item.phase)
                }
            }
            item.preview?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item.lastActivityAt?.let {
                Text(
                    TIME_FMT.get().format(Date(it)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** phase → 状态圆点（运行中绿色、思考紫色、其余灰）。 */
@Composable
private fun PhaseDot(phase: String) {
    val color = when {
        phase.contains("run", ignoreCase = true) -> com.zai.zcode.core.design.StreamingGreen
        phase.contains("think", ignoreCase = true) -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    Box(
        Modifier
            .size(8.dp)
            .background(color, CircleShape)
    )
}

private val TIME_FMT = ThreadLocal.withInitial {
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
}
