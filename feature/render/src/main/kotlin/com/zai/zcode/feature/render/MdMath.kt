package com.zai.zcode.feature.render

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zai.zcode.core.design.LocalZcodeColors
import com.zai.zcode.core.design.ZcodeType
import com.zai.zcode.core.markdown.MdBlock
import ru.noties.jlatexmath.JLatexMathDrawable
import ru.noties.jlatexmath.JLatexMathView

/**
 * 块级公式渲染：JLatexMath(GPL-2.0,isolated in this module)。
 * 布局用 fillMaxWidth+wrap，公式居中；失败(非法 latex)回退源码文本。
 *
 * 修复依据（v0.2.25）：回退源码文本此前走 MaterialTheme bodySmall/onSurfaceVariant，
 * 与同文件 MdBlockView 的令牌体系脱节；对齐 ZcodeType.mono + zc.textSecondary。
 */
@Composable
internal fun MdMathBlockView(block: MdBlock.MathBlock, modifier: Modifier = Modifier) {
    val zc = LocalZcodeColors.current
    val latex = block.latex.trim()
    val error = remember(latex) { runCatching { buildDrawable(latex) }.isFailure }
    if (latex.isEmpty() || error) {
        Text(
            "$$latex$$",
            style = ZcodeType.mono.copy(fontFamily = FontFamily.Monospace),
            color = zc.textSecondary,
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        return
    }
    AndroidView(
        factory = { ctx ->
            JLatexMathView(ctx).apply {
                // 修复依据：JLatexMathView 是 fluent setter（textSize/textColor/align），
                // 不是 setTextColor/setLatex(latex, size)
                align(JLatexMathView.ALIGN_CENTER, JLatexMathView.ALIGN_CENTER)
            }
        },
        update = { view ->
            view.textSize(16)
            view.textColor(android.graphics.Color.WHITE)
            view.setLatex(latex)
        },
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
}

private fun buildDrawable(latex: String): JLatexMathDrawable =
    JLatexMathDrawable.builder(latex)
        // CI(run 39016930334)报 textSize 形参是 Float:JLatexMathDrawable builder 用 f 后缀
        .textSize(16f)
        .padding(8)
        .background(0)
        .align(JLatexMathDrawable.ALIGN_CENTER)
        .build()
