package com.zai.zcode.feature.sessions.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
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
) {
    val zc = LocalZcodeColors.current
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
