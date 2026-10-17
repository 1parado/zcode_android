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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
import com.zai.zcode.core.markdown.MdBlock
import com.zai.zcode.core.markdown.MdCodeHighlighter
import com.zai.zcode.core.markdown.MdSpan

/**
 * 单块渲染：每块一个 composable，流式更新只重组变化块。
 * 表格与代码块横滚（不抢占父布局手势，见 ChatScreen 的 pointerInput 拦截说明）。
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
    val style = when (block.level) {
        1 -> MaterialTheme.typography.titleLarge
        2 -> MaterialTheme.typography.titleMedium
        3 -> MaterialTheme.typography.titleSmall
        else -> MaterialTheme.typography.labelLarge
    }
    Text(
        text = spansToAnnotated(block.spans),
        style = style,
        fontWeight = FontWeight.Bold,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

// ---------- 段落 ----------

@Composable
private fun MdParagraph(block: MdBlock.Paragraph, modifier: Modifier) {
    Text(
        text = spansToAnnotated(block.spans),
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.fillMaxWidth(),
    )
}

// ---------- 代码块 ----------

private val CODE_BG = Color(0x14FFFFFF) // 表面色 8% 白叠加，暗色亮色主题都柔和
private val CODE_KEYWORD = Color(0xFF82AAFF)
private val CODE_STRING = Color(0xFFC3E88D)
private val CODE_COMMENT = Color(0xFF7A7F9A)
private val CODE_NUMBER = Color(0xFFF78C6C)
private val CODE_ANNOTATION = Color(0xFFFFCB6B)

@Composable
private fun MdCode(block: MdBlock.CodeBlock, modifier: Modifier) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CODE_BG)
            .padding(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                block.language ?: "text",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Text(
                "复制",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    clipboard.setText(AnnotatedString(block.lines.joinToString("\n")))
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            block.lines.forEach { line ->
                Text(
                    text = highlightLine(line, block.language),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
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
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
    ) {
        block.blocks.forEach { MdBlockView(it) }
    }
}

// ---------- 列表 ----------

@Composable
private fun MdList(block: MdBlock.ListBlock, modifier: Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        block.items.forEachIndexed { index, itemBlocks ->
            Row {
                Text(
                    if (block.ordered) "${index + 1}." else "•",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .horizontalScroll(rememberScrollState())
            .padding(8.dp),
    ) {
        // 表头
        Row {
            block.header.forEach { cell ->
                Text(
                    spansToAnnotated(cell),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.widthIn(min = 72.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        block.rows.forEach { row ->
            Row {
                row.forEach { cell ->
                    Text(
                        spansToAnnotated(cell),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.widthIn(min = 72.dp).padding(horizontal = 8.dp, vertical = 4.dp),
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
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
    )
}

// ---------- 行内 spans → AnnotatedString ----------

@Composable
internal fun spansToAnnotated(spans: List<MdSpan>): AnnotatedString = remember(spans) {
    buildAnnotatedString {
        spans.forEach { span ->
            when (span) {
                is MdSpan.Plain -> append(span.text)
                is MdSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) }
                is MdSpan.Italic -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(span.text) }
                is MdSpan.Code -> withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, background = CODE_BG),
                ) { append(" " + span.text + " ") }
                is MdSpan.Link -> withStyle(SpanStyle(color = MaterialThemeColor.Link)) { append(span.text) }
                is MdSpan.Math -> append(span.text) // 行内公式文字回退（渲染层 MdInline 集成 JLatexMath）
            }
        }
    }
}

/** 主题无关的链接色（后续接 design token）。 */
private object MaterialThemeColor {
    val Link = Color(0xFF82B1FF)
}
