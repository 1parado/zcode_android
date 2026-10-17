package com.zai.zcode.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zai.zcode.core.design.CardCorner
import com.zai.zcode.feature.chat.ChatRepository
import com.zai.zcode.feature.chat.ChatViewModel
import com.zai.zcode.feature.render.MarkdownContent

/**
 * 对话页：行流（LazyColumn）+ 底部输入栏。
 * 流式渲染依赖 row.delta append 的细粒度归约快照 —— LazyColumn items(key=rowId)
 * 只重组变化的行。
 */
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onWatchScreen: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsState()
    val listState = rememberLazyListState()

    // 新行或流式增长时贴底（用户上滑翻历史时不打扰）
    val shouldStick by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            last == null || last.index >= listState.layoutInfo.totalItemsCount - 2
        }
    }
    LaunchedEffect(ui.rows.size, ui.rows.lastOrNull()?.text?.length) {
        if (shouldStick && ui.rows.isNotEmpty()) {
            listState.animateScrollToItem(ui.rows.size - 1)
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        // 顶栏
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ 返回") }
            Box(Modifier.weight(1f))
            TextButton(onClick = onWatchScreen) { Text("看电脑") }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(ui.rows, key = { it.rowId }) { row ->
                ChatRowView(row)
            }
            if (ui.rows.isEmpty()) {
                item {
                    Text(
                        "加载会话…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
            }
        }

        // 输入栏
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = viewModel.draft,
                onValueChange = viewModel::onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("发送消息…") },
                maxLines = 4,
            )
            // 修复依据（P3，评审 2026-10-17）：phase 是封闭枚举，isRunning 由
            // ChatRepository 映射（draft/prewarming/running），UI 不猜子串
            if (ui.isRunning) {
                // 运行中：显示停止按钮
                IconButton(onClick = viewModel::stop) {
                    Text("⏹", style = MaterialTheme.typography.titleLarge)
                }
            } else {
                IconButton(onClick = viewModel::send, enabled = !viewModel.sending) {
                    Text("➤", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

@Composable
private fun ChatRowView(row: ChatRepository.ChatRow) {
    val isUser = row.kind == "userInput"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(
                    if (isUser) RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp)
                    else RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)
                )
                .background(
                    if (isUser) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .padding(12.dp),
        ) {
            when (row.kind) {
                "userInput" -> Text(
                    row.text ?: row.inputText ?: "",
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                // Markdown 渲染层：标题/列表/表格/代码块/公式/行内样式（参考 lody-ios MarkdownView 管线）
                "assistantText" -> MarkdownContent(
                    raw = row.text ?: "",
                    isStreaming = row.streaming,
                )
                "reasoning" -> Text(
                    row.text ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                "toolCall" -> Column {
                    Text(
                        "🔧 ${row.toolName ?: "tool"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    row.inputText?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> row.text?.takeIf { it.isNotBlank() }?.let { Text(it) }
            }
            if (row.streaming) {
                Text("●", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
