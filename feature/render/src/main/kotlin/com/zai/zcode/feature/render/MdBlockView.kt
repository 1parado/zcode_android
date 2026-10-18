package com.zai.zcode.feature.render

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
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

// 修复依据（暗色主题对比度）：原先五个固定色在浅色主题的浅底（zc.input 近白）上
// 对比度不足（如 C3E88D 浅绿字在白底上几乎不可读）。配色跟随主题：
// 浅色用 Material Light 配方、暗色用 Palenight 配方，均针对各自背景调过对比。
@Composable
private fun codeColors(): CodePalette {
    val zc = LocalZcodeColors.current
    // isDark 判据：设计令牌没有显式 isSystemInDarkTheme 透出，用正文色亮度近似——
    // 暗色主题的 zc.text 接近白（亮度高），浅色主题接近黑（亮度低）。
    val isDark = zc.text.luminance() > 0.5f
    return if (isDark) CodePalette(
        keyword = Color(0xFF82AAFF), string = Color(0xFFC3E88D), comment = Color(0xFF697098),
        number = Color(0xFFF78C6C), annotation = Color(0xFFFFCB6B),
    ) else CodePalette(
        keyword = Color(0xFF3B5BC0), string = Color(0xFF2E7D32), comment = Color(0xFF8A8F9C),
        number = Color(0xFFC25107), annotation = Color(0xFF9A6B00),
    )
}

private data class CodePalette(
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val annotation: Color,
)

@Composable
private fun MdCode(block: MdBlock.CodeBlock, modifier: Modifier) {
    val zc = LocalZcodeColors.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
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
                if (copied) "已复制 ✓" else "复制",
                style = ZcodeType.uiXs,
                color = if (copied) zc.running else zc.queued,
                modifier = Modifier.clickable {
                    clipboard.setText(AnnotatedString(block.lines.joinToString("\n")))
                    copied = true
                },
            )
        }
        LaunchedEffect(copied) {
            if (copied) {
                kotlinx.coroutines.delay(1_500)
                copied = false
            }
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
private fun highlightLine(line: String, language: String?): AnnotatedString {
    val palette = codeColors()
    return remember(line, language, palette) {
        buildAnnotatedString {
            // CI(run 39016930334)报 language 可空与 tokenize(String) 不匹配:空语言按 PLAIN 处理
            MdCodeHighlighter.tokenize(line, language ?: "").forEach { tok ->
                val color = when (tok.tok) {
                    MdCodeHighlighter.Tok.KEYWORD -> palette.keyword
                    MdCodeHighlighter.Tok.STRING -> palette.string
                    MdCodeHighlighter.Tok.COMMENT -> palette.comment
                    MdCodeHighlighter.Tok.NUMBER -> palette.number
                    MdCodeHighlighter.Tok.ANNOTATION -> palette.annotation
                    MdCodeHighlighter.Tok.PLAIN -> Color.Unspecified
                }
                if (color == Color.Unspecified) append(tok.text) else withStyle(SpanStyle(color = color)) { append(tok.text) }
            }
        }
    }
}

// ---------- 引用 ----------

@Composable
private fun MdQuote(block: MdBlock.Quote, modifier: Modifier) {
    val zc = LocalZcodeColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZcodeShape.control)
            .background(zc.panel),
    ) {
        // 左侧竖条是 Markdown 引用的通用视觉语言；用边框色弱化，不抢正文
        Spacer(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(zc.border),
        )
        Column(
            Modifier
                .padding(start = ZcodeSpacing.sm, top = ZcodeSpacing.xs, bottom = ZcodeSpacing.xs, end = ZcodeSpacing.xs)
                .weight(1f),
        ) {
            block.blocks.forEach { MdBlockView(it) }
        }
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

/**
 * 表格渲染：表头加重 + 分隔线 + 列对齐。
 *
 * 修复依据（用户报「渲染不美观」）：原先表格只是同色 Row 堆叠，表头与数据行
 * 视觉上无区分，单元格之间没有分隔线，长表格读起来串行。改为：表头底部一条
 * 边框线加重，数据行之间用细分隔线（border 色，足够弱不抢内容）。
 * （列宽对齐依赖解析器侧对齐信息，当前 parser 未透出 alignment，先以等权
 * widthIn(min=88.dp) 保证窄屏可读、宽屏不挤压；横向滚动由外层承担。）
 */
@Composable
private fun MdTable(block: MdBlock.Table, modifier: Modifier) {
    val zc = LocalZcodeColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZcodeShape.inner)
            .background(zc.panel)
            .horizontalScroll(rememberScrollState()),
    ) {
        // 表头
        Row {
            block.header.forEach { cell ->
                Text(
                    spansToAnnotated(cell),
                    style = ZcodeType.uiSm,
                    color = zc.text,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .widthIn(min = 88.dp)
                        .background(zc.input)
                        .padding(horizontal = ZcodeSpacing.sm, vertical = ZcodeSpacing.xs),
                )
            }
        }
        // 表头下分隔线：比行间线重一档，视觉上把"列名"与"数据"分开
        Spacer(
            Modifier.fillMaxWidth().height(1.dp).background(zc.border),
        )
        block.rows.forEachIndexed { index, row ->
            if (index > 0) {
                Spacer(Modifier.fillMaxWidth().height(0.5.dp).background(zc.border))
            }
            Row {
                row.forEach { cell ->
                    Text(
                        spansToAnnotated(cell),
                        style = ZcodeType.uiSm,
                        color = zc.textSecondary,
                        modifier = Modifier.widthIn(min = 88.dp).padding(horizontal = ZcodeSpacing.sm, vertical = ZcodeSpacing.xs),
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

/**
 * 行内 spans → AnnotatedString。
 *
 * 链接处理（安全性 + 可用性）：
 * - **URL scheme 白名单**：只放行 http/https/mailto。markdown 里 `[]()`的 url 是任意
 *   字符串，`javascript:`/`intent:`/`file:` 等 scheme 在移动端是真实攻击面
 *   （intent: 可以拉起任意组件），渲染时必须过滤。
 * - **点击打开**：LinkAnnotation.Url 是 Compose 1.7+ 的官方点击 API，Text 的
 *   linkStyle 拦截手势，无需外层 detectTapGestures。
 */
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
                    is MdSpan.Link -> {
                        val safe = span.url.trim().lowercase().let { u ->
                            u.startsWith("http://") || u.startsWith("https://") ||
                                u.startsWith("mailto:")
                        }
                        if (safe) {
                            withLink(LinkAnnotation.Url(
                                span.url,
                                TextLinkStyles(style = SpanStyle(color = zc.queued, textDecoration = TextDecoration.Underline)),
                            )) { append(span.text) }
                        } else {
                            // 不安全 scheme：降级为纯文本展示，不渲染成链接样式
                            withStyle(SpanStyle(color = zc.textSecondary)) { append(span.text) }
                        }
                    }
                    is MdSpan.Math -> append(span.text) // 行内公式文字回退（渲染层 MdInline 集成 JLatexMath）
                }
            }
        }
    }
}
