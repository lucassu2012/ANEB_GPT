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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.data.VoiceResultEntity
import com.aneb.probe.engine.VoiceRunner
import com.aneb.probe.scoring.AqsScorer
import com.aneb.probe.scoring.BufferingDetector
import com.aneb.probe.ui.components.AnebGradientCard
import com.aneb.probe.ui.components.AnebPageIntro
import com.aneb.probe.ui.components.AnebSectionTitle
import com.aneb.probe.ui.components.AnebTopBar
import com.aneb.probe.ui.components.pressable
import com.aneb.probe.ui.theme.AnebTheme
import com.aneb.probe.ui.theme.AnebType
import com.aneb.probe.ui.theme.Grade
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Profile 4 实时语音测量屏：消费 [VoiceRunner.Sample] 与 Room v15 `voice_result` 持久记录。
 *
 * - 主流程优先尝试 v2 `server-sim`（`/realtime-sim` WebSocket 多轮仿真），节点不支持时由
 *   [com.aneb.probe.data.VoiceResultRepository.runWithFallbackAndPersist] 自动降级 v1 `paced-proxy`；
 * - 连续性 mini-run（受控断连）独立卡片展示，不进入语音分；
 * - 仅 `Phase.Done` 样本落库，展示侧现算独立语音分（不写回 Room），并提供离线 JSON/CSV 导出。
 */
@Composable
fun VoiceScreen(
    nodeLabel: String,
    sample: VoiceRunner.Sample?,
    running: Boolean,
    otherRunActive: Boolean,
    fellBackToPacedProxy: Boolean,
    statusNotice: String?,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    contSample: VoiceRunner.Sample?,
    contRunning: Boolean,
    onStartContinuity: () -> Unit,
    onCancelContinuity: () -> Unit,
    recentVoice: List<VoiceResultEntity>,
    exportStatus: String?,
    onExportJson: () -> Unit,
    onExportCsv: () -> Unit,
    onBack: () -> Unit,
) {
    val view = LocalView.current
    val activeVoiceRun = running || contRunning
    DisposableEffect(view, activeVoiceRun) {
        val prev = view.keepScreenOn
        if (activeVoiceRun) {
            view.keepScreenOn = true
        }
        onDispose { view.keepScreenOn = prev }
    }
    val colors = AnebTheme.colors
    val phase = sample?.phase
    val isSim = sample?.caliber == VoiceRunner.SIM_CALIBER

    val phaseLabel = when (phase) {
        VoiceRunner.Phase.Ping -> "时延基线测量中"
        VoiceRunner.Phase.Uplink -> "上行语音帧发送中（20ms 帧节奏）"
        VoiceRunner.Phase.Downlink -> "下行 TTS 帧流接收中（50fps）"
        VoiceRunner.Phase.Handshake -> "实时会话建立中（WebSocket）"
        VoiceRunner.Phase.Turns -> "多轮语音对话仿真中（8 轮·含打断）"
        VoiceRunner.Phase.Done -> "测量完成"
        null -> if (running) "准备中…" else "点击开始实时语音双工测量"
    }

    val (heroVal, heroUnit) = when (phase) {
        VoiceRunner.Phase.Ping -> VoiceFormat.msOrDash(sample.rttMs, decimals = 0) to "ms RTT"
        VoiceRunner.Phase.Uplink -> "${sample.framesSent}/${VoiceRunner.UPLINK_FRAMES}" to "上行帧"
        VoiceRunner.Phase.Downlink -> "${sample.framesRecv}/${VoiceRunner.DOWNLINK_FRAMES}" to "下行帧"
        VoiceRunner.Phase.Handshake -> "…" to "会话建立"
        VoiceRunner.Phase.Turns -> "${sample.framesRecv}" to "下行帧（${sample.turnsOk} 轮 OK）"
        VoiceRunner.Phase.Done -> {
            val (mouthEar, isProxy) = VoiceFormat.primaryMouthEar(sample.mouthEarProxyMs, sample.mouthEarBudgetMs)
            VoiceFormat.msOrDash(mouthEar, decimals = 0) to
                if (isProxy) "ms 口到耳（实测代理）" else "ms 口到耳预算"
        }
        null -> "—" to "口到耳"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        AnebTopBar(showBack = true, onBack = onBack)
        AnebPageIntro(
            eyebrow = "PROFILE 4 · REALTIME VOICE",
            title = "实时语音测量",
            subtitle = "节点 $nodeLabel · 观测口径独立出分，不进入 Token AQS",
            modifier = Modifier.padding(top = 3.dp, bottom = 14.dp),
        )

        // ---- 主读数与启动控制卡 ----
        AnebGradientCard(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (sample != null) VoiceFormat.caliberLabel(sample.caliber) else "server-sim 优先",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.brand,
                    )
                    if (sample?.lowConfidence == true) {
                        Text(
                            ResultFormat.LOW_CONFIDENCE_LABEL,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.fair,
                        )
                    } else if (fellBackToPacedProxy) {
                        Text(
                            "已自动降级 paced-proxy",
                            fontSize = 10.sp,
                            color = colors.muted,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    heroVal,
                    style = AnebType.StatValue,
                    fontSize = 46.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.ink,
                )
                Text(heroUnit, fontSize = 12.sp, color = colors.muted)
                Spacer(Modifier.height(6.dp))
                Text(
                    phaseLabel,
                    fontSize = 11.sp,
                    color = if (phase == VoiceRunner.Phase.Done) colors.excellent else colors.brand,
                )
                Spacer(Modifier.height(16.dp))

                val startBlocked = !running && (otherRunActive || contRunning)
                val buttonBg = when {
                    running -> colors.poor
                    startBlocked -> colors.surfaceMuted
                    else -> colors.brand
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(buttonBg)
                        .testTag("voice_go_button")
                        .semantics {
                            contentDescription = if (running) "取消语音测量" else "开始实时语音测量"
                        }
                        .pressable(
                            onClick = { if (running) onCancel() else onStart() },
                            enabled = running || !startBlocked,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when {
                            running -> "取消测量"
                            startBlocked -> "其他测试进行中"
                            else -> "开始实时语音测量"
                        },
                        color = if (startBlocked) colors.muted else Color(0xFF05121A),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                statusNotice?.let { notice ->
                    Spacer(Modifier.height(10.dp))
                    Text(notice, fontSize = 10.sp, color = colors.fair)
                }
            }
        }

        // ---- 实时指标栅格（null 恒显 "—"，遵守 R-10） ----
        AnebSectionTitle(
            text = "语音核心指标",
            modifier = Modifier.padding(top = 16.dp, start = 4.dp, bottom = 7.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VoiceMetricTile("RTT P50", VoiceFormat.msOrDash(sample?.rttMs), "ms", colors.ink, Modifier.weight(1f))
            VoiceMetricTile("网络抖动", VoiceFormat.msOrDash(sample?.jitterMs), "ms", colors.ink, Modifier.weight(1f))
            VoiceMetricTile("M7 最长静默", VoiceFormat.msOrDash(sample?.m7MaxFrameGapMs), "ms", colors.fair, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VoiceMetricTile("M3 上行帧抖动", VoiceFormat.msOrDash(sample?.upFrameJitterMs), "ms", colors.brand, Modifier.weight(1f))
            VoiceMetricTile(
                if (isSim) "M2' 下行净抖动" else "M2 下行帧抖动",
                VoiceFormat.msOrDash(if (isSim) sample?.downNetJitterMs else sample?.downFrameJitterMs),
                "ms",
                colors.excellent,
                Modifier.weight(1f),
            )
            VoiceMetricTile(
                "近零到达占比",
                VoiceFormat.nearZeroRatioDisplay(sample?.voiceNearZeroArrivalRatio),
                "诊断",
                colors.muted,
                Modifier.weight(1f),
            )
        }
        if (isSim) {
            val simSample = sample!!
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VoiceMetricTile("M4 TTFB P50", VoiceFormat.msOrDash(simSample.ttfbP50Ms), "ms", colors.brand2, Modifier.weight(1f))
                VoiceMetricTile("M5 轮次切换", VoiceFormat.msOrDash(simSample.turnSwitchP50Ms), "ms", colors.brand2, Modifier.weight(1f))
                VoiceMetricTile("M6 打断停帧", VoiceFormat.msOrDash(simSample.bargeStopMaxMs), "ms", colors.brand2, Modifier.weight(1f))
            }
        }

        // ---- 语音独立结论卡（仅 Done 相位显示） ----
        if (phase == VoiceRunner.Phase.Done) {
            Spacer(Modifier.height(14.dp))
            VoiceConclusionCard(sample!!)
        }

        // ---- 连续性 mini-run 卡（受控断连，独立口径不进语音分） ----
        AnebSectionTitle(
            text = "连续性子测（受控断连）",
            modifier = Modifier.padding(top = 16.dp, start = 4.dp, bottom = 7.dp),
        )
        VoiceContinuityCard(
            sample = contSample,
            running = contRunning,
            disabled = running || otherRunActive,
            onStart = onStartContinuity,
            onCancel = onCancelContinuity,
        )

        // ---- 最近语音落库记录与离线导出 ----
        AnebSectionTitle(
            text = "本地落库记录（${recentVoice.size}）",
            modifier = Modifier.padding(top = 16.dp, start = 4.dp, bottom = 7.dp),
        )
        VoiceHistoryExportCard(
            records = recentVoice,
            exportStatus = exportStatus,
            onExportJson = onExportJson,
            onExportCsv = onExportCsv,
        )

        Spacer(Modifier.height(14.dp))
        Text(
            VoiceFormat.CLAIM_SCOPE_TEXT +
                "；口到耳预算含编解码/抖动缓冲名义常数 ${VoiceRunner.CODEC_JB_BUDGET_MS.toInt()}ms。",
            fontSize = 9.5.sp,
            lineHeight = 15.sp,
            color = colors.faint,
            modifier = Modifier.padding(bottom = 28.dp),
        )
    }
}

@Composable
private fun VoiceMetricTile(
    label: String,
    value: String,
    unit: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val colors = AnebTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xB811162C))
            .border(1.dp, colors.hairline, RoundedCornerShape(14.dp))
            .padding(vertical = 11.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, fontSize = 9.5.sp, color = colors.muted, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Text(value, style = AnebType.StatValue, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = accent, maxLines = 1)
        Text(unit, fontSize = 9.sp, color = colors.faint)
    }
}

@Composable
private fun VoiceConclusionCard(sample: VoiceRunner.Sample) {
    val colors = AnebTheme.colors
    val result = remember(sample) { VoiceFormat.scoreSample(sample) } ?: return
    val score = result.score
    val grade = score?.let { Grade.fromAqsScore(it) }
    val accent = if (score == null) colors.muted else colors.gradeColor(grade)
    val tableName = AqsScorer.VOICE_WEIGHTS_TABLE_BY_VERSION[result.aqsVersion] ?: "?"

    AnebGradientCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "语音体验分（独立口径）",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    score?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—",
                    style = AnebType.StatValue,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(VoiceFormat.verdictFor(result), fontSize = 11.5.sp, lineHeight = 17.sp, color = colors.ink)
            if (result.subScores.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "子分 " + result.subScores.entries.joinToString(" · ") { (k, v) ->
                        "$k ${String.format(Locale.ROOT, "%.0f", v)}"
                    } + "（表 $tableName · ${result.aqsVersion}）",
                    fontSize = 10.sp,
                    color = colors.muted,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (sample.caliber == VoiceRunner.SIM_CALIBER) {
                    "帧接收 ${sample.framesRecv} · ${sample.turnsOk} 轮 protocol_ok（emitted 对账口径）"
                } else {
                    "帧接收 ${sample.framesRecv}/${VoiceRunner.DOWNLINK_FRAMES}（TCP 重传掩盖真丢帧，仅计数参考）"
                },
                fontSize = 10.sp,
                color = colors.faint,
            )
            Text(
                "近零到达占比 ${VoiceFormat.nearZeroRatioDisplay(sample.voiceNearZeroArrivalRatio)}" +
                    "（帧间隔 <${BufferingDetector.NEAR_ZERO_ARRIVAL_US}µs，缓冲突发诊断，不进语音分）",
                fontSize = 10.sp,
                color = colors.faint,
            )
        }
    }
}

@Composable
private fun VoiceContinuityCard(
    sample: VoiceRunner.Sample?,
    running: Boolean,
    disabled: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = AnebTheme.colors
    AnebGradientCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("受控断连检出与重建", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                if (sample?.phase == VoiceRunner.Phase.Done) {
                    val complete = sample.continuityDetectMs != null && sample.continuityResumeMs != null
                    Text(
                        if (complete) "完成" else "部分缺席",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (complete) colors.excellent else colors.fair,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            when {
                sample == null && !running -> Text(
                    "服务端受控 WS 硬关：测断连检出与新会话重建耗时。单次事件，观测口径不进语音分，与真实蜂窝断网严格分口径。",
                    fontSize = 10.5.sp,
                    lineHeight = 16.sp,
                    color = colors.muted,
                )
                running && sample?.phase != VoiceRunner.Phase.Done -> {
                    val phaseText = when (sample?.phase) {
                        VoiceRunner.Phase.Handshake -> "断连会话建立中…"
                        VoiceRunner.Phase.Turns ->
                            if (sample.continuityDetectMs != null) "已检出断连，重建新会话中…"
                            else "轮次运行中（收帧 ${sample.framesRecv}）…"
                        else -> "准备中…"
                    }
                    Text(phaseText, fontSize = 11.sp, color = colors.brand)
                }
                sample?.phase == VoiceRunner.Phase.Done -> {
                    Text(
                        "检出 ${VoiceFormat.msOrDash(sample.continuityDetectMs, decimals = 0)} ms · " +
                            "重建 ${VoiceFormat.msOrDash(sample.continuityResumeMs, decimals = 0)} ms · " +
                            "LOW/INCONCLUSIVE（单次受控事件）",
                        fontSize = 11.5.sp,
                        color = colors.ink,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            val btnColor = when {
                running -> colors.poor
                disabled -> colors.surfaceMuted
                else -> colors.brand2
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(btnColor)
                    .pressable(
                        onClick = { if (running) onCancel() else onStart() },
                        enabled = running || !disabled,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (running) "取消连续性子测" else "触发连续性 mini-run",
                    color = if (!running && disabled) colors.muted else Color(0xFF05121A),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun VoiceHistoryExportCard(
    records: List<VoiceResultEntity>,
    exportStatus: String?,
    onExportJson: () -> Unit,
    onExportCsv: () -> Unit,
) {
    val colors = AnebTheme.colors
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.US) }
    AnebGradientCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            if (records.isEmpty()) {
                Text("暂无语音落库记录，完成一次测量后可在此查看与离线导出。", fontSize = 11.sp, color = colors.muted)
            } else {
                records.take(10).forEachIndexed { idx, r ->
                    if (idx > 0) Spacer(Modifier.height(8.dp))
                    val (mouthEar, _) = VoiceFormat.primaryMouthEar(r.mouthEarProxyMs, r.mouthEarBudgetMs)
                    val m7 = r.m7MaxFrameGapMs?.let { " · M7 ${VoiceFormat.msOrDash(it, decimals = 0)}ms" } ?: ""
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${fmt.format(Date(r.tsEpochMs))} · ${VoiceFormat.caliberLabel(r.caliber)}" +
                                    if (r.lowConfidence) " · 低置信" else "",
                                fontSize = 11.sp,
                                color = colors.ink,
                                maxLines = 1,
                            )
                            Text(
                                "RTT ${VoiceFormat.msOrDash(r.rttMs, decimals = 0)}ms$m7",
                                fontSize = 9.5.sp,
                                color = colors.muted,
                                maxLines = 1,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${VoiceFormat.msOrDash(mouthEar, decimals = 0)} ms",
                            style = AnebType.StatValue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.brand,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VoiceExportButton(
                    label = "导出 JSON",
                    enabled = records.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    onClick = onExportJson,
                )
                VoiceExportButton(
                    label = "导出 CSV",
                    enabled = records.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    onClick = onExportCsv,
                )
            }
            exportStatus?.let { status ->
                Spacer(Modifier.height(8.dp))
                Text(status, fontSize = 9.5.sp, color = colors.muted)
            }
        }
    }
}

@Composable
private fun VoiceExportButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = AnebTheme.colors
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (enabled) Color(0x2232D9F5) else Color.Transparent)
            .border(1.dp, if (enabled) colors.brand.copy(alpha = 0.35f) else colors.hairline, RoundedCornerShape(11.dp))
            .pressable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) colors.brand else colors.faint,
        )
    }
}
