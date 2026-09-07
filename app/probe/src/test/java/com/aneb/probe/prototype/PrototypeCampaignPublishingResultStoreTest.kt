package com.aneb.probe.prototype

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class PrototypeCampaignPublishingResultStoreTest {
    @Test
    fun completeQuickIsSavedLocallyBeforeCanonicalEvidenceIsPublished() = runBlocking {
        val config = PrototypeCampaignPersistenceFixture.campaignConfig("campaign-publishing-store")
        val result = PrototypeCampaignPersistenceFixture.completeQuickCampaign(config)
        val steps = mutableListOf<String>()
        val store = PrototypeCampaignPublishingResultStore(
            localStore = PrototypeCampaignResultStore { receivedConfig, receivedResult ->
                assertEquals(config, receivedConfig)
                assertEquals(result, receivedResult)
                steps += "local"
            },
            canonicalUploadLoader = PrototypeCanonicalUploadLoader { campaignId ->
                assertEquals(config.campaignId, campaignId)
                steps += "render"
                "canonical-upload"
            },
            evidencePublisher = PrototypeCanonicalEvidencePublisher { nodeBaseUrl, campaignId, upload ->
                assertEquals(config.nodeTicket.nodeBaseUrl, nodeBaseUrl)
                assertEquals(config.campaignId, campaignId)
                assertEquals("canonical-upload", upload)
                steps += "publish"
            },
        )

        store.save(config, result)

        assertEquals(listOf("local", "render", "publish"), steps)
    }

    @Test
    fun publicationFailureCarriesTheAlreadySavedResult() = runBlocking {
        val config = PrototypeCampaignPersistenceFixture.campaignConfig(
            "campaign-publishing-failure-carrier",
        )
        val result = PrototypeCampaignPersistenceFixture.completeQuickCampaign(config)
        val publicationCause = IllegalStateException("node rejected evidence")
        val steps = mutableListOf<String>()
        val store = PrototypeCampaignPublishingResultStore(
            localStore = PrototypeCampaignResultStore { _, _ -> steps += "local" },
            canonicalUploadLoader = PrototypeCanonicalUploadLoader {
                steps += "render"
                "canonical-upload"
            },
            evidencePublisher = PrototypeCanonicalEvidencePublisher { _, _, _ ->
                steps += "publish"
                throw publicationCause
            },
        )

        try {
            store.save(config, result)
            fail("publication failure must carry the durable campaign result")
        } catch (failure: PrototypeCampaignPublicationFailedWithResult) {
            assertEquals("P018_EVIDENCE_PUBLICATION_FAILED", failure.message)
            assertSame(result, failure.result)
            assertSame(publicationCause, failure.cause)
        }
        assertEquals(listOf("local", "render", "publish"), steps)
    }

    @Test
    fun completeAcceptanceIsPublishedAfterTheLocalSave() = runBlocking {
        val config = PrototypeCampaignPersistenceFixture.campaignConfig(
            "campaign-publishing-acceptance",
        )
        val result = PrototypeCampaignPersistenceFixture.completeAcceptanceCampaign(config)
        val steps = mutableListOf<String>()
        val store = PrototypeCampaignPublishingResultStore(
            localStore = PrototypeCampaignResultStore { _, _ -> steps += "local" },
            canonicalUploadLoader = PrototypeCanonicalUploadLoader {
                steps += "render"
                "canonical-acceptance-upload"
            },
            evidencePublisher = PrototypeCanonicalEvidencePublisher { _, _, upload ->
                assertEquals("canonical-acceptance-upload", upload)
                steps += "publish"
            },
        )

        store.save(config, result)

        assertEquals(listOf("local", "render", "publish"), steps)
    }

    @Test
    fun localSaveFailureIsNotMisreportedAsAPublicationFailure() = runBlocking {
        val config = PrototypeCampaignPersistenceFixture.campaignConfig(
            "campaign-local-save-failure-boundary",
        )
        val result = PrototypeCampaignPersistenceFixture.completeQuickCampaign(config)
        val localCause = IllegalStateException("Room transaction failed")
        var renderCalled = false
        val store = PrototypeCampaignPublishingResultStore(
            localStore = PrototypeCampaignResultStore { _, _ -> throw localCause },
            canonicalUploadLoader = PrototypeCanonicalUploadLoader {
                renderCalled = true
                "must-not-render"
            },
            evidencePublisher = PrototypeCanonicalEvidencePublisher { _, _, _ ->
                fail("must not publish when the local save failed")
            },
        )

        try {
            store.save(config, result)
            fail("local save failure must remain visible")
        } catch (failure: IllegalStateException) {
            assertSame(localCause, failure)
        }
        assertEquals(false, renderCalled)
    }

    @Test
    fun canonicalRenderFailureCarriesTheAlreadySavedResult() = runBlocking {
        val config = PrototypeCampaignPersistenceFixture.campaignConfig(
            "campaign-render-failure-carrier",
        )
        val result = PrototypeCampaignPersistenceFixture.completeQuickCampaign(config)
        val renderCause = IllegalArgumentException("canonical graph is invalid")
        val steps = mutableListOf<String>()
        val store = PrototypeCampaignPublishingResultStore(
            localStore = PrototypeCampaignResultStore { _, _ -> steps += "local" },
            canonicalUploadLoader = PrototypeCanonicalUploadLoader {
                steps += "render"
                throw renderCause
            },
            evidencePublisher = PrototypeCanonicalEvidencePublisher { _, _, _ ->
                fail("must not publish when canonical rendering failed")
            },
        )

        try {
            store.save(config, result)
            fail("render failure must carry the durable campaign result")
        } catch (failure: PrototypeCampaignPublicationFailedWithResult) {
            assertEquals("P018_EVIDENCE_PUBLICATION_FAILED", failure.message)
            assertSame(result, failure.result)
            assertSame(renderCause, failure.cause)
        }
        assertEquals(listOf("local", "render"), steps)
    }

    @Test
    fun interruptedCancelledAndInvalidCampaignsAllReachPublication() = runBlocking {
        val interruptedConfig = PrototypeCampaignPersistenceFixture.campaignConfig(
            "campaign-publish-interrupted",
        )
        val cancelledConfig = PrototypeCampaignPersistenceFixture.campaignConfig(
            "campaign-publish-cancelled",
        )
        val invalidConfig = PrototypeCampaignPersistenceFixture.campaignConfig(
            "campaign-publish-invalid",
        )
        val campaigns = listOf(
            interruptedConfig to PrototypeCampaignPersistenceFixture.partialQuickCampaign(
                interruptedConfig,
            ),
            cancelledConfig to PrototypeCampaignPersistenceFixture.cancelledQuickCampaign(
                cancelledConfig,
            ),
            invalidConfig to PrototypeCampaignPersistenceFixture.invalidSequenceQuickCampaign(
                invalidConfig,
            ),
        )

        campaigns.forEach { (config, result) ->
            val steps = mutableListOf<String>()
            val store = PrototypeCampaignPublishingResultStore(
                localStore = PrototypeCampaignResultStore { _, _ -> steps += "local" },
                canonicalUploadLoader = PrototypeCanonicalUploadLoader {
                    steps += "render"
                    "canonical-${config.campaignId}"
                },
                evidencePublisher = PrototypeCanonicalEvidencePublisher { _, _, _ ->
                    steps += "publish"
                },
            )

            store.save(config, result)

            assertEquals(config.campaignId, listOf("local", "render", "publish"), steps)
        }
    }
}
