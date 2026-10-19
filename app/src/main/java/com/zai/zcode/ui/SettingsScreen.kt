package com.zai.zcode.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zai.zcode.core.design.LocalZcodeColors
import com.zai.zcode.core.design.ZcodeShape
import com.zai.zcode.core.design.ZcodeSpacing
import com.zai.zcode.core.design.ZcodeType
import com.zai.zcode.core.network.ZcodeConnection
import com.zai.zcode.core.protocol.v4.ZcodeProtocolV4
import com.zai.zcode.ui.usage.DailyModelTrendChart
import com.zai.zcode.ui.usage.ModelUsageDonut
import com.zai.zcode.ui.usage.UsageHeatmap
import com.zai.zcode.ui.usage.UsageRangeTabs
import com.zai.zcode.ui.usage.formatTokensCn

/**
 * 设置页（独立路由）。
 *
 * 之前「设置」按钮直接导航到 CONNECT（连接/授权页），用户报障「点设置跳到配对页」——
 * 远程操控场景下设置是查看本机远控状态，不是重新配对。本页展示连接信息、账号信息
 * （oauth.restoreCachedSession）与使用统计（usage-stats.getAppUsageSnapshot），
 * 需要换电脑时由用户显式点「重新连接」才退到 CONNECT。
 */
@Composable
fun SettingsScreen(
    connection: ZcodeConnection,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
) {
    val zc = LocalZcodeColors.current
    val state by connection.state.collectAsState()
    val serverInfo by connection.serverInfo.collectAsState()
    val currentWorkspace by connection.currentWorkspace.collectAsState()
    val connected = state is ZcodeConnection.State.Ready

    // 账号 + 使用统计：进入页面时拉一次（只读展示，失败静默显示占位）
    var account by remember { mutableStateOf<ZcodeProtocolV4.AccountInfo?>(null) }
    // 官方双快照模式（AppUsagePanel.tsx:37-38）：lifetime("all") 用于热力图与摘要条，
    // range 快照（7d/30d）用于趋势图与模型环形图——随切换重拉。
    var lifetime by remember { mutableStateOf<ZcodeProtocolV4.AppUsageSnapshot?>(null) }
    var usage by remember { mutableStateOf<ZcodeProtocolV4.AppUsageSnapshot?>(null) }
    var usageRange by remember { mutableStateOf("7d") }
    LaunchedEffect(connected) {
        val p = connection.protocol.value ?: return@LaunchedEffect
        account = runCatching { p.cachedAccount() }.getOrNull()
        lifetime = runCatching { p.appUsageSnapshot("all") }.getOrNull()
        usage = lifetime
    }
    LaunchedEffect(connected, usageRange) {
        if (usageRange == "all") {
            usage = lifetime
            return@LaunchedEffect
        }
        val p = connection.protocol.value ?: return@LaunchedEffect
        usage = runCatching { p.appUsageSnapshot(usageRange) }.getOrNull()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // 顶栏重做（与列表页/会话页统一：48dp 图标钮 + 标题居左）——原三元素居中行
        // 的「‹ 返回」TextButton 触达区小且视觉上不像按钮，用户报「点了没反应」。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = ZcodeSpacing.xs, vertical = ZcodeSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = zc.text,
                )
            }
            Spacer(Modifier.width(4.dp))
            Text("设置", style = ZcodeType.uiXl, color = zc.text)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ZcodeSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(ZcodeSpacing.sm),
        ) {
            // ── 账号信息 ──
            SectionTitle("账号")
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(ZcodeShape.card)
                    .background(zc.card)
                    .padding(horizontal = ZcodeSpacing.md, vertical = ZcodeSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 头像占位：官方头像 URL 在 relay 链路不可直连，用首字圆形（不虚占网络）
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .padding(end = ZcodeSpacing.md)
                        .clip(RoundedCornerShape(50))
                        .background(zc.input)
                        .padding(10.dp),
                ) {
                    Text(
                        account?.name?.take(1) ?: "·",
                        style = ZcodeType.uiMd,
                        color = zc.text,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        account?.name ?: "未读取到账号（电脑端未登录或老版本）",
                        style = ZcodeType.uiMd,
                        color = zc.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    account?.email?.let {
                        Text(it, style = ZcodeType.uiXs, color = zc.textTertiary, maxLines = 1)
                    }
                }
            }

            // ── 使用统计（官方双快照口径：摘要/热力图=all lifetime，趋势图/环形图=range）──
            SectionTitle("使用统计")
            if (usage != null) {
                // 时段切换（官方 AppUsageRangeTabs：仅 7d/30d 两档，all 用于摘要）
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    UsageRangeTabs(current = usageRange) { usageRange = it }
                }
                val lifetimeSnap = lifetime
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZcodeSpacing.sm),
                ) {
                    UsageStatCell(Modifier.weight(1f), formatTokensCn(lifetimeSnap?.totalTokens ?: usage!!.totalTokens), "累计 Token 数")
                    UsageStatCell(Modifier.weight(1f), formatTokensCn(lifetimeSnap?.peakDayTokens ?: usage!!.peakDayTokens), "峰值 Token 数")
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZcodeSpacing.sm),
                ) {
                    UsageStatCell(Modifier.weight(1f), "${lifetimeSnap?.currentStreakDays ?: usage!!.currentStreakDays} 天", "当前连续天数")
                    UsageStatCell(Modifier.weight(1f), "${lifetimeSnap?.longestStreakDays ?: usage!!.longestStreakDays} 天", "最长连续天数")
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZcodeSpacing.sm),
                ) {
                    UsageStatCell(Modifier.weight(1f), "${usage!!.totalSessions}", "会话")
                    UsageStatCell(Modifier.weight(1f), "${usage!!.activeDays}", "活跃天数")
                }
                Spacer(Modifier.height(ZcodeSpacing.sm))

                // Token 活动热力图（lifetime heatmap，官方不随 range 变）
                UsageHeatmap(heatmap = lifetimeSnap?.heatmap ?: usage!!.heatmap)

                // 每日模型趋势折线（官方 AppUsageDailyModelTrendChart，随 range 变）
                if (usage!!.dailyModelUsage.isNotEmpty()) {
                    Spacer(Modifier.height(ZcodeSpacing.sm))
                    DailyModelTrendChart(snapshot = usage!!)
                }

                // 模型用量环形图（真实 models 数据，随 range 变）
                if (usage!!.models.isNotEmpty()) {
                    Spacer(Modifier.height(ZcodeSpacing.sm))
                    ModelUsageDonut(models = usage!!.models)
                }

                usage!!.avgTimeToFirstTokenMs?.let { ttft ->
                    SettingRow(label = "平均首字延迟", value = "${ttft / 1000.0}s")
                }
            } else {
                Text(
                    "暂无法读取使用统计（电脑端版本较旧或未产生记录）",
                    style = ZcodeType.uiXs,
                    color = zc.textTertiary,
                    modifier = Modifier.padding(vertical = ZcodeSpacing.sm),
                )
            }

            // ── 连接 ──
            SectionTitle("连接")
            SettingRow(label = "连接状态", value = if (connected) "已连接" else "未连接")
            SettingRow(label = "服务器", value = serverInfo?.serverId ?: "—")
            SettingRow(
                label = "工作区",
                value = currentWorkspace?.label
                    ?: currentWorkspace?.path?.substringAfterLast('\\')?.substringAfterLast('/')
                    ?: "—",
            )
            SettingRow(label = "版本", value = serverInfo?.version ?: "—")

            Spacer(Modifier.height(ZcodeSpacing.lg))

            Text(
                "需要切换到另一台电脑或重新配对时，点下面的按钮进入连接页。",
                style = ZcodeType.uiXs,
                color = zc.textTertiary,
            )
            TextButton(
                onClick = onReconnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(zc.card, ZcodeShape.card)
                    .padding(ZcodeSpacing.md),
            ) {
                Text("重新连接 / 切换电脑", style = ZcodeType.uiMd, color = zc.text)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = ZcodeType.uiXs,
        color = LocalZcodeColors.current.textTertiary,
        modifier = Modifier.padding(top = ZcodeSpacing.sm),
    )
}

@Composable
private fun UsageStatCell(modifier: Modifier, value: String, label: String) {
    val zc = LocalZcodeColors.current
    Column(
        modifier
            .clip(ZcodeShape.card)
            .background(zc.card)
            .padding(horizontal = ZcodeSpacing.md, vertical = ZcodeSpacing.sm),
    ) {
        Text(value, style = ZcodeType.uiLg, color = zc.text)
        Text(label, style = ZcodeType.uiXs, color = zc.textTertiary)
    }
}

/** token 数中文量级展示（亿/万，对齐官方「18.7 亿」口径）——实现在 usage 包。 */
private fun formatTokens(n: Long): String = formatTokensCn(n)

@Composable
private fun SettingRow(label: String, value: String) {
    val zc = LocalZcodeColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(zc.card, ZcodeShape.card)
            .padding(horizontal = ZcodeSpacing.md, vertical = ZcodeSpacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = ZcodeType.uiSm, color = zc.textSecondary)
        Text(
            value,
            style = ZcodeType.uiSm,
            color = zc.text,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(0.62f),
        )
    }
}
