package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfileContractV2Test {
    private fun v2Profile(
        mode: String = ScenarioProfile.MODE_TOKEN_SIMULATION,
        executionTarget: String = "aneb_probe_simulator",
        claimScope: String = "application_end_to_end_to_probe_node",
        phase: ProfilePhase = ProfilePhase(ProfilePhase.TYPE_BEHAVIOR_TRACE, seed = 42, modelId = "model", modelHash = "hash"),
        requiredMetricIds: List<String> = listOf("SIM_TPS_LIVE"),
        missingBehavior: String = "show_unavailable_never_zero",
    ) = ScenarioProfile(
        profileId = "token_standard",
        version = "1.0.0",
        contractVersion = ScenarioProfile.CONTRACT_V2,
        modeId = mode,
        executionTarget = executionTarget,
        claimScope = claimScope,
        business = ProfileBusiness(
            categoryId = "token_simulation",
            label = "Token 仿真",
            archetypeLabels = listOf("Kimi-style"),
            behaviorFeatureIds = listOf("streaming_tokens"),
            behaviorModelId = "model",
            behaviorModelVersion = "1.0.0",
            behaviorModelHash = "hash",
            calibrationStatus = "hypothesis",
            modelSourceKind = "explicit_assumption",
        ),
        measurements = listOf(
            ProfileMeasurement(
                metricId = "SIM_TPS_LIVE",
                label = "AI 流式到达速率",
                domain = "token",
                unit = "events/s",
                measurementLevel = "proxy",
                sourceEventIds = listOf("TOKEN_ARRIVAL"),
                formulaId = "sim-tps-live-v1",
                aggregation = "window_1s",
                direction = "higher_is_better",
                requiredForScore = true,
                minimumSampleCount = 20,
                targetRole = "quality",
                qualityTarget = ProfileQualityTarget(
                    operator = "gte",
                    value = 20.0,
                    requiredComplianceRatio = 0.95,
                    provenance = "aneb_product_provisional_v1",
                ),
            ),
        ),
        livePresentation = ProfileLivePresentation(
            primaryMetricId = "SIM_TPS_LIVE",
            secondaryMetricIds = listOf("SIM_STALL_RATIO"),
            windowMs = 1_000,
            uiRefreshMs = 250,
            staleAfterMs = 1_500,
            missingBehavior = missingBehavior,
        ),
        evaluation = ProfileEvaluation(
            targetSetId = "token-targets-v1",
            scorePolicyId = "token-score-v1",
            conclusionPolicyId = "token-conclusions-v1",
            requiredMetricIds = requiredMetricIds,
            guardrailMetricIds = requiredMetricIds,
            groupWeights = mapOf("responsiveness" to 1.0),
            gradeBands = mapOf("excellent" to 85.0, "good" to 70.0),
            missingRequiredMetric = "score_null",
            invalidRun = "retain_raw_suppress_score",
        ),
        phases = listOf(phase),
    )

    @Test
    fun `parses schema v2 draft nullable not-applicable behavior model`() {
        val body = listOf(
            File("../profiles/drafts/network_comprehensive_standard.json"),
            File("../../profiles/drafts/network_comprehensive_standard.json"),
            File("profiles/drafts/network_comprehensive_standard.json"),
        ).first { it.isFile }.readText()
        val profile = ProfileParser.parseSingle(body)

        assertEquals(ScenarioProfile.CONTRACT_V2, profile.contractVersion)
        assertEquals(ScenarioProfile.MODE_NETWORK_COMPREHENSIVE, profile.modeId)
        assertEquals("not_applicable", profile.business.calibrationStatus)
        assertEquals(null, profile.business.behaviorModelId)
        assertEquals("show_unavailable_never_zero", profile.livePresentation.missingBehavior)

        val assessment = ProfileCapability.assess(profile)
        assertFalse(assessment.executable)
        assertTrue("network comprehensive engine is intentionally not wired yet", assessment.unsupportedPhaseTypes.isNotEmpty())
        assertTrue("schema-compatible draft should not fail contract validation: ${assessment.contractIssues}", assessment.contractIssues.isEmpty())
    }

    @Test
    fun `v2 validation fails closed on third-party target and zero-like missing behavior`() {
        val assessment = ProfileCapability.assess(
            v2Profile(
                executionTarget = "third_party_llm_api",
                claimScope = "application_end_to_end_to_llm_api",
                missingBehavior = "show_zero",
            ),
        )

        assertFalse(assessment.executable)
        assertTrue(assessment.contractIssues.contains("执行目标不是 ANEB 自建仿真节点"))
        assertTrue(assessment.contractIssues.contains("结论适用范围必须限定为自建节点应用层路径"))
        assertTrue(assessment.contractIssues.contains("缺失值策略必须为 show_unavailable_never_zero"))
    }

    @Test
    fun `v2 validation fails closed when required metric declaration diverges`() {
        val assessment = ProfileCapability.assess(v2Profile(requiredMetricIds = emptyList()))

        assertFalse(assessment.executable)
        assertTrue(assessment.contractIssues.contains("必需指标声明不一致"))
    }
}
