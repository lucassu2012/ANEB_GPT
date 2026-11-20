package com.aneb.probe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.engine.BehaviorTraceRunner
import com.aneb.probe.engine.ProfileCapability
import com.aneb.probe.engine.ProfilePhase
import com.aneb.probe.engine.ScenarioProfile
import com.aneb.probe.ui.components.AnebPageIntro
import com.aneb.probe.ui.components.AnebTopBar
import com.aneb.probe.ui.components.pressable
import com.aneb.probe.ui.theme.AnebTheme
import java.util.Locale

/**
 * Profile 目录只呈现节点实际下发或 APK 内置的版本化合同，不在 UI 中复制配置。
 * “可执行”仅表示当前 APK 已识别 mode/phase 与展示合同，不代表会改变既有 AQS 编排。
 */
@Composable
fun ProfileCatalogScreen(
    profiles: List<ScenarioProfile>,
    source: String?,
    warnings: List<String>,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onRunV2Simulation: ((ScenarioProfile) -> Unit)? = null,
    onCancelV2Simulation: (() -> Unit)? = null,
    v2SimulationRunning: Boolean = false,
    v2SimulationLogs: List<String> = emptyList(),
    v2SimulationResult: BehaviorTraceRunner.BehaviorTraceRunResult? = null,
    v2SimulationError: String? = null,
) {
    val colors = AnebTheme.colors
    val view = LocalView.current
    DisposableEffect(view, v2SimulationRunning) {
        val prev = view.keepScreenOn
        if (v2SimulationRunning) {
            view.keepScreenOn = true
        }
        onDispose { view.keepScreenOn = prev }
    }
    Column(Modifier.fillMaxSize().background(colors.background).padding(horizontal = 16.dp)) {
        AnebTopBar(showBack = true, onBack = onBack, showMenu = true)
        Row(verticalAlignment = Alignment.Bottom) {
            AnebPageIntro(
                eyebrow = "PROFILE REGISTRY",
                title = "测试方案目录",
                subtitle = "业务、指标、实时动效与结论策略的版本化合同。",
                modifier = Modifier.weight(1f),
            )
            Text(
                if (loading) "同步中" else "刷新",
                fontSize = 10.sp,
                color = if (loading) colors.faint else colors.brand,
                modifier = Modifier
                    .border(1.dp, colors.hairline, CircleShape)
                    .pressable(enabled = !loading, onClick = onRefresh)
                    .padding(horizontal = 11.dp, vertical = 7.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 12.dp, bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ContractStatusCard(
                    source = source,
                    count = profiles.size,
                    warnings = warnings,
                    loading = loading,
                    error = error,
                )
            }
            items(profiles, key = { "${it.profileId}@${it.version}" }) { profile ->
                ProfileContractCard(
                    profile = profile,
                    onRunV2Simulation = onRunV2Simulation,
                    onCancelV2Simulation = onCancelV2Simulation,
                    v2SimulationRunning = v2SimulationRunning,
                )
            }
            if (v2SimulationRunning || v2SimulationLogs.isNotEmpty() || v2SimulationResult != null || v2SimulationError != null) {
                item {
                    V2SimulationPanel(
                        running = v2SimulationRunning,
                        logs = v2SimulationLogs,
                        result = v2SimulationResult,
                        error = v2SimulationError,
                    )
                }
            }
            if (!loading && profiles.isEmpty()) {
                item {
                    EmptyCatalog(error)
                }
            }
            item {
                Text(
                    "执行边界：Token 体验仍按 S1/S2/S3 固定映射生成 AQS；基础测速独立输出下载、上传、RTT、抖动和应用层请求失败率。目录中的兼容状态不改变评分或场景顺序。",
                    fontSize = 9.sp,
                    lineHeight = 14.sp,
                    color = colors.faint,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ContractStatusCard(
    source: String?,
    count: Int,
    warnings: List<String>,
    loading: Boolean,
    error: String?,
) {
    val colors = AnebTheme.colors
    val sourceText = when {
        loading && source == null -> "正在核验节点配置"
        source == "server" -> "节点已核验"
        source == "assets_fallback" -> "APK 内置兜底"
        else -> "尚未核验"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF11172B), RoundedCornerShape(18.dp))
            .border(1.dp, colors.hairline, RoundedCornerShape(18.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(7.dp)
                    .height(7.dp)
                    .background(
                        if (source == "server") colors.excellent else if (error == null) colors.fair else colors.poor,
                        CircleShape,
                    ),
            )
            Spacer(Modifier.width(8.dp))
            Text(sourceText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
            Spacer(Modifier.weight(1f))
            Text("$count 个 Profile", fontSize = 10.sp, color = colors.muted)
        }
        Text(
            when {
                error != null -> error
                warnings.isNotEmpty() -> "节点配置未完成一致性核验；当前展示已明确标注来源。"
                source == "server" -> "展示内容来自当前测试节点，并已通过 Profile 合同解析。"
                else -> "离线展示随 APK 发布的冻结副本。"
            },
            fontSize = 9.5.sp,
            lineHeight = 14.sp,
            color = if (error == null) colors.muted else colors.poor,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun ProfileContractCard(
    profile: ScenarioProfile,
    onRunV2Simulation: ((ScenarioProfile) -> Unit)? = null,
    onCancelV2Simulation: (() -> Unit)? = null,
    v2SimulationRunning: Boolean = false,
) {
    val colors = AnebTheme.colors
    val assessment = ProfileCapability.assess(profile)
    val isV2 = profile.contractVersion == ScenarioProfile.CONTRACT_V2
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF0E1426), RoundedCornerShape(18.dp))
            .border(1.dp, colors.hairline, RoundedCornerShape(18.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(profileTitle(profile), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                Text(
                    "${modeLabel(profile.modeId)} · ${profile.profileId}@${profile.version}",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.muted,
                )
            }
            StatusPill(assessment.executable)
        }
        Text(
            if (isV2 && profile.description.isBlank()) {
                "${profile.business.label} · 模型 ${profile.business.behaviorModelId}@${profile.business.behaviorModelVersion} (${profile.business.calibrationStatus})"
            } else {
                profile.description.ifBlank { "未提供业务说明" }
            },
            fontSize = 10.sp,
            lineHeight = 15.sp,
            color = colors.muted,
            modifier = Modifier.padding(top = 9.dp),
        )

        if (isV2) {
            ContractLine(
                label = "实时主指标",
                value = profile.livePresentation.primaryMetricId.ifBlank { "—" } +
                    profile.livePresentation.secondaryMetricIds.takeIf { it.isNotEmpty() }
                        ?.let { " · ${it.joinToString(" / ")}" }.orEmpty(),
                detail = "${profile.livePresentation.windowMs}ms 窗口 · ${profile.livePresentation.uiRefreshMs}ms 刷新 · ${profile.livePresentation.missingBehavior}",
            )
            ContractLine(
                label = "必需指标",
                value = profile.evaluation.requiredMetricIds.takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: "—",
                detail = "共 ${profile.measurements.size} 项度量 · 门控 ${profile.evaluation.guardrailMetricIds.joinToString().ifBlank { "无" }}",
            )
            ContractLine(
                label = "评分策略",
                value = profile.evaluation.scorePolicyId.ifBlank { "—" },
                detail = "缺失策略=${profile.evaluation.missingRequiredMetric} · 轨迹 seed=${profile.trace?.seed ?: 0L}",
            )
        } else {
            ContractLine(
                label = "实时主指标",
                value = profile.presentation.liveMetricLabel.ifBlank { "—" } +
                    profile.presentation.liveMetricUnit.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                detail = if (profile.presentation.liveWindowMs > 0 && profile.presentation.uiRefreshMs > 0) {
                    "${profile.presentation.liveWindowMs}ms 窗口 · ${profile.presentation.uiRefreshMs}ms 刷新"
                } else null,
            )
            ContractLine(
                label = "输出指标",
                value = profile.presentation.metricIds.takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: "—",
            )
            ContractLine(
                label = "结论策略",
                value = profile.presentation.conclusionPolicyId.ifBlank { "—" },
            )
        }
        ContractLine(
            label = "测试阶段",
            value = profile.phases.joinToString(" → ") { phaseLabel(it.type) }.ifBlank { "—" },
            detail = profile.estDurationS.takeIf { it > 0 }?.let { "配置时长约 ${it.toInt()} 秒" },
        )

        val limitation = buildList {
            if (assessment.unsupportedPhaseTypes.isNotEmpty()) {
                add("需引擎插件：${assessment.unsupportedPhaseTypes.joinToString()}")
            }
            addAll(assessment.contractIssues)
        }
        if (limitation.isNotEmpty()) {
            Text(
                limitation.joinToString("；"),
                fontSize = 9.sp,
                lineHeight = 13.sp,
                color = colors.fair,
                modifier = Modifier.padding(top = 9.dp),
            )
        }
        if (assessment.executable && isV2 && profile.modeId == ScenarioProfile.MODE_TOKEN_SIMULATION && onRunV2Simulation != null) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (v2SimulationRunning) "仿真运行中…" else "运行行为轨迹仿真",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (v2SimulationRunning) colors.faint else colors.brand,
                    modifier = Modifier
                        .border(1.dp, if (v2SimulationRunning) colors.hairline else colors.brand.copy(alpha = 0.4f), CircleShape)
                        .pressable(enabled = !v2SimulationRunning, onClick = { onRunV2Simulation(profile) })
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                if (v2SimulationRunning && onCancelV2Simulation != null) {
                    Text(
                        "停止",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.poor,
                        modifier = Modifier
                            .border(1.dp, colors.poor.copy(alpha = 0.4f), CircleShape)
                            .pressable(onClick = onCancelV2Simulation)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun V2SimulationPanel(
    running: Boolean,
    logs: List<String>,
    result: BehaviorTraceRunner.BehaviorTraceRunResult?,
    error: String?,
) {
    val colors = AnebTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF11172B), RoundedCornerShape(18.dp))
            .border(1.dp, colors.hairline, RoundedCornerShape(18.dp))
            .padding(14.dp),
    ) {
        Text(
            if (running) "aneb-profile-v2 仿真执行中" else "aneb-profile-v2 仿真摘要",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.ink,
        )
        error?.let {
            Text(it, fontSize = 10.sp, color = colors.poor, modifier = Modifier.padding(top = 6.dp))
        }
        result?.let { r ->
            val scoreText = r.score?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "null (${r.suppressionReasons.joinToString()})"
            Text(
                "status=${r.status} · tasks=${r.tasksSucceeded}/${r.tasksExecuted} · score=$scoreText",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = if (r.computable) colors.excellent else colors.fair,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                "TOK-B01=${r.metrics["TOK-B01"]?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "null"} · " +
                    "TOK-B11=${r.metrics["TOK-B11"]?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "null"} · " +
                    "TOK-N03=${r.metrics["TOK-N03"]?.let { String.format(Locale.ROOT, "%.1fms", it) } ?: "null"}",
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        logs.takeLast(6).forEach { line ->
            Text(
                line,
                fontSize = 8.5.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.faint,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun StatusPill(executable: Boolean) {
    val colors = AnebTheme.colors
    val color = if (executable) colors.excellent else colors.fair
    Text(
        if (executable) "当前引擎可执行" else "需要引擎适配",
        fontSize = 8.5.sp,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.10f), CircleShape)
            .border(1.dp, color.copy(alpha = 0.24f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

@Composable
private fun ContractLine(label: String, value: String, detail: String? = null) {
    val colors = AnebTheme.colors
    Row(Modifier.fillMaxWidth().padding(top = 9.dp), verticalAlignment = Alignment.Top) {
        Text(label, fontSize = 9.sp, color = colors.faint, modifier = Modifier.width(70.dp))
        Column(Modifier.weight(1f)) {
            Text(value, fontSize = 10.sp, lineHeight = 14.sp, color = colors.ink)
            detail?.let { Text(it, fontSize = 8.5.sp, color = colors.muted, modifier = Modifier.padding(top = 2.dp)) }
        }
    }
}

@Composable
private fun EmptyCatalog(error: String?) {
    val colors = AnebTheme.colors
    Box(
        Modifier.fillMaxWidth().height(130.dp).border(1.dp, colors.hairline, RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(error ?: "没有可展示的 Profile", fontSize = 11.sp, color = colors.muted)
    }
}

private fun modeLabel(modeId: String): String = when (modeId) {
    ScenarioProfile.MODE_TOKEN_EXPERIENCE -> "Token 体验"
    ScenarioProfile.MODE_NETWORK_BASIC -> "基本测速"
    ScenarioProfile.MODE_TOKEN_SIMULATION -> "Token 仿真"
    ScenarioProfile.MODE_AI_REALTIME_SIMULATION -> "AI 实时语音仿真"
    ScenarioProfile.MODE_NETWORK_COMPREHENSIVE -> "网络综合"
    else -> "扩展模式"
}

private fun profileTitle(profile: ScenarioProfile): String = when (profile.profileId) {
    "s1_chat" -> "AI 对话流"
    "s2_coding_agent" -> "编码 Agent"
    "s3_multimodal" -> "多模态任务"
    "basic_network" -> "网络基本性能"
    "token_multimodal_standard" -> profile.business.label.ifBlank { "多模态 Token 仿真" }
    else -> profile.business.label.ifBlank { profile.profileId }
}

private fun phaseLabel(type: String): String = when (type) {
    ProfilePhase.TYPE_CLOCK_SYNC -> "时延"
    ProfilePhase.TYPE_UPLOAD_BURST -> "上行突发"
    ProfilePhase.TYPE_THINK_PAUSE -> "思考停顿"
    ProfilePhase.TYPE_TOKEN_STREAM -> "流式输出"
    ProfilePhase.TYPE_TOOL_LOOP -> "工具循环"
    ProfilePhase.TYPE_DOWNLOAD_THROUGHPUT -> "下载"
    ProfilePhase.TYPE_UPLOAD_THROUGHPUT -> "上传"
    ProfilePhase.TYPE_BEHAVIOR_TRACE -> "行为轨迹"
    else -> type
}
