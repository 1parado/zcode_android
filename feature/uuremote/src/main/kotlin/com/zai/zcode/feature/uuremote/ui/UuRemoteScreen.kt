package com.zai.zcode.feature.uuremote.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zai.zcode.feature.uuremote.UuRemoteInstallationChecker
import com.zai.zcode.feature.uuremote.UuRemoteUiState
import com.zai.zcode.feature.uuremote.UuRemoteViewModel

/**
 * UU 远程页：展示状态机当前态，主按钮按状态切换（安装引导 / 绑定 / 连接 / 重试 / 重绑）。
 * 状态所有者为 UuRemoteViewModel（onResume 重新校验安装与绑定）。
 */
@Composable
fun UuRemoteScreen(
    onBack: () -> Unit,
    viewModel: UuRemoteViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity

    // 每次进入/回到本页时重新校验安装与绑定状态
    LaunchedEffect(Unit) {
        viewModel.onScreenResumed(UuRemoteInstallationChecker.isInstalled(context))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ 返回") }
        }
        Text("看电脑", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = when (val s = state) {
                UuRemoteUiState.NotInstalled -> "尚未安装 UU 远程，安装后可与电脑建立远程画面。"
                UuRemoteUiState.NotBound -> "尚未绑定电脑，点击下方按钮完成绑定。"
                UuRemoteUiState.Binding -> "正在绑定，请在 UU 远程中确认…"
                is UuRemoteUiState.Bound -> "已绑定设备 ${s.deviceId.take(8)}…，点击开始远控。"
                UuRemoteUiState.Connecting -> "正在连接电脑…"
                is UuRemoteUiState.Failed -> s.message
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (val s = state) {
            UuRemoteUiState.NotInstalled -> Button(
                onClick = { viewModel.installGuide(context) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("安装 UU 远程") }

            UuRemoteUiState.NotBound, UuRemoteUiState.Binding -> Button(
                onClick = { activity?.let(viewModel::onWatchClicked) },
                enabled = state is UuRemoteUiState.NotBound,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state is UuRemoteUiState.Binding) {
                    CircularProgressIndicator(Modifier.padding(end = 8.dp))
                }
                Text("绑定电脑")
            }

            is UuRemoteUiState.Bound, UuRemoteUiState.Connecting -> Button(
                onClick = { activity?.let(viewModel::onWatchClicked) },
                enabled = state is UuRemoteUiState.Bound,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("开始远控") }

            is UuRemoteUiState.Failed -> {
                Button(
                    onClick = {
                        if (s.rebindNeeded) activity?.let(viewModel::rebind)
                        else activity?.let(viewModel::onWatchClicked)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (s.rebindNeeded) "重新绑定" else "重试") }
                OutlinedButton(
                    onClick = {
                        viewModel.onScreenResumed(UuRemoteInstallationChecker.isInstalled(context))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("刷新状态") }
            }
        }
    }
}
