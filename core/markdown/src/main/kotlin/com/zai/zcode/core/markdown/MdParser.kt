package com.zai.zcode.core.markdown

/**
 * 定稿判定：给定「块终止处的那一行」，判断该块是否**确定不会再变**。
 *
 * @param next 终止行内容（已 trim）；块 consumed 到该行为止
 * @param nextIsLast 该行就是文本的最后一行：它要么还没写完（无结尾 '\n'），
 *   要么是尾随换行产生的空串占位 —— 两种情况它都可能被后续 chunk 改写，
 *   因此依赖终止行内容做判定的块一律不得定稿。
 */
private typealias SettledTest = (next: String, nextIsLast: Boolean) -> Boolean

/**
 * 块级解析器入口对象。
 *
 * 流式策略（关键）：
 * - 未闭合的 ``` 代码围栏：流式模式下把已到达内容当作代码块尾部渲染，
 *   下个 chunk 重新解析时自然闭合；
 * - 未闭合的 $$ 公式块：同理；
 * - 表格：只要出现表头 + 分隔行即识别为表格，流式追加行只影响 rows 尾部；
 * - stableId 用块首行内容 hash：前缀块跨解析复用（streamIdentity 同思路）。
 *
 * 除 `parse` 外还暴露 `parseDetected`：多返回「已定稿的行前缀」元信息，
 * 供 [MdStreamParser] 做增量解析（见该类的注释）。
 */
object MdParser {

    fun parse(text: String, isStreaming: Boolean = false): List<MdBlock> =
        parseDetected(text, isStreaming).blocks

    /**
     * 带前缀定稿信息的解析结果。
     *
     * @property blocks 全量块（与 [parse] 一致）
     * @property safeLineCount 可安全复用的行前缀长度（exclusive）：[0, safeLineCount)
     *   内的行已产出**不会再变**的定稿块，后续文本追加只影响其后的块。
     * @property safePrefixEndChars 第 safeLineCount 行在原文中的字符偏移；
     *   续解析时取 `text.substring(safePrefixEndChars)` 即是未定稿后缀。
     * @property safeBlocks 定稿块（[blocks] 的前缀）。
     */
    data class Parsed(
        val blocks: List<MdBlock>,
        val safeLineCount: Int,
        val safePrefixEndChars: Int,
        val safeBlocks: List<MdBlock>,
    )

    fun parseDetected(text: String, isStreaming: Boolean = false): Parsed {
        val lines = text.split('\n')
        val out = Out(lines)
        parseBlocks(lines, 0, out, isStreaming)
        return out.build()
    }

    /** 解析输出累加器：块 + 该块消耗到的行（exclusive）+ 定稿判定。 */
    private class Out(private val lines: List<String>) {
        val blocks = ArrayList<MdBlock>()
        private val endLines = ArrayList<Int>()
        private val settledTests = ArrayList<SettledTest>()

        fun add(block: MdBlock, endLine: Int, settled: SettledTest) {
            blocks.add(block)
            endLines.add(endLine)
            settledTests.add(settled)
        }

        private fun closedAt(i: Int): Boolean {
            val end = endLines[i]
            // 块一路吸收到文本末尾：它最后一行还可能继续长，一律不定稿
            if (end >= lines.size) return false
            return settledTests[i](lines[end].trim(), end == lines.size - 1)
        }

        fun build(): Parsed {
            var safeIndex = 0
            var safeLine = 0
            for (i in blocks.indices) {
                if (!closedAt(i)) break
                safeIndex = i + 1
                safeLine = endLines[i]
            }
            return Parsed(
                blocks = blocks,
                safeLineCount = safeLine,
                safePrefixEndChars = if (safeLine <= 0) 0 else lineStartOffset(safeLine),
                safeBlocks = if (safeIndex == 0) emptyList() else blocks.subList(0, safeIndex).toList(),
            )
        }

        /** 第 [lineIndex] 行起点在原文中的字符偏移（前 lineIndex 行各自的长度 + '\n'）。 */
        private fun lineStartOffset(lineIndex: Int): Int {
            var chars = 0
            for (k in 0 until lineIndex) chars += lines[k].length + 1
            return chars
        }
    }

    /**
     * 不吸收后续行的块（标题 / 分隔线 / 已闭合围栏 / 单行公式）：
     * 只要它自己最后一行已写完（后面还有行）就定稿，与下一行内容无关。
     */
    private val settledAlways: SettledTest = { _, _ -> true }

    /** 流式未闭合围栏：吸收到文本末尾，永不提前定稿。 */
    private val settledNever: SettledTest = { _, _ -> false }

    private fun parseBlocks(lines: List<String>, start: Int, out: Out, isStreaming: Boolean) {
        var i = start
        val n = lines.size

        while (i < n) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty()) { i++; continue }

            when {
                // 标题 #{1..6}
                trimmed.startsWith("#") -> {
                    val level = run {
                        var l = 0
                        while (l < trimmed.length && trimmed[l] == '#' && l < 6) l++
                        l
                    }
                    val rest = trimmed.drop(level).trimStart()
                    if (level in 1..6 && rest.isNotEmpty()) {
                        out.add(
                            MdBlock.Heading(sid(trimmed), level, MdInlineParser.parse(rest, isStreaming)),
                            i + 1,
                            settledAlways,
                        )
                        i++
                    } else {
                        i = parseParagraph(lines, i, out, isStreaming)
                    }
                }
                // 围栏代码块 ```
                trimmed.startsWith("```") -> {
                    val lang = trimmed.removePrefix("```").trim().takeIf { it.isNotEmpty() }
                    val body = ArrayList<String>()
                    var j = i + 1
                    var closed = false
                    while (j < n) {
                        if (lines[j].trim().startsWith("```")) { closed = true; break }
                        body.add(lines[j])
                        j++
                    }
                    if (!closed && !isStreaming) {
                        // 非流式：未闭合按普通段落兜底
                        i = parseParagraph(lines, i, out, isStreaming)
                    } else {
                        out.add(
                            MdBlock.CodeBlock(sid(trimmed), lang, body),
                            if (closed) j + 1 else n,
                            if (closed) settledAlways else settledNever,
                        )
                        i = if (closed) j + 1 else n
                    }
                }
                // 块级公式 $$
                trimmed.startsWith("$$") -> {
                    val single = trimmed.removePrefix("$$").removeSuffix("$$")
                    if (single.length < trimmed.length - 4 || (trimmed.length >= 4 && trimmed.endsWith("$$") && trimmed != "$$")) {
                        // 单行 $$...$$
                        out.add(MdBlock.MathBlock(sid(trimmed), single.trim()), i + 1, settledAlways)
                        i++
                    } else {
                        val body = StringBuilder()
                        var j = i + 1
                        var closed = false
                        while (j < n) {
                            if (lines[j].trim().endsWith("$$")) {
                                body.append(lines[j].trim().removeSuffix("$$"))
                                closed = true
                                break
                            }
                            body.append(lines[j]).append('\n')
                            j++
                        }
                        if (!closed && !isStreaming) {
                            i = parseParagraph(lines, i, out, isStreaming)
                        } else {
                            out.add(
                                MdBlock.MathBlock(sid(trimmed), body.toString().trim()),
                                if (closed) j + 1 else n,
                                if (closed) settledAlways else settledNever,
                            )
                            i = if (closed) j + 1 else n
                        }
                    }
                }
                // 分隔线
                trimmed == "---" || trimmed == "***" -> {
                    out.add(MdBlock.Divider(sid(trimmed)), i + 1, settledAlways)
                    i++
                }
                // 引用 >
                trimmed.startsWith(">") -> {
                    val quoteLines = ArrayList<String>()
                    var j = i
                    while (j < n && lines[j].trim().startsWith(">")) {
                        quoteLines.add(lines[j].trim().removePrefix(">").removePrefix(" "))
                        j++
                    }
                    val inner = Out(quoteLines)
                    parseBlocks(quoteLines, 0, inner, isStreaming)
                    // 修复依据（v0.2.16 增量解析）：引用会一直吞掉后续 `>` 行，必须按
                    // "下一行不是 > " 定稿，否则增量结果会把一段引用拆成多个块。
                    out.add(MdBlock.Quote(sid(trimmed), inner.blocks), j) { next, last ->
                        !last && !next.startsWith(">")
                    }
                    i = j
                }
                // 列表 - / * / 1.
                isListItem(trimmed) -> {
                    val ordered = trimmed.length > 1 && trimmed[0].isDigit()
                    val items = ArrayList<List<MdBlock>>()
                    var j = i
                    while (j < n) {
                        val t = lines[j].trim()
                        if (!isListItem(t)) break
                        // 修复依据(run 39020330064 CI):有序与无序是两个列表块,
                        // 遇到 ordered 属性变化要断开,不能全部并入同一个 ListBlock
                        val isOrdered = t.length > 1 && t[0].isDigit()
                        if (items.isNotEmpty() && isOrdered != ordered) break
                        val content = stripListMarker(t)
                        val sub = ArrayList<String>()
                        var k = j + 1
                        // 收集缩进续行（简化：两个以上空格开头的行并入本项）
                        while (k < n && lines[k].startsWith("  ") && lines[k].trim().isNotEmpty()) {
                            sub.add(lines[k].trim())
                            k++
                        }
                        val itemText = if (sub.isEmpty()) content else content + "\n" + sub.joinToString("\n")
                        val itemLines = itemText.split('\n')
                        val itemOut = Out(itemLines)
                        parseBlocks(itemLines, 0, itemOut, isStreaming)
                        items.add(itemOut.blocks)
                        j = k
                    }
                    // 列表同样会在「下一项 ordered 属性变化」处断开，此时本块已定稿
                    out.add(MdBlock.ListBlock(sid(trimmed), ordered, items), j) { next, last ->
                        !last && (!isListItem(next) || (next.length > 1 && next[0].isDigit()) != ordered)
                    }
                    i = j
                }
                // 表格：|...| 行后跟 |---|---|
                trimmed.startsWith("|") && i + 1 < n && isTableSeparator(lines[i + 1].trim()) -> {
                    val header = splitTableRow(trimmed).map { MdInlineParser.parse(it.trim(), isStreaming) }
                    val rows = ArrayList<List<List<MdSpan>>>()
                    var j = i + 2
                    while (j < n && lines[j].trim().startsWith("|")) {
                        rows.add(splitTableRow(lines[j].trim()).map { MdInlineParser.parse(it.trim(), isStreaming) })
                        j++
                    }
                    // 表格会持续吞掉后续 `|` 行 → 只有下一行不是 `|` 才算定稿
                    out.add(MdBlock.Table(sid(trimmed), header, rows), j) { next, last ->
                        !last && !next.startsWith("|")
                    }
                    i = j
                }
                else -> i = parseParagraph(lines, i, out, isStreaming)
            }
        }
    }

    /** 从 [start] 收集一个段落（到空行或下一个块级标记），产出 Paragraph。 */
    private fun parseParagraph(lines: List<String>, start: Int, out: Out, isStreaming: Boolean): Int {
        val sb = StringBuilder()
        var i = start
        val n = lines.size
        while (i < n) {
            val t = lines[i].trim()
            if (t.isEmpty() || startsBlock(t)) break
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(t)
            i++
        }
        if (sb.isNotEmpty()) {
            // 修复依据（v0.2.16 增量解析）：段落终止于「空行」或「块起始行」，但这个
            // 终止行必须是真实完整的行——尾随换行产生的空串占位不能算，否则
            // "甲\n乙\n" + "丙" 时本该合并成一段的会被提前定稿成两段。
            out.add(
                MdBlock.Paragraph(sid(sb.toString()), MdInlineParser.parse(sb.toString(), isStreaming)),
                i,
            ) { next, last -> !last && (next.isEmpty() || startsBlock(next)) }
        }
        return if (i == start) start + 1 else i
    }

    private fun startsBlock(t: String): Boolean =
        t.startsWith("#") || t.startsWith("```") || t.startsWith(">") || t == "---" || t == "***" ||
            t.startsWith("$$") || isListItem(t) || (t.startsWith("|") && t.length > 1)

    private fun isListItem(t: String): Boolean =
        (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ")) ||
            (t.length > 2 && t[0].isDigit() && t.substringBefore(' ')!!.matches(Regex("^\\d+\\.$")) && t.contains(' '))

    private fun stripListMarker(t: String): String =
        if (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ")) t.substring(2)
        else t.substringAfter(' ', "")

    private fun isTableSeparator(t: String): Boolean =
        t.startsWith("|") && t.replace("|", "").replace("-", "").replace(":", "").replace(" ", "").isEmpty() &&
            t.contains("-")

    private fun splitTableRow(t: String): List<String> {
        var s = t
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|")) s = s.substring(0, s.length - 1)
        return s.split('|')
    }

    /** 前缀复用键：块首行内容 hash，保证同一流内同内容块 id 稳定。 */
    private fun sid(line: String): String = line.hashCode().toString(16)
}
