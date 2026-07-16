package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeSimulationScorerTest {
    @Test
    fun `quick score remains low confidence and inconclusive`() {
        val result = RealtimeSimulationScorer.score(goodEvidence("quick", 1, 3))
        assertNotNull(result.totalScore)
        assertEquals(TokenConfidence.LOW, result.confidence)
        assertEquals(TokenVerdict.INCONCLUSIVE, result.verdict)
    }

    @Test
    fun `standard compliant evidence passes with high confidence`() {
        val result = RealtimeSimulationScorer.score(goodEvidence("standard", 10, 12))
        assertEquals(100.0, result.totalScore!!, 1e-9)
        assertEquals(TokenConfidence.HIGH, result.confidence)
        assertEquals(TokenVerdict.PASS, result.verdict)
    }

    @Test
    fun `missing required RTT suppresses score`() {
        val evidence = goodEvidence("standard", 10, 12).copy(
            sessions = goodEvidence("standard", 10, 12).sessions.map { it.copy(rttSamplesMs = emptyList()) },
        )
        val result = RealtimeSimulationScorer.score(evidence)
        assertNull(result.totalScore)
        assertEquals(TokenVerdict.INCONCLUSIVE, result.verdict)
    }

    @Test
    fun `stall guardrail caps score`() {
        val evidence = goodEvidence("standard", 10, 12)
        val degraded = evidence.copy(
            sessions = evidence.sessions.map { session ->
                session.copy(turns = session.turns.map { it.copy(stallFrames = 10, concealFrames = 10, onTimeFrames = 90) })
            },
        )
        val result = RealtimeSimulationScorer.score(degraded)
        assertTrue(result.totalScore!! <= 54.0)
        assertNotNull(result.capReason)
    }

    private fun goodEvidence(variant: String, sessionCount: Int, turnsPerSession: Int): RealtimeRunEvidence =
        RealtimeRunEvidence(
            variant = variant,
            sessions = List(sessionCount) { sessionIndex ->
                RealtimeSessionEvidence(
                    established = true,
                    setupMs = 500.0,
                    handshakeMs = 50.0,
                    rttSamplesMs = List(5) { 45.0 },
                    turns = List(turnsPerSession) { turnIndex ->
                        RealtimeTurnEvidence(
                            responseExcessMs = 40.0,
                            expectedFrames = 100,
                            uniqueFrames = 100,
                            onTimeFrames = 100,
                            stallFrames = 0,
                            concealFrames = 0,
                            arrivalVariationMs = List(99) { 2.0 },
                            bargeResponseMs = if (turnIndex < 2 || (variant == "quick" && turnIndex == 0)) 80.0 else null,
                            interrupted = turnIndex < 2 || (variant == "quick" && turnIndex == 0),
                            success = true,
                        )
                    },
                    unexpectedDisconnect = false,
                    error = null,
                )
            },
        )
}
