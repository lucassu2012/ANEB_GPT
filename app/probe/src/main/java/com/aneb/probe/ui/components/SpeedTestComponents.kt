package com.aneb.probe.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.ui.theme.AnebElevation
import com.aneb.probe.ui.theme.AnebShapes
import com.aneb.probe.ui.theme.AnebTheme
import com.aneb.probe.ui.theme.AnebType
import com.aneb.probe.ui.theme.Grade
import com.aneb.probe.ui.theme.LocalReducedMotion
import kotlin.math.sin

// ---------------------------------------------------------------------------
// SpeedTest 组件族（Claude Design v2）——连接横幅 / 阶段步进器 / 你↔节点 / 实时吞吐折线 /
// 结果大数字行 / 底部 TabBar(GO 凸起)。全部走 AnebTheme.colors 语义色、AnebShapes 圆角、
// GlassChrome 材质；箭头/ECG/播放/信号/时钟图标用 Compose Canvas path 画（设计无位图）。
// 减弱动效([LocalReducedMotion])：流动点/端点脉冲 → 静态终态。纯展示层，不碰测量语义。
// ---------------------------------------------------------------------------

/** 阶段步进器单步状态：已完成 / 进行中 / 待办。 */
enum class StepState { Done, On, Todo }

/** 结果行图标语义：下行(箭头↓) / 上行(箭头↑) / 卡顿(ECG 脉冲)。 */
enum class ResIcon { Down, Up, Stall }

/** 底部主 tab：测试(GO 凸起) / 历史 / 设置。 */
enum class MainTab(val label: String) {
    Test("测试"),
    History("历史"),
    Settings("设置"),
}

/**
 * 结果大数字行数据项。
 * @param grade 该量分级（决定图标底/值色与角标；null → 中性灰，R-10 失败样本不发档色）
 */
data class StResItem(
    val icon: ResIcon,
    val name: String,
    val value: String,
    val unit: String,
    val grade: Grade?,
)

// ---------------------------------------------------------------------------
// StBanner —— 连接横幅（"已连接 · ISP" + 副信息 + 右侧动作）
// ---------------------------------------------------------------------------

/**
 * 连接横幅：左侧状态点（[dotColor]，默认优档绿）+ ISP 名与副信息 + 右侧动作文字按钮。
 * iOS 卡面：[AnebShapes.button] 圆角、[AnebColors.surface] 面、hairline 描边。
 *
 * @param dotColor 状态点色；默认（Color.Unspecified）解析为 [AnebColors.excellent]（优档绿）
 */
@Composable
fun StBanner(
    isp: String,
    sub: String,
    action: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    dotColor: Color = Color.Unspecified,
) {
    val colors = AnebTheme.colors
    val dot = if (dotColor == Color.Unspecified) colors.excellent else dotColor
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(AnebShapes.button)
            .background(colors.surface)
            .border(1.dp, colors.hairline, AnebShapes.button)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(10.dp)) {
            val rc = size.minDimension / 2f
            drawCircle(dot.copy(alpha = 0.25f), radius = rc)
            drawCircle(dot, radius = rc * 0.6f)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(isp, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.ink, maxLines = 1)
            Text(sub, style = AnebType.Caption, color = colors.muted, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = action,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.brand,
            modifier = Modifier.pressable(onClick = onAction),
        )
    }
}

// ---------------------------------------------------------------------------
// StStep —— 阶段步进器（节点 + 连接线 + 标签）
// ---------------------------------------------------------------------------

/**
 * 阶段步进器：等宽节点行 + 节点间连接线 + 标签行。Done=band 实心带勾、On=band 环+halo、
 * Todo=灰空心。连接线在前一步 Done 时染 band。
 */
@Composable
fun StStep(
    labels: List<String>,
    states: List<StepState>,
    band: Color,
    modifier: Modifier = Modifier,
) {
    val colors = AnebTheme.colors
    val n = labels.size.coerceAtLeast(1)
    val nodeSize = 26.dp
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(nodeSize)) {
            // 连接线（画在节点之后、被节点圆盖住两端）
            Canvas(Modifier.matchParentSize()) {
                val cellW = size.width / n
                val cy = size.height / 2f
                val rNode = nodeSize.toPx() / 2f
                for (i in 0 until n - 1) {
                    val x1 = cellW * (i + 0.5f) + rNode
                    val x2 = cellW * (i + 1 + 0.5f) - rNode
                    val done = states.getOrNull(i) == StepState.Done
                    drawLine(
                        color = if (done) band else colors.hairline,
                        start = Offset(x1, cy),
                        end = Offset(x2, cy),
                        strokeWidth = 2.5.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
            Row(Modifier.fillMaxWidth()) {
                labels.indices.forEach { i ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        StepNode(states.getOrElse(i) { StepState.Todo }, band, nodeSize)
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            labels.indices.forEach { i ->
                val st = states.getOrElse(i) { StepState.Todo }
                Text(
                    text = labels[i],
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontSize = 10.5.sp,
                    color = if (st == StepState.Todo) colors.muted else colors.ink,
                    fontWeight = if (st == StepState.On) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun StepNode(state: StepState, band: Color, size: Dp) {
    val colors = AnebTheme.colors
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        when (state) {
            StepState.Done -> {
                drawCircle(band, r, c)
                val check = Path().apply {
                    moveTo(c.x - r * 0.42f, c.y + r * 0.02f)
                    lineTo(c.x - r * 0.08f, c.y + r * 0.34f)
                    lineTo(c.x + r * 0.46f, c.y - r * 0.34f)
                }
                drawPath(
                    check,
                    color = colors.surface,
                    style = Stroke(width = r * 0.24f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            StepState.On -> {
                drawCircle(band.copy(alpha = 0.18f), r, c)
                drawCircle(band, r * 0.72f, c, style = Stroke(width = r * 0.22f))
                drawCircle(band, r * 0.30f, c)
            }
            StepState.Todo -> {
                drawCircle(colors.surfaceMuted, r, c)
                drawCircle(colors.hairline, r, c, style = Stroke(width = r * 0.14f))
                drawCircle(colors.faint, r * 0.22f, c)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// StLink —— 你 ↔ 节点（两端 + 流动连线）
// ---------------------------------------------------------------------------

/**
 * 你↔节点：左端设备（手机图标 + [deviceLabel]）、右端节点（球状 + [nodeLabel]）、中间 band 色
 * 流动连线（三点从左向右推进）。**减弱动效**下流动点静置（无动画）。
 */
@Composable
fun StLink(
    deviceLabel: String,
    nodeLabel: String,
    band: Color,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Endpoint(EndpointKind.Device, deviceLabel, band)
        LinkLine(band, Modifier.weight(1f))
        Endpoint(EndpointKind.Node, nodeLabel, band)
    }
}

private enum class EndpointKind { Device, Node }

@Composable
private fun Endpoint(kind: EndpointKind, label: String, band: Color) {
    val colors = AnebTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(colors.surfaceMuted)
                .border(1.dp, colors.hairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            EndpointGlyph(kind, band, 22.dp)
        }
        Spacer(Modifier.height(5.dp))
        Text(label, style = AnebType.Caption, color = colors.muted, maxLines = 1)
    }
}

@Composable
private fun EndpointGlyph(kind: EndpointKind, tint: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        when (kind) {
            EndpointKind.Device -> {
                val rectW = w * 0.5f
                val rectH = h * 0.74f
                val left = (w - rectW) / 2f
                val top = (h - rectH) / 2f
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(left, top),
                    size = Size(rectW, rectH),
                    cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
                    style = Stroke(width = w * 0.09f),
                )
                drawLine(
                    tint,
                    start = Offset(w * 0.5f - rectW * 0.18f, top + rectH * 0.86f),
                    end = Offset(w * 0.5f + rectW * 0.18f, top + rectH * 0.86f),
                    strokeWidth = w * 0.07f,
                    cap = StrokeCap.Round,
                )
            }
            EndpointKind.Node -> {
                val r = w * 0.36f
                val c = Offset(w / 2f, h / 2f)
                val st = w * 0.08f
                drawCircle(tint, r, c, style = Stroke(width = st))
                drawLine(tint, Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = st * 0.8f)
                drawOval(
                    tint,
                    topLeft = Offset(c.x - r * 0.45f, c.y - r),
                    size = Size(r * 0.9f, r * 2f),
                    style = Stroke(width = st * 0.8f),
                )
            }
        }
    }
}

@Composable
private fun LinkLine(band: Color, modifier: Modifier) {
    val colors = AnebTheme.colors
    val reduced = LocalReducedMotion.current
    // 无条件创建 transition（避免条件化组合调用）；减弱动效时相位固定，流动点静置。
    val transition = rememberInfiniteTransition(label = "link")
    val animState = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "link-phase",
    )
    // 动画值在 DrawScope 闭包内读取：每帧只失效重绘、不重组外层宿主（测试期避免逐帧全屏重组）。
    Canvas(modifier.height(44.dp)) {
        val phase = if (reduced) 0f else animState.value
        drawLink(phase, band, colors.hairline)
    }
}

private fun DrawScope.drawLink(phase: Float, band: Color, hairline: Color) {
    val cy = size.height / 2f
    val x0 = 2f
    val x1 = size.width - 2f
    drawLine(hairline, Offset(x0, cy), Offset(x1, cy), strokeWidth = 2f, cap = StrokeCap.Round)
    val dotR = 3.2.dp.toPx()
    val count = 3
    for (i in 0 until count) {
        val fr = (phase + i.toFloat() / count) % 1f
        val x = x0 + (x1 - x0) * fr
        val a = sin(Math.PI * fr).toFloat().coerceIn(0f, 1f) // 两端淡出
        drawCircle(band.copy(alpha = 0.85f * a), radius = dotR, center = Offset(x, cy))
    }
}

// ---------------------------------------------------------------------------
// StGraph —— 实时吞吐折线（gfill 面 + gline 线 + gdot 末端点）
// ---------------------------------------------------------------------------

/**
 * 实时吞吐折线：标题 + 当前值一行，下方归一化折线（[points] 0..1）。band 色描线、
 * band→透明竖向渐变填充、末端 band 点。[animated] 且非减弱动效时末端点带轻脉冲。
 *
 * @param points 归一化序列（0f..1f，1=顶）；< 2 点只画基线
 */
@Composable
fun StGraph(
    title: String,
    nowValue: String,
    points: List<Float>,
    band: Color,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
) {
    val colors = AnebTheme.colors
    val reduced = LocalReducedMotion.current
    val transition = rememberInfiniteTransition(label = "graph")
    val pulseState = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
        label = "graph-pulse",
    )
    // 折线/面路径复用：持有单例 Path，每次绘制前 reset()——避免每帧 new Path() 抖 GC。
    val linePath = remember { Path() }
    val areaPath = remember { Path() }

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(title, style = AnebType.Overline, color = colors.faint)
            Spacer(Modifier.weight(1f))
            Text(nowValue, style = AnebType.StatValue, fontSize = 18.sp, color = band)
        }
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(92.dp)) {
            // 脉冲动画值在 DrawScope 闭包内读取：每帧只失效重绘、不重组外层宿主。
            val pulse = if (animated && !reduced) pulseState.value else 0f
            val w = size.width
            val h = size.height
            val topP = 6.dp.toPx()
            val botP = 4.dp.toPx()
            val usable = (h - topP - botP).coerceAtLeast(1f)
            val baseY = h - botP
            drawLine(colors.hairline, Offset(0f, baseY), Offset(w, baseY), strokeWidth = 1.5f)
            if (points.size < 2) return@Canvas
            val n = points.size
            val line = linePath.apply { reset() }
            val area = areaPath.apply { reset() }
            var lastX = 0f
            var lastY = baseY
            points.forEachIndexed { i, p ->
                val x = w * i / (n - 1)
                val y = topP + (1f - p.coerceIn(0f, 1f)) * usable
                if (i == 0) {
                    line.moveTo(x, y)
                    area.moveTo(x, baseY)
                    area.lineTo(x, y)
                } else {
                    line.lineTo(x, y)
                    area.lineTo(x, y)
                }
                lastX = x
                lastY = y
            }
            area.lineTo(lastX, baseY)
            area.close()
            drawPath(area, brush = Brush.verticalGradient(listOf(band.copy(alpha = 0.30f), band.copy(alpha = 0.02f))))
            drawPath(line, color = band, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(band.copy(alpha = 0.22f), radius = 5.dp.toPx() * (1f + pulse * 0.5f), center = Offset(lastX, lastY))
            drawCircle(band, radius = 3.5.dp.toPx(), center = Offset(lastX, lastY))
        }
    }
}

// ---------------------------------------------------------------------------
// StResults —— 结果大数字行
// ---------------------------------------------------------------------------

/**
 * 结果大数字行列表：每行 [图标徽标] + 名称/分级角标 + 大值/单位；行间 hairline 分隔。
 * 图标底/值色随分级（gradeSoft / gradeColor），null 分级走中性（R-10）。
 */
@Composable
fun StResults(
    items: List<StResItem>,
    modifier: Modifier = Modifier,
) {
    val colors = AnebTheme.colors
    Column(modifier.fillMaxWidth()) {
        items.forEachIndexed { i, item ->
            if (i > 0) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(36.dp).clip(AnebShapes.sm).background(colors.gradeSoft(item.grade)),
                    contentAlignment = Alignment.Center,
                ) {
                    ResIconGlyph(item.icon, colors.gradeColor(item.grade), 18.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = colors.ink, maxLines = 1)
                    Spacer(Modifier.height(3.dp))
                    GradeChip(grade = item.grade)
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(item.value, style = AnebType.StatValue, fontSize = 22.sp, color = colors.gradeColor(item.grade))
                    if (item.unit.isNotEmpty()) {
                        Spacer(Modifier.width(2.dp))
                        Text(
                            item.unit,
                            fontSize = 12.sp,
                            color = colors.muted,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResIconGlyph(icon: ResIcon, tint: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = w * 0.14f
        when (icon) {
            ResIcon.Down -> {
                drawLine(tint, Offset(w * 0.5f, h * 0.18f), Offset(w * 0.5f, h * 0.80f), strokeWidth = sw, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.28f, h * 0.56f), Offset(w * 0.5f, h * 0.82f), strokeWidth = sw, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.72f, h * 0.56f), Offset(w * 0.5f, h * 0.82f), strokeWidth = sw, cap = StrokeCap.Round)
            }
            ResIcon.Up -> {
                drawLine(tint, Offset(w * 0.5f, h * 0.82f), Offset(w * 0.5f, h * 0.20f), strokeWidth = sw, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.28f, h * 0.44f), Offset(w * 0.5f, h * 0.18f), strokeWidth = sw, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.72f, h * 0.44f), Offset(w * 0.5f, h * 0.18f), strokeWidth = sw, cap = StrokeCap.Round)
            }
            ResIcon.Stall -> {
                val ecg = Path().apply {
                    moveTo(w * 0.08f, h * 0.5f)
                    lineTo(w * 0.34f, h * 0.5f)
                    lineTo(w * 0.44f, h * 0.24f)
                    lineTo(w * 0.56f, h * 0.78f)
                    lineTo(w * 0.66f, h * 0.5f)
                    lineTo(w * 0.92f, h * 0.5f)
                }
                drawPath(ecg, tint, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// AnebTabBar —— 底部主 tab（历史 · 测试 GO 凸起 · 设置）
// ---------------------------------------------------------------------------

/**
 * 底部主 tab：中央「测试」为 [AnebColors.brand] 凸起 GO 圆钮（上移 + 抬升阴影），两侧历史/设置。
 * 外壳走 [GlassChrome] 毛玻璃 + 上缘发丝线；选中态品牌蓝、未选次文本灰，深浅色随主题。
 */
@Composable
fun AnebTabBar(
    current: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AnebTheme.colors
    GlassChrome(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
            Box(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SideTab(MainTab.History, current, onSelect, Modifier.weight(1f))
                    Spacer(Modifier.weight(1f)) // 中央 GO 让位
                    SideTab(MainTab.Settings, current, onSelect, Modifier.weight(1f))
                }
                GoButton(
                    selected = current == MainTab.Test,
                    onClick = { onSelect(MainTab.Test) },
                    modifier = Modifier.align(Alignment.TopCenter).offset(y = (-18).dp),
                )
            }
        }
    }
}

@Composable
private fun SideTab(
    tab: MainTab,
    current: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier,
) {
    val colors = AnebTheme.colors
    val on = tab == current
    val tint = if (on) colors.brand else colors.muted
    Column(
        modifier.pressable(onClick = { onSelect(tab) }),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (tab) {
            MainTab.History -> TabGlyphClock(tint, 22.dp)
            MainTab.Settings -> TabGlyphSettings(tint, 22.dp)
            MainTab.Test -> Spacer(Modifier.size(22.dp)) // Test 由中央 GO 承载
        }
        Spacer(Modifier.height(3.dp))
        Text(tab.label, fontSize = 10.5.sp, color = tint, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun GoButton(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AnebTheme.colors
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(54.dp)
                .shadow(AnebElevation.level2, CircleShape)
                .clip(CircleShape)
                .background(colors.brand)
                .pressable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(20.dp)) {
                val tri = Path().apply {
                    moveTo(size.width * 0.34f, size.height * 0.24f)
                    lineTo(size.width * 0.34f, size.height * 0.76f)
                    lineTo(size.width * 0.78f, size.height * 0.5f)
                    close()
                }
                drawPath(tri, Color.White)
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            MainTab.Test.label,
            fontSize = 10.5.sp,
            color = if (selected) colors.brand else colors.muted,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** 时钟图标（历史 tab）：圆 + 时/分针。 */
@Composable
private fun TabGlyphClock(tint: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension * 0.4f
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        val st = this.size.minDimension * 0.08f
        drawCircle(tint, r, c, style = Stroke(width = st))
        drawLine(tint, c, Offset(c.x, c.y - r * 0.55f), strokeWidth = st, cap = StrokeCap.Round)
        drawLine(tint, c, Offset(c.x + r * 0.42f, c.y + r * 0.12f), strokeWidth = st, cap = StrokeCap.Round)
    }
}

/** 设置图标（设置 tab）：三条带旋钮的滑杆。 */
@Composable
private fun TabGlyphSettings(tint: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val st = w * 0.08f
        val ys = floatArrayOf(h * 0.28f, h * 0.5f, h * 0.72f)
        val knob = floatArrayOf(w * 0.66f, w * 0.36f, w * 0.62f)
        for (i in 0..2) {
            drawLine(tint, Offset(w * 0.16f, ys[i]), Offset(w * 0.84f, ys[i]), strokeWidth = st, cap = StrokeCap.Round)
            drawCircle(tint, w * 0.09f, Offset(knob[i], ys[i]))
        }
    }
}

// ---------------------------------------------------------------------------
// Preview（debugImplementation ui-tooling；不进 release）
// ---------------------------------------------------------------------------

@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 360)
@Composable
private fun PreviewSpeedTestStack() {
    AnebTheme(darkTheme = true) {
        val band = AnebTheme.colors.good
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            StBanner(isp = "China Mobile · 5G", sub = "已连接 · 就近节点", action = "切换", onAction = {})
            Spacer(Modifier.height(16.dp))
            StStep(
                labels = listOf("连接", "下行", "上行", "完成"),
                states = listOf(StepState.Done, StepState.On, StepState.Todo, StepState.Todo),
                band = band,
            )
            Spacer(Modifier.height(16.dp))
            StLink(deviceLabel = "你", nodeLabel = "节点", band = band)
            Spacer(Modifier.height(16.dp))
            StGraph(
                title = "实时吞吐",
                nowValue = "128.4 Mbps",
                points = listOf(0.2f, 0.4f, 0.35f, 0.6f, 0.55f, 0.8f, 0.72f, 0.9f),
                band = band,
            )
            Spacer(Modifier.height(16.dp))
            StResults(
                items = listOf(
                    StResItem(ResIcon.Down, "下行", "128.4", "Mbps", Grade.Excellent),
                    StResItem(ResIcon.Up, "上行", "42.1", "Mbps", Grade.Good),
                    StResItem(ResIcon.Stall, "卡顿", "1", "次", Grade.Fair),
                ),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 360)
@Composable
private fun PreviewTabBar() {
    AnebTheme(darkTheme = true) {
        AnebTabBar(current = MainTab.Test, onSelect = {})
    }
}
