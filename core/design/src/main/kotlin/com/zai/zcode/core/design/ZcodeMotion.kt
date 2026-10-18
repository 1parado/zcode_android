package com.zai.zcode.core.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.ripple
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

/**
 * 动效令牌（docs/ui-design.md §7）：呼吸 1.6s、折叠 180ms、果冻按压弹簧。
 * 系统关闭动画（AnimatorDurationScale=0）时自动退化为静态，不再走 infinite 循环。
 */
object ZcodeMotion {
    /** 折叠/展开（思考块、Sheet 内容）标准时长。 */
    const val COLLAPSE_MS = 180

    /** 呼吸动画周期。 */
    const val BREATHE_MS = 1600

    val collapseEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /**
     * 果冻按压目标缩放。
     * 2026 年主流 AI 客户端做「位移即反馈」：按下那一帧就缩一点，比 ripple 更早给
     * 触达确认。0.96 是"看得见但不干扰阅读"的量级——再大就变成抖动的廉价感。
     */
    const val PRESS_SCALE = 0.96f

    /**
     * 回弹弹簧：中低阻尼 + 低刚度，松手后有一次轻微过冲再稳定。
     * 低刚度让形变看起来是"被弹回去"而不是"被补间回去"（补间回弹没有果冻感）。
     */
    val pressSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow,
    )
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

/**
 * 系统是否允许动效（动画时长缩放为 0 即用户显式要求减弱动效）。
 * 修复依据（spec §7）：减弱动效下要彻底不走动画，而不是"动画很快"。
 */
@Composable
fun animationsEnabled(): Boolean {
    val ctx = LocalContext.current
    return remember(ctx) {
        android.provider.Settings.Global.getFloat(
            ctx.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f
    }
}

/**
 * 果冻按压缩放：跟随按压态做缩放。缩放只在 graphicsLayer 上做，不参与布局测量，
 * 列表滚动中连续按压也不会触发重组测量。
 *
 * @param interactionSource 必须与 clickable 共用同一实例，否则收不到按压事件。
 */
@Composable
fun Modifier.jellyPress(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
): Modifier {
    if (!animationsEnabled()) return this
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) ZcodeMotion.PRESS_SCALE else 1f,
        animationSpec = ZcodeMotion.pressSpring,
        label = "jellyScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * 一体化果冻点击：按压缩放 + clickable（保留系统 ripple 作无障碍反馈）。
 * 卡片 / Chip / 列表项统一走这里，保证全 App 触感一致。
 */
@Composable
fun Modifier.jellyClickable(
    onClick: () -> Unit,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val clickModifier = if (onLongClick != null) {
        Modifier.combinedClickable(
            interactionSource = source,
            indication = ripple(),
            enabled = enabled,
            onClickLabel = onClickLabel,
            onLongClickLabel = onClickLabel,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    } else {
        Modifier.clickable(
            interactionSource = source,
            enabled = enabled,
            onClickLabel = onClickLabel,
            onClick = onClick,
        )
    }
    return this
        .jellyPress(source, enabled)
        .then(clickModifier)
}
