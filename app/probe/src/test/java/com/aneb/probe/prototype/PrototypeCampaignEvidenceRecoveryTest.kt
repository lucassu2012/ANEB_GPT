package com.aneb.probe.prototype

import android.content.Context
import androidx.room.Room
import com.aneb.probe.data.AnebDatabase
import com.aneb.probe.data.PrototypeCampaignRoomRepository
import com.aneb.probe.net.AnebClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class PrototypeCampaignEvidenceRecoveryTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun interruptedCampaignCanPublishItsUnchangedSavedEvidenceAfterNodeRecovery() = runBlocking {
        val config = PrototypeCampaignPersistenceFixture.campaignConfig("campaign-recovery")
        val result = PrototypeCampaignPersistenceFixture.partialQuickCampaign(config)
        val database = Room.inMemoryDatabaseBuilder(context, AnebDatabase::class.java).build()
        val repository = PrototypeCampaignRoomRepository(database)
        var nodeAvailable = false
        val uploads = mutableListOf<Pair<String, String>>()
        val transport = PrototypeEvidenceTransport { url, body ->
            uploads += url to body
            if (!nodeAvailable) {
                AnebClient.HttpTextResult(httpCode = null, body = null, error = "connection refused")
            } else {
                AnebClient.HttpTextResult(
                    httpCode = 200,
                    body = "{\"campaign_id\":\"${config.campaignId}\",\"manifest_sha256\":\"${"a".repeat(64)}\"," +
                        "\"publication_status\":\"verified\",\"schema_version\":" +
                        "\"aneb-prototype-publication-receipt-0.1\"}\n",
                    error = null,
                )
            }
        }
        try {
            val failure = runCatching {
                PrototypeCampaignProductionResultStore(repository, authorityProvider(), transport)
                    .save(config, result)
            }.exceptionOrNull()
            assertTrue(failure is PrototypeCampaignPublicationFailedWithResult)
            val savedBeforeRecovery = requireNotNull(repository.loadExportSnapshot(config.campaignId))
            assertNotNull(savedBeforeRecovery.campaign.captureAuthorityJson)

            nodeAvailable = true
            val receipt = PrototypeCampaignEvidenceRecovery(repository, transport).retry(config.campaignId)

            assertEquals(config.campaignId, receipt.campaignId)
            assertEquals("a".repeat(64), receipt.manifestSha256)
            assertEquals(2, uploads.size)
            assertEquals(uploads.first(), uploads.last())
            assertEquals(
                "${config.nodeTicket.nodeBaseUrl}/api/v1/prototype/campaigns/evidence",
                uploads.last().first,
            )
            assertEquals(savedBeforeRecovery, repository.loadExportSnapshot(config.campaignId))
        } finally {
            database.close()
        }
    }

    @Test
    fun cancellationDuringRecoveryPreservesTheSavedEvidence() = runBlocking {
        val config = PrototypeCampaignPersistenceFixture.campaignConfig("campaign-recovery-cancel")
        val result = PrototypeCampaignPersistenceFixture.partialQuickCampaign(config)
        val database = Room.inMemoryDatabaseBuilder(context, AnebDatabase::class.java).build()
        val repository = PrototypeCampaignRoomRepository(database)
        val authority = authorityProvider().capture(config, result)
        val cancelled = CancellationException("result view was closed")
        var requests = 0
        try {
            repository.saveWithAuthority(
                config, result, authority.campaignAuthorityJson, authority.runAuthorityJsonByRunId,
            )
            val saved = repository.loadExportSnapshot(config.campaignId)
            val recovery = PrototypeCampaignEvidenceRecovery(repository, PrototypeEvidenceTransport { _, _ ->
                requests += 1
                throw cancelled
            })

            assertSame(cancelled, runCatching { recovery.retry(config.campaignId) }.exceptionOrNull())
            assertEquals(1, requests)
            assertEquals(saved, repository.loadExportSnapshot(config.campaignId))
        } finally {
            database.close()
        }
    }

    @Test
    fun missingCampaignOrCaptureAuthorityNeverCreatesAnUpload() = runBlocking {
        val config = PrototypeCampaignPersistenceFixture.campaignConfig("campaign-without-authority")
        val result = PrototypeCampaignPersistenceFixture.partialQuickCampaign(config)
        val database = Room.inMemoryDatabaseBuilder(context, AnebDatabase::class.java).build()
        val repository = PrototypeCampaignRoomRepository(database)
        var requests = 0
        val recovery = PrototypeCampaignEvidenceRecovery(repository, PrototypeEvidenceTransport { _, _ ->
            requests += 1
            error("missing evidence must not reach the network")
        })
        try {
            val missing = runCatching { recovery.retry(config.campaignId) }.exceptionOrNull()
            assertTrue(missing is IllegalStateException)
            assertEquals("P018_EVIDENCE_PUBLICATION_FAILED", missing?.message)
            assertNull(repository.load(config.campaignId))

            repository.save(config, result)
            val saved = requireNotNull(repository.loadExportSnapshot(config.campaignId))
            assertNull(saved.campaign.captureAuthorityJson)
            val missingAuthority = runCatching { recovery.retry(config.campaignId) }.exceptionOrNull()
            assertTrue(missingAuthority is IllegalArgumentException)
            assertEquals("prototype canonical upload authority is missing", missingAuthority?.message)
            assertEquals(saved, repository.loadExportSnapshot(config.campaignId))
            assertEquals(0, requests)
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
}
