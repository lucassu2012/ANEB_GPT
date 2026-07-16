package com.aneb.probe.engine

import kotlin.math.abs

/**
 * Profile 与已安装引擎能力的显式对账。
 *
 * 合同完整和引擎可执行是两件事：Profile v2 即使通过合同校验，也只有在对应 mode/phase
 * 已接入运行时后才会显示“可执行”，避免目录把候选 Profile 冒充成可测能力。
 */
object ProfileCapability {
    data class Assessment(
        val executable: Boolean,
        val unsupportedPhaseTypes: Set<String>,
        val contractIssues: List<String>,
    )

    private val tokenPhases = setOf(
        ProfilePhase.TYPE_CLOCK_SYNC,
        ProfilePhase.TYPE_UPLOAD_BURST,
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
    )
    private val measurementLevels = setOf("exact", "derived", "proxy", "unsupported")
    private val calibrationStates = setOf("hypothesis", "calibrated", "validated", "retired", "not_applicable")

    fun assess(profile: ScenarioProfile): Assessment {
        val supported = when (profile.modeId) {
            ScenarioProfile.MODE_TOKEN_EXPERIENCE -> tokenPhases
            ScenarioProfile.MODE_NETWORK_BASIC -> basicPhases
            ScenarioProfile.MODE_TOKEN_SIMULATION -> tokenSimulationPhases
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
            if (profile.evaluation.scorePolicyId != "token-sim-score-v1") add("Token 评分策略未被当前引擎识别")
            if (profile.evaluation.scoreAnchorPolicyId != "compliance-anchors-v1") add("Token 评分锚点策略未被当前引擎识别")
            if (profile.evaluation.conclusionPolicyId != "token-sim-conclusions-v1") add("Token 结论策略未被当前引擎识别")
            val requiredFormulaIds = profile.measurements
                .filter { it.requiredForScore }
                .map { it.formulaId }
                .toSet()
            val unknown = requiredFormulaIds - tokenRequiredFormulaIds
            if (unknown.isNotEmpty()) add("Token 必需指标公式未被识别: ${unknown.sorted().joinToString()}")
            val execution = profile.executionPlan
            if (profile.evidenceTier !in setOf("quick", "standard")) add("Token 证据等级无效")
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
