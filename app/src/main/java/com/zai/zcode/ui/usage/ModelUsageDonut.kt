package com.zai.zcode.ui.usage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zai.zcode.core.design.LocalZcodeColors
import com.zai.zcode.core.design.ZcodeShape
import com.zai.zcode.core.design.ZcodeSpacing
import com.zai.zcode.core.design.ZcodeType
import com.zai.zcode.core.protocol.v4.ZcodeProtocolV4

/**
 * 模型用量环形图（官方 AppUsageModelUsagePieChart 的 Compose 形态）：
 * 左侧 donut（各模型 share 弧段），右侧图例（色点 + 模型名 + tokens + 占比）。
 * 数据来自 usage-stats.getAppUsageSnapshot 的真实 models 列表。
 */
@Composable
fun ModelUsageDonut(
    models: List<ZcodeProtocolV4.ModelUsage>,
    modifier: Modifier = Modifier,
) {
    val zc = LocalZcodeColors.current
    val total = models.sumOf { it.totalTokens }
    val palette = donutPalette()
    Column(
        modifier
            .fillMaxWidth()
            .clip(ZcodeShape.card)
            .background(zc.card)
            .padding(ZcodeSpacing.md),
        verticalArrangement = Arrangement.spacedBy(ZcodeSpacing.md),
    ) {
        Text("模型用量", style = ZcodeType.uiSm, color = zc.textSecondary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Donut(
                segments = models.mapIndexed { i, m -> (m.share.coerceIn(0f, 1f)) to palette[i % palette.size] },
                centerLabel = "${formatTokensCn(total)}",
                modifier = Modifier.size(120.dp),
            )
            Spacer(Modifier.padding(ZcodeSpacing.sm))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZcodeSpacing.sm)) {
                models.take(5).forEachIndexed { i, m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(palette[i % palette.size]),
                        )
                        Spacer(Modifier.padding(4.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                m.modelId.substringAfterLast("/"),
                                style = ZcodeType.uiXs,
                                color = zc.text,
                                maxLines = 1,
                            )
                            Text(
                                "${formatTokensCn(m.totalTokens)} tokens · ${(m.share * 100).toInt()}%",
                                style = ZcodeType.uiXs,
                                color = zc.textTertiary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Donut：蓝为主色（占比最大项），绿/紫/红/橙顺延 —— 对齐官方截图配色感觉。 */
@Composable
internal fun donutPalette(): List<Color> {
    val zc = LocalZcodeColors.current
    return listOf(
        zc.running,              // 蓝（主力模型）
        Color(0xFF22A06B),       // 绿
        Color(0xFF9B7EDE),       // 紫
        Color(0xFFE5484D),       // 红
        Color(0xFFF5A623),       // 橙
    )
}

@Composable
private fun Donut(
    segments: List<Pair<Float, Color>>,
    centerLabel: String,
    modifier: Modifier = Modifier,
    stroke: Dp = 18.dp,
) {
    val zc = LocalZcodeColors.current
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(120.dp)) {
            val strokePx = stroke.toPx()
            val inset = strokePx / 2
            val arcSize = Size(size.width - strokePx, size.height - strokePx)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = zc.input,
                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(strokePx, cap = StrokeCap.Butt),
            )
            var startAngle = -90f
            segments.forEach { (fraction, color) ->
                val sweep = fraction * 360f
                drawArc(
                    color = color,
                    startAngle = startAngle, sweepAngle = (sweep - 2f).coerceAtLeast(0f), useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = Stroke(strokePx, cap = StrokeCap.Butt),
                )
                startAngle += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerLabel, style = ZcodeType.uiLg, color = zc.text)
            Text("tokens", style = ZcodeType.uiXs, color = zc.textTertiary)
        }
    }
}

/** token 数中文量级展示（亿/万，对齐官方「18.7 亿」口径）。 */
internal fun formatTokensCn(n: Long): String = when {
    n >= 100_000_000 -> "%.1f 亿".format(n / 100_000_000.0)
    n >= 10_000 -> "%.1f 万".format(n / 10_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}
