package com.zai.zcode.core.design

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字阶令牌（docs/ui-design.md §4）：ui-xl 18/24 → ui-xs 11/14，
 * 代码/终端 mono 12/18。全部用系统默认字体，只定尺寸与行高。
 */
object ZcodeType {
    // 页面大标题
    val uiXl = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
    // 区域标题
    val uiLg = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    // 列表主标题
    val uiMd = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    // 正文
    val uiSm = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    // 次要/标签
    val uiXs = TextStyle(fontSize = 11.sp, lineHeight = 14.sp)
    // 代码/工具输入/终端
    val mono = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace)
}
