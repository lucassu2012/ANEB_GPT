package com.aneb.probe.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * ANEB 语义色扩展——Material3 的 ColorScheme 只有通用角色（primary/surface…），装不下
 * "四级语义色 + 分级映射 + iOS 材质/柔和底"这套领域色。用一个 [AnebColors] 经
 * CompositionLocal 下发，与 M3 ColorScheme 并存：M3 角色供标准组件，AnebColors 供
 * 仪表/分级/瓦片/玻璃材质。
 *
 * 固定品牌（dynamicColor=false）：即装即用、跨机型视觉一致，不吃 Android 12+ 动态取色。
 * 深浅色双主题：[darkAnebColors] / [lightAnebColors]，由系统深浅色自动切换（值照 tokens.css）。
 */
@Immutable
data class AnebColors(
    // 品牌（交互态）
    val brand: Color,
    val brand2: Color,
    val brandPress: Color,
    // 四级语义
    val excellent: Color,
    val good: Color,
    val fair: Color,
    val poor: Color,
    val neutral: Color,
    // 四级语义柔和底（chip / 角标背景）
    val excellentSoft: Color,
    val goodSoft: Color,
    val fairSoft: Color,
    val poorSoft: Color,
    // 骨架 / 面
    val background: Color,
    val surface: Color, // 卡片 acard
    val surface2: Color, // 卡片2 acard2
    val surfaceElevated: Color, // 抬升卡片（= 卡片色，配阴影抬升）
    val surfaceMuted: Color, // 段控轨 / 进度条底（acard2 调）
    val hairline: Color,
    val material: Color, // 毛玻璃底色（amat；半透）
    // 文本
    val ink: Color,
    val muted: Color,
    val faint: Color,
) {
    /**
     * 分级 → 语义色（单一事实来源；null/未知 → [neutral]）。
     * 接受 [Grade] 强类型；分级串入口用 [Grade.fromKey] 先转换。
     */
    fun gradeColor(grade: Grade?): Color = when (grade) {
        Grade.Excellent -> excellent
        Grade.Good -> good
        Grade.Fair -> fair
        Grade.Poor -> poor
        null -> neutral
    }

    /** 分级字符串（KpiGrading 常量）→ 语义色；便利重载，内部走 [Grade.fromKey] */
    fun gradeColor(gradeKey: String?): Color = gradeColor(Grade.fromKey(gradeKey))

    /** 分级 → 柔和底色（chip/角标背景；null → 中性灰 12% 垫色） */
    fun gradeSoft(grade: Grade?): Color = when (grade) {
        Grade.Excellent -> excellentSoft
        Grade.Good -> goodSoft
        Grade.Fair -> fairSoft
        Grade.Poor -> poorSoft
        null -> neutral.copy(alpha = 0.12f)
    }
}

val darkAnebColors = AnebColors(
    brand = AnebPalette.Brand.Base,
    brand2 = AnebPalette.Brand.Hover,
    brandPress = AnebPalette.Brand.Press,
    excellent = AnebPalette.Semantic.Excellent,
    good = AnebPalette.Semantic.Good,
    fair = AnebPalette.Semantic.Fair,
    poor = AnebPalette.Semantic.Poor,
    neutral = AnebPalette.Neutral,
    excellentSoft = AnebPalette.Semantic.Excellent.copy(alpha = 0.16f),
    goodSoft = AnebPalette.Semantic.Good.copy(alpha = 0.16f),
    fairSoft = AnebPalette.Semantic.Fair.copy(alpha = 0.16f),
    poorSoft = AnebPalette.Semantic.Poor.copy(alpha = 0.16f),
    background = AnebPalette.Dark.Background,
    surface = AnebPalette.Dark.Card,
    surface2 = AnebPalette.Dark.Card2,
    surfaceElevated = AnebPalette.Dark.Card,
    surfaceMuted = AnebPalette.Dark.Card2,
    hairline = AnebPalette.Dark.Hairline,
    material = AnebPalette.Dark.Material,
    ink = AnebPalette.Dark.Ink,
    muted = AnebPalette.Dark.Muted,
    faint = AnebPalette.Dark.Faint,
)

val lightAnebColors = AnebColors(
    brand = AnebPalette.Brand.LightBase,
    brand2 = AnebPalette.Brand.LightHover,
    brandPress = AnebPalette.Brand.Press,
    excellent = AnebPalette.Semantic.ExcellentLight,
    good = AnebPalette.Semantic.GoodLight,
    fair = AnebPalette.Semantic.FairLight,
    poor = AnebPalette.Semantic.PoorLight,
    neutral = AnebPalette.NeutralLight,
    excellentSoft = AnebPalette.Semantic.ExcellentLight.copy(alpha = 0.14f),
    goodSoft = AnebPalette.Semantic.GoodLight.copy(alpha = 0.14f),
    fairSoft = AnebPalette.Semantic.FairLight.copy(alpha = 0.14f),
    poorSoft = AnebPalette.Semantic.PoorLight.copy(alpha = 0.14f),
    background = AnebPalette.Light.Background,
    surface = AnebPalette.Light.Card,
    surface2 = AnebPalette.Light.Card2,
    surfaceElevated = AnebPalette.Light.Card,
    surfaceMuted = AnebPalette.Light.Card2,
    hairline = AnebPalette.Light.Hairline,
    material = AnebPalette.Light.Material,
    ink = AnebPalette.Light.Ink,
    muted = AnebPalette.Light.Muted,
    faint = AnebPalette.Light.Faint,
)

private val LocalAnebColors = staticCompositionLocalOf { darkAnebColors }

// ---- M3 ColorScheme（标准组件用；iOS 系统蓝作 primary，语义面/文本对齐 AnebColors）----

private val DarkColorScheme = darkColorScheme(
    primary = AnebPalette.Brand.Base,
    onPrimary = Color.White,
    secondary = AnebPalette.Brand.Hover,
    background = AnebPalette.Dark.Background,
    onBackground = AnebPalette.Dark.Ink,
    surface = AnebPalette.Dark.Card,
    onSurface = AnebPalette.Dark.Ink,
    surfaceVariant = AnebPalette.Dark.Card2,
    onSurfaceVariant = AnebPalette.Dark.Muted,
    outline = AnebPalette.Dark.Hairline,
    error = AnebPalette.Semantic.Poor,
    onError = Color.White,
)

private val LightColorScheme = lightColorScheme(
    primary = AnebPalette.Brand.LightBase,
    onPrimary = Color.White,
    secondary = AnebPalette.Brand.LightHover,
    background = AnebPalette.Light.Background,
    onBackground = AnebPalette.Light.Ink,
    surface = AnebPalette.Light.Card,
    onSurface = AnebPalette.Light.Ink,
    surfaceVariant = AnebPalette.Light.Card2,
    onSurfaceVariant = AnebPalette.Light.Muted,
    outline = AnebPalette.Light.Hairline,
    error = AnebPalette.Semantic.PoorLight,
    onError = Color.White,
)

/**
 * ANEB 主题根。用法：`AnebTheme { … }`；语义色取用 `AnebTheme.colors.excellent` /
 * `AnebTheme.colors.gradeColor(grade)`。
 *
 * 主题接入点（各屏依赖）：
 * - [AnebColors]（语义/材质色）经 CompositionLocal 下发；
 * - iOS 连续圆角 [AnebShapes.material] 注入 M3 `shapes`；
 * - 无障碍"减弱动效"经 [LocalReducedMotion] 下发（由系统动画缩放推断），
 *   动效基元（Modifiers/HalfGauge 等）据此降级为交叉淡入 / 直接终态。
 *
 * @param darkTheme 缺省跟随系统深浅色；测试/预览可显式覆盖。
 * @param reducedMotion 缺省由系统动画缩放推断；测试/预览可显式覆盖。
 */
@Composable
fun AnebTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    reducedMotion: Boolean = rememberReducedMotion(),
    content: @Composable () -> Unit,
) {
    val anebColors = if (darkTheme) darkAnebColors else lightAnebColors
    CompositionLocalProvider(
        LocalAnebColors provides anebColors,
        LocalReducedMotion provides reducedMotion,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = AnebType.Typography,
            shapes = AnebShapes.material,
            content = content,
        )
    }
}

/** 语义色访问器：`AnebTheme.colors.…`（M3 `MaterialTheme.colorScheme` 的领域色姊妹） */
object AnebTheme {
    val colors: AnebColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAnebColors.current
}
