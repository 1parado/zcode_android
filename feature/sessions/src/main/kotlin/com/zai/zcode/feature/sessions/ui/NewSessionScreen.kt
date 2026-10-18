package com.zai.zcode.feature.sessions.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zai.zcode.core.design.LocalZcodeColors
import com.zai.zcode.core.design.ZcodeSpacing
import com.zai.zcode.core.design.ZcodeType
import com.zai.zcode.core.design.components.ZcodeComposer
import com.zai.zcode.core.design.jellyClickable

/**
 * 新会话草稿页：先说话，会话才诞生。
 *
 * 修复依据（真机 v0.2.19「创建会话还是不行」）：空会话（draft）在服务端是纯内存态，
 * 不落盘、不进任务索引（zcodeTaskIndexSyncer.ts:675），所以「点 + 立刻建一个空会话」
 * 在产品上等价于「建了一个看不见的东西」，用户只能判定创建失败。参考实现
 * chat_page.dart:674 是同一语义：没有 sessionId 时先让用户输入，首条消息随
 * createSession 一起发出（session-mgmt.ts:105 的 startPromptTurn 才会落盘）。
 *
 * 交互上这同时是 2026 年主流 AI 客户端的形状——首页就是输入框，不先弹一个空房间。
 */
@Composable
fun NewSessionScreen(
    creating: Boolean,
    error: String?,
    onDismissError: () -> Unit,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
    /** 草稿页配置选择（模型/思考/模式），随 createSession 一次性带入。 */
    modelOptions: List<com.zai.zcode.feature.sessions.SessionsViewModel.DraftModelOption> = emptyList(),
    modelLoading: Boolean = false,
    selectedModel: com.zai.zcode.feature.sessions.SessionsViewModel.DraftModelOption? = null,
    onSelectModel: (com.zai.zcode.feature.sessions.SessionsViewModel.DraftModelOption) -> Unit = {},
    selectedMode: String? = null,
    onSelectMode: (String) -> Unit = {},
    selectedThought: String? = null,
    onSelectThought: (String) -> Unit = {},
    modeOptions: List<com.zai.zcode.feature.sessions.SessionsViewModel.ModeOption> = emptyList(),
    thoughtOptions: List<String> = emptyList(),
    thoughtLabel: (String) -> String = { it },
) {
    val zc = LocalZcodeColors.current
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(Modifier.fillMaxSize().imePadding()) {
        // 修复依据（真机点击测试：× 显示在 y≈68、实际命中在 y≈140，差值=状态栏
        // 高度）：enableEdgeToEdge 后内容画进状态栏底下，触摸被系统状态栏区域
        // 拦截——顶栏必须加 statusBarsPadding，图标与命中区才对齐。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = ZcodeSpacing.sm, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Rounded.Close, contentDescription = "取消", tint = zc.textSecondary)
            }
            Box(Modifier.weight(1f))
            if (creating) {
                Text("正在创建…", style = ZcodeType.uiXs, color = zc.textTertiary)
                Spacer(Modifier.size(ZcodeSpacing.md))
            }
        }

        // 空房间不做骨架屏、不做占位气泡：会话还不存在，任何"内容区"都是谎报。
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = ZcodeSpacing.xl),
            ) {
                Text(
                    "新会话",
                    style = ZcodeType.uiXl,
                    color = zc.text,
                )
                Spacer(Modifier.size(ZcodeSpacing.sm))
                Text(
                    "写下第一条指令，会话即刻开始",
                    style = ZcodeType.uiSm,
                    color = zc.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }

        error?.let { msg ->
            Text(
                msg,
                modifier = Modifier
                    .fillMaxWidth()
                    .jellyClickable(onClick = onDismissError)
                    .padding(horizontal = ZcodeSpacing.screenPadding, vertical = ZcodeSpacing.sm),
                style = ZcodeType.uiXs,
                color = zc.error,
            )
        }

        // 草稿页配置行（用户报「新建会话没法选权限/思考/模型」）：与会话页
        // ComposerConfigBar 同风格，选中项随 createSession 一次性带入。
        DraftConfigBar(
            modelOptions = modelOptions,
            modelLoading = modelLoading,
            selectedModel = selectedModel,
            onSelectModel = onSelectModel,
            selectedMode = selectedMode,
            onSelectMode = onSelectMode,
            selectedThought = selectedThought,
            onSelectThought = onSelectThought,
            modeOptions = modeOptions,
            thoughtOptions = thoughtOptions,
            thoughtLabel = thoughtLabel,
        )

        // 修复依据（真机 v0.2.22）：与会话页共用 [ZcodeComposer]。草稿页发出第一条
        // 消息后原地进入会话页，输入框形状必须连续，否则这一瞬间会被读成换了个应用。
        ZcodeComposer(
            value = text,
            onValueChange = { text = it },
            onSend = { onSubmit(text) },
            sendEnabled = text.isNotBlank() && !creating,
            // 草稿页输入的是"第一条指令"，常常是一整段需求说明，给到 6 行
            maxLines = 6,
            focusRequester = focus,
        )
    }
}

/** 草稿页配置 chips：模式 / 思考档位 / 模型（与会话页 ComposerConfigBar 同风格）。 */
@Composable
private fun DraftConfigBar(
    modelOptions: List<com.zai.zcode.feature.sessions.SessionsViewModel.DraftModelOption>,
    modelLoading: Boolean,
    selectedModel: com.zai.zcode.feature.sessions.SessionsViewModel.DraftModelOption?,
    onSelectModel: (com.zai.zcode.feature.sessions.SessionsViewModel.DraftModelOption) -> Unit,
    selectedMode: String?,
    onSelectMode: (String) -> Unit,
    selectedThought: String?,
    onSelectThought: (String) -> Unit,
    modeOptions: List<com.zai.zcode.feature.sessions.SessionsViewModel.ModeOption>,
    thoughtOptions: List<String>,
    thoughtLabel: (String) -> String,
) {
    val zc = LocalZcodeColors.current
    var showModeMenu by remember { mutableStateOf(false) }
    var showThoughtMenu by remember { mutableStateOf(false) }
    var showModelMenu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = ZcodeSpacing.md, vertical = 2.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(ZcodeSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chip(
            icon = Icons.Rounded.Policy,
            text = modeOptions.firstOrNull { it.mode == selectedMode }?.label ?: "变更前确认",
            onClick = { showModeMenu = true },
        )
        DropdownMenu(expanded = showModeMenu, onDismissRequest = { showModeMenu = false }) {
            modeOptions.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(option.label, style = ZcodeType.uiSm, color = zc.text)
                            Text(option.description, style = ZcodeType.uiXs, color = zc.textTertiary)
                        }
                    },
                    leadingIcon = {
                        if (selectedMode == option.mode) {
                            Icon(
                                Icons.Rounded.Check,
                                null,
                                Modifier.size(16.dp),
                                tint = zc.running,
                            )
                        }
                    },
                    onClick = { onSelectMode(option.mode); showModeMenu = false },
                )
            }
        }
        Chip(
            icon = Icons.Rounded.Psychology,
            text = selectedThought?.let(thoughtLabel) ?: "中",
            onClick = { showThoughtMenu = true },
        )
        DropdownMenu(expanded = showThoughtMenu, onDismissRequest = { showThoughtMenu = false }) {
            thoughtOptions.forEach { level ->
                DropdownMenuItem(
                    text = { Text(thoughtLabel(level), style = ZcodeType.uiSm, color = zc.text) },
                    leadingIcon = {
                        if (selectedThought == level) {
                            Icon(
                                Icons.Rounded.Check,
                                null,
                                Modifier.size(16.dp),
                                tint = zc.running,
                            )
                        }
                    },
                    onClick = { onSelectThought(level); showThoughtMenu = false },
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Chip(
            icon = Icons.Rounded.Memory,
            // 模型名可能很长：显示 modelId 最后一段（GLM-5.3 / claude-sonnet-4 等）
            text = selectedModel?.modelId?.substringAfterLast('/')
                ?: if (modelLoading) "读取中…" else "模型",
            onClick = { showModelMenu = true },
        )
        DropdownMenu(expanded = showModelMenu, onDismissRequest = { showModelMenu = false }) {
            if (modelOptions.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(if (modelLoading) "读取中…" else "没有可用模型", style = ZcodeType.uiSm) },
                    onClick = {},
                    enabled = false,
                )
            }
            modelOptions.forEach { option ->
                DropdownMenuItem(
                    text = {
                        // 长模型名两行展示：provider · name 一行放不下
                        Text(
                            option.label,
                            style = ZcodeType.uiSm,
                            color = zc.text,
                            maxLines = 2,
                        )
                    },
                    leadingIcon = {
                        if (selectedModel?.providerId == option.providerId &&
                            selectedModel?.modelId == option.modelId
                        ) {
                            Icon(
                                Icons.Rounded.Check,
                                null,
                                Modifier.size(16.dp),
                                tint = zc.running,
                            )
                        }
                    },
                    onClick = { onSelectModel(option); showModelMenu = false },
                )
            }
        }
    }
}

@Composable
private fun Chip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
) {
    val zc = LocalZcodeColors.current
    Row(
        Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
            .background(zc.input)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = zc.textSecondary)
        Spacer(Modifier.width(5.dp))
        Text(text, style = ZcodeType.uiXs, color = zc.textSecondary, maxLines = 1)
    }
}
