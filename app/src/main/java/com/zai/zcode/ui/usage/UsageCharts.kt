package com.zai.zcode.ui.usage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.zai.zcode.core.design.LocalZcodeColors
import com.zai.zcode.core.design.ZcodeShape
import com.zai.zcode.core.design.ZcodeSpacing
import com.zai.zcode.core.design.ZcodeType
import com.zai.zcode.core.protocol.v4.ZcodeProtocolV4

/** 官方 AppUsageRangeTabs：仅 7d/30d 两档（all 只用于摘要条）。 */
@Composable
internal fun UsageRangeTabs(current: String, onSelect: (String) -> Unit) {
    val zc = LocalZcodeColors.current
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(zc.input)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf("7d" to "近 7 天", "30d" to "近 30 天").forEach { (id, label) ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (current == id) zc.base else Color.Transparent)
                    .clickable { onSelect(id) }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    label,
                    style = ZcodeType.uiXs,
                    color = if (current == id) zc.text else zc.textTertiary,
                )
            }
        }
    }
}

/**
 * 每日模型趋势折线图（官方 AppUsageDailyModelTrendChart 的 Compose 形态）：
 * 前 6 个模型各一条独立折线（非堆叠），Y 轴取可见序列的单日峰值，X 轴稀疏刻度。
 */
@Composable
internal fun DailyModelTrendChart(snapshot: ZcodeProtocolV4.AppUsageSnapshot) {
    val zc = LocalZcodeColors.current
    // 官方 L134：top6 模型（snapshot.models 已按用量降序）
    val topModels = remember(snapshot) { snapshot.models.take(6).map { it.modelId } }
    val palette = donutPalette()
    val series = remember(snapshot, topModels) {
        // modelId -> (date -> tokens)
        topModels.map { modelId ->
            modelId to snapshot.dailyModelUsage.mapNotNull { day ->
                val t = day.models.firstOrNull { it.modelId == modelId }?.totalTokens ?: 0L
                day.date to t
            }
        }
    }
    val dates = remember(snapshot) { snapshot.dailyModelUsage.map { it.date } }
    val maxTokens = remember(series) {
        series.flatMap { it.second }.map { it.second }.maxOrNull() ?: 0L
    }
    if (dates.size < 2 || maxTokens <= 0L) return

    Column(
        Modifier
            .fillMaxWidth()
            .clip(ZcodeShape.card)
            .background(zc.card)
            .padding(ZcodeSpacing.md),
        verticalArrangement = Arrangement.spacedBy(ZcodeSpacing.sm),
    ) {
        Text("每日模型趋势", style = ZcodeType.uiSm, color = zc.textSecondary)
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val w = size.width
            val h = size.height
            val stepX = w / (dates.size - 1)
            // 网格线（3 条）
            listOf(0f, 0.5f, 1f).forEach { f ->
                drawLine(
                    color = zc.input,
                    start = Offset(0f, h * f),
                    end = Offset(w, h * f),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            series.forEachIndexed { si, (_, points) ->
                val color = palette[si % palette.size]
                val path = Path()
                var started = false
                points.forEachIndexed { pi, (_, tokens) ->
                    val x = pi * stepX
                    val y = h * (1f - tokens.toFloat() / maxTokens.toFloat())
                    if (!started) {
                        path.moveTo(x, y)
                        started = true
                    } else {
                        path.lineTo(x, y)
                    }
                }
                drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        // 图例（前 6 模型）。
        // 修复依据（用户截图反馈 2026-10-19）：单行 Row 放不下长模型名（如
        // custom/workbuddy2api/cn:glm-5.3），溢出被压缩成竖排单字。改为两列网格 +
        // 名称截断（限定名去掉 provider 前缀仍超长时按 ellipsis 截断）。
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            topModels.chunked(2).forEach { rowModels ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowModels.forEachIndexed { ri, modelId ->
                        val i = topModels.indexOf(modelId)
                        Row(
                            Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .width(8.dp)
                                    .height(8.dp)
                                    .background(palette[i % palette.size], RoundedCornerShape(2.dp)),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                // 显示限定名（去掉 provider/ 前缀），最长 ~14 字符截断
                                legendLabel(modelId),
                                style = ZcodeType.uiXs,
                                color = zc.textTertiary,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                        if (rowModels.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** 图例名：去掉 provider/ 与命名空间前缀，超长截断到 16 字符。 */
private fun legendLabel(modelId: String): String {
    val short = modelId.substringAfterLast('/')
        .substringAfterLast(':')
    return if (short.length > 16) short.take(15) + "…" else short
}
