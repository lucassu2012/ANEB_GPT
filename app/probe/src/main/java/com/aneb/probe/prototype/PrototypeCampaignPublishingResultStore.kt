package com.aneb.probe.prototype

import com.aneb.probe.data.PrototypeCampaignRoomRepository
import com.aneb.probe.net.AnebClient

internal fun interface PrototypeCanonicalUploadLoader {
    suspend fun render(campaignId: String): String
}

internal fun interface PrototypeCanonicalEvidencePublisher {
    suspend fun publish(nodeBaseUrl: String, campaignId: String, canonicalUpload: String)
}

internal class PrototypeCampaignPublicationFailedWithResult(
    val result: PrototypeQuickCampaignRunner.CampaignResult,
    cause: Throwable,
) : IllegalStateException("P018_EVIDENCE_PUBLICATION_FAILED", cause)

/** Local-first persistence followed by publication of the canonical campaign evidence. */
internal class PrototypeCampaignPublishingResultStore(
    private val localStore: PrototypeCampaignResultStore,
    private val canonicalUploadLoader: PrototypeCanonicalUploadLoader,
    private val evidencePublisher: PrototypeCanonicalEvidencePublisher,
) : PrototypeCampaignResultStore {
    override suspend fun save(
        config: PrototypeCampaignConfig,
        result: PrototypeQuickCampaignRunner.CampaignResult,
    ) {
        localStore.save(config, result)
        try {
            val upload = canonicalUploadLoader.render(config.campaignId)
            evidencePublisher.publish(
                config.nodeTicket.nodeBaseUrl,
                config.campaignId,
                upload,
            )
        } catch (failure: PrototypeCampaignPublicationFailedWithResult) {
            throw failure
        } catch (failure: Exception) {
            throw PrototypeCampaignPublicationFailedWithResult(result, failure)
        }
    }
}

/** Production composition: authority-backed Room save, canonical render, then local-node publish. */
internal class PrototypeCampaignProductionResultStore(
    repository: PrototypeCampaignRoomRepository,
    authorityProvider: PrototypeCampaignAuthorityProvider,
    evidenceTransport: PrototypeEvidenceTransport,
) : PrototypeCampaignResultStore {
    constructor(
        repository: PrototypeCampaignRoomRepository,
        authorityProvider: PrototypeCampaignAuthorityProvider,
        client: AnebClient,
    ) : this(
        repository = repository,
        authorityProvider = authorityProvider,
        evidenceTransport = PrototypeEvidenceTransport(client::postPrototypeEvidence),
    )

    private val delegate = PrototypeCampaignPublishingResultStore(
        localStore = PrototypeCampaignAuthorityResultStore(authorityProvider) {
                config,
                result,
                campaignAuthorityJson,
                runAuthorityJsonByRunId,
            ->
            repository.saveWithAuthority(
                config,
                result,
                campaignAuthorityJson,
                runAuthorityJsonByRunId,
            )
        },
        canonicalUploadLoader = PrototypeCanonicalUploadLoader { campaignId ->
            val snapshot = repository.loadExportSnapshot(campaignId)
                ?: throw IllegalStateException(PUBLICATION_FAILED)
            PrototypeCanonicalUploadRenderer.render(snapshot)
        },
        evidencePublisher = PrototypeCanonicalEvidencePublisher { nodeBaseUrl, campaignId, upload ->
            PrototypeEvidencePublisher.publish(
                transport = evidenceTransport,
                nodeBaseUrl = nodeBaseUrl,
                expectedCampaignId = campaignId,
                canonicalUpload = upload,
            )
        },
    )

    override suspend fun save(
        config: PrototypeCampaignConfig,
        result: PrototypeQuickCampaignRunner.CampaignResult,
    ) = delegate.save(config, result)

    private companion object {
        const val PUBLICATION_FAILED = "P018_EVIDENCE_PUBLICATION_FAILED"
    }
}
