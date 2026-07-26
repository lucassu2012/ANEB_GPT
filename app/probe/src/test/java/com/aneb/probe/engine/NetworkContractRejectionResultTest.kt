package com.aneb.probe.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkContractRejectionResultTest {
    @Test
    fun `contract rejection retains machine reason and suppresses all business output`() {
        val profile = ScenarioProfile(
            profileId = "network_comprehensive_quick",
            version = "1.2.0",
            contractVersion = ScenarioProfile.CONTRACT_V2,
            modeId = ScenarioProfile.MODE_NETWORK_COMPREHENSIVE,
            executionTarget = "aneb_probe_simulator",
            claimScope = "application_end_to_end_to_probe_node",
            evaluation = ProfileEvaluation(
                scorePolicyId = "network-comprehensive-score-v1",
                scoreAnchorPolicyId = "compliance-anchors-v1",
                conclusionPolicyId = "network-comprehensive-conclusions-v2",
            ),
        )

        val result = buildFailedNetworkComprehensiveResult(
            runId = "00000000-0000-4000-8000-000000000003",
            startedAtEpochMs = 1_000L,
            serverBase = "https://aneb.test",
            variant = "quick",
            reasonCode = "receipt_missing",
            profile = profile,
        )

        assertEquals("invalid", result.status)
        assertEquals(TokenVerdict.INVALID, result.verdict)
        assertEquals(TokenConfidence.INVALID, result.confidence)
        assertNull(result.totalScore)
        assertNull(result.grade)
        assertNull(result.downloadMbps)
        assertNull(result.uploadMbps)
        assertEquals(0L, result.downloadBytes)
        assertEquals(0L, result.uploadBytes)
        assertTrue(result.metrics.isEmpty())
        assertTrue(result.groupScores.isEmpty())
        assertEquals("receipt_missing", result.notComputableReason)
        assertEquals(listOf("receipt_missing"), result.transferErrors)
        assertEquals(
            "receipt_missing",
            Json.parseToJsonElement(result.evidenceJson)
                .jsonObject.getValue("invalid_reason").jsonPrimitive.content,
        )
        assertEquals("network_comprehensive_quick", result.profileId)
        assertEquals("1.2.0", result.profileVersion)
    }
}
