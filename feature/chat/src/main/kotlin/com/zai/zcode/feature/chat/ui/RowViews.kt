package com.zai.zcode.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbDownOffAlt
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.ThumbUpOffAlt
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import com.zai.zcode.core.design.LocalZcodeColors
import com.zai.zcode.feature.chat.ChatViewModel
import com.zai.zcode.core.design.ZcodeShape
import com.zai.zcode.core.design.ZcodeSpacing
import com.zai.zcode.core.design.ZcodeType
import com.zai.zcode.core.design.components.LiveDot
import com.zai.zcode.core.design.components.ZcodeStatus
import com.zai.zcode.core.design.ZcodeMotion
import com.zai.zcode.core.design.jellyPress
import com.zai.zcode.feature.chat.ChatRepository
import com.zai.zcode.feature.render.MarkdownContent
import kotlinx.coroutines.delay

/**
 * 会话流的行渲染（docs/ui-design.md §5.3）。
 *
 * 两条裁决：
 * 1. **用户气泡 vs 助手无气泡**——用户说话有边界感（最大 84% 宽 / 圆角 18,6），助手正文是
 *    全宽文档（spec §5.3：「助手正文不包卡片，直接全宽排布」），中间不放容器，
 *    代码块与表格才能用满宽度。
 * 2. **kind → 渲染路径在编译期穷尽**——[ChatRepository.ChatRowKind] 是 sealed interface，
 *    `when (row)` 不加 else 分支。服务端新增行类型时这里直接编译不过，逼着显式补渲染路径，
 *    而不是让新行悄悄掉进兜底。
 *
 * 入场动效（docs/ui-design.md §7）：新行淡入 + 8dp 上移，180ms collapseEasing——
 * 消息"出现"而不是"跳现"。只对新增行播一次：动画状态按 rowId 记账，历史回填
 * 批量插入的行也各自淡入，但同一行不会重播。
 */
/** 图片加载需要的连接上下文（ChatScreen 提供；图片画廊官方 markdown-image 同链路）。 */
internal val LocalChatImageContext = compositionLocalOf<Pair<String, com.zai.zcode.core.network.ZcodeConnection>?> { null }

@Composable
internal fun ChatRowView(row: ChatRepository.ChatRowKind, viewModel: ChatViewModel? = null) {
    // 入场动效：remember(rowId) 的初始 alpha/offset 是 0，首帧后 animateFloatAsState
    // 推到 1。历史行（回填）同样走这一路径，但只播一次不随重组重播。
    var appeared by remember(row.rowId) { mutableStateOf(false) }
    LaunchedEffect(row.rowId) { appeared = true }
    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(ZcodeMotion.COLLAPSE_MS, easing = ZcodeMotion.collapseEasing),
        label = "rowAlpha",
    )
    val offsetY by animateDpAsState(
        targetValue = if (appeared) 0.dp else 8.dp,
        animationSpec = tween(ZcodeMotion.COLLAPSE_MS, easing = ZcodeMotion.collapseEasing),
        label = "rowOffset",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha; translationY = offsetY.toPx() },
    ) {
        when (row) {
            is ChatRepository.ChatRowKind.UserInput -> UserInputRow(row)
            is ChatRepository.ChatRowKind.AssistantText -> AssistantTextRow(row, viewModel)
            is ChatRepository.ChatRowKind.Reasoning -> ReasoningRow(row)
            is ChatRepository.ChatRowKind.ToolCall -> ToolCallRow(row)
            is ChatRepository.ChatRowKind.TurnHeader -> TurnHeaderRow(row)
            is ChatRepository.ChatRowKind.Subagent -> SubagentRow(row)
            is ChatRepository.ChatRowKind.Artifact -> ArtifactRow(row)
            is ChatRepository.ChatRowKind.HookInvocation -> HookInvocationRow(row)
            is ChatRepository.ChatRowKind.TimelineMarker -> TimelineMarkerRow(row)
            is ChatRepository.ChatRowKind.Unknown -> UnknownRow(row)
        }
    }
}

// ── 用户 / 助手 ──

@Composable
private fun UserInputRow(row: ChatRepository.ChatRowKind.UserInput) {
    val zc = LocalZcodeColors.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            row.text,
            modifier = Modifier
                // spec 5.3：气泡最大 84%，圆角 18 与 6 的缺口指向说话人
                .fillMaxWidth(0.84f)
                .clip(RoundedCornerShape(18.dp, 6.dp, 18.dp, 18.dp))
                .background(zc.input)
                // 一键复制：长按气泡全文入剪贴板（用户消息是纯文本，无 Markdown 面板，
                // 长按是移动端对"复制这段话"最惯用的手势）
                .combinedClickable(
                    enabled = true,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(),
                    onLongClick = {
                        clipboard.setText(AnnotatedString(row.text))
                        copied = true
                    },
                    onClick = {},
                )
                .padding(horizontal = ZcodeSpacing.md, vertical = 10.dp),
            style = ZcodeType.uiMd,
            color = zc.text,
        )
    }
    // 图片附件缩略图（官方 ConversationRowView attachmentKind=media 同语义）
    val imageAttachments = row.attachments.filter { it.isImage }
    val ctx = LocalChatImageContext.current
    if (imageAttachments.isNotEmpty() && ctx != null) {
        ChatImageGallery(
            imageRefs = imageAttachments.map { it.ref },
            sessionId = ctx.first,
            connection = ctx.second,
            modifier = Modifier.padding(top = ZcodeSpacing.xs),
        )
    }
    // 复制成功提示：短暂显示后消失，不弹 Toast（Toast 与气泡在同一屏幕区域叠加，
    // 视觉重心分散；行内小徽标足够）。
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }
    if (copied) {
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Text("已复制", style = ZcodeType.uiXs, color = zc.textTertiary)
        }
    }
}

@Composable
private fun AssistantTextRow(
    row: ChatRepository.ChatRowKind.AssistantText,
    viewModel: ChatViewModel? = null,
) {
    val zc = LocalZcodeColors.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        MarkdownContent(raw = row.text, isStreaming = row.streaming)
        // 修复依据（用户报「无法复制 Agent 回复」）：复制按钮移到正文下方且流式一结束
        // 即出现（此前藏在右上角且仅 >80 字才显示，用户找不到）。长回复是文档，
        // "操作"（复制）放内容末尾是阅读动线的自然终点。
        if (!row.streaming) {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 消息级操作（官方 ConversationRowView:2323-2396 同款反馈/重试/分支）
                if (viewModel != null) {
                    val entityId = row.entityId ?: row.rowId.toString()
                    Icon(
                        if (row.feedback == "like") Icons.Rounded.ThumbUp else Icons.Rounded.ThumbUpOffAlt,
                        contentDescription = "点赞",
                        modifier = Modifier
                            .size(14.dp)
                            .clickable { viewModel.toggleFeedback(row.rowId, entityId, row.feedback) },
                        tint = if (row.feedback == "like") zc.running else zc.textTertiary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Icon(
                        if (row.feedback == "dislike") Icons.Rounded.ThumbDown else Icons.Rounded.ThumbDownOffAlt,
                        contentDescription = "点踩",
                        modifier = Modifier
                            .size(14.dp)
                            .clickable { viewModel.toggleFeedbackDislike(row.rowId, entityId, row.feedback) },
                        tint = if (row.feedback == "dislike") zc.error else zc.textTertiary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = "重跑此消息",
                        modifier = Modifier
                            .size(14.dp)
                            .clickable { viewModel.retryMessage(row.rowId, entityId) },
                        tint = zc.textTertiary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Icon(
                        Icons.Rounded.CallSplit,
                        contentDescription = "从此消息分支新会话",
                        modifier = Modifier
                            .size(14.dp)
                            .clickable {
                                viewModel.forkMessage(row.rowId, entityId) { childId ->
                                    // fork 成功：事件由外层 ChatScreen 订阅导航（见 ChatScreen ForkEventBus）
                                    ForkEventBus.emit(childId)
                                }
                            },
                        tint = zc.textTertiary,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Row(
                    Modifier
                        .clip(ZcodeShape.control)
                        .clickable {
                            clipboard.setText(AnnotatedString(row.text))
                            copied = true
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = "复制回复",
                        modifier = Modifier.size(12.dp),
                        tint = if (copied) zc.running else zc.textTertiary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (copied) "已复制" else "复制",
                        style = ZcodeType.uiXs,
                        color = if (copied) zc.running else zc.textTertiary,
                    )
                }
            }
        }
        // 修复依据（spec 8.6「无 emoji」）：原先用 Text("●") 当流式指示，那是字形不是矢量，
        // 不同字体会渲染成不同东西（乃至豆腐块）。走 LiveDot + 呼吸动画。
        if (row.streaming) {
            LiveDot(ZcodeStatus.Running, Modifier.padding(top = ZcodeSpacing.sm))
        }
        // 正文内嵌图片画廊（官方 markdown-image：zcode-artifact:// 经 attachmentRead 拉字节）
        val ctx = LocalChatImageContext.current
        if (row.imageRefs.isNotEmpty() && ctx != null) {
            ChatImageGallery(
                imageRefs = row.imageRefs,
                sessionId = ctx.first,
                connection = ctx.second,
                modifier = Modifier.padding(top = ZcodeSpacing.sm),
            )
        }
    }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }
}

/** fork 事件总线：RowView 拿不到导航回调，经单例中转给 ChatScreen 导航。 */
internal object ForkEventBus {
    private val _events = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: kotlinx.coroutines.flow.SharedFlow<String> = _events
    fun emit(childSessionId: String) {
        _events.tryEmit(childSessionId)
    }
}

/**
 * 思考：默认折叠成一行「思考 · 12s」。
 * 修复依据（spec 5.3）：思考过程常常比最终答案长几倍，默认展开会把真正的回答一直顶出屏幕，
 * 用户还得手动往下追——折叠是信息密度与注意力的平衡，不是藏内容。
 */
@Composable
private fun ReasoningRow(row: ChatRepository.ChatRowKind.Reasoning) {
    val zc = LocalZcodeColors.current
    var expanded by rememberSaveable(row.rowId) { mutableStateOf(false) }
    // 修复依据（docs/ui-design.md §7）：箭头转 180° 是折叠状态最省地方的表达——
    // 不用换图标（换图标会闪一下），转的过程本身就是"展开了"的语义。
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(ZcodeMotion.COLLAPSE_MS, easing = ZcodeMotion.collapseEasing),
        label = "reasoningChevron",
    )
    val rowInteraction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .fillMaxWidth()
            // Trade-off（spec §5.3）：思考行本身是次要信息，给它 ripple 会把注意力
            // 从正文拉走；这里只要按压缩放这一层触达反馈，不叠加 ripple。
            .jellyPress(rowInteraction)
            .clickable(
                interactionSource = rowInteraction,
                indication = null,
                onClick = { expanded = !expanded },
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "收起思考" else "展开思考",
                modifier = Modifier
                    .size(14.dp)
                    // graphicsLayer 旋转不参与测量：连续展开/收起不会让整列重排跳动。
                    .graphicsLayer { rotationZ = rotation },
                tint = zc.textTertiary,
            )
            Spacer(Modifier.width(6.dp))
            Text("思考", style = ZcodeType.uiXs, color = zc.textTertiary)
        }
        if (expanded) {
            Text(
                row.text,
                modifier = Modifier.padding(top = ZcodeSpacing.sm),
                style = ZcodeType.uiSm,
                color = zc.textSecondary,
            )
        }
    }
}

// ── 工具 / hook / 子代理 / 结果物 ──

/** toolCall 状态 → 语义状态色（spec：工具行用状态色，不复用 success/destructive）。 */
private fun toolStatusOf(status: String?): ZcodeStatus = when (status) {
    "inputStreaming", "running" -> ZcodeStatus.Running
    "pendingApproval" -> ZcodeStatus.Attention
    "error" -> ZcodeStatus.Error
    "success" -> ZcodeStatus.Done
    else -> ZcodeStatus.Queued
}

@Composable
private fun ToolCallRow(row: ChatRepository.ChatRowKind.ToolCall) {
    val zc = LocalZcodeColors.current
    val status = toolStatusOf(row.status)
    var expanded by remember(row.rowId) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(ZcodeShape.inner)
            .background(zc.card)
            // 点击卡体展开/收起输出（官方工具卡的展开语义）
            .clickable { expanded = !expanded }
            .padding(horizontal = ZcodeSpacing.md, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 按工具身份选图标（官方 60+ renderer 的移动端近似：高频 5 类）
            val icon = when (row.displayKind ?: toolFamilyOf(row.toolName)) {
                "bash_output", "bash", "execute" -> Icons.Rounded.Terminal
                "file_diff", "edit", "write" -> Icons.Rounded.Edit
                "read" -> Icons.Rounded.Description
                "cua" -> Icons.Rounded.DesktopWindows
                else -> Icons.Rounded.Build
            }
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = zc.textSecondary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                // 卡面标题：diff 显示文件名，bash 显示命令摘要，其余显示工具名
                row.diffFilePath?.substringAfterLast('/')
                    ?: row.inputText?.lineSequence()?.firstOrNull()?.take(48)
                    ?: row.toolName,
                style = ZcodeType.mono,
                color = zc.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // edit/write 显示 +N -N（官方 file_diff 卡同款）
            if (row.diffAdditions != null || row.diffDeletions != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    buildString {
                        row.diffAdditions?.takeIf { it > 0 }?.let { append("+${it} ") }
                        row.diffDeletions?.takeIf { it > 0 }?.let { append("-${it}") }
                    },
                    style = ZcodeType.uiXs,
                    color = if ((row.diffDeletions ?: 0) > (row.diffAdditions ?: 0)) {
                        Color(0xFFD44747)
                    } else {
                        Color(0xFF2E9E5B)
                    },
                )
            }
            Spacer(Modifier.width(6.dp))
            LiveDot(status)
        }
        // 运行中：Bash 有界预览一行（官方 outputPreview）
        if (row.streaming && (row.outputPreview ?: row.progressLine) != null) {
            Text(
                (row.outputPreview ?: row.progressLine)?.lineSequence()?.lastOrNull().orEmpty(),
                modifier = Modifier.padding(top = 4.dp),
                style = ZcodeType.mono,
                color = zc.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 失败：错误码 + 信息（官方 error 块）
        if (row.status == "error") {
            Text(
                listOfNotNull(row.errorCode, row.errorMessage).joinToString("："),
                modifier = Modifier.padding(top = 4.dp),
                style = ZcodeType.uiXs,
                color = zc.error,
                maxLines = if (expanded) 8 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 展开：bash 输出 / diff 补丁 / 终态全文（官方专属 renderer 的移动端近似）
        if (expanded) {
            val body = when {
                row.patch.isNotEmpty() -> null // 走 diff 渲染
                row.bashOutput?.isNotBlank() == true -> row.bashOutput
                row.outputText?.isNotBlank() == true -> row.outputText
                else -> null
            }
            if (row.patch.isNotEmpty()) {
                DiffHunksView(row.patch, Modifier.padding(top = 6.dp))
            } else if (body != null) {
                Text(
                    body,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF16181D))
                        .padding(8.dp),
                    style = ZcodeType.mono.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    ),
                    color = Color(0xFFC9CED6),
                    maxLines = 20,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (row.outputText == null && row.bashOutput == null && row.patch.isEmpty()) {
                Text(
                    "无输出",
                    modifier = Modifier.padding(top = 6.dp),
                    style = ZcodeType.uiXs,
                    color = zc.textTertiary,
                )
            }
        } else {
            // 参数摘要：工具输入常常是整份文件内容，截断到两行，避免一行工具吃掉整屏
            row.inputText?.takeIf { it.isNotBlank() && row.diffFilePath == null }?.let {
                Text(
                    it,
                    modifier = Modifier.padding(top = 4.dp),
                    style = ZcodeType.mono,
                    color = zc.textTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 工具名 → 卡面家族（无 display kind 时的近似分派）。 */
private fun toolFamilyOf(toolName: String): String = when {
    toolName.equals("bash", true) || toolName.equals("execute", true) -> "bash"
    toolName.equals("edit", true) || toolName.equals("write", true) -> "edit"
    toolName.equals("read", true) -> "read"
    else -> "other"
}

/** 补丁片段渲染（+绿/-红/上下文灰，官方 file_diff structuredPatch 的简化）。 */
@Composable
private fun DiffHunksView(hunks: List<com.zai.zcode.core.protocol.v4.PatchHunk>, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF16181D))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        hunks.take(3).forEach { hunk ->
            Text(
                "@@ -${hunk.oldStart}",
                style = ZcodeType.mono.copy(fontFamily = FontFamily.Monospace, fontSize = 9.sp),
                color = Color(0xFF6E9CD2),
            )
            hunk.lines.take(24).forEach { line ->
                Text(
                    line.ifEmpty { " " },
                    style = ZcodeType.mono.copy(fontFamily = FontFamily.Monospace, fontSize = 9.sp),
                    color = when {
                        line.startsWith("+") -> Color(0xFF9AD8AE)
                        line.startsWith("-") -> Color(0xFFE0A2A6)
                        else -> Color(0xFF7F8794)
                    },
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun HookInvocationRow(row: ChatRepository.ChatRowKind.HookInvocation) {
    val zc = LocalZcodeColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ZcodeSpacing.md, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Terminal,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = zc.textTertiary,
        )
        Spacer(Modifier.width(6.dp))
        val count = row.hookCount?.takeIf { it > 1 }?.let { " ×$it" }.orEmpty()
        Text(
            "${row.hookEventName ?: "hook"}$count",
            style = ZcodeType.uiXs,
            color = zc.textTertiary,
        )
        if (row.streaming) {
            Spacer(Modifier.width(6.dp))
            LiveDot(ZcodeStatus.Running)
        }
    }
}

@Composable
private fun SubagentRow(row: ChatRepository.ChatRowKind.Subagent) {
    val zc = LocalZcodeColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(ZcodeShape.inner)
            .background(zc.card)
            .padding(horizontal = ZcodeSpacing.md, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = zc.textSecondary,
            )
            Spacer(Modifier.width(6.dp))
            Text(row.subagentType ?: "子代理", style = ZcodeType.uiSm, color = zc.text)
            if (row.streaming) {
                Spacer(Modifier.width(6.dp))
                LiveDot(ZcodeStatus.Running)
            }
        }
        row.summaryText?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                modifier = Modifier.padding(top = 4.dp),
                style = ZcodeType.uiSm,
                color = zc.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ArtifactRow(row: ChatRepository.ChatRowKind.Artifact) {
    val zc = LocalZcodeColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ZcodeShape.inner)
            .background(zc.card)
            .padding(horizontal = ZcodeSpacing.md, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            row.displayName ?: row.artifactType ?: "结果物",
            style = ZcodeType.uiSm,
            color = zc.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        row.sizeBytes?.let {
            Text(formatBytes(it), style = ZcodeType.uiXs, color = zc.textTertiary)
        }
    }
}

/** 结果物体积：够用即可，MB 以上保留一位小数，避免一行轻微变化就让 Text 抖动。 */
private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
}

// ── 时间线 ──

/**
 * turnHeader：一轮的边界。
 * 已结束且带文件摘要时渲染「N 个文件已更改」摘要卡（spec §5.3 Diff 摘要），
 * 进行中的轮只留一条轻边界，避免每轮开头都挂一张空卡。
 */
@Composable
private fun TurnHeaderRow(row: ChatRepository.ChatRowKind.TurnHeader) {
    val zc = LocalZcodeColors.current
    val changes = row.fileChanges ?: return
    if (changes.files <= 0) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(ZcodeShape.inner)
            .background(zc.card)
            .padding(horizontal = ZcodeSpacing.md, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${changes.files} 个文件已更改",
            style = ZcodeType.uiSm,
            color = zc.text,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "+${changes.additions} −${changes.deletions}",
            style = ZcodeType.mono,
            color = zc.textTertiary,
        )
    }
}

/**
 * 系统分隔：居中细字（spec §5.3「系统分隔居中 11sp」）。
 * 居中是与对话内容最强的区隔——靠左会被读成另一条发言，居中一眼就知道这是时间线标记。
 */
@Composable
private fun TimelineMarkerRow(row: ChatRepository.ChatRowKind.TimelineMarker) {
    val zc = LocalZcodeColors.current
    val text = row.text ?: return
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZcodeSpacing.xl, vertical = ZcodeSpacing.sm),
        style = ZcodeType.uiXs,
        color = zc.textTertiary,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 未知行类型：降级展示而非丢弃。
 * label 是服务端下发的 kind——用户看得见「这是一个还没支持的行」，
 * 开发者从截图就能定位该补哪个分支。
 */
@Composable
private fun UnknownRow(row: ChatRepository.ChatRowKind.Unknown) {
    Text(
        "暂不支持的行类型：${row.label}",
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        style = ZcodeType.uiXs,
        color = LocalZcodeColors.current.textTertiary,
    )
}
