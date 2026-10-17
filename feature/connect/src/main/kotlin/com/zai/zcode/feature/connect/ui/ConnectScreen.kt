package com.zai.zcode.feature.connect.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zai.zcode.core.design.CardCorner
import com.zai.zcode.core.network.ZcodeConnection
import com.zai.zcode.feature.connect.ConnectViewModel

/**
 * 连接页：地址 + Token 表单 → 连接。展示状态机进度与 server-info 摘要。
 * 连接成功后由宿主导航进入会话列表。
 */
@Composable
fun ConnectScreen(
    onConnected: () -> Unit,
    onScanQr: () -> Unit,
    viewModel: ConnectViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // 修复依据（P1-C，评审 2026-10-17）：导航由"本次连接成功"边沿（justConnected）驱动，
    // 而非"当前是 Ready"——否则从 Sessions 点设置重进本页会瞬间被弹回，改不了地址。
    if (state.justConnected) {
        androidx.compose.runtime.LaunchedEffect(state.justConnected) {
            viewModel.consumeJustConnected()
            onConnected()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("连接到 ZCode", style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(
            value = state.serverUrl,
            onValueChange = { v -> viewModel.update { it.copy(serverUrl = v) } },
            label = { Text("服务器地址") },
            placeholder = { Text("http://192.168.1.10:3030") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.token,
            onValueChange = { v -> viewModel.update { it.copy(token = v) } },
            label = { Text("访问 Token（可选）") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedButton(onClick = onScanQr, modifier = Modifier.fillMaxWidth()) {
            Text("扫码配对")
        }

        Button(
            onClick = viewModel::connect,
            enabled = !state.connecting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.connecting) "连接中…" else "连接")
        }

        state.error?.let { err ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(
                    "连接失败：$err（将自动重试）",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        state.serverInfo?.let { info ->
            Card(shape = CardCorner) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("已连接", style = MaterialTheme.typography.titleSmall)
                    Text("版本：${info.version ?: "?"}（协议 v${info.protocolVersion ?: "?"}）",
                        style = MaterialTheme.typography.bodySmall)
                    info.workspaces.forEach { ws ->
                        Text("工作区：${ws.label ?: ws.path}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (state.connecting) {
            Spacer(Modifier.height(8.dp))
            CircularProgressIndicator()
        }
    }
}

/** 状态标签（会话列表顶栏等复用）：连接机状态 → 简短中文。 */
fun stateLabel(state: ZcodeConnection.State): String = when (state) {
    is ZcodeConnection.State.Ready -> "已连接"
    is ZcodeConnection.State.Discovering -> "发现服务…"
    is ZcodeConnection.State.ConnectingWs -> "建立连接…"
    is ZcodeConnection.State.Handshaking -> "握手…"
    is ZcodeConnection.State.Failed -> "重连中…"
    ZcodeConnection.State.Disconnected -> "未连接"
}
