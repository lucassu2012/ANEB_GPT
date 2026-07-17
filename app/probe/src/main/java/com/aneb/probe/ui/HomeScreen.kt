package com.aneb.probe.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.data.TestRun
import com.aneb.probe.data.NetworkComprehensiveResultEntity
import com.aneb.probe.engine.AnebTestMode
import com.aneb.probe.engine.TestEngine
import com.aneb.probe.ui.components.AnebWordmark
import com.aneb.probe.ui.components.pressable
import com.aneb.probe.ui.theme.AnebPalette
import com.aneb.probe.ui.theme.AnebTheme
import com.aneb.probe.ui.theme.AnebType
import com.aneb.probe.ui.theme.Grade
import com.aneb.probe.ui.theme.LocalReducedMotion
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** 首页：原生复刻 `ANEB_UI/screens/home.html` 的 idle 状态与三档网络抽屉。 */
@Composable
fun HomeScreen(
    lastRun: TestRun?,
    lastBasicRun: NetworkComprehensiveResultEntity?,
    testMode: AnebTestMode,
    mode: TestEngine.Mode,
    onTestModeChange: (AnebTestMode) -> Unit,
    running: Boolean,
    notice: String? = null,
    connectionLabel: String,
    nodeLabel: String,
    onStart: () -> Unit,
    onOpenServer: () -> Unit,
    onOpenLastResult: (String) -> Unit,
    onOpenLastBasicResult: (String) -> Unit,
) {
    val colors = AnebTheme.colors
    var sheetSnap by remember { mutableStateOf(SheetSnap.Collapsed) }
    var dragTotal by remember { mutableFloatStateOf(0f) }
    var dragDeltaDp by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var confirmStress by remember { mutableStateOf(false) }
    var confirmRecovery by remember { mutableStateOf(false) }
    var confirmWeakNetwork by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val collapsedHeight = 150.dp
    val halfHeight = 250.dp
    val expandedHeight = 340.dp
    val anchorHeight = when (sheetSnap) {
        SheetSnap.Collapsed -> collapsedHeight
        SheetSnap.Half -> halfHeight
        SheetSnap.Expanded -> expandedHeight
    }
    val animatedSheetHeight by animateDpAsState(
        targetValue = anchorHeight,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 430f),
        label = "home-network-sheet",
    )
    val sheetHeight = if (isDragging) {
        (anchorHeight + dragDeltaDp.dp).coerceIn(collapsedHeight, expandedHeight)
    } else {
        animatedSheetHeight
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AnebPalette.Dark.DeepBackground),
    ) {
        AnebWordmark(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp),
        )

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(78.dp))
            TestModeSwitch(
                selected = testMode,
                enabled = !running,
                onSelect = onTestModeChange,
            )
            Spacer(Modifier.height(27.dp))
            IdleStartRing(
                running = running,
                onStart = {
                    if (testMode == AnebTestMode.NETWORK_BASIC && mode == TestEngine.Mode.STRESS) {
                        confirmWeakNetwork = true
                    } else if (testMode == AnebTestMode.TOKEN_SIMULATION && mode == TestEngine.Mode.STRESS) {
                        confirmStress = true
                    } else if (testMode == AnebTestMode.AI_REALTIME_SIMULATION && mode == TestEngine.Mode.STRESS) {
                        confirmRecovery = true
                    } else {
                        onStart()
                    }
                },
            )
            Text(
                when (testMode) {
                    AnebTestMode.NETWORK_BASIC -> if (mode == TestEngine.Mode.STRESS) {
                        "隔离模拟 ↓3 / ↑1 Mbps 与 +120±30ms 应用时延，真实无线信号保持不变"
                    } else {
                        "并行测量上下行容量、负载 RTT、稳定性与 UDP 应用探针"
                    }
                    AnebTestMode.TOKEN_SIMULATION -> if (mode == TestEngine.Mode.STRESS) {
                        "100MiB 视频上传 + 100MiB 大对象返回，动态测量容量与负载 RTT"
                    } else {
                        "模拟文本、文档与图片 AI 互动，动态测量 Token 到达"
                    }
                    AnebTestMode.AI_REALTIME_SIMULATION -> if (mode == TestEngine.Mode.STRESS) {
                        "执行两次受控服务端中断，动态测量重连到首个有效音频帧"
                    } else {
                        "模拟 GPT-Live 类双工语音、连续音频帧与用户打断"
                    }
                    AnebTestMode.TOKEN_EXPERIENCE -> "执行经典 Agent 场景取证与 AQS 评分"
                },
                fontSize = 10.5.sp,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center,
                color = colors.faint,
                modifier = Modifier.padding(top = 13.dp, start = 34.dp, end = 34.dp),
            )
        }

        notice?.let {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 20.dp, end = 20.dp, bottom = sheetHeight + 10.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xF20D121B))
                    .border(1.dp, colors.hairline, RoundedCornerShape(999.dp))
                    .padding(horizontal = 13.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(colors.fair))
                Spacer(Modifier.width(8.dp))
                Text(it, fontSize = 10.sp, color = colors.ink)
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(sheetHeight)
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF191D39), Color(0xFF0C0F22)),
                    ),
                )
                .border(
                    1.dp,
                    Color(0x1F9DB4DA),
                    RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .pointerInput(sheetSnap) {
                        detectVerticalDragGestures(
                            onDragStart = {
                                dragTotal = 0f
                                dragDeltaDp = 0f
                                isDragging = true
                            },
                            onVerticalDrag = { change, amount ->
                                change.consume()
                                dragTotal += amount
                                dragDeltaDp = -dragTotal / density.density
                            },
                            onDragEnd = {
                                val releasedHeight = (anchorHeight + dragDeltaDp.dp)
                                    .coerceIn(collapsedHeight, expandedHeight)
                                sheetSnap = when {
                                    releasedHeight < (collapsedHeight + halfHeight) / 2f -> SheetSnap.Collapsed
                                    releasedHeight < (halfHeight + expandedHeight) / 2f -> SheetSnap.Half
                                    else -> SheetSnap.Expanded
                                }
                                isDragging = false
                                dragTotal = 0f
                                dragDeltaDp = 0f
                            },
                            onDragCancel = {
                                isDragging = false
                                dragTotal = 0f
                                dragDeltaDp = 0f
                            },
                        )
                    }
                    .pressable(onClick = { sheetSnap = sheetSnap.next() }),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.width(36.dp).height(3.dp).clip(CircleShape).background(Color(0x52CAD6EA)))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(53.dp)
                    .pressable(onClick = { sheetSnap = sheetSnap.next() })
                    .padding(start = 14.dp, end = 13.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NetworkGlyph()
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(connectionLabel, fontSize = 14.sp, fontWeight = FontWeight(450), color = colors.ink, maxLines = 1)
                    Text("$nodeLabel · 真实测试节点", fontSize = 10.sp, color = colors.muted, maxLines = 1)
                }
                Text(if (sheetSnap == SheetSnap.Expanded) "⌄" else "⌃", fontSize = 14.sp, color = colors.muted)
            }

            if (sheetSnap != SheetSnap.Collapsed) {
                SheetDetailRow(
                    symbol = "◎",
                    label = "测试节点",
                    value = nodeLabel,
                    action = "更换",
                    onAction = onOpenServer,
                )
                if (testMode == AnebTestMode.NETWORK_BASIC && lastBasicRun != null) {
                    val down = lastBasicRun.downloadMbps?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—"
                    val up = lastBasicRun.uploadMbps?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—"
                    SheetDetailRow(
                        symbol = "↕",
                        label = "上次网络综合",
                        value = "${lastBasicRun.totalScore?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—"} 分 · ↓ $down ↑ $up",
                        action = "查看",
                        onAction = { onOpenLastBasicResult(lastBasicRun.runId) },
                    )
                } else if (lastRun != null && testMode == AnebTestMode.TOKEN_EXPERIENCE) {
                    val grade = lastRun.aqsScore?.let { Grade.fromAqsScore(it) }
                    SheetDetailRow(
                        symbol = "↗",
                        label = "上次成绩",
                        value = "${lastRun.aqsScore?.roundToInt() ?: "—"} · ${grade?.labelFriendly ?: "未完成"}",
                        action = "查看",
                        onAction = { onOpenLastResult(lastRun.runId) },
                    )
                } else if (sheetSnap == SheetSnap.Expanded) {
                    SheetDetailRow(
                        symbol = if (testMode == AnebTestMode.NETWORK_BASIC) "↕" else "—",
                        label = if (testMode == AnebTestMode.NETWORK_BASIC) "测试项目" else "测试档位",
                        value = when (testMode) {
                            AnebTestMode.NETWORK_BASIC -> if (mode == TestEngine.Mode.STRESS) {
                                "当前：合成弱网 · 约 42 秒 · ↓3 ↑1 Mbps · +120±30ms"
                            } else {
                                modeSummary(mode, "约 20 秒", "约 42 秒")
                            }
                            AnebTestMode.TOKEN_SIMULATION -> when (mode) {
                                TestEngine.Mode.QUICK -> "当前：快测 · 约 2 分钟"
                                TestEngine.Mode.FORENSIC -> "当前：标准 · 约 23 分钟"
                                TestEngine.Mode.STRESS -> "当前：压力 · 约 2–5 分钟 · 约 200MiB 流量"
                            }
                            AnebTestMode.AI_REALTIME_SIMULATION -> when (mode) {
                                TestEngine.Mode.QUICK -> "当前：快测 · 约 25 秒"
                                TestEngine.Mode.FORENSIC -> "当前：标准 · 约 22 分钟"
                                TestEngine.Mode.STRESS -> "当前：恢复 · 约 1 分钟 · 2 次受控中断"
                            }
                            AnebTestMode.TOKEN_EXPERIENCE -> "完成首次测试后显示"
                        },
                        action = null,
                        onAction = {},
                    )
                }
            }
        }

        if (confirmStress) {
            AlertDialog(
                onDismissRequest = { confirmStress = false },
                title = { Text("开始大对象压力测试？") },
                text = {
                    Text(
                        "本次将上传 100MiB 仿真视频并下载 100MiB 仿真结果，预计消耗约 200MiB 流量，" +
                            "可能引起手机发热。建议使用 WiFi，并保持 ANEB 在前台。",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { confirmStress = false; onStart() }) { Text("开始压力测试") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmStress = false }) { Text("取消") }
                },
            )
        }

        if (confirmWeakNetwork) {
            AlertDialog(
                onDismissRequest = { confirmWeakNetwork = false },
                title = { Text("开始合成弱网测试？") },
                text = {
                    Text(
                        "本次只对 ANEB 当前测试流量模拟下行 3Mbps、上行 1Mbps、应用请求附加 RTT 120±30ms。" +
                            "不会修改手机的 RSRP/SINR，也不模拟 DNS、TCP、TLS 或 UDP 丢包；结果会永久标记为“合成弱网”。",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { confirmWeakNetwork = false; onStart() }) { Text("开始弱网测试") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmWeakNetwork = false }) { Text("取消") }
                },
            )
        }

        if (confirmRecovery) {
            AlertDialog(
                onDismissRequest = { confirmRecovery = false },
                title = { Text("开始受控恢复测试？") },
                text = {
                    Text(
                        "ANEB 节点将只对本次测试连接执行两次受控中断，并测量客户端重新建立会话后到首个有效音频帧的时间。" +
                            "这不会切换手机网络，也不会影响其他 App；结论不代表蜂窝断网或跨网迁移能力。",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { confirmRecovery = false; onStart() }) { Text("开始恢复测试") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmRecovery = false }) { Text("取消") }
                },
            )
        }
    }
}

private fun modeSummary(mode: TestEngine.Mode, quick: String, standard: String): String = when (mode) {
    TestEngine.Mode.QUICK -> "当前：快测 · $quick"
    TestEngine.Mode.FORENSIC -> "当前：标准 · $standard"
    TestEngine.Mode.STRESS -> "当前：快测 · $quick"
}

@Composable
private fun TestModeSwitch(
    selected: AnebTestMode,
    enabled: Boolean,
    onSelect: (AnebTestMode) -> Unit,
) {
    val colors = AnebTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xA80B1020))
            .border(1.dp, colors.hairline, RoundedCornerShape(999.dp))
            .padding(3.dp),
    ) {
        listOf(
            AnebTestMode.NETWORK_BASIC,
            AnebTestMode.TOKEN_SIMULATION,
            AnebTestMode.AI_REALTIME_SIMULATION,
        ).forEach { mode ->
            val active = mode == selected
            Text(
                mode.label,
                fontSize = 10.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) colors.ink else colors.muted,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (active) colors.brand.copy(alpha = 0.16f) else Color.Transparent)
                    .border(
                        1.dp,
                        if (active) colors.brand.copy(alpha = 0.28f) else Color.Transparent,
                        RoundedCornerShape(999.dp),
                    )
                    .pressable(enabled = enabled, onClick = { onSelect(mode) })
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

private enum class SheetSnap {
    Collapsed, Half, Expanded;

    fun up() = when (this) { Collapsed -> Half; Half -> Expanded; Expanded -> Expanded }
    fun down() = when (this) { Expanded -> Half; Half -> Collapsed; Collapsed -> Collapsed }
    fun next() = when (this) { Collapsed -> Half; Half -> Expanded; Expanded -> Collapsed }
}

@Composable
private fun IdleStartRing(running: Boolean, onStart: () -> Unit) {
    val colors = AnebTheme.colors
    val reducedMotion = LocalReducedMotion.current
    val transition = rememberInfiniteTransition(label = "idle-start-ring")
    val breath by transition.animateFloat(
        initialValue = 0.985f,
        targetValue = 1.025f,
        animationSpec = infiniteRepeatable(tween(1_650), RepeatMode.Reverse),
        label = "idle-start-breath",
    )
    val glow by transition.animateFloat(
        initialValue = 0.16f,
        targetValue = 0.46f,
        animationSpec = infiniteRepeatable(tween(1_650), RepeatMode.Reverse),
        label = "idle-start-glow",
    )
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(7_000, easing = LinearEasing)),
        label = "idle-start-rotation",
    )
    Box(
        modifier = Modifier
            .size(198.dp)
            .scale(if (reducedMotion) 1f else breath)
            .pressable(onClick = onStart, enabled = !running),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = 1.25.dp.toPx()
            drawCircle(
                color = Color(0xFF43E1E6).copy(alpha = if (reducedMotion) 0.18f else glow),
                radius = size.minDimension / 2f - stroke,
                style = Stroke(8.dp.toPx(), cap = StrokeCap.Round),
            )
            rotate(if (reducedMotion) 0f else rotation, pivot = center) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        listOf(Color(0xFF67EDCC), Color(0xFF43E1E6), Color(0xFF3EB4F1), Color(0xFF67EDCC)),
                        center = center,
                    ),
                    radius = size.minDimension / 2f - stroke,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Text(
            if (running) "进行中" else "开始",
            fontSize = 33.sp,
            fontWeight = FontWeight(320),
            letterSpacing = (-1.3).sp,
            color = colors.ink,
        )
    }
}

@Composable
private fun NetworkGlyph() {
    val colors = AnebTheme.colors
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .border(1.dp, colors.hairline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(15.dp)) {
            val c = Offset(size.width / 2f, size.height * 0.66f)
            repeat(3) { i ->
                val r = size.width * (0.18f + i * 0.16f)
                drawArc(
                    colors.muted.copy(alpha = 0.72f - i * 0.12f),
                    startAngle = 205f,
                    sweepAngle = 130f,
                    useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r),
                    size = androidx.compose.ui.geometry.Size(r * 2f, r * 2f),
                    style = Stroke(0.85.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            drawCircle(colors.muted, 1.1.dp.toPx(), c)
        }
    }
}

@Composable
private fun SheetDetailRow(
    symbol: String,
    label: String,
    value: String,
    action: String?,
    onAction: () -> Unit,
) {
    val colors = AnebTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).border(1.dp, colors.hairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(symbol, fontSize = 11.sp, color = colors.muted)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 9.sp, color = colors.muted)
            Text(value, fontSize = 12.sp, fontWeight = FontWeight(450), color = colors.ink, maxLines = 1)
        }
        if (action != null) {
            Text(
                action,
                fontSize = 9.sp,
                color = colors.brand,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .border(1.dp, colors.brand.copy(alpha = 0.2f), RoundedCornerShape(999.dp))
                    .pressable(onClick = onAction)
                    .padding(horizontal = 9.dp, vertical = 6.dp),
            )
        }
    }
}

/** run 网络/时间副标题；只使用真实 transport、节点和本机时间。 */
internal object NetworkLabel {
    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)

    fun forRun(run: TestRun): String {
        val transport = when (run.transport.lowercase()) {
            "wifi" -> "WiFi"
            "cellular" -> "蜂窝"
            else -> "自动"
        }
        return "$transport · ${ProbeNodeCatalog.labelForUrl(run.serverBase)} · ${run.mode} · " +
            fmt.format(Date(run.startedAtEpochMs))
    }
}
