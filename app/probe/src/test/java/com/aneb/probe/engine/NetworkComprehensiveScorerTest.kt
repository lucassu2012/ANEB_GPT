package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkComprehensiveScorerTest {
    @Test fun healthyStandardPassesWithHighConfidence() {
        val result = NetworkComprehensiveScorer.score(healthy("standard"))
        assertEquals(TokenVerdict.PASS, result.verdict)
        assertEquals(TokenConfidence.HIGH, result.confidence)
        assertNotNull(result.totalScore)
        assertEquals("A", result.grade)
    }

    @Test fun quickIsAlwaysLowAndInconclusive() {
        val result = NetworkComprehensiveScorer.score(healthy("quick"))
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
    }

    @Test fun loadedLatencyRegressionFailsStandard() {
        val result = NetworkComprehensiveScorer.score(
            healthy("standard").copy(loadedRttMs = List(120) { 420.0 }),
        )
        assertEquals(TokenVerdict.FAIL, result.verdict)
        assertNotNull(result.totalScore)
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
