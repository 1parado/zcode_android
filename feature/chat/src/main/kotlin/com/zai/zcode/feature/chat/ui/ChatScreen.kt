package com.zai.zcode.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zai.zcode.core.design.LocalZcodeColors
import com.zai.zcode.core.design.ZcodeShape
import com.zai.zcode.core.design.ZcodeSpacing
import com.zai.zcode.core.design.ZcodeType
import com.zai.zcode.core.design.jellyClickable
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
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsState()
    val listState = rememberLazyListState()

    // 是否已停在底部：决定「跟随新内容」还是「累计未读」。
    // derivedStateOf 让它只在跨越阈值时变，滚动过程中不触发额外重组。
    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            last == null || last.index >= listState.layoutInfo.totalItemsCount - 2
        }
    }
    var pendingCount by remember { mutableIntStateOf(0) }
    var settledCount by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(ui.rows.size) {
        if (ui.rows.isEmpty()) return@LaunchedEffect
        if (atBottom) {
            listState.scrollToItem(ui.rows.size - 1)
        } else if (ui.rows.size > settledCount) {
            // 修复依据（spec §5.3）：用户在翻历史时不把视图拽走，改为累计未读数
            pendingCount += ui.rows.size - settledCount
        }
        settledCount = ui.rows.size
    }

    LaunchedEffect(atBottom) {
        if (atBottom) pendingCount = 0
    }

    Box(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxSize()) {
            ChatTopBar(onBack = onBack, onWatchScreen = onWatchScreen)
            ChatRowList(
                rows = ui.rows,
                listState = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            ChatComposer(viewModel = viewModel, isRunning = ui.isRunning)
        }

        if (pendingCount > 0) {
            JumpToLatestButton(
                count = pendingCount,
                onClick = {
                    scope.launch {
                        if (ui.rows.isNotEmpty()) listState.scrollToItem(ui.rows.size - 1)
                        pendingCount = 0
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp),
            )
        }
    }
}

@Composable
private fun ChatTopBar(onBack: () -> Unit, onWatchScreen: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ZcodeSpacing.sm, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) { Text("‹ 返回", style = ZcodeType.uiSm) }
        Box(Modifier.weight(1f))
        TextButton(onClick = onWatchScreen) { Text("看电脑", style = ZcodeType.uiSm) }
    }
}

@Composable
private fun ChatRowList(
    rows: List<ChatRepository.ChatRowKind>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(ZcodeSpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(
            items = rows,
            key = { it.rowId },
            // 修复依据（v0.2.17）：不声明 contentType 时 LazyColumn 把所有行当同一类，
            // 回收池里「工具卡」和「整段 Markdown」互相错配，滚出屏再滚回来每一行都要
            // 重新测量与重建 Composition —— 快速滑动时的掉帧主要来自这里。
            contentType = { row -> contentTypeOf(row) },
        ) { row -> ChatRowView(row) }
        if (rows.isEmpty()) {
            item {
                Text(
                    "加载会话…",
                    style = ZcodeType.uiSm,
                    color = LocalZcodeColors.current.textTertiary,
                    modifier = Modifier.fillMaxWidth().padding(ZcodeSpacing.xxl),
                )
            }
        }
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
    val zc = LocalZcodeColors.current
    Box(
        modifier
            .jellyClickable(onClick = onClick, onClickLabel = "回到底部")
            .background(zc.card, ZcodeShape.input)
            .padding(horizontal = ZcodeSpacing.md, vertical = 8.dp),
    ) {
        Text("↓ $count 条新消息", style = ZcodeType.uiSm, color = zc.text)
    }
}

/**
 * 输入栏（spec §5.3 状态机）：
 * - 运行中且无文字 → ■ 停止
 * - 其余 → ↑ 发送（有文字才可点）
 *
 * 不锁输入：等待审批 / 运行中都允许继续打字，发送即排队（是不会冻结用户手的设计，
 * 与「先想好再一次性发」相反——远程操控场景里用户常常已经在打下一条指令）。
 */
@Composable
private fun ChatComposer(viewModel: ChatViewModel, isRunning: Boolean) {
    val draft = viewModel.draft
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(ZcodeSpacing.md),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = viewModel::onDraftChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("发送消息…", style = ZcodeType.uiSm) },
            maxLines = 4,
            textStyle = ZcodeType.uiMd,
        )
        if (isRunning && draft.isBlank()) {
            IconButton(onClick = viewModel::stop) {
                Icon(
                    Icons.Rounded.Stop,
                    contentDescription = "停止",
                    tint = LocalZcodeColors.current.error,
                )
            }
        } else {
            val enabled = draft.isNotBlank() && !viewModel.sending
            Box(
                modifier = Modifier
                    .size(48.dp)
                    // 修复依据（docs/ui-design.md §7 果冻按压）：发送是会话页最频繁的交互，
                    // 按压缩放让「按下了」在抬指之前就被感知到。
                    .jellyClickable(enabled = enabled, onClick = viewModel::send),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.ArrowUpward,
                    contentDescription = "发送",
                    tint = if (enabled) LocalZcodeColors.current.text else LocalZcodeColors.current.textTertiary,
                )
            }
        }
    }
}
