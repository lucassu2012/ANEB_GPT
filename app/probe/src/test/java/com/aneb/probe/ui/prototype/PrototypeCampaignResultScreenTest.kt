package com.aneb.probe.ui.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

class PrototypeCampaignResultScreenTest {
    @Test
    fun `action state projection controls both actions and exact status message`() {
        val expected = listOf(
            Triple(PrototypeCampaignResultActionState.Idle, true, null),
            Triple(PrototypeCampaignResultActionState.Exporting, false, "Exporting…"),
            Triple(PrototypeCampaignResultActionState.PreparingShare, false, "Preparing share…"),
            Triple(PrototypeCampaignResultActionState.Saved, true, "Saved to Downloads/ANEB"),
            Triple(PrototypeCampaignResultActionState.ShareOpened, true, "Share sheet opened"),
            Triple(
                PrototypeCampaignResultActionState.ShareUnavailable,
                true,
                "Saved, but share is unavailable",
            ),
            Triple(PrototypeCampaignResultActionState.Failed, true, "Export failed"),
        )

        expected.forEach { (state, actionsEnabled, message) ->
            val presentation = prototypeCampaignResultActionPresentation(state)
            assertEquals(actionsEnabled, presentation.actionsEnabled)
            assertEquals(message, presentation.message)
        }
    }

    @Test
    fun `ready actions use the independent action state projection`() {
        val screen = source("ui/prototype/PrototypeCampaignResultScreen.kt")

        assertTrue(
            screen.contains(
                "actionState: PrototypeCampaignResultActionState = " +
                    "PrototypeCampaignResultActionState.Idle",
            ),
        )
        assertTrue(
            screen.contains(
                "val actionPresentation = prototypeCampaignResultActions(loadState, actionState)",
            ),
        )
        assertTrue(screen.contains("actionPresentation = requireNotNull(actionPresentation)"))
        assertEquals(
            2,
            Regex("enabled = actionPresentation\\.actionsEnabled")
                .findAll(screen)
                .count(),
        )
        assertTrue(screen.contains("actionPresentation.message?.let"))
    }

    @Test
    fun `only ready load states expose the same action contract for complete and partial results`() {
        val complete = PrototypeCampaignResultLoadState.Ready(
            campaignId = "campaign-complete",
            presentation = resultPresentation("Complete"),
        )
        val partial = PrototypeCampaignResultLoadState.Ready(
            campaignId = "campaign-partial",
            presentation = resultPresentation("Partial"),
        )
        val expectedIdle = prototypeCampaignResultActionPresentation(
            PrototypeCampaignResultActionState.Idle,
        )

        assertEquals(
            null,
            prototypeCampaignResultActions(
                PrototypeCampaignResultLoadState.Loading("campaign-loading"),
                PrototypeCampaignResultActionState.Idle,
            ),
        )
        assertEquals(
            null,
            prototypeCampaignResultActions(
                PrototypeCampaignResultLoadState.Unavailable("campaign-unavailable"),
                PrototypeCampaignResultActionState.Failed,
            ),
        )
        assertEquals(
            expectedIdle,
            prototypeCampaignResultActions(complete, PrototypeCampaignResultActionState.Idle),
        )
        assertEquals(
            expectedIdle,
            prototypeCampaignResultActions(partial, PrototypeCampaignResultActionState.Idle),
        )
        assertEquals(
            "Export failed",
            prototypeCampaignResultActions(
                complete,
                PrototypeCampaignResultActionState.Failed,
            )?.message,
        )
    }

    @Test
    fun `result screen renders validated states and ready actions`() {
        val screen = source("ui/prototype/PrototypeCampaignResultScreen.kt")
        val activity = source("ui/MainActivity.kt")

        assertTrue(screen.contains("fun PrototypeCampaignResultScreen("))
        assertTrue(screen.contains("loadState: PrototypeCampaignResultLoadState"))
        assertTrue(screen.contains("onExport: () -> Unit"))
        assertTrue(screen.contains("onShare: () -> Unit"))
        assertTrue(screen.contains("BackHandler(onBack = onBack)"))
        assertTrue(screen.contains("AnebTopBar(showBack = true, onBack = onBack)"))
        assertTrue(screen.contains("is PrototypeCampaignResultLoadState.Loading"))
        assertTrue(screen.contains("is PrototypeCampaignResultLoadState.Unavailable"))
        assertTrue(screen.contains("is PrototypeCampaignResultLoadState.Ready"))
        assertTrue(screen.contains("presentation.integrity"))
        assertTrue(screen.contains("presentation.evidenceBadge"))
        assertTrue(screen.contains("presentation.confidenceExplanation"))
        assertTrue(screen.contains("presentation.disclosure"))
        assertTrue(screen.contains("presentation.attemptedRuns"))
        assertTrue(screen.contains("presentation.successfulRuns"))
        assertTrue(screen.contains("presentation.failedRuns"))
        assertTrue(screen.contains("presentation.notStartedRuns"))
        assertTrue(screen.contains("presentation.conditions.forEach"))
        assertTrue(screen.contains("condition.metricNullReason"))
        assertTrue(screen.contains("condition.rpiNullReasons"))
        assertTrue(screen.contains("Text(\"Export ZIP\""))
        assertTrue(screen.contains("Text(\"Share ZIP\""))
        assertTrue(screen.contains("Text(\"Back\""))
        assertTrue(screen.contains("onClick = onExport"))
        assertTrue(screen.contains("onClick = onShare"))

        assertTrue(activity.contains("PrototypeCampaignResultScreen("))
        assertTrue(activity.contains("prototypeCampaignResultNavigator.dismiss("))
        assertTrue(activity.contains("screen = Screen.PrototypeMode"))
    }

    private fun source(relativePath: String): String {
        val path = listOf(
            Path.of("app/probe/src/main/java/com/aneb/probe/$relativePath"),
            Path.of("src/main/java/com/aneb/probe/$relativePath"),
            Path.of("../../app/probe/src/main/java/com/aneb/probe/$relativePath"),
        ).firstOrNull(Files::isRegularFile) ?: error("source fixture was not found: $relativePath")
        return Files.readAllBytes(path).toString(UTF_8)
    }

    private fun resultPresentation(status: String): PrototypeCampaignResultPresentation =
        PrototypeCampaignResultPresentation(
            campaignId = "campaign-$status",
            status = status,
            campaignMode = "Quick",
            attemptedRuns = "3",
            successfulRuns = "3",
            failedRuns = "0",
            notStartedRuns = "0",
            integrity = "Local campaign result saved · evidence bundle unverified",
            evidenceBadge = "Synthetic application-layer condition",
            confidenceExplanation = "Evidence completeness only",
            rpiLabel = "Relative Prototype Index",
            disclosure = "Synthetic local probe result",
            conditions = emptyList(),
        )
}
