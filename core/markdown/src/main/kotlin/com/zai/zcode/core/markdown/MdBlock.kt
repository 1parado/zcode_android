package com.zai.zcode.core.markdown

/**
 * Markdown 块级模型。设计参考 Lakr233/MarkdownView（lody-ios 底层）：
 * 解析结果是与视图解耦的纯数据，流式更新时前面的块按 id 复用、只重建尾部块。
 * 每个块带 [stableId]：同一流式文本的连续解析中，前缀块内容不变则 stableId 不变，
 * 视图层据此跳过已渲染块的重排（对应 MarkdownView 的 streamIdentity 思路）。
 */
sealed interface MdBlock {
    /** 前缀复用键：内容相同的块在流式重解析后保持同一 id。 */
    val stableId: String

    data class Paragraph(override val stableId: String, val spans: List<MdSpan>) : MdBlock

    data class Heading(override val stableId: String, val level: Int, val spans: List<MdSpan>) : MdBlock

    data class CodeBlock(
        override val stableId: String,
        val language: String?,
        val lines: List<String>,
    ) : MdBlock

    data class Quote(override val stableId: String, val blocks: List<MdBlock>) : MdBlock

    data class ListBlock(
        override val stableId: String,
        val ordered: Boolean,
        val items: List<List<MdBlock>>,
    ) : MdBlock

    /** 表格：块级提升为一等块（不全宽提升发生在布局层），超宽由 UI 层横滚。 */
    data class Table(
        override val stableId: String,
        val header: List<List<MdSpan>>,
        val rows: List<List<List<MdSpan>>>,
    ) : MdBlock

    data class Divider(override val stableId: String) : MdBlock

    /** 块级公式 $$...$$，行内 $...$ 在 [MdSpan.Math] 里。 */
    data class MathBlock(override val stableId: String, val latex: String) : MdBlock
}

/**
 * 行内 span：一段带样式的文本节点序列。解析输出保持文本与顺序，
 * 样式（粗体/斜体/代码/链接/公式）以子串切片表达，未闭合的行内语法在
 * 流式模式下按字面渲染（见 [MdInlineParser]）。
 */
sealed interface MdSpan {
    val text: String

    data class Plain(override val text: String) : MdSpan
    data class Bold(override val text: String, val italic: Boolean) : MdSpan
    data class Italic(override val text: String) : MdSpan
    data class Code(override val text: String) : MdSpan
    data class Link(override val text: String, val url: String) : MdSpan
    data class Math(override val text: String) : MdSpan
}
