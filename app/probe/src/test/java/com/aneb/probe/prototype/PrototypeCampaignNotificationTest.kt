package com.aneb.probe.prototype

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PrototypeCampaignNotificationTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun shellHasNoCancelAndCampaignActionsRemainBoundToTheirOriginalOwner() {
        val shell = notification(campaignId = null)
        assertEquals(0, shell.actions?.size ?: 0)

        val first = notification("campaign-notification-a")
        val replacement = notification("campaign-notification-b")
        val firstAction = first.actions.single().actionIntent
        val replacementAction = replacement.actions.single().actionIntent

        assertNotEquals(firstAction, replacementAction)
        assertEquals(
            "campaign-notification-a",
            PrototypeCampaignCancelIntent.campaignIdOrNull(shadowOf(firstAction).savedIntent),
        )
        assertEquals(
            "campaign-notification-b",
            PrototypeCampaignCancelIntent.campaignIdOrNull(shadowOf(replacementAction).savedIntent),
        )
        assertEquals(
            "campaign-notification-a",
            PrototypeCampaignCancelIntent.campaignIdOrNull(shadowOf(firstAction).savedIntent),
        )
    }

    @Test
    fun cancelIntentRejectsMissingBlankOrMismatchedCampaignOwnership() {
        val valid = PrototypeCampaignCancelIntent.create(context, "campaign-intent-a")
        assertEquals("campaign-intent-a", PrototypeCampaignCancelIntent.campaignIdOrNull(valid))

        val invalid = listOf(
            Intent(valid).also {
                it.removeExtra(PrototypeCampaignCancelIntent.EXTRA_CAMPAIGN_ID)
            },
            Intent(valid).putExtra(PrototypeCampaignCancelIntent.EXTRA_CAMPAIGN_ID, ""),
            Intent(valid).putExtra(
                PrototypeCampaignCancelIntent.EXTRA_CAMPAIGN_ID,
                "campaign-intent-b",
            ),
            Intent(valid).setData(null),
            Intent(valid).setData(Uri.parse("${valid.data}/extra")),
            Intent(valid).setAction("com.aneb.probe.action.NOT_A_PROTOTYPE_CANCEL"),
        )
        invalid.forEach { intent ->
            assertNull(PrototypeCampaignCancelIntent.campaignIdOrNull(intent))
        }
        assertFalse(valid.data.toString().contains("campaign-intent-b"))
    }

    @Test
    fun publicCancelStartsTheSameCampaignBoundServiceIntent() {
        val application = RuntimeEnvironment.getApplication()

        PrototypeCampaignService.cancel(application, "campaign-public-cancel")

        assertEquals(
            "campaign-public-cancel",
            PrototypeCampaignCancelIntent.campaignIdOrNull(
                shadowOf(application).nextStartedService,
            ),
        )
    }

    private fun notification(campaignId: String?) = buildPrototypeCampaignNotification(
        context = context,
        campaignId = campaignId,
        title = "Prototype Quick",
        cancelLabel = "Cancel",
    )
}
