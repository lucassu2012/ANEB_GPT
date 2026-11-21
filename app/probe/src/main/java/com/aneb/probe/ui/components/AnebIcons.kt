package com.aneb.probe.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 极简精密几何矢量图标集：完全替代 emoji，统一 ANEB 深色精密仪器视觉风格。
 * 纯 Compose Canvas 矢量路径绘制，零外部图片依赖，任意 DPI 保持锐利。
 */
enum class AnebGlyph {
    Chat,
    Code,
    Image,
    Document,
    Voice,
    Video,
    Load,
    Pulse,
    Check,
    Alert,
}

@Composable
fun AnebIcon(
    glyph: AnebGlyph,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    strokeDp: Dp = 1.4.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val s = Stroke(width = strokeDp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (glyph) {
            AnebGlyph.Chat -> {
                val p = Path().apply {
                    moveTo(w * 0.16f, h * 0.22f)
                    lineTo(w * 0.84f, h * 0.22f)
                    lineTo(w * 0.84f, h * 0.68f)
                    lineTo(w * 0.44f, h * 0.68f)
                    lineTo(w * 0.24f, h * 0.84f)
                    lineTo(w * 0.24f, h * 0.68f)
                    lineTo(w * 0.16f, h * 0.68f)
                    close()
                }
                drawPath(p, tint, style = s)
                drawLine(tint, Offset(w * 0.32f, h * 0.40f), Offset(w * 0.68f, h * 0.40f), strokeWidth = strokeDp.toPx(), cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.32f, h * 0.54f), Offset(w * 0.56f, h * 0.54f), strokeWidth = strokeDp.toPx(), cap = StrokeCap.Round)
            }
            AnebGlyph.Code -> {
                val left = Path().apply {
                    moveTo(w * 0.36f, h * 0.28f)
                    lineTo(w * 0.16f, h * 0.50f)
                    lineTo(w * 0.36f, h * 0.72f)
                }
                val right = Path().apply {
                    moveTo(w * 0.64f, h * 0.28f)
                    lineTo(w * 0.84f, h * 0.50f)
                    lineTo(w * 0.64f, h * 0.72f)
                }
                drawPath(left, tint, style = s)
                drawPath(right, tint, style = s)
                drawLine(tint, Offset(w * 0.56f, h * 0.24f), Offset(w * 0.44f, h * 0.76f), strokeWidth = strokeDp.toPx(), cap = StrokeCap.Round)
            }
            AnebGlyph.Image -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.14f, h * 0.18f),
                    size = Size(w * 0.72f, h * 0.64f),
                    cornerRadius = CornerRadius(w * 0.12f, w * 0.12f),
                    style = s,
                )
                drawCircle(tint, radius = w * 0.07f, center = Offset(w * 0.34f, h * 0.38f))
                val mountain = Path().apply {
                    moveTo(w * 0.20f, h * 0.72f)
                    lineTo(w * 0.44f, h * 0.50f)
                    lineTo(w * 0.58f, h * 0.63f)
                    lineTo(w * 0.70f, h * 0.52f)
                    lineTo(w * 0.80f, h * 0.72f)
                }
                drawPath(mountain, tint, style = s)
            }
            AnebGlyph.Document -> {
                val page = Path().apply {
                    moveTo(w * 0.24f, h * 0.14f)
                    lineTo(w * 0.60f, h * 0.14f)
                    lineTo(w * 0.78f, h * 0.32f)
                    lineTo(w * 0.78f, h * 0.86f)
                    lineTo(w * 0.24f, h * 0.86f)
                    close()
                }
                drawPath(page, tint, style = s)
                drawLine(tint, Offset(w * 0.36f, h * 0.46f), Offset(w * 0.66f, h * 0.46f), strokeWidth = strokeDp.toPx(), cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.36f, h * 0.60f), Offset(w * 0.66f, h * 0.60f), strokeWidth = strokeDp.toPx(), cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.36f, h * 0.74f), Offset(w * 0.54f, h * 0.74f), strokeWidth = strokeDp.toPx(), cap = StrokeCap.Round)
            }
            AnebGlyph.Voice -> {
                val heights = listOf(0.26f, 0.52f, 0.76f, 0.48f, 0.28f)
                heights.forEachIndexed { idx, frac ->
                    val x = w * (0.18f + idx * 0.16f)
                    val half = h * frac * 0.5f
                    drawLine(
                        color = tint,
                        start = Offset(x, h * 0.5f - half),
                        end = Offset(x, h * 0.5f + half),
                        strokeWidth = (strokeDp * 1.2f).toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
            AnebGlyph.Video -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.22f, h * 0.12f),
                    size = Size(w * 0.56f, h * 0.76f),
                    cornerRadius = CornerRadius(w * 0.12f, w * 0.12f),
                    style = s,
                )
                val tri = Path().apply {
                    moveTo(w * 0.44f, h * 0.38f)
                    lineTo(w * 0.62f, h * 0.50f)
                    lineTo(w * 0.44f, h * 0.62f)
                    close()
                }
                drawPath(tri, tint, style = s)
            }
            AnebGlyph.Load -> {
                drawLine(tint, Offset(w * 0.16f, h * 0.30f), Offset(w * 0.84f, h * 0.30f), strokeWidth = strokeDp.toPx(), cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.16f, h * 0.50f), Offset(w * 0.68f, h * 0.50f), strokeWidth = strokeDp.toPx(), cap = StrokeCap.Round)
                val wave = Path().apply {
                    moveTo(w * 0.16f, h * 0.74f)
                    lineTo(w * 0.38f, h * 0.74f)
                    lineTo(w * 0.48f, h * 0.58f)
                    lineTo(w * 0.58f, h * 0.82f)
                    lineTo(w * 0.68f, h * 0.74f)
                    lineTo(w * 0.84f, h * 0.74f)
                }
                drawPath(wave, tint, style = s)
            }
            AnebGlyph.Pulse -> {
                val p = Path().apply {
                    moveTo(w * 0.12f, h * 0.54f)
                    lineTo(w * 0.30f, h * 0.54f)
                    lineTo(w * 0.42f, h * 0.22f)
                    lineTo(w * 0.56f, h * 0.78f)
                    lineTo(w * 0.68f, h * 0.44f)
                    lineTo(w * 0.76f, h * 0.54f)
                    lineTo(w * 0.88f, h * 0.54f)
                }
                drawPath(p, tint, style = s)
            }
            AnebGlyph.Check -> {
                val p = Path().apply {
                    moveTo(w * 0.20f, h * 0.52f)
                    lineTo(w * 0.42f, h * 0.74f)
                    lineTo(w * 0.80f, h * 0.28f)
                }
                drawPath(p, tint, style = s)
            }
            AnebGlyph.Alert -> {
                val tri = Path().apply {
                    moveTo(w * 0.50f, h * 0.16f)
                    lineTo(w * 0.86f, h * 0.82f)
                    lineTo(w * 0.14f, h * 0.82f)
                    close()
                }
                drawPath(tri, tint, style = s)
                drawLine(tint, Offset(w * 0.50f, h * 0.42f), Offset(w * 0.50f, h * 0.62f), strokeWidth = strokeDp.toPx(), cap = StrokeCap.Round)
                drawCircle(tint, radius = strokeDp.toPx() * 0.65f, center = Offset(w * 0.50f, h * 0.72f))
            }
        }
    }
}
