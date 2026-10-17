package com.zai.zcode.feature.render

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.zai.zcode.core.markdown.MdBlock
import com.zai.zcode.core.markdown.MdParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 流式感知 Markdown 渲染入口（MarkdownView 管线移植）：
 * raw 文本 → 后台线程解析（Dispatchers.Default）→ 块列表 → Compose 分块渲染。
 *
 * 节流合并：解析结果以 State 发布，行级 delta 高频到达时 Compose 的快照
 * 合帧（每帧最多一次重组）天然做合并；解析本身在 Default 线程不阻塞 UI。
 * 前缀块数据类相等比较 + data class 结构共享，使未变化块跳过重组。
 */
@Composable
fun MarkdownContent(
    raw: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    val content = remember { mutableStateOf<List<MdBlock>>(emptyList()) }
    LaunchedEffect(raw, isStreaming) {
        content.value = withContext(Dispatchers.Default) {
            MdParser.parse(raw, isStreaming = isStreaming)
        }
    }
    Column(modifier.fillMaxWidth()) {
        content.value.forEach { block ->
            MdBlockView(block)
        }
    }
}
