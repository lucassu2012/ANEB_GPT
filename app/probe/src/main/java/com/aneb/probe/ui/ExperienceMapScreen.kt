package com.aneb.probe.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.ui.components.AnebTopBar
import com.aneb.probe.ui.components.pressable
import com.aneb.probe.ui.theme.AnebTheme
import com.aneb.probe.engine.KpiGrading
import java.util.Locale

/**
 * 本机路测坐标视图：只投影 Room 中真实 GPS 样本。没有底图 SDK 时显示坐标轨迹网格，
 * 不把原型里的深圳热力点或道路伪装成用户数据。
 */
@Composable
fun ExperienceMapScreen(points: List<ExperienceMapPoint>) {
    val colors = AnebTheme.colors
    var layer by rememberSaveable { mutableStateOf(MapLayer.Experience) }
    Box(Modifier.fillMaxSize().background(Color(0xFF080B17))) {
        MapGrid(points = points, layer = layer, modifier = Modifier.fillMaxSize())
        AnebTopBar(Modifier.padding(horizontal = 12.dp), showMenu = true)

        Column(
            modifier = Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 66.dp),
        ) {
            Text("EXPERIENCE MAP", fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = colors.faint)
            Text("网络体验地图", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.ink)
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 15.dp, top = 66.dp)
                .background(Color(0xC2080C1A), CircleShape)
                .border(1.dp, colors.hairline, CircleShape)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            MapLayer.entries.forEach { item ->
                val selected = layer == item
                Text(
                    item.label,
                    fontSize = 9.sp,
                    color = if (selected) colors.brand else colors.muted,
                    modifier = Modifier
                        .background(if (selected) colors.brand.copy(alpha = 0.12f) else Color.Transparent, CircleShape)
                        .pressable(onClick = { layer = item })
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        if (points.isEmpty()) Column(
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .width(46.dp)
                    .height(46.dp)
                    .border(1.dp, colors.brand.copy(alpha = 0.32f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("◎", fontSize = 18.sp, color = colors.brand)
            }
            Spacer(Modifier.height(12.dp))
            Text("还没有真实地图样本", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colors.ink)
            Text(
                "开启设置中的 GPS 路测并完成测试后，真实坐标才会进入本机地图视图。",
                fontSize = 10.sp,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center,
                color = colors.muted,
                modifier = Modifier.padding(top = 5.dp),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(140.dp)
                .padding(horizontal = 8.dp)
                .background(
                    Brush.verticalGradient(listOf(Color(0xFA191E3A), Color(0xFC0A0E1F))),
                    RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                )
                .border(1.dp, colors.hairline, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
        ) {
            Box(Modifier.fillMaxWidth().height(22.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.width(35.dp).height(3.dp).background(colors.faint.copy(alpha = 0.65f), CircleShape))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(30.dp).height(30.dp).border(1.dp, colors.hairline, CircleShape), contentAlignment = Alignment.Center) {
                    Text("⌁", fontSize = 14.sp, color = colors.muted)
                }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (layer == MapLayer.Experience) "AI 体验图层" else "网络延迟图层", fontSize = 13.sp, color = colors.ink)
                    Text(
                        if (points.isEmpty()) "等待真实路测样本" else "本机 ${points.size} 个 GPS 样本 · ${points.map { it.runId }.distinct().size} 次测试",
                        fontSize = 10.sp,
                        color = colors.muted,
                    )
                }
                val latest = points.maxByOrNull { it.tsNanos }
                val value = if (layer == MapLayer.Experience) {
                    latest?.aqsScore?.let { String.format(Locale.ROOT, "%.0f", it) }
                } else {
                    latest?.rttMs?.let { String.format(Locale.ROOT, "%.0f ms", it) }
                }
                Text(value ?: "—", fontSize = 17.sp, color = colors.excellent)
            }
            if (points.isNotEmpty()) {
                Text(
                    "坐标与轨迹只存本机；点位颜色来自对应 run 的真实 ${if (layer == MapLayer.Experience) "AQS" else "N1 RTT"}，缺值显示中性灰。",
                    fontSize = 9.sp,
                    color = colors.faint,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                )
            }
        }
    }
}

private enum class MapLayer(val label: String) { Experience("AI 体验"), Latency("延迟") }

data class ExperienceMapPoint(
    val runId: String,
    val tsNanos: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Double?,
    val aqsScore: Double?,
    val rttMs: Double?,
)

@Composable
private fun MapGrid(points: List<ExperienceMapPoint>, layer: MapLayer, modifier: Modifier = Modifier) {
    val colors = AnebTheme.colors
    Canvas(modifier) {
        val grid = 34.dp.toPx()
        var x = -grid
        while (x < size.width + grid) {
            drawLine(colors.hairline.copy(alpha = 0.28f), Offset(x, 0f), Offset(x + size.height * 0.28f, size.height), 1f)
            x += grid
        }
        var y = 0f
        while (y < size.height) {
            drawLine(colors.hairline.copy(alpha = 0.22f), Offset(0f, y), Offset(size.width, y + size.width * 0.08f), 1f)
            y += grid
        }
        if (points.isNotEmpty()) {
            val minLat = points.minOf { it.lat }
            val maxLat = points.maxOf { it.lat }
            val minLon = points.minOf { it.lon }
            val maxLon = points.maxOf { it.lon }
            val latSpan = (maxLat - minLat).takeIf { it > 1e-9 }
            val lonSpan = (maxLon - minLon).takeIf { it > 1e-9 }
            fun projected(point: ExperienceMapPoint): Offset {
                val nx = lonSpan?.let { ((point.lon - minLon) / it).toFloat() } ?: 0.5f
                val ny = latSpan?.let { ((point.lat - minLat) / it).toFloat() } ?: 0.5f
                return Offset(
                    x = size.width * (0.10f + nx * 0.80f),
                    y = size.height * (0.23f + (1f - ny) * 0.52f),
                )
            }
            val ordered = points.sortedBy { it.tsNanos }
            ordered.zipWithNext().forEach { (a, b) ->
                drawLine(colors.brand.copy(alpha = 0.28f), projected(a), projected(b), 2.dp.toPx(), StrokeCap.Round)
            }
            ordered.forEach { point ->
                val grade = if (layer == MapLayer.Experience) {
                    point.aqsScore?.let { score ->
                        when {
                            score >= 85.0 -> KpiGrading.EXCELLENT
                            score >= 70.0 -> KpiGrading.GOOD
                            score >= 55.0 -> KpiGrading.FAIR
                            else -> KpiGrading.POOR
                        }
                    }
                } else {
                    KpiGrading.grade("N1", point.rttMs)
                }
                val color = colors.gradeColor(grade)
                val at = projected(point)
                drawCircle(color.copy(alpha = 0.18f), radius = 10.dp.toPx(), center = at)
                drawCircle(color, radius = 4.dp.toPx(), center = at)
            }
        }
    }
}
