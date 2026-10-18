package com.zai.zcode.feature.render

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.zai.zcode.core.design.LocalZcodeColors
import com.zai.zcode.core.design.ZcodeShape
import com.zai.zcode.core.design.ZcodeSpacing
import com.zai.zcode.core.design.ZcodeType
import com.zai.zcode.core.markdown.MdBlock
import com.zai.zcode.core.markdown.MdCodeHighlighter
import com.zai.zcode.core.markdown.MdSpan

/**
 * 单块渲染：每块一个 composable，流式更新只重组变化块。
 * 表格与代码块横滚（不抢占父布局手势，见 ChatScreen 的 pointerInput 拦截说明）。
 *
 * 修复依据（v0.2.25）：此前全部走 MaterialTheme 默认字阶与 colorScheme
 * （titleLarge/bodyMedium/primary/surfaceVariant/outline），是全 App 里唯一一块
 * "每条回复都要路过"却不吃设计令牌的渲染面——列表和输入框换了 Zai 风格，
 * 助手回答里的标题/代码块/表格还是另一套字体层级与紫色强调。本版对齐 ZcodeType /
 * LocalZcodeColors / ZcodeShape，正文统一 uiMd、强调色一律语义色。
 */
@Composable
fun MdBlockView(block: MdBlock, modifier: Modifier = Modifier) {
    when (block) {
        is MdBlock.Paragraph -> MdParagraph(block, modifier)
        is MdBlock.Heading -> MdHeading(block, modifier)
        is MdBlock.CodeBlock -> MdCode(block, modifier)
        is MdBlock.Quote -> MdQuote(block, modifier)
        is MdBlock.ListBlock -> MdList(block, modifier)
        is MdBlock.Table -> MdTable(block, modifier)
        is MdBlock.Divider -> MdDivider(modifier)
        is MdBlock.MathBlock -> MdMathBlockView(block, modifier)
    }
}

// ---------- 标题 ----------

@Composable
private fun MdHeading(block: MdBlock.Heading, modifier: Modifier) {
    val zc = LocalZcodeColors.current
    val style = when (block.level) {
        // Markdown 标题映射：h1→uiXl（页面大标题量级）… h4+→uiMd。
        // 不再借用 Material 的 title/label 层级——字阶必须只有一套。
        1 -> ZcodeType.uiXl
        2 -> ZcodeType.uiLg
        3 -> ZcodeType.uiMd
        else -> ZcodeType.uiMd
    }
    Text(
        text = spansToAnnotated(block.spans),
        style = style,
        fontWeight = FontWeight.Bold,
        color = zc.text,
        modifier = modifier.fillMaxWidth().padding(vertical = ZcodeSpacing.xs),
    )
}

// ---------- 段落 ----------

@Composable
private fun MdParagraph(block: MdBlock.Paragraph, modifier: Modifier) {
    Text(
        text = spansToAnnotated(block.spans),
        style = ZcodeType.uiMd,
        color = LocalZcodeColors.current.text,
        modifier = modifier.fillMaxWidth(),
    )
}

// ---------- 代码块 ----------

private val CODE_KEYWORD = Color(0xFF82AAFF)
private val CODE_STRING = Color(0xFFC3E88D)
private val CODE_COMMENT = Color(0xFF7A7F9A)
private val CODE_NUMBER = Color(0xFFF78C6C)
private val CODE_ANNOTATION = Color(0xFFFFCB6B)

@Composable
private fun MdCode(block: MdBlock.CodeBlock, modifier: Modifier) {
    val zc = LocalZcodeColors.current
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZcodeShape.inner)
            .background(zc.input)
            .padding(ZcodeSpacing.sm),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                block.language ?: "text",
                style = ZcodeType.uiXs,
                color = zc.textTertiary,
            )
            Text(
                "复制",
                style = ZcodeType.uiXs,
                color = zc.queued,
                modifier = Modifier.clickable {
                    clipboard.setText(AnnotatedString(block.lines.joinToString("\n")))
                },
            )
        }
        Spacer(Modifier.height(ZcodeSpacing.xs))
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            block.lines.forEach { line ->
                Text(
                    text = highlightLine(line, block.language),
                    style = ZcodeType.mono,
                    color = zc.text,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 近似高亮 → AnnotatedString（remember 缓存，流式重排不重复 tokenize）。 */
@Composable
private fun highlightLine(line: String, language: String?): AnnotatedString = remember(line, language) {
    buildAnnotatedString {
        // CI(run 39016930334)报 language 可空与 tokenize(String) 不匹配:空语言按 PLAIN 处理
        MdCodeHighlighter.tokenize(line, language ?: "").forEach { tok ->
            val color = when (tok.tok) {
                MdCodeHighlighter.Tok.KEYWORD -> CODE_KEYWORD
                MdCodeHighlighter.Tok.STRING -> CODE_STRING
                MdCodeHighlighter.Tok.COMMENT -> CODE_COMMENT
                MdCodeHighlighter.Tok.NUMBER -> CODE_NUMBER
                MdCodeHighlighter.Tok.ANNOTATION -> CODE_ANNOTATION
                MdCodeHighlighter.Tok.PLAIN -> Color.Unspecified
            }
            if (color == Color.Unspecified) append(tok.text) else withStyle(SpanStyle(color = color)) { append(tok.text) }
        }
    }
}

// ---------- 引用 ----------

@Composable
private fun MdQuote(block: MdBlock.Quote, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZcodeShape.control)
            .background(LocalZcodeColors.current.panel)
            .padding(start = ZcodeSpacing.sm, top = ZcodeSpacing.xs, bottom = ZcodeSpacing.xs, end = ZcodeSpacing.xs),
    ) {
        block.blocks.forEach { MdBlockView(it) }
    }
}

// ---------- 列表 ----------

@Composable
private fun MdList(block: MdBlock.ListBlock, modifier: Modifier) {
    val zc = LocalZcodeColors.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        block.items.forEachIndexed { index, itemBlocks ->
            Row {
                Text(
                    if (block.ordered) "${index + 1}." else "•",
                    style = ZcodeType.uiMd,
                    color = zc.textTertiary,
                    modifier = Modifier.width(24.dp),
                )
                Column(Modifier.weight(1f)) { itemBlocks.forEach { MdBlockView(it) } }
            }
        }
    }
}

// ---------- 表格 ----------

@Composable
private fun MdTable(block: MdBlock.Table, modifier: Modifier) {
    val zc = LocalZcodeColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZcodeShape.inner)
            .background(zc.panel)
            .horizontalScroll(rememberScrollState())
            .padding(ZcodeSpacing.sm),
    ) {
        // 表头
        Row {
            block.header.forEach { cell ->
                Text(
                    spansToAnnotated(cell),
                    style = ZcodeType.uiSm,
                    color = zc.text,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.widthIn(min = 72.dp).padding(horizontal = ZcodeSpacing.sm, vertical = ZcodeSpacing.xs),
                )
            }
        }
        block.rows.forEach { row ->
            Row {
                row.forEach { cell ->
                    Text(
                        spansToAnnotated(cell),
                        style = ZcodeType.uiSm,
                        color = zc.textSecondary,
                        modifier = Modifier.widthIn(min = 72.dp).padding(horizontal = ZcodeSpacing.sm, vertical = ZcodeSpacing.xs),
                    )
                }
            }
        }
    }
}

// ---------- 分隔线 ----------

@Composable
private fun MdDivider(modifier: Modifier) {
    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(LocalZcodeColors.current.border),
    )
}

// ---------- 行内 spans → AnnotatedString ----------

@Composable
internal fun spansToAnnotated(spans: List<MdSpan>): AnnotatedString {
    val zc = LocalZcodeColors.current
    return remember(spans, zc) {
        buildAnnotatedString {
            spans.forEach { span ->
                when (span) {
                    is MdSpan.Plain -> append(span.text)
                    is MdSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) }
                    is MdSpan.Italic -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(span.text) }
                    is MdSpan.Code -> withStyle(
                        SpanStyle(fontFamily = FontFamily.Monospace, background = zc.input),
                    ) { append(" " + span.text + " ") }
                    is MdSpan.Link -> withStyle(SpanStyle(color = zc.queued)) { append(span.text) }
                    is MdSpan.Math -> append(span.text) // 行内公式文字回退（渲染层 MdInline 集成 JLatexMath）
                }
            }
        }
    }
}
