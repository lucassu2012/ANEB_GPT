package com.aneb.probe.ui.prototype

import com.aneb.probe.prototype.CompatibleNodeTicket
import com.aneb.probe.prototype.PrototypeCampaignConfig
import com.aneb.probe.prototype.PrototypeCampaignProgress
import com.aneb.probe.prototype.PrototypeCampaignSession
import kotlinx.coroutines.CancellationException

internal data class PrototypeCampaignUiInput(
    val nodeUrl: String,
    val nodeCompatible: Boolean,
    val checkingNode: Boolean,
    val otherRunActive: Boolean,
    val session: PrototypeCampaignSession,
    val progress: PrototypeCampaignProgress? = null,
)

internal data class PrototypeCampaignUiPresentation(
    val quickRunning: Boolean,
    val quickAvailable: Boolean,
    val showCancel: Boolean,
    val cancelEnabled: Boolean,
    val statusMessage: String?,
)

internal sealed interface PrototypeCampaignUiActionResult {
    data object Busy : PrototypeCampaignUiActionResult

    data object RequestNotificationPermission : PrototypeCampaignUiActionResult

    data object FreshNodeCheckRequired : PrototypeCampaignUiActionResult

    data class Started(
        val config: PrototypeCampaignConfig,
    ) : PrototypeCampaignUiActionResult

    data class LaunchFailed(
        val message: String,
    ) : PrototypeCampaignUiActionResult
}

internal class PrototypeCampaignUiController(
    private val ticketForStart: (String) -> CompatibleNodeTicket?,
    private val campaignIdFactory: () -> String,
    private val startCampaign: (PrototypeCampaignConfig) -> Unit,
    private val cancelCampaign: (String) -> Unit = {},
) {
    private var notificationPermissionPending = false
    private var pendingCampaignId: String? = null
    private var pendingCancellationCampaignId: String? = null

    fun requestStart(input: PrototypeCampaignUiInput): PrototypeCampaignUiActionResult {
        if (
            notificationPermissionPending ||
            pendingCampaignId != null ||
            input.otherRunActive ||
            input.session is PrototypeCampaignSession.Running ||
            input.session is PrototypeCampaignSession.Cancelling
        ) {
            return PrototypeCampaignUiActionResult.Busy
        }
        if (!input.nodeCompatible) {
            return PrototypeCampaignUiActionResult.FreshNodeCheckRequired
        }
        if (input.checkingNode) return PrototypeCampaignUiActionResult.Busy
        ticketForStart(input.nodeUrl)
            ?: return PrototypeCampaignUiActionResult.FreshNodeCheckRequired
        notificationPermissionPending = true
        return PrototypeCampaignUiActionResult.RequestNotificationPermission
    }

    fun continueStartAfterNotification(
        input: PrototypeCampaignUiInput,
    ): PrototypeCampaignUiActionResult {
        check(notificationPermissionPending) { "Prototype Quick start was not requested" }
        notificationPermissionPending = false
        if (
            input.otherRunActive ||
            input.session is PrototypeCampaignSession.Running ||
            input.session is PrototypeCampaignSession.Cancelling
        ) {
            return PrototypeCampaignUiActionResult.Busy
        }
        val ticket = ticketForStart(input.nodeUrl)
            ?: return PrototypeCampaignUiActionResult.FreshNodeCheckRequired
        val config = PrototypeCampaignConfig(
            nodeTicket = ticket,
            campaignId = campaignIdFactory(),
        )
        pendingCampaignId = config.campaignId
        return try {
            startCampaign(config)
            PrototypeCampaignUiActionResult.Started(config)
        } catch (cancelled: CancellationException) {
            if (pendingCampaignId == config.campaignId) pendingCampaignId = null
            throw cancelled
        } catch (failure: Exception) {
            if (pendingCampaignId == config.campaignId) pendingCampaignId = null
            PrototypeCampaignUiActionResult.LaunchFailed(
                failure.message ?: "Unable to start Prototype Quick campaign.",
            )
        }
    }

    fun observe(session: PrototypeCampaignSession) {
        when (session) {
            PrototypeCampaignSession.Idle -> Unit
            is PrototypeCampaignSession.Running -> {
                val campaignId = session.config.campaignId
                if (pendingCampaignId == campaignId) pendingCampaignId = null
                if (
                    pendingCancellationCampaignId != null &&
                    pendingCancellationCampaignId != campaignId
                ) {
                    pendingCancellationCampaignId = null
                }
            }
            is PrototypeCampaignSession.Cancelling -> {
                if (pendingCampaignId == session.config.campaignId) pendingCampaignId = null
            }
            is PrototypeCampaignSession.Finished -> clearMatchingPending(session.config.campaignId)
            is PrototypeCampaignSession.Failed -> clearMatchingPending(session.config.campaignId)
            is PrototypeCampaignSession.Cancelled -> clearMatchingPending(session.config.campaignId)
        }
    }

    fun requestCancel(session: PrototypeCampaignSession): Boolean {
        if (session !is PrototypeCampaignSession.Running) return false
        val campaignId = session.config.campaignId
        if (pendingCancellationCampaignId == campaignId) return false
        pendingCancellationCampaignId = campaignId
        try {
            cancelCampaign(campaignId)
        } catch (failure: Exception) {
            if (pendingCancellationCampaignId == campaignId) {
                pendingCancellationCampaignId = null
            }
            throw failure
        }
        return true
    }

    private fun clearMatchingPending(campaignId: String) {
        if (campaignId == pendingCampaignId) pendingCampaignId = null
        if (campaignId == pendingCancellationCampaignId) pendingCancellationCampaignId = null
    }

    fun presentation(input: PrototypeCampaignUiInput): PrototypeCampaignUiPresentation {
        val running = input.session is PrototypeCampaignSession.Running
        val cancelling = input.session is PrototypeCampaignSession.Cancelling
        val cancellationPending = running &&
            input.session.config.campaignId == pendingCancellationCampaignId
        val startPending = notificationPermissionPending || pendingCampaignId != null
        val quickRunning = running || cancelling || startPending
        val statusMessage = when (val session = input.session) {
            PrototypeCampaignSession.Idle -> if (startPending) "Starting Quick campaign…" else null
            is PrototypeCampaignSession.Running -> if (cancellationPending) {
                "Cancelling Quick campaign…"
            } else {
                progressMessage(session, input.progress) ?: "Quick campaign is running."
            }
            is PrototypeCampaignSession.Cancelling -> "Cancelling Quick campaign…"
            is PrototypeCampaignSession.Finished -> "Quick campaign finished."
            is PrototypeCampaignSession.Failed -> "Quick campaign failed: ${session.message}"
            is PrototypeCampaignSession.Cancelled -> "Quick campaign cancelled."
        }
        return PrototypeCampaignUiPresentation(
            quickRunning = quickRunning,
            quickAvailable = input.nodeCompatible &&
                !input.checkingNode &&
                !input.otherRunActive &&
                !quickRunning,
            showCancel = running || cancelling,
            cancelEnabled = running && !cancellationPending,
            statusMessage = statusMessage,
        )
    }

    private fun progressMessage(
        session: PrototypeCampaignSession.Running,
        progress: PrototypeCampaignProgress?,
    ): String? {
        if (progress?.campaignId != session.config.campaignId) return null
        val phase = when (progress) {
            is PrototypeCampaignProgress.Running -> {
                val condition = conditionLabel(progress.currentRunRef.conditionId) ?: return null
                "Running $condition"
            }

            is PrototypeCampaignProgress.Cooldown -> {
                val condition = conditionLabel(progress.nextRunRef.conditionId) ?: return null
                "Preparing $condition"
            }

            is PrototypeCampaignProgress.Saving -> "Saving local result…"
        }
        return "$phase · ${progress.processedRuns}/${progress.totalRuns} processed"
    }

    private fun conditionLabel(conditionId: String): String? = when (conditionId) {
        "baseline_v0.1" -> "Baseline"
        "slow_v0.1" -> "Slow"
        "unstable_v0.1" -> "Unstable"
        else -> null
    }
}
