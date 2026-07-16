package com.aneb.probe.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.ui.theme.AnebShapes
import com.aneb.probe.ui.theme.AnebTheme
import kotlin.math.cos
import kotlin.math.sin
import com.aneb.probe.ui.theme.AnebType

/** `ANEB_UI` 2026.07.15-2 的原生 Compose 视觉基元。 */

@Composable
fun AnebWordmark(modifier: Modifier = Modifier) {
    val colors = AnebTheme.colors
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Canvas(Modifier.size(17.dp)) {
            val path = Path().apply {
                moveTo(size.width * 0.14f, size.height * 0.64f)
                quadraticTo(size.width * 0.30f, size.height * 0.24f, size.width * 0.50f, size.height * 0.24f)
                quadraticTo(size.width * 0.70f, size.height * 0.24f, size.width * 0.86f, size.height * 0.64f)
            }
            drawPath(
                path,
                color = colors.muted.copy(alpha = 0.72f),
                style = Stroke(width = 1.15.dp.toPx(), cap = StrokeCap.Round),
            )
            drawCircle(colors.muted.copy(alpha = 0.72f), 1.35.dp.toPx(), Offset(size.width * 0.50f, size.height * 0.64f))
        }
        Text(
            "ANEB",
            fontSize = 14.sp,
            fontWeight = FontWeight(660),
            letterSpacing = 1.45.sp,
            color = colors.muted.copy(alpha = 0.76f),
        )
        Text(
            "PROBE",
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.3.sp,
            color = colors.muted.copy(alpha = 0.62f),
        )
    }
}

@Composable
fun AnebTopBar(
    modifier: Modifier = Modifier,
    showBack: Boolean = false,
    onBack: () -> Unit = {},
    showMenu: Boolean = false,
    onMenu: () -> Unit = {},
) {
    val colors = AnebTheme.colors
    Box(modifier = modifier.fillMaxWidth().height(52.dp)) {
        if (showBack) {
            Text(
                "‹",
                fontSize = 32.sp,
                fontWeight = FontWeight.Light,
                color = colors.ink.copy(alpha = 0.76f),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .pressable(onClick = onBack)
                    .padding(horizontal = 5.dp, vertical = 5.dp),
            )
        }
        AnebWordmark(Modifier.align(Alignment.Center))
        if (showMenu) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .pressable(onClick = onMenu)
                    .padding(7.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                repeat(3) {
                    Box(Modifier.width(18.dp).height(1.dp).background(colors.muted.copy(alpha = 0.75f)))
                }
            }
        }
    }
}

@Composable
fun AnebPageIntro(
    eyebrow: String,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val colors = AnebTheme.colors
    Column(modifier) {
        Text(
            eyebrow.uppercase(),
            style = AnebType.Overline,
            fontSize = 9.sp,
            letterSpacing = 1.35.sp,
            color = colors.faint,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            title,
            fontSize = 24.sp,
            fontWeight = FontWeight(570),
            letterSpacing = (-0.6).sp,
            color = colors.ink,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(5.dp))
            Text(subtitle, fontSize = 12.sp, lineHeight = 19.sp, color = colors.muted)
        }
    }
}

@Composable
fun AnebGradientCard(
    modifier: Modifier = Modifier,
    radius: Dp = 18.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = AnebTheme.colors
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(radius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(Color(0xD6191F3A), Color(0xDB0B0F21)),
                ),
            )
            .border(1.dp, colors.hairline, shape),
        content = content,
    )
}

@Composable
fun AnebScoreRing(
    score: Int?,
    fraction: Float,
    accent: Color,
    label: String,
    supporting: String,
    modifier: Modifier = Modifier,
    /** 需要小数或非分数读数时覆盖中央文本；null 时沿用 [score]。 */
    valueText: String? = null,
    strokeWidth: Dp = 9.dp,
    /** 非 null 时显示 SpeedTest 风格实时指针；结果页保持 null，原有分数环布局不变。 */
    needleFraction: Float? = null,
    /** 指针尚无样本时仍保留速度表排版，避免首个 250ms 窗口形成后文字跳位。 */
    speedometerLayout: Boolean = false,
) {
    val colors = AnebTheme.colors
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val inset = strokeWidth.toPx() / 2f + 1f
            val arcSize = androidx.compose.ui.geometry.Size(size.width - inset * 2f, size.height - inset * 2f)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = Color(0x57314567),
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(strokeWidth.toPx(), cap = StrokeCap.Butt),
            )
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(accent.copy(alpha = 0.72f), accent, accent.copy(alpha = 0.88f)),
                    center = center,
                ),
                startAngle = 135f,
                sweepAngle = 270f * fraction.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(strokeWidth.toPx(), cap = StrokeCap.Butt),
            )
            needleFraction?.coerceIn(0f, 1f)?.let { liveFraction ->
                val angleRad = Math.toRadians((135f + 270f * liveFraction).toDouble())
                val radius = size.minDimension * 0.34f
                val tip = Offset(
                    x = center.x + cos(angleRad).toFloat() * radius,
                    y = center.y + sin(angleRad).toFloat() * radius,
                )
                drawLine(
                    color = accent.copy(alpha = 0.18f),
                    start = center,
                    end = tip,
                    strokeWidth = strokeWidth.toPx() * 1.65f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFFBCECF2), accent),
                        start = center,
                        end = tip,
                    ),
                    start = center,
                    end = tip,
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawCircle(accent.copy(alpha = 0.16f), radius = 10.dp.toPx(), center = tip)
                drawCircle(accent, radius = 3.2.dp.toPx(), center = tip)
                drawCircle(Color(0xFFDAFAFF), radius = 5.dp.toPx(), center = center)
                drawCircle(accent, radius = 2.1.dp.toPx(), center = center)
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = if (speedometerLayout) Modifier.padding(top = 76.dp) else Modifier,
        ) {
            Text(
                valueText ?: score?.toString() ?: "—",
                style = AnebType.DisplayScore,
                fontSize = if (speedometerLayout) 36.sp else 56.sp,
                fontWeight = FontWeight.Medium,
                color = colors.ink,
            )
            Spacer(Modifier.height(if (speedometerLayout) 2.dp else 9.dp))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = accent)
            Spacer(Modifier.height(4.dp))
            Text(supporting, fontSize = 10.sp, color = colors.muted, textAlign = TextAlign.Center)
        }
    }
}

data class AnebMetric(val label: String, val value: String, val unit: String = "", val color: Color? = null)

@Composable
fun AnebMetricTrio(items: List<AnebMetric>, modifier: Modifier = Modifier) {
    val colors = AnebTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(width = 1.dp, color = colors.hairline, shape = AnebShapes.xs)
            .padding(vertical = 12.dp),
    ) {
        items.take(3).forEachIndexed { index, item ->
            if (index > 0) Box(Modifier.width(1.dp).height(45.dp).background(colors.hairline))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(item.label, fontSize = 10.sp, color = colors.muted)
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        item.value,
                        style = AnebType.StatValue,
                        fontSize = 18.sp,
                        fontWeight = FontWeight(560),
                        color = item.color ?: colors.ink,
                    )
                    if (item.unit.isNotBlank()) {
                        Spacer(Modifier.width(2.dp))
                        Text(item.unit, fontSize = 9.sp, color = colors.muted, modifier = Modifier.padding(bottom = 2.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun AnebSparkline(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
) {
    val colors = AnebTheme.colors
    Canvas(modifier) {
        for (i in 1..2) {
            val y = size.height * i / 3f
            drawLine(colors.hairline.copy(alpha = 0.55f), Offset(0f, y), Offset(size.width, y), 1f)
        }
        if (values.size < 2) return@Canvas
        val line = Path()
        val area = Path()
        values.forEachIndexed { index, raw ->
            val x = size.width * index / (values.size - 1)
            val y = size.height * (1f - raw.coerceIn(0f, 1f))
            if (index == 0) {
                line.moveTo(x, y)
                area.moveTo(x, size.height)
                area.lineTo(x, y)
            } else {
                line.lineTo(x, y)
                area.lineTo(x, y)
            }
        }
        if (fill) {
            area.lineTo(size.width, size.height)
            area.close()
            drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), Color.Transparent)))
        }
        drawPath(line, color, style = Stroke(1.7.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
fun AnebSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    val colors = AnebTheme.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.uppercase(),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.25.sp,
            color = colors.faint,
        )
        Spacer(Modifier.weight(1f))
        if (action != null) {
            Text(
                action,
                fontSize = 10.sp,
                color = colors.brand,
                modifier = Modifier.pressable(onClick = onAction).padding(vertical = 5.dp),
            )
        }
    }
}
