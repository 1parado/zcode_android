package com.zai.zcode.core.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zai.zcode.core.design.LocalZcodeColors
import com.zai.zcode.core.design.ZcodeShape
import com.zai.zcode.core.design.ZcodeSpacing
import com.zai.zcode.core.design.ZcodeType
import com.zai.zcode.core.design.rememberBreatheAlpha

/**
 * 基础组件（docs/ui-design.md §8）：LiveDot / StatusPill / FilterChip /
 * SectionHeader / EmptyState / DeviceChip。M1 期均为无依赖纯 Compose 实现。
 */

/** 语义状态枚举：与 spec 状态色一一对应；done 不着色。 */
enum class ZcodeStatus { Running, Attention, Error, Queued, Done }

@Composable
private fun statusColor(status: ZcodeStatus): Color = when (status) {
    ZcodeStatus.Running -> LocalZcodeColors.current.running
    ZcodeStatus.Attention -> LocalZcodeColors.current.attention
    ZcodeStatus.Error -> LocalZcodeColors.current.error
    ZcodeStatus.Queued -> LocalZcodeColors.current.queued
    ZcodeStatus.Done -> LocalZcodeColors.current.textTertiary
}

/** 运行指示点：Running 呼吸，其余静态。8dp 圆点。 */
@Composable
fun LiveDot(status: ZcodeStatus, modifier: Modifier = Modifier) {
    val color = statusColor(status)
    val alpha = if (status == ZcodeStatus.Running) rememberBreatheAlpha() else 1f
    Box(
        modifier
            .size(8.dp)
            .alpha(alpha)
            .background(color, CircleShape)
    )
}

/** 状态胶囊：点 + 文本，内嵌 8dp 圆角底。 */
@Composable
fun StatusPill(status: ZcodeStatus, label: String, modifier: Modifier = Modifier) {
    val zc = LocalZcodeColors.current
    val color = statusColor(status)
    Row(
        modifier
            .background(color.copy(alpha = 0.12f), ZcodeShape.inner)
            .padding(horizontal = ZcodeSpacing.sm, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveDot(status)
        Spacer(Modifier.width(6.dp))
        Text(label, style = ZcodeType.uiXs, color = color)
    }
}

/** 筛选 Chip 行：单选互斥，选中态中性反色（非 brand）。 */
@Composable
fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zc = LocalZcodeColors.current
    val bg = if (selected) zc.text else zc.card
    val fg = if (selected) (if (zc.darkTheme) zc.base else zc.base) else zc.textSecondary
    Box(
        modifier
            .clickable(onClick = onClick)
            .background(bg, ZcodeShape.inner)
            .padding(horizontal = ZcodeSpacing.md, vertical = 6.dp),
    ) {
        Text(label, style = ZcodeType.uiSm, color = fg)
    }
}

/** 区块标题：ui-sm 大写间距。 */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        modifier = modifier.padding(horizontal = ZcodeSpacing.screenPadding, vertical = ZcodeSpacing.sm),
        style = ZcodeType.uiXs,
        color = LocalZcodeColors.current.textTertiary,
    )
}

/** 空态：标题 + 可选说明 + 可选动作。 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val zc = LocalZcodeColors.current
    Box(modifier.padding(ZcodeSpacing.xl), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = ZcodeType.uiLg, color = zc.text)
            description?.let {
                Spacer(Modifier.width(ZcodeSpacing.sm))
                Text(it, style = ZcodeType.uiSm, color = zc.textSecondary)
            }
            action?.let {
                Spacer(Modifier.width(ZcodeSpacing.lg))
                it()
            }
        }
    }
}

/** 设备标签：Home 顶栏当前 workspace 名；提供 onClick 时可点开 workspace 选择器。 */
@Composable
fun DeviceChip(
    deviceName: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val zc = LocalZcodeColors.current
    Row(
        modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(zc.card, ZcodeShape.inner)
            .padding(horizontal = ZcodeSpacing.md, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveDot(ZcodeStatus.Running)
        Spacer(Modifier.width(6.dp))
        Text(deviceName, style = ZcodeType.uiSm, color = zc.textSecondary)
        if (onClick != null) {
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = "切换工作区",
                modifier = Modifier.size(14.dp),
                tint = zc.textTertiary,
            )
        }
    }
}

