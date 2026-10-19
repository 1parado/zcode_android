package com.zai.zcode.feature.chat

/**
 * 极简 VT/ANSI 序列渲染器：把 PTY 下行的原始输出转成适合纯文本 LazyColumn
 * 展示的行缓冲。
 *
 * 背景（用户报「输入 clear，屏上出现 c / cl / cle / clea / clear 逐字累积」）：
 * Windows PowerShell 的 PSReadLine 对每个到达的字符做「重画当前行」渲染
 * （`\r` + ESC[K + 重打整行）。旧实现用正则把 ANSI 序列和 `\r` 全剥掉后追加，
 * 每次重画的残片就被当成新文本累积下来。官方桌面端用 xterm.js 解析完整 VT
 * 状态机所以天然正确；Android 端没有 xterm，这里实现覆盖渲染所需的最小子集：
 *
 * - `ESC[K`（清除到行尾）、`ESC[2K`（清整行）→ 删除缓冲行对应部分
 * - `\r`（回到行首）→ 下一帧写入覆盖当前行（PSReadLine 重画的核心）
 * - `\b` → 光标左移
 * - CSI 光标移动（A/B/C/D/G/H）→ 移动缓冲行内光标，后续字符覆盖写入
 * - `ESC[J`（清屏）、`ESC[L/M/P/@`（行/字符插删）
 * - OSC（窗口标题等）→ 丢弃
 * - 其余不可打印序列 → 丢弃（颜色对纯文本展示无意义）
 *
 * **跨 chunk 状态（用户报「屏上出现 [0m、[c[?9001h 字面量」）**：PTY 下行按
 * WebSocket/relay 分包，转义序列经常被从中间切断（一段以 ESC 结尾、下一段以
 * `[0m` 开头）。旧实现每次 feed() 独立解析，截断处就把半截序列当普通文本渲染。
 * 这里改为有状态解析：feed() 内先拼上上次遗留的不完整序列前缀，结尾再存回
 * [pending]，序列完整后才消费。
 *
 * 不是完整 xterm：滚动区域、备屏（alternate screen，vim/htop）等不处理；
 * 备屏场景退化为「新行追加」，聊天式展示下可接受。
 */
class VtRenderer {

    // 上次 feed() 结尾遗留的不完整转义序列（含开头的 ESC），完整序列不会被存进来
    private var pending = ""

    // 每行一份可变 StringBuilder：覆盖写入（PSReadLine 重画）的核心数据结构
    private val lines = mutableListOf(StringBuilder())
    private var cursorRow = 0
    private var cursorCol = 0

    /** 当前渲染结果（行间 `\n` 连接）。 */
    val text: String get() = lines.joinToString("\n")

    /** 重置（新建 PTY 时调用——旧 shell 的输出历史不属于新 shell）。 */
    fun reset() {
        pending = ""
        lines.clear()
        lines.add(StringBuilder())
        cursorRow = 0
        cursorCol = 0
    }

    /** 喂一段 PTY 输出，返回渲染后的完整文本。 */
    fun feed(data: String): String {
        // 与上次遗留的不完整序列前缀拼接后再解析（修复跨 chunk 截断泄漏）
        val stream = pending + data
        pending = ""
        var i = 0
        val n = stream.length
        while (i < n) {
            val c = stream[i]
            when {
                c == '\u001B' -> {
                    val next = consumeEscape(stream, i)
                    if (next == NEED_MORE) {
                        // 序列被本段结尾截断：存前缀等下一段拼完（不再当文本渲染）
                        pending = stream.substring(i)
                        i = n
                        continue
                    }
                    i = next
                }
                c == '\n' -> newLine()
                c == '\r' -> cursorCol = 0
                c == '\b' -> if (cursorCol > 0) cursorCol--
                c == '\t' -> {
                    // 对齐到 8 列
                    val target = (cursorCol / 8 + 1) * 8
                    while (cursorCol < target) putChar(' ')
                }
                c >= ' ' -> putChar(c)
                // 其余控制字符丢弃
            }
            i++
        }
        return text
    }

    private fun newLine() {
        cursorRow++
        cursorCol = 0
        while (lines.size <= cursorRow) lines.add(StringBuilder())
    }

    /** 在 (cursorRow, cursorCol) 覆盖写入一个字符，光标右移。 */
    private fun putChar(c: Char) {
        while (lines.size <= cursorRow) lines.add(StringBuilder())
        val line = lines[cursorRow]
        while (line.length <= cursorCol) line.append(' ')
        line[cursorCol] = c
        cursorCol++
    }

    private companion object {
        /** consumeEscape 的返回值：序列不完整，等下一 chunk。 */
        const val NEED_MORE = -1
        /** 不完整序列的最大长度（防异常流无限堆积）：真实 VT 序列都很短。 */
        const val MAX_PENDING = 64
    }

    /**
     * stream[index] == ESC，返回处理完该序列后的下一个下标；
     * 序列在流结尾被截断时返回 [NEED_MORE]（调用方存入 [pending]）。
     */
    private fun consumeEscape(stream: String, index: Int): Int {
        val n = stream.length
        var i = index + 1
        if (i >= n) return NEED_MORE
        when (stream[i]) {
            '[' -> {
                // CSI：参数字节 0-9;?<> 中间，终结字母
                var params = ""
                i++
                while (i < n && (stream[i] in '0'..'9' || stream[i] == ';' || stream[i] == '?' || stream[i] == '>' || stream[i] == '<')) {
                    params += stream[i]
                    i++
                }
                if (i >= n) return NEED_MORE
                val final = stream[i]
                handleCsi(params, final)
                return i + 1
            }
            ']' -> {
                // OSC：直到 BEL 或 ESC \
                i++
                while (i < n) {
                    if (stream[i] == '\u0007') return i + 1
                    if (stream[i] == '\u001B' && i + 1 < n && stream[i + 1] == '\\') return i + 2
                    i++
                }
                return NEED_MORE
            }
            // ESC ( X 等 charset 选择：吃掉 2 字节；可能截断
            '(' -> return if (i + 1 < n) i + 2 else NEED_MORE
            // 其余单字符序列（ESC 7/8/M/D/E 等）
            else -> return i + 1
        }
    }

    private fun handleCsi(params: String, final: Char) {
        val nums = params.split(';').mapNotNull { it.filter { ch -> ch.isDigit() }.ifEmpty { null }?.toIntOrNull() }
        fun num(default: Int) = nums.firstOrNull() ?: default
        ensureRow()
        val line = lines[cursorRow]
        when (final) {
            'K' -> {
                // 清除到行尾 / 行首 / 整行
                when (num(0)) {
                    0 -> if (line.length > cursorCol) line.delete(cursorCol, line.length)
                    1 -> { line.delete(0, minOf(cursorCol, line.length)); cursorCol = 0 }
                    2 -> { line.clear(); cursorCol = 0 }
                }
            }
            'J' -> when (num(0)) {
                0 -> while (lines.size > cursorRow + 1) lines.removeAt(lines.size - 1)
                2, 3 -> {
                    lines.clear(); lines.add(StringBuilder()); cursorRow = 0; cursorCol = 0
                }
            }
            'A' -> cursorRow = (cursorRow - num(1).coerceAtLeast(1)).coerceAtLeast(0)
            'B' -> { cursorRow += num(1).coerceAtLeast(1); ensureRow() }
            'C' -> cursorCol += num(1).coerceAtLeast(1)
            'D' -> cursorCol = (cursorCol - num(1).coerceAtLeast(1)).coerceAtLeast(0)
            'G' -> cursorCol = (num(1) - 1).coerceAtLeast(0) // CHA 光标绝对列
            'H', 'f' -> { // CUP 绝对定位（1-based，行;列）
                val row = nums.getOrNull(0)?.takeIf { it > 0 } ?: 1
                val col = nums.getOrNull(1)?.takeIf { it > 0 } ?: 1
                cursorRow = (row - 1).coerceAtLeast(0)
                cursorCol = (col - 1).coerceAtLeast(0)
                ensureRow()
            }
            'L' -> { // 插入行
                repeat(num(1).coerceAtLeast(1)) { lines.add(cursorRow, StringBuilder()) }
            }
            'M' -> { // 删除行
                repeat(num(1).coerceAtLeast(1)) {
                    if (lines.size > 1) lines.removeAt(cursorRow.coerceAtMost(lines.size - 1))
                }
                ensureRow()
            }
            'P' -> { // 删除字符
                val count = num(1).coerceAtLeast(1)
                if (cursorCol < line.length) line.delete(cursorCol, minOf(cursorCol + count, line.length))
            }
            '@' -> { // 插入字符
                repeat(num(1).coerceAtLeast(1)) { line.insert(cursorCol, ' ') }
            }
            // 颜色/样式（m）、私有模式（?...h/l）、滚动区（r）等：纯文本展示不需要
        }
    }

    private fun ensureRow() {
        while (lines.size <= cursorRow) lines.add(StringBuilder())
    }
}
