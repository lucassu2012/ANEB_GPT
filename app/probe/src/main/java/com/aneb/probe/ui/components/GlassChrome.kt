package com.aneb.probe.ui.components

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aneb.probe.ui.theme.AnebTheme

/**
 * 毛玻璃 chrome（顶栏/底部操作区）——照交接稿 tokens.css §5.4 与 §4 布局：内容从其下方滚过，
 * chrome 为半透材质浮层，不用实心色条切走固定高度。
 *
 * 材质实现（毛玻璃 API31/降级方案）：
 * - **API 31+（RenderEffect 可用，真机 P40 Pro 支持）**：半透材质底色 [AnebColors.material]
 *   叠加 [glassBlur] —— 用 `Modifier.graphicsLayer { renderEffect = createBlurEffect(saturate 近似) }`
 *   软化材质层，读作"真实材质"。⚠️ Compose 无原生 backdrop-filter：renderEffect 作用于层
 *   **自身**绘制而非其背后像素，故此处是"半透材质 + 软化"的高保真近似；若需 iOS 那样的真·背景
 *   模糊（采样身后已绘内容），须引入 backdrop 捕获（haze 类）方案，留待后续。
 * - **API < 31（降级）**：仅半透遮罩（scrim）——同一 [AnebColors.material] 半透底色、稍加不透明，
 *   不做模糊；视觉降级但语义一致（内容仍从下方滚过、无硬分割线）。
 *
 * @param strong true = 加厚材质（大浮层/弹窗：blur 40 近似）；false = 常规（顶/底栏：blur 24）
 * @param content chrome 内容（标题行 / 按钮行），绘制在材质层之上、不被模糊
 */
@Composable
fun GlassChrome(
    modifier: Modifier = Modifier,
    strong: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = AnebTheme.colors
    // API<31 降级：无模糊，稍加不透明度补偿"糊"的缺失，保清晰的遮罩读感
    val supportsBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val panelColor = if (supportsBlur) colors.material else colors.material.copy(alpha = 0.9f)
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .glassBlur(strong = strong)
                .background(panelColor),
        )
        content()
    }
}

/**
 * 玻璃模糊层修饰（API31+ 生效；旧版 no-op 走 scrim 降级）。半径对齐 tokens：
 * 常规 blur(24) / 加厚 blur(40)。TileMode.DECAL 避免边缘拉伸出实边。
 */
fun Modifier.glassBlur(strong: Boolean): Modifier {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return this
    val radiusPx = if (strong) 40f else 24f
    return this.graphicsLayer {
        renderEffect = RenderEffect
            .createBlurEffect(radiusPx, radiusPx, Shader.TileMode.DECAL)
            .asComposeRenderEffect()
    }
}

/**
 * 滚动边缘渐隐（§5：内容进入玻璃浮层区用 mask 渐隐，不压硬分割线）。用离屏合成 + DstIn
 * 竖向渐变把顶部 [topFade]、底部 [bottomFade] 高度内的内容淡出——近似 CSS `mask-image`。
 *
 * 用于可滚动内容容器：顶 ~96dp、底 ~72dp（交接稿默认）淡出到玻璃 chrome 下。
 */
fun Modifier.fadingEdges(
    topFade: Dp = 96.dp,
    bottomFade: Dp = 72.dp,
): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val topPx = topFade.toPx()
        val botPx = bottomFade.toPx()
        if (topPx > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black),
                    startY = 0f,
                    endY = topPx,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
        if (botPx > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Black, Color.Transparent),
                    startY = size.height - botPx,
                    endY = size.height,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }
