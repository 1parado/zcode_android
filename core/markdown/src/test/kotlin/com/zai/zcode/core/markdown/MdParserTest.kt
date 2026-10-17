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

    // ── 增量解析（v0.2.16）──

    /**
     * 增量解析的等价性：对同一段流式文本按 chunk 逐步喂入，每一步的结果必须与
     * 全量 `MdParser.parse(text, isStreaming=true)` 逐块相等。这是「只解析尾部」
     * 优化不引入偏斜的唯一硬保证，用多种块形态 × 多种切分粒度交叉验证。
     */
    @Test
    fun `incremental parse equals full parse for every chunking`() {
        val samples = listOf(
            "普通段落一直写到结尾。",
            "## 标题\n段落一\n\n段落二",
            "前文\n```kotlin\nfun a() {}\n```\n后文",
            "- 甲\n- 乙\n1. 丙\n2. 丁",
            "| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |",
            "> 引用一\n> 引用二\n\n收尾段落",
            "\$\$\nE=mc^2\n\$\$\n结束",
            "---\n甲\n\n---\n乙",
        )
        for (sample in samples) {
            for (chunk in listOf(1, 2, 3, 7, 1000)) {
                val parser = MdStreamParser()
                var last: List<MdBlock> = emptyList()
                var i = 0
                while (i < sample.length) {
                    val end = minOf(sample.length, i + chunk)
                    val text = sample.substring(0, end)
                    parser.parse(text, isStreaming = true)
                    last = parser.blocks
                    val expected = MdParser.parse(text, isStreaming = true)
                    assertEquals("sample=$sample chunk=$chunk at=$end", expected, last)
                    i = end
                }
                assertEquals(MdParser.parse(sample, isStreaming = true), last)
            }
        }
    }

    @Test
    fun `incremental reuses settled prefix instances`() {
        // 修复依据（v0.2.16）：前缀块必须是**同一批对象实例**，Compose 才能靠
        // equals/引用相等跳过未变化块的重组；每次都新建就失去了缓存意义。
        val parser = MdStreamParser()
        parser.parse("甲\n\n乙\n\n丙", isStreaming = false)
        val before = parser.blocks
        val settled = parser.settledBlockCount
        assertTrue("应有已定稿前缀", settled >= 2)

        parser.parse("甲\n\n乙\n\n丙丁", isStreaming = false)
        val after = parser.blocks
        for (i in 0 until settled) {
            assertTrue("块 $i 应复用同一实例", before[i] === after[i])
        }
    }

    @Test
    fun `non append reset produces same result as fresh parser`() {
        // 文本被替换（不是追加）时必须退化为整篇重解析，结果仍与全量一致
        val parser = MdStreamParser()
        parser.parse("第一段\n\n第二段", isStreaming = false)
        val replaced = "完全不同的内容\n\n另一段"
        parser.parse(replaced, isStreaming = false)
        val got = parser.blocks
        assertEquals(MdParser.parse(replaced, isStreaming = false), got)
    }

    @Test
    fun `settled prefix grows as text arrives`() {
        // 长文流式：定稿前缀随文本增长单调前进 → 每帧解析量与尾部长度成正比，而非全文
        val parser = MdStreamParser()
        var prev = 0
        val sb = StringBuilder()
        repeat(40) { round ->
            sb.append("第 ").append(round).append(" 段文字。\n\n")
            parser.parse(sb.toString(), isStreaming = true)
            val settled = parser.settledBlockCount
            assertTrue("定稿块数不应倒退: $settled < $prev", settled >= prev)
            prev = settled
        }
        assertTrue("长文应累积出大量定稿块，实测 $prev", prev >= 30)
        assertEquals(MdParser.parse(sb.toString(), isStreaming = true), parser.blocks)
    }
}
