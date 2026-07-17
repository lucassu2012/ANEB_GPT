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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.engine.ProfileCapability
import com.aneb.probe.engine.ProfilePhase
import com.aneb.probe.engine.ScenarioProfile
import com.aneb.probe.ui.components.AnebPageIntro
import com.aneb.probe.ui.components.AnebTopBar
import com.aneb.probe.ui.components.pressable
import com.aneb.probe.ui.theme.AnebTheme

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
    showBack: Boolean = true,
) {
    val colors = AnebTheme.colors
    Column(Modifier.fillMaxSize().background(colors.background).padding(horizontal = 16.dp)) {
        AnebTopBar(showBack = showBack, onBack = onBack, showMenu = true)
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
                ProfileContractCard(profile)
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
                warnings.any { it.startsWith("bundled_audit_only_profiles=") } ->
                    "节点合同已核验；同时展示 APK 内置候选 Profile，候选项不会自动执行或进入评分。"
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
private fun ProfileContractCard(profile: ScenarioProfile) {
    val colors = AnebTheme.colors
    val assessment = ProfileCapability.assess(profile)
    val isV2 = profile.contractVersion == ScenarioProfile.CONTRACT_V2
    val title = profile.business.label.takeIf { isV2 && it.isNotBlank() } ?: profileTitle(profile.profileId)
    val liveMetric = if (isV2) profile.livePresentation.primaryMetricId else profile.presentation.liveMetricLabel
    val liveDetail = if (isV2) {
        "${profile.livePresentation.windowMs}ms 窗口 · ${profile.livePresentation.uiRefreshMs}ms 刷新 · ${profile.livePresentation.staleAfterMs}ms 过期"
    } else if (profile.presentation.liveWindowMs > 0 && profile.presentation.uiRefreshMs > 0) {
        "${profile.presentation.liveWindowMs}ms 窗口 · ${profile.presentation.uiRefreshMs}ms 刷新"
    } else null
    val metricSummary = if (isV2) {
        profile.measurements.take(6).joinToString(" · ") { it.label.ifBlank { it.metricId } } +
            if (profile.measurements.size > 6) " · +${profile.measurements.size - 6}" else ""
    } else {
        profile.presentation.metricIds.joinToString(" · ")
    }
    val conclusionPolicy = if (isV2) profile.evaluation.conclusionPolicyId else profile.presentation.conclusionPolicyId
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF0E1426), RoundedCornerShape(18.dp))
            .border(1.dp, colors.hairline, RoundedCornerShape(18.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                Text(
                    "${modeLabel(profile.modeId)} · ${profile.profileId}@${profile.version}",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.muted,
                )
            }
            StatusPill(assessment.executable, profile.version.contains("draft", ignoreCase = true))
        }
        Text(
            profile.description.ifBlank {
                profile.business.behaviorFeatureIds.takeIf { isV2 && it.isNotEmpty() }?.joinToString(" · ")
                    ?: "未提供业务说明"
            },
            fontSize = 10.sp,
            lineHeight = 15.sp,
            color = colors.muted,
            modifier = Modifier.padding(top = 9.dp),
        )

        if (isV2) {
            ContractLine(
                label = "业务与边界",
                value = profile.business.categoryId,
                detail = "${profile.claimScope} · ${profile.business.calibrationStatus}",
            )
        }
        ContractLine(
            label = "实时主指标",
            value = liveMetric.ifBlank { "—" } + if (!isV2) {
                profile.presentation.liveMetricUnit.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            } else "",
            detail = liveDetail,
        )
        ContractLine(
            label = "输出指标",
            value = metricSummary.ifBlank { "—" },
        )
        ContractLine(
            label = "结论策略",
            value = conclusionPolicy.ifBlank { "—" },
            detail = profile.evaluation.targetSetId.takeIf { isV2 && it.isNotBlank() },
        )
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
    }
}

@Composable
private fun StatusPill(executable: Boolean, draft: Boolean) {
    val colors = AnebTheme.colors
    val color = if (executable) colors.excellent else colors.fair
    Text(
        when {
            draft -> "候选 · 未执行"
            executable -> "当前引擎可执行"
            else -> "需要引擎适配"
        },
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
    ScenarioProfile.MODE_TOKEN_SIMULATION -> "Token 模拟"
    ScenarioProfile.MODE_AI_REALTIME_SIMULATION -> "AI 实时交互"
    ScenarioProfile.MODE_NETWORK_COMPREHENSIVE -> "网络综合性能"
    else -> "扩展模式"
}

private fun profileTitle(id: String): String = when (id) {
    "s1_chat" -> "AI 对话流"
    "s2_coding_agent" -> "编码 Agent"
    "s3_multimodal" -> "多模态任务"
    "basic_network" -> "网络基本性能"
    else -> id
}

private fun phaseLabel(type: String): String = when (type) {
    ProfilePhase.TYPE_CLOCK_SYNC -> "时延"
    ProfilePhase.TYPE_UPLOAD_BURST -> "上行突发"
    ProfilePhase.TYPE_THINK_PAUSE -> "思考停顿"
    ProfilePhase.TYPE_TOKEN_STREAM -> "流式输出"
    ProfilePhase.TYPE_DOWNLOAD_BURST -> "下行大对象"
    ProfilePhase.TYPE_TOOL_LOOP -> "工具循环"
    ProfilePhase.TYPE_DOWNLOAD_THROUGHPUT -> "下载"
    ProfilePhase.TYPE_UPLOAD_THROUGHPUT -> "上传"
    else -> type
}
