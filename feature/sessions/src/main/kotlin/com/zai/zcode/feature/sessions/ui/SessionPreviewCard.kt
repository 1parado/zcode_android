package com.zai.zcode.feature.sessions.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import com.zai.zcode.core.design.LocalZcodeColors
import com.zai.zcode.core.design.ZcodeShape
import com.zai.zcode.core.design.ZcodeSpacing
import com.zai.zcode.core.design.ZcodeType
import com.zai.zcode.core.design.components.LiveDot
import com.zai.zcode.core.design.components.ZcodeStatus
import com.zai.zcode.feature.sessions.SessionsRepository

/**
 * 会话预览卡片（长按会话条目弹出，官方 hover card 的移动端形态）：
 * 标题 + 最近消息摘要 + 更新时间 + 实时状态，附「打开会话」与置顶/归档/删除操作。
 *
 * 设计依据（用户需求 2026-10-19）：长按预览与长按操作菜单合并为一张卡——同一手势
 * 两层信息，不互相抢占。数据全部来自 [SessionsRepository.SessionItem] 已有字段
 * （列表订阅的同一份快照），不新增网络请求或第二次 sessions-index 订阅。
 *
 * 动画：淡入 + 92% 缩放 + 轻微上移，180ms（需求 §5：150–220ms、克制）；系统
 * 关闭动画（ANIMATOR_DURATION_SCALE=0）时 AnimatedVisibility 直接跳切，天然兼容
 * 减少动态效果。
 */
@Composable
fun SessionPreviewCard(
    item: SessionsRepository.SessionItem,
    relativeTime: String?,
    absoluteTime: String?,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zc = LocalZcodeColors.current
    val status = item.toStatus()
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }

    AnimatedVisibility(
        visibleState = visible,
        enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.92f) +
            slideInVertically(tween(180)) { it / 12 },
        exit = fadeOut(tween(140)),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .clip(ZcodeShape.card)
                .background(zc.base)
                // TalkBack：整卡读出「会话预览 + 标题 + 状态」；「打开会话」按钮单独可聚焦
                .semantics { contentDescription = "会话预览：${item.title}" }
                .padding(ZcodeSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(ZcodeSpacing.sm),
        ) {
            // 状态行：LiveDot + 状态词 + 更新时间（与 TaskCard 同构，复用同一状态映射）
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveDot(status)
                Spacer(Modifier.width(ZcodeSpacing.xs))
                Text(statusLabel(item), style = ZcodeType.uiXs, color = statusColor(status))
                Spacer(Modifier.weight(1f))
                Text(
                    relativeTime ?: absoluteTime ?: "时间未知",
                    style = ZcodeType.uiXs,
                    color = zc.textTertiary,
                )
            }
            // 标题（长标题截断）
            Text(
                item.title.ifBlank { "（无标题）" },
                style = ZcodeType.uiLg,
                color = zc.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // 最近消息摘要：列表订阅里本来就有（TaskCard 第三层同源）；缺失时空态文案
            if (item.preview.isNullOrBlank()) {
                Text("暂无消息摘要", style = ZcodeType.uiSm, color = zc.textTertiary)
            } else {
                Text(
                    item.preview,
                    style = ZcodeType.uiSm,
                    color = zc.textSecondary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            // 主操作：打开会话（走现有 onOpenSession(sessionId) 导航）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clip(ZcodeShape.input)
                    .background(zc.text)
                    .clickable { onOpen() }
                    .padding(horizontal = ZcodeSpacing.lg, vertical = ZcodeSpacing.md),
                contentAlignment = Alignment.Center,
            ) {
                Text("打开会话", style = ZcodeType.uiMd, color = zc.base)
            }
            // 次操作行：置顶 / 归档 / 删除（原长按菜单能力并入预览，不丢功能）
            Row(horizontalArrangement = Arrangement.spacedBy(ZcodeSpacing.sm)) {
                PreviewAction(
                    icon = { Icon(Icons.Rounded.PushPin, null, modifier = Modifier.size(14.dp), tint = zc.text) },
                    label = if (item.pinned) "取消置顶" else "置顶",
                    modifier = Modifier.weight(1f),
                ) { onTogglePin() }
                PreviewAction(
                    icon = { Icon(Icons.Rounded.Archive, null, modifier = Modifier.size(14.dp), tint = zc.text) },
                    label = "归档",
                    modifier = Modifier.weight(1f),
                ) { onArchive() }
                PreviewAction(
                    icon = { Icon(Icons.Rounded.Delete, null, modifier = Modifier.size(14.dp), tint = zc.error) },
                    label = "删除",
                    modifier = Modifier.weight(1f),
                ) { onDelete() }
            }
        }
    }
    // 关闭动画结束后真正 dismiss（AnimatedVisibility 播完 exit 才回调）
    if (!visible.targetState && !visible.currentState) {
        // MutableTransitionState 首帧即 false→true，这里只在显式退出后触发
        if (visible.isIdle && !visible.currentState) onDismiss()
    }
}

/** 预览卡内的次级操作块：卡底色 + 图标文字，44dp 最小触控。 */
@Composable
private fun PreviewAction(
    icon: @Composable () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val zc = LocalZcodeColors.current
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(ZcodeShape.input)
            .background(zc.input)
            .clickable(onClick = onClick)
            .padding(horizontal = ZcodeSpacing.sm, vertical = ZcodeSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        icon()
        Spacer(Modifier.width(ZcodeSpacing.xs))
        Text(label, style = ZcodeType.uiXs, color = zc.text, maxLines = 1)
    }
}
