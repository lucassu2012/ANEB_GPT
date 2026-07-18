package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnebConclusionTest {
    @Test fun invalidRunsFreezeFailureMeaningWithoutOrderInference() {
        val token = TokenSimulationScorer.score(TokenRunEvidence("quick", emptyList(), emptyList(), "bad_clock"))
        val realtime = RealtimeSimulationScorer.score(RealtimeRunEvidence("quick", emptyList(), "bad_contract"))
        val network = NetworkComprehensiveScorer.score(
            NetworkComprehensiveEvidence(
                variant = "standard",
                idleRttMs = emptyList(),
                loadedRttMs = emptyList(),
                downloadWindowsMbps = emptyList(),
                uploadWindowsMbps = emptyList(),
                appRequestAttempts = 0,
                appRequestSuccesses = 0,
                udpPacketsSent = 0,
                udpReceivedSeqs = emptyList(),
                udpUnavailableReason = null,
                handshakes = emptyList(),
                invalidReason = "bad_probe",
            ),
        )

        assertEquals("token-invalid-evidence", token.conclusionItems.single().conclusionId)
        assertEquals("realtime-invalid-evidence", realtime.conclusionItems.single().conclusionId)
        assertEquals("network-invalid-evidence", network.conclusionItems.single().conclusionId)
        listOf(token.conclusionItems.single(), realtime.conclusionItems.single(), network.conclusionItems.single()).forEach { item ->
            assertEquals(AnebConclusionSeverity.FAILURE, item.severity)
            assertTrue(item.basis.contains("invalid_reason"))
        }
    }

    @Test fun behaviorVocabularyPreservesUnknownProfileIdsInsteadOfHidingDrift() {
        val sentence = AnebBehaviorFeatureCatalogV1.sentence(
            listOf("full_duplex", "future_feature"),
            fallbackFeatureIds = emptyList(),
        )

        assertTrue(sentence.contains("全双工交互"))
        assertTrue(sentence.contains("未识别特征(future_feature)"))
    }
}
