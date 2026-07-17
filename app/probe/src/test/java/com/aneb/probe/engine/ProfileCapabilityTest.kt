package com.aneb.probe.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCapabilityTest {
    private val validV2Json = """{
      "contract_version":"aneb-profile-v2",
      "profile_id":"token_standard",
      "version":"1.0.0-draft",
      "mode_id":"token_simulation",
      "execution_target":"aneb_probe_simulator",
      "claim_scope":"application_end_to_end_to_probe_node",
      "business":{
        "category_id":"token_multimodal",
        "label":"Token 多模态",
        "behavior_feature_ids":["uplink_burst","streaming_downlink"],
        "behavior_model_id":"token-model-v1",
        "behavior_model_version":"0.1.0",
        "behavior_model_hash":"sha256:test",
        "calibration_status":"hypothesis",
        "model_source_kind":"aneb_behavior_model"
      },
      "measurements":[{
        "metric_id":"TOK-B01","label":"首 Token 时延","domain":"business","unit":"ms",
        "measurement_level":"exact","formula_id":"tok_b01-v1","aggregation":"p95",
        "direction":"lower_is_better","required_for_score":true,"minimum_sample_count":3,
        "target_role":"quality","quality_target":{"operator":"lte","value":2000,
        "required_compliance_ratio":0.95,"provenance":"aneb_product_provisional_v1"}
      }],
      "live_presentation":{"primary_metric_id":"TOKENS_PER_SECOND_LIVE",
        "secondary_metric_ids":["RTT_LIVE"],"window_ms":1000,"ui_refresh_ms":250,
        "stale_after_ms":1500,"missing_behavior":"show_unavailable_never_zero"},
      "evaluation":{"target_set_id":"token-targets-v1","score_policy_id":"token-sim-score-v1",
        "score_anchor_policy_id":"compliance-anchors-v1",
        "conclusion_policy_id":"token-sim-conclusions-v1","required_metric_ids":["TOK-B01"],
        "guardrail_metric_ids":[],"group_weights":{"responsiveness":1.0},
        "missing_required_metric":"score_null","invalid_run":"retain_raw_suppress_score"},
      "evidence_tier":"quick",
      "execution_plan":{"contract_version":"aneb-token-runtime-plan-v1","artifact":"runtime_plan.json",
        "artifact_hash":"sha256:test-plan","seed":20260716,"variant":"quick"},
      "phases":[{"type":"behavior_trace","model_id":"token-model-v1",
        "model_version":"0.1.0","model_hash":"sha256:test","seed":20260716,
        "runtime_artifact":"runtime_plan.json","runtime_artifact_hash":"sha256:test-plan"}]
    }"""

    private fun completeProfile(mode: String, phases: List<ProfilePhase>) = ScenarioProfile(
        profileId = "test",
        version = "1.0.0",
        modeId = mode,
        description = "测试业务",
        presentation = ProfilePresentation(
            liveMetricId = "rate",
            liveMetricLabel = "实时速率",
            liveMetricUnit = "Mbps",
            metricIds = listOf("rate"),
            conclusionPolicyId = "policy-v1",
        ),
        phases = phases,
    )

    @Test
    fun `known basic profile is executable`() {
        val result = ProfileCapability.assess(
            completeProfile(
                ScenarioProfile.MODE_NETWORK_BASIC,
                listOf(
                    ProfilePhase(ProfilePhase.TYPE_CLOCK_SYNC),
                    ProfilePhase(ProfilePhase.TYPE_DOWNLOAD_THROUGHPUT),
                    ProfilePhase(ProfilePhase.TYPE_UPLOAD_THROUGHPUT),
                ),
            ),
        )
        assertTrue(result.executable)
        assertTrue(result.unsupportedPhaseTypes.isEmpty())
    }

    @Test
    fun `legacy multimodal download burst is executable`() {
        val result = ProfileCapability.assess(
            completeProfile(
                ScenarioProfile.MODE_TOKEN_EXPERIENCE,
                listOf(
                    ProfilePhase(ProfilePhase.TYPE_TOKEN_STREAM, tokens = 200),
                    ProfilePhase(ProfilePhase.TYPE_DOWNLOAD_BURST, bytes = 12_582_912, chunkKb = 256),
                ),
            ),
        )

        assertTrue(result.executable)
        assertTrue(result.unsupportedPhaseTypes.isEmpty())
    }

    @Test
    fun `unknown phase requires an engine plugin`() {
        val result = ProfileCapability.assess(
            completeProfile(
                ScenarioProfile.MODE_TOKEN_EXPERIENCE,
                listOf(ProfilePhase("future_transport_probe")),
            ),
        )
        assertFalse(result.executable)
        assertTrue("future_transport_probe" in result.unsupportedPhaseTypes)
    }

    @Test
    fun `incomplete presentation is not executable`() {
        val result = ProfileCapability.assess(
            ScenarioProfile(
                profileId = "incomplete",
                version = "1.0.0",
                description = "测试业务",
                phases = listOf(ProfilePhase(ProfilePhase.TYPE_TOKEN_STREAM)),
            ),
        )
        assertFalse(result.executable)
        assertTrue(result.contractIssues.isNotEmpty())
    }

    @Test
    fun `approved token v2 fields are executable when engine contract matches`() {
        val profile = ProfileParser.parseSingle(validV2Json)
        val result = ProfileCapability.assess(profile)

        assertEquals(ScenarioProfile.CONTRACT_V2, profile.contractVersion)
        assertEquals("token-model-v1", profile.business.behaviorModelId)
        assertEquals("TOKENS_PER_SECOND_LIVE", profile.livePresentation.primaryMetricId)
        assertTrue(result.contractIssues.isEmpty())
        assertTrue(result.executable)
        assertTrue(result.unsupportedPhaseTypes.isEmpty())
    }

    @Test
    fun `v2 required metric drift fails closed`() {
        val profile = ProfileParser.parseSingle(validV2Json)
        val drifted = profile.copy(
            evaluation = profile.evaluation.copy(requiredMetricIds = emptyList()),
        )

        val result = ProfileCapability.assess(drifted)
        assertFalse(result.executable)
        assertTrue(result.contractIssues.any { it.contains("必需指标声明不一致") })
    }

    @Test
    fun `v2 missing value behavior drift fails closed`() {
        val profile = ProfileParser.parseSingle(validV2Json)
        val drifted = profile.copy(
            livePresentation = profile.livePresentation.copy(missingBehavior = "coerce_zero"),
        )

        val result = ProfileCapability.assess(drifted)
        assertFalse(result.executable)
        assertTrue(result.contractIssues.any { it.contains("show_unavailable_never_zero") })
    }

    @Test
    fun `v2 required metric without quality target fails closed`() {
        val profile = ProfileParser.parseSingle(validV2Json)
        val drifted = profile.copy(
            measurements = profile.measurements.map { it.copy(qualityTarget = null) },
        )

        val result = ProfileCapability.assess(drifted)
        assertFalse(result.executable)
        assertTrue(result.contractIssues.any { it.contains("必需指标缺少质量目标") })
    }

    @Test
    fun `v2 quality target may delegate threshold to versioned policy`() {
        val profile = ProfileParser.parseSingle(validV2Json)
        val delegated = profile.copy(
            measurements = profile.measurements.map { metric ->
                metric.copy(
                    qualityTarget = ProfileQualityTarget(
                        operator = "deadline_by_artifact_size",
                        policyId = "artifact-deadlines-v1",
                    ),
                )
            },
        )

        val result = ProfileCapability.assess(delegated)
        assertTrue(result.contractIssues.isEmpty())
        assertTrue(result.executable)
    }

    @Test
    fun `network comprehensive accepts no behavior model and rejects mismatched policy`() {
        val network = ProfileParser.parseSingle(
            validV2Json
                .replace("\"profile_id\":\"token_standard\"", "\"profile_id\":\"network_standard\"")
                .replace("\"mode_id\":\"token_simulation\"", "\"mode_id\":\"network_comprehensive\"")
                .replace(
                    "\"behavior_model_id\":\"token-model-v1\",\n        \"behavior_model_version\":\"0.1.0\",\n        \"behavior_model_hash\":\"sha256:test\",\n        \"calibration_status\":\"hypothesis\",\n        \"model_source_kind\":\"aneb_behavior_model\"",
                    "\"behavior_model_id\":null,\n        \"behavior_model_version\":null,\n        \"behavior_model_hash\":null,\n        \"calibration_status\":\"not_applicable\",\n        \"model_source_kind\":null",
                )
        ).copy(
            phases = listOf(ProfilePhase("path_setup")),
            evidenceTier = "",
            executionPlan = null,
        )

        val result = ProfileCapability.assess(network)
        assertNull(network.business.behaviorModelId)
        assertEquals("not_applicable", network.business.calibrationStatus)
        assertTrue(result.contractIssues.none { it.contains("行为模型") })
        assertTrue(result.contractIssues.isNotEmpty())
        assertFalse(result.executable)
        assertFalse("path_setup" in result.unsupportedPhaseTypes)
    }

    @Test
    fun `published weak network profile freezes supported shaping and exclusions`() {
        val file = sequenceOf(
            File("../../profiles/published/network_comprehensive_weak_capacity_latency/profile.json"),
            File("../profiles/published/network_comprehensive_weak_capacity_latency/profile.json"),
            File("profiles/published/network_comprehensive_weak_capacity_latency/profile.json"),
        ).first { it.isFile }
        val profile = ProfileParser.parseSingle(file.readText())

        val result = ProfileCapability.assess(profile)
        assertTrue(result.contractIssues.joinToString(), result.executable)
        assertEquals("aneb-synthetic-impairment-v1", profile.syntheticImpairment?.contractVersion)
        assertTrue(profile.syntheticImpairment?.excludedFromShaping?.contains("radio_rsrp") == true)

        val drifted = profile.copy(
            syntheticImpairment = profile.syntheticImpairment?.copy(excludedFromShaping = listOf("dns")),
        )
        assertFalse(ProfileCapability.assess(drifted).executable)
    }

    @Test
    fun `published gateway profiles freeze allowlist fingerprints and radio exclusions`() {
        fun profile(name: String): ScenarioProfile {
            val file = sequenceOf(
                File("../../profiles/published/$name/profile.json"),
                File("../profiles/published/$name/profile.json"),
                File("profiles/published/$name/profile.json"),
            ).first { it.isFile }
            return ProfileParser.parseSingle(file.readText())
        }

        val loss = profile("network_comprehensive_gateway_loss")
        val recovery = profile("network_comprehensive_gateway_recovery")
        assertTrue(ProfileCapability.assess(loss).contractIssues.joinToString(), ProfileCapability.assess(loss).executable)
        assertTrue(ProfileCapability.assess(recovery).contractIssues.joinToString(), ProfileCapability.assess(recovery).executable)
        assertEquals("ip_loss_latency@1.0.0", loss.gatewayImpairment?.profileRef)
        assertEquals("ip_outage_recovery@1.0.0", recovery.gatewayImpairment?.profileRef)
        assertTrue(loss.gatewayImpairment?.excludedFromImpairment?.contains("radio_sinr") == true)

        val drifted = loss.copy(
            gatewayImpairment = loss.gatewayImpairment?.copy(profileFingerprint = "0".repeat(64)),
        )
        assertFalse(ProfileCapability.assess(drifted).executable)
    }
}
