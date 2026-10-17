package com.zai.zcode.core.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 设计系统基线（core/design）：对齐 app 现有主题色，供各 feature 模块引用。
 * app 层 ZcodemobileTheme 是壳主题；feature 模块内一律用 [ZcodeTheme] 保证一致。
 */

// 品牌色（与 app ui/theme/Color.kt 同源）
val ZcodePrimary = Color(0xFF6C5CE7)
val ZcodePrimaryDim = Color(0xFF4834D4)
val ZcodeSurfaceDark = Color(0xFF121212)
val ZcodeSurfaceLight = Color(0xFFFDFDFD)
val StreamingGreen = Color(0xFF00B894)
val ErrorRed = Color(0xFFD63031)

private val DarkColors = darkColorScheme(
    primary = ZcodePrimary,
    secondary = ZcodePrimaryDim,
    background = ZcodeSurfaceDark,
    surface = ZcodeSurfaceDark,
    error = ErrorRed,
)

private val LightColors = lightColorScheme(
    primary = ZcodePrimary,
    secondary = ZcodePrimaryDim,
    background = ZcodeSurfaceLight,
    surface = ZcodeSurfaceLight,
    error = ErrorRed,
)

@Composable
fun ZcodeTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

// 通用形状令牌
val CardCorner = RoundedCornerShape(12.dp)
val SheetCorner = RoundedCornerShape(20.dp)
