package com.aneb.probe.ui.prototype

import com.aneb.probe.data.PrototypeCampaignRoomRepository
import com.aneb.probe.prototype.PrototypeQuickCampaignRunner
import java.util.Locale

internal data class PrototypeCampaignResultPresentation(
    val campaignId: String,
    val status: String,
    val campaignMode: String,
    val attemptedRuns: String,
    val successfulRuns: String,
    val failedRuns: String,
    val notStartedRuns: String,
    val integrity: String,
    val evidenceBadge: String,
    val confidenceExplanation: String,
    val rpiLabel: String,
    val disclosure: String,
    val conditions: List<PrototypeConditionResultPresentation>,
)

internal data class PrototypeConditionResultPresentation(
    val conditionId: String,
    val title: String,
    val ttft: String,
    val completion: String,
    val eventRate: String,
    val stallCount: String,
    val stallDuration: String,
    val successRate: String,
    val rpi: String,
    val confidence: String,
    val metricNullReason: String?,
    val rpiNullReasons: List<String>,
)

internal object PrototypeCampaignResultPresenter {
    fun present(
        stored: PrototypeCampaignRoomRepository.StoredCampaign,
    ): PrototypeCampaignResultPresentation {
        val summary = stored.summary
        require(summary.conditionSummaries.map { it.conditionId } == CONDITION_IDS)
        require(stored.runs.map { it.conditionId } == CONDITION_IDS)

        return PrototypeCampaignResultPresentation(
            campaignId = stored.campaignId,
            status = when (summary.status) {
                PrototypeQuickCampaignRunner.CampaignStatus.COMPLETE -> "Complete"
                PrototypeQuickCampaignRunner.CampaignStatus.PARTIAL -> "Partial"
            },
            campaignMode = "Quick",
            attemptedRuns = summary.attemptedRuns.toString(),
            successfulRuns = summary.successfulRuns.toString(),
            failedRuns = summary.failedRuns.toString(),
            notStartedRuns = summary.notStartedRuns.toString(),
            integrity = "Local campaign result saved · evidence bundle unverified",
            evidenceBadge = "Synthetic application-layer condition",
            confidenceExplanation =
                "Confidence is evidence completeness for this Quick campaign, " +
                    "not an industry or network confidence interval.",
            rpiLabel = "Relative Prototype Index (same-campaign synthetic comparison)",
            disclosure = DISCLOSURE,
            conditions = summary.conditionSummaries.zip(stored.runs).mapIndexed { index, pair ->
                val condition = pair.first
                val run = pair.second
                PrototypeConditionResultPresentation(
                    conditionId = condition.conditionId,
                    title = CONDITION_TITLES[index],
                    ttft = metric(condition.medianTtftMs, " ms"),
                    completion = metric(condition.medianCompletionMs, " ms"),
                    eventRate = metric(condition.medianStreamEventRateEps, " events/s"),
                    stallCount = metric(condition.medianStallCount),
                    stallDuration = metric(condition.medianStallDurationMs, " ms"),
                    successRate = metric(condition.successRate * 100.0, "%"),
                    rpi = condition.rpi?.toString() ?: MISSING_VALUE,
                    confidence = condition.confidence.name,
                    metricNullReason = run.failureReason.takeIf {
                        condition.medianTtftMs == null ||
                            condition.medianCompletionMs == null ||
                            condition.medianStreamEventRateEps == null ||
                            condition.medianStallCount == null ||
                            condition.medianStallDurationMs == null
                    },
                    rpiNullReasons = condition.allNullReasons
                        ?: listOfNotNull(condition.primaryNullReason),
                )
            },
        )
    }

    private fun metric(value: Double?, suffix: String = ""): String = value?.let {
        String.format(Locale.ROOT, "%.6f", it).trimEnd('0').trimEnd('.') + suffix
    } ?: MISSING_VALUE

    private val CONDITION_IDS = listOf(
        "baseline_v0.1",
        "slow_v0.1",
        "unstable_v0.1",
    )
    private val CONDITION_TITLES = listOf("Baseline", "Slow", "Unstable")
    private const val MISSING_VALUE = "—"
    private const val DISCLOSURE =
        "This score compares deterministic application-layer conditions against this campaign's Baseline. " +
            "It is not a formal ANEB industry score and does not represent a third-party AI application's " +
            "network requirement. These results are synthetic application-layer measurements from this " +
            "local probe and do not measure or represent packet loss, RAN, core network, operator, public " +
            "Internet, a real third-party AI app, model inference, AQS, MOS, network quality, an SLA, or a grade."
}
