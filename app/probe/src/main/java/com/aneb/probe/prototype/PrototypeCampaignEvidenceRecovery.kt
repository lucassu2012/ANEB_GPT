package com.aneb.probe.prototype

import com.aneb.probe.data.PrototypeCampaignRoomRepository

/** Republishes a saved campaign without running or capturing it again. */
internal class PrototypeCampaignEvidenceRecovery(
    private val repository: PrototypeCampaignRoomRepository,
    private val transport: PrototypeEvidenceTransport,
) {
    suspend fun retry(campaignId: String): PrototypePublicationReceipt {
        val snapshot = repository.loadExportSnapshot(campaignId)
            ?: throw IllegalStateException("P018_EVIDENCE_PUBLICATION_FAILED")
        val upload = PrototypeCanonicalUploadRenderer.render(snapshot)
        return PrototypeEvidencePublisher.publish(
            transport = transport,
            nodeBaseUrl = snapshot.campaign.nodeBaseUrl,
            expectedCampaignId = campaignId,
            canonicalUpload = upload,
        )
    }
}
