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
    this != PrototypeCampaignResultActionState.Exporting &&
        this != PrototypeCampaignResultActionState.PreparingShare

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
    ) -> Unit,
) {
    key(campaignId) {
        var actionState by remember(campaignId) {
            mutableStateOf(PrototypeCampaignResultActionState.Idle)
        }
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
        content(loadState, onBack, actionState, onExport, onShare)
    }
}
