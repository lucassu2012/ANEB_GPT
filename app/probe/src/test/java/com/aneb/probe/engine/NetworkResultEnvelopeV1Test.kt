package com.aneb.probe.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkResultEnvelopeV1Test {
    @Test fun resolvedResultFreezesAllProfileMetricsAndExplicitMissingState() {
        val observed = NetworkMetricEvidence("NET-B01", 42.0, 1.0, 10, 10, 100.0)
        val profile = profile(listOf("NET-B01", "NET-B08"))
        val result = result(
            metrics = mapOf(observed.metricId to observed),
            evidenceJson = completeEvidenceJson(),
        )
        val input = input(
            result,
            NetworkResultEnvelopeSource(
                profile = profile,
                profileHash = DIGEST_A,
                profileUri = "asset:///published/network_comprehensive_quick/profile.json",
            ),
        )

        val first = NetworkResultEnvelopeV1.build(input)
        val second = NetworkResultEnvelopeV1.build(input)
        assertEquals(first.bodyJson, second.bodyJson)
        assertEquals(first.canonicalSha256, second.canonicalSha256)
        assertEquals(TokenRuntimeIntegrity.canonicalSha256(first.bodyJson), first.canonicalSha256)

        val root = Json.parseToJsonElement(first.bodyJson).jsonObject
        assertEquals("network_comprehensive", root.getValue("test_type").jsonPrimitive.content)
        assertEquals("not_applicable", root.getValue("profile").jsonObject
            .getValue("runtime_artifact_status").jsonPrimitive.content)
        val metrics = root.getValue("evaluation").jsonObject.getValue("metrics").jsonObject
        assertEquals("observed", metrics.getValue("NET-B01").jsonObject.getValue("state").jsonPrimitive.content)
        assertEquals("missing", metrics.getValue("NET-B08").jsonObject.getValue("state").jsonPrimitive.content)
        assertEquals(JsonNull, metrics.getValue("NET-B08").jsonObject.getValue("value"))
        assertEquals("measurement_not_emitted_by_current_engine", metrics.getValue("NET-B08").jsonObject
            .getValue("invalid_reason").jsonPrimitive.content)
        val radio = root.getValue("context").jsonObject.getValue("radio").jsonObject
        assertEquals("not_collected", radio.getValue("collection_status").jsonPrimitive.content)
        assertTrue(root.getValue("completeness").jsonObject.getValue("missing_fields").jsonArray
            .any { it.jsonPrimitive.content == "/context/radio" })
    }

    @Test fun preflightFailureRetainsOriginalContextAndSuppressesScore() {
        val failed = result(
            status = "invalid",
            totalScore = null,
            grade = null,
            verdict = TokenVerdict.INVALID,
            confidence = TokenConfidence.INVALID,
            metrics = emptyMap(),
            transferErrors = listOf("guard_rejected:vpn_active"),
            evidenceJson = """{"invalid_reason":"guard_rejected:vpn_active"}""",
            conclusions = listOf("Measurement did not start."),
        )
        val envelope = NetworkResultEnvelopeV1.build(
                input(failed, NetworkResultEnvelopeSource(profile = null), status = "failed"),
            )
        val root = Json.parseToJsonElement(envelope.bodyJson).jsonObject

        assertEquals("invalid", root.getValue("run").jsonObject.getValue("validity").jsonPrimitive.content)
        val score = root.getValue("evaluation").jsonObject.getValue("score").jsonObject
        assertEquals("suppressed_invalid", score.getValue("state").jsonPrimitive.content)
        assertEquals(JsonNull, score.getValue("value"))
        val raw = root.getValue("category_payload").jsonObject.getValue("raw_evidence").jsonObject
        assertEquals("guard_rejected:vpn_active", raw.getValue("invalid_reason").jsonPrimitive.content)
        assertTrue(raw.containsKey("engine_failure_context"))
        assertEquals(0, raw.getValue("idle_rtt_ms").jsonArray.size)
    }

    private fun profile(metricIds: List<String>) = ScenarioProfile(
        profileId = "network_comprehensive_quick",
        version = "1.0.0",
        contractVersion = ScenarioProfile.CONTRACT_V2,
        modeId = ScenarioProfile.MODE_NETWORK_COMPREHENSIVE,
        claimScope = "application_end_to_end_to_probe_node",
        business = ProfileBusiness(),
        measurementCatalogId = "network-comprehensive-measurements-v1",
        measurements = metricIds.map { id ->
            ProfileMeasurement(
                metricId = id,
                label = if (id == "NET-B01") "Download P05" else "Post-load RTT",
                domain = "network",
                unit = if (id == "NET-B01") "Mbps" else "ms",
                measurementLevel = "exact",
                formulaId = "${id.lowercase()}-v1",
                aggregation = "p05",
                direction = if (id == "NET-B01") "higher_is_better" else "lower_is_better",
                requiredForScore = id == "NET-B01",
                minimumSampleCount = 10,
                qualityTarget = ProfileQualityTarget(
                    operator = if (id == "NET-B01") "gte" else "lte",
                    value = if (id == "NET-B01") 25.0 else 100.0,
                    requiredComplianceRatio = 0.95,
                    provenance = "aneb_product_provisional_v1",
                ),
            )
        },
        evaluation = ProfileEvaluation(
            targetSetId = "network-comprehensive-targets-v1",
            scorePolicyId = "network-comprehensive-score-v1",
            scoreAnchorPolicyId = "compliance-anchors-v1",
            conclusionPolicyId = "network-comprehensive-conclusions-v1",
        ),
    )

    private fun result(
        status: String = "completed",
        totalScore: Double? = 90.0,
        grade: String? = "A",
        verdict: TokenVerdict = TokenVerdict.INCONCLUSIVE,
        confidence: TokenConfidence = TokenConfidence.LOW,
        metrics: Map<String, NetworkMetricEvidence>,
        transferErrors: List<String> = emptyList(),
        evidenceJson: String,
        conclusions: List<String> = listOf("Quick run is directional only."),
    ) = BasicSpeedResult(
        runId = "00000000-0000-7000-8000-000000000301",
        startedAtEpochMs = 1_000L,
        serverBase = "https://probe.invalid",
        claimScope = "application_end_to_end_to_probe_node",
        profileId = "network_comprehensive_quick",
        profileVersion = "1.0.0",
        variant = "quick",
        scorePolicyId = "network-comprehensive-score-v1",
        scoreAnchorPolicyId = "compliance-anchors-v1",
        conclusionPolicyId = "network-comprehensive-conclusions-v1",
        status = status,
        totalScore = totalScore,
        grade = grade,
        verdict = verdict,
        confidence = confidence,
        downloadMbps = 42.0,
        uploadMbps = 16.0,
        pingMs = 30.0,
        loadedRttMs = 80.0,
        jitterMs = 4.0,
        requestLossRate = 0.0,
        postLoadPingMs = 35.0,
        downloadBytes = 1024,
        uploadBytes = 512,
        transferErrors = transferErrors,
        metrics = metrics,
        groupScores = if (totalScore == null) emptyMap() else mapOf("capacity" to 90.0),
        conclusions = conclusions,
        evidenceJson = evidenceJson,
        coverageRatio = if (metrics.isEmpty()) null else 1.0,
        minimumSampleSatisfied = metrics.takeIf { it.isNotEmpty() }?.let { true },
    )

    private fun input(
        result: BasicSpeedResult,
        source: NetworkResultEnvelopeSource,
        status: String = "completed",
    ) = NetworkResultEnvelopeInput(
        result = result,
        source = source,
        producer = AnebResultProducerContext("aneb-probe-android", "0.5.2-codex", "test"),
        device = AnebResultDeviceContext("Huawei", "P40 Pro", "12", 31, "com.aneb.probe.codex", "0.5.2-codex", 34),
        network = AnebResultNetworkContext("auto", "wifi", listOf("validated=true"), null, true, null, false, false, "active"),
        endedAtEpochMs = 2_000L,
        status = status,
    )

    private fun completeEvidenceJson() = """
        {
          "contract_version":"aneb-network-evidence-v1",
          "variant":"quick",
          "idle_rtt_ms":[30.0],
          "loaded_rtt_ms":[80.0],
          "download_windows_mbps":[42.0],
          "upload_windows_mbps":[16.0],
          "app_request_attempts":2,
          "app_request_successes":2,
          "udp_packets_sent":1,
          "udp_received_seqs":[0],
          "udp_unavailable_reason":null,
          "handshakes":[],
          "synthetic_impairment":null,
          "gateway_impairment":null,
          "recovery":null,
          "invalid_reason":null
        }
    """.trimIndent()

    private companion object {
        const val DIGEST_A = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
