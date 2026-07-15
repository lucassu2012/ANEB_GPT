package com.aneb.probe.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * ANEB 连续圆角层级（iOS squircle 质感近似）——照交接稿 tokens.css §5.2。
 *
 * Compose 无原生 squircle（超椭圆）图形，用 [RoundedCornerShape] 圆角近似；层级按面积
 * 递减：面越大圆角越大（卡片 22 → 瓦片 18 → 按钮 16 → sm 12 → xs 9），胶囊用 pill。
 * 数值与设计稿逐一对齐，跨屏统一取用（各屏依赖本基座）。
 */
object AnebShapes {
    val xl = RoundedCornerShape(28.dp) // --r-xl 大浮层/弹窗
    val card = RoundedCornerShape(22.dp) // --r-card 卡片
    val tile = RoundedCornerShape(18.dp) // --r-tile 瓦片
    val button = RoundedCornerShape(16.dp) // --r-btn 按钮
    val sm = RoundedCornerShape(12.dp) // --r-sm
    val xs = RoundedCornerShape(9.dp) // --r-xs 小角标
    val pill = RoundedCornerShape(percent = 50) // --r-pill 胶囊（999）

    /** 注入 M3 `MaterialTheme.shapes`：standard 组件圆角取 iOS 层级 */
    val material: Shapes = Shapes(
        extraSmall = xs,
        small = sm,
        medium = card,
        large = xl,
        extraLarge = RoundedCornerShape(34.dp),
    )
}

/**
 * ANEB 三级阴影 token（照 tokens.css §5.3；分层 · 大面越厚）。
 *
 * CSS 多层弥散阴影在 Compose 无直接等价（`shadow` 只吃单一 elevation + shape）。此处给出
 * 与设计意图对齐的 elevation 高度 token 供组件 `Modifier.shadow(elevation, shape)` 取用；
 * 大面浮层用 [level3]，卡片用 [level1]，抬升瓦片用 [level2]。
 */
object AnebElevation {
    val level1: Dp = 2.dp // --shadow-1 细描边阴影（卡片/瓦片）
    val level2: Dp = 10.dp // --shadow-2 中等浮起（抬升卡片/分段选中）
    val level3: Dp = 28.dp // --shadow-3 大面浮层（弹窗/底部操作区）
}
