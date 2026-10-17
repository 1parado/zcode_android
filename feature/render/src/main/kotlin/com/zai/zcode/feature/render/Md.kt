package com.zai.zcode.feature.render

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.zai.zcode.core.markdown.MdBlock
import com.zai.zcode.core.markdown.MdStreamParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 流式感知 Markdown 渲染入口（MarkdownView 管线移植）：
 * raw 文本 → 后台线程解析（Dispatchers.Default）→ 块列表 → Compose 分块渲染。
 *
 * 两道节流（修复依据 v0.2.16「流式输出越到后面越卡」）：
 * 1. **增量解析**：[MdStreamParser] 复用已定稿的前缀块，每帧只解析未定稿的尾部，
 *    把 O(n²) 的累计扫描降为 O(n)；前缀块还是同一批实例，未变化块跳过重组。
 * 2. **帧节流**：高频 delta 到达时按 [PARSE_THROTTLE_MS] 合并，最多每 60ms 解析一次。
 *    助手输出常常几十毫秒一个 chunk，全部照单解析会持续占满 Default 线程，
 *    主线程拿不到帧预算 —— 合并到人眼分辨不出的粒度，观感反而更连贯。
 *
 * 尾部（最后一帧）不会被节流吃掉：文本停止增长后 [PARSE_THROTTLE_MS] 内必然再解析一次。
 */
@Composable
fun MarkdownContent(
    raw: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    var content by remember { mutableStateOf<List<MdBlock>>(emptyList()) }
    // 解析请求通道：raw 每次变化往里塞一份，协程侧按节流节奏消费（CONFLATED 保留最新）
    val requests = remember { Channel<ParseRequest>(Channel.CONFLATED) }
    val parser = remember { MdStreamParser() }

    LaunchedEffect(raw, isStreaming) {
        requests.trySend(ParseRequest(raw, isStreaming))
    }

    LaunchedEffect(requests, parser) {
        for (request in requests) {
            val blocks = withContext(Dispatchers.Default) {
                parser.parse(request.text, isStreaming = request.isStreaming)
                parser.blocks
            }
            content = blocks
            // 流式下合并窗口内到达的后续 delta；非流式（整段替换）无需等待
            if (request.isStreaming) delay(PARSE_THROTTLE_MS)
        }
    }

    Column(modifier.fillMaxWidth()) {
        content.forEach { block ->
            // key = stableId：块身份稳定，前缀块复用时 Compose 直接跳过
            androidx.compose.runtime.key(block.stableId) {
                MdBlockView(block)
            }
        }
    }
}

/** 流式解析节流窗口：60ms ≈ 16fps 的解析节奏，远快于人眼对文本增长的分辨力。 */
private const val PARSE_THROTTLE_MS = 60L

/**
 * 一次解析请求：[isStreaming] 随文本一起过通道。
 * 修复依据（v0.2.16）：消费协程不会被重组重启，若只捕获外层 flag，流式转化为成形的
 * 那一刻它读到的还是旧值 —— 未闭合围栏/公式的兜底语义（部分渲染 vs 段落兜底）
 * 会一直停在旧模式上，表现为「最后一段代码块/公式突然变了样子」。
 */
private data class ParseRequest(val text: String, val isStreaming: Boolean)
