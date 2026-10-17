package com.zai.zcode.core.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 圆角令牌（docs/ui-design.md §5）：卡 12 / 内嵌 8 / 控件 6 /
 * 输入 20 / Sheet 24 / Dialog 16 / full。
 */
object ZcodeShape {
    val card: Shape = RoundedCornerShape(12.dp)
    val inner: Shape = RoundedCornerShape(8.dp)
    val control: Shape = RoundedCornerShape(6.dp)
    val input: Shape = RoundedCornerShape(20.dp)
    val sheet: Shape = RoundedCornerShape(24.dp)
    val dialog: Shape = RoundedCornerShape(16.dp)
    val full: Shape = RoundedCornerShape(50)
}
