package com.zai.zcode.core.design

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 间距与尺寸令牌（docs/ui-design.md §6）：4dp 栅格，触控目标 ≥48dp。
 */
object ZcodeSpacing {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp

    // 触控目标
    val touch: Dp = 48.dp

    // 列表卡内边距
    val cardPadding: Dp = 16.dp
    // 页面水平边距
    val screenPadding: Dp = 16.dp
}
