package com.zai.zcode.core.markdown

/**
 * 流式感知块级解析器。参考 MarkdownView 的 parse(text, isStreaming:)：
 * 块按“块前缀 + 行”识别，输出 [MdBlock] 列表。
 *
 * 流式策略（关键）：
 * - 未闭合的 ``` 代码围栏：流式模式下把已到达内容当作代码块尾部渲染，
 *   下个 chunk 重新解析时自然闭合；
 * - 未闭合的 $$ 公式块：同理；
 * - 表格：只要出现表头 + 分隔行即识别为表格，流式追加行只影响 rows 尾部；
 * - stableId 用块首行内容 hash：前缀块跨解析复用（streamIdentity 同思路）。
 */
object MdParser {

    fun parse(text: String, isStreaming: Boolean = false): List<MdBlock> {
        val blocks = ArrayList<MdBlock>()
        val lines = text.split('\n')
        var i = 0
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
                        blocks.add(MdBlock.Heading(sid(trimmed), level, MdInlineParser.parse(rest, isStreaming)))
                        i++
                    } else {
                        i = parseParagraph(lines, i, blocks, isStreaming)
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
                        i = parseParagraph(lines, i, blocks, isStreaming)
                    } else {
                        blocks.add(MdBlock.CodeBlock(sid(trimmed), lang, body))
                        i = if (closed) j + 1 else n
                    }
                }
                // 块级公式 $$
                trimmed.startsWith("$$") -> {
                    val single = trimmed.removePrefix("$$").removeSuffix("$$")
                    if (single.length < trimmed.length - 4 || (trimmed.length >= 4 && trimmed.endsWith("$$") && trimmed != "$$")) {
                        // 单行 $$...$$
                        blocks.add(MdBlock.MathBlock(sid(trimmed), single.trim()))
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
                            i = parseParagraph(lines, i, blocks, isStreaming)
                        } else {
                            blocks.add(MdBlock.MathBlock(sid(trimmed), body.toString().trim()))
                            i = if (closed) j + 1 else n
                        }
                    }
                }
                // 分隔线
                trimmed == "---" || trimmed == "***" -> {
                    blocks.add(MdBlock.Divider(sid(trimmed)))
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
                    blocks.add(MdBlock.Quote(sid(trimmed), parse(quoteLines.joinToString("\n"), isStreaming)))
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
                        val content = stripListMarker(t)
                        val sub = ArrayList<String>()
                        var k = j + 1
                        // 收集缩进续行（简化：两个以上空格开头的行并入本项）
                        while (k < n && lines[k].startsWith("  ") && lines[k].trim().isNotEmpty()) {
                            sub.add(lines[k].trim())
                            k++
                        }
                        val itemText = if (sub.isEmpty()) content else content + "\n" + sub.joinToString("\n")
                        items.add(parse(itemText, isStreaming))
                        j = k
                    }
                    blocks.add(MdBlock.ListBlock(sid(trimmed), ordered, items))
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
                    blocks.add(MdBlock.Table(sid(trimmed), header, rows))
                    i = j
                }
                else -> i = parseParagraph(lines, i, blocks, isStreaming)
            }
        }
        return blocks
    }

    /** 从 [start] 收集一个段落（到空行或下一个块级标记），产出 Paragraph。 */
    private fun parseParagraph(lines: List<String>, start: Int, out: MutableList<MdBlock>, isStreaming: Boolean): Int {
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
            out.add(MdBlock.Paragraph(sid(sb.toString()), MdInlineParser.parse(sb.toString(), isStreaming)))
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

    /** 前缀复用键：内容 hash + 行号前缀，保证同一流内同内容块 id 稳定。 */
    private fun sid(line: String): String = line.hashCode().toString(16)
}
