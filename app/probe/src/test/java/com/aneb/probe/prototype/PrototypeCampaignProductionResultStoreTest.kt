package com.aneb.probe.prototype

import android.content.Context
import androidx.room.Room
import com.aneb.probe.data.AnebDatabase
import com.aneb.probe.data.PrototypeCampaignRoomRepository
import com.aneb.probe.net.AnebClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class PrototypeCampaignProductionResultStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun persistsAuthorityAndPublishesTheCanonicalRoomSnapshot() = runBlocking {
        val config = PrototypeCampaignPersistenceFixture.campaignConfig("campaign-production-publish")
        val result = PrototypeCampaignPersistenceFixture.completeQuickCampaign(config)
        val database = Room.inMemoryDatabaseBuilder(context, AnebDatabase::class.java).build()
        val repository = PrototypeCampaignRoomRepository(database)
        var observedUpload: String? = null
        val transport = PrototypeEvidenceTransport { _, canonicalUpload ->
            observedUpload = canonicalUpload
            AnebClient.HttpTextResult(
                httpCode = 200,
                body =
                    "{\"campaign_id\":\"${config.campaignId}\",\"manifest_sha256\":\"${"a".repeat(64)}\"," +
                        "\"publication_status\":\"verified\",\"schema_version\":" +
                        "\"aneb-prototype-publication-receipt-0.1\"}\n",
                error = null,
            )
        }
        val provider = PrototypeCampaignAuthorityProvider(
            PrototypeAndroidRuntimeCapture(
                sourceCommit = "b".repeat(40),
                androidVersionName = "0.5.14",
                androidVersionCode = 46,
                apkSha256 = "c".repeat(64),
                deviceManufacturer = "ANEB",
                deviceModel = "P40",
                deviceOsRelease = "12",
                deviceSdkInt = 35,
            ),
        )
        try {
            PrototypeCampaignProductionResultStore(repository, provider, transport).save(config, result)

            val stored = requireNotNull(repository.load(config.campaignId))
            assertNotNull(stored.captureAuthorityJson)
            assertTrue(stored.runs.all { it.runAuthorityJson != null })
            val upload = requireNotNull(observedUpload)
            assertTrue(upload.contains("\"schema_version\":\"aneb-prototype-upload-0.1\""))
            assertTrue(upload.contains("\"campaign_id\":\"${config.campaignId}\""))
            assertEquals(config.campaignId, stored.campaignId)
        } finally {
            database.close()
        }
    }

    @Test
    fun publicationFailureDoesNotRollBackTheLocalAuthorityGraph() = runBlocking {
        val config = PrototypeCampaignPersistenceFixture.campaignConfig("campaign-local-first-failure")
        val result = PrototypeCampaignPersistenceFixture.completeQuickCampaign(config)
        val database = Room.inMemoryDatabaseBuilder(context, AnebDatabase::class.java).build()
        val repository = PrototypeCampaignRoomRepository(database)
        val transport = PrototypeEvidenceTransport { _, _ ->
            AnebClient.HttpTextResult(
                httpCode = 500,
                body = "{\"code\":\"P018_EVIDENCE_PUBLICATION_FAILED\",\"ok\":false}\n",
                error = "http 500",
            )
        }
        try {
            try {
                PrototypeCampaignProductionResultStore(
                    repository,
                    authorityProvider(),
                    transport,
                ).save(config, result)
                fail("publication failure must be visible to the campaign owner")
            } catch (failure: IllegalStateException) {
                assertEquals("P018_EVIDENCE_PUBLICATION_FAILED", failure.message)
            }

            val stored = requireNotNull(repository.load(config.campaignId))
            assertNotNull(stored.captureAuthorityJson)
            assertTrue(stored.runs.all { it.runAuthorityJson != null })
        } finally {
            database.close()
        }
    }

    @Test
    fun partialCampaignIsPersistedAndPublishedWithItsPlannedSuffix() = runBlocking {
        val config = PrototypeCampaignPersistenceFixture.campaignConfig(
            "campaign-production-partial-publish",
        )
        val result = PrototypeCampaignPersistenceFixture.partialQuickCampaign(config)
        val database = Room.inMemoryDatabaseBuilder(context, AnebDatabase::class.java).build()
        val repository = PrototypeCampaignRoomRepository(database)
        var observedUpload: String? = null
        val transport = PrototypeEvidenceTransport { _, canonicalUpload ->
            observedUpload = canonicalUpload
            AnebClient.HttpTextResult(
                httpCode = 200,
                body =
                    "{\"campaign_id\":\"${config.campaignId}\",\"manifest_sha256\":\"${"a".repeat(64)}\"," +
                        "\"publication_status\":\"verified\",\"schema_version\":" +
                        "\"aneb-prototype-publication-receipt-0.1\"}\n",
                error = null,
            )
        }
        try {
            PrototypeCampaignProductionResultStore(
                repository,
                authorityProvider(),
                transport,
            ).save(config, result)

            val upload = requireNotNull(observedUpload)
            val payload = Json.parseToJsonElement(upload).jsonObject
            val meta = Json.parseToJsonElement(
                payload.getValue("meta_json").jsonPrimitive.content,
            ).jsonObject
            val runsCsv = payload.getValue("runs_csv").jsonPrimitive.content
            assertEquals("partial", meta.getValue("campaign_status").jsonPrimitive.content)
            assertTrue(runsCsv.contains("interrupted"))
            assertTrue(runsCsv.contains("not_started"))
            assertNotNull(repository.load(config.campaignId))
        } finally {
            database.close()
        }
    }

    private fun authorityProvider() = PrototypeCampaignAuthorityProvider(
        PrototypeAndroidRuntimeCapture(
            sourceCommit = "b".repeat(40),
            androidVersionName = "0.5.14",
            androidVersionCode = 46,
            apkSha256 = "c".repeat(64),
            deviceManufacturer = "ANEB",
            deviceModel = "P40",
            deviceOsRelease = "12",
            deviceSdkInt = 35,
        ),
    )
}
