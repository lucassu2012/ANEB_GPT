package com.aneb.probe.ui.prototype

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

internal fun PrototypeCampaignResultActionState.canStartResultAction(): Boolean =
    prototypeCampaignResultActionPresentation(this).actionsEnabled

@Composable
internal fun PrototypeCampaignResultRoute(
    campaignId: String,
    loadState: PrototypeCampaignResultLoadState,
    coordinator: PrototypeCampaignResultActionCoordinator,
    onBack: () -> Unit,
    content: @Composable (
        PrototypeCampaignResultLoadState,
        () -> Unit,
        PrototypeCampaignResultActionState,
        () -> Unit,
        () -> Unit,
        () -> Unit,
    ) -> Unit,
) {
    key(campaignId) {
        var actionState by remember(campaignId) {
            mutableStateOf(PrototypeCampaignResultActionState.Idle)
        }
        // Acknowledgement belongs to this result view, not to the mutable export action state.
        // Reopening a result reverts to unverified until the original node confirms again.
        var publicationConfirmed by remember(campaignId) { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val onExport: () -> Unit = {
            if (actionState.canStartResultAction()) {
                actionState = PrototypeCampaignResultActionState.Exporting
                scope.launch {
                    actionState = coordinator.export(campaignId)
                }
            }
        }
        val onShare: () -> Unit = {
            if (actionState.canStartResultAction()) {
                actionState = PrototypeCampaignResultActionState.PreparingShare
                scope.launch {
                    actionState = coordinator.share(campaignId)
                }
            }
        }
        val onRetryPublication: () -> Unit = {
            if (actionState.canStartResultAction()) {
                publicationConfirmed = false
                actionState = PrototypeCampaignResultActionState.Publishing
                scope.launch {
                    actionState = coordinator.retryPublication(campaignId)
                    publicationConfirmed = actionState == PrototypeCampaignResultActionState.Published
                }
            }
        }
        val displayedLoadState = if (publicationConfirmed) loadState.withConfirmedPublication() else loadState
        content(displayedLoadState, onBack, actionState, onExport, onShare, onRetryPublication)
    }
}
