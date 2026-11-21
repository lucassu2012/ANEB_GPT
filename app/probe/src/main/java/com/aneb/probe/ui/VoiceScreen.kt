package com.aneb.probe.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
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
import com.aneb.probe.ui.theme.LocalReducedMotion
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

        // ---- 实时双向语音示波座舱（8 轮对话打断轨 + 400ms 红线时延图 + 上下行帧脉冲）----
        AnebSectionTitle(
            text = "实时双向语音示波座舱",
            modifier = Modifier.padding(top = 16.dp, start = 4.dp, bottom = 7.dp),
        )
        VoiceBidirectionalCockpit(sample = sample, running = running)

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
private fun VoiceBidirectionalCockpit(
    sample: VoiceRunner.Sample?,
    running: Boolean,
) {
    val colors = AnebTheme.colors
    val turns = sample?.liveTurnHistory.orEmpty()
    val totalTurns = (sample?.totalPlannedTurns ?: 8).coerceAtLeast(1)
    var selectedTurn by remember(turns.size, running) { mutableStateOf<Int?>(null) }
    val upProgress = ((sample?.framesSent ?: 0).toFloat() / (totalTurns * 75f + VoiceRunner.SIM_M3_FRAMES).coerceAtLeast(1f)).coerceIn(0f, 1f)
    val downProgress = ((sample?.framesRecv ?: 0).toFloat() / (totalTurns * 80f).coerceAtLeast(1f)).coerceIn(0f, 1f)
    val m7Gap = sample?.m7MaxFrameGapMs
    val m7Alert = m7Gap != null && m7Gap.isFinite() && m7Gap > 150.0
    val wave = sample?.liveFrameIntervalsMs.orEmpty()
    val direction = sample?.liveDirection
    val activeTurnIdx = sample?.liveActiveTurnIndex

    val (dirLabel, dirAccent) = when (direction) {
        "PING" -> "时延基线探测中" to colors.brand
        "UPLINK" -> (if (activeTurnIdx != null) "第 ${activeTurnIdx + 1} 轮 · 上行发声中（20ms 帧）" else "上行 20ms 帧节奏校准中") to colors.brand
        "WAITING_AI" -> (if (activeTurnIdx != null) "第 ${activeTurnIdx + 1} 轮 · 等待 AI 首帧开口" else "建立全双工会话中") to colors.fair
        "DOWNLINK" -> (if (activeTurnIdx != null) "第 ${activeTurnIdx + 1} 轮 · AI 回复中（50fps 帧）" else "下行语音流接收中") to colors.excellent
        "BARGE_IN" -> (if (activeTurnIdx != null) "第 ${activeTurnIdx + 1} 轮 · 触发打断停帧测试" else "打断停帧测试") to colors.brand2
        "DONE" -> "8 轮双工测量完成" to colors.excellent
        else -> (if (running) "准备实时语音会话…" else "待机 · 等待开始") to colors.muted
    }

    val instJit = sample?.liveInstantJitterMs ?: sample?.downNetJitterMs ?: sample?.upFrameJitterMs ?: sample?.jitterMs
    val wavePeak = wave.maxOrNull()
    val reducedMotion = LocalReducedMotion.current
    val spectrumPhase = if (running && !reducedMotion) {
        val transition = rememberInfiniteTransition(label = "voice-spectrum-carrier")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (2.0 * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
            label = "voice-spectrum-phase",
        ).value
    } else {
        0.6f
    }
    val frameCounterSeed = ((sample?.framesSent ?: 0) + (sample?.framesRecv ?: 0)) * 0.27f

    AnebGradientCard(Modifier.fillMaxWidth(), radius = 18.dp) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            // 1) 专业录音室级双向音频频谱 + 放大抖动示波器（168dp 固定舞台，25Hz 实时驱动）
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            Modifier
                                .width(7.dp)
                                .height(7.dp)
                                .clip(CircleShape)
                                .background(dirAccent),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(dirLabel, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.ink, maxLines = 1)
                    }
                    Text(
                        instJit?.takeIf { it.isFinite() }?.let { "实时抖动 ${String.format(Locale.ROOT, "%.1f", it)} ms" }
                            ?: "20ms 帧频谱",
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = if ((instJit ?: 0.0) > 25.0) colors.poor else dirAccent,
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(168.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFF050A17), Color(0xFF0A1429), Color(0xFF050A17)),
                            ),
                        )
                        .border(1.dp, dirAccent.copy(alpha = if (running) 0.55f else 0.22f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        val midY = h * 0.5f
                        val isPing = direction == "PING"
                        val isWaiting = direction == "WAITING_AI"
                        val isActiveVoice = running && direction in setOf("UPLINK", "DOWNLINK", "BARGE_IN", "PING")

                        // 精密声学刻度网格（±25% / ±50% dB 参考线 + 中轴线）
                        val gridStroke = 0.8.dp.toPx()
                        listOf(0.16f, 0.33f, 0.67f, 0.84f).forEach { frac ->
                            drawLine(
                                color = Color(0x226890C8),
                                start = Offset(0f, h * frac),
                                end = Offset(w, h * frac),
                                strokeWidth = gridStroke,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                            )
                        }
                        drawLine(
                            color = dirAccent.copy(alpha = 0.35f),
                            start = Offset(0f, midY),
                            end = Offset(w, midY),
                            strokeWidth = 1.dp.toPx(),
                        )

                        // 32 频段镜像高动态音频频谱柱 + 悬浮峰值帽（真实帧间隔能量 × 人声共振峰调制）
                        val barCount = 32
                        val gap = 2.6.dp.toPx()
                        val barW = ((w - gap * (barCount - 1)) / barCount).coerceAtLeast(2f)
                        val maxHalfH = h * 0.44f
                        val recentEnergy = if (wave.isNotEmpty()) {
                            val tail = wave.takeLast(12)
                            val meanDev = tail.map { abs(it - if (isPing) (sample?.rttMs ?: 30.0) else 20.0) }.average()
                            (0.58 + (meanDev / 14.0).coerceIn(0.0, 0.42)).toFloat()
                        } else if (isActiveVoice) {
                            0.68f
                        } else if (isWaiting) {
                            0.18f
                        } else {
                            0.08f
                        }

                        for (b in 0 until barCount) {
                            val norm = (b.toFloat() / (barCount - 1)) * 2f - 1f // -1..1 居中对称
                            // 人声共振峰包络（低频温暖 + 中频主峰 + 高频谐波衰减）
                            val formant = (
                                0.55f * exp((-norm * norm * 2.2f).toDouble()).toFloat() +
                                    0.35f * exp((-(abs(norm) - 0.42f) * (abs(norm) - 0.42f) * 14f).toDouble()).toFloat() +
                                    0.22f
                                ).coerceIn(0.18f, 1.0f)

                            val rawSample = if (wave.isNotEmpty()) {
                                val idx = ((b.toFloat() / barCount) * wave.size).toInt().coerceIn(0, wave.lastIndex)
                                wave[idx]
                            } else 20.0
                            val sampleDev = abs(rawSample - if (isPing) (sample?.rttMs ?: 30.0) else 20.0)
                            val spikeBoost = (sampleDev / 12.0).toFloat().coerceIn(0f, 0.85f)
                            val isSpikeBar = !isPing && rawSample > 38.0

                            val amp = if (isActiveVoice || (wave.size >= 2 && !running)) {
                                val osc1 = 0.5f + 0.5f * sin(spectrumPhase * 2f + b * 0.62f + frameCounterSeed)
                                val osc2 = 0.5f + 0.5f * cos(spectrumPhase * 3f - b * 0.41f + frameCounterSeed * 1.4f)
                                val liveMod = if (running) (0.34f + 0.66f * (0.62f * osc1 + 0.38f * osc2)) else (0.45f + 0.35f * osc1)
                                (formant * (recentEnergy * liveMod + spikeBoost * 0.55f)).coerceIn(0.10f, 0.96f)
                            } else if (isWaiting) {
                                val pulse = 0.5f + 0.5f * sin(spectrumPhase * 1.5f + b * 0.35f)
                                (0.08f + 0.16f * formant * pulse).coerceIn(0.06f, 0.28f)
                            } else {
                                (0.05f + 0.08f * formant).coerceIn(0.04f, 0.14f)
                            }

                            val barH = maxHalfH * amp
                            val x = b * (barW + gap)
                            val barColor = when {
                                isSpikeBar && rawSample > 80.0 -> colors.poor
                                isSpikeBar -> colors.fair
                                direction == "BARGE_IN" -> colors.brand2
                                else -> dirAccent
                            }

                            // 上半频谱柱
                            drawRoundRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(Color.White.copy(alpha = 0.92f), barColor, barColor.copy(alpha = 0.26f)),
                                    startY = midY - barH,
                                    endY = midY,
                                ),
                                topLeft = Offset(x, midY - barH),
                                size = Size(barW, barH),
                                cornerRadius = CornerRadius(barW * 0.4f, barW * 0.4f),
                            )
                            // 下半镜像频谱柱（微暗层次感）
                            drawRoundRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(barColor.copy(alpha = 0.24f), barColor.copy(alpha = 0.72f), Color.White.copy(alpha = 0.65f)),
                                    startY = midY,
                                    endY = midY + barH * 0.88f,
                                ),
                                topLeft = Offset(x, midY),
                                size = Size(barW, barH * 0.88f),
                                cornerRadius = CornerRadius(barW * 0.4f, barW * 0.4f),
                            )
                            // 专业音频悬浮峰值帽（Peak Hold Cap）
                            if (amp > 0.14f) {
                                val capY = (midY - barH - 4.dp.toPx()).coerceAtLeast(3.dp.toPx())
                                drawRoundRect(
                                    color = if (isSpikeBar) colors.poor else Color(0xFFE6FAFF),
                                    topLeft = Offset(x, capY),
                                    size = Size(barW, 2.dp.toPx()),
                                    cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()),
                                )
                            }
                        }

                        // 叠加高增益真实帧间隔微示波曲线（以 20ms 为中心自动缩放，放大 2-10ms 真实抖动）
                        if (wave.size >= 2) {
                            val centerVal = if (isPing) (sample?.rttMs ?: wave.average()) else 20.0
                            val maxDev = maxOf(
                                8.0,
                                wave.maxOf { abs(it - centerVal) } * 1.15,
                            )
                            val stepX = w / (wave.size - 1).coerceAtLeast(1)
                            val trace = Path()
                            wave.forEachIndexed { idx, ms ->
                                val x = idx * stepX
                                val normDelta = ((ms - centerVal) / maxDev).toFloat().coerceIn(-0.92f, 0.92f)
                                val y = midY - normDelta * maxHalfH * 0.92f
                                if (idx == 0) trace.moveTo(x, y) else trace.lineTo(x, y)
                            }
                            drawPath(
                                path = trace,
                                color = Color(0xEEFFFFFF),
                                style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round),
                            )
                            wave.forEachIndexed { idx, ms ->
                                if (!isPing && ms > 38.0) {
                                    val x = idx * stepX
                                    val normDelta = ((ms - centerVal) / maxDev).toFloat().coerceIn(-0.92f, 0.92f)
                                    val y = midY - normDelta * maxHalfH * 0.92f
                                    drawCircle(
                                        color = if (ms > 90.0) colors.poor else colors.fair,
                                        radius = 3.5.dp.toPx(),
                                        center = Offset(x, y),
                                    )
                                }
                            }
                        }
                    }

                    // 左上角专业控制台角标
                    Row(
                        modifier = Modifier.align(Alignment.TopStart),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (running) "LIVE · 25Hz SPECTRUM" else "AUDIO SPECTRUM",
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (running) dirAccent else colors.faint,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xBB070D1D))
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                    }
                    // 右上角峰值与基准读数
                    Row(
                        modifier = Modifier.align(Alignment.TopEnd),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (wave.size >= 2) {
                            Text(
                                "峰值 ${wavePeak?.roundToInt() ?: 0} ms",
                                fontSize = 8.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = if ((wavePeak ?: 0.0) > 45.0) colors.fair else colors.ink,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xBB070D1D))
                                    .padding(horizontal = 5.dp, vertical = 2.dp),
                            )
                        }
                        Text(
                            if (direction == "PING") "RTT 基准" else "20ms 中心轴",
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.muted,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xBB070D1D))
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                    }
                    // 底边频率标尺
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        listOf("125Hz", "500Hz", "1kHz", "4kHz", "8kHz").forEach { freq ->
                            Text(freq, fontSize = 7.5.sp, fontFamily = FontFamily.Monospace, color = colors.faint)
                        }
                    }
                    if (!running && wave.size < 2) {
                        Text(
                            "点击上方开始后，实时驱动 32 频段声学频谱与双向抖动示波",
                            fontSize = 9.5.sp,
                            color = colors.muted,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xCC060A15))
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }

            // 2) 双工帧进度轨：上行说话 vs 下行 AI 回复
            VoiceDuplexRail(
                label = "上行语音轨（我说话 · 20ms 帧）",
                stats = "已发 ${sample?.framesSent ?: 0} 帧 · M3 抖动 ${VoiceFormat.msOrDash(sample?.upFrameJitterMs)} ms",
                fraction = upProgress,
                accent = colors.brand,
            )
            VoiceDuplexRail(
                label = "下行语音轨（AI 回复 · 50fps 帧）",
                stats = "已收 ${sample?.framesRecv ?: 0} 帧 · M7 静默 ${VoiceFormat.msOrDash(m7Gap)} ms",
                fraction = downProgress,
                accent = if (m7Alert) colors.poor else colors.excellent,
            )

            // 3) 交互式 8 轮对话打断选择器（点击任意已完成轮次查看明细）
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("8 轮双工对话（T4/T7 为打断轮 · 点击查明细）", fontSize = 9.5.sp, color = colors.muted)
                    Text(
                        "${turns.size}/$totalTurns 轮",
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.brand,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (idx in 0 until totalTurns) {
                        val point = turns.firstOrNull { it.turnIndex == idx }
                        val isBarge = idx == 3 || idx == 6
                        val isActiveNow = running && activeTurnIdx == idx && point == null
                        val isSelected = selectedTurn == idx
                        val mouthEar = point?.mouthEarMs?.takeIf { it.isFinite() }
                        val cellAccent = when {
                            isActiveNow -> colors.brand
                            mouthEar == null && point == null -> colors.faint
                            mouthEar == null -> colors.fair
                            mouthEar <= 260.0 -> colors.excellent
                            mouthEar <= 400.0 -> colors.brand
                            else -> colors.poor
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    when {
                                        isSelected -> cellAccent.copy(alpha = 0.28f)
                                        point != null || isActiveNow -> cellAccent.copy(alpha = 0.14f)
                                        else -> Color(0x55090D1D)
                                    },
                                )
                                .border(
                                    if (isSelected || isActiveNow) 1.3.dp else 1.dp,
                                    if (point != null || isActiveNow) cellAccent.copy(alpha = 0.65f) else colors.hairline,
                                    RoundedCornerShape(8.dp),
                                )
                                .then(
                                    if (point != null) Modifier.pressable(onClick = { selectedTurn = if (isSelected) null else idx })
                                    else Modifier,
                                )
                                .padding(vertical = 5.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                if (isBarge) "T${idx + 1}*" else "T${idx + 1}",
                                fontSize = 8.sp,
                                fontWeight = if (isActiveNow || isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (point != null || isActiveNow) colors.ink else colors.faint,
                                maxLines = 1,
                            )
                            Text(
                                when {
                                    mouthEar != null -> "${mouthEar.roundToInt()}"
                                    isActiveNow -> "…"
                                    else -> "—"
                                },
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = cellAccent,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            // 选中轮次剖析条
            selectedTurn?.let { selIdx ->
                turns.firstOrNull { it.turnIndex == selIdx }?.let { pt ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xAA0C1327))
                            .border(1.dp, colors.brand.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "T${pt.turnIndex + 1}${if (pt.interrupted) "（打断轮）" else ""}",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.brand,
                        )
                        Text(
                            "口到耳 ${VoiceFormat.msOrDash(pt.mouthEarMs, 0)}ms · TTFB ${VoiceFormat.msOrDash(pt.ttfbMs, 0)}ms · 静默 ${VoiceFormat.msOrDash(pt.maxGapMs, 0)}ms" +
                                (pt.bargeStopMs?.let { " · 停帧 ${it.roundToInt()}ms" } ?: ""),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.ink,
                        )
                    }
                }
            }

            // 4) 逐轮口到耳 M1' vs 400ms 红线柱状图
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("逐轮口到耳 M1' 对比（浅紫内柱=下行 TTFB）", fontSize = 9.5.sp, color = colors.muted)
                    Text("红虚线=400ms 流畅线", fontSize = 8.5.sp, color = colors.poor.copy(alpha = 0.85f))
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(78.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x99070B17))
                        .border(1.dp, colors.hairline, RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val maxMs = maxOf(
                            520.0,
                            (turns.mapNotNull { it.mouthEarMs }.maxOrNull() ?: 0.0) * 1.15,
                        )
                        val vetoY = size.height * (1f - (400.0 / maxMs).toFloat().coerceIn(0f, 1f))
                        drawLine(
                            color = colors.poor.copy(alpha = 0.72f),
                            start = Offset(0f, vetoY),
                            end = Offset(size.width, vetoY),
                            strokeWidth = 1.2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f)),
                        )
                        val slotW = size.width / totalTurns.coerceAtLeast(1)
                        val barW = (slotW * 0.56f).coerceAtLeast(6f)
                        for (idx in 0 until totalTurns) {
                            val pt = turns.firstOrNull { it.turnIndex == idx } ?: continue
                            val m1 = pt.mouthEarMs?.takeIf { it.isFinite() } ?: continue
                            val barH = (size.height * (m1 / maxMs).toFloat().coerceIn(0.06f, 1f))
                            val left = idx * slotW + (slotW - barW) / 2f
                            val barColor = when {
                                m1 <= 260.0 -> colors.excellent
                                m1 <= 400.0 -> colors.brand
                                else -> colors.poor
                            }
                            drawRoundRect(
                                color = barColor.copy(alpha = if (selectedTurn == idx) 1f else 0.82f),
                                topLeft = Offset(left, size.height - barH),
                                size = Size(barW, barH),
                                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                            )
                            val ttfb = pt.ttfbMs?.takeIf { it.isFinite() && it > 0.0 }
                            if (ttfb != null) {
                                val ttfbH = (size.height * (ttfb / maxMs).toFloat().coerceIn(0.03f, 1f))
                                    .coerceAtMost(barH)
                                drawRoundRect(
                                    color = colors.brand2,
                                    topLeft = Offset(left, size.height - ttfbH),
                                    size = Size(barW, ttfbH),
                                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                                )
                            }
                        }
                    }
                    if (turns.isEmpty()) {
                        Text(
                            if (running) "完成首轮对话后逐轮绘制口到耳对比柱…" else "点击上方开始后逐轮记录口到耳延迟",
                            fontSize = 9.5.sp,
                            color = colors.faint,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceDuplexRail(
    label: String,
    stats: String,
    fraction: Float,
    accent: Color,
) {
    val colors = AnebTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = colors.ink)
            Text(stats, fontSize = 9.sp, color = accent)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(Color(0x55222C4A)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
        }
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
