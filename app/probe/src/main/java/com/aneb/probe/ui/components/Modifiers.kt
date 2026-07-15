package com.aneb.probe.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import com.aneb.probe.ui.theme.AnebMotion
import com.aneb.probe.ui.theme.LocalReducedMotion
import kotlin.math.roundToInt

/**
 * ANEB 动效基元（Modifier 扩展 + 计数 helper）——照交接稿 motion.html 的 6 条原则，
 * 全部尊重 [LocalReducedMotion]：减弱时弹簧/脉冲 → 交叉淡入、按压缩放 → 透明度、
 * 数字弧线 → 直接终态。各屏共用本基座。
 */

/**
 * 按压即反馈（§1）：pointer-down 立即 `scale(.96)`，[AnebMotion.Dur1] ease-out；松手回弹。
 * 附带 `clickable`（默认无涟漪，走 iOS 缩放反馈）。**减弱动效**下改为透明度变化（.7）不缩放。
 *
 * @param onClick 点击回调
 * @param enabled 是否可交互
 */
fun Modifier.pressable(
    onClick: () -> Unit,
    enabled: Boolean = true,
): Modifier = composed {
    val reduced = LocalReducedMotion.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val active = pressed && enabled
    val scale by animateFloatAsState(
        targetValue = if (active && !reduced) 0.96f else 1f,
        animationSpec = tween(AnebMotion.Dur1, easing = AnebMotion.EaseOut),
        label = "press-scale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (active && reduced) 0.7f else 1f,
        animationSpec = tween(AnebMotion.Dur1, easing = AnebMotion.EaseOut),
        label = "press-alpha",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.alpha = alpha
        }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}

/**
 * 脉冲环（§3 · 持续状态反馈）：GO 按钮外三层向外扩散呼吸环，scale 1→2.7、透明 .55→0，
 * 周期 2.6s，三层延迟 0/.9/1.8s。**减弱动效**下降级为单层静态淡环（无动画）。
 *
 * 画在元件背后（`drawBehind`），环径基于元件短边；供 idle 态 GO 按钮包裹使用。
 *
 * @param color 环色（通常品牌色）
 */
fun Modifier.pulseRing(color: Color): Modifier = composed {
    val reduced = LocalReducedMotion.current
    if (reduced) {
        drawBehind {
            val base = size.minDimension / 2f
            drawCircle(
                color = color.copy(alpha = 0.3f),
                radius = base * 1.5f,
                style = Stroke(width = 2f),
                center = Offset(size.width / 2f, size.height / 2f),
            )
        }
    } else {
        val transition = rememberInfiniteTransition(label = "pulse")
        val phases = listOf(0, 900, 1800).map { delay ->
            transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2600, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                    initialStartOffset = StartOffset(delay),
                ),
                label = "pulse-$delay",
            )
        }
        drawBehind {
            val base = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            phases.forEach { p ->
                val t = p.value
                // scale 1→2.7；透明在 70% 处归零（对齐 CSS keyframes）
                val scale = 1f + t * 1.7f
                val alpha = (0.55f * (1f - t / 0.7f)).coerceAtLeast(0f)
                if (alpha > 0f) {
                    drawCircle(
                        color = color.copy(alpha = alpha),
                        radius = base * scale,
                        style = Stroke(width = 2f),
                        center = center,
                    )
                }
            }
        }
    }
}

/**
 * 入场 settle（§4/入场原则）：从"接近最终态"温和落定，绝不从空白开始（缩略图/首帧安全）。
 * 常态：透明 0→1 + 轻微放大 .98→1（[AnebMotion.Dur4] ease-out）。**减弱动效**：仅交叉淡入。
 */
fun Modifier.settleIn(): Modifier = composed {
    val reduced = LocalReducedMotion.current
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, AnebMotion.easeOutTween(AnebMotion.Dur4))
    }
    val t = progress.value
    graphicsLayer {
        // 透明从 .0 起（略给初值避免完全消失，符合"绝不消失"）
        alpha = 0.001f + 0.999f * t
        if (!reduced) {
            val s = 0.98f + 0.02f * t
            scaleX = s
            scaleY = s
        }
    }
}

/**
 * 数字 settle（§入场 · 计数）：从 0 计到目标（[AnebMotion.Dur4] ease-out）；**减弱动效**下直接
 * 呈现终值。null 目标透传 null（值缺失显 "—" 由调用点处理，绝不顶 0）。
 *
 * @param target 目标整数分数；null = 未合成
 * @return 当前应显示的整数（动画帧值 / 终值）
 */
@Composable
fun animatedCount(target: Int?): Int? {
    if (target == null) return null
    val reduced = LocalReducedMotion.current
    val anim = remember { Animatable(if (reduced) target.toFloat() else 0f) }
    LaunchedEffect(target, reduced) {
        if (reduced) {
            anim.snapTo(target.toFloat())
        } else {
            anim.animateTo(target.toFloat(), AnebMotion.easeOutTween(AnebMotion.Dur4))
        }
    }
    return anim.value.roundToInt()
}
