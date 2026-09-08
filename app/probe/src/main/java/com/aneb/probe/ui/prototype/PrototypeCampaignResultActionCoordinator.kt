package com.aneb.probe.ui.prototype

import com.aneb.probe.prototype.PrototypeDeviceFallbackExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

internal class PrototypeCampaignResultActionCoordinator(
    private val exportCampaign: suspend (campaignId: String) ->
        PrototypeDeviceFallbackExporter.Outcome,
    private val openShare: (publishedUri: String) -> Boolean,
    private val ioDispatcher: CoroutineDispatcher,
    private val publishCampaign: suspend (campaignId: String) -> Unit = {
        error("P018_EVIDENCE_PUBLICATION_FAILED")
    },
) {
    suspend fun retryPublication(campaignId: String): PrototypeCampaignResultActionState = try {
        withContext(ioDispatcher) { publishCampaign(campaignId) }
        PrototypeCampaignResultActionState.Published
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        PrototypeCampaignResultActionState.PublicationFailed
    }

    suspend fun export(campaignId: String): PrototypeCampaignResultActionState =
        when (withContext(ioDispatcher) { exportCampaign(campaignId) }) {
            is PrototypeDeviceFallbackExporter.Outcome.Success ->
                PrototypeCampaignResultActionState.Saved
            PrototypeDeviceFallbackExporter.Outcome.Failed,
            PrototypeDeviceFallbackExporter.Outcome.Unavailable,
            -> PrototypeCampaignResultActionState.Failed
            PrototypeDeviceFallbackExporter.Outcome.Busy ->
                PrototypeCampaignResultActionState.Exporting
        }

    suspend fun share(campaignId: String): PrototypeCampaignResultActionState =
        when (val outcome = withContext(ioDispatcher) { exportCampaign(campaignId) }) {
            is PrototypeDeviceFallbackExporter.Outcome.Success ->
                if (openShare(outcome.uri)) {
                    PrototypeCampaignResultActionState.ShareOpened
                } else {
                    PrototypeCampaignResultActionState.ShareUnavailable
                }
            PrototypeDeviceFallbackExporter.Outcome.Failed,
            PrototypeDeviceFallbackExporter.Outcome.Unavailable,
            -> PrototypeCampaignResultActionState.Failed
            PrototypeDeviceFallbackExporter.Outcome.Busy ->
                PrototypeCampaignResultActionState.PreparingShare
        }
}
