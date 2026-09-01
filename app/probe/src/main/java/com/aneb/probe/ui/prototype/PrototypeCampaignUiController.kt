package com.aneb.probe.ui.prototype

import com.aneb.probe.prototype.CompatibleNodeTicket
import com.aneb.probe.prototype.PrototypeCampaignConfig
import com.aneb.probe.prototype.PrototypeCampaignProgress
import com.aneb.probe.prototype.PrototypeCampaignSession
import com.aneb.probe.prototype.PrototypeQuickCampaignRunner
import com.aneb.probe.prototype.PrototypeRunLivePhase
import kotlinx.coroutines.CancellationException
import java.util.Locale

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
    val liveExecution: PrototypeCampaignLiveExecutionPresentation?,
)

data class PrototypeCampaignLiveExecutionPresentation(
    val currentRunLabel: String,
    val completedRunsLabel: String,
    val phaseLabel: String,
    val ttftLabel: String?,
    val eventRateLabel: String?,
    val stallDetected: Boolean,
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
    private var pendingCampaignMode: PrototypeQuickCampaignRunner.CampaignMode? = null
    private var pendingCampaignId: String? = null
    private var pendingCancellationCampaignId: String? = null

    fun requestStart(
        input: PrototypeCampaignUiInput,
        mode: PrototypeQuickCampaignRunner.CampaignMode =
            PrototypeQuickCampaignRunner.CampaignMode.QUICK,
    ): PrototypeCampaignUiActionResult {
        if (
            pendingCampaignMode != null ||
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
        pendingCampaignMode = mode
        return PrototypeCampaignUiActionResult.RequestNotificationPermission
    }

    fun continueStartAfterNotification(
        input: PrototypeCampaignUiInput,
    ): PrototypeCampaignUiActionResult {
        val campaignMode = checkNotNull(pendingCampaignMode) {
            "Prototype campaign start was not requested"
        }
        pendingCampaignMode = null
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
            campaignMode = campaignMode,
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

    fun requestCancel(
        session: PrototypeCampaignSession,
        progress: PrototypeCampaignProgress? = null,
    ): Boolean {
        if (session !is PrototypeCampaignSession.Running) return false
        if (
            progress is PrototypeCampaignProgress.Saving &&
            progress.campaignId == session.config.campaignId
        ) {
            return false
        }
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
        val savingCompletedResult = running &&
            input.progress is PrototypeCampaignProgress.Saving &&
            input.progress.campaignId == input.session.config.campaignId
        val cancellationPending = running &&
            input.session.config.campaignId == pendingCancellationCampaignId
        val startPending = pendingCampaignMode != null || pendingCampaignId != null
        val quickRunning = running || cancelling || startPending
        val liveExecution = if (running && !cancellationPending) {
            liveExecutionPresentation(input.session, input.progress)
        } else {
            null
        }
        val statusMessage = when (val session = input.session) {
            PrototypeCampaignSession.Idle -> if (startPending) {
                "Starting ${campaignLabel(pendingCampaignMode)} campaign…"
            } else {
                null
            }
            is PrototypeCampaignSession.Running -> if (cancellationPending) {
                "Cancelling ${campaignLabel(session.config.campaignMode)} campaign…"
            } else {
                progressMessage(session, input.progress)
                    ?: "${campaignLabel(session.config.campaignMode)} campaign is running."
            }
            is PrototypeCampaignSession.Cancelling ->
                "Cancelling ${campaignLabel(session.config.campaignMode)} campaign…"
            is PrototypeCampaignSession.Finished ->
                "${campaignLabel(session.config.campaignMode)} campaign finished."
            is PrototypeCampaignSession.Failed ->
                "${campaignLabel(session.config.campaignMode)} campaign failed: ${session.message}"
            is PrototypeCampaignSession.Cancelled ->
                "${campaignLabel(session.config.campaignMode)} campaign cancelled · " +
                    "partial evidence saved."
        }
        return PrototypeCampaignUiPresentation(
            quickRunning = quickRunning,
            quickAvailable = input.nodeCompatible &&
                !input.checkingNode &&
                !input.otherRunActive &&
                !quickRunning,
            showCancel = (running && !savingCompletedResult) || cancelling,
            cancelEnabled = running && !cancellationPending && !savingCompletedResult,
            statusMessage = statusMessage,
            liveExecution = liveExecution,
        )
    }

    private fun liveExecutionPresentation(
        session: PrototypeCampaignSession.Running,
        progress: PrototypeCampaignProgress?,
    ): PrototypeCampaignLiveExecutionPresentation? {
        if (progress?.campaignId != session.config.campaignId) return null
        val currentRunLabel: String
        val phaseLabel: String
        val ttftLabel: String?
        val eventRateLabel: String?
        val stallDetected: Boolean
        when (progress) {
            is PrototypeCampaignProgress.Running -> {
                val condition = conditionLabel(progress.currentRunRef.conditionId) ?: return null
                currentRunLabel = liveRunLabel(session, condition, progress.currentRunRef.runIndex)
                phaseLabel = when (progress.live.phase) {
                    PrototypeRunLivePhase.CONNECTING -> "Connecting"
                    PrototypeRunLivePhase.WAITING_FOR_FIRST_EVENT -> "Waiting for first event"
                    PrototypeRunLivePhase.STREAMING -> "Streaming"
                    PrototypeRunLivePhase.FINALIZING -> "Finalizing"
                }
                ttftLabel = progress.live.ttftMs?.let { metricLabel(it, "ms") }
                eventRateLabel = progress.live.eventRateEps?.let { metricLabel(it, "events/s") }
                stallDetected = progress.live.stallObserved
            }
            is PrototypeCampaignProgress.Cooldown -> {
                val condition = conditionLabel(progress.nextRunRef.conditionId) ?: return null
                currentRunLabel = liveRunLabel(session, condition, progress.nextRunRef.runIndex)
                phaseLabel = "Preparing next run"
                ttftLabel = null
                eventRateLabel = null
                stallDetected = false
            }
            is PrototypeCampaignProgress.Saving -> {
                currentRunLabel = "Campaign"
                phaseLabel = "Saving"
                ttftLabel = null
                eventRateLabel = null
                stallDetected = false
            }
        }
        return PrototypeCampaignLiveExecutionPresentation(
            currentRunLabel = currentRunLabel,
            completedRunsLabel = "${progress.processedRuns} / ${progress.totalRuns} completed",
            phaseLabel = phaseLabel,
            ttftLabel = ttftLabel,
            eventRateLabel = eventRateLabel,
            stallDetected = stallDetected,
        )
    }

    private fun liveRunLabel(
        session: PrototypeCampaignSession.Running,
        condition: String,
        runIndex: Int,
    ): String {
        val runsPerCondition = session.config.campaignMode.runsPerCondition
        val occurrence = (runIndex - 1) / 3 + 1
        return "$condition — run $occurrence of $runsPerCondition"
    }

    private fun metricLabel(value: Double, unit: String): String? =
        if (value.isFinite() && value >= 0.0) {
            String.format(Locale.ROOT, "%.1f %s", value, unit)
        } else {
            null
        }

    private fun progressMessage(
        session: PrototypeCampaignSession.Running,
        progress: PrototypeCampaignProgress?,
    ): String? {
        if (progress?.campaignId != session.config.campaignId) return null
        val phase = when (progress) {
            is PrototypeCampaignProgress.Running -> {
                val condition = conditionLabel(progress.currentRunRef.conditionId) ?: return null
                "Running $condition${runOccurrence(session, progress.currentRunRef.runIndex)}"
            }

            is PrototypeCampaignProgress.Cooldown -> {
                val condition = conditionLabel(progress.nextRunRef.conditionId) ?: return null
                "Preparing $condition${runOccurrence(session, progress.nextRunRef.runIndex)}"
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

    private fun campaignLabel(mode: PrototypeQuickCampaignRunner.CampaignMode?): String =
        when (mode) {
            PrototypeQuickCampaignRunner.CampaignMode.ACCEPTANCE -> "Acceptance"
            PrototypeQuickCampaignRunner.CampaignMode.QUICK,
            null,
            -> "Quick"
        }

    private fun runOccurrence(session: PrototypeCampaignSession.Running, runIndex: Int): String =
        if (session.config.campaignMode == PrototypeQuickCampaignRunner.CampaignMode.ACCEPTANCE) {
            val occurrence = (runIndex - 1) / 3 + 1
            " — run $occurrence of 3"
        } else {
            ""
        }
}
