package com.aneb.probe.ui.prototype

import com.aneb.probe.data.PrototypeCampaignRoomRepository
import com.aneb.probe.prototype.PrototypeCampaignSession
import kotlinx.coroutines.CancellationException

internal data class PrototypeCampaignResultRouteState(
    val openCampaignId: String? = null,
    val publicationWarning: String? = null,
    val dismissedFinishedCampaignId: String? = null,
)

internal sealed interface PrototypeCampaignResultLoadState {
    data class Loading(val campaignId: String) : PrototypeCampaignResultLoadState

    data class Ready(
        val campaignId: String,
        val presentation: PrototypeCampaignResultPresentation,
        val publicationWarning: String? = null,
    ) : PrototypeCampaignResultLoadState

    data class Unavailable(val campaignId: String) : PrototypeCampaignResultLoadState
}

internal fun PrototypeCampaignResultLoadState.withConfirmedPublication(): PrototypeCampaignResultLoadState =
    if (this is PrototypeCampaignResultLoadState.Ready) {
        copy(
            publicationWarning = null,
            presentation = presentation.copy(
                integrity = "Original node confirmed publication · device ZIP remains unverified",
            ),
        )
    } else {
        this
    }

internal class PrototypeCampaignResultNavigator(
    private val loadCampaign: suspend (String) -> PrototypeCampaignRoomRepository.StoredCampaign?,
) {
    fun observe(
        state: PrototypeCampaignResultRouteState,
        session: PrototypeCampaignSession,
    ): PrototypeCampaignResultRouteState {
        val (campaignId, publicationWarning) = when (session) {
            is PrototypeCampaignSession.Finished ->
                session.config.campaignId to session.publicationWarning
            is PrototypeCampaignSession.Cancelled ->
                session.config.campaignId to session.publicationWarning
            else -> return state
        }
        if (state.dismissedFinishedCampaignId == campaignId) return state
        return state.copy(
            openCampaignId = campaignId,
            publicationWarning = publicationWarning,
        )
    }

    fun dismiss(
        state: PrototypeCampaignResultRouteState,
        campaignId: String,
    ): PrototypeCampaignResultRouteState {
        require(state.openCampaignId == campaignId)
        return state.copy(
            openCampaignId = null,
            publicationWarning = null,
            dismissedFinishedCampaignId = campaignId,
        )
    }

    fun suppressForAcceptedStart(
        state: PrototypeCampaignResultRouteState,
        currentSession: PrototypeCampaignSession,
    ): PrototypeCampaignResultRouteState {
        val previousFinishedCampaignId = when (currentSession) {
            is PrototypeCampaignSession.Finished -> currentSession.config.campaignId
            is PrototypeCampaignSession.Cancelled -> currentSession.config.campaignId
            else -> null
        }
        return state.copy(
            openCampaignId = null,
            publicationWarning = null,
            dismissedFinishedCampaignId =
                previousFinishedCampaignId ?: state.dismissedFinishedCampaignId,
        )
    }

    suspend fun load(
        campaignId: String,
        publicationWarning: String? = null,
    ): PrototypeCampaignResultLoadState = try {
        val stored = loadCampaign(campaignId)
            ?: return PrototypeCampaignResultLoadState.Unavailable(campaignId)
        PrototypeCampaignResultLoadState.Ready(
            campaignId = campaignId,
            presentation = PrototypeCampaignResultPresenter.present(stored),
            publicationWarning = publicationWarning,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        PrototypeCampaignResultLoadState.Unavailable(campaignId)
    }
}
