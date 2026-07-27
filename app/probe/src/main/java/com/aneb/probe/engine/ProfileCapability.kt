package com.aneb.probe.engine

import kotlin.math.abs

/**
 * Profile 与已安装引擎能力的显式对账。
 *
 * 合同完整和引擎可执行是两件事：Profile v2 即使通过合同校验，也只有在对应 mode/phase
 * 已接入运行时后才会显示“可执行”，避免目录把候选 Profile 冒充成可测能力。
 */
object ProfileCapability {
    private const val NETWORK_QUICK_RUNTIME_CONTRACT = "aneb-network-runtime-plan-v1"
    private const val NETWORK_QUICK_RUNTIME_ARTIFACT = "runtime_plan.json"
    private const val NETWORK_QUICK_RUNTIME_HASH =
        "sha256:8981267030abd4cd95dabe3e3bff8d2af4b7de6b8659cc8c267c97f519cf2603"
    private const val NETWORK_QUICK_RUNTIME_SEED = 20260727L

    data class Assessment(
        val executable: Boolean,
        val unsupportedPhaseTypes: Set<String>,
        val contractIssues: List<String>,
    )

    private val tokenPhases = setOf(
        ProfilePhase.TYPE_CLOCK_SYNC,
        ProfilePhase.TYPE_UPLOAD_BURST,
        ProfilePhase.TYPE_DOWNLOAD_BURST,
        ProfilePhase.TYPE_THINK_PAUSE,
        ProfilePhase.TYPE_TOKEN_STREAM,
        ProfilePhase.TYPE_TOOL_LOOP,
    )
    private val basicPhases = setOf(
        ProfilePhase.TYPE_CLOCK_SYNC,
        ProfilePhase.TYPE_DOWNLOAD_THROUGHPUT,
        ProfilePhase.TYPE_UPLOAD_THROUGHPUT,
    )
    private val tokenSimulationPhases = setOf(ProfilePhase.TYPE_BEHAVIOR_TRACE)
    private val realtimeSimulationPhases = setOf(ProfilePhase.TYPE_BEHAVIOR_TRACE)
    private val networkComprehensivePhases = setOf(
        ProfilePhase.TYPE_PATH_SETUP,
        ProfilePhase.TYPE_IDLE_LATENCY,
        ProfilePhase.TYPE_DOWNLOAD_LOADED,
        ProfilePhase.TYPE_UPLOAD_LOADED,
        ProfilePhase.TYPE_UDP_SEQUENCE,
        ProfilePhase.TYPE_CONTROLLED_OUTAGE_RECOVERY,
        ProfilePhase.TYPE_POST_LOAD_LATENCY,
    )
    private val tokenRequiredFormulaIds = setOf(
        "tok_b01-v1",
        "tok_b02-v1",
        "ttft-excess-v1",
        "token-on-time-200ms-v1",
        "itl-residual-over-200ms-v1",
        "itl-residual-over-1000ms-v1",
        "unique-seq-completeness-v1",
        "sim-token-retry-overhead-v1",
        "tok_n03-v1",
        "tok_n04-v1",
        "tok_n05-v1",
        "tok_n06-v1",
        "tok_n07-v1",
        "tok_n08-v1",
        "tok_n09-v1",
    )
    private val realtimeRequiredFormulaIds = setOf(
        "live_b01-v1",
        "live_b02-v1",
        "realtime-response-excess-v1",
        "live_b05-v1",
        "live_b06-v1",
        "live_b07-v1",
        "live_b08-v1",
        "live_b09-v1",
        "live_b10-v1",
        "live_b11-v1",
        "live_n01-v1",
        "live_n02-v1",
        "live_n03-v1",
        "live_n04-v1",
    )
    private val networkRequiredFormulaIds = setOf(
        "window-goodput-p05-v1",
        "echo-rtt-v1",
        "loaded-echo-rtt-v1",
        "loaded-p95-minus-idle-p50-v1",
        "rtt-p95-minus-p50-v1",
        "goodput-robust-cv-v1",
        "application-request-failure-ratio-v1",
        "sequenced-udp-nonreturn-ratio-v1",
        "connection-stage-timing-v1",
        "controlled-outage-observed-v1",
        "trigger-ack-to-first-success-v1",
        "post-recovery-request-success-ratio-v1",
        "post-recovery-echo-rtt-v1",
        "gateway-ip-outage-observed-v1",
        "gateway-active-ack-to-first-echo-complete-v1",
        "post-gateway-recovery-request-success-ratio-v1",
        "post-gateway-recovery-echo-rtt-v1",
    )
    private val measurementLevels = setOf("exact", "derived", "proxy", "unsupported")
    private val calibrationStates = setOf("hypothesis", "calibrated", "validated", "retired", "not_applicable")

    fun assess(profile: ScenarioProfile): Assessment {
        val supported = when (profile.modeId) {
            ScenarioProfile.MODE_TOKEN_EXPERIENCE -> tokenPhases
            ScenarioProfile.MODE_NETWORK_BASIC -> basicPhases
            ScenarioProfile.MODE_TOKEN_SIMULATION -> tokenSimulationPhases
            ScenarioProfile.MODE_AI_REALTIME_SIMULATION -> realtimeSimulationPhases
            ScenarioProfile.MODE_NETWORK_COMPREHENSIVE -> networkComprehensivePhases
            // v2 引擎逐类接入；未接入前必须保持不可执行。
            else -> emptySet()
        }
        val unsupported = profile.phases.map { it.type }.filter { it !in supported }.toSet()
        val issues = if (profile.contractVersion == ScenarioProfile.CONTRACT_V2) {
            assessV2(profile)
        } else {
            assessLegacy(profile)
        }
        return Assessment(
            executable = supported.isNotEmpty() && unsupported.isEmpty() && issues.isEmpty(),
            unsupportedPhaseTypes = unsupported,
            contractIssues = issues,
        )
    }

    private fun assessLegacy(profile: ScenarioProfile): List<String> = buildList {
        if (profile.description.isBlank()) add("缺少业务说明")
        if (profile.phases.isEmpty()) add("没有测试阶段")
        if (profile.presentation.liveMetricId.isBlank()) add("缺少实时主指标")
        if (profile.presentation.metricIds.isEmpty()) add("缺少输出指标清单")
        if (profile.presentation.conclusionPolicyId.isBlank()) add("缺少结论策略")
        if (profile.presentation.liveWindowMs <= 0) add("实时窗口无效")
        if (profile.presentation.uiRefreshMs <= 0) add("刷新频率无效")
    }

    private fun assessV2(profile: ScenarioProfile): List<String> = buildList {
        if (profile.executionTarget != "aneb_probe_simulator") add("执行目标不是 ANEB 自建仿真节点")
        if (profile.claimScope.isBlank()) add("缺少结论适用范围")
        if (profile.business.categoryId.isBlank() || profile.business.label.isBlank()) add("缺少业务类型")
        if (profile.business.behaviorFeatureIds.isEmpty()) add("缺少业务行为特征")
        if (profile.business.calibrationStatus !in calibrationStates) add("模型校准状态无效")
        if (profile.business.calibrationStatus == "not_applicable") {
            if (
                !profile.business.behaviorModelId.isNullOrBlank() ||
                !profile.business.behaviorModelVersion.isNullOrBlank() ||
                !profile.business.behaviorModelHash.isNullOrBlank() ||
                !profile.business.modelSourceKind.isNullOrBlank()
            ) {
                add("无需行为模型的业务不得声明模型元数据")
            }
        } else {
            if (profile.business.behaviorModelId.isNullOrBlank()) add("缺少行为模型来源")
            if (profile.business.behaviorModelHash.isNullOrBlank()) add("缺少行为模型哈希")
        }
        if (profile.phases.isEmpty()) add("没有测试阶段")
        if (profile.measurements.isEmpty()) add("缺少全量测量指标")

        val metricIds = profile.measurements.map { it.metricId }
        if (metricIds.any { it.isBlank() }) add("存在空指标 ID")
        if (metricIds.distinct().size != metricIds.size) add("指标 ID 重复")
        profile.measurements.forEach { metric ->
            if (metric.label.isBlank()) add("${metric.metricId} 缺少指标名称")
            if (metric.measurementLevel !in measurementLevels) add("${metric.metricId} 可度量等级无效")
            if (metric.formulaId.isBlank()) add("${metric.metricId} 缺少公式版本")
            if (metric.minimumSampleCount <= 0) add("${metric.metricId} 最小样本量无效")
            if (metric.requiredForScore && metric.qualityTarget == null) add("${metric.metricId} 必需指标缺少质量目标")
            metric.qualityTarget?.let { target ->
                if (target.operator.isBlank()) add("${metric.metricId} 质量目标缺少运算符")
                if (target.value == null && target.values.isEmpty() && target.policyId.isNullOrBlank()) {
                    add("${metric.metricId} 质量目标缺少门限或策略")
                }
                target.requiredComplianceRatio?.let { ratio ->
                    if (ratio !in 0.0..1.0) add("${metric.metricId} 达标比例无效")
                }
            }
        }

        val required = profile.evaluation.requiredMetricIds
        if (profile.measurements.filter { it.requiredForScore }.map { it.metricId }.toSet() != required.toSet()) {
            add("必需指标声明不一致")
        }
        if (profile.evaluation.guardrailMetricIds.any { it !in required }) add("门控指标不在必需指标中")
        if (profile.evaluation.targetSetId.isBlank()) add("缺少目标集版本")
        if (profile.evaluation.scorePolicyId.isBlank()) add("缺少评分策略")
        if (profile.modeId == ScenarioProfile.MODE_TOKEN_SIMULATION) {
            val stress = profile.evidenceTier == "stress"
            val expectedScorePolicy = if (stress) "token-stress-score-v1" else "token-sim-score-v1"
            val expectedConclusionPolicy = if (stress) "token-stress-conclusions-v2" else "token-sim-conclusions-v2"
            if (profile.evaluation.scorePolicyId != expectedScorePolicy) add("Token 评分策略未被当前引擎识别")
            if (profile.evaluation.scoreAnchorPolicyId != "compliance-anchors-v1") add("Token 评分锚点策略未被当前引擎识别")
            if (profile.evaluation.conclusionPolicyId != expectedConclusionPolicy) add("Token 结论策略未被当前引擎识别")
            val requiredFormulaIds = profile.measurements
                .filter { it.requiredForScore }
                .map { it.formulaId }
                .toSet()
            val unknown = requiredFormulaIds - tokenRequiredFormulaIds
            if (unknown.isNotEmpty()) add("Token 必需指标公式未被识别: ${unknown.sorted().joinToString()}")
            val execution = profile.executionPlan
            if (profile.evidenceTier !in setOf("quick", "standard", "stress")) add("Token 证据等级无效")
            if (execution == null) {
                add("Token 缺少可执行计划")
            } else {
                if (execution.contractVersion != "aneb-token-runtime-plan-v1") add("Token 执行计划合同不受支持")
                if (execution.artifact != "runtime_plan.json") add("Token 执行计划文件名不受支持")
                if (!execution.artifactHash.startsWith("sha256:")) add("Token 执行计划缺少哈希")
                if (execution.seed == 0L) add("Token 执行计划缺少 seed")
                if (execution.variant != profile.evidenceTier) add("Token 执行计划与证据等级不一致")
            }
        }
        if (profile.modeId == ScenarioProfile.MODE_AI_REALTIME_SIMULATION) {
            val realtimePolicies = mapOf(
                "realtime-interaction-score-v1" to "realtime-interaction-conclusions-v2",
                "realtime-recovery-score-v2" to "realtime-recovery-conclusions-v3",
            )
            if (profile.evaluation.scorePolicyId !in realtimePolicies) add("实时交互评分策略未被当前引擎识别")
            if (profile.evaluation.scoreAnchorPolicyId != "compliance-anchors-v1") add("实时交互评分锚点策略未被当前引擎识别")
            if (profile.evaluation.conclusionPolicyId != realtimePolicies[profile.evaluation.scorePolicyId]) add("实时交互结论策略未被当前引擎识别")
            val requiredFormulaIds = profile.measurements
                .filter { it.requiredForScore }
                .map { it.formulaId }
                .toSet()
            val unknown = requiredFormulaIds - realtimeRequiredFormulaIds
            if (unknown.isNotEmpty()) add("实时交互必需指标公式未被识别: ${unknown.sorted().joinToString()}")
            val execution = profile.executionPlan
            if (profile.evidenceTier !in setOf("quick", "standard", "recovery")) add("实时交互证据等级无效")
            if (execution == null) {
                add("实时交互缺少可执行计划")
            } else {
                if (execution.contractVersion != "aneb-realtime-runtime-plan-v1") add("实时交互执行计划合同不受支持")
                if (execution.artifact != "runtime_plan.json") add("实时交互执行计划文件名不受支持")
                if (!execution.artifactHash.startsWith("sha256:")) add("实时交互执行计划缺少哈希")
                if (execution.seed == 0L) add("实时交互执行计划缺少 seed")
                if (execution.variant != profile.evidenceTier) add("实时交互执行计划与证据等级不一致")
            }
        }
        if (profile.modeId == ScenarioProfile.MODE_NETWORK_COMPREHENSIVE) {
            val syntheticRecovery = profile.profileId == "network_comprehensive_weak_recovery"
            val gatewayLoss = profile.profileId == "network_comprehensive_gateway_loss"
            val gatewayRecovery = profile.profileId == "network_comprehensive_gateway_recovery"
            val recoveryProfile = syntheticRecovery || gatewayRecovery
            val expectedScorePolicy = when {
                gatewayRecovery -> "network-gateway-recovery-score-v2"
                gatewayLoss -> "network-gateway-score-v1"
                syntheticRecovery -> "network-recovery-score-v1"
                else -> "network-comprehensive-score-v1"
            }
            val expectedConclusionPolicy = when {
                gatewayRecovery -> "network-gateway-recovery-conclusions-v3"
                gatewayLoss -> "network-gateway-conclusions-v2"
                syntheticRecovery -> "network-recovery-conclusions-v2"
                else -> "network-comprehensive-conclusions-v2"
            }
            if (profile.evaluation.scorePolicyId != expectedScorePolicy) add("网络综合评分策略未被当前引擎识别")
            if (profile.evaluation.scoreAnchorPolicyId != "compliance-anchors-v1") add("网络综合评分锚点策略未被当前引擎识别")
            if (profile.evaluation.conclusionPolicyId != expectedConclusionPolicy) add("网络综合结论策略未被当前引擎识别")
            val requiredFormulaIds = profile.measurements
                .filter { it.requiredForScore }
                .map { it.formulaId }
                .toSet()
            val unknown = requiredFormulaIds - networkRequiredFormulaIds
            if (unknown.isNotEmpty()) add("网络综合必需指标公式未被识别: ${unknown.sorted().joinToString()}")
            if (profile.evidenceTier !in setOf("quick", "standard", "recovery", "gateway_lab")) add("网络综合证据等级无效")
            val execution = profile.executionPlan
            if (profile.profileId == "network_comprehensive_quick") {
                if (execution == null) {
                    add("网络综合 Quick 缺少可执行计划")
                } else {
                    if (execution.contractVersion != NETWORK_QUICK_RUNTIME_CONTRACT) add("网络综合执行计划合同不受支持")
                    if (execution.artifact != NETWORK_QUICK_RUNTIME_ARTIFACT) add("网络综合执行计划文件名不受支持")
                    if (execution.artifactHash != NETWORK_QUICK_RUNTIME_HASH) add("网络综合执行计划哈希不受支持")
                    if (execution.seed != NETWORK_QUICK_RUNTIME_SEED) add("网络综合执行计划 seed 不受支持")
                    if (execution.variant != "quick") add("网络综合执行计划与证据等级不一致")
                }
            } else if (execution != null) {
                add("Legacy 网络综合测试不得声明独立执行计划")
            }
            val phaseTypes = profile.phases.map { it.type }
            val standardPhases = listOf(
                ProfilePhase.TYPE_PATH_SETUP, ProfilePhase.TYPE_IDLE_LATENCY,
                ProfilePhase.TYPE_DOWNLOAD_LOADED, ProfilePhase.TYPE_UPLOAD_LOADED,
                ProfilePhase.TYPE_UDP_SEQUENCE, ProfilePhase.TYPE_POST_LOAD_LATENCY,
            )
            val recoveryPhases = standardPhases.toMutableList().apply {
                add(lastIndex, ProfilePhase.TYPE_CONTROLLED_OUTAGE_RECOVERY)
            }
            if (phaseTypes != if (recoveryProfile) recoveryPhases else standardPhases) add("网络综合阶段顺序或集合不受支持")
            profile.syntheticImpairment?.let { impairment ->
                if (profile.profileId !in setOf("network_comprehensive_weak_capacity_latency", "network_comprehensive_weak_recovery")) add("合成弱网 Profile ID 不受支持")
                if (impairment.contractVersion != "aneb-synthetic-impairment-v1") add("合成弱网合同版本不受支持")
                val expectedRoute = if (syntheticRecovery) "weak-recovery-v1" else "weak-capacity-latency-v1"
                if (impairment.routeId != expectedRoute) add("合成弱网路由不受支持")
                if (impairment.seed == 0L) add("合成弱网缺少 seed")
                if (impairment.downlinkMbps <= 0.0 || impairment.uplinkMbps <= 0.0) add("合成弱网容量门限无效")
                if (impairment.addedRttMs < 0 || impairment.jitterMs < 0) add("合成弱网时延参数无效")
                val requiredApplies = setOf("http_request_delay", "http_request_body", "http_response_body")
                if (!impairment.appliesTo.containsAll(requiredApplies)) add("合成弱网适用范围不完整")
                val requiredExclusions = setOf("dns", "tcp", "tls", "udp", "radio_rsrp", "radio_sinr")
                if (!impairment.excludedFromShaping.containsAll(requiredExclusions)) add("合成弱网排除项不完整")
                if (syntheticRecovery) {
                    if (impairment.outageDurationMs != 2_000) add("恢复测试必须声明 2 秒应用请求不可用窗口")
                    if ("application_request_availability_window" !in impairment.appliesTo) add("恢复测试缺少应用请求不可用范围")
                    if (!impairment.excludedFromShaping.containsAll(setOf("ip_packet_loss", "route_change"))) add("恢复测试排除项不完整")
                } else if (impairment.outageDurationMs != 0) {
                    add("容量与时延弱网不得声明中断窗口")
                }
                val upload = profile.phases.firstOrNull { it.type == ProfilePhase.TYPE_UPLOAD_LOADED }
                if (upload?.bytes != 131_072L || upload.chunkKb != 64 || upload.parallel != 2) {
                    add("合成弱网上传必须使用服务器确认的 128KiB 双连接分块")
                }
            }
            if (profile.profileId.contains("_weak_") && profile.syntheticImpairment == null) add("弱网 Profile 缺少合成整形合同")
            profile.gatewayImpairment?.let { gateway ->
                if (!gatewayLoss && !gatewayRecovery) add("网关弱网 Profile ID 不受支持")
                if (gateway.contractVersion != "aneb-gateway-binding-v1") add("网关绑定合同版本不受支持")
                if (gateway.impairmentLayer != "ip_forwarding") add("网关实验必须声明 IP 转发层")
                val expectedRef = if (gatewayRecovery) "ip_outage_recovery@1.0.0" else "ip_loss_latency@1.0.0"
                val expectedFingerprint = if (gatewayRecovery) {
                    "208f2acdd13e15b799e1f5e27e0cad525c8750f6355f66eb7ea9ad78a87673d8"
                } else {
                    "91bd6b105606ea2dd4db7a79486ca20892b2b1770239fbe247dbf51be52d7984"
                }
                if (gateway.profileRef != expectedRef) add("网关 Profile 引用不受支持")
                if (gateway.profileFingerprint != expectedFingerprint) add("网关 Profile 指纹不匹配")
                if (gateway.activationDelayMs != 500) add("网关激活等待参数不匹配")
                if (gatewayRecovery) {
                    if (gateway.kind != "outage" || gateway.durationMs != 2_000) add("网关恢复实验必须绑定 2 秒网络层中断")
                    if (gateway.uplink.lossPct != 100.0 || gateway.downlink.lossPct != 100.0) add("网关恢复实验必须双向 100% 丢包")
                    if (
                        gateway.uplink.rateMbps != 0.0 || gateway.downlink.rateMbps != 0.0 ||
                        gateway.uplink.delayMs != 0 || gateway.downlink.delayMs != 0 ||
                        gateway.uplink.jitterMs != 0 || gateway.downlink.jitterMs != 0
                    ) add("网关恢复实验包含未声明的附加整形")
                } else {
                    if (gateway.kind != "continuous" || gateway.durationMs != 60_000) add("网关容量实验持续时间不匹配")
                    if (gateway.uplink.rateMbps != 2.0 || gateway.downlink.rateMbps != 5.0) add("网关容量实验速率参数不匹配")
                    if (gateway.uplink.delayMs != 50 || gateway.downlink.delayMs != 50) add("网关容量实验时延参数不匹配")
                    if (gateway.uplink.jitterMs != 10 || gateway.downlink.jitterMs != 10) add("网关容量实验抖动参数不匹配")
                    if (gateway.uplink.lossPct != 1.0 || gateway.downlink.lossPct != 1.0) add("网关容量实验丢包参数不匹配")
                }
                val exclusions = setOf("radio_rsrp", "radio_rsrq", "radio_sinr", "base_station_scheduler", "actual_route_change")
                if (gateway.excludedFromImpairment.toSet() != exclusions) add("网关实验排除项与白名单不一致")
            }
            if ((gatewayLoss || gatewayRecovery) && profile.gatewayImpairment == null) add("网关 Profile 缺少网络层绑定合同")
            if (profile.gatewayImpairment != null && profile.syntheticImpairment != null) add("同一 Profile 不得叠加两种弱网机制")
        }
        if (profile.evaluation.conclusionPolicyId.isBlank()) add("缺少结论策略")
        if (profile.evaluation.missingRequiredMetric != "score_null") add("必需指标缺失策略必须为 score_null")
        if (profile.evaluation.invalidRun != "retain_raw_suppress_score") add("无效 run 策略不符合 fail-closed")
        val weightSum = profile.evaluation.groupWeights.values.sum()
        if (profile.evaluation.groupWeights.isEmpty() || abs(weightSum - 1.0) > 0.000_001) add("评分组权重之和必须为 1")

        if (profile.livePresentation.primaryMetricId.isBlank()) add("缺少动态主指标")
        if (profile.livePresentation.secondaryMetricIds.isEmpty()) add("缺少动态辅助指标")
        if (profile.livePresentation.windowMs <= 0 || profile.livePresentation.uiRefreshMs <= 0) add("动态刷新窗口无效")
        if (profile.livePresentation.staleAfterMs < profile.livePresentation.uiRefreshMs) add("实时指标过期窗口小于刷新周期")
        if (profile.livePresentation.missingBehavior != "show_unavailable_never_zero") add("缺失值策略必须为 show_unavailable_never_zero")

        profile.phases.filter { it.type == ProfilePhase.TYPE_BEHAVIOR_TRACE }.forEach { phase ->
            if (profile.business.calibrationStatus == "not_applicable") add("无需行为模型的业务不得包含行为轨迹阶段")
            if (phase.modelId != profile.business.behaviorModelId) add("阶段模型 ID 与业务声明不一致")
            if (phase.modelHash != profile.business.behaviorModelHash) add("阶段模型哈希与业务声明不一致")
            if (phase.seed == 0L) add("行为轨迹缺少确定性 seed")
            profile.executionPlan?.let { execution ->
                if (phase.runtimeArtifact != execution.artifact) add("阶段执行文件与 Profile 不一致")
                if (phase.runtimeArtifactHash != execution.artifactHash) add("阶段执行哈希与 Profile 不一致")
                if (phase.seed != execution.seed) add("阶段 seed 与执行计划不一致")
            }
        }
    }
}
