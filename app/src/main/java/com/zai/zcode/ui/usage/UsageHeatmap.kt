package com.zai.zcode.ui.usage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zai.zcode.core.design.LocalZcodeColors
import com.zai.zcode.core.design.ZcodeShape
import com.zai.zcode.core.design.ZcodeSpacing
import com.zai.zcode.core.design.ZcodeType
import com.zai.zcode.core.protocol.v4.ZcodeProtocolV4
import java.time.LocalDate

/**
 * Token 活动热力图（官方 packages/ui UsageHeatmap.tsx 的 Compose 形态）。
 *
 * 数据来自 usage-stats.getAppUsageSnapshot 的真实 heatmap；三种视图与官方
 * buildWeeklyLikeHeatmapColumns 同款公式本地聚合（不发新请求）：
 * - 每日：直接用服务端每格 level；
 * - 每周：周内 totalTokens 求和 → level = ceil(v/max*4) 截 1-4，列内自下而上
 *   按 v/max 比例填充格子数；
 * - 累计：周总量前缀和后同法渲染。
 * 网格 7 行（周日起始）× 52 周，水平滚动；无数据日补 0 格对齐自然周。
 */
@Composable
fun UsageHeatmap(
    heatmap: ZcodeProtocolV4.Heatmap?,
    modifier: Modifier = Modifier,
) {
    val zc = LocalZcodeColors.current
    var mode by remember { mutableStateOf("每日") }
    // 点选的格子（详情条显示该日真实 token 数）；mode 切换时清除选中（语义变了）
    var selected by remember { mutableStateOf<SelectedCell?>(null) }
    LaunchedEffect(mode) { selected = null }
    // date -> cell（服务端返回近 range 天真实数据；"all" 时是全量周序列）
    val cellByDate = remember(heatmap) {
        HashMap<String, ZcodeProtocolV4.HeatmapCell>().apply {
            heatmap?.weeks?.forEach { w ->
                w.days.forEach { c -> c?.let { put(it.date, it) } }
            }
        }
    }
    // 补齐 52 周 × 7 天网格（周日起始），空白补 level 0 —— 对齐官方
    // buildDisplayHeatmapWeeks：热力图不因数据范围窄而缩水
    val grid = remember(cellByDate) { build52WeekGrid(cellByDate) }

    Column(
        modifier
            .fillMaxWidth()
            .clip(ZcodeShape.card)
            .background(zc.card)
            .padding(ZcodeSpacing.md),
        verticalArrangement = Arrangement.spacedBy(ZcodeSpacing.sm),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Token 活动", style = ZcodeType.uiSm, color = zc.textSecondary, modifier = Modifier.weight(1f))
            ModeSelector(mode) { mode = it }
        }

        val columns = remember(grid, mode) { buildColumns(grid, cellByDate, mode) }
        Row {
            // 星期轴（官方左侧 日/一/…/六）：只标首、三、五行，11dp 格子放不下三个字
            Column(
                Modifier.padding(end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                val weekLabels = listOf("日", "一", "二", "三", "四", "五", "六")
                weekLabels.forEachIndexed { row, label ->
                    Box(Modifier.size(11.dp), contentAlignment = Alignment.Center) {
                        if (row == 0 || row == 2 || row == 4) {
                            Text(label, style = ZcodeType.uiXs, color = zc.textTertiary)
                        }
                    }
                }
            }
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                columns.forEach { column ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        column.cells.forEach { cell ->
                            // 每格可点：弹出该日真实 token 数（官方 tooltip 的移动端形态）
                            Box(
                                Modifier
                                    .size(11.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(cellColor(cell.level))
                                    .clickable { selected = SelectedCell(cell.date, cell.totalTokens) },
                            )
                        }
                    }
                }
            }
        }
        // 选中格的详情条（点格显示日期 + tokens；点「关闭」收起）。
        // 修复依据（用户截图反馈 2026-10-19）：每周/累计模式的 tokens 是**整周合计**
        // （累计为前缀和），详情却显示格子自己的日期——「10月11日 · 6.1 亿」读起来
        // 像一天的量，语义错误。改为「X月X日当周 · N tokens」/「截至X月X日 · N tokens」。
        selected?.let { cell ->
            val d = runCatching { java.time.LocalDate.parse(cell.date) }.getOrNull()
            val prettyDate = d?.let { "${it.monthValue}月${it.dayOfMonth}日" } ?: cell.date
            val label = when (mode) {
                "每周" -> "$prettyDate 当周 · ${formatTokensCn(cell.totalTokens)} tokens"
                "累计" -> "截至 $prettyDate · ${formatTokensCn(cell.totalTokens)} tokens"
                else -> "$prettyDate · ${formatTokensCn(cell.totalTokens)} tokens"
            } + if (cell.totalTokens <= 0) "（无活动）" else ""
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = ZcodeType.uiXs,
                    color = zc.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "关闭",
                    style = ZcodeType.uiXs,
                    color = zc.running,
                    modifier = Modifier.clickable { selected = null },
                )
            }
        }

        // 月份轴 + 少/多图例
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            columns.forEach { column ->
                Box(Modifier.width(11.dp)) {
                    column.monthLabel?.let {
                        Text(it, style = ZcodeType.uiXs, color = zc.textTertiary)
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            Text("少", style = ZcodeType.uiXs, color = zc.textTertiary)
            listOf(0, 1, 2, 3, 4).forEach { lv ->
                Spacer(Modifier.width(3.dp))
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(cellColor(lv)),
                )
            }
            Spacer(Modifier.width(3.dp))
            Text("多", style = ZcodeType.uiXs, color = zc.textTertiary)
        }
    }
}

/** 分段选择器（每日/每周/累计），官方 UsageHeatmap 右上角同款。 */
@Composable
private fun ModeSelector(current: String, onSelect: (String) -> Unit) {
    val zc = LocalZcodeColors.current
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(zc.input)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf("每日", "每周", "累计").forEach { m ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (current == m) zc.base else Color.Transparent)
                    .clickable { onSelect(m) }
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text(m, style = ZcodeType.uiXs, color = if (current == m) zc.text else zc.textTertiary)
            }
        }
    }
}

/**
 * 热力图专用色阶（官方 styles.css --color-usage-heatmap-0..4 的 Compose 对应）：
 * level 0 是表面底色，1-4 是 sky 蓝按 18/36/58/82% 混入表面的梯度。
 * 官方用 oklab color-mix，这里按两种主题各取一组预混近似值（视觉校准）：
 * 浅色主题表面近白 → 冷浅蓝到饱和蓝；深色主题表面近黑 → 暗蓝到亮蓝。
 */
@Composable
private fun cellColor(level: Int): Color {
    val zc = LocalZcodeColors.current
    val light = listOf(
        Color(0xFFF6F7F8), // 0：近表面
        Color(0xFFD7E7F7), // 1：sky 18%
        Color(0xFFB0CFEF), // 2：sky 36%
        Color(0xFF7FA9DE), // 3：sky 58%
        Color(0xFF2F6BBF), // 4：sky 82%
    )
    val dark = listOf(
        Color(0xFF1E2126), // 0：近表面
        Color(0xFF24384E), // 1
        Color(0xFF2C4E72), // 2
        Color(0xFF3A6BA0), // 3
        Color(0xFF63A0E8), // 4：最亮
    )
    return (if (zc.darkTheme) dark else light)[level.coerceIn(0, 4)]
}

private const val WEEK_COUNT = 52
private const val DAYS_PER_WEEK = 7

/** 52 周 × 7 天日期网格（周日起始），最后列为数据最晚日期所在周。 */
private fun build52WeekGrid(
    cellByDate: Map<String, ZcodeProtocolV4.HeatmapCell>,
): List<List<LocalDate>> {
    val lastDate = cellByDate.keys.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
        .maxOrNull() ?: LocalDate.now()
    val endWeekStart = lastDate.minusDays((lastDate.dayOfWeek.value % 7).toLong()) // Sunday=0
    val start = endWeekStart.minusDays((WEEK_COUNT - 1) * 7L)
    return (0 until WEEK_COUNT).map { w ->
        (0 until DAYS_PER_WEEK).map { d -> start.plusDays((w * DAYS_PER_WEEK + d).toLong()) }
    }
}

/** 选中格详情：日期 + 当日真实 token 数（点击格子弹出）。 */
private data class SelectedCell(val date: String, val totalTokens: Long)

private data class Column(val cells: List<SelectedCellWithLevel>, val monthLabel: String?)

private data class SelectedCellWithLevel(val date: String, val totalTokens: Long, val level: Int)

/** 按模式构建每列 7 格（对齐 UsageHeatmap.tsx 三个 build* 分支）。 */
private fun buildColumns(
    grid: List<List<LocalDate>>,
    cellByDate: Map<String, ZcodeProtocolV4.HeatmapCell>,
    mode: String,
): List<Column> {
    fun cellFor(date: LocalDate): SelectedCellWithLevel {
        val c = cellByDate[date.toString()]
        return SelectedCellWithLevel(date.toString(), c?.totalTokens ?: 0L, c?.level ?: 0)
    }

    return when (mode) {
        "每周", "累计" -> {
            // 周总量（累计模式再做前缀和）
            val weeklyTotals = grid.map { week ->
                week.sumOf { cellByDate[it.toString()]?.totalTokens ?: 0L }
            }
            val values = if (mode == "每周") weeklyTotals else weeklyTotals.runningReduce { acc, v -> acc + v }
            val maxValue = (values.maxOrNull() ?: 0L).coerceAtLeast(0L)
            grid.mapIndexed { w, week ->
                val value = values.getOrElse(w) { 0L }
                val level = if (value <= 0 || maxValue <= 0) 0
                else (kotlin.math.ceil(value.toDouble() / maxValue * 4).toInt()).coerceIn(1, 4)
                val filledRows = if (value <= 0 || maxValue <= 0) 0
                else (kotlin.math.ceil(value.toDouble() / maxValue * DAYS_PER_WEEK).toInt()).coerceIn(1, DAYS_PER_WEEK)
                Column(
                    // 列内自下而上填充（官方 isFilled = dayOffset >= 7 - filledRows）；
                    // 该列所有格共享周总量，点击显示周合计所在日语义不明——日期取该格
                    // 自己的日期、tokens 取 0 以下的说明「（无活动）」由详情条兜底
                    cells = (0 until DAYS_PER_WEEK).map { d ->
                        val date = week[d]
                        val filled = d >= DAYS_PER_WEEK - filledRows
                        if (filled) SelectedCellWithLevel(date.toString(), value, level)
                        else SelectedCellWithLevel(date.toString(), 0L, 0)
                    },
                    monthLabel = monthLabelOf(week),
                )
            }
        }
        else -> grid.map { week ->
            Column(
                cells = week.map { cellFor(it) },
                monthLabel = monthLabelOf(week),
            )
        }
    }
}

/** 周列含某月 1 日时归入该月（官方 resolveHeatmapColumnMonthDate 同规则）。 */
private fun monthLabelOf(week: List<LocalDate>): String? =
    week.firstOrNull { it.dayOfMonth == 1 }?.let { "${it.monthValue}月" }

private inline fun List<Long>.runningReduce(operation: (Long, Long) -> Long): List<Long> {
    if (isEmpty()) return emptyList()
    val out = ArrayList<Long>(size)
    var acc = first()
    out.add(acc)
    for (i in 1 until size) {
        acc = operation(acc, this[i])
        out.add(acc)
    }
    return out
}
