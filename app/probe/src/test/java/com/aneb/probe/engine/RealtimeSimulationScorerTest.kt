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
        assertEquals(
            setOf(
                "LIVE-B01", "LIVE-B02", "LIVE-B03", "LIVE-B04", "LIVE-B05", "LIVE-B06",
                "LIVE-B07", "LIVE-B08", "LIVE-B09", "LIVE-B10", "LIVE-B11", "LIVE-B12",
                "LIVE-N01", "LIVE-N02", "LIVE-N03", "LIVE-N04", "LIVE-N05", "LIVE-N06",
                "LIVE-N07", "LIVE-N08", "LIVE-R01",
            ),
            result.metrics.keys,
        )
        assertEquals(0.512, result.metrics.getValue("LIVE-N06").componentValues.getValue("uplink_p05_mbps"), 1e-9)
        assertEquals(120.0, result.metrics.getValue("LIVE-N07").value!!, 1e-9)
        assertEquals(0.0, result.metrics.getValue("LIVE-N03").value!!, 1e-9)
    }

    @Test
    fun `partial standard evidence cannot pass before minimum coverage`() {
        val result = RealtimeSimulationScorer.score(goodEvidence("standard", 5, 12))

        assertNotNull(result.totalScore)
        assertEquals(TokenConfidence.MEDIUM, result.confidence)
        assertEquals(TokenVerdict.INCONCLUSIVE, result.verdict)
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

    @Test
    fun `untriggered optional recovery stays unavailable without suppressing score`() {
        val result = RealtimeSimulationScorer.score(goodEvidence("standard", 10, 12))

        assertNotNull(result.totalScore)
        assertNull(result.metrics.getValue("LIVE-B11").value)
        assertEquals(0, result.metrics.getValue("LIVE-B11").sampleCount)
        assertTrue(result.conclusions.any { it.contains("未触发连接中断") })
    }

    @Test
    fun `observed reconnect freezes recovery evidence`() {
        val base = goodEvidence("standard", 10, 12)
        val sessions = base.sessions.toMutableList()
        sessions[0] = sessions[0].copy(unexpectedDisconnect = true, error = "network_lost")
        sessions[1] = sessions[1].copy(recoveryMs = 1_500.0, reconnectEvents = 1)

        val result = RealtimeSimulationScorer.score(base.copy(sessions = sessions))

        assertEquals(1_500.0, result.metrics.getValue("LIVE-B11").value!!, 1e-9)
        assertEquals(1.0, result.metrics.getValue("LIVE-N08").value!!, 1e-9)
        assertTrue(result.conclusions.any { it.contains("连接恢复 P95") })
    }

    @Test
    fun `controlled recovery passes only with two observed timely recoveries`() {
        val result = RealtimeSimulationScorer.score(
            controlledRecoveryEvidence(),
            "realtime-recovery-score-v2",
        )

        assertEquals(100.0, result.totalScore!!, 1e-9)
        assertEquals(TokenConfidence.HIGH, result.confidence)
        assertEquals(TokenVerdict.PASS, result.verdict)
        assertEquals(1_785.0, result.metrics.getValue("LIVE-B11").value!!, 1e-9)
        assertEquals(2.0, result.metrics.getValue("LIVE-N08").value!!, 1e-9)
        assertTrue(result.conclusions.any { it.contains("计划受控中断 2 次") })
        assertTrue(result.conclusions.any { it.contains("不代表蜂窝断网") })
    }

    @Test
    fun `controlled recovery failure is measured zero rather than missing`() {
        val base = controlledRecoveryEvidence()
        val sessions = base.sessions.toMutableList()
        sessions[3] = sessions[3].copy(recoveryMs = null, reconnectEvents = 1)

        val result = RealtimeSimulationScorer.score(
            base.copy(sessions = sessions),
            "realtime-recovery-score-v2",
        )

        assertEquals(TokenConfidence.HIGH, result.confidence)
        assertEquals(TokenVerdict.FAIL, result.verdict)
        assertTrue(result.totalScore!! <= 54.0)
        assertEquals(0.5, result.metrics.getValue("LIVE-B11").complianceRatio!!, 1e-9)
        assertNotNull(result.capReason)
    }

    private fun controlledRecoveryEvidence(): RealtimeRunEvidence {
        val base = goodEvidence("recovery", 4, 3)
        return base.copy(
            sessions = base.sessions.mapIndexed { index, session ->
                when (index) {
                    0, 2 -> session.copy(
                        unexpectedDisconnect = true,
                        error = "controlled_transport_close",
                        controlledDisconnectExpected = true,
                        controlledDisconnectObserved = true,
                    )
                    1 -> session.copy(recoveryMs = 1_500.0, reconnectEvents = 1, recoveryStimulusBaselineMs = 1_550.0)
                    else -> session.copy(recoveryMs = 1_800.0, reconnectEvents = 1, recoveryStimulusBaselineMs = 1_550.0)
                }
            },
        )
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
                            responseMs = 540.0,
                            maxMissingRunFrames = 0,
                            uplinkGoodputKbps = 512.0,
                            downlinkGoodputKbps = 640.0,
                            unplannedOverlap = if (turnIndex == 0) null else false,
                        )
                    },
                    unexpectedDisconnect = false,
                    error = null,
                    loadedRttSamplesMs = List(20) { 120.0 },
                )
            },
        )
}
