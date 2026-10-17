package com.zai.zcode.core.markdown

/**
 * 行内解析：输出 [MdSpan] 序列。支持 **bold** / *italic* / `code` /
 * [text](url) / $math$。策略与 MarkdownView 一致：只认精确匹配的简单语法，
 * 不追求 CommonMark 完整性（快速、可预测、流式友好）。
 *
 * 流式感知：isStreaming=true 时，落在文本末尾且未闭合的标记按字面量输出，
 * 下一个 chunk 到来重新解析即可自然闭合 —— 前面的已闭合 span 不受影响。
 */
object MdInlineParser {

    fun parse(text: String, isStreaming: Boolean = false): List<MdSpan> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<MdSpan>()
        var i = 0
        val n = text.length
        val plain = StringBuilder()

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                out.add(MdSpan.Plain(plain.toString()))
                plain.clear()
            }
        }

        while (i < n) {
            val c = text[i]
            when {
                // **bold** 或 ***bold-italic***
                c == '*' && i + 1 < n && text[i + 1] == '*' -> {
                    val stars = run {
                        var s = 0
                        while (i + s < n && text[i + s] == '*') s++
                        s
                    }
                    // 闭合串找相同长度：**…** / ***…***
                    if (stars >= 2) {
                        val close = findSeq(text, i + stars, '*'.code, stars)
                        if (close >= 0) {
                            flushPlain()
                            val inner = text.substring(i + stars, close)
                            val italic = inner.length >= 2 && inner.startsWith("*") && inner.endsWith("*")
                            val core = if (italic) inner.substring(1, inner.length - 1) else inner
                            out.add(
                                if (italic) MdSpan.Bold(core, italic = true)
                                else MdSpan.Bold(core, italic = false)
                            )
                            i = close + stars
                            continue
                        }
                    }
                    plain.append(text, i, minOf(i + stars, n))
                    i += stars
                }
                // *italic*
                c == '*' -> {
                    // 单星：找下一个非**开头的单星
                    var j = i + 1
                    var close = -1
                    while (j < n) {
                        if (text[j] == '*') {
                            // 跳过 **（那是 bold 的边界）
                            if (j + 1 < n && text[j + 1] == '*') { j += 2; continue }
                            close = j
                            break
                        }
                        if (text[j] == '\n') break // 不跨行
                        j++
                    }
                    if (close > i + 1) {
                        flushPlain()
                        out.add(MdSpan.Italic(text.substring(i + 1, close)))
                        i = close + 1
                        continue
                    }
                    plain.append(c)
                    i++
                }
                // `code`
                c == '`' -> {
                    // ``` 属于块级，行内不处理
                    val close = text.indexOf('`', i + 1)
                    if (close > i + 1 || (close > i && close != i)) {
                        if (close > i) {
                            flushPlain()
                            out.add(MdSpan.Code(text.substring(i + 1, close)))
                            i = close + 1
                            continue
                        }
                    }
                    plain.append(c)
                    i++
                }
                // [text](url)
                c == '[' -> {
                    val closeBracket = text.indexOf(']', i + 1)
                    if (closeBracket > i && closeBracket + 1 < n && text[closeBracket + 1] == '(') {
                        val closeParen = text.indexOf(')', closeBracket + 2)
                        if (closeParen > closeBracket) {
                            flushPlain()
                            out.add(
                                MdSpan.Link(
                                    text = text.substring(i + 1, closeBracket),
                                    url = text.substring(closeBracket + 2, closeParen),
                                )
                            )
                            i = closeParen + 1
                            continue
                        }
                    }
                    plain.append(c)
                    i++
                }
                // $math$
                c == '$' -> {
                    val close = findDollarClose(text, i)
                    if (close > i + 1) {
                        flushPlain()
                        out.add(MdSpan.Math(text.substring(i + 1, close)))
                        i = close + 1
                        continue
                    }
                    plain.append(c)
                    i++
                }
                else -> {
                    plain.append(c)
                    i++
                }
            }
        }
        flushPlain()
        return out
    }

    /** 找从 [from] 开始的连续 [count] 个 [ch]。 */
    private fun findSeq(text: String, from: Int, ch: Int, count: Int): Int {
        var i = from
        val n = text.length
        outer@ while (i + count <= n) {
            for (k in 0 until count) {
                if (text.codePointAt(i + k) != ch) {
                    i++
                    continue@outer
                }
            }
            return i
        }
        return -1
    }

    /** 找与开头 $ 配对的闭合 $：跳过 \$ 转义。 */
    private fun findDollarClose(text: String, open: Int): Int {
        var i = open + 1
        val n = text.length
        while (i < n) {
            if (text[i] == '\\') { i += 2; continue }
            if (text[i] == '$') return i
            if (text[i] == '\n') return -1 // 行内公式不跨行
            i++
        }
        return -1
    }
}
