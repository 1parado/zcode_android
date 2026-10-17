package com.zai.zcode.core.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * 设计系统主题（docs/ui-design.md）：token 层（ZcodeColors/Type/Shape/Spacing/Motion）
 * 通过 CompositionLocal 提供给 UI；MaterialTheme colorScheme 由 token 映射，
 * tonalElevation 一律 0（spec：表面层级用离散色值，不用 M3 tonal 覆盖）。
 * feature 模块内一律用 [ZcodeTheme] 保证一致。
 */

private fun mapColors(zc: ZcodeColors) = if (zc.darkTheme) {
    darkColorScheme(
        primary = zc.text,
        onPrimary = zc.base,
        secondary = zc.textSecondary,
        background = zc.base,
        onBackground = zc.text,
        surface = zc.base,
        onSurface = zc.text,
        surfaceVariant = zc.card,
        onSurfaceVariant = zc.textSecondary,
        surfaceContainer = zc.card,
        surfaceContainerHigh = zc.card,
        surfaceContainerHighest = zc.input,
        surfaceContainerLow = zc.panel,
        surfaceContainerLowest = zc.base,
        outline = zc.border,
        outlineVariant = zc.border,
        error = zc.error,
    )
} else {
    lightColorScheme(
        primary = zc.text,
        onPrimary = zc.base,
        secondary = zc.textSecondary,
        background = zc.base,
        onBackground = zc.text,
        surface = zc.base,
        onSurface = zc.text,
        surfaceVariant = zc.card,
        onSurfaceVariant = zc.textSecondary,
        surfaceContainer = zc.card,
        surfaceContainerHigh = zc.card,
        surfaceContainerHighest = zc.input,
        surfaceContainerLow = zc.panel,
        surfaceContainerLowest = zc.base,
        outline = zc.border,
        outlineVariant = zc.border,
        error = zc.error,
    )
}

@Composable
fun ZcodeTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val zc = ZcodeColors.of(darkTheme)
    CompositionLocalProvider(LocalZcodeColors provides zc) {
        MaterialTheme(
            colorScheme = mapColors(zc),
            content = content,
        )
    }
}

// 兼容旧引用：CardCorner/SheetCorner 映射到新 shape 令牌
val CardCorner get() = ZcodeShape.card
val SheetCorner get() = ZcodeShape.sheet
