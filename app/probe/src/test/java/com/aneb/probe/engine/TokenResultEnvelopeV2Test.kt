package com.aneb.probe.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenResultEnvelopeV2Test {
    @Test fun rawTaskEvidenceCarriesAlignedEndToEndTtftBasis() {
        val evidence = TokenRunEvidence(
            variant = "quick",
            tasks = listOf(
                TokenTaskEvidence(
                    workloadKind = "text",
                    uploadBytes = 8192,
                    responseArtifactBytes = 0,
                    success = true,
                    networkFailure = false,
                    error = null,
                    clickToNodeReceiveMs = 10.0,
                    ttftExcessMs = 40.0,
                    uploadGoodputMbps = 6.0,
                    downloadGoodputMbps = null,
                    expectedTokens = 10,
                    uniqueTokens = 10,
                    duplicateTokens = 0,
                    tokenLatenessMs = listOf(40.0),
                    itlResidualMs = listOf(2.0),
                    requestCount = 1,
                    failedRequestCount = 0,
                    taskId = "quick-text-0",
                    serverProcessingMs = 300.0,
                    ttftMs = 340.0,
                ),
            ),
            rttSamplesMs = listOf(30.0),
        )

        val task = TokenResultEnvelopeV2.rawEvidenceJson(evidence)
            .getValue("tasks").jsonArray.single().jsonObject
        assertEquals("quick-text-0", task.getValue("task_id").jsonPrimitive.content)
        assertEquals(300.0, task.getValue("server_processing_ms").jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(340.0, task.getValue("ttft_ms").jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test fun collectedRadioIsEmbeddedReferencedAndNotMarkedMissing() {
        val evidence = TokenRunEvidence("quick", emptyList(), listOf(42.5))
        val result = tokenResult(
            TokenScoreResult(
                null, null, TokenVerdict.INCONCLUSIVE, TokenConfidence.LOW,
                emptyMap(), emptyMap(), null, listOf("Insufficient evidence."),
                coverageRatio = 0.0, minimumSampleSatisfied = false,
                notComputableReason = "test_fixture",
            ),
            evidence,
        )
        val body = TokenResultEnvelopeV2.build(
            input(
                result,
                TokenResultEnvelopeSource(
                    profile = radioOnlyProfileFixture(
                        "token_multimodal_quick",
                        ScenarioProfile.MODE_TOKEN_SIMULATION,
                        "TOK-R01",
                    ),
                ),
                radio = collectedRadioEvidenceFixture(),
            ),
        ).bodyJson
        val root = Json.parseToJsonElement(body).jsonObject
        val radio = root.getValue("context").jsonObject.getValue("radio").jsonObject

        assertEquals("collected", radio.getValue("collection_status").jsonPrimitive.content)
        assertEquals(1, radio.getValue("sample_count").jsonPrimitive.content.toInt())
        assertFalse(root.getValue("completeness").jsonObject.getValue("missing_fields").jsonArray
            .any { it.jsonPrimitive.content == "/context/radio" })
        assertTrue(root.getValue("evidence").jsonObject.getValue("refs").jsonArray
            .any { it.jsonObject.getValue("ref_id").jsonPrimitive.content == "radio-context" })
        assertEquals(1, root.getValue("evidence").jsonObject.getValue("environment_events").jsonArray.size)
        val metric = root.getValue("evaluation").jsonObject.getValue("metrics").jsonObject
            .getValue("TOK-R01").jsonObject
        assertEquals("observed", metric.getValue("state").jsonPrimitive.content)
        assertEquals(JsonNull, metric.getValue("value"))
        assertEquals(1, metric.getValue("sample_count").jsonPrimitive.content.toInt())
        assertEquals("radio-context", metric.getValue("source_evidence_ref_ids").jsonArray.single().jsonPrimitive.content)
        assertFalse(body.contains("22.5"))
        assertFalse(body.contains("114.0"))
    }

    @Test fun resolvedResultFreezesIdentityScoreAndExplicitMissingRadio() {
        val metric = TokenMetricEvidence(
            metricId = "TOK-N03",
            value = 42.5,
            complianceRatio = 0.97,
            sampleCount = 20,
            minimumSampleCount = 20,
            targetComplianceRatio = 0.95,
            score = 91.0,
        )
        val score = TokenScoreResult(
            totalScore = 88.0,
            grade = "A",
            verdict = TokenVerdict.PASS,
            confidence = TokenConfidence.HIGH,
            groupScores = mapOf("network_stability" to 91.0),
            metrics = mapOf(metric.metricId to metric),
            capReason = null,
            conclusions = listOf("结论：PASS；证据置信度 HIGH。"),
            coverageRatio = 1.0,
            minimumSampleSatisfied = true,
        )
        val profile = ScenarioProfile(
            profileId = "token_multimodal_quick",
            version = "1.0.0",
            contractVersion = ScenarioProfile.CONTRACT_V2,
            modeId = ScenarioProfile.MODE_TOKEN_SIMULATION,
            claimScope = "application_end_to_end_to_probe_node",
            business = ProfileBusiness(
                behaviorModelId = "token-model",
                behaviorModelVersion = "1.0.0",
                behaviorModelHash = DIGEST_B,
                calibrationStatus = "hypothesis",
                modelSourceKind = "product_requirement_hypothesis",
            ),
            measurementCatalogId = "token-sim-measurements-v1",
            measurements = listOf(
                ProfileMeasurement(
                    metricId = metric.metricId,
                    label = "应用层 RTT",
                    domain = "network",
                    unit = "ms",
                    measurementLevel = "exact",
                    formulaId = "tok_n03-v1",
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
                ProfileMeasurement(
                    metricId = "TOK-B03",
                    label = "Document upload latency",
                    domain = "business",
                    unit = "ms",
                    measurementLevel = "exact",
                    formulaId = "tok_b03-v1",
                    aggregation = "p95",
                    direction = "lower_is_better",
                    requiredForScore = false,
                    minimumSampleCount = 3,
                    qualityTarget = ProfileQualityTarget(
                        operator = "lte",
                        value = 5_000.0,
                        requiredComplianceRatio = 0.95,
                        provenance = "aneb_product_provisional_v1",
                    ),
                ),
            ),
            evaluation = ProfileEvaluation(
                targetSetId = "token-sim-targets-v1",
                scorePolicyId = "token-sim-score-v1",
                scoreAnchorPolicyId = "compliance-anchors-v1",
                conclusionPolicyId = "token-sim-conclusions-v1",
            ),
        )
        val result = tokenResult(score, TokenRunEvidence("quick", emptyList(), listOf(42.5)))
        val input = input(
            result = result,
            source = TokenResultEnvelopeSource(
                profile = profile,
                profileHash = DIGEST_A,
                runtimeArtifactHash = DIGEST_C,
                profileUri = "asset:///published/token_multimodal_quick/profile.json",
                runtimeArtifactUri = "asset:///published/token_multimodal_quick/runtime_plan.json",
            ),
        )

        val first = TokenResultEnvelopeV2.build(input)
        val second = TokenResultEnvelopeV2.build(input)
        assertEquals(first.bodyJson, second.bodyJson)
        assertEquals(first.canonicalSha256, second.canonicalSha256)
        assertEquals(TokenRuntimeIntegrity.canonicalSha256(first.bodyJson), first.canonicalSha256)

        val root = Json.parseToJsonElement(first.bodyJson).jsonObject
        assertEquals("aneb-result-v2", root.getValue("schema_version").jsonPrimitive.content)
        assertEquals(
            "aneb-result-exporter-v2",
            root.getValue("producer").jsonObject.getValue("exporter_version").jsonPrimitive.content,
        )
        assertEquals(88.0, root.getValue("evaluation").jsonObject
            .getValue("score").jsonObject.getValue("value").jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(DIGEST_A, root.getValue("profile").jsonObject
            .getValue("profile_fingerprint").jsonObject.getValue("value").jsonPrimitive.content)
        val frozenMetrics = root.getValue("evaluation").jsonObject.getValue("metrics").jsonObject
        assertEquals("missing", frozenMetrics.getValue("TOK-B03").jsonObject
            .getValue("state").jsonPrimitive.content)
        assertEquals("measurement_not_emitted_by_current_engine", frozenMetrics.getValue("TOK-B03").jsonObject
            .getValue("invalid_reason").jsonPrimitive.content)
        val radio = root.getValue("context").jsonObject.getValue("radio").jsonObject
        assertEquals("not_collected", radio.getValue("collection_status").jsonPrimitive.content)
        assertEquals(JsonNull, radio.getValue("rsrp_dbm"))
        assertEquals(0, radio.getValue("sample_count").jsonPrimitive.content.toInt())
        assertTrue(root.getValue("completeness").jsonObject.getValue("missing_fields").jsonArray
            .any { it.jsonPrimitive.content == "/context/radio" })
        assertFalse(first.bodyJson.contains("latitude", ignoreCase = true))
        assertFalse(first.bodyJson.contains("longitude", ignoreCase = true))
    }

    @Test fun invalidPreflightResultDisclosesUnavailableProfileAndSuppressesScore() {
        val evidence = TokenRunEvidence(
            variant = "quick",
            tasks = emptyList(),
            rttSamplesMs = emptyList(),
            invalidReason = "guard_rejected:vpn_active",
        )
        val result = tokenResult(TokenSimulationScorer.score(evidence), evidence)
        val root = Json.parseToJsonElement(
            TokenResultEnvelopeV2.build(
                input(result, TokenResultEnvelopeSource(profile = null), status = "failed"),
            ).bodyJson,
        ).jsonObject

        assertEquals("invalid", root.getValue("run").jsonObject.getValue("validity").jsonPrimitive.content)
        assertEquals("suppressed_invalid", root.getValue("evaluation").jsonObject
            .getValue("score").jsonObject.getValue("state").jsonPrimitive.content)
        assertEquals(JsonNull, root.getValue("evaluation").jsonObject
            .getValue("score").jsonObject.getValue("value"))
        assertEquals("unavailable", root.getValue("profile").jsonObject
            .getValue("resolution_status").jsonPrimitive.content)
        assertEquals(JsonNull, root.getValue("profile").jsonObject.getValue("profile_version"))
        assertEquals("unavailable", root.getValue("category_payload").jsonObject
            .getValue("behavior_model").jsonObject.getValue("resolution_status").jsonPrimitive.content)
    }

    private fun tokenResult(score: TokenScoreResult, evidence: TokenRunEvidence) = TokenSimulationResult(
        runId = "00000000-0000-7000-8000-000000000101",
        startedAtEpochMs = 1_000L,
        serverBase = "https://probe.invalid",
        profileId = "token_multimodal_quick",
        profileVersion = "1.0.0",
        behaviorModelId = "token-model",
        behaviorModelVersion = "1.0.0",
        behaviorModelHash = DIGEST_B,
        calibrationStatus = "hypothesis",
        variant = "quick",
        scorePolicyId = "token-sim-score-v1",
        scoreAnchorPolicyId = "compliance-anchors-v1",
        conclusionPolicyId = "token-sim-conclusions-v1",
        score = score,
        evidence = evidence,
    )

    private fun input(
        result: TokenSimulationResult,
        source: TokenResultEnvelopeSource,
        status: String = "completed",
        radio: FormalRadioEvidence = FormalRadioEvidence.notCollected("test_fixture_no_radio"),
    ) = TokenResultEnvelopeInput(
        result = result,
        source = source,
        producer = AnebResultProducerContext("aneb-probe-android", "0.5.1-codex", "test"),
        device = AnebResultDeviceContext("Huawei", "P40 Pro", "12", 31, "com.aneb.probe.codex", "0.5.1-codex", 33),
        network = AnebResultNetworkContext("auto", "cellular", listOf("validated=true"), null, true, null, null, false, "active"),
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
