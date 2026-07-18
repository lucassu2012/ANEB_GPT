package com.aneb.probe.engine

import com.aneb.probe.data.EnvEvent
import com.aneb.probe.data.EnvEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeSimulationScorerTest {
    @Test
    fun `missing ready is connection failure not node contract mismatch`() {
        assertNull(realtimeContractInvalidReason(null))
        assertNull(realtimeContractInvalidReason("aneb-realtime-session-v1"))
        assertEquals("node_contract_mismatch", realtimeContractInvalidReason("aneb-realtime-session-v2"))
    }

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
    fun `standard fail conclusion names every missed required quality gate`() {
        val base = goodEvidence("standard", 10, 12)
        val degraded = base.copy(
            sessions = base.sessions.mapIndexed { sessionIndex, session ->
                session.copy(
                    turns = session.turns.mapIndexed { turnIndex, turn ->
                        turn.copy(responseExcessMs = if ((sessionIndex * 12 + turnIndex) % 10 == 0) 250.0 else 40.0)
                    },
                )
            },
        )

        val result = RealtimeSimulationScorer.score(degraded)

        assertEquals(TokenConfidence.HIGH, result.confidence)
        assertEquals(TokenVerdict.FAIL, result.verdict)
        assertTrue(
            result.conclusionItems.single { it.conclusionId == "realtime-required-gate-policy" }
                .text.contains("不能判为 PASS；最终判定仍服从证据等级"),
        )
        assertTrue(result.conclusions.any { it.contains("LIVE-B04 响应超额时延达标率 90.0% < 95.0%") })
        assertEquals(
            listOf("metric:LIVE-B04"),
            result.conclusionItems.single { it.conclusionId == "realtime-failed-quality-gates" }.basis,
        )
        assertTrue(result.conclusions.any { it.contains("即使综合分或等级较高") })
        assertTrue(result.conclusions.any { it.contains("响应超额时延 P95 250.0ms") })
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
    fun `unexpected disconnect reports user task failure before missing score inputs`() {
        val base = goodEvidence("quick", 1, 3)
        val disconnected = base.copy(
            sessions = base.sessions.map { session ->
                session.copy(
                    unexpectedDisconnect = true,
                    error = "network_lost",
                    turns = session.turns.map { turn ->
                        turn.copy(
                            responseExcessMs = null,
                            responseMs = null,
                            uniqueFrames = 0,
                            onTimeFrames = 0,
                            stallFrames = turn.expectedFrames,
                            concealFrames = turn.expectedFrames,
                            arrivalVariationMs = emptyList(),
                            bargeResponseMs = null,
                            success = false,
                            uplinkGoodputKbps = null,
                            downlinkGoodputKbps = null,
                        )
                    },
                )
            },
        )

        val result = RealtimeSimulationScorer.score(disconnected)

        assertNull(result.totalScore)
        assertEquals(TokenVerdict.INCONCLUSIVE, result.verdict)
        assertTrue(result.conclusions.first().contains("业务任务失败"))
        assertTrue(result.conclusions.first().contains("1/1 个会话意外中断"))
        assertTrue(result.conclusions.first().contains("3/3 轮未完成"))
        assertTrue(result.conclusions.any { it.contains("会话中断率应 ≤1%") })
        assertTrue(result.conclusions.any { it.contains("本次分别为 100.0% 和 0.0%") })
        assertTrue(result.conclusions.any { it.contains("不能据此单因归因") })
        assertTrue(result.conclusions.any { it.contains("没有冻结到系统默认网络变化证据") })
        assertTrue(result.conclusions.any { it.contains("必需指标缺失") })
    }

    @Test
    fun `unexpected disconnect reports co-occurring default network evidence without causal claim`() {
        val base = goodEvidence("quick", 1, 3)
        val disconnected = base.copy(
            sessions = base.sessions.map { session ->
                session.copy(
                    unexpectedDisconnect = true,
                    turns = session.turns.map { it.copy(success = false) },
                )
            },
        )
        val pathEvents = listOf(
            EnvEvent(10, EnvEventType.PATH_CHANGE, "default_network_lost path=path-1 transport=wifi"),
            EnvEvent(20, EnvEventType.PATH_CHANGE, "default_network_available path=path-2"),
            EnvEvent(30, EnvEventType.PATH_CHANGE, "default_network_ready path=path-2 transport=cellular validated=true not_suspended=true"),
            EnvEvent(40, EnvEventType.PATH_CHANGE, "default_network_monitor_unavailable reason=SecurityException"),
            EnvEvent(50, EnvEventType.RAT_CHANGE, "lte -> nr"),
        )

        val result = RealtimeSimulationScorer.score(
            disconnected,
            "realtime-interaction-score-v1",
            environmentEvents = pathEvents,
        )

        val attribution = result.conclusions.first { it.contains("系统默认网络变化证据") }
        val attributionItem = result.conclusionItems.first { it.conclusionId == "realtime-connection-stability" }
        assertTrue(attribution.contains("3 条"))
        assertTrue(attribution.contains("默认 Wi-Fi 网络丢失"))
        assertTrue(attribution.contains("与连接异常共现"))
        assertTrue(attribution.contains("不能单独证明因果"))
        assertTrue(!attribution.contains("monitor_unavailable"))
        assertTrue(!attribution.contains("path=path-1"))
        assertEquals(AnebConclusionSeverity.RECOMMENDATION, attributionItem.severity)
        assertTrue(attributionItem.basis.contains("evidence:environment-events"))
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
        assertTrue(result.conclusions.any { it.contains("连接稳定性失败") })
        assertTrue(result.conclusions.any { it.contains("会话中断率应 ≤1%") })
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
