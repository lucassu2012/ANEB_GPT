package com.aneb.probe.ui.prototype

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrototypeModeScreenConfirmationTest {
    @Test
    fun quickSelectionRequiresConfirmationBeforeStarting() {
        var quickStarts = 0
        var acceptanceStarts = 0

        val selected = PrototypeCampaignLaunchState()
            .select(PrototypeCampaignLaunchMode.QUICK)

        assertEquals(0, quickStarts)
        assertEquals("Quick", selected.pending?.modeLabel)
        assertEquals(3, selected.pending?.runCount)

        val confirmed = selected.confirm(
            onStartQuick = { quickStarts += 1 },
            onStartAcceptance = { acceptanceStarts += 1 },
        )

        assertEquals(1, quickStarts)
        assertEquals(0, acceptanceStarts)
        assertNull(confirmed.pending)
    }

    @Test
    fun quickConfirmationDisclosesItsFixedOrderAndDuration() {
        val confirmation = PrototypeCampaignLaunchState()
            .select(PrototypeCampaignLaunchMode.QUICK)
            .pending
            ?: error("Quick confirmation was not selected")

        assertEquals("B1 → S1 → U1", confirmation.runOrder)
        assertEquals("About 35 seconds", confirmation.estimatedDuration)
    }

    @Test
    fun acceptanceConfirmationDisclosesTheFixedCampaignAndEvidenceBoundary() {
        val confirmation = PrototypeCampaignLaunchState()
            .select(PrototypeCampaignLaunchMode.ACCEPTANCE)
            .pending
            ?: error("Acceptance confirmation was not selected")

        assertEquals("Acceptance", confirmation.modeLabel)
        assertEquals(9, confirmation.runCount)
        assertEquals(
            "B1 → S1 → U1 → B2 → S2 → U2 → B3 → S3 → U3",
            confirmation.runOrder,
        )
        assertEquals("About 1 minute 45 seconds", confirmation.estimatedDuration)
        assertEquals(
            "Results are stored locally in Room. After a result is saved, you can export " +
                "an unverified ZIP on this device.",
            confirmation.evidenceNotice,
        )
        assertEquals(
            "Synthetic app-layer measurement only — not AQS, an operator rating or an SLA.",
            confirmation.claimBoundary,
        )
    }

    @Test
    fun acceptanceStartsOnlyAfterItsConfirmation() {
        var quickStarts = 0
        var acceptanceStarts = 0
        val selected = PrototypeCampaignLaunchState()
            .select(PrototypeCampaignLaunchMode.ACCEPTANCE)

        val confirmed = selected.confirm(
            onStartQuick = { quickStarts += 1 },
            onStartAcceptance = { acceptanceStarts += 1 },
        )

        assertEquals(0, quickStarts)
        assertEquals(1, acceptanceStarts)
        assertNull(confirmed.pending)
    }

    @Test
    fun cancellingTheConfirmationClosesItWithoutStartingEitherCampaign() {
        var quickStarts = 0
        var acceptanceStarts = 0
        val cancelled = PrototypeCampaignLaunchState()
            .select(PrototypeCampaignLaunchMode.ACCEPTANCE)
            .cancel()

        val unchanged = cancelled.confirm(
            onStartQuick = { quickStarts += 1 },
            onStartAcceptance = { acceptanceStarts += 1 },
        )

        assertNull(unchanged.pending)
        assertEquals(0, quickStarts)
        assertEquals(0, acceptanceStarts)
    }

    @Test
    fun modeButtonsOnlySelectAndTheConfirmationOwnsTheStartCallbacks() {
        val source = source("ui/prototype/PrototypeModeScreen.kt")

        assertTrue(source.contains("var launchState by remember"))
        assertTrue(source.contains("PrototypeCampaignLaunchMode.QUICK"))
        assertTrue(source.contains("PrototypeCampaignLaunchMode.ACCEPTANCE"))
        assertTrue(source.contains("launchState = launchState.confirm("))
        assertTrue(source.contains("Text(\"Start campaign\""))
        assertTrue(source.contains("launchState = launchState.cancel()"))
        assertTrue(source.contains("confirmation.runOrder"))
        assertTrue(source.contains("confirmation.estimatedDuration"))
        assertTrue(source.contains("confirmation.evidenceNotice"))
        assertTrue(source.contains("confirmation.claimBoundary"))
        assertFalse(source.contains("onClick = onStartQuick"))
        assertFalse(source.contains("onClick = onStartAcceptance"))
    }

    private fun source(relativePath: String): String {
        val path = listOf(
            Path.of("app/probe/src/main/java/com/aneb/probe/$relativePath"),
            Path.of("src/main/java/com/aneb/probe/$relativePath"),
            Path.of("../../app/probe/src/main/java/com/aneb/probe/$relativePath"),
        ).firstOrNull(Files::isRegularFile)
            ?: error("source fixture was not found: $relativePath")
        return Files.readAllBytes(path).toString(UTF_8)
    }
}
