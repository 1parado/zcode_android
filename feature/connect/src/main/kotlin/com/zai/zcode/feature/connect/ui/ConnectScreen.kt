package com.zai.zcode.feature.connect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zai.zcode.core.design.LocalZcodeColors
import com.zai.zcode.core.design.ZcodeShape
import com.zai.zcode.core.design.ZcodeSpacing
import com.zai.zcode.core.design.ZcodeType
import com.zai.zcode.core.design.jellyClickable
import com.zai.zcode.core.network.RelayConnectionParams
import com.zai.zcode.feature.connect.ConnectViewModel

/**
 * 连接页：地址 + Token 表单 → 连接。展示状态机进度与 server-info 摘要。
 * 连接成功后由宿主导航进入会话列表。
 *
 * 修复依据（v0.2.23）：此前这一页是全 App 唯一停留在 MaterialTheme 默认样式的主页面
 * （headlineMedium 字阶、errorContainer 错误卡、Button/OutlinedButton、硬编码 dp）。
 * 它是用户打开 App 看到的第一个页面，与列表/会话页的 Zai 令牌体系不一致会被读成
 * "两个应用"。本版全部对齐 core/design 令牌；主按钮与扫码入口改走果冻按压，
 * 与全 App 触感一致。
 */
@Composable
fun ConnectScreen(
    onConnected: () -> Unit,
    onScanQr: () -> Unit,
    viewModel: ConnectViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    // 扫码配对：页内弹层（不走路由），扫到二维码回填地址并尝试自动连接
    // 修复依据(run 39017131812):fully-qualified remember 配 by 委托无法解析 getValue/setValue,改用正常 import
    var scanning by remember { mutableStateOf(false) }
    // 扫码层内显示的错误/提示信息(非配对码等)
    var qrMessage by remember { mutableStateOf<String?>(null) }
    if (scanning) {
        QrScanScreen(
            onClose = { scanning = false },
            message = qrMessage,
            onScanned = { raw ->
                // relay 直连：桌面端网页远控二维码(zcode.z.ai/remote/v4?sid=...)直接解析
                // 为 relay 连接参数，走 wss://<host>/ws 配对+bridge（用户报障"扫桌面
                // 二维码链接失败"的正解）。解析失败才提示非配对码。
                if (RelayConnectionParams.parse(raw) != null) {
                    viewModel.disconnect()
                    viewModel.update { it.copy(serverUrl = raw.trim(), token = it.token) }
                    viewModel.connect()
                } else {
                    val parsed = parsePairingQr(raw)
                    if (parsed == null) {
                        qrMessage = nonPairingQrHint(raw)
                    } else {
                        val (host, token) = parsed
                        viewModel.update {
                            it.copy(serverUrl = host, token = token ?: it.token)
                        }
                        viewModel.connect()
                    }
                }
            },
        )
        return
    }

    // 修复依据（P1-C，评审 2026-10-17）：导航由"本次连接成功"边沿（justConnected）驱动，
    // 而非"当前是 Ready"——否则从 Sessions 点设置重进本页会瞬间被弹回，改不了地址。
    if (state.justConnected) {
        androidx.compose.runtime.LaunchedEffect(state.justConnected) {
            viewModel.consumeJustConnected()
            onConnected()
        }
    }

    val zc = LocalZcodeColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(ZcodeSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZcodeSpacing.lg),
    ) {
        Text("连接到 ZCode", style = ZcodeType.uiXl, color = zc.text)

        ConnectField(
            value = state.serverUrl,
            onValueChange = { v -> viewModel.update { it.copy(serverUrl = v) } },
            label = "服务器地址",
            placeholder = "http://192.168.1.10:3030",
            keyboardType = KeyboardType.Uri,
        )
        ConnectField(
            value = state.token,
            onValueChange = { v -> viewModel.update { it.copy(token = v) } },
            label = "访问 Token（可选）",
            placeholder = null,
            keyboardType = KeyboardType.Password,
            visualTransformation = PasswordVisualTransformation(),
        )

        // 扫码入口与主按钮同为 48dp 触控目标；次级操作用卡底色，不和主操作抢视觉
        SecondaryButton("扫码配对", onClick = {
            // 优先走宿主导航回调（保留扩展点）；本页直接弹扫码层
            scanning = true
            onScanQr()
        })

        PrimaryButton(
            label = if (state.connecting) "连接中…" else "连接",
            enabled = !state.connecting,
            onClick = {
                // 手动点连接前先停掉上一次的重连循环(用户报障:失败后一直自动重传)
                viewModel.disconnect()
                viewModel.connect()
            },
        )

        state.error?.let { err ->
            // 错误不再套 errorContainer：底色保持中性，文字用语义红——
            // 整卡染红在 Zai 体系里没有对应令牌，且大面积色块让页面显得"挂了"
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(zc.card, ZcodeShape.card)
                    .padding(ZcodeSpacing.md),
            ) {
                Text(
                    "连接失败：$err（将自动重试）",
                    style = ZcodeType.uiSm,
                    color = zc.error,
                )
            }
        }

        state.serverInfo?.let { info ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(zc.card, ZcodeShape.card)
                    .padding(ZcodeSpacing.md),
                verticalArrangement = Arrangement.spacedBy(ZcodeSpacing.xs),
            ) {
                Text("已连接", style = ZcodeType.uiMd, color = zc.text)
                Text(
                    "版本：${info.version ?: "?"}（协议 v${info.protocolVersion ?: "?"}）",
                    style = ZcodeType.uiXs,
                    color = zc.textSecondary,
                )
                info.workspaces.forEach { ws ->
                    Text(
                        "工作区：${ws.label ?: ws.path}",
                        style = ZcodeType.uiXs,
                        color = zc.textSecondary,
                        maxLines = 1,
                    )
                }
            }
        }

        if (state.connecting) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = zc.textTertiary,
                )
            }
        }
        Spacer(Modifier.height(ZcodeSpacing.xl))
    }
}

/**
 * 表单输入：与会话页 [com.zai.zcode.core.design.components.ZcodeComposer] 同一形状语义
 * （20dp 胶囊 + input 底色 + 透明描边）。连接表单和聊天输入框在视觉上是同一类东西——
 * "这里可以打字"——不应因为页签不同而换一套表单语言。
 */
@Composable
private fun ConnectField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String?,
    keyboardType: KeyboardType,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation =
        androidx.compose.ui.text.input.VisualTransformation.None,
) {
    val zc = LocalZcodeColors.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label, style = ZcodeType.uiXs, color = zc.textTertiary) },
        placeholder = placeholder?.let {
            { Text(it, style = ZcodeType.uiSm, color = zc.textTertiary) }
        },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visualTransformation,
        singleLine = true,
        shape = ZcodeShape.input,
        textStyle = ZcodeType.uiMd,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            disabledBorderColor = Color.Transparent,
            focusedContainerColor = zc.input,
            unfocusedContainerColor = zc.input,
            disabledContainerColor = zc.input,
            focusedTextColor = zc.text,
            unfocusedTextColor = zc.text,
            cursorColor = zc.text,
        ),
    )
}

/** 主操作：中性反色（FilterChip 选中态同一语言），48dp 触控目标 + 果冻按压。 */
@Composable
private fun PrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val zc = LocalZcodeColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .jellyClickable(enabled = enabled, onClick = onClick, onClickLabel = label)
            .background(if (enabled) zc.text else zc.input, ZcodeShape.input)
            .padding(horizontal = ZcodeSpacing.lg, vertical = ZcodeSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = ZcodeType.uiMd,
            color = when {
                !enabled -> zc.textTertiary
                // 反色填充上的前景用页面底色：深浅两侧都天然成立，不需要新令牌
                else -> zc.base
            },
        )
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

/**
 * 配对二维码解析：接受两种形态——
 * 1) 纯服务器地址（http(s)://host:port）
 * 2) zcode://connect?host=<address>[&token=<token>]
 * 解析失败返回 null（扫码结果不是配对码，忽略并保持扫码层）。
 */
internal fun parsePairingQr(raw: String): Pair<String, String?>? {
    val s = raw.trim()
    if (s.startsWith("zcode://connect")) {
        val uri = android.net.Uri.parse(s)
        val host = uri.getQueryParameter("host") ?: return null
        return host to uri.getQueryParameter("token")
    }
    return if (s.startsWith("http://") || s.startsWith("https://")) s to null else null
}

/**
 * 非 relay、非局域网配对码的扫码结果提示（relay 分支已在 [RelayConnectionParams.parse]
 * 命中并直接连接，此函数只兜底其余内容）。
 */
internal fun nonPairingQrHint(raw: String): String {
    val isWebRemote = raw.contains("zcode.z.ai/remote") || raw.contains("/remote/v3") || raw.contains("/remote/v4")
    return if (isWebRemote) {
        "这是桌面端的「网页远控」二维码，但缺少配对参数（sid/hash/t），无法直连。\n" +
            "请重新扫码，或手动输入电脑的局域网地址（如 http://192.168.1.10:3030）。"
    } else {
        "二维码内容不是可用的连接地址，请手动输入服务器地址。"
    }
}
