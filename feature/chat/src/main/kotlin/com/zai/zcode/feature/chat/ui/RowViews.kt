package com.zai.zcode.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import com.zai.zcode.core.design.LocalZcodeColors
import com.zai.zcode.core.design.ZcodeShape
import com.zai.zcode.core.design.ZcodeSpacing
import com.zai.zcode.core.design.ZcodeType
import com.zai.zcode.core.design.components.LiveDot
import com.zai.zcode.core.design.components.ZcodeStatus
import com.zai.zcode.core.design.ZcodeMotion
import com.zai.zcode.core.design.jellyPress
import com.zai.zcode.feature.chat.ChatRepository
import com.zai.zcode.feature.render.MarkdownContent

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
 */
@Composable
internal fun ChatRowView(row: ChatRepository.ChatRowKind) {
    when (row) {
        is ChatRepository.ChatRowKind.UserInput -> UserInputRow(row)
        is ChatRepository.ChatRowKind.AssistantText -> AssistantTextRow(row)
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

// ── 用户 / 助手 ──

@Composable
private fun UserInputRow(row: ChatRepository.ChatRowKind.UserInput) {
    val zc = LocalZcodeColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            row.text,
            modifier = Modifier
                // spec 5.3：气泡最大 84%，圆角 18 与 6 的缺口指向说话人
                .fillMaxWidth(0.84f)
                .clip(RoundedCornerShape(18.dp, 6.dp, 18.dp, 18.dp))
                .background(zc.input)
                .padding(horizontal = ZcodeSpacing.md, vertical = 10.dp),
            style = ZcodeType.uiMd,
            color = zc.text,
        )
    }
}

@Composable
private fun AssistantTextRow(row: ChatRepository.ChatRowKind.AssistantText) {
    val zc = LocalZcodeColors.current
    Column(Modifier.fillMaxWidth()) {
        MarkdownContent(raw = row.text, isStreaming = row.streaming)
        // 修复依据（spec 8.6「无 emoji」）：原先用 Text("●") 当流式指示，那是字形不是矢量，
        // 不同字体会渲染成不同东西（乃至豆腐块）。走 LiveDot + 呼吸动画。
        if (row.streaming) {
            LiveDot(ZcodeStatus.Running, Modifier.padding(top = ZcodeSpacing.sm))
        }
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
    Column(
        Modifier
            .fillMaxWidth()
            .clip(ZcodeShape.inner)
            .background(zc.card)
            .padding(horizontal = ZcodeSpacing.md, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Build,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = zc.textSecondary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                row.toolName,
                style = ZcodeType.mono,
                color = zc.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(6.dp))
            LiveDot(status)
        }
        // 参数摘要：工具输入常常是整份文件内容，截断到两行，避免一行工具吃掉整屏
        row.inputText?.takeIf { it.isNotBlank() }?.let {
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
