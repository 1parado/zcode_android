package com.zai.zcode.core.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MdParserTest {

    @Test
    fun `heading levels`() {
        val blocks = MdParser.parse("## 标题\n正文段落")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MdBlock.Heading && (blocks[0] as MdBlock.Heading).level == 2)
        assertTrue(blocks[1] is MdBlock.Paragraph)
    }

    @Test
    fun `streaming unclosed code fence`() {
        // 流式模式：未闭合 ``` 也要产出代码块
        val blocks = MdParser.parse("前文\n```kotlin\nfun a() {", isStreaming = true)
        assertTrue(blocks.any { it is MdBlock.CodeBlock && it.language == "kotlin" && it.lines.contains("fun a() {") })
        // 非流式：未闭合按段落兜底
        val strict = MdParser.parse("前文\n```kotlin\nfun a() {", isStreaming = false)
        assertTrue(strict.none { it is MdBlock.CodeBlock })
    }

    @Test
    fun `closed code fence`() {
        val blocks = MdParser.parse("```python\nprint(1)\n```")
        val code = blocks.filterIsInstance<MdBlock.CodeBlock>().single()
        assertEquals("python", code.language)
        assertEquals(listOf("print(1)"), code.lines)
    }

    @Test
    fun `table parsing`() {
        val md = "| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |"
        val t = MdParser.parse(md).filterIsInstance<MdBlock.Table>().single()
        assertEquals(2, t.header.size)
        assertEquals(2, t.rows.size)
        assertEquals("a", (t.header[0][0] as MdSpan.Plain).text)
        assertEquals("4", (t.rows[1][1][0] as MdSpan.Plain).text)
    }

    @Test
    fun `streaming table rows append keeps prefix stable`() {
        val t1 = MdParser.parse("| a | b |\n|---|---|\n| 1 | 2 |", isStreaming = true)
            .filterIsInstance<MdBlock.Table>().single()
        val t2 = MdParser.parse("| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |", isStreaming = true)
            .filterIsInstance<MdBlock.Table>().single()
        assertEquals(t1.stableId, t2.stableId)
        assertTrue(t1.rows.size < t2.rows.size)
    }

    @Test
    fun `inline bold italic code link math`() {
        // 修复依据(run 39019873084 CI):Kotlin 字符串里裸写 $x^2$ 会被当作字符串模板,
        // 编译期 Unresolved reference 'x';数学定界符需转义为 \$
        val spans = MdInlineParser.parse("**粗** *斜* `码` [链](https://a.b) \$x^2\$")
        assertEquals(9, spans.size)
        assertTrue(spans[0] is MdSpan.Bold)
        assertTrue(spans[1] is MdSpan.Plain && spans[1].text == " ")
        assertTrue(spans[2] is MdSpan.Italic)
        assertTrue(spans[4] is MdSpan.Code)
        assertTrue(spans[6] is MdSpan.Link && (spans[6] as MdSpan.Link).url == "https://a.b")
        assertTrue(spans[8] is MdSpan.Math)
    }

    @Test
    fun `streaming unclosed bold rendered as literal`() {
        val spans = MdInlineParser.parse("已经 **加粗", isStreaming = true)
        assertTrue(spans.none { it is MdSpan.Bold })
        assertTrue(spans.joinToString("") { it.text }.contains("**"))
    }

    @Test
    fun `block math`() {
        // 修复依据(同上):$$ 同为字符串模板定界符,需转义
        val blocks = MdParser.parse("\$\$\nE=mc^2\n\$\$")
        val m = blocks.filterIsInstance<MdBlock.MathBlock>().single()
        assertEquals("E=mc^2", m.latex)
    }

    @Test
    fun `list with ordered and unordered`() {
        val blocks = MdParser.parse("- a\n- b\n1. x\n2. y")
        val list = blocks.filterIsInstance<MdBlock.ListBlock>()
        assertEquals(2, list.size)
        assertTrue(!list[0].ordered)
        assertTrue(list[1].ordered)
    }

    @Test
    fun `quote nesting`() {
        val blocks = MdParser.parse("> 引用行")
        val q = blocks.filterIsInstance<MdBlock.Quote>().single()
        assertTrue(q.blocks.isNotEmpty())
    }

    @Test
    fun `highlighter keywords and strings`() {
        val toks = MdCodeHighlighter.tokenize("val s = \"hi\" // 尾注", "kotlin")
        assertTrue(toks.any { it.tok == MdCodeHighlighter.Tok.KEYWORD && it.text == "val" })
        assertTrue(toks.any { it.tok == MdCodeHighlighter.Tok.STRING })
        assertTrue(toks.last().tok == MdCodeHighlighter.Tok.COMMENT)
    }

    @Test
    fun `highlighter unknown language falls back plain`() {
        val toks = MdCodeHighlighter.tokenize("some unknown words", "brainfuck")
        assertTrue(toks.all { it.tok == MdCodeHighlighter.Tok.PLAIN })
    }
}
