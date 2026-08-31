package com.aneb.probe.ui.prototype

import android.content.Context
import androidx.room.Room
import com.aneb.probe.data.AnebDatabase
import com.aneb.probe.data.PrototypeCampaignRoomRepository
import com.aneb.probe.prototype.PrototypeCampaignPersistenceFixture
import com.aneb.probe.prototype.PrototypeCampaignSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class PrototypeCampaignResultNavigationTest {
    @Test
    fun `finished campaign routes by exact id and loads the validated Room result`() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, AnebDatabase::class.java).build()
        try {
            val repository = PrototypeCampaignRoomRepository(database)
            val config = PrototypeCampaignPersistenceFixture.campaignConfig(CAMPAIGN_ID)
            val result = PrototypeCampaignPersistenceFixture.completeQuickCampaign(config)
            repository.save(config, result)
            val loadedIds = mutableListOf<String>()
            val navigator = PrototypeCampaignResultNavigator { campaignId ->
                loadedIds += campaignId
                repository.load(campaignId)
            }

            val route = navigator.observe(
                PrototypeCampaignResultRouteState(),
                PrototypeCampaignSession.Finished(config, result),
            )
            val loaded = navigator.load(requireNotNull(route.openCampaignId))

            assertEquals(CAMPAIGN_ID, route.openCampaignId)
            assertEquals(listOf(CAMPAIGN_ID), loadedIds)
            assertTrue(loaded is PrototypeCampaignResultLoadState.Ready)
            loaded as PrototypeCampaignResultLoadState.Ready
            assertEquals(CAMPAIGN_ID, loaded.campaignId)
            assertEquals(CAMPAIGN_ID, loaded.presentation.campaignId)
        } finally {
            database.close()
        }
    }

    @Test
    fun `idle active failed and cancelled sessions never route to a result`() {
        val config = PrototypeCampaignPersistenceFixture.campaignConfig("campaign-result-non-finished")
        val navigator = PrototypeCampaignResultNavigator {
            error("non-Finished sessions must not load a campaign")
        }
        val sessions = listOf(
            PrototypeCampaignSession.Idle,
            PrototypeCampaignSession.Running(config),
            PrototypeCampaignSession.Cancelling(config),
            PrototypeCampaignSession.Failed(config, "save failed"),
            PrototypeCampaignSession.Cancelled(config),
        )

        sessions.forEach { session ->
            assertEquals(
                PrototypeCampaignResultRouteState(),
                navigator.observe(PrototypeCampaignResultRouteState(), session),
            )
        }
    }

    @Test
    fun `back dismissal survives recreation and does not reopen the same Finished campaign`() =
        runBlocking {
            val config = PrototypeCampaignPersistenceFixture.campaignConfig("campaign-result-dismissed")
            val result = PrototypeCampaignPersistenceFixture.completeQuickCampaign(config)
            val navigator = PrototypeCampaignResultNavigator { error("dismissal must not load") }
            val opened = navigator.observe(
                PrototypeCampaignResultRouteState(),
                PrototypeCampaignSession.Finished(config, result),
            )

            val dismissed = navigator.dismiss(opened, config.campaignId)
            val afterRecreation = navigator.observe(
                PrototypeCampaignResultRouteState(
                    openCampaignId = dismissed.openCampaignId,
                    dismissedFinishedCampaignId = dismissed.dismissedFinishedCampaignId,
                ),
                PrototypeCampaignSession.Finished(config, result),
            )

            assertEquals(null, dismissed.openCampaignId)
            assertEquals(config.campaignId, dismissed.dismissedFinishedCampaignId)
            assertEquals(null, afterRecreation.openCampaignId)
            assertEquals(config.campaignId, afterRecreation.dismissedFinishedCampaignId)
        }

    @Test
    fun `accepted new start suppresses the previous Finished campaign during the service handoff`() =
        runBlocking {
            val config = PrototypeCampaignPersistenceFixture.campaignConfig("campaign-result-start-gap")
            val result = PrototypeCampaignPersistenceFixture.completeQuickCampaign(config)
            val finished = PrototypeCampaignSession.Finished(config, result)
            val navigator = PrototypeCampaignResultNavigator { error("start suppression must not load") }
            val opened = navigator.observe(PrototypeCampaignResultRouteState(), finished)

            val suppressed = navigator.suppressForAcceptedStart(opened, finished)
            val whileServiceStillFinished = navigator.observe(suppressed, finished)

            assertEquals(null, suppressed.openCampaignId)
            assertEquals(config.campaignId, suppressed.dismissedFinishedCampaignId)
            assertEquals(null, whileServiceStillFinished.openCampaignId)
        }

    @Test
    fun `a newly Finished campaign opens after the previous campaign was dismissed`() = runBlocking {
        val firstConfig = PrototypeCampaignPersistenceFixture.campaignConfig("campaign-result-first")
        val secondConfig = PrototypeCampaignPersistenceFixture.campaignConfig("campaign-result-second")
        val firstFinished = PrototypeCampaignSession.Finished(
            firstConfig,
            PrototypeCampaignPersistenceFixture.completeQuickCampaign(firstConfig),
        )
        val secondFinished = PrototypeCampaignSession.Finished(
            secondConfig,
            PrototypeCampaignPersistenceFixture.completeQuickCampaign(secondConfig),
        )
        val navigator = PrototypeCampaignResultNavigator { error("route selection must not load") }
        val dismissedFirst = navigator.dismiss(
            navigator.observe(PrototypeCampaignResultRouteState(), firstFinished),
            firstConfig.campaignId,
        )

        val openedSecond = navigator.observe(dismissedFirst, secondFinished)

        assertEquals(secondConfig.campaignId, openedSecond.openCampaignId)
        assertEquals(firstConfig.campaignId, openedSecond.dismissedFinishedCampaignId)
    }

    @Test
    fun `missing or invalid persisted campaign is unavailable without an in-memory fallback`() =
        runBlocking {
            val campaignId = "campaign-result-unavailable"
            val navigators = listOf(
                PrototypeCampaignResultNavigator { null },
                PrototypeCampaignResultNavigator { throw IllegalArgumentException("invalid graph") },
            )

            navigators.forEach { navigator ->
                assertEquals(
                    PrototypeCampaignResultLoadState.Unavailable(campaignId),
                    navigator.load(campaignId),
                )
            }
        }

    @Test
    fun `activity wires saveable Finished-only routing to the validated Room loader`() {
        val source = activitySource()

        assertTrue(source.contains("data class PrototypeResult(val campaignId: String) : Screen"))
        assertTrue(source.contains("PrototypeCampaignRoomRepository(db)"))
        assertTrue(source.contains("PrototypeCampaignResultNavigator"))
        assertTrue(source.contains("var openPrototypeResultCampaignId by rememberSaveable"))
        assertTrue(source.contains("var dismissedFinishedCampaignId by rememberSaveable"))
        assertTrue(source.contains("prototypeCampaignResultNavigator.observe("))
        assertTrue(source.contains("PrototypeCampaignResultLoadState.Loading(campaignId)"))
        assertTrue(source.contains("prototypeCampaignResultNavigator.load(campaignId)"))
        assertTrue(source.contains("suppressForAcceptedStart("))
        assertTrue(source.contains("is Screen.PrototypeResult"))
        assertTrue(!source.contains("prototypeCampaignSession.result"))
    }

    private fun activitySource(): String {
        val path = listOf(
            Path.of("app/probe/src/main/java/com/aneb/probe/ui/MainActivity.kt"),
            Path.of("src/main/java/com/aneb/probe/ui/MainActivity.kt"),
            Path.of("../../app/probe/src/main/java/com/aneb/probe/ui/MainActivity.kt"),
        ).firstOrNull(Files::isRegularFile) ?: error("MainActivity source fixture was not found")
        return Files.readAllBytes(path).toString(UTF_8)
    }

    private companion object {
        const val CAMPAIGN_ID = "campaign-result-navigation-finished"
    }
}
