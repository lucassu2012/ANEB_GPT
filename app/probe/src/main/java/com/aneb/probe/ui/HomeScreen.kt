package com.aneb.probe.ui

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.data.TestRun
import com.aneb.probe.ui.components.HalfGauge
import com.aneb.probe.ui.components.SegmentedControl
import com.aneb.probe.ui.components.StBanner
import com.aneb.probe.ui.components.pressable
import com.aneb.probe.ui.components.pulseRing
import com.aneb.probe.ui.theme.AnebElevation
import com.aneb.probe.ui.theme.AnebTheme
import com.aneb.probe.ui.theme.AnebType
import com.aneb.probe.ui.theme.Grade
import com.aneb.probe.ui.theme.onGrade
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 首页（Claude Design v2 · SpeedTest 式首页）：bare 顶栏（A/NEB 字标 + 简洁/专业段控）+ 连接横幅
 * [StBanner] + 180° 半盘 [HalfGauge] idle 待机（中心承载 GO 播放按钮 + 三层脉冲环）+ gohint +
 * 上次成绩 chip（[lastRun]）。
 *
 * 纯 UI 层：数据经参数注入（[lastRun] 由 MainActivity 查 Room），[onStart] 触发既有 startRun
 * 编排（测量语义不动）。历史/设置导航已由底部 [com.aneb.probe.ui.components.AnebTabBar] 承载，
 * 顶栏不再挂副入口；横幅右侧动作透传至 [onOpenSettings]（就近入设置切服务器）。
 */
@Composable
fun HomeScreen(
    lastRun: TestRun?,
    running: Boolean,
    onStart: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLastResult: (String) -> Unit,
) {
    val colors = AnebTheme.colors
    // 顶栏 简洁/专业 段控：控制上次成绩 chip 的信息密度（简洁只给分档，专业补网络副行）。
    var density by rememberSaveable { mutableStateOf(HomeDensity.Simple) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = 20.dp),
    ) {
        // ---- bare 顶栏：A/NEB 字标 + 简洁/专业段控 ----
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("A", fontSize = 20.sp, fontWeight = FontWeight.Black, color = colors.ink)
                Text("NEB", fontSize = 20.sp, fontWeight = FontWeight.Black, color = colors.brand2)
            }
            Spacer(Modifier.weight(1f))
            SegmentedControl(
                options = HomeDensity.entries,
                selected = density,
                onSelect = { density = it },
                label = { it.label },
            )
        }

        Spacer(Modifier.height(10.dp))

        // ---- 连接横幅（就绪态；动作入设置切服务器）----
        StBanner(
            isp = homeNetworkLabel(lastRun),
            sub = "轻触 GO 开始测试 · 约 90 秒",
            action = "设置",
            onAction = onOpenSettings,
            dotColor = if (lastRun != null) colors.excellent else colors.neutral,
        )

        Spacer(Modifier.weight(1f))

        // ---- 180° 半盘 idle + 中心 GO（三层脉冲环）----
        HalfGauge(
            fraction = 0f,
            band = colors.neutral,
            idle = true,
            modifier = Modifier.fillMaxWidth().aspectRatio(1.8f),
        ) {
            GoButton(running = running, onStart = onStart)
        }
        Text(
            "轻触开始 · 约 90 秒",
            fontSize = 12.5.sp,
            color = colors.muted,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp),
        )

        Spacer(Modifier.weight(1f))

        // ---- 上次成绩 chip ----
        if (lastRun != null) {
            LastResultChip(
                run = lastRun,
                detailed = density == HomeDensity.Pro,
                onClick = { onOpenLastResult(lastRun.runId) },
            )
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** 首页信息密度段控：简洁（只给分档）/ 专业（补网络副行）。纯展示，不碰测量。 */
enum class HomeDensity(val label: String) { Simple("简洁"), Pro("专业") }

/** 中心 GO 播放按钮（品牌圆钮 + 白三角 + GO 字 + 三层脉冲环）；[running] 时禁用。 */
@Composable
private fun BoxScope.GoButton(running: Boolean, onStart: () -> Unit) {
    val colors = AnebTheme.colors
    Box(
        modifier = Modifier
            .size(88.dp)
            .pulseRing(colors.brand)
            .shadow(AnebElevation.level2, CircleShape)
            .clip(CircleShape)
            .background(colors.brand)
            .pressable(onClick = onStart, enabled = !running),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(28.dp)) {
                val tri = Path().apply {
                    moveTo(size.width * 0.34f, size.height * 0.22f)
                    lineTo(size.width * 0.34f, size.height * 0.78f)
                    lineTo(size.width * 0.80f, size.height * 0.5f)
                    close()
                }
                drawPath(tri, Color.White)
            }
            Text("GO", fontSize = 13.sp, fontWeight = FontWeight.Black, color = Color.White)
        }
    }
}

@Composable
private fun LastResultChip(run: TestRun, detailed: Boolean, onClick: () -> Unit) {
    val colors = AnebTheme.colors
    val score = run.aqsScore
    val grade = score?.let { Grade.fromAqsScore(it) }
    val gradeColor = colors.gradeColor(grade)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(colors.surfaceMuted)
            .border(1.dp, colors.hairline, RoundedCornerShape(13.dp))
            .pressable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(gradeColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = score?.roundToInt()?.toString() ?: "—",
                style = AnebType.StatValue,
                fontSize = 14.sp,
                // 徽标底色为分级色：文字反色按底色亮度择近黑/近白，保证深浅主题对比（token 化）。
                color = colors.onGrade(grade),
            )
        }
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                "上次：${grade?.labelFriendly ?: "未完成"}",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.ink,
            )
            if (detailed) {
                Text(
                    NetworkLabel.forRun(run),
                    fontSize = 11.sp,
                    color = colors.muted,
                )
            }
        }
        Text("›", fontSize = 18.sp, color = colors.faint)
    }
}

/** 首页横幅网络标签（无实时 ISP，用上次 run 的传输通道近似；无历史 run → 自动）。 */
private fun homeNetworkLabel(run: TestRun?): String = when (run?.transport?.lowercase()) {
    "wifi" -> "WiFi 网络"
    "cellular" -> "蜂窝网络"
    else -> "自动选择网络"
}

/** run 网络/时间副标题（"电信 5G SA · 深圳 · 昨天"占位口径；无地理信息只显 transport+时间）。 */
internal object NetworkLabel {
    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)

    fun forRun(run: TestRun): String {
        val transport = when (run.transport.lowercase()) {
            "wifi" -> "WiFi"
            "cellular" -> "蜂窝"
            else -> "自动"
        }
        return "$transport · ${run.mode} · ${fmt.format(Date(run.startedAtEpochMs))}"
    }
}
