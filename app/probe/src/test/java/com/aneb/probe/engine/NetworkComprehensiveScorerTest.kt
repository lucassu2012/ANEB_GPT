package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkComprehensiveScorerTest {
    @Test fun healthyStandardPassesWithHighConfidence() {
        val result = NetworkComprehensiveScorer.score(healthy("standard"))
        assertEquals(TokenVerdict.PASS, result.verdict)
        assertEquals(TokenConfidence.HIGH, result.confidence)
        assertNotNull(result.totalScore)
        assertEquals("A", result.grade)
        assertTrue(result.conclusionItems.any { it.conclusionId == "network-task-completion" })
        assertTrue(result.conclusionItems.any { it.conclusionId == "network-behavior-profile" && it.text.contains("负载响应能力") })
        assertTrue(result.conclusionItems.any { it.conclusionId == "network-primary-bottleneck" && it.basis.single().startsWith("metric:") })
    }

    @Test fun quickIsAlwaysLowAndInconclusive() {
        val result = NetworkComprehensiveScorer.score(healthy("quick"))
        assertEquals(TokenVerdict.INCONCLUSIVE, result.verdict)
        assertEquals(TokenConfidence.LOW, result.confidence)
        assertNotNull(result.totalScore)
    }

    @Test fun singleRepeatabilityQualificationRunCannotRaiseConfidenceOrPass() {
        val result = NetworkComprehensiveScorer.score(healthy("repeatability_qualification"))
        assertEquals(TokenVerdict.INCONCLUSIVE, result.verdict)
        assertEquals(TokenConfidence.LOW, result.confidence)
        assertNotNull(result.totalScore)
    }

    @Test fun udpUnavailableSuppressesTotalInsteadOfInventingLoss() {
        val result = NetworkComprehensiveScorer.score(
            healthy("standard").copy(udpReceivedSeqs = emptyList(), udpUnavailableReason = "no_response"),
        )
        assertEquals(TokenVerdict.INCONCLUSIVE, result.verdict)
        assertNull(result.totalScore)
        assertNull(result.metrics.getValue("NET-B10").value)
        assertTrue(result.conclusionItems.any { it.conclusionId == "network-udp-unavailable" && it.severity == AnebConclusionSeverity.WARNING })
    }

    @Test fun loadedLatencyRegressionFailsStandard() {
        val result = NetworkComprehensiveScorer.score(
            healthy("standard").copy(loadedRttMs = List(120) { 420.0 }),
        )
        assertEquals(TokenVerdict.FAIL, result.verdict)
        assertNotNull(result.totalScore)
    }

    @Test fun syntheticWeakNetworkConclusionDisclosesTargetsAndExclusions() {
        val result = NetworkComprehensiveScorer.score(
            healthy("weak_capacity_latency").copy(
                syntheticImpairment = SyntheticNetworkEvidence(
                    profileId = "network_comprehensive_weak_capacity_latency",
                    profileVersion = "1.0.0",
                    downlinkMbps = 3.0,
                    uplinkMbps = 1.0,
                    addedRttMs = 120,
                    jitterMs = 30,
                    appliesTo = listOf("http_request_delay", "http_request_body", "http_response_body"),
                    excludedFromShaping = listOf("dns", "tcp", "tls", "udp", "radio_rsrp", "radio_sinr"),
                    serverAcknowledged = true,
                ),
            ),
        )
        assertTrue(result.conclusions.any { it.contains("合成弱网") && it.contains("120±30") })
        assertTrue(result.conclusions.any { it.contains("RSRP/SINR") })
    }

    private fun healthy(variant: String) = NetworkComprehensiveEvidence(
        variant = variant,
        idleRttMs = List(30) { 35.0 + (it % 3) },
        loadedRttMs = List(120) { 65.0 + (it % 5) },
        downloadWindowsMbps = List(15) { 80.0 + (it % 3) },
        uploadWindowsMbps = List(15) { 35.0 + (it % 2) },
        appRequestAttempts = 155,
        appRequestSuccesses = 155,
        udpPacketsSent = 200,
        udpReceivedSeqs = (0 until 200).toList(),
        udpUnavailableReason = null,
        handshakes = List(5) { NetworkHandshakeEvidence(8.0, 20.0, 35.0, true) },
    )
}
