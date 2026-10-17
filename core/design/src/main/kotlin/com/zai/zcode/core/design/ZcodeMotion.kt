package com.zai.zcode.core.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier

/**
 * 动效令牌（docs/ui-design.md §7）：呼吸 1.6s、折叠 180ms。
 * 系统关闭动画（AnimatorDurationScale=0）时自动退化为静态，不再走 infinite 循环。
 */
object ZcodeMotion {
    /** 折叠/展开（思考块、Sheet 内容）标准时长。 */
    const val COLLAPSE_MS = 180

    /** 呼吸动画周期。 */
    const val BREATHE_MS = 1600

    val collapseEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

/**
 * 呼吸透明度动画：返回 0.4f..1f 循环值；系统减弱动效（时长缩放为 0）时恒为 1f。
 * 用法：Modifier.graphicsLayer(alpha = rememberBreatheAlpha())。
 */
@Composable
fun rememberBreatheAlpha(): Float {
    // 修复依据(spec §7 减弱动效):animateFloat 是 infiniteRepeatable,在系统动画
    // 关闭时依旧驱动重组;此处读时长缩放降级为静态。
    val scale = androidx.compose.ui.platform.LocalContext.current.let { ctx ->
        android.provider.Settings.Global.getFloat(
            ctx.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
    }
    if (scale == 0f) return 1f
    val transition = rememberInfiniteTransition(label = "breathe")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(ZcodeMotion.BREATHE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breatheAlpha",
    )
    return alpha
}
