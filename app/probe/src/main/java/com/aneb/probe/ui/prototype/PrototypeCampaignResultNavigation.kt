package com.aneb.probe.ui.prototype

import com.aneb.probe.data.PrototypeCampaignRoomRepository
import com.aneb.probe.prototype.PrototypeCampaignSession
import kotlinx.coroutines.CancellationException

internal data class PrototypeCampaignResultRouteState(
    val openCampaignId: String? = null,
    val dismissedFinishedCampaignId: String? = null,
)

internal sealed interface PrototypeCampaignResultLoadState {
    data class Loading(val campaignId: String) : PrototypeCampaignResultLoadState

    data class Ready(
        val campaignId: String,
        val presentation: PrototypeCampaignResultPresentation,
    ) : PrototypeCampaignResultLoadState

    data class Unavailable(val campaignId: String) : PrototypeCampaignResultLoadState
}

internal class PrototypeCampaignResultNavigator(
    private val loadCampaign: suspend (String) -> PrototypeCampaignRoomRepository.StoredCampaign?,
) {
    fun observe(
        state: PrototypeCampaignResultRouteState,
        session: PrototypeCampaignSession,
    ): PrototypeCampaignResultRouteState {
        if (session !is PrototypeCampaignSession.Finished) return state
        val campaignId = session.config.campaignId
        if (state.dismissedFinishedCampaignId == campaignId) return state
        return state.copy(openCampaignId = campaignId)
    }

    fun dismiss(
        state: PrototypeCampaignResultRouteState,
        campaignId: String,
    ): PrototypeCampaignResultRouteState {
        require(state.openCampaignId == campaignId)
        return state.copy(
            openCampaignId = null,
            dismissedFinishedCampaignId = campaignId,
        )
    }

    fun suppressForAcceptedStart(
        state: PrototypeCampaignResultRouteState,
        currentSession: PrototypeCampaignSession,
    ): PrototypeCampaignResultRouteState {
        val previousFinishedCampaignId =
            (currentSession as? PrototypeCampaignSession.Finished)?.config?.campaignId
        return state.copy(
            openCampaignId = null,
            dismissedFinishedCampaignId =
                previousFinishedCampaignId ?: state.dismissedFinishedCampaignId,
        )
    }

    suspend fun load(campaignId: String): PrototypeCampaignResultLoadState = try {
        val stored = loadCampaign(campaignId)
            ?: return PrototypeCampaignResultLoadState.Unavailable(campaignId)
        PrototypeCampaignResultLoadState.Ready(
            campaignId = campaignId,
            presentation = PrototypeCampaignResultPresenter.present(stored),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        PrototypeCampaignResultLoadState.Unavailable(campaignId)
    }
}
