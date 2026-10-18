package com.zai.zcode.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextOverflow
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
import com.zai.zcode.core.design.components.EmptyState
import com.zai.zcode.core.design.components.LiveDot
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
            ChatTopBar(
                title = title,
                connState = connState,
                isRunning = ui.isRunning,
                onBack = onBack,
                onWatchScreen = onWatchScreen,
            )
            ChatRowList(
                rows = ui.rows,
                listState = listState,
                loadState = loadState,
                onRetry = viewModel::retrySubscribe,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
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

/**
 * 会话顶栏：返回 / 会话标题 + 连接态 / 看电脑。
 *
 * 修复依据（真机 v0.2.21「不知道自己在看哪个会话」）：原先这一行只有两个 TextButton，
 * 从列表点进来之后屏幕上没有任何东西指明正在看哪个会话——点错一次要滑到底部靠内容
 * 辨认。标题在中间偏左、单行 Ellipsis，是会话页最便宜的方位感。
 *
 * 标题下方（同一行的副标题位置）放状态而不是另一行：断线时如果不说，用户唯一的
 * 线索是"内容不再增长"，会误判成模型卡住。复用列表页同一套 [stateLabel] 文案，
 * 两页对同一件事用同一种说法。
 *
 * 状态行三选一，且多数时候不存在：断线（attention 色，要处理）> 运行中（带呼吸点，
 * 回答"电脑在不在干活"）> 什么都不显示。常态安静、异常才出声，顶栏才不会变成
 * 一条永远占两行的横幅。
 */
@Composable
private fun ChatTopBar(
    title: String?,
    connState: ZcodeConnection.State,
    isRunning: Boolean,
    onBack: () -> Unit,
    onWatchScreen: () -> Unit,
) {
    val zc = LocalZcodeColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZcodeSpacing.sm, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) { Text("‹ 返回", style = ZcodeType.uiSm) }
        // 标题区吃掉剩余宽度：返回/看电脑是固定短按钮，标题才是会变长的那个。
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = ZcodeSpacing.xs),
        ) {
            Text(
                // 没有标题（还没读出来 / 桌面没起）时显示占位而不是「新会话」：
                // 那是编造内容，用户会以为自己真的建了一个叫这个名字的会话。
                title ?: "…",
                style = ZcodeType.uiMd,
                color = if (title == null) zc.textTertiary else zc.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 只在"有事"时占用这一行：已连接且空闲是最常见的状态，为它画一行副标题
            // 等于让顶栏永远多占 14sp。异常才值得占地方。
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
        TextButton(onClick = onWatchScreen) { Text("看电脑", style = ZcodeType.uiSm) }
    }
}

@Composable
private fun ChatRowList(
    rows: List<ChatRepository.ChatRowKind>,
    listState: LazyListState,
    loadState: ChatLoadState,
    onRetry: () -> Unit,
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
