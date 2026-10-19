package com.zai.zcode.core.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zai.zcode.core.design.LocalZcodeColors
import com.zai.zcode.core.design.ZcodeShape
import com.zai.zcode.core.design.ZcodeSpacing
import com.zai.zcode.core.design.ZcodeType
import com.zai.zcode.core.design.jellyClickable

/**
 * 输入栏：会话页与新建会话页共用（docs/ui-design.md §5.3）。
 *
 * 抽出来的理由不是"少写代码"，而是**两处必须长得一样**：用户从新建页发出第一条消息
 * 后原地进入会话页，输入框若换了形状、圆角或按钮位置，这一瞬间会被读成"跳到了另一个
 * 应用"。共用一份组件才能保证这个转场是连续的。
 *
 * 形状决策：
 * - 输入框走 [ZcodeShape.input]（20dp 圆角）而不是 Material 默认的 4dp 描边方框。
 *   2026 年主流 AI 客户端的输入区是一个"胶囊容器"，视觉上明示"这里可以打字"；
 *   描边方框是表单语言的残留，在聊天场景里显得生硬。
 * - 描边去掉（透明）：底色 [LocalZcodeColors.input] 已经把边界说清楚了，再描一道边
 *   是重复表达，浅色主题下还会显脏。
 * - 按钮 48dp 触控目标（[ZcodeSpacing.touch] 量级），发送是全 App 最高频交互。
 *
 * 状态机：运行中且 [onStop] 非空 → ■ 停止；否则 → ↑ 发送（[sendEnabled] 才亮）。
 */
@Composable
fun ZcodeComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "发送消息…",
    sendEnabled: Boolean = true,
    onStop: (() -> Unit)? = null,
    maxLines: Int = 4,
    focusRequester: FocusRequester? = null,
    /** 输入框左侧附加动作（如附件选择，官方 composer 的 + 按钮）。null 不占位。 */
    leadingAction: (@Composable () -> Unit)? = null,
) {
    val zc = LocalZcodeColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(ZcodeSpacing.md),
        verticalAlignment = Alignment.Bottom,
    ) {
        leadingAction?.invoke()
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                // 收起时是一行高度，展开到 maxLines 后停住：不设上限的话贴一段长文本
                // 会把整个输入栏顶成半屏，把聊天内容挤没。
                .heightIn(min = 48.dp)
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it },
            placeholder = { Text(placeholder, style = ZcodeType.uiSm, color = zc.textTertiary) },
            maxLines = maxLines,
            textStyle = ZcodeType.uiMd,
            shape = ZcodeShape.input,
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
        // 停止与发送互斥占同一个位置：两个按钮来回换会让输入框宽度跟着抖。
        // 运行中优先给停止——此时"让它停下"比"再说一句"重要得多。
        if (onStop != null) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .jellyClickable(onClick = onStop, onClickLabel = "停止"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Stop,
                    contentDescription = "停止",
                    tint = zc.error,
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    // 修复依据（docs/ui-design.md §7 果冻按压）：按压缩放让「按下了」
                    // 在抬指之前就被感知到，发送这种高频交互尤其明显。
                    .jellyClickable(enabled = sendEnabled, onClick = onSend, onClickLabel = "发送"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.ArrowUpward,
                    contentDescription = "发送",
                    tint = if (sendEnabled) zc.text else zc.textTertiary,
                )
            }
        }
    }
}
