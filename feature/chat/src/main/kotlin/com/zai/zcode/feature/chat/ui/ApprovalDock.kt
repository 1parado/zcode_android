package com.zai.zcode.feature.chat.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zai.zcode.core.design.LocalZcodeColors
import com.zai.zcode.core.design.ZcodeMotion
import com.zai.zcode.core.design.ZcodeShape
import com.zai.zcode.core.design.ZcodeSpacing
import com.zai.zcode.core.design.ZcodeType
import com.zai.zcode.core.design.jellyClickable
import com.zai.zcode.feature.chat.ChatRepository

/**
 * 审批 / 输入 Dock（docs/ui-design.md §5.3）。
 *
 * 为什么它是这一屏最重要的东西：远程操控的典型姿势是**人不在电脑前**。命令卡在
 * 审批上就永远不动了——手机端能不能放行，决定了这个产品是"能看"还是"能用"。
 *
 * 三条铁律（spec §5.3 + 安全）：
 * 1. **只走服务端下发的 optionId**，不硬编码 allow/deny。服务端演进会让硬编码 id
 *    静默变成别的语义（"deny" 变 "allowAlways" 是最坏的一种）。
 * 2. **逐个步进**：多待决时一次只呈现一个，确认后服务端撤回该项，下一个自动顶上。
 *    不做"全部放行"——一次误触的代价是交出整轮权限。
 * 3. **敏感输入不落草稿**：sensitive=true 时输入框不回填、不记忆。
 */
@Composable
internal fun ApprovalDock(
    interactions: List<ChatRepository.PendingInteraction>,
    resolvingId: String?,
    onSubmit: (interactionId: String, optionId: String?, freeText: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 步进：只呈现队首。确认后服务端撤回该项，列表变短，下一项自动顶上。
    val current = interactions.firstOrNull() ?: return
    val remaining = interactions.size - 1

    Column(
        modifier
            .fillMaxWidth()
            .background(LocalZcodeColors.current.panel)
            .padding(ZcodeSpacing.md),
    ) {
        // 多待决时给出"还有几个"，否则用户以为放行一个就完了。
        if (remaining > 0) {
            Text(
                "还有 $remaining 项待处理",
                style = ZcodeType.uiXs,
                color = LocalZcodeColors.current.textTertiary,
                modifier = Modifier.padding(bottom = ZcodeSpacing.sm),
            )
        }
        // AnimatedContent 换 key：切到下一个待决时内容淡入上滑，不是硬切。
        AnimatedContent(
            targetState = current,
            transitionSpec = {
                (fadeIn(animationSpec = tween(ZcodeMotion.COLLAPSE_MS)) + slideInVertically { it / 4 })
                    .togetherWith(
                        fadeOut(animationSpec = tween(ZcodeMotion.COLLAPSE_MS)) +
                            slideOutVertically { it / 4 },
                    )
            },
            // contentKey 显式给 interactionId：换待决项时按 id 判定"这是另一张卡"，
            // 而不是按数据类相等（服务端补字段会导致同一项也被当成新项重播动画）。
            contentKey = { it.interactionId },
            label = "approvalDockItem",
        ) { item ->
            InteractionCard(
                item = item,
                submitting = resolvingId == item.interactionId,
                onSubmit = onSubmit,
            )
        }
    }
}

@Composable
private fun InteractionCard(
    item: ChatRepository.PendingInteraction,
    submitting: Boolean,
    onSubmit: (interactionId: String, optionId: String?, freeText: String?) -> Unit,
) {
    val zc = LocalZcodeColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(zc.card, ZcodeShape.card)
            .padding(ZcodeSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Lock,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = zc.attention,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                when (item.kind) {
                    "permission" -> "需要授权"
                    "userInput" -> "需要你输入"
                    else -> "需要确认"
                },
                style = ZcodeType.uiXs,
                color = zc.attention,
            )
            item.toolName?.let {
                Spacer(Modifier.width(6.dp))
                Text(
                    it,
                    style = ZcodeType.mono,
                    color = zc.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }

        Spacer(Modifier.height(ZcodeSpacing.sm))
        Text(item.prompt, style = ZcodeType.uiMd, color = zc.text)

        if (item.options.isNotEmpty()) {
            Spacer(Modifier.height(ZcodeSpacing.md))
            // 选项按重要度纵向排列而不是挤成一排：手机上拇指点选，横向小按钮误触率高。
            Column(verticalArrangement = Arrangement.spacedBy(ZcodeSpacing.sm)) {
                item.options.forEach { opt ->
                    OptionButton(
                        label = opt.label,
                        primary = isAllowKind(opt.kind),
                        enabled = !submitting,
                        onClick = { onSubmit(item.interactionId, opt.optionId, null) },
                    )
                }
            }
        }

        if (item.freeText) {
            Spacer(Modifier.height(ZcodeSpacing.sm))
            FreeTextInput(
                sensitive = item.sensitive,
                enabled = !submitting,
                onSubmit = { text -> onSubmit(item.interactionId, null, text) },
            )
        }

        // 服务端没给 options 也不收自由文本：旧 CLI 或形状不认识。
        // 明确说"请在电脑端处理"，而不是渲染一排点不了/点错的按钮。
        if (item.options.isEmpty() && !item.freeText) {
            Spacer(Modifier.height(ZcodeSpacing.sm))
            Text(
                "这项确认的形状本版本还不支持，请在电脑端处理",
                style = ZcodeType.uiXs,
                color = zc.textTertiary,
            )
        }

        if (submitting) {
            Spacer(Modifier.height(ZcodeSpacing.sm))
            Text("提交中…", style = ZcodeType.uiXs, color = zc.textTertiary)
        }
    }
}

/**
 * 哪个选项画成主按钮：服务端 kind 说了算。
 * allowAlways / custom（如 fullAccess）**不给主按钮**——它们交出的权限比这一次大，
 * 视觉上必须和"只放行这一次"区分开，不能让用户顺手点到。
 */
private fun isAllowKind(kind: String?): Boolean = when (kind) {
    "allowOnce" -> true
    "deny" -> false
    "allowAlways", "custom" -> false
    // userInput 的 optionId 没有 kind：它不是放行/拒绝，不按主按钮画。
    else -> false
}

@Composable
private fun OptionButton(
    label: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val zc = LocalZcodeColors.current
    Box(
        Modifier
            .fillMaxWidth()
            // 修复依据（docs/ui-design.md §7 果冻按压）：审批是这一屏最重要的一次点击，
            // 按压缩放让"按下了"在抬指前就被感知到。
            .jellyClickable(enabled = enabled, onClick = onClick, onClickLabel = label)
            .background(
                color = if (primary) zc.attention else zc.input,
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = ZcodeSpacing.md, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = ZcodeType.uiMd,
            color = if (primary) zc.onAttention else zc.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FreeTextInput(
    sensitive: Boolean,
    enabled: Boolean,
    onSubmit: (String) -> Unit,
) {
    // 修复依据（snapshot.ts userInputRequestPayloadSchema.sensitive）：
    // sensitive=true 表示这是口令类输入——不回填、不进历史。remember（非 Saveable）
    // 保证进程重建后不留痕；视觉类型也切成密码。
    var text by remember(sensitive) { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            enabled = enabled,
            singleLine = true,
            textStyle = ZcodeType.uiMd,
            visualTransformation = if (sensitive) {
                androidx.compose.ui.text.input.PasswordVisualTransformation()
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            },
        )
        Spacer(Modifier.width(ZcodeSpacing.sm))
        Box(
            Modifier
                .jellyClickable(
                    enabled = enabled && text.isNotBlank(),
                    onClick = { onSubmit(text); text = "" },
                    onClickLabel = "提交",
                )
                .background(LocalZcodeColors.current.attention, RoundedCornerShape(10.dp))
                .padding(horizontal = ZcodeSpacing.md, vertical = 12.dp),
        ) {
            Text("提交", style = ZcodeType.uiMd, color = LocalZcodeColors.current.onAttention)
        }
    }
}
