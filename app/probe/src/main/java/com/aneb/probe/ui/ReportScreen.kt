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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.aneb.probe.scoring.ReportAnalyzer.KpiSeries
import com.aneb.probe.scoring.ReportAnalyzer.Method
import com.aneb.probe.scoring.ReportAnalyzer.ReportAnalysis
import com.aneb.probe.ui.components.GlassChrome
import com.aneb.probe.ui.components.SectionLabel
import com.aneb.probe.ui.theme.AnebElevation
import com.aneb.probe.ui.theme.AnebShapes
import com.aneb.probe.ui.theme.AnebTheme
import com.aneb.probe.ui.theme.AnebType

/**
 * 分层测试敏感度报告页（analysis layer ③ 呈现，iOS 化）。读多次 run → [ReportMapper] →
 * ReportAnalyzer → 本页渲染：头条结论、敏感度条目、token 消耗派生投影（带"估算"标）、
 * 趋势折线（Canvas，iOS 语义色）、文献对照、claim scope 页脚。样本不足显引导文案。
 *
 * iOS 材质：毛玻璃顶栏 + 底部玻璃操作区、#1C1C1E 连续圆角卡、tabular 数值、iOS 蓝主按钮。
 * 数据全部来自 [ReportAnalyzer]（本层不重算）；分级色走 AnebColors，LazyColumn key 保留。
 *
 * @param analysis 分析结果；null＝加载中
 * @param exportStatus 最近一次导出/分享状态行（logcat 合同镜像）；null＝无
 */
@Composable
fun ReportScreen(
    analysis: ReportAnalysis?,
    exportStatus: String?,
    onExportMarkdown: () -> Unit,
    onExportJson: () -> Unit,
    onShare: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = AnebTheme.colors
    Column(modifier = Modifier.fillMaxSize().background(colors.background).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(8.dp))
        GlassHeader("敏感度报告", onBack)

        if (analysis == null) {
            Text("加载中…", color = colors.muted, modifier = Modifier.padding(top = 24.dp))
            return
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // 元信息
            item {
                Text(
                    "${ReportFormat.methodLabel(analysis.method)} · 有效 run ${analysis.validRunCount} · " +
                        "不同网络条件 ${analysis.distinctConditionCount}",
                    fontSize = 11.5.sp,
                    color = colors.muted,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
            }

            val insufficient = analysis.method == Method.INSUFFICIENT || analysis.sensitivity.isEmpty()

            if (insufficient) {
                item { GuidanceCard(ReportFormat.insufficientGuidance(analysis)) }
            } else {
                // 头条结论（第一条，突出）
                item {
                    analysis.conclusions.firstOrNull()?.let { HeadlineCard(it) }
                }
                // 敏感度条目
                item { SectionLabel("敏感度发现") }
                items(
                    count = analysis.sensitivity.size,
                    key = { i -> "${analysis.sensitivity[i].driver}-${analysis.sensitivity[i].metric}" },
                ) { i ->
                    FindingRow(ReportFormat.findingLine(analysis.sensitivity[i]))
                }
            }

            // 其余结论（去头条）
            item { SectionLabel("结论明细") }
            items(count = analysis.conclusions.size, key = { it }) { i ->
                ConclusionRow(i + 1, analysis.conclusions[i])
            }

            // token 派生投影（带估算标）
            item { SectionLabel("token 消耗投影", trailing = analysis.tokenProjection.marker) }
            item { TokenProjectionCard(analysis) }

            // 趋势折线
            item { SectionLabel("趋势") }
            item { TrendsBlock(analysis) }

            // 文献锚点
            item { SectionLabel("文献对照（口径不同，仅供参考）") }
            items(count = analysis.tokenProjection.literatureAnchors.size) { i ->
                val a = analysis.tokenProjection.literatureAnchors[i]
                AnchorRow(a.name, a.statement, a.source)
            }

            // claim scope 页脚
            item {
                Text(
                    analysis.claimScopeNote,
                    fontSize = 10.sp,
                    color = colors.faint,
                    modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
                )
            }
        }

        exportStatus?.let {
            Text(
                it,
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.faint,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }

        // 底部玻璃操作区
        GlassChrome(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp)
                .clip(AnebShapes.card)
                .border(1.dp, colors.hairline, AnebShapes.card),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IosSoftButton("导出 MD", onExportMarkdown, Modifier.weight(1f))
                IosSoftButton("导出 JSON", onExportJson, Modifier.weight(1f))
                IosFilledButton("分享", onShare, Modifier.weight(1f))
            }
        }
    }
}

/** iOS 卡片容器：#1C1C1E 连续圆角（card 22）+ hairline 描边 + 细阴影。 */
@Composable
private fun IosCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = AnebTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(AnebElevation.level1, AnebShapes.card, clip = false)
            .clip(AnebShapes.card)
            .background(colors.surface)
            .border(1.dp, colors.hairline, AnebShapes.card),
    ) { content() }
}

@Composable
private fun HeadlineCard(text: String) {
    val colors = AnebTheme.colors
    IosCard(modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "头条结论",
                fontSize = 11.sp,
                fontWeight = FontWeight(640),
                letterSpacing = 0.09.em,
                color = colors.brand2,
            )
            Spacer(Modifier.height(6.dp))
            Text(text, style = AnebType.Body, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
        }
    }
}

@Composable
private fun GuidanceCard(text: String) {
    val colors = AnebTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .clip(AnebShapes.card)
            .background(colors.surfaceMuted)
            .border(1.dp, colors.hairline, AnebShapes.card),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("样本积累中", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = colors.ink)
            Spacer(Modifier.height(6.dp))
            Text(text, fontSize = 12.5.sp, color = colors.muted)
        }
    }
}

@Composable
private fun FindingRow(line: String) {
    val colors = AnebTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.padding(top = 6.dp).size(5.dp).clip(AnebShapes.pill).background(colors.good),
        )
        Spacer(Modifier.width(9.dp))
        Text(line, fontSize = 12.5.sp, color = colors.ink)
    }
}

@Composable
private fun ConclusionRow(index: Int, text: String) {
    val colors = AnebTheme.colors
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(
            "$index",
            style = AnebType.StatValue,
            fontSize = 12.sp,
            color = colors.brand2,
            modifier = Modifier.width(20.dp),
        )
        Text(text, fontSize = 12.5.sp, color = colors.ink)
    }
}

@Composable
private fun TokenProjectionCard(a: ReportAnalysis) {
    val colors = AnebTheme.colors
    val p = a.tokenProjection
    fun fnum(v: Double?): String = v?.let { String.format(java.util.Locale.ROOT, "%.1f", it) } ?: "—"
    IosCard(modifier = Modifier.padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            KvRow("丢包增量", p.lossPctDelta?.let { "${fnum(it)}%" } ?: "—")
            KvRow("每 token TPOT 拉长", "${fnum(p.tpotElongationMsLow)}–${fnum(p.tpotElongationMsHigh)} ms")
            KvRow("会话中断率", p.sessionDropRate?.let { "${fnum(it * 100)}%" } ?: "—")
            KvRow("每会话上行重发", "${fnum(p.uplinkResendTokensLow)}–${fnum(p.uplinkResendTokensHigh)} token")
            Spacer(Modifier.height(8.dp))
            Text(p.note, fontSize = 10.5.sp, color = colors.faint)
        }
    }
}

@Composable
private fun KvRow(k: String, v: String) {
    val colors = AnebTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(k, fontSize = 12.5.sp, color = colors.muted)
        Text(v, style = AnebType.StatValue, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
    }
}

@Composable
private fun AnchorRow(name: String, statement: String, source: String) {
    val colors = AnebTheme.colors
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.brand2)
        Text(statement, fontSize = 11.5.sp, color = colors.ink)
        Text(source, fontSize = 10.sp, color = colors.faint)
    }
}

/** 关键 KPI 趋势折线块：优先画 aqs / ttft / rtt（有 ≥2 个非 null 点才画）。 */
@Composable
private fun TrendsBlock(a: ReportAnalysis) {
    val colors = AnebTheme.colors
    val wanted = listOf("aqs" to "AQS", "ttft" to "TTFT", "rtt" to "RTT")
    val byMetric = a.trends.series.associateBy { it.metric }
    var any = false
    IosCard(modifier = Modifier.padding(vertical = 4.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            for ((id, label) in wanted) {
                val series = byMetric[id] ?: continue
                if (series.values.count { it != null } < 2) continue
                any = true
                Text(
                    "$label（${series.values.count { it != null }} 点）",
                    fontSize = 11.sp,
                    color = colors.muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
                TrendLine(series, if (id == "aqs") colors.good else colors.fair)
            }
            if (!any) {
                Text(
                    "趋势需 ≥2 个同指标非空点，样本积累后展示。",
                    fontSize = 11.sp,
                    color = colors.faint,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun TrendLine(series: KpiSeries, line: androidx.compose.ui.graphics.Color) {
    val colors = AnebTheme.colors
    val pts = series.values
    Canvas(modifier = Modifier.fillMaxWidth().height(56.dp).padding(vertical = 4.dp)) {
        val w = size.width
        val h = size.height
        drawLine(colors.hairline, Offset(0f, h - 1f), Offset(w, h - 1f), strokeWidth = 1f)
        val nonNull = pts.filterNotNull()
        if (nonNull.size < 2) return@Canvas
        val minV = nonNull.min()
        val maxV = nonNull.max()
        val span = (maxV - minV).takeIf { it > 1e-9 } ?: 1.0
        val n = pts.size
        val dx = if (n > 1) w / (n - 1) else w
        val path = Path()
        var started = false
        for (i in 0 until n) {
            val v = pts[i] ?: continue // 断点：null 不连线（R-10）
            val x = dx * i
            val y = h - 2f - ((v - minV) / span).toFloat() * (h - 6f)
            if (!started) {
                path.moveTo(x, y); started = true
            } else {
                path.lineTo(x, y)
            }
        }
        drawPath(path, color = line, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
    }
}
