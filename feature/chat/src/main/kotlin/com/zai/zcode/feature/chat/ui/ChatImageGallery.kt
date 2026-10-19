package com.zai.zcode.feature.chat.ui

import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import com.zai.zcode.core.design.LocalZcodeColors
import com.zai.zcode.core.design.ZcodeSpacing
import com.zai.zcode.core.design.ZcodeType
import com.zai.zcode.core.network.ZcodeConnection
import com.zai.zcode.core.protocol.v4.ZcodeProtocolV4

/**
 * 聊天流图片（官方 markdown-image.tsx 的移动端形态）。
 *
 * 数据链路与官方一致：正文 markdown 里的 `![...](zcode-artifact://ref)` 由
 * [com.zai.zcode.feature.chat.ChatRepository.artifactImageRefs] 提取为 ref 列表，
 * 字节经 v4/conversation/attachmentRead（≤512KiB 分块）拉全后本地解码为 ImageBitmap。
 * 点击放大为全屏预览。
 */
@Composable
fun ChatImageGallery(
    imageRefs: List<String>,
    sessionId: String,
    connection: ZcodeConnection,
    modifier: Modifier = Modifier,
) {
    if (imageRefs.isEmpty()) return
    val zc = LocalZcodeColors.current
    var previewRef by remember { mutableStateOf<String?>(null) }

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZcodeSpacing.sm),
    ) {
        items(imageRefs.size) { index ->
            val ref = imageRefs[index]
            ChatImageThumb(
                ref = ref,
                sessionId = sessionId,
                connection = connection,
                onClick = { previewRef = ref },
            )
        }
    }
    previewRef?.let { ref ->
        ChatImagePreview(
            ref = ref,
            sessionId = sessionId,
            connection = connection,
            onDismiss = { previewRef = null },
        )
    }
}

/** 缩略图（单张，128dp 高）：加载中占位 → 成功 Image → 失败占位文案。 */
@Composable
private fun ChatImageThumb(
    ref: String,
    sessionId: String,
    connection: ZcodeConnection,
    onClick: () -> Unit,
) {
    val image = rememberAttachmentImage(ref, sessionId, connection)
    Box(
        Modifier
            .width(128.dp)
            .height(96.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = image != null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when (image) {
            null -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else -> Image(
                bitmap = image,
                contentDescription = "会话图片",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 128.dp, height = 96.dp),
            )
        }
    }
}

/** 全屏预览（点击关闭）。 */
@Composable
private fun ChatImagePreview(
    ref: String,
    sessionId: String,
    connection: ZcodeConnection,
    onDismiss: () -> Unit,
) {
    val image = rememberAttachmentImage(ref, sessionId, connection)
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        when (image) {
            null -> Text("图片加载中…", style = ZcodeType.uiSm)
            else -> Image(
                bitmap = image,
                contentDescription = "会话图片预览",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * ref → ImageBitmap（remember 缓存；分块循环拉全 512KiB chunks 后一次性解码）。
 * 失败/取消返回 null（UI 显示占位，不崩）。
 */
@Composable
private fun rememberAttachmentImage(
    ref: String,
    sessionId: String,
    connection: ZcodeConnection,
): ImageBitmap? {
    var image by remember(ref) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(ref) {
        val protocol = connection.protocol.value ?: return@LaunchedEffect
        val bytes = runCatching {
            var offset = 0L
            val out = ArrayList<Byte>()
            // 防御上限 8MiB（协议 attachmentPreviewMaxBytes 同量级）
            while (offset < 8L * 1024 * 1024) {
                val chunk: ZcodeProtocolV4.AttachmentChunk = protocol.conversationAttachmentRead(
                    sessionId = sessionId,
                    ref = ref,
                    // attachmentRead 的 target 需要 row 授权；ref 本身已在行内，
                    // entityId 传 ref 归属行即可（服务端按 ref 授权校验）
                    rowId = sessionId,
                    attachmentIndex = 0,
                    offset = offset,
                ) ?: break
                out.addAll(android.util.Base64.decode(chunk.dataBase64, android.util.Base64.DEFAULT).toList())
                offset = chunk.nextOffset ?: break
            }
            out.toByteArray()
        }.getOrNull() ?: return@LaunchedEffect
        image = runCatching {
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?.asImageBitmap()
        }.getOrNull()
    }
    return image
}
