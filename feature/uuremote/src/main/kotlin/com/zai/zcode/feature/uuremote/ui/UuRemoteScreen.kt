package com.zai.zcode.feature.uuremote.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import com.zai.zcode.core.design.LocalZcodeColors
import com.zai.zcode.core.design.ZcodeShape
import com.zai.zcode.core.design.ZcodeSpacing
import com.zai.zcode.core.design.ZcodeType
import com.zai.zcode.core.design.jellyClickable
import com.zai.zcode.feature.uuremote.UuRemoteInstallationChecker
import com.zai.zcode.feature.uuremote.UuRemoteUiState
import com.zai.zcode.feature.uuremote.UuRemoteViewModel

/**
 * UU 远程页：展示状态机当前态，主按钮按状态切换（安装引导 / 绑定 / 连接 / 重试 / 重绑）。
 * 状态所有者为 UuRemoteViewModel（onResume 重新校验安装与绑定）。
 *
 * 修复依据（v0.2.25）：此前停留在 MaterialTheme（headlineMedium/onSurfaceVariant/
 * Button/OutlinedButton）+ 硬编码 dp，是与 ConnectScreen(v0.2.23) 同类的"换页换皮"残留；
 * 本版对齐 Zcode 令牌，按钮改 48dp 果冻按压，与全 App 触感一致。
 */
@Composable
fun UuRemoteScreen(
    onBack: () -> Unit,
    viewModel: UuRemoteViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val zc = LocalZcodeColors.current
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
            .padding(ZcodeSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZcodeSpacing.lg),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ 返回", color = zc.textSecondary) }
        }
        Text("看电脑", style = ZcodeType.uiXl, color = zc.text)
        Text(
            text = when (val s = state) {
                UuRemoteUiState.NotInstalled -> "尚未安装 UU 远程，安装后可与电脑建立远程画面。"
                UuRemoteUiState.NotBound -> "尚未绑定电脑，点击下方按钮完成绑定。"
                UuRemoteUiState.Binding -> "正在绑定，请在 UU 远程中确认…"
                is UuRemoteUiState.Bound -> "已绑定设备 ${s.deviceId.take(8)}…，点击开始远控。"
                UuRemoteUiState.Connecting -> "正在连接电脑…"
                is UuRemoteUiState.Failed -> s.message
            },
            style = ZcodeType.uiMd,
            color = zc.textSecondary,
        )

        when (val s = state) {
            UuRemoteUiState.NotInstalled -> {
                PrimaryButton(
                    label = "安装 UU 远程",
                    onClick = { viewModel.installGuide(context) },
                )
                // 修复依据（用户报「装了之后还是显示没安装」）：从市场装完回到本页
                // 时 LaunchedEffect(Unit) 不会重跑——提供手动重检入口。
                SecondaryButton(
                    label = "我已安装，重新检测",
                    onClick = {
                        viewModel.onScreenResumed(UuRemoteInstallationChecker.isInstalled(context))
                    },
                )
            }

            UuRemoteUiState.NotBound, UuRemoteUiState.Binding -> PrimaryButton(
                label = "绑定电脑",
                enabled = state is UuRemoteUiState.NotBound,
                loading = state is UuRemoteUiState.Binding,
                onClick = { activity?.let(viewModel::onWatchClicked) },
            )

            is UuRemoteUiState.Bound, UuRemoteUiState.Connecting -> PrimaryButton(
                label = "开始远控",
                enabled = state is UuRemoteUiState.Bound,
                onClick = { activity?.let(viewModel::onWatchClicked) },
            )

            is UuRemoteUiState.Failed -> {
                PrimaryButton(
                    label = if (s.rebindNeeded) "重新绑定" else "重试",
                    onClick = {
                        if (s.rebindNeeded) activity?.let(viewModel::rebind)
                        else activity?.let(viewModel::onWatchClicked)
                    },
                )
                SecondaryButton(
                    label = "刷新状态",
                    onClick = {
                        viewModel.onScreenResumed(UuRemoteInstallationChecker.isInstalled(context))
                    },
                )
            }
        }
    }
}

/** 主操作：中性反色填充（zc.text 底 + zc.base 前景），48dp 触控目标 + 果冻按压。 */
@Composable
private fun PrimaryButton(
    label: String,
    enabled: Boolean = true,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    val zc = LocalZcodeColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .jellyClickable(enabled = enabled && !loading, onClick = onClick, onClickLabel = label)
            .background(if (enabled && !loading) zc.text else zc.input, ZcodeShape.input)
            .padding(horizontal = ZcodeSpacing.lg, vertical = ZcodeSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = zc.textTertiary)
        } else {
            Text(
                label,
                style = ZcodeType.uiMd,
                color = when {
                    !enabled -> zc.textTertiary
                    else -> zc.base
                },
            )
        }
    }
}

/** 次级操作：卡底色 + 主文本，不与主操作抢视觉权重。 */
@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit) {
    val zc = LocalZcodeColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .jellyClickable(onClick = onClick, onClickLabel = label)
            .background(zc.card, ZcodeShape.input)
            .padding(horizontal = ZcodeSpacing.lg, vertical = ZcodeSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = ZcodeType.uiMd, color = zc.text)
    }
}
