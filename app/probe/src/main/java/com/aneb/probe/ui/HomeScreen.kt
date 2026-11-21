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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.BuildConfig
import com.aneb.probe.data.BasicSpeedResultEntity
import com.aneb.probe.data.TestRun
import com.aneb.probe.data.VoiceResultEntity
import com.aneb.probe.engine.AnebTestMode
import com.aneb.probe.ui.components.AnebGlyph
import com.aneb.probe.ui.components.AnebGradientCard
import com.aneb.probe.ui.components.AnebIcon
import com.aneb.probe.ui.components.AnebWordmark
import com.aneb.probe.ui.components.pressable
import com.aneb.probe.ui.theme.AnebPalette
import com.aneb.probe.ui.theme.AnebTheme
import com.aneb.probe.ui.theme.Grade
import com.aneb.probe.ui.theme.LocalReducedMotion
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 公网正式版（`release`：`probe-release.apk`）面向外部手机测速用户，默认隐藏首页右上角
 * 局域网 `PROTOTYPE` 入口；仅在 `prototypeRelease`、`prototypeEngineering` 及 `debug` 变体展示。
 */
internal fun shouldShowPrototypeEntry(
    isPrototypeRelease: Boolean = BuildConfig.PROTOTYPE_RELEASE,
    isPrototypeEngineering: Boolean = BuildConfig.PROTOTYPE_ENGINEERING,
    isDebug: Boolean = BuildConfig.DEBUG,
): Boolean = isPrototypeRelease || isPrototypeEngineering || isDebug

/**
 * 首页：AI 与热门应用网络体验一级工作台。
 * 首屏直接暴露核心模式（AI 对话/编码 · 抗负载响应 · 基础/抖音）与四大特色直达入口
 * （实时 AI 语音 Profile 4 · 抖音/豆包体验伴侣 · 多模态 AI 轨迹 v2 · 主流 AI 直连测通），
 * 摆脱普通 Speedtest 单纯测带宽的局限。
 */
@Composable
fun HomeScreen(
    lastRun: TestRun?,
    lastBasicRun: BasicSpeedResultEntity?,
    testMode: AnebTestMode,
    onTestModeChange: (AnebTestMode) -> Unit,
    running: Boolean,
    notice: String? = null,
    connectionLabel: String,
    nodeLabel: String,
    onStart: () -> Unit,
    onOpenServer: () -> Unit,
    onOpenPrototype: () -> Unit,
    onOpenLastResult: (String) -> Unit,
    onOpenLastBasicResult: (String) -> Unit,
    onOpenVoice: (() -> Unit)? = null,
    showPrototypeEntry: Boolean = shouldShowPrototypeEntry(),
    lastVoiceRun: VoiceResultEntity? = null,
    onOpenAppCompanion: (() -> Unit)? = null,
    onOpenProfiles: (() -> Unit)? = null,
    onOpenReachBoard: (() -> Unit)? = null,
) {
    val colors = AnebTheme.colors
    val voiceBadge = remember(lastVoiceRun) {
        if (lastVoiceRun == null) {
            "豆包语音同口径"
        } else {
            val (mouthEar, _) = VoiceFormat.primaryMouthEar(lastVoiceRun.mouthEarProxyMs, lastVoiceRun.mouthEarBudgetMs)
            mouthEar?.takeIf { it.isFinite() }?.let { "口到耳 ${it.roundToInt()}ms" } ?: "已测 Profile 4"
        }
    }
    val appBadge = remember(lastBasicRun, lastRun) {
        when {
            lastBasicRun?.downloadMbps != null -> "抖音·${BusinessExperienceEvaluator.evaluateDouyin(lastBasicRun).badge}"
            lastRun?.aqsScore != null -> "豆包·${BusinessExperienceEvaluator.evaluateDoubaoChat(lastRun).badge}"
            else -> "校准模型+真机"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AnebPalette.Dark.DeepBackground),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 92.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ---- 顶栏 ----
            Box(Modifier.fillMaxWidth().height(50.dp)) {
                AnebWordmark(Modifier.align(Alignment.Center))
                if (showPrototypeEntry) {
                    Text(
                        "PROTOTYPE",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = colors.brand,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color(0x1F32D9F5))
                            .border(1.dp, colors.brand.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
                            .pressable(onClick = onOpenPrototype)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            TestModeSwitch(
                selected = testMode,
                enabled = !running,
                onSelect = onTestModeChange,
            )

            Spacer(Modifier.height(10.dp))
            ActiveModePreviewStrip(testMode = testMode)

            Spacer(Modifier.height(14.dp))
            IdleStartRing(testMode = testMode, running = running, onStart = onStart)

            Text(
                when (testMode) {
                    AnebTestMode.NETWORK_RESPONSIVENESS ->
                        "模拟后台下载或传大图时，前台聊 AI / 打语音会不会突然变卡（测延迟膨胀 Δ）"
                    AnebTestMode.NETWORK_BASIC ->
                        "测下载、上传、Ping、抖动，并按实机校准拐点评估抖音 1080P 与 6 大 AI 场景"
                    AnebTestMode.TOKEN_EXPERIENCE ->
                        "评估网络是否适合 AI 对话、编码、豆包流畅吐字和文件上传"
                },
                fontSize = 10.sp,
                lineHeight = 15.sp,
                textAlign = TextAlign.Center,
                color = colors.muted,
                modifier = Modifier.padding(top = 10.dp, start = 12.dp, end = 12.dp),
            )

            notice?.let {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xF2121829))
                        .border(1.dp, colors.fair.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(colors.fair))
                    Spacer(Modifier.width(8.dp))
                    Text(it, fontSize = 10.5.sp, color = colors.ink)
                }
            }

            // ---- 清晰视觉分隔：以下为独立专项剧场（不随上方模式切换）----
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "独立专项实测（不随上方切换）",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.ink,
                )
                Text(
                    "一键直达专页",
                    fontSize = 9.5.sp,
                    color = colors.faint,
                )
            }
            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                FeatureQuickCard(
                    glyph = AnebGlyph.Voice,
                    accent = colors.excellent,
                    title = "实时 AI 语音",
                    badge = voiceBadge,
                    subtitle = "10Hz 实时抖动示波 · 8 轮可点查",
                    enabled = onOpenVoice != null,
                    onClick = { onOpenVoice?.invoke() },
                    modifier = Modifier.weight(1f),
                )
                FeatureQuickCard(
                    glyph = AnebGlyph.Video,
                    accent = colors.brand,
                    title = "抖音 / 豆包体验",
                    badge = appBadge,
                    subtitle = "实测拐点评估 + 拉起真机对照",
                    enabled = onOpenAppCompanion != null,
                    onClick = { onOpenAppCompanion?.invoke() },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(9.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                FeatureQuickCard(
                    glyph = AnebGlyph.Image,
                    accent = colors.brand2,
                    title = "多模态 AI 轨迹",
                    badge = "~25s 三模态",
                    subtitle = "文本/1MB文档/5MB高清图实时实战",
                    enabled = onOpenProfiles != null,
                    onClick = { onOpenProfiles?.invoke() },
                    modifier = Modifier.weight(1f),
                )
                FeatureQuickCard(
                    glyph = AnebGlyph.Pulse,
                    accent = colors.fair,
                    title = "主流 AI 直连测通",
                    badge = "REACH",
                    subtitle = "豆包 · DeepSeek · Kimi 握手",
                    enabled = onOpenReachBoard != null,
                    onClick = { onOpenReachBoard?.invoke() },
                    modifier = Modifier.weight(1f),
                )
            }

            // ---- 节点与上次成绩状态卡 ----
            Spacer(Modifier.height(12.dp))
            AnebGradientCard(Modifier.fillMaxWidth(), radius = 18.dp) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pressable(onClick = onOpenServer)
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NetworkGlyph()
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                connectionLabel,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "$nodeLabel · 真实测试节点",
                                fontSize = 10.sp,
                                color = colors.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            "更换节点 ›",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.brand,
                        )
                    }

                    if (testMode.isBasic && lastBasicRun != null) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(colors.hairline))
                        val down = lastBasicRun.downloadMbps?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—"
                        val up = lastBasicRun.uploadMbps?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—"
                        SheetDetailRow(
                            symbol = "↕",
                            label = if (lastBasicRun.profileId == com.aneb.probe.engine.NetworkSpeedEngine.RESPONSIVENESS_PROFILE_ID)
                                "上次高负载并发" else "上次基础 / 抖音测速",
                            value = "↓ $down  ↑ $up Mbps",
                            action = "查看",
                            onAction = { onOpenLastBasicResult(lastBasicRun.runId) },
                        )
                    } else if (lastRun != null && testMode == AnebTestMode.TOKEN_EXPERIENCE) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(colors.hairline))
                        val grade = lastRun.aqsScore?.let { Grade.fromAqsScore(it) }
                        SheetDetailRow(
                            symbol = "↗",
                            label = "上次 AI 体验成绩",
                            value = "${lastRun.aqsScore?.roundToInt() ?: "—"} 分 · ${grade?.labelFriendly ?: "未完成"}",
                            action = "查看",
                            onAction = { onOpenLastResult(lastRun.runId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureQuickCard(
    glyph: AnebGlyph,
    accent: Color,
    title: String,
    badge: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AnebTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xE0171D38), Color(0xE80C1023)),
                ),
            )
            .border(1.dp, accent.copy(alpha = if (enabled) 0.28f else 0.12f), RoundedCornerShape(16.dp))
            .pressable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            AnebIcon(glyph = glyph, tint = accent, size = 16.dp)
            Text(
                badge,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.13f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(7.dp))
        Text(
            title,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            subtitle,
            fontSize = 9.sp,
            lineHeight = 13.sp,
            color = colors.muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun TestModeSwitch(
    selected: AnebTestMode,
    enabled: Boolean,
    onSelect: (AnebTestMode) -> Unit,
) {
    val colors = AnebTheme.colors
    val orderedModes = listOf(
        AnebTestMode.TOKEN_EXPERIENCE to "AI 对话/编码",
        AnebTestMode.NETWORK_RESPONSIVENESS to "高负载并发",
        AnebTestMode.NETWORK_BASIC to "基础/抖音",
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xA80B1020))
            .border(1.dp, colors.hairline, RoundedCornerShape(999.dp))
            .padding(3.dp),
    ) {
        orderedModes.forEach { (mode, displayLabel) ->
            val active = mode == selected
            Text(
                displayLabel,
                fontSize = 10.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) colors.ink else colors.muted,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (active) colors.brand.copy(alpha = 0.16f) else Color.Transparent)
                    .border(
                        1.dp,
                        if (active) colors.brand.copy(alpha = 0.30f) else Color.Transparent,
                        RoundedCornerShape(999.dp),
                    )
                    .pressable(enabled = enabled, onClick = { onSelect(mode) })
                    .padding(horizontal = 13.dp, vertical = 7.dp),
            )
        }
    }
}

/** 动态模式实测预览条：随顶部三模式切换实时变换主题色与实测环节，零 emoji，一眼分清三者差异。 */
@Composable
private fun ActiveModePreviewStrip(testMode: AnebTestMode) {
    val colors = AnebTheme.colors
    val (accent, stages, outputLine) = when (testMode) {
        AnebTestMode.TOKEN_EXPERIENCE -> Triple(
            colors.brand,
            listOf(
                AnebGlyph.Chat to "AI 对话流",
                AnebGlyph.Code to "编码 Diff",
                AnebGlyph.Image to "多模态图检",
            ),
            "点击开始 → 真实模拟对话/写代码/传图识图 · 测 TTFT/ITL/卡顿",
        )
        AnebTestMode.NETWORK_RESPONSIVENESS -> Triple(
            colors.brand2,
            listOf(
                AnebGlyph.Pulse to "01 空闲反应",
                AnebGlyph.Load to "02 边下载边交互",
                AnebGlyph.Load to "03 边上传边交互",
            ),
            "目的：查清后台下载或传大图时，前台聊 AI/打语音会不会突然卡顿",
        )
        AnebTestMode.NETWORK_BASIC -> Triple(
            colors.excellent,
            listOf(
                AnebGlyph.Pulse to "Ping / 抖动",
                AnebGlyph.Load to "并发上下行",
                AnebGlyph.Video to "抖音 1080P 拐点",
            ),
            "点击开始 → 实测上下行 Mbps + 抖音流畅度 + 6 大 AI 场景",
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.08f))
            .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            stages.forEach { (glyph, stage) ->
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .background(Color(0x880B1022))
                        .border(1.dp, accent.copy(alpha = 0.25f), CircleShape)
                        .padding(vertical = 4.dp, horizontal = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    AnebIcon(glyph = glyph, tint = accent, size = 11.dp, strokeDp = 1.3.dp)
                    Spacer(Modifier.width(3.dp))
                    Text(
                        stage,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Text(
            outputLine,
            fontSize = 8.5.sp,
            color = accent,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

@Composable
private fun IdleStartRing(testMode: AnebTestMode, running: Boolean, onStart: () -> Unit) {
    val colors = AnebTheme.colors
    val reducedMotion = LocalReducedMotion.current
    val (ringGlowColor, sweepColors, modeCaption) = when (testMode) {
        AnebTestMode.TOKEN_EXPERIENCE -> Triple(
            Color(0xFF43E1E6),
            listOf(Color(0xFF67EDCC), Color(0xFF43E1E6), Color(0xFF3EB4F1), Color(0xFF67EDCC)),
            "AI 对话 / 编码",
        )
        AnebTestMode.NETWORK_RESPONSIVENESS -> Triple(
            Color(0xFF9C7BFF),
            listOf(Color(0xFF7C8BFF), Color(0xFFA97BFF), Color(0xFFF5A94C), Color(0xFF7C8BFF)),
            "高负载并发",
        )
        AnebTestMode.NETWORK_BASIC -> Triple(
            Color(0xFF46E3A6),
            listOf(Color(0xFF46E3A6), Color(0xFF39D9C2), Color(0xFF4CC6F7), Color(0xFF46E3A6)),
            "基础 / 抖音评估",
        )
    }
    val breath: Float
    val glow: Float
    val rotation: Float
    if (reducedMotion) {
        breath = 1f
        glow = 0.18f
        rotation = 0f
    } else {
        val transition = rememberInfiniteTransition(label = "idle-start-ring")
        breath = transition.animateFloat(
            initialValue = 0.985f,
            targetValue = 1.025f,
            animationSpec = infiniteRepeatable(tween(1_650), RepeatMode.Reverse),
            label = "idle-start-breath",
        ).value
        glow = transition.animateFloat(
            initialValue = 0.16f,
            targetValue = 0.46f,
            animationSpec = infiniteRepeatable(tween(1_650), RepeatMode.Reverse),
            label = "idle-start-glow",
        ).value
        rotation = transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(7_000, easing = LinearEasing)),
            label = "idle-start-rotation",
        ).value
    }
    Box(
        modifier = Modifier
            .size(164.dp)
            .scale(breath)
            .pressable(onClick = onStart, enabled = !running),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = 1.4.dp.toPx()
            drawCircle(
                color = ringGlowColor.copy(alpha = glow),
                radius = size.minDimension / 2f - stroke,
                style = Stroke(8.dp.toPx(), cap = StrokeCap.Round),
            )
            rotate(rotation, pivot = center) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        sweepColors,
                        center = center,
                    ),
                    radius = size.minDimension / 2f - stroke,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (running) "进行中" else "开始",
                fontSize = 30.sp,
                fontWeight = FontWeight(320),
                letterSpacing = (-1.2).sp,
                color = colors.ink,
            )
            Text(
                modeCaption,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Medium,
                color = ringGlowColor,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
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
                fontSize = 9.5.sp,
                color = colors.brand,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .border(1.dp, colors.brand.copy(alpha = 0.28f), RoundedCornerShape(999.dp))
                    .pressable(onClick = onAction)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
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
