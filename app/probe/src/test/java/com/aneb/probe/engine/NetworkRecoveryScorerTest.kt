package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkRecoveryScorerTest {
    @Test fun healthySingleEventScoresButRemainsLowConfidenceInconclusive() {
        val result = NetworkRecoveryScorer.score(healthy())
        assertNotNull(result.totalScore)
        assertEquals(TokenVerdict.INCONCLUSIVE, result.verdict)
        assertEquals(TokenConfidence.LOW, result.confidence)
        assertEquals(2_180.0, result.metrics.getValue("RCV-B02").value!!, 0.0)
    }

    @Test fun missingServerAcknowledgementIsInvalid() {
        val result = NetworkRecoveryScorer.score(healthy().copy(serverAcknowledged = false))
        assertNull(result.totalScore)
        assertEquals(TokenVerdict.INVALID, result.verdict)
    }

    @Test fun unobservedOutageFailsGuardrail() {
        val result = NetworkRecoveryScorer.score(healthy().copy(outageFailureCount = 0))
        assertEquals(TokenVerdict.FAIL, result.verdict)
        assertEquals(0.0, result.metrics.getValue("RCV-B01").score!!, 0.0)
    }

    @Test fun noRecoveryRetainsNullAndSuppressesTotal() {
        val result = NetworkRecoveryScorer.score(healthy().copy(recoveryTimeMs = null, postRecoveryRttMs = emptyList()))
        assertEquals(TokenVerdict.FAIL, result.verdict)
        assertNull(result.totalScore)
    }

    private fun healthy() = NetworkRecoveryEvidence(
        serverAcknowledged = true,
        triggerAcknowledged = true,
        declaredOutageMs = 2_000,
        outageFailureCount = 11,
        recoveryTimeMs = 2_180.0,
        postRecoveryRttMs = List(12) { 165.0 + it },
    )
}
