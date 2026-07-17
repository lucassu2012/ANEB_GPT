package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenStressScorerTest {
    @Test
    fun `fully compliant stress run scores 100 but remains directional`() {
        val result = TokenStressScorer.score(goodEvidence())

        assertEquals(100.0, result.totalScore!!, 1e-9)
        assertEquals(TokenConfidence.LOW, result.confidence)
        assertEquals(TokenVerdict.INCONCLUSIVE, result.verdict)
        assertTrue(result.conclusions.first().contains("单次方向性证据"))
        assertEquals(380.0, result.metrics.getValue("TOK-B04").value!!, 1e-9)
    }

    @Test
    fun `incomplete 100MiB task fails and caps score`() {
        val failed = goodEvidence().copy(
            tasks = goodEvidence().tasks.map {
                it.copy(success = false, networkFailure = true, failedRequestCount = 1)
            },
        )
        val result = TokenStressScorer.score(failed)

        assertEquals(TokenVerdict.FAIL, result.verdict)
        assertTrue(result.totalScore!! <= 54.0)
        assertNotNull(result.capReason)
    }

    @Test
    fun `missing loaded RTT suppresses total score`() {
        val result = TokenStressScorer.score(goodEvidence().copy(loadedRttSamplesMs = emptyList()))

        assertNull(result.totalScore)
        assertEquals(TokenVerdict.INCONCLUSIVE, result.verdict)
        assertTrue(result.conclusions.first().contains("必需指标缺失"))
    }

    @Test
    fun `loaded RTT target affects independent responsiveness score`() {
        val result = TokenStressScorer.score(goodEvidence().copy(loadedRttSamplesMs = List(40) { 350.0 }))

        assertNotNull(result.totalScore)
        assertTrue(result.groupScores.getValue("loaded_responsiveness") < 55.0)
        assertEquals(TokenConfidence.LOW, result.confidence)
    }

    private fun goodEvidence(): TokenRunEvidence = TokenRunEvidence(
        variant = "stress",
        tasks = listOf(
            TokenTaskEvidence(
                workloadKind = "video",
                uploadBytes = 100L * 1024 * 1024,
                responseArtifactBytes = 100L * 1024 * 1024,
                success = true,
                networkFailure = false,
                error = null,
                clickToNodeReceiveMs = 50_000.0,
                ttftExcessMs = 40.0,
                uploadGoodputMbps = 25.0,
                downloadGoodputMbps = 30.0,
                expectedTokens = 300,
                uniqueTokens = 300,
                duplicateTokens = 0,
                tokenLatenessMs = List(300) { 20.0 },
                itlResidualMs = List(300) { 5.0 },
                requestCount = 2,
                failedRequestCount = 0,
                artifactDownloadDurationMs = 28_000.0,
                taskId = "video-100mib",
                serverProcessingMs = 300.0,
                ttftMs = 380.0,
            ),
        ),
        rttSamplesMs = List(20) { 50.0 },
        loadedRttSamplesMs = List(40) { 120.0 },
    )
}
