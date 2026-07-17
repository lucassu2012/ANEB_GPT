package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenSimulationScorerTest {
    @Test
    fun `compliance anchors interpolate exactly`() {
        assertEquals(0.0, TokenSimulationScorer.complianceScore(0.0), 1e-9)
        assertEquals(55.0, TokenSimulationScorer.complianceScore(0.80), 1e-9)
        assertEquals(70.0, TokenSimulationScorer.complianceScore(0.90), 1e-9)
        assertEquals(85.0, TokenSimulationScorer.complianceScore(0.95), 1e-9)
        assertEquals(100.0, TokenSimulationScorer.complianceScore(1.0), 1e-9)
    }

    @Test
    fun `quick run has score but remains inconclusive low confidence`() {
        val result = TokenSimulationScorer.score(goodEvidence("quick", 3))
        assertNotNull(result.totalScore)
        assertEquals(TokenConfidence.LOW, result.confidence)
        assertEquals(TokenVerdict.INCONCLUSIVE, result.verdict)
    }

    @Test
    fun `standard fully compliant run passes high confidence`() {
        val result = TokenSimulationScorer.score(goodEvidence("standard", 20))
        assertEquals(100.0, result.totalScore!!, 1e-9)
        assertEquals(TokenConfidence.HIGH, result.confidence)
        assertEquals(TokenVerdict.PASS, result.verdict)
        assertEquals(350.0, result.metrics.getValue("TOK-B03").value!!, 1e-9)
        assertEquals(390.0, result.metrics.getValue("TOK-B04").value!!, 1e-9)
        assertEquals(1.0, result.metrics.getValue("TOK-B04").complianceRatio!!, 1e-9)
    }

    @Test
    fun `required metric missing suppresses total score`() {
        val evidence = goodEvidence("standard", 20).copy(rttSamplesMs = List(20) { null })
        val result = TokenSimulationScorer.score(evidence)
        assertNull(result.totalScore)
        assertEquals(TokenVerdict.INCONCLUSIVE, result.verdict)
        assertTrue(result.conclusions.first().contains("必需指标缺失"))
    }

    @Test
    fun `severe stalls cap score at 54`() {
        val tasks = goodEvidence("standard", 20).tasks.mapIndexed { index, task ->
            if (index == 0) task.copy(itlResidualMs = List(100) { 1_500.0 }) else task
        }
        val result = TokenSimulationScorer.score(goodEvidence("standard", 20).copy(tasks = tasks))
        assertTrue(result.totalScore!! <= 54.0)
        assertEquals(TokenVerdict.FAIL, result.verdict)
        assertNotNull(result.capReason)
    }

    @Test
    fun `document and image deadlines preserve approved representative targets`() {
        assertEquals(6_000.0, TokenSimulationScorer.uploadDeadlineMs("document", 5L * 1024 * 1024), 1e-9)
        assertEquals(10_000.0, TokenSimulationScorer.uploadDeadlineMs("image", 10L * 1024 * 1024), 1e-9)
    }

    private fun goodEvidence(variant: String, taskCount: Int): TokenRunEvidence {
        val tasks = List(taskCount) { index ->
            TokenTaskEvidence(
                workloadKind = when (index % 3) { 0 -> "text"; 1 -> "document"; else -> "image" },
                uploadBytes = when (index % 3) { 0 -> 8 * 1024L; 1 -> 5L * 1024 * 1024; else -> 10L * 1024 * 1024 },
                responseArtifactBytes = 0,
                success = true,
                networkFailure = false,
                error = null,
                clickToNodeReceiveMs = 500.0,
                ttftExcessMs = 40.0,
                uploadGoodputMbps = 50.0,
                downloadGoodputMbps = null,
                expectedTokens = 100,
                uniqueTokens = 100,
                duplicateTokens = 0,
                tokenLatenessMs = List(100) { 20.0 },
                itlResidualMs = List(100) { 5.0 },
                requestCount = 1,
                failedRequestCount = 0,
                taskId = "task-$index",
                serverProcessingMs = 350.0,
                ttftMs = 390.0,
            )
        }
        return TokenRunEvidence(
            variant = variant,
            tasks = tasks,
            rttSamplesMs = List(20) { 30.0 },
        )
    }
}
