package com.aneb.probe.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * ANEB 动效基元——照交接稿 tokens.css §5.6 与 motion.html 的 6 条原则。
 *
 * 缓动/时长为**单一事实来源**，全项目动画取此常量（各屏依赖本基座）。默认临界阻尼弹簧
 * （无过冲，response .3–.4）；只有手势本身带动量（甩/拖放）才用 [EaseSpring] 轻微过冲。
 *
 * "减弱动效"经 [LocalReducedMotion] 全局下发（见 [rememberReducedMotion]）：弹簧/脉冲 →
 * 交叉淡入、按压缩放 → 透明度、数字与弧线 → 直接终态。反馈更温和，绝不消失。
 */
object AnebMotion {

    // ---- 缓动（cubic-bezier，逐值对齐 tokens.css） ----
    val EaseOut: Easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f) // --ease-out
    val EaseInOut: Easing = CubicBezierEasing(0.65f, 0f, 0.35f, 1f) // --ease-inout
    val EaseSpring: Easing = CubicBezierEasing(0.34f, 1.42f, 0.5f, 1f) // --ease-spring（仅手势动量）

    // ---- 时长（毫秒；--dur-1..4） ----
    const val Dur1: Int = 120 // .12s 按压反馈
    const val Dur2: Int = 240 // .24s 段控/小过渡
    const val Dur3: Int = 400 // .4s  中过渡
    const val Dur4: Int = 600 // .6s  仪表弧/入场 settle

    /** 临界阻尼弹簧（默认无过冲，response ≈ .34）——数字/尺寸 settle 首选 */
    fun <T> criticalSpring(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 1f,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** 轻微过冲弹簧（仅手势动量：甩/拖放）——对齐 --ease-spring 的 1.42 过冲感 */
    fun <T> overshootSpring(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.5f,
        stiffness = Spring.StiffnessMedium,
    )

    /** 标准 ease-out 补间（时长毫秒），减弱动效时调用点应改用 [snap] 语义（时长 0） */
    fun <T> easeOutTween(durationMs: Int = Dur4): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMs, easing = EaseOut)
}

/**
 * "减弱动效"偏好（无障碍）。true = 用户要求减少动画：弹簧/脉冲降级交叉淡入、按压缩放降级
 * 透明度、数字/弧线直接落终态。由 [AnebTheme] 在根部注入（默认 [rememberReducedMotion]）。
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * 从系统动画缩放（Settings.Global.ANIMATOR_DURATION_SCALE == 0）推断"减弱动效"。
 *
 * Android 无 iOS 那样的独立"减弱动效"开关；开发者选项/无障碍关闭动画会把动画缩放置 0，
 * 这是最贴近"用户不想要动画"的系统信号。Compose 的动画时钟本身也会因缩放 0 把 tween 时长
 * 缩到 0（直接落终态），[LocalReducedMotion] 则让**非补间**的手写动效（脉冲环/入场）也能据此
 * 走"交叉淡入 / 直接终态"分支。读取一次即缓存（系统级设置本轮组合内稳定）。
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val scale = runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f)
        scale == 0f
    }
}
