package com.zai.zcode.core.markdown

/**
 * 近似代码高亮（策略抄自 MarkdownView 的无依赖 lexer）：
 * 只认 keyword / string / comment / number / annotation 五类 token，
 * 快而不求全 —— 每次流式重排只在几十微秒级，保证 120fps 不掉帧。
 */
object MdCodeHighlighter {

    enum class Tok { KEYWORD, STRING, COMMENT, NUMBER, ANNOTATION, PLAIN }

    data class Token(val text: String, val tok: Tok)

    private val KEYWORDS: Map<String, Set<String>> = mapOf(
        "kotlin" to setOf(
            "fun", "val", "var", "class", "object", "interface", "data", "sealed", "enum", "when", "if", "else",
            "for", "while", "return", "import", "package", "override", "private", "internal", "public", "companion",
            "suspend", "async", "await", "try", "catch", "finally", "throw", "this", "super", "null", "true", "false",
            "is", "as", "in", "out", "by", "lateinit", "const", "abstract", "open", "init", "constructor", "typealias",
        ),
        "java" to setOf(
            "public", "private", "protected", "class", "interface", "extends", "implements", "static", "final",
            "void", "int", "long", "double", "float", "boolean", "char", "new", "return", "if", "else", "for",
            "while", "try", "catch", "finally", "throw", "throws", "import", "package", "this", "super", "null",
            "true", "false", "abstract", "synchronized", "volatile", "instanceof",
        ),
        "swift" to setOf(
            "func", "let", "var", "class", "struct", "enum", "protocol", "extension", "if", "else", "guard",
            "for", "while", "return", "import", "override", "private", "public", "internal", "open", "static",
            "self", "nil", "true", "false", "as", "is", "in", "where", "switch", "case", "default", "throws", "try",
            "catch", "finally", "async", "await", "some", "any", "actor",
        ),
        "typescript" to setOf(
            "const", "let", "var", "function", "class", "interface", "type", "enum", "extends", "implements",
            "import", "export", "from", "as", "return", "if", "else", "for", "while", "switch", "case", "default",
            "new", "this", "super", "null", "undefined", "true", "false", "async", "await", "of", "in", "typeof",
            "instanceof", "public", "private", "protected", "readonly", "static", "abstract", "declare", "namespace",
        ),
        "javascript" to setOf(
            "const", "let", "var", "function", "class", "extends", "import", "export", "from", "as", "return",
            "if", "else", "for", "while", "switch", "case", "default", "new", "this", "super", "null", "undefined",
            "true", "false", "async", "await", "of", "in", "typeof", "instanceof", "throw", "try", "catch", "finally",
        ),
        "python" to setOf(
            "def", "class", "if", "elif", "else", "for", "while", "return", "import", "from", "as", "with",
            "try", "except", "finally", "raise", "pass", "break", "continue", "lambda", "yield", "global", "nonlocal",
            "None", "True", "False", "and", "or", "not", "is", "in", "assert", "async", "await", "del",
        ),
        "go" to setOf(
            "func", "var", "const", "type", "struct", "interface", "map", "chan", "go", "defer", "return", "if",
            "else", "for", "range", "switch", "case", "default", "package", "import", "select", "break", "continue",
            "fallthrough", "goto", "nil", "true", "false", "new", "make", "len", "cap", "append", "panic", "recover",
        ),
        "rust" to setOf(
            "fn", "let", "mut", "const", "static", "struct", "enum", "trait", "impl", "for", "while", "loop", "if",
            "else", "match", "return", "use", "mod", "pub", "crate", "self", "Self", "super", "as", "in", "where",
            "async", "await", "move", "dyn", "ref", "type", "unsafe", "true", "false", "Some", "None", "Ok", "Err",
        ),
        "json" to setOf("true", "false", "null"),
        "bash" to setOf(
            "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac", "function", "return",
            "export", "local", "echo", "cd", "exit", "source", "set",
        ),
    )

    /** 语言别名归一。 */
    fun normalize(lang: String?): String = when (lang?.lowercase()) {
        "kt", "kts" -> "kotlin"
        "js", "mjs", "cjs", "jsx" -> "javascript"
        "ts", "tsx" -> "typescript"
        "py" -> "python"
        "golang" -> "go"
        "rs" -> "rust"
        "sh", "shell", "zsh" -> "bash"
        null, "" -> "kotlin"
        else -> lang.lowercase()
    }

    fun tokenize(line: String, lang: String): List<Token> {
        val keywords = KEYWORDS[normalize(lang)] ?: return listOf(Token(line, Tok.PLAIN))
        val out = ArrayList<Token>(8)
        var i = 0
        val n = line.length
        val plain = StringBuilder()

        fun flush() {
            if (plain.isNotEmpty()) { out.add(Token(plain.toString(), Tok.PLAIN)); plain.clear() }
        }

        while (i < n) {
            val c = line[i]
            when {
                // 行注释（不处理块注释：流式场景块注释跨行状态复杂，收益低）
                (c == '/' && i + 1 < n && (line[i + 1] == '/' || line[i + 1] == '*')) ||
                    (c == '#' && normalize(lang) != "json" && !keywords.contains("#")) -> {
                    flush()
                    out.add(Token(line.substring(i), Tok.COMMENT))
                    return out
                }
                // 字符串
                c == '"' || c == '\'' || c == '`' -> {
                    var j = i + 1
                    while (j < n) {
                        if (line[j] == '\\') { j += 2; continue }
                        if (line[j] == c) { j++; break }
                        j++
                    }
                    if (j > n) j = n
                    flush()
                    out.add(Token(line.substring(i, j), Tok.STRING))
                    i = j
                }
                // 数字
                c.isDigit() -> {
                    var j = i
                    while (j < n && (line[j].isDigit() || line[j] == '.' || line[j] == '_')) j++
                    // 词尾字母（如 0x1F、1e9）
                    while (j < n && line[j].isLetter()) j++
                    flush()
                    out.add(Token(line.substring(i, j), Tok.NUMBER))
                    i = j
                }
                // 标识符
                c.isLetter() || c == '_' || c == '$' -> {
                    var j = i
                    while (j < n && (line[j].isLetterOrDigit() || line[j] == '_' || line[j] == '$')) j++
                    val word = line.substring(i, j)
                    flush()
                    out.add(
                        when {
                            keywords.contains(word) -> Token(word, Tok.KEYWORD)
                            // @Annotation / 装饰器（前一个非空白字符是行首或标点，简化为仅看 @ 前缀）
                            word.startsWith("@") -> Token(word, Tok.ANNOTATION)
                            else -> Token(word, Tok.PLAIN)
                        }
                    )
                    i = j
                }
                else -> { plain.append(c); i++ }
            }
        }
        flush()
        return out
    }
}
