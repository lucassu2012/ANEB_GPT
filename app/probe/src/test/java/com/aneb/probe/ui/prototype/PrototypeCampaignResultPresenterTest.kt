package com.aneb.probe.ui.prototype

import android.content.Context
import androidx.room.Room
import com.aneb.probe.data.AnebDatabase
import com.aneb.probe.data.PrototypeCampaignRoomRepository
import com.aneb.probe.prototype.PrototypeCampaignPersistenceFixture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class PrototypeCampaignResultPresenterTest {
    @Test
    fun `complete result is presented only from the validated stored campaign`() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, AnebDatabase::class.java).build()
        try {
            val repository = PrototypeCampaignRoomRepository(database)
            val config = PrototypeCampaignPersistenceFixture.campaignConfig(CAMPAIGN_ID)
            val result = PrototypeCampaignPersistenceFixture.completeQuickCampaign(config)
            repository.save(config, result)
            val stored = requireNotNull(repository.load(CAMPAIGN_ID))

            val presentation = PrototypeCampaignResultPresenter.present(stored)

            assertEquals(CAMPAIGN_ID, presentation.campaignId)
            assertEquals("Complete", presentation.status)
            assertEquals("Quick", presentation.campaignMode)
            assertEquals("3", presentation.attemptedRuns)
            assertEquals("3", presentation.successfulRuns)
            assertEquals("0", presentation.failedRuns)
            assertEquals("0", presentation.notStartedRuns)
            assertEquals(
                "Local campaign result saved · evidence bundle unverified",
                presentation.integrity,
            )
            assertEquals(
                "Relative Prototype Index (same-campaign synthetic comparison)",
                presentation.rpiLabel,
            )
            assertEquals(listOf("Baseline", "Slow", "Unstable"), presentation.conditions.map { it.title })
            val authoritativeBaseline = stored.summary.conditionSummaries.first()
            with(presentation.conditions.first()) {
                assertEquals("baseline_v0.1", conditionId)
                assertEquals(metric(authoritativeBaseline.medianTtftMs, " ms"), ttft)
                assertEquals(metric(authoritativeBaseline.medianCompletionMs, " ms"), completion)
                assertEquals(metric(authoritativeBaseline.medianStreamEventRateEps, " events/s"), eventRate)
                assertEquals(metric(authoritativeBaseline.medianStallCount), stallCount)
                assertEquals(metric(authoritativeBaseline.medianStallDurationMs, " ms"), stallDuration)
                assertEquals(percent(authoritativeBaseline.successRate), successRate)
                assertEquals("100", rpi)
                assertEquals("LOW", confidence)
                assertNull(metricNullReason)
                assertEquals(emptyList<String>(), rpiNullReasons)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `partial result keeps authoritative null zero and machine reasons distinct`() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, AnebDatabase::class.java).build()
        try {
            val repository = PrototypeCampaignRoomRepository(database)
            val config = PrototypeCampaignPersistenceFixture.campaignConfig(PARTIAL_CAMPAIGN_ID)
            val result = PrototypeCampaignPersistenceFixture.partialQuickCampaign(config)
            repository.save(config, result)
            val stored = requireNotNull(repository.load(PARTIAL_CAMPAIGN_ID))

            val presentation = PrototypeCampaignResultPresenter.present(stored)

            assertEquals("Partial", presentation.status)
            assertEquals("2", presentation.attemptedRuns)
            assertEquals("1", presentation.successfulRuns)
            assertEquals("1", presentation.failedRuns)
            assertEquals("1", presentation.notStartedRuns)
            with(presentation.conditions[0]) {
                assertEquals("0", stallCount)
                assertEquals("0 ms", stallDuration)
                assertEquals("—", rpi)
                assertNull(metricNullReason)
                assertEquals(listOf("campaign_incomplete"), rpiNullReasons)
            }
            with(presentation.conditions[1]) {
                assertEquals("—", ttft)
                assertEquals("—", completion)
                assertEquals("—", eventRate)
                assertEquals("—", stallCount)
                assertEquals("—", stallDuration)
                assertEquals("0%", successRate)
                assertEquals("—", rpi)
                assertEquals("NONE", confidence)
                assertEquals("stream_interrupted", metricNullReason)
                assertEquals(
                    listOf(
                        "campaign_incomplete",
                        "no_successful_condition_run",
                        "mandatory_metric_missing",
                    ),
                    rpiNullReasons,
                )
            }
            with(presentation.conditions[2]) {
                assertEquals("—", ttft)
                assertEquals("0%", successRate)
                assertEquals("—", rpi)
                assertEquals("not_started", metricNullReason)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `condition cards preserve frozen identities and the complete claim boundary`() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, AnebDatabase::class.java).build()
        try {
            val repository = PrototypeCampaignRoomRepository(database)
            val config = PrototypeCampaignPersistenceFixture.campaignConfig(CLAIM_CAMPAIGN_ID)
            repository.save(config, PrototypeCampaignPersistenceFixture.completeQuickCampaign(config))
            val stored = requireNotNull(repository.load(CLAIM_CAMPAIGN_ID))

            val presentation = PrototypeCampaignResultPresenter.present(stored)

            assertEquals("Synthetic application-layer condition", presentation.evidenceBadge)
            assertEquals(
                "Confidence is evidence completeness for this Quick campaign, " +
                    "not an industry or network confidence interval.",
                presentation.confidenceExplanation,
            )
            assertEquals(
                listOf(
                    "baseline_v0.1" to "Baseline",
                    "slow_v0.1" to "Slow",
                    "unstable_v0.1" to "Unstable",
                ),
                presentation.conditions.map { condition -> condition.conditionId to condition.title },
            )
            assertEquals(
                "This score compares deterministic application-layer conditions against this campaign's Baseline. " +
                    "It is not a formal ANEB industry score and does not represent a third-party AI application's " +
                    "network requirement. These results are synthetic application-layer measurements from this " +
                    "local probe and do not measure or represent packet loss, RAN, core network, operator, public " +
                    "Internet, a real third-party AI app, model inference, AQS, MOS, network quality, an SLA, or a grade.",
                presentation.disclosure,
            )
            listOf("Excellent", "Good", "Poor").forEach { forbiddenGrade ->
                assertFalse(presentation.disclosure.contains(forbiddenGrade))
            }
        } finally {
            database.close()
        }
    }

    private fun metric(value: Double?, suffix: String = ""): String = value?.let {
        String.format(Locale.ROOT, "%.6f", it).trimEnd('0').trimEnd('.') + suffix
    } ?: "—"

    private fun percent(value: Double): String = metric(value * 100.0, "%")

    private companion object {
        const val CAMPAIGN_ID = "campaign-result-presenter-complete"
        const val PARTIAL_CAMPAIGN_ID = "campaign-result-presenter-partial"
        const val CLAIM_CAMPAIGN_ID = "campaign-result-presenter-claims"
    }
}
