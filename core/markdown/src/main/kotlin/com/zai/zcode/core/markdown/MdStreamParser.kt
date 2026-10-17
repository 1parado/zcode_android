package com.zai.zcode.core.markdown

/**
 * 流式增量解析器：把「每帧整篇重解析」降为「只解析未定稿的尾部」。
 *
 * 修复依据（v0.2.16「流式输出越到后面越卡」）：[MdParser.parse] 是 O(全文)，而流式的
 * 每一次 delta 都会触发一次——一条 8000 字的助手回复按 30 字一个 chunk 算就是约 270
 * 次全量解析，累计 O(n²) 的扫描量；后台线程被占满时主线程拿不到足够的帧预算，
 * 表现就是"越往后面越顿"。
 *
 * 思路：Markdown 的块边界要等「下一行已到达且不再是本块的延续」才真正确定。
 * [MdParser.parseDetected] 把这个边界算出来（safePrefixEndChars），本类据此把已定稿的
 * 前缀块缓存下来，下一帧只解析后缀再拼接。前缀块是**同一批对象实例**，配合 data class
 * 结构相等，Compose 侧未变化块直接跳过重组。
 *
 * 不变量：对同一实例，每次输入的 [text] 应是上一次的**前缀扩展**（流式 append 天然满足）。
 * 检测到不是追加就整篇重解析，因此结果永远与 `MdParser.parse` 一致
 * （由 MdParserTest 的逐 chunk 等价性用例守住）。
 */
class MdStreamParser {

    private var cachedText: String = ""
    private var cachedBlocks: List<MdBlock> = emptyList()
    private var cachedStreaming: Boolean? = null
    private var safePrefixEndChars: Int = 0
    private var settledCount: Int = 0

    /** 当前缓存的块（上次解析结果）。 */
    val blocks: List<MdBlock> get() = cachedBlocks

    /** 已定稿（后续追加不会再改变）的块数；供诊断与测试观察增量效果。 */
    val settledBlockCount: Int get() = settledCount

    /**
     * 解析 [text]，返回与 `MdParser.parse(text, isStreaming)` 等价的块列表。
     *
     * @return true 表示本次走了增量（复用前缀），false 表示整篇重解析。
     */
    fun parse(text: String, isStreaming: Boolean = false): Boolean {
        val canReuse = cachedText.isNotEmpty() &&
            cachedBlocks.isNotEmpty() &&
            safePrefixEndChars > 0 &&
            // 修复依据（v0.2.16）：isStreaming 翻转会改变未闭合围栏/公式的兜底语义，
            // 已定稿前缀是在另一个模式下算出来的，必须整篇重解析。
            cachedStreaming == isStreaming &&
            cachedText.length >= safePrefixEndChars &&
            text.length >= safePrefixEndChars &&
            // 只比对 [0, safePrefixEndChars)：cache 里可能挂着上一次的更长尾巴，
            // 超出部分的差异不影响前缀复用。
            cachedText.regionMatches(0, text, 0, safePrefixEndChars)

        if (!canReuse) {
            val parsed = MdParser.parseDetected(text, isStreaming)
            cachedText = text
            cachedBlocks = parsed.blocks
            cachedStreaming = isStreaming
            safePrefixEndChars = parsed.safePrefixEndChars
            settledCount = parsed.safeBlocks.size
            return false
        }

        val settled = cachedBlocks.subList(0, settledCount).toList()
        // 后缀：包含上一帧未定稿的块 + 本帧新到达的文本
        val tail = MdParser.parseDetected(text.substring(safePrefixEndChars), isStreaming)
        val merged = ArrayList<MdBlock>(settled.size + tail.blocks.size)
        merged.addAll(settled)
        merged.addAll(tail.blocks)

        cachedText = text
        cachedBlocks = merged
        cachedStreaming = isStreaming
        settledCount = settled.size + tail.safeBlocks.size
        safePrefixEndChars = safePrefixEndChars + tail.safePrefixEndChars
        return true
    }

    /** 丢弃缓存（文本被替换而非追加时自动触发，调用方无需显式调用）。 */
    fun reset() {
        cachedText = ""
        cachedBlocks = emptyList()
        cachedStreaming = null
        safePrefixEndChars = 0
        settledCount = 0
    }
}
