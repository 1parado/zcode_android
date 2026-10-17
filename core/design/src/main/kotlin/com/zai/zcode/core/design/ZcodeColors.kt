package com.zai.zcode.core.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Zai 色彩令牌（docs/ui-design.md §3）：Dark 底 #161616 / Light 底 #F8F8F8，
 * brand 单色 + 语义状态色（running/attention/error/queued/diff）。
 * 废弃旧紫色 #6C5CE7 / 纯黑 #121212。
 */

// —— Dark 表面层级 ——
val ZaiDarkBase = Color(0xFF161616)      // 全局底色
val ZaiDarkPanel = Color(0xFF1F1F1F)     // 区域底（导航、Sheet）
val ZaiDarkCard = Color(0xFF242424)      // 卡片
val ZaiDarkInput = Color(0xFF2A2A2A)     // 输入框
val ZaiDarkBorder = Color(0x1AE5E5E5)    // 描边（E5E5E5 @ 10%）
val ZaiDarkText = Color(0xFFE5E5E5)      // 主文本
val ZaiDarkTextSecondary = Color(0xFF9E9E9E) // 次要文本
val ZaiDarkTextTertiary = Color(0xFF6E6E6E)  // 辅助/禁用

// —— Light 表面层级 ——
val ZaiLightBase = Color(0xFFF8F8F8)
val ZaiLightPanel = Color(0xFFF1F1F1)
val ZaiLightCard = Color(0xFFFFFFFF)
val ZaiLightInput = Color(0xFFEFEFEF)
val ZaiLightBorder = Color(0x1A000000)   // 黑 @ 10%
val ZaiLightText = Color(0xFF404040)
val ZaiLightTextSecondary = Color(0xFF737373)
val ZaiLightTextTertiary = Color(0xFFA3A3A3)

// —— Brand 单色（深浅同值，UI 中尽量少用） ——
val ZaiBrand = Color(0xFF6C5CE7)         // 保留旧紫为 brand；主操作优先中性色

// —— 状态色：Dark / Light 成对 ——
data class StatusPalette(val dark: Color, val light: Color) {
    fun resolve(darkTheme: Boolean): Color = if (darkTheme) dark else light
}

// running：亮绿 / 深绿
val StatusRunning = StatusPalette(Color(0xFF4ADE80), Color(0xFF16A34A))
// attention：亮黄 / 深琥珀
val StatusAttention = StatusPalette(Color(0xFFFBBF24), Color(0xFFB45309))
// error：亮红 / 深红
val StatusError = StatusPalette(Color(0xFFF87171), Color(0xFFDC2626))
// queued：亮蓝 / 深蓝
val StatusQueued = StatusPalette(Color(0xFF38BDF8), Color(0xFF0284C7))
// diff：added 绿 / removed 红（仅用于 Diff 行，非状态语义）
val DiffAdded = StatusPalette(Color(0xFF4ADE80), Color(0xFF16A34A))
val DiffRemoved = StatusPalette(Color(0xFFF87171), Color(0xFFDC2626))

// —— 填充态 attention 的前景 ——
// Dark 侧 attention 是亮琥珀 #FBBF24，压深字；Light 侧是深琥珀 #B45309，压白字。
// 两侧都保证 4.5:1 以上，不是靠猜。
val OnAttentionDark = Color(0xFF3D2A00)
val OnAttentionLight = Color(0xFFFFFFFF)

/** 语义色集合，经 [ZcodeTheme] 注入；UI 一律从这里取色，不直接引用具体 hex。 */
@Immutable
data class ZcodeColors(
    val darkTheme: Boolean,
    // 表面
    val base: Color,
    val panel: Color,
    val card: Color,
    val input: Color,
    val border: Color,
    // 文本
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    // brand（少用）
    val brand: Color,
    // 状态（已按当前明暗解析完毕，UI 直接用）
    val running: Color,
    val attention: Color,
    val error: Color,
    val queued: Color,
    val diffAdded: Color,
    val diffRemoved: Color,
    /**
     * 填充态 attention 按钮上的前景色（审批「允许一次」这类主操作）。
     *
     * 修复依据（v0.2.18 审批 Dock）：attention 是琥珀色，深浅两侧亮度都不够稳定，
     * 直接把文本色叠上去在 Light 下会掉到 3:1 以下。这里给一个与填充色配对的
     * 前景色（深色字压亮琥珀），而不是让调用方猜 Color.White 还是 Color.Black。
     */
    val onAttention: Color,
) {
    companion object {
        fun of(darkTheme: Boolean): ZcodeColors = if (darkTheme) {
            ZcodeColors(
                darkTheme = true,
                base = ZaiDarkBase, panel = ZaiDarkPanel, card = ZaiDarkCard,
                input = ZaiDarkInput, border = ZaiDarkBorder,
                text = ZaiDarkText, textSecondary = ZaiDarkTextSecondary, textTertiary = ZaiDarkTextTertiary,
                brand = ZaiBrand,
                running = StatusRunning.resolve(true), attention = StatusAttention.resolve(true),
                error = StatusError.resolve(true), queued = StatusQueued.resolve(true),
                diffAdded = DiffAdded.resolve(true), diffRemoved = DiffRemoved.resolve(true),
                onAttention = OnAttentionDark,
            )
        } else {
            ZcodeColors(
                darkTheme = false,
                base = ZaiLightBase, panel = ZaiLightPanel, card = ZaiLightCard,
                input = ZaiLightInput, border = ZaiLightBorder,
                text = ZaiLightText, textSecondary = ZaiLightTextSecondary, textTertiary = ZaiLightTextTertiary,
                brand = ZaiBrand,
                running = StatusRunning.resolve(false), attention = StatusAttention.resolve(false),
                error = StatusError.resolve(false), queued = StatusQueued.resolve(false),
                diffAdded = DiffAdded.resolve(false), diffRemoved = DiffRemoved.resolve(false),
                onAttention = OnAttentionLight,
            )
        }
    }
}

val LocalZcodeColors = staticCompositionLocalOf { ZcodeColors.of(darkTheme = true) }
