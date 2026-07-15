package com.aneb.probe.ui.components

import android.graphics.Paint
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.ui.theme.AnebMotion
import com.aneb.probe.ui.theme.AnebTheme
import com.aneb.probe.ui.theme.AnebType
import com.aneb.probe.ui.theme.LocalReducedMotion
import kotlin.math.cos
import kotlin.math.sin

/**
 * 180° 半盘指针表（Claude Design v2 主仪表）——照 SpeedTest 式半环仪表 Canvas 绘制：
 *
 * 几何（由 [modifier] 给定的画布尺寸自适应；圆心＝底边中点，半环向上鼓）：
 * - **轨**：底色半环（[AnebColors.surfaceMuted]），startAngle=180° 顺时针 sweep=180°，经正上方；
 *   环宽 = 画布宽 5%（下限 6px），圆头。
 * - **进度弧**：band 色半环，sweep = 180°·fraction，叠在轨上（idle 不画）。
 * - **21 刻度**：均匀铺满 180°（20 等分），每 5 格一根长主刻度、其余短次刻度；点亮到 fraction
 *   用 band 色、未亮/idle 用文本色 18% 灰；短线径向绘制在环内侧。
 * - **5 刻度数字**：主刻度处 0 / 25 / 50 / 75 / 100，[AnebColors.muted] 小字，绘在主刻度更内侧。
 * - **指针 + hub**：从圆心射向 fraction 角的锥形针（band 色）+ 中心 hub（band 外盘 + 面色内点）；
 *   idle 不画（首页 GO 由 [center] 槽承载）。
 *
 * 指针/进度/刻度点亮统一走 [animateFloatAsState] 扫动到 fraction；**减弱动效**
 * ([LocalReducedMotion]) 下 animationSpec 退化为 [snap]，直接落终态（无扫动）。
 *
 * @param fraction 0f..1f 归一化读数（分数/100 等，由调用点投影既有量，不改测量）
 * @param band 分档色（Grade → AnebColors.gradeColor，调用点传入）；着色进度弧/点亮刻度/指针/hub
 * @param idle true = 只画灰轨+灰刻度（不画进度/指针/hub），首页待机态；中心放 GO 按钮
 * @param center 中心内容槽（BoxScope；巨大分数 / GO 按钮 / 实时值等，由调用点对齐）
 */
@Composable
fun HalfGauge(
    fraction: Float,
    band: Color,
    idle: Boolean = false,
    modifier: Modifier = Modifier,
    center: @Composable BoxScope.() -> Unit = {},
) {
    val colors = AnebTheme.colors
    val reduced = LocalReducedMotion.current

    // 扫动到目标读数；减弱动效直接落终态（snap）——尊重 LocalReducedMotion。
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = if (reduced) snap() else AnebMotion.easeOutTween(AnebMotion.Dur4),
        label = "half-gauge",
    )
    val f = if (idle) 0f else animated

    val trackColor = colors.surfaceMuted
    val tickIdle = colors.ink.copy(alpha = 0.18f)
    val numberColor = colors.muted
    val hubInner = colors.surface
    // 刻度数字用原生 Canvas 文本；paint 复用，颜色/字号入帧再设（随主题/尺寸变化）。
    val labelPaint = remember {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }
    // 指针 Path 复用：持有单例，每帧绘制前 reset()——避免每帧 new Path() 抖 GC（labelPaint 同此办理）。
    val needlePath = remember { Path() }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            drawHalfDial(
                idle = idle,
                fraction = f,
                band = band,
                trackColor = trackColor,
                tickIdle = tickIdle,
                numberColor = numberColor,
                hubInner = hubInner,
                labelPaint = labelPaint,
                needlePath = needlePath,
            )
        }
        center()
    }
}

/** 半盘几何绘制（轨 → 进度弧 → 21 刻度 + 5 数字 → 指针 + hub）。 */
private fun DrawScope.drawHalfDial(
    idle: Boolean,
    fraction: Float,
    band: Color,
    trackColor: Color,
    tickIdle: Color,
    numberColor: Color,
    hubInner: Color,
    labelPaint: Paint,
    needlePath: Path,
) {
    val w = size.width
    val h = size.height
    val strokeW = (w * 0.05f).coerceAtLeast(6f)
    // 半径受宽（半环占满宽）与高（半环 + 环宽）双约束，取小者防裁切。
    val r = minOf((w - strokeW) / 2f, h - strokeW).coerceAtLeast(1f)
    val topPad = ((h - (r + strokeW)) / 2f).coerceAtLeast(0f) // 垂直居中余量
    val cx = w / 2f
    val cy = topPad + r + strokeW / 2f // hub（圆心）＝半环底边中点

    // ---- 轨（灰底半环） ----
    drawArc(
        color = trackColor,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(cx - r, cy - r),
        size = Size(r * 2f, r * 2f),
        style = Stroke(width = strokeW, cap = StrokeCap.Round),
    )

    // ---- 进度弧（band 色；idle 不画） ----
    if (!idle && fraction > 0f) {
        drawArc(
            color = band,
            startAngle = 180f,
            sweepAngle = 180f * fraction,
            useCenter = false,
            topLeft = Offset(cx - r, cy - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(width = strokeW, cap = StrokeCap.Round),
        )
    }

    // ---- 21 刻度（20 等分，每 5 格主刻度）+ 5 主刻度数字 ----
    val ringInner = r - strokeW / 2f
    val tickOuter = ringInner - r * 0.02f
    labelPaint.textSize = r * 0.115f
    labelPaint.color = numberColor.toArgb()
    val fm = labelPaint.fontMetrics
    val baselineAdj = (fm.ascent + fm.descent) / 2f
    for (i in 0..20) {
        val theta = 180.0 + i * 9.0 // 180°→360°
        val rad = Math.toRadians(theta)
        val ux = cos(rad).toFloat()
        val uy = sin(rad).toFloat()
        val major = i % 5 == 0
        val tickLen = if (major) r * 0.11f else r * 0.06f
        val tickInner = tickOuter - tickLen
        val lit = !idle && (i.toFloat() / 20f) <= fraction + 1e-3f
        drawLine(
            color = when {
                idle -> tickIdle
                lit -> band.copy(alpha = 0.95f)
                else -> tickIdle
            },
            start = Offset(cx + ux * tickInner, cy + uy * tickInner),
            end = Offset(cx + ux * tickOuter, cy + uy * tickOuter),
            strokeWidth = if (major) strokeW * 0.14f else strokeW * 0.09f,
            cap = StrokeCap.Round,
        )
        if (major) {
            val rNum = tickInner - r * 0.11f
            val px = cx + ux * rNum
            val py = cy + uy * rNum
            drawContext.canvas.nativeCanvas.drawText(
                (i * 5).toString(),
                px,
                py - baselineAdj,
                labelPaint,
            )
        }
    }

    // ---- 指针 + hub（idle 不画） ----
    if (!idle) {
        val theta = Math.toRadians(180.0 + 180.0 * fraction)
        val ux = cos(theta).toFloat()
        val uy = sin(theta).toFloat()
        val perpX = -uy // 垂直于针向（+90°）
        val perpY = ux
        val needleLen = r * 0.80f
        val tailLen = r * 0.16f
        val baseHalf = strokeW * 0.55f
        val tip = Offset(cx + ux * needleLen, cy + uy * needleLen)
        val tail = Offset(cx - ux * tailLen, cy - uy * tailLen)
        val baseA = Offset(cx + perpX * baseHalf, cy + perpY * baseHalf)
        val baseB = Offset(cx - perpX * baseHalf, cy - perpY * baseHalf)
        val needle = needlePath.apply {
            reset()
            moveTo(tip.x, tip.y)
            lineTo(baseA.x, baseA.y)
            lineTo(tail.x, tail.y)
            lineTo(baseB.x, baseB.y)
            close()
        }
        drawPath(needle, color = band)
        val rHub = strokeW * 0.85f
        drawCircle(color = band, radius = rHub, center = Offset(cx, cy))
        drawCircle(color = hubInner, radius = rHub * 0.42f, center = Offset(cx, cy))
    }
}

// ------------------------------------------------------------------
// Preview（debugImplementation ui-tooling；不进 release）
// ------------------------------------------------------------------

@Preview(widthDp = 320, heightDp = 210, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PreviewHalfGaugeRunning() {
    AnebTheme(darkTheme = true) {
        HalfGauge(fraction = 0.62f, band = AnebTheme.colors.good) {
            androidx.compose.material3.Text(
                text = "62",
                style = AnebType.DisplayScore,
                fontSize = 56.sp,
                color = AnebTheme.colors.good,
            )
        }
    }
}

@Preview(widthDp = 320, heightDp = 210, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PreviewHalfGaugeIdle() {
    AnebTheme(darkTheme = true) {
        HalfGauge(fraction = 0f, band = AnebTheme.colors.neutral, idle = true) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.size(60.dp),
            )
        }
    }
}
