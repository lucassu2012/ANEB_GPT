package com.aneb.probe.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeResultEnvelopeV2Test {
    @Test fun collectedRadioIsEmbeddedAndNotMarkedMissing() {
        val evidence = RealtimeRunEvidence("quick", emptyList())
        val result = result(
            RealtimeScoreResult(
                null, null, TokenVerdict.INCONCLUSIVE, TokenConfidence.LOW,
                emptyMap(), emptyMap(), null,
                listOf(AnebConclusionItem("realtime-test-insufficient", AnebConclusionSeverity.WARNING, "Insufficient evidence.", listOf("evidence:realtime-raw"))),
                coverageRatio = 0.0, minimumSampleSatisfied = false,
                notComputableReason = "test_fixture",
            ),
            evidence,
        )
        val root = Json.parseToJsonElement(
            RealtimeResultEnvelopeV2.build(
                input(
                    result,
                    RealtimeResultEnvelopeSource(
                        profile = radioOnlyProfileFixture(
                            "ai_realtime_voice_quick",
                            ScenarioProfile.MODE_AI_REALTIME_SIMULATION,
                            "LIVE-R01",
                        ),
                    ),
                    radio = collectedRadioEvidenceFixture(),
                ),
            ).bodyJson,
        ).jsonObject
        val radio = root.getValue("context").jsonObject.getValue("radio").jsonObject

        assertEquals("collected", radio.getValue("collection_status").jsonPrimitive.content)
        assertEquals(1, radio.getValue("sample_count").jsonPrimitive.content.toInt())
        assertTrue(root.getValue("completeness").jsonObject.getValue("missing_fields").jsonArray
            .none { it.jsonPrimitive.content == "/context/radio" })
        val metric = root.getValue("evaluation").jsonObject.getValue("metrics").jsonObject
            .getValue("LIVE-R01").jsonObject
        assertEquals("observed", metric.getValue("state").jsonPrimitive.content)
        assertEquals(JsonNull, metric.getValue("value"))
        assertEquals("radio-context", metric.getValue("source_evidence_ref_ids").jsonArray.single().jsonPrimitive.content)
    }

    @Test fun resolvedResultFreezesProfileScoreEvidenceAndMissingRadio() {
        val metric = RealtimeMetricEvidence(
            metricId = "LIVE-N02",
            value = 42.5,
            complianceRatio = 0.97,
            sampleCount = 20,
            minimumSampleCount = 20,
            targetComplianceRatio = 0.95,
            score = 91.0,
        )
        val score = RealtimeScoreResult(
            totalScore = 91.0,
            grade = "A",
            verdict = TokenVerdict.PASS,
            confidence = TokenConfidence.HIGH,
            groupScores = mapOf("network_readiness" to 91.0),
            metrics = mapOf(metric.metricId to metric),
            capReason = null,
            conclusionItems = listOf(AnebConclusionItem("realtime-test-pass", AnebConclusionSeverity.INFO, "PASS with sufficient controlled evidence.", listOf("score:verdict"))),
            coverageRatio = 1.0,
            minimumSampleSatisfied = true,
        )
        val profile = profile(metric.metricId)
        val result = result(score, RealtimeRunEvidence("quick", emptyList()))
        val input = input(
            result,
            RealtimeResultEnvelopeSource(
                profile = profile,
                profileHash = DIGEST_A,
                runtimeArtifactHash = DIGEST_C,
                profileUri = "asset:///published/ai_realtime_voice_quick/profile.json",
                runtimeArtifactUri = "asset:///published/ai_realtime_voice_quick/runtime_plan.json",
            ),
        )

        val first = RealtimeResultEnvelopeV2.build(input)
        val second = RealtimeResultEnvelopeV2.build(input)
        assertEquals(first.bodyJson, second.bodyJson)
        assertEquals(first.canonicalSha256, second.canonicalSha256)
        assertEquals(TokenRuntimeIntegrity.canonicalSha256(first.bodyJson), first.canonicalSha256)

        val root = Json.parseToJsonElement(first.bodyJson).jsonObject
        assertEquals("ai_realtime_simulation", root.getValue("test_type").jsonPrimitive.content)
        assertEquals("application_end_to_end_to_probe_node", root.getValue("claim").jsonObject
            .getValue("scope").jsonPrimitive.content)
        assertEquals(DIGEST_A, root.getValue("profile").jsonObject
            .getValue("profile_fingerprint").jsonObject.getValue("value").jsonPrimitive.content)
        assertEquals(91.0, root.getValue("evaluation").jsonObject.getValue("score").jsonObject
            .getValue("value").jsonPrimitive.content.toDouble(), 0.0)
        val conclusion = root.getValue("evaluation").jsonObject.getValue("conclusions").jsonArray.single().jsonObject
        assertEquals("realtime-test-pass", conclusion.getValue("conclusion_id").jsonPrimitive.content)
        assertEquals("info", conclusion.getValue("severity").jsonPrimitive.content)
        assertEquals("score:verdict", conclusion.getValue("basis").jsonArray.single().jsonPrimitive.content)
        assertEquals("realtime-sample-coverage-v1", root.getValue("evaluation").jsonObject
            .getValue("score").jsonObject.getValue("confidence_basis").jsonObject
            .getValue("method_id").jsonPrimitive.content)
        assertEquals(0, root.getValue("category_payload").jsonObject
            .getValue("raw_evidence").jsonObject.getValue("sessions").jsonArray.size)
        val radio = root.getValue("context").jsonObject.getValue("radio").jsonObject
        assertEquals("not_collected", radio.getValue("collection_status").jsonPrimitive.content)
        assertEquals(JsonNull, radio.getValue("rsrp_dbm"))
        assertTrue(root.getValue("completeness").jsonObject.getValue("missing_fields").jsonArray
            .any { it.jsonPrimitive.content == "/context/radio" })
    }

    @Test fun invalidPreflightResultSuppressesScoreAndDoesNotInventProfile() {
        val evidence = RealtimeRunEvidence("quick", emptyList(), "guard_rejected:vpn_active")
        val result = result(RealtimeSimulationScorer.score(evidence), evidence)
        val root = Json.parseToJsonElement(
            RealtimeResultEnvelopeV2.build(
                input(result, RealtimeResultEnvelopeSource(profile = null), status = "failed"),
            ).bodyJson,
        ).jsonObject

        assertEquals("invalid", root.getValue("run").jsonObject.getValue("validity").jsonPrimitive.content)
        val score = root.getValue("evaluation").jsonObject.getValue("score").jsonObject
        assertEquals("suppressed_invalid", score.getValue("state").jsonPrimitive.content)
        assertEquals(JsonNull, score.getValue("value"))
        assertEquals("invalid", score.getValue("verdict").jsonPrimitive.content)
        assertEquals("unavailable", root.getValue("profile").jsonObject
            .getValue("resolution_status").jsonPrimitive.content)
        assertEquals(JsonNull, root.getValue("profile").jsonObject.getValue("profile_version"))
        assertEquals("unavailable", root.getValue("category_payload").jsonObject
            .getValue("behavior_model").jsonObject.getValue("resolution_status").jsonPrimitive.content)
    }

    @Test fun recoveryVariantUsesControlledRecoveryClaim() {
        val evidence = RealtimeRunEvidence("recovery", emptyList(), "controlled_disconnect_missing")
        val result = result(RealtimeSimulationScorer.score(evidence, "realtime-recovery-score-v2"), evidence)
            .copy(variant = "recovery", profileId = "ai_realtime_voice_recovery")
        val root = Json.parseToJsonElement(
            RealtimeResultEnvelopeV2.build(
                input(result, RealtimeResultEnvelopeSource(profile = null), status = "failed"),
            ).bodyJson,
        ).jsonObject

        assertEquals("controlled_server_disconnect_recovery_to_probe_node", root.getValue("claim").jsonObject
            .getValue("scope").jsonPrimitive.content)
    }

    private fun profile(metricId: String) = ScenarioProfile(
        profileId = "ai_realtime_voice_quick",
        version = "1.0.0",
        contractVersion = ScenarioProfile.CONTRACT_V2,
        modeId = ScenarioProfile.MODE_AI_REALTIME_SIMULATION,
        claimScope = "application_end_to_end_to_probe_node",
        business = ProfileBusiness(
            behaviorModelId = "realtime-model",
            behaviorModelVersion = "1.0.0",
            behaviorModelHash = DIGEST_B,
            calibrationStatus = "hypothesis",
            modelSourceKind = "product_requirement_hypothesis",
        ),
        measurementCatalogId = "realtime-interaction-measurements-v1",
        measurements = listOf(
            ProfileMeasurement(
                metricId = metricId,
                label = "Conversation RTT",
                domain = "network",
                unit = "ms",
                measurementLevel = "exact",
                formulaId = "live_n02-v1",
                aggregation = "p95",
                direction = "lower_is_better",
                requiredForScore = true,
                minimumSampleCount = 20,
                qualityTarget = ProfileQualityTarget(
                    operator = "lte",
                    value = 100.0,
                    requiredComplianceRatio = 0.95,
                    provenance = "aneb_product_provisional_v1",
                ),
            ),
        ),
        evaluation = ProfileEvaluation(
            targetSetId = "realtime-interaction-targets-v1",
            scorePolicyId = "realtime-interaction-score-v1",
            scoreAnchorPolicyId = "compliance-anchors-v1",
            conclusionPolicyId = "realtime-interaction-conclusions-v1",
        ),
    )

    private fun result(score: RealtimeScoreResult, evidence: RealtimeRunEvidence) = RealtimeSimulationResult(
        runId = "00000000-0000-7000-8000-000000000201",
        startedAtEpochMs = 1_000L,
        serverBase = "https://probe.invalid",
        profileId = "ai_realtime_voice_quick",
        profileVersion = "1.0.0",
        behaviorModelId = "realtime-model",
        behaviorModelVersion = "1.0.0",
        behaviorModelHash = DIGEST_B,
        calibrationStatus = "hypothesis",
        variant = "quick",
        scorePolicyId = "realtime-interaction-score-v1",
        scoreAnchorPolicyId = "compliance-anchors-v1",
        conclusionPolicyId = "realtime-interaction-conclusions-v1",
        score = score,
        evidence = evidence,
    )

    private fun input(
        result: RealtimeSimulationResult,
        source: RealtimeResultEnvelopeSource,
        status: String = "completed",
        radio: FormalRadioEvidence = FormalRadioEvidence.notCollected("test_fixture_no_radio"),
    ) = RealtimeResultEnvelopeInput(
        result = result,
        source = source,
        producer = AnebResultProducerContext("aneb-probe-android", "0.5.2-codex", "test"),
        device = AnebResultDeviceContext("Huawei", "P40 Pro", "12", 31, "com.aneb.probe.codex", "0.5.2-codex", 34),
        network = AnebResultNetworkContext("auto", "wifi", listOf("validated=true"), null, true, null, false, false, "active"),
        endedAtEpochMs = 2_000L,
        status = status,
        radio = radio,
    )

    private companion object {
        const val DIGEST_A = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val DIGEST_B = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val DIGEST_C = "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}
