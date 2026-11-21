package com.aneb.probe.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.data.Exporter
import com.aneb.probe.engine.BasicSpeedResult
import com.aneb.probe.engine.LoadedResponsiveness
import com.aneb.probe.engine.LoadedResponsivenessSummary
import com.aneb.probe.ui.components.AnebGlyph
import com.aneb.probe.ui.components.AnebGradientCard
import com.aneb.probe.ui.components.AnebIcon
import com.aneb.probe.ui.components.AnebMetric
import com.aneb.probe.ui.components.AnebMetricTrio
import com.aneb.probe.ui.components.AnebPageIntro
import com.aneb.probe.ui.components.AnebSectionTitle
import com.aneb.probe.ui.components.AnebTopBar
import com.aneb.probe.ui.components.pressable
import com.aneb.probe.ui.theme.AnebTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/** Unscored directional facts. Historical Basic conclusions and scores are never reused here. */
@Composable
fun LoadedResponsivenessScreen(result: BasicSpeedResult, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportStatus by remember(result.runId) { mutableStateOf("") }
    var exporting by remember(result.runId) { mutableStateOf(false) }
    val report = remember(result.responsivenessJson) {
        result.responsivenessJson?.let { runCatching { LoadedResponsiveness.decode(it) }.getOrNull() }
    }
    val summary = remember(report) { report?.let(LoadedResponsiveness::summarize) }
    val verdict = remember(summary) { BusinessExperienceEvaluator.evaluateLoadedResponsiveness(summary) }
    fun valueNum(value: Double?): String =
        value?.takeIf { it.isFinite() }?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—"
    fun deltaStr(value: Double?): String =
        value?.takeIf { it.isFinite() }?.let { String.format(Locale.ROOT, "%+.1f ms", it) } ?: "不可用"
    val colors = AnebTheme.colors

    CompositionLocalProvider(LocalContentColor provides colors.ink) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            AnebTopBar(showBack = true, onBack = onBack, showMenu = false)
            AnebPageIntro(
                eyebrow = "CONCURRENT LOAD",
                title = "高负载并发",
                subtitle = "边下载/传大图边用 AI 会不会卡 · 状态：${report?.status ?: result.status} · 节点 ${ProbeNodeCatalog.labelForUrl(result.serverBase)}",
                modifier = Modifier.padding(top = 3.dp, bottom = 12.dp),
            )

            ExperienceVerdictCard(verdict = verdict)
            Spacer(Modifier.height(10.dp))

            ConcurrentScenarioConclusionsCard(summary = summary)
            Spacer(Modifier.height(12.dp))

            if (summary == null) {
                AnebGradientCard(Modifier.fillMaxWidth(), radius = 16.dp) {
                    Text(
                        "未能读取本次原始响应性证据。不会用旧 Basic 指标补位，请保留诊断记录。",
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp,
                        color = colors.fair,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            } else {
                AnebMetricTrio(
                    listOf(
                        AnebMetric("空闲 P50", valueNum(summary.idleP50Ms), "ms", colors.brand),
                        AnebMetric("下载负载 P95", valueNum(summary.downloadP95Ms), "ms", colors.fair),
                        AnebMetric("上传负载 P95", valueNum(summary.uploadP95Ms), "ms", colors.brand2),
                    ),
                )
                Spacer(Modifier.height(10.dp))
                AnebGradientCard(Modifier.fillMaxWidth(), radius = 16.dp) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("下载满载延迟增量（Δ）", fontSize = 11.sp, color = colors.muted)
                            Text(deltaStr(summary.downloadDeltaMs), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("上传满载延迟增量（Δ）", fontSize = 11.sp, color = colors.muted)
                            Text(deltaStr(summary.uploadDeltaMs), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                        }
                    }
                }

                AnebSectionTitle("各阶段原始样本证据", Modifier.padding(top = 16.dp, start = 4.dp, bottom = 8.dp))
                AnebGradientCard(Modifier.fillMaxWidth(), radius = 16.dp) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        summary.stages.forEachIndexed { idx, stage ->
                            if (idx > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
                            val label = when (stage.stage) {
                                "idle" -> "空闲基线"
                                "download" -> "下载负载"
                                else -> "上传负载"
                            }
                            val domNote = if (
                                stage.stage != "idle" &&
                                stage.rttDominanceOk == false &&
                                stage.rttDominanceRatio != null &&
                                stage.rttDominanceRatio.isFinite() &&
                                stage.valueMs != null
                            ) {
                                " · 窗口时延主导（RTT占比偏高）"
                            } else ""
                            Column(Modifier.fillMaxWidth()) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.ink)
                                    Text(
                                        "有效 ${stage.validSampleCount} / 观察 ${stage.totalSampleCount}",
                                        fontSize = 10.5.sp,
                                        color = if (stage.validSampleCount > 0) colors.excellent else colors.fair,
                                    )
                                }
                                if (stage.missingReason != null || domNote.isNotEmpty()) {
                                    Text(
                                        (stage.missingReason?.let { "证据不足：$it" } ?: "") + domNote,
                                        fontSize = 9.5.sp,
                                        color = colors.fair,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (report != null && !exporting) colors.brand.copy(alpha = 0.16f) else Color(0x14FFFFFF))
                    .border(
                        1.dp,
                        if (report != null && !exporting) colors.brand.copy(alpha = 0.45f) else colors.hairline,
                        RoundedCornerShape(14.dp),
                    )
                    .pressable(enabled = report != null && !exporting, onClick = {
                        val raw = result.responsivenessJson ?: return@pressable
                        exporting = true
                        scope.launch {
                            val outcome = withContext(Dispatchers.IO) {
                                Exporter.exportToDownloads(
                                    context,
                                    "aneb_loaded_${result.runId}.json",
                                    "application/json",
                                    raw,
                                )
                            }
                            exportStatus = if (outcome.ok) {
                                "已保存到下载/ANEB（${outcome.bytes}字节）"
                            } else {
                                "导出失败，原始记录仍在本机：${outcome.error}"
                            }
                            exporting = false
                        }
                    })
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (exporting) "正在保存…" else "导出负载响应性证据 JSON",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (report != null && !exporting) colors.brand else colors.faint,
                )
            }
            if (exportStatus.isNotEmpty()) {
                Text(
                    exportStatus,
                    fontSize = 10.sp,
                    color = colors.muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "口径说明：仅限 ANEB 应用到所选节点的应用层往返测量，不产生综合分。负载样本只采用完整位于该阶段、且探测期间记录到传输字节增加的 echo；上传字节表示客户端写入，不是远端接收证明，P95 仅描述本次有效样本。",
                fontSize = 9.5.sp,
                lineHeight = 14.5.sp,
                color = colors.faint,
            )
            Text(
                "运行：${result.runId} · 方法：${report?.methodVersion ?: "不可用"}",
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.faint,
                modifier = Modifier.padding(top = 6.dp, bottom = 28.dp),
            )
        }
    }
}

@Composable
private fun ConcurrentScenarioConclusionsCard(summary: LoadedResponsivenessSummary?) {
    val colors = AnebTheme.colors
    val dlDelta = summary?.downloadDeltaMs?.takeIf { it.isFinite() }
    val ulDelta = summary?.uploadDeltaMs?.takeIf { it.isFinite() }

    val (dlOk, dlText) = when {
        dlDelta == null -> false to "下载满载样本不足，无法判定边下载边聊 AI 的卡顿风险。"
        dlDelta <= 40.0 -> true to "几乎无感（+${dlDelta.roundToInt()} ms）：后台下载或刷高清视频时，前台聊 AI 依然流畅不卡。"
        dlDelta <= 120.0 -> true to "轻微变慢（+${dlDelta.roundToInt()} ms）：普通 AI 文本对话可用，但连续追问可能偶有半秒迟滞。"
        else -> false to "明显变卡（+${dlDelta.roundToInt()} ms）：下载流量占满队列，会明显拖慢 AI 首字响应与吐字。"
    }
    val (ulOk, ulText) = when {
        ulDelta == null -> false to "上传满载样本不足，无法判定边传大图边打 AI 语音的卡顿风险。"
        ulDelta <= 40.0 -> true to "排队极轻（+${ulDelta.roundToInt()} ms）：上传大图或同步网盘的同时，实时 AI 语音仍可自然插话。"
        ulDelta <= 120.0 -> true to "轻度排队（+${ulDelta.roundToInt()} ms）：实时语音偶有停顿，建议传大文件时错峰使用。"
        else -> false to "极易卡顿（+${ulDelta.roundToInt()} ms）：上行缓冲区积压严重，边传图边语音极易抢话或断句。"
    }
    val (adviceOk, adviceText) = when {
        dlDelta == null && ulDelta == null ->
            false to "本次未采集到足够满载回声样本，请在稳定连接下重测一次。"
        (dlDelta ?: 0.0) <= 40.0 && (ulDelta ?: 0.0) <= 40.0 ->
            true to "当前网络队列管理表现优秀，日常多设备并发下载与使用 AI 无需额外限速。"
        (ulDelta ?: 0.0) > 120.0 && (ulDelta ?: 0.0) >= (dlDelta ?: 0.0) ->
            false to "主要瓶颈在上行队列膨胀：发大图/相册备份时建议暂停实时 AI 语音，或在路由器开启智能流控（SQM）。"
        (dlDelta ?: 0.0) > 120.0 ->
            false to "主要瓶颈在下行缓冲膨胀：后台全速下载会挤压前台交互，建议给大文件下载适当限速或切换 5G 对比。"
        else ->
            true to "并发负载存在轻度延迟抬升：常规 AI 对话不受影响，高敏实时语音建议避开大文件同步。"
    }

    AnebGradientCard(Modifier.fillMaxWidth(), radius = 16.dp) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(
                "为什么测高负载并发？3 条直接结论",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.ink,
            )
            Text(
                "普通测速只看空网最大带宽，但实际经常「边下载/传图边用 AI」。一旦路由器缓存膨胀，哪怕网速很高，前台对话与语音也会突然卡死。",
                fontSize = 9.5.sp,
                lineHeight = 14.5.sp,
                color = colors.muted,
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
            ScenarioConclusionRow("边下载 / 刷高清视频边聊 AI", dlText, dlOk)
            ScenarioConclusionRow("边传大图 / 同步网盘边打 AI 语音", ulText, ulOk)
            ScenarioConclusionRow("原因诊断与使用建议", adviceText, adviceOk)
        }
    }
}

@Composable
private fun ScenarioConclusionRow(title: String, body: String, good: Boolean) {
    val colors = AnebTheme.colors
    val tint = if (good) colors.excellent else colors.fair
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        AnebIcon(
            glyph = if (good) AnebGlyph.Check else AnebGlyph.Alert,
            tint = tint,
            size = 14.dp,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = colors.ink)
            Text(body, fontSize = 9.5.sp, lineHeight = 14.5.sp, color = colors.muted, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
