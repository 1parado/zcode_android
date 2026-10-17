package com.zai.zcode.feature.sessions.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zai.zcode.core.design.LocalZcodeColors
import com.zai.zcode.core.design.ZcodeShape
import com.zai.zcode.core.design.ZcodeSpacing
import com.zai.zcode.core.design.ZcodeType
import com.zai.zcode.core.design.components.LiveDot
import com.zai.zcode.core.design.components.ZcodeStatus
import com.zai.zcode.feature.sessions.SessionsRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 任务卡（docs/ui-design.md §5.2）：三层信息——状态行 / 标题+未读点 / 预览+元数据。
 * 六状态变体：attention > running(含后台) > queued > error > done > idle。
 */
@Composable
fun TaskCard(
    item: SessionsRepository.SessionItem,
    relativeTime: String?,
    absoluteTime: String?,
    onClick: () -> Unit,
) {
    val zc = LocalZcodeColors.current
    val status = item.toStatus()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(zc.card, ZcodeShape.card)
            .clickable(onClick = onClick)
            .padding(ZcodeSpacing.cardPadding),
        verticalArrangement = Arrangement.spacedBy(ZcodeSpacing.sm),
    ) {
        // 第一层：状态行（LiveDot + 状态词 + 右侧元数据）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveDot(status)
            Spacer(Modifier.width(ZcodeSpacing.sm))
            Text(statusLabel(item), style = ZcodeType.uiXs, color = statusColor(status), modifier = Modifier.weight(1f))
            if (item.pinned) {
                Icon(
                    Icons.Rounded.PushPin,
                    contentDescription = "已置顶",
                    modifier = Modifier.size(12.dp),
                    tint = zc.textTertiary,
                )
            }
            relativeTime?.let {
                Text(it, style = ZcodeType.uiXs, color = zc.textTertiary)
            } ?: absoluteTime?.let {
                Text(it, style = ZcodeType.uiXs, color = zc.textTertiary)
            }
        }
        // 第二层：标题 + 未读蓝点
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.title,
                style = ZcodeType.uiMd,
                fontWeight = FontWeight.SemiBold,
                color = zc.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (item.unread) {
                Spacer(Modifier.width(ZcodeSpacing.sm))
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .size(7.dp)
                        .background(zc.queued, androidx.compose.foundation.shape.CircleShape),
                )
            }
        }
        // 第三层：预览 / 待决摘要 / 后台工作标记
        val attention = when {
            item.permissionCount > 0 -> "需要审批 · ${item.permissionCount} 项"
            item.userInputCount > 0 -> "等待输入 · ${item.userInputCount} 项"
            else -> null
        }
        if (attention != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Notifications,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = statusColor(ZcodeStatus.Attention),
                )
                Spacer(Modifier.width(ZcodeSpacing.xs))
                Text(attention, style = ZcodeType.uiXs, color = statusColor(ZcodeStatus.Attention))
            }
        }
        item.preview?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = ZcodeType.uiSm,
                color = zc.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (item.hasBackgroundWork) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = zc.textTertiary,
                )
                Spacer(Modifier.width(ZcodeSpacing.xs))
                Text("后台工作运行中", style = ZcodeType.uiXs, color = zc.textTertiary)
            }
        }
    }
}

/** 六状态映射：attention > running > queued > error > done > idle。 */
private fun SessionsRepository.SessionItem.toStatus(): ZcodeStatus = when {
    hasPendingInteraction -> ZcodeStatus.Attention
    !ended && phase == "running" -> ZcodeStatus.Running
    !ended && (phase == "draft" || phase == "prewarming") -> ZcodeStatus.Running
    !ended && phase == "error" -> ZcodeStatus.Error
    ended && phase == "error" -> ZcodeStatus.Error
    ended -> ZcodeStatus.Done
    else -> ZcodeStatus.Queued
}

@Composable
private fun statusLabel(item: SessionsRepository.SessionItem): String = when {
    item.hasPendingInteraction -> "需要你"
    !item.ended && item.phase == "running" -> "运行中"
    !item.ended && (item.phase == "draft" || item.phase == "prewarming") -> "启动中"
    !item.ended && item.hasBackgroundWork -> "后台工作中"
    item.phase == "error" -> "出错"
    item.ended -> "已完成"
    else -> "排队"
}

@Composable
private fun statusColor(status: ZcodeStatus): Color = when (status) {
    ZcodeStatus.Running -> LocalZcodeColors.current.running
    ZcodeStatus.Attention -> LocalZcodeColors.current.attention
    ZcodeStatus.Error -> LocalZcodeColors.current.error
    ZcodeStatus.Queued -> LocalZcodeColors.current.queued
    ZcodeStatus.Done -> LocalZcodeColors.current.textTertiary
}

private val ABS_FMT = ThreadLocal.withInitial { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

internal fun formatAbsoluteTime(timestamp: Long): String = ABS_FMT.get().format(Date(timestamp))
