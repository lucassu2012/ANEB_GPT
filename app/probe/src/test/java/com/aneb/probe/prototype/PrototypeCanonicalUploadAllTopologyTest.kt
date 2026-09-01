package com.aneb.probe.prototype

import android.content.Context
import androidx.room.Room
import com.aneb.probe.data.AnebDatabase
import com.aneb.probe.data.PrototypeCampaignRoomRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class PrototypeCanonicalUploadAllTopologyTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun completeAcceptanceRendersAllNinePlannedRuns() = runBlocking {
        val config = PrototypeCampaignPersistenceFixture.campaignConfig(
            "campaign-canonical-upload-acceptance",
        )
        val result = PrototypeCampaignPersistenceFixture.completeAcceptanceCampaign(config)

        val payload = renderThroughRoom(config, result)
        val meta = Json.parseToJsonElement(payload.string("meta_json")).jsonObject
        val runLines = payload.string("runs_csv").trimEnd('\n').lines()

        assertEquals("acceptance", meta.string("campaign_mode"))
        assertEquals(9, meta.getValue("run_plan").jsonObject.getValue("planned_runs").jsonPrimitive.content.toInt())
        assertEquals(10, runLines.size)
        assertEquals(
            listOf(
                "baseline_v0.1", "slow_v0.1", "unstable_v0.1",
                "baseline_v0.1", "slow_v0.1", "unstable_v0.1",
                "baseline_v0.1", "slow_v0.1", "unstable_v0.1",
            ),
            runLines.drop(1).map { row -> row.split(',')[6] },
        )
        assertTrue(runLines.drop(1).all { row -> row.split(',')[9] == "complete" })
    }

    @Test
    fun interruptedQuickRendersTheFailureAndNullableNotStartedSuffix() = runBlocking {
        val config = PrototypeCampaignPersistenceFixture.campaignConfig(
            "campaign-canonical-upload-interrupted",
        )
        val result = PrototypeCampaignPersistenceFixture.partialQuickCampaign(config)

        val payload = renderThroughRoom(config, result)
        val meta = Json.parseToJsonElement(payload.string("meta_json")).jsonObject
        val runRows = payload.string("runs_csv").trimEnd('\n').lines().drop(1).map { row ->
            row.split(',')
        }
        val eventLines = payload.string("events_jsonl").trimEnd('\n').lines()

        assertEquals("partial", meta.string("campaign_status"))
        assertEquals(listOf("complete", "interrupted", "not_started"), runRows.map { it[9] })
        assertEquals("run_failed", Json.parseToJsonElement(eventLines.last()).jsonObject.string("event_type"))
        assertEquals("", runRows[2][14])
        assertEquals("", runRows[2][15])
        assertEquals("", runRows[2][16])
        assertTrue(runRows[2].subList(19, 27).all(String::isEmpty))
        assertEquals("", runRows[2][28])
        assertEquals("false", runRows[2][29])
        assertEquals("not_started", runRows[2][30])
    }

    @Test
    fun cancelledQuickRendersTheCancellationAndNullableNotStartedSuffix() = runBlocking {
        val config = PrototypeCampaignPersistenceFixture.campaignConfig(
            "campaign-canonical-upload-cancelled",
        )
        val result = PrototypeCampaignPersistenceFixture.cancelledQuickCampaign(config)

        val payload = renderThroughRoom(config, result)
        val meta = Json.parseToJsonElement(payload.string("meta_json")).jsonObject
        val runRows = payload.string("runs_csv").trimEnd('\n').lines().drop(1).map { row ->
            row.split(',')
        }
        val eventLines = payload.string("events_jsonl").trimEnd('\n').lines()

        assertEquals("cancelled", meta.string("campaign_status"))
        assertEquals(listOf("cancelled", "not_started", "not_started"), runRows.map { it[9] })
        assertEquals("run_cancelled", Json.parseToJsonElement(eventLines.last()).jsonObject.string("event_type"))
        assertTrue(runRows.drop(1).all { row -> row.subList(14, 17).all(String::isEmpty) })
        assertTrue(runRows.drop(1).all { row -> row.subList(19, 27).all(String::isEmpty) })
        assertTrue(runRows.drop(1).all { row -> row[28].isEmpty() })
    }

    @Test
    fun cancellationBeforeDispatchRendersEveryPlannedRunAsNotStarted() = runBlocking {
        val config = PrototypeCampaignPersistenceFixture.campaignConfig(
            "campaign-canonical-upload-all-not-started",
        )
        val result = PrototypeCampaignPersistenceFixture
            .cancelledBeforeFirstFrameQuickCampaign(config)

        val payload = renderThroughRoom(config, result)
        val meta = Json.parseToJsonElement(payload.string("meta_json")).jsonObject
        val runRows = payload.string("runs_csv").trimEnd('\n').lines().drop(1).map { row ->
            row.split(',')
        }
        val summaryRows = payload.string("summary_csv").trimEnd('\n').lines().drop(1).map { row ->
            row.split(',')
        }

        assertEquals("partial", meta.string("campaign_status"))
        assertEquals("", payload.string("events_jsonl"))
        assertEquals(List(3) { "not_started" }, runRows.map { row -> row[9] })
        assertTrue(runRows.all { row -> row.subList(14, 17).all(String::isEmpty) })
        assertTrue(runRows.all { row -> row.subList(19, 27).all(String::isEmpty) })
        assertTrue(runRows.all { row -> row[28].isEmpty() })
        assertTrue(runRows.all { row -> row[29] == "false" && row[30] == "not_started" })
        assertTrue(summaryRows.all { row -> row[6] == "0" && row[9] == "1" })
        assertTrue(summaryRows.all { row -> row[22].isEmpty() })
    }

    @Test
    fun cancellationAfterAllContentStillRendersCancelledWithoutAReceipt() = runBlocking {
        val config = PrototypeCampaignPersistenceFixture.campaignConfig(
            "campaign-canonical-upload-cancelled-after-content",
        )
        val result = PrototypeCampaignPersistenceFixture.cancelledQuickCampaign(
            config = config,
            contentCount = 120,
        )

        val payload = renderThroughRoom(config, result)
        val runRows = payload.string("runs_csv").trimEnd('\n').lines().drop(1).map { row ->
            row.split(',')
        }
        val eventLines = payload.string("events_jsonl").trimEnd('\n').lines()

        assertEquals(listOf("cancelled", "not_started", "not_started"), runRows.map { it[9] })
        assertEquals("120", runRows.first()[18])
        assertEquals("", runRows.first()[20])
        assertEquals("", runRows.first()[28])
        assertEquals(122, eventLines.size)
        assertEquals("run_cancelled", Json.parseToJsonElement(eventLines.last()).jsonObject.string("event_type"))
        assertTrue(eventLines.none { line ->
            Json.parseToJsonElement(line).jsonObject.string("event_type") == "terminal_event"
        })
    }

    @Test
    fun invalidSequenceAcceptanceRendersAllNinePlannedSlots() = runBlocking {
        val config = PrototypeCampaignPersistenceFixture.campaignConfig(
            "campaign-canonical-upload-invalid-acceptance",
        )
        val result = PrototypeCampaignPersistenceFixture.invalidSequenceAcceptanceCampaign(config)

        val payload = renderThroughRoom(config, result)
        val meta = Json.parseToJsonElement(payload.string("meta_json")).jsonObject
        val runRows = payload.string("runs_csv").trimEnd('\n').lines().drop(1).map { row ->
            row.split(',')
        }
        val summaryRows = payload.string("summary_csv").trimEnd('\n').lines().drop(1)
        val eventLines = payload.string("events_jsonl").trimEnd('\n').lines()

        assertEquals("acceptance", meta.string("campaign_mode"))
        assertEquals("partial", meta.string("campaign_status"))
        assertEquals(9, runRows.size)
        assertEquals(List(7) { "complete" } + listOf("invalid_sequence", "not_started"), runRows.map { it[9] })
        assertEquals("run_failed", Json.parseToJsonElement(eventLines.last()).jsonObject.string("event_type"))
        assertTrue(summaryRows.all { row -> row.split(',')[22].isEmpty() })
    }

    private suspend fun renderThroughRoom(
        config: PrototypeCampaignConfig,
        result: PrototypeQuickCampaignRunner.CampaignResult,
    ): kotlinx.serialization.json.JsonObject {
        val database = Room.inMemoryDatabaseBuilder(context, AnebDatabase::class.java).build()
        try {
            val repository = PrototypeCampaignRoomRepository(database)
            val authority = authorityProvider().capture(config, result)
            repository.saveWithAuthority(
                config = config,
                result = result,
                captureAuthorityJson = authority.campaignAuthorityJson,
                runAuthorityJsonByRunId = authority.runAuthorityJsonByRunId,
            )
            val snapshot = requireNotNull(repository.loadExportSnapshot(config.campaignId))
            return Json.parseToJsonElement(
                PrototypeCanonicalUploadRenderer.render(snapshot),
            ).jsonObject
        } finally {
            database.close()
        }
    }

    private fun authorityProvider() = PrototypeCampaignAuthorityProvider(
        PrototypeAndroidRuntimeCapture(
            sourceCommit = "b".repeat(40),
            androidVersionName = "0.2.0",
            androidVersionCode = 20,
            apkSha256 = "c".repeat(64),
            deviceManufacturer = "ANEB",
            deviceModel = "P40",
            deviceOsRelease = "12",
            deviceSdkInt = 35,
        ),
    )

    private fun kotlinx.serialization.json.JsonObject.string(key: String): String =
        getValue(key).jsonPrimitive.content
}
