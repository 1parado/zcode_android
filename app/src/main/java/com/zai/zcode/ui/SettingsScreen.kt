package com.zai.zcode.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zai.zcode.core.design.LocalZcodeColors
import com.zai.zcode.core.design.ZcodeShape
import com.zai.zcode.core.design.ZcodeSpacing
import com.zai.zcode.core.design.ZcodeType
import com.zai.zcode.core.network.ZcodeConnection

/**
 * 设置页（独立路由）。
 *
 * 之前「设置」按钮直接导航到 CONNECT（连接/授权页），用户报障「点设置跳到配对页」——
 * 远程操控场景下设置是查看本机远控状态，不是重新配对。本页只展示连接信息与版本，
 * 需要换电脑时由用户显式点「重新连接」才退到 CONNECT，而不是点设置就被动清掉会话。
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

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZcodeSpacing.screenPadding, vertical = ZcodeSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ 返回", style = ZcodeType.uiSm) }
            Spacer(Modifier.weight(1f))
            Text("设置", style = ZcodeType.uiXl, color = zc.text)
            Spacer(Modifier.weight(1f))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ZcodeSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(ZcodeSpacing.sm),
        ) {
            SettingRow(label = "连接状态", value = if (connected) "已连接" else "未连接")
            SettingRow(
                label = "服务器",
                value = serverInfo?.serverId ?: "—",
            )
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
