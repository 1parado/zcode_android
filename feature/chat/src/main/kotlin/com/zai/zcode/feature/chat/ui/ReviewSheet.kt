package com.zai.zcode.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zai.zcode.core.design.LocalZcodeColors
import com.zai.zcode.core.design.ZcodeShape
import com.zai.zcode.core.design.ZcodeSpacing
import com.zai.zcode.core.design.ZcodeType
import com.zai.zcode.feature.chat.ChatViewModel

/**
 * 审查面板（官方 GitPane + GitPaneChangeCard 的移动端形态，ModalBottomSheet 承载）：
 * scope 下拉选择（未暂存/已暂存/全部分支更改）+ 刷新按钮 + 文件列表
 * （文件名 + 目录灰字 + +N/-N 红绿数字 + 展开箭头），展开懒加载 diff patch。
 * 数据走 git 通道 refresh/getDiff（与官方审查面板同一条 RPC 链路）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ReviewSheet(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
) {
    val zc = LocalZcodeColors.current
    val state = viewModel.reviewState
    // 写操作弹层（官方 GitCommitDialog 的移动端形态）
    var showCommitDialog by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }

    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = ZcodeSpacing.screenPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("审查", style = ZcodeType.uiLg, color = zc.text)
                Spacer(Modifier.weight(1f))
                if (state.loading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = zc.textTertiary)
                } else {
                    IconButton(onClick = { viewModel.refreshReview() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.Refresh, "刷新", tint = zc.textSecondary)
                    }
                }
            }
            Spacer(Modifier.height(ZcodeSpacing.sm))

            // scope 下拉（官方 Select：未暂存/已暂存/全部分支更改）
            ScopeDropdown(state) { viewModel.refreshReview(it) }
            state.error?.let {
                Spacer(Modifier.height(ZcodeSpacing.xs))
                Text(it, style = ZcodeType.uiXs, color = zc.error)
            }
            Spacer(Modifier.height(ZcodeSpacing.xs))

            // 写操作行（官方 GitActionMenu：提交为主操作，stage/discard 为次级）
            Row(horizontalArrangement = Arrangement.spacedBy(ZcodeSpacing.sm)) {
                ReviewActionButton(
                    label = "提交…",
                    enabled = state.files.isNotEmpty() && !viewModel.reviewBusy && state.scope != ChatViewModel.ReviewScope.BRANCH,
                    primary = true,
                    modifier = Modifier.weight(1f),
                ) { showCommitDialog = true }
                ReviewActionButton(
                    label = if (state.scope == ChatViewModel.ReviewScope.STAGED) "取消暂存" else "暂存全部",
                    enabled = state.files.isNotEmpty() && !viewModel.reviewBusy && state.scope != ChatViewModel.ReviewScope.BRANCH,
                    modifier = Modifier.weight(1f),
                ) { viewModel.stageAll(state.scope == ChatViewModel.ReviewScope.STAGED) }
                ReviewActionButton(
                    label = "丢弃",
                    enabled = state.files.isNotEmpty() && !viewModel.reviewBusy && state.scope == ChatViewModel.ReviewScope.UNSTAGED,
                    modifier = Modifier.weight(1f),
                ) { showDiscardConfirm = true }
            }
            viewModel.reviewResult?.let {
                Spacer(Modifier.height(ZcodeSpacing.xs))
                Text(it, style = ZcodeType.uiXs, color = zc.textSecondary)
            }
            Spacer(Modifier.height(ZcodeSpacing.sm))

            // 文件列表（空态：当前 scope 无变更）
            if (!state.loading && state.files.isEmpty() && state.error == null) {
                Text(
                    "当前范围没有变更文件",
                    style = ZcodeType.uiSm,
                    color = zc.textTertiary,
                    modifier = Modifier.padding(vertical = ZcodeSpacing.lg),
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(ZcodeSpacing.xs),
            ) {
                state.files.forEach { file ->
                    ReviewFileCard(
                        file = file,
                        workspacePath = "", // 展示只取相对段
                        expanded = viewModel.reviewState.diffs.containsKey(file.path),
                        patch = viewModel.reviewState.diffs[file.path],
                        onToggle = { viewModel.toggleReviewFile(file.path) },
                    )
                }
            }
            Spacer(Modifier.height(ZcodeSpacing.lg))
        }
    }

    // 提交对话框（官方 GitCommitDialog：message 输入 + AI 生成 + 包含未暂存 + 提交/推送）
    if (showCommitDialog) {
        CommitDialog(
            viewModel = viewModel,
            onDismiss = { showCommitDialog = false },
        )
    }
    // 丢弃确认（危险操作，官方 discard 前同样有确认）
    if (showDiscardConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text("丢弃这些更改？", style = ZcodeType.uiMd) },
            text = { Text("未暂存的 ${state.files.size} 个文件的更改将被永久丢弃，无法恢复。", style = ZcodeType.uiSm) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showDiscardConfirm = false
                    viewModel.discardFiles(state.files.map { it.path })
                }) { Text("丢弃", color = zc.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showDiscardConfirm = false }) { Text("取消") }
            },
        )
    }
}

/** 提交对话框：commit message（可 AI 生成）+ 包含未暂存 + 提交 / 提交并推送。 */
@Composable
private fun CommitDialog(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
) {
    val zc = LocalZcodeColors.current
    var message by remember { mutableStateOf("") }
    var includeUnstaged by remember { mutableStateOf(viewModel.reviewState.scope == ChatViewModel.ReviewScope.UNSTAGED) }
    var generating by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("提交更改", style = ZcodeType.uiMd) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ZcodeSpacing.sm)) {
                androidx.compose.material3.OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    placeholder = { Text("提交信息", style = ZcodeType.uiSm, color = zc.textTertiary) },
                    shape = ZcodeShape.input,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { generating = true; viewModel.generateCommitMessage { m ->
                            generating = false
                            if (m != null) message = m
                        } }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (generating) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = zc.textTertiary)
                    } else {
                        Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(14.dp), tint = zc.running)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("AI 生成提交信息", style = ZcodeType.uiSm, color = zc.running)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(
                        checked = includeUnstaged,
                        onCheckedChange = { includeUnstaged = it },
                    )
                    Text("包含未暂存的更改", style = ZcodeType.uiSm, color = zc.textSecondary)
                }
            }
        },
        confirmButton = {
            Row {
                androidx.compose.material3.TextButton(
                    enabled = message.isNotBlank() && !viewModel.reviewBusy,
                    onClick = { onDismiss(); viewModel.commitReview(message, includeUnstaged, pushAfterCommit = false) },
                ) { Text("提交", color = zc.running) }
                androidx.compose.material3.TextButton(
                    enabled = message.isNotBlank() && !viewModel.reviewBusy,
                    onClick = { onDismiss(); viewModel.commitReview(message, includeUnstaged, pushAfterCommit = true) },
                ) { Text("提交并推送", color = zc.running) }
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 审查操作按钮（主操作实心、次级描边）。 */
@Composable
private fun ReviewActionButton(
    label: String,
    enabled: Boolean,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val zc = LocalZcodeColors.current
    Box(
        modifier
            .heightIn(min = 36.dp)
            .clip(ZcodeShape.input)
            .background(if (primary && enabled) zc.text else zc.card)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = ZcodeSpacing.md, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = ZcodeType.uiSm,
            color = when {
                !enabled -> zc.textTertiary
                primary -> zc.base
                else -> zc.text
            },
        )
    }
}

@Composable
private fun ScopeDropdown(
    state: ChatViewModel.ReviewState,
    onSelect: (ChatViewModel.ReviewScope) -> Unit,
) {
    val zc = LocalZcodeColors.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(ZcodeShape.input)
                .background(zc.input)
                .clickable { expanded = true }
                .padding(horizontal = ZcodeSpacing.md, vertical = ZcodeSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                state.scope.label + when (state.scope) {
                    ChatViewModel.ReviewScope.BRANCH -> state.branchLabel?.let { " · $it" } ?: ""
                    else -> "（${state.files.size}）"
                },
                style = ZcodeType.uiSm,
                color = zc.text,
            )
            Spacer(Modifier.width(ZcodeSpacing.xs))
            Icon(
                if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                null,
                Modifier.size(16.dp),
                tint = zc.textTertiary,
            )
        }
        if (expanded) {
            androidx.compose.material3.Card(
                modifier = Modifier.padding(top = 44.dp),
                shape = ZcodeShape.card,
                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = zc.base),
                elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 6.dp),
            ) {
                Column(Modifier.padding(vertical = ZcodeSpacing.xs)) {
                    ChatViewModel.ReviewScope.entries.forEach { scope ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { expanded = false; onSelect(scope) }
                                .padding(horizontal = ZcodeSpacing.md, vertical = ZcodeSpacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                scope.label,
                                style = ZcodeType.uiSm,
                                color = if (scope == state.scope) zc.running else zc.text,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 单文件卡片（官方 GitPaneChangeCard）：文件名 + 目录灰字 + +N/-N + 展开箭头。 */
@Composable
private fun ReviewFileCard(
    file: ChatViewModel.ReviewFile,
    workspacePath: String,
    expanded: Boolean,
    patch: String?,
    onToggle: () -> Unit,
) {
    val zc = LocalZcodeColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(ZcodeShape.card)
            .background(zc.card)
            .clickable(onClick = onToggle)
            .padding(horizontal = ZcodeSpacing.md, vertical = ZcodeSpacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    file.path.substringAfterLast('/').substringAfterLast('\\'),
                    style = ZcodeType.uiSm,
                    color = zc.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val dir = file.path.substringBeforeLast('/').substringBeforeLast('\\')
                if (dir.isNotEmpty() && dir != file.path) {
                    Text(dir, style = ZcodeType.uiXs, color = zc.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(ZcodeSpacing.sm))
            if (file.added > 0) {
                Text("+${file.added}", style = ZcodeType.uiXs, color = Color(0xFF2E9E5B))
                Spacer(Modifier.width(4.dp))
            }
            if (file.removed > 0) {
                Text("-${file.removed}", style = ZcodeType.uiXs, color = Color(0xFFD44747))
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                contentDescription = if (expanded) "收起" else "展开",
                Modifier.size(18.dp),
                tint = zc.textTertiary,
            )
        }
        // 展开：diff patch（等宽字号缩小，横向滚动看长行；loading 态 patch == null）
        if (expanded) {
            Spacer(Modifier.height(ZcodeSpacing.sm))
            when {
                patch == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = zc.textTertiary)
                    Spacer(Modifier.width(ZcodeSpacing.xs))
                    Text("加载 diff…", style = ZcodeType.uiXs, color = zc.textTertiary)
                }
                patch.isEmpty() -> Text("无法读取 diff（文件可能被删除或为二进制）", style = ZcodeType.uiXs, color = zc.textTertiary)
                else -> DiffPatchView(patch)
            }
        }
    }
}

/** 极简 patch 渲染：+ 行绿底、- 行红底、@@ 头弱化（官方 lightweight-diff-preview 的简化）。 */
@Composable
private fun DiffPatchView(patch: String) {
    val zc = LocalZcodeColors.current
    val lines = remember(patch) { patch.split('\n').take(400) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF16181D))
            .horizontalScroll(rememberScrollState())
            .padding(ZcodeSpacing.xs),
    ) {
        lines.forEach { line ->
            val (bg, fg) = when {
                line.startsWith("+++") || line.startsWith("---") -> Color.Transparent to Color(0xFF7F8794)
                line.startsWith("@@") -> Color.Transparent to Color(0xFF6E9CD2)
                line.startsWith("+") -> Color(0xFF1E3A2A) to Color(0xFF9AD8AE)
                line.startsWith("-") -> Color(0xFF3E2326) to Color(0xFFE0A2A6)
                else -> Color.Transparent to Color(0xFFC9CED6)
            }
            Text(
                line.ifEmpty { " " },
                style = ZcodeType.mono.copy(fontFamily = FontFamily.Monospace, fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp)),
                color = fg,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bg)
                    .padding(horizontal = 4.dp),
                maxLines = 1,
            )
        }
    }
}
