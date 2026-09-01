package com.aneb.probe.prototype

import com.aneb.probe.net.RawSseEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID
import kotlin.math.floor

internal data class PrototypeCampaignRunRef(
    val runIndex: Int,
    val runId: String,
    val conditionId: String,
)

internal enum class PrototypeRunLivePhase {
    CONNECTING,
    WAITING_FOR_FIRST_EVENT,
    STREAMING,
    FINALIZING,
}

internal data class PrototypeRunLiveProgress(
    val phase: PrototypeRunLivePhase = PrototypeRunLivePhase.CONNECTING,
    val validatedEventCount: Int = 0,
    val ttftMs: Double? = null,
    val eventRateEps: Double? = null,
    val stallObserved: Boolean = false,
)

internal fun PrototypeRunLiveSnapshot.toPrototypeRunLiveProgress(
    nominalIntervalMs: Int,
): PrototypeRunLiveProgress {
    require(nominalIntervalMs > 0) { "prototype live nominal interval must be positive" }
    val timestamps = validatedContentTimestampsNanos
    val first = timestamps.firstOrNull()
    val span = if (timestamps.size >= 2) timestamps.last() - timestamps.first() else null
    val stallThresholdNanos = maxOf(500_000_000L, 4L * nominalIntervalMs * 1_000_000L)
    return PrototypeRunLiveProgress(
        phase = when {
            terminalClientMonotonicNanos != null -> PrototypeRunLivePhase.FINALIZING
            timestamps.isNotEmpty() -> PrototypeRunLivePhase.STREAMING
            runStartedObserved -> PrototypeRunLivePhase.WAITING_FOR_FIRST_EVENT
            else -> PrototypeRunLivePhase.CONNECTING
        },
        validatedEventCount = timestamps.size,
        ttftMs = first?.let { timestamp ->
            (timestamp - t0MonotonicNanos).toDouble() / 1_000_000.0
        },
        eventRateEps = span?.let { spanNanos ->
            require(spanNanos > 0L) { "prototype live stream span must be positive" }
            (timestamps.size - 1).toDouble() * 1_000_000_000.0 / spanNanos.toDouble()
        },
        stallObserved = timestamps.zipWithNext().any { (previous, next) ->
            next - previous > stallThresholdNanos
        },
    )
}

internal sealed interface PrototypeCampaignProgress {
    val campaignId: String
    val processedRuns: Int
    val totalRuns: Int

    data class Running(
        override val campaignId: String,
        val currentRunRef: PrototypeCampaignRunRef,
        override val processedRuns: Int,
        override val totalRuns: Int,
        val live: PrototypeRunLiveProgress = PrototypeRunLiveProgress(),
    ) : PrototypeCampaignProgress

    data class Cooldown(
        override val campaignId: String,
        val nextRunRef: PrototypeCampaignRunRef,
        override val processedRuns: Int,
        override val totalRuns: Int,
    ) : PrototypeCampaignProgress

    data class Saving(
        override val campaignId: String,
        override val processedRuns: Int,
        override val totalRuns: Int,
    ) : PrototypeCampaignProgress
}

/** Executes the fixed Prototype 0.1 Quick campaign as one ordered product operation. */
class PrototypeQuickCampaignRunner private constructor(
    private val executeRun:
        suspend (RunPlan, String, (PrototypeRunLiveSnapshot) -> Unit) -> RunResult,
    private val runIdFactory: (Int) -> String,
    private val waitBetweenRuns: suspend (Long) -> Unit,
    private val clockDomainIdFactory: (Int) -> String,
    private val publishProgress: (PrototypeCampaignProgress) -> Unit,
) {
    internal constructor(
        executeRun: suspend (RunPlan) -> RunResult,
        runIdFactory: (Int) -> String,
        waitBetweenRuns: suspend (Long) -> Unit,
        publishProgress: (PrototypeCampaignProgress) -> Unit = {},
    ) : this(
        executeRun = { plan, _, _ -> executeRun(plan) },
        runIdFactory = runIdFactory,
        waitBetweenRuns = waitBetweenRuns,
        clockDomainIdFactory = { UUID.randomUUID().toString() },
        publishProgress = publishProgress,
    )

    constructor(
        streamAdapter: PrototypeRunStreamAdapter,
        runIdFactory: (Int) -> String = { UUID.randomUUID().toString() },
        waitBetweenRuns: suspend (Long) -> Unit = { delay(it) },
    ) : this(
        executeRun = productExecution(streamAdapter),
        runIdFactory = runIdFactory,
        waitBetweenRuns = waitBetweenRuns,
        clockDomainIdFactory = { UUID.randomUUID().toString() },
        publishProgress = {},
    )

    internal constructor(
        streamAdapter: PrototypeRunStreamAdapter,
        publishProgress: (PrototypeCampaignProgress) -> Unit,
    ) : this(
        executeRun = productExecution(streamAdapter),
        runIdFactory = { UUID.randomUUID().toString() },
        waitBetweenRuns = { delay(it) },
        clockDomainIdFactory = { UUID.randomUUID().toString() },
        publishProgress = publishProgress,
    )

    internal constructor(
        streamAdapter: PrototypeRunStreamAdapter,
        runIdFactory: (Int) -> String,
        clockDomainIdFactory: (Int) -> String,
        waitBetweenRuns: suspend (Long) -> Unit,
        publishProgress: (PrototypeCampaignProgress) -> Unit = {},
    ) : this(
        executeRun = productExecution(streamAdapter),
        runIdFactory = runIdFactory,
        waitBetweenRuns = waitBetweenRuns,
        clockDomainIdFactory = clockDomainIdFactory,
        publishProgress = publishProgress,
    )

    data class RunPlan(
        val endpoint: String,
        val campaignId: String,
        val runId: String,
        val runIndex: Int,
        val conditionId: String,
        val requestBody: String,
        val campaignMode: CampaignMode = CampaignMode.QUICK,
    ) {
        internal val condition: ConditionMetadata
            get() = conditionMetadata(conditionId)
    }

    internal data class ConditionMetadata(
        val id: String,
        val version: String,
        val nominalIntervalMs: Int,
        val scheduleHash: String,
    )

    enum class RunStatus {
        COMPLETE,
        INTERRUPTED,
        INVALID_SEQUENCE,
        CANCELLED,
        NOT_STARTED,
    }

    data class RunMetrics(
        val ttftMs: Double?,
        val completionMs: Double?,
        val streamSpanMs: Double?,
        val streamEventRateEps: Double?,
        val stallThresholdMs: Double?,
        val stallCount: Int?,
        val stallDurationMs: Double?,
        val stallFraction: Double?,
    )

    class RunResult private constructor(
        val runIndex: Int,
        val runId: String,
        val conditionId: String,
        val status: RunStatus,
        val taskSuccess: Boolean,
        val scoreEligible: Boolean,
        val eventsExpected: Int,
        val eventsReceived: Int,
        val failureReason: String?,
        val terminalReceiptValid: Boolean?,
        val completedStreamResult: PrototypeRunStreamResult?,
        val partialEvidence: PrototypeInterruptedStreamEvidence?,
        val evidenceEvents: List<JsonObject>,
        val metrics: RunMetrics?,
    ) {
        /** Compatibility accessor for callers that already established a complete run. */
        val streamResult: PrototypeRunStreamResult
            get() = checkNotNull(completedStreamResult) {
                "prototype run does not have a completed stream result"
            }

        companion object {
            internal fun completeForTest(
                runIndex: Int,
                runId: String,
                conditionId: String,
                streamResult: PrototypeRunStreamResult,
            ): RunResult = RunResult(
                runIndex = runIndex,
                runId = runId,
                conditionId = conditionId,
                status = RunStatus.COMPLETE,
                taskSuccess = true,
                scoreEligible = true,
                eventsExpected = EXPECTED_CONTENT_EVENTS,
                eventsReceived = streamResult.validatedContentEvents.size,
                failureReason = null,
                terminalReceiptValid = true,
                completedStreamResult = streamResult,
                partialEvidence = null,
                evidenceEvents = emptyList(),
                metrics = null,
            )

            internal fun complete(
                runIndex: Int,
                runId: String,
                conditionId: String,
                streamResult: PrototypeRunStreamResult,
                evidenceEvents: List<JsonObject>,
                metrics: RunMetrics,
            ): RunResult = RunResult(
                runIndex = runIndex,
                runId = runId,
                conditionId = conditionId,
                status = RunStatus.COMPLETE,
                taskSuccess = true,
                scoreEligible = true,
                eventsExpected = EXPECTED_CONTENT_EVENTS,
                eventsReceived = streamResult.validatedContentEvents.size,
                failureReason = null,
                terminalReceiptValid = true,
                completedStreamResult = streamResult,
                partialEvidence = null,
                evidenceEvents = evidenceEvents,
                metrics = metrics,
            )

            internal fun interrupted(
                plan: RunPlan,
                evidence: PrototypeInterruptedStreamEvidence,
                evidenceEvents: List<JsonObject>,
                metrics: RunMetrics?,
            ): RunResult = RunResult(
                runIndex = plan.runIndex,
                runId = plan.runId,
                conditionId = plan.conditionId,
                status = RunStatus.INTERRUPTED,
                taskSuccess = false,
                scoreEligible = false,
                eventsExpected = EXPECTED_CONTENT_EVENTS,
                eventsReceived = evidence.validatedContentEvents.size,
                failureReason = "stream_interrupted",
                terminalReceiptValid = null,
                completedStreamResult = null,
                partialEvidence = evidence,
                evidenceEvents = evidenceEvents,
                metrics = metrics,
            )

            internal fun invalidSequence(
                plan: RunPlan,
                evidence: PrototypeInterruptedStreamEvidence,
                evidenceEvents: List<JsonObject>,
            ): RunResult = RunResult(
                runIndex = plan.runIndex,
                runId = plan.runId,
                conditionId = plan.conditionId,
                status = RunStatus.INVALID_SEQUENCE,
                taskSuccess = false,
                scoreEligible = false,
                eventsExpected = EXPECTED_CONTENT_EVENTS,
                eventsReceived = evidence.validatedContentEvents.size,
                failureReason = "invalid_sequence",
                terminalReceiptValid = null,
                completedStreamResult = null,
                partialEvidence = evidence,
                evidenceEvents = evidenceEvents,
                metrics = null,
            )

            internal fun cancelled(
                plan: RunPlan,
                evidence: PrototypeInterruptedStreamEvidence,
                evidenceEvents: List<JsonObject>,
                metrics: RunMetrics?,
            ): RunResult = RunResult(
                runIndex = plan.runIndex,
                runId = plan.runId,
                conditionId = plan.conditionId,
                status = RunStatus.CANCELLED,
                taskSuccess = false,
                scoreEligible = false,
                eventsExpected = EXPECTED_CONTENT_EVENTS,
                eventsReceived = evidence.validatedContentEvents.size,
                failureReason = "cancelled",
                terminalReceiptValid = null,
                completedStreamResult = null,
                partialEvidence = evidence,
                evidenceEvents = evidenceEvents,
                metrics = metrics,
            )

            internal fun notStarted(plan: RunPlan): RunResult = RunResult(
                runIndex = plan.runIndex,
                runId = plan.runId,
                conditionId = plan.conditionId,
                status = RunStatus.NOT_STARTED,
                taskSuccess = false,
                scoreEligible = false,
                eventsExpected = EXPECTED_CONTENT_EVENTS,
                eventsReceived = 0,
                failureReason = "not_started",
                terminalReceiptValid = null,
                completedStreamResult = null,
                partialEvidence = null,
                evidenceEvents = emptyList(),
                metrics = null,
            )
        }
    }

    internal data class SummaryRun(
        val conditionId: String,
        val status: RunStatus,
        val taskSuccess: Boolean,
        val scoreEligible: Boolean,
        val metrics: RunMetrics?,
    )

    enum class CampaignStatus {
        COMPLETE,
        PARTIAL,
        CANCELLED,
    }

    enum class CampaignMode(
        val wireValue: String,
        internal val runsPerCondition: Int,
    ) {
        QUICK(wireValue = "quick", runsPerCondition = 1),
        ACCEPTANCE(wireValue = "acceptance", runsPerCondition = 3),
    }

    enum class Confidence {
        NONE,
        LOW,
        MEDIUM,
        HIGH,
    }

    data class ConditionSummary(
        val conditionId: String,
        val plannedRuns: Int,
        val attemptedRuns: Int,
        val successfulRuns: Int,
        val failedRuns: Int,
        val notStartedRuns: Int,
        val successRate: Double,
        val confidence: Confidence,
        val medianTtftMs: Double?,
        val minTtftMs: Double?,
        val maxTtftMs: Double?,
        val medianCompletionMs: Double?,
        val minCompletionMs: Double?,
        val maxCompletionMs: Double?,
        val medianStreamEventRateEps: Double?,
        val medianStallCount: Double?,
        val medianStallDurationMs: Double?,
        val medianStallFraction: Double?,
        val rpi: Int?,
        val rpiPolicyId: String,
        val primaryNullReason: String?,
        val allNullReasons: List<String>?,
    )

    data class CampaignSummary(
        val campaignId: String,
        val campaignMode: String,
        val plannedRuns: Int,
        val attemptedRuns: Int,
        val successfulRuns: Int,
        val failedRuns: Int,
        val notStartedRuns: Int,
        val successRate: Double,
        val status: CampaignStatus,
        val conditionSummaries: List<ConditionSummary>,
    )

    data class CampaignResult(
        val runs: List<RunResult>,
        val summary: CampaignSummary,
    )

    suspend fun run(
        endpoint: String,
        campaignId: String,
        mode: CampaignMode = CampaignMode.QUICK,
    ): CampaignResult {
        val conditions = List(mode.runsPerCondition) { QUICK_CONDITIONS }.flatten()
        val plans = conditions.mapIndexed { index, condition ->
            val runIndex = index + 1
            val runId = runIdFactory(runIndex)
            RunPlan(
                endpoint = endpoint,
                campaignId = campaignId,
                runId = runId,
                runIndex = runIndex,
                conditionId = condition.id,
                requestBody = requestBody(
                    campaignId = campaignId,
                    runId = runId,
                    runIndex = runIndex,
                    conditionId = condition.id,
                    mode = mode,
                ),
                campaignMode = mode,
            )
        }
        val results = ArrayList<RunResult>(plans.size)
        val cancellationAuthority = currentCoroutineContext()[PrototypeUserCancellationAuthority]
        val resultReadyAuthority = currentCoroutineContext()[PrototypeCampaignResultReadyAuthority]
        var completedCampaignResult: CampaignResult? = null
        plans.forEachIndexed { index, plan ->
            val clockDomainId = clockDomainIdFactory(plan.runIndex)
            require(clockDomainId.isNotBlank()) {
                "prototype clock-domain identity must be non-empty"
            }
            val currentRunRef = PrototypeCampaignRunRef(
                runIndex = plan.runIndex,
                runId = plan.runId,
                conditionId = plan.conditionId,
            )
            fun publishRunning(live: PrototypeRunLiveProgress) {
                publishProgress(
                    PrototypeCampaignProgress.Running(
                        campaignId = campaignId,
                        currentRunRef = currentRunRef,
                        processedRuns = results.size,
                        totalRuns = plans.size,
                        live = live,
                    ),
                )
            }
            var lastLiveProgress = PrototypeRunLiveProgress()
            publishRunning(lastLiveProgress)
            try {
                val runResult = executeRun(plan, clockDomainId) { snapshot ->
                    val nextLiveProgress =
                        snapshot.toPrototypeRunLiveProgress(plan.condition.nominalIntervalMs)
                    if (nextLiveProgress != lastLiveProgress) {
                        lastLiveProgress = nextLiveProgress
                        publishRunning(nextLiveProgress)
                    }
                }
                if (index == plans.lastIndex) {
                    val candidate = campaignResult(
                        campaignId = campaignId,
                        mode = mode,
                        results = results + runResult,
                    )
                    if (resultReadyAuthority?.claimCompletedOutcome() == false) {
                        val streamResult = runResult.completedStreamResult
                        val cancellation = CancellationException(
                            "prototype campaign cancelled before validated result completion",
                        )
                        if (streamResult == null) throw cancellation
                        throw PrototypeRunCancellationObservation(
                            evidence = PrototypeInterruptedStreamEvidence(
                                rawEvents = streamResult.rawEvents.dropLast(1),
                                t0MonotonicNanos = streamResult.t0MonotonicNanos,
                                validatedContentEvents = streamResult.validatedContentEvents,
                                interruptionClientMonotonicNanos =
                                    streamResult.terminalClientMonotonicNanos,
                            ),
                            cause = cancellation,
                        )
                    }
                    completedCampaignResult = candidate
                }
                results += runResult
            } catch (error: PrototypeRunCancellationObservation) {
                error.evidence?.let { evidence ->
                    val evidenceEvents = projectInterruptedEvidence(
                        plan = plan,
                        clockDomainId = clockDomainId,
                        evidence = evidence,
                        failureReason = "cancelled",
                        terminalEventType = "run_cancelled",
                    )
                    results += RunResult.cancelled(
                        plan = plan,
                        evidence = evidence,
                        evidenceEvents = evidenceEvents,
                        metrics = interruptedRunMetrics(plan.condition, evidence),
                    )
                    results += plans.drop(index + 1).map(RunResult::notStarted)
                } ?: run {
                    results += plans.drop(index).map(RunResult::notStarted)
                }
                throw PrototypeCampaignCancelledWithResult(
                    result = campaignResult(
                        campaignId = campaignId,
                        mode = mode,
                        results = results,
                    ),
                    cause = error,
                )
            } catch (error: PrototypeRunInvalidSequenceException) {
                val evidenceEvents = projectInterruptedEvidence(
                    plan = plan,
                    clockDomainId = clockDomainId,
                    evidence = error.evidence,
                    failureReason = "invalid_sequence",
                )
                results += RunResult.invalidSequence(
                    plan = plan,
                    evidence = error.evidence,
                    evidenceEvents = evidenceEvents,
                )
                results += plans.drop(index + 1).map(RunResult::notStarted)
                return campaignResult(
                    campaignId = campaignId,
                    mode = mode,
                    results = results,
                )
            } catch (error: PrototypeRunStreamInterruptedException) {
                val evidenceEvents = projectInterruptedEvidence(
                    plan = plan,
                    clockDomainId = clockDomainId,
                    evidence = error.evidence,
                )
                results += RunResult.interrupted(
                    plan = plan,
                    evidence = error.evidence,
                    evidenceEvents = evidenceEvents,
                    metrics = interruptedRunMetrics(plan.condition, error.evidence),
                )
                results += plans.drop(index + 1).map(RunResult::notStarted)
                return campaignResult(
                    campaignId = campaignId,
                    mode = mode,
                    results = results,
                )
            } catch (error: CancellationException) {
                if (cancellationAuthority?.isRequested() != true) throw error
                results += plans.drop(index).map(RunResult::notStarted)
                throw PrototypeCampaignCancelledWithResult(
                    result = campaignResult(
                        campaignId = campaignId,
                        mode = mode,
                        results = results,
                    ),
                    cause = error,
                )
            }
            val nextPlan = plans.getOrNull(index + 1)
            if (nextPlan != null) {
                publishProgress(
                    PrototypeCampaignProgress.Cooldown(
                        campaignId = campaignId,
                        nextRunRef = PrototypeCampaignRunRef(
                            runIndex = nextPlan.runIndex,
                            runId = nextPlan.runId,
                            conditionId = nextPlan.conditionId,
                        ),
                        processedRuns = results.size,
                        totalRuns = plans.size,
                    ),
                )
                try {
                    waitBetweenRuns(QUICK_COOLDOWN_MS)
                } catch (error: CancellationException) {
                    if (cancellationAuthority?.isRequested() != true) throw error
                    results += plans.drop(index + 1).map(RunResult::notStarted)
                    throw PrototypeCampaignCancelledWithResult(
                        result = campaignResult(
                            campaignId = campaignId,
                            mode = mode,
                            results = results,
                        ),
                        cause = error,
                    )
                }
            }
        }

        return checkNotNull(completedCampaignResult) {
            "prototype completed campaign result was not finalized"
        }
    }

    private fun campaignResult(
        campaignId: String,
        mode: CampaignMode,
        results: List<RunResult>,
    ): CampaignResult = CampaignResult(
        runs = results,
        summary = canonicalCampaignSummary(
            campaignId = campaignId,
            mode = mode,
            results = results.map { run ->
                SummaryRun(
                    conditionId = run.conditionId,
                    status = run.status,
                    taskSuccess = run.taskSuccess,
                    scoreEligible = run.scoreEligible,
                    metrics = run.metrics,
                )
            },
        ),
    )

    internal companion object {
        @JvmSynthetic
        internal fun canonicalCampaignSummary(
            campaignId: String,
            results: List<SummaryRun>,
        ): CampaignSummary = canonicalCampaignSummary(
            campaignId = campaignId,
            mode = CampaignMode.QUICK,
            results = results,
        )

        @JvmSynthetic
        internal fun canonicalCampaignSummary(
            campaignId: String,
            mode: CampaignMode,
            results: List<SummaryRun>,
        ): CampaignSummary {
            val attemptedRuns = results.count { it.status != RunStatus.NOT_STARTED }
            val successfulRuns = results.count { it.taskSuccess }
            val hasNotStartedRuns = results.any { it.status == RunStatus.NOT_STARTED }
            val hasCancelledRun = results.any { it.status == RunStatus.CANCELLED }
            val status = when {
                !hasNotStartedRuns -> CampaignStatus.COMPLETE
                hasCancelledRun -> CampaignStatus.CANCELLED
                else -> CampaignStatus.PARTIAL
            }
            val summaries = enrichRpi(
                summaries = conditionSummaries(mode = mode, results = results),
                status = status,
            )
            val plannedRuns = QUICK_CONDITIONS.size * mode.runsPerCondition
            return CampaignSummary(
                campaignId = campaignId,
                campaignMode = mode.wireValue,
                plannedRuns = plannedRuns,
                attemptedRuns = attemptedRuns,
                successfulRuns = successfulRuns,
                failedRuns = attemptedRuns - successfulRuns,
                notStartedRuns = plannedRuns - attemptedRuns,
                successRate = successfulRuns.toDouble() / plannedRuns,
                status = status,
                conditionSummaries = summaries,
            )
        }

        private fun conditionSummaries(
            mode: CampaignMode,
            results: List<SummaryRun>,
        ): List<ConditionSummary> =
            QUICK_CONDITIONS.map { condition ->
                val plannedRuns = mode.runsPerCondition
                val conditionRuns = results.filter { run -> run.conditionId == condition.id }
                val attemptedRuns = conditionRuns.count { run -> run.status != RunStatus.NOT_STARTED }
                val successfulRuns = conditionRuns.filter { run -> run.taskSuccess && run.scoreEligible }
                val ttftValues = successfulRuns.mapNotNull { run -> run.metrics?.ttftMs }
                val completionValues = successfulRuns.mapNotNull { run -> run.metrics?.completionMs }
                val eventRateValues = successfulRuns.mapNotNull { run -> run.metrics?.streamEventRateEps }
                val stallCountValues = successfulRuns.mapNotNull { run -> run.metrics?.stallCount?.toDouble() }
                val stallDurationValues = successfulRuns.mapNotNull { run -> run.metrics?.stallDurationMs }
                val stallFractionValues = successfulRuns.mapNotNull { run -> run.metrics?.stallFraction }
                ConditionSummary(
                    conditionId = condition.id,
                    plannedRuns = plannedRuns,
                    attemptedRuns = attemptedRuns,
                    successfulRuns = successfulRuns.size,
                    failedRuns = attemptedRuns - successfulRuns.size,
                    notStartedRuns = plannedRuns - attemptedRuns,
                    successRate = successfulRuns.size.toDouble() / plannedRuns,
                    confidence = when (successfulRuns.size) {
                        3 -> Confidence.HIGH
                        2 -> Confidence.MEDIUM
                        1 -> Confidence.LOW
                        else -> Confidence.NONE
                    },
                    medianTtftMs = median(ttftValues),
                    minTtftMs = ttftValues.minOrNull(),
                    maxTtftMs = ttftValues.maxOrNull(),
                    medianCompletionMs = median(completionValues),
                    minCompletionMs = completionValues.minOrNull(),
                    maxCompletionMs = completionValues.maxOrNull(),
                    medianStreamEventRateEps = median(eventRateValues),
                    medianStallCount = median(stallCountValues),
                    medianStallDurationMs = median(stallDurationValues),
                    medianStallFraction = median(stallFractionValues),
                    rpi = null,
                    rpiPolicyId = RPI_POLICY_ID,
                    primaryNullReason = null,
                    allNullReasons = null,
                )
            }

        private fun median(values: List<Double>): Double? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 1) {
                sorted[middle]
            } else {
                (sorted[middle - 1] + sorted[middle]) / 2.0
            }
        }

        private fun enrichRpi(
            summaries: List<ConditionSummary>,
            status: CampaignStatus,
        ): List<ConditionSummary> {
            val baseline = summaries.first()
            val baselineHasSuccessfulRun = baseline.successfulRuns > 0
            val baselineReasons = rpiNullReasons(
                summary = baseline,
                status = status,
                baselineHasSuccessfulRun = baselineHasSuccessfulRun,
            )
            return summaries.map { summary ->
                val rowReasons = rpiNullReasons(
                    summary = summary,
                    status = status,
                    baselineHasSuccessfulRun = baselineHasSuccessfulRun,
                )
                val effectiveReasons = rowReasons.ifEmpty { baselineReasons }
                if (effectiveReasons.isNotEmpty()) {
                    summary.copy(
                        rpi = null,
                        primaryNullReason = effectiveReasons.first(),
                        allNullReasons = effectiveReasons,
                    )
                } else {
                    summary.copy(
                        rpi = rpiValue(baseline = baseline, current = summary),
                        primaryNullReason = null,
                        allNullReasons = null,
                    )
                }
            }
        }

        private fun rpiNullReasons(
            summary: ConditionSummary,
            status: CampaignStatus,
            baselineHasSuccessfulRun: Boolean,
        ): List<String> {
            val reasons = mutableSetOf<String>()
            if (status != CampaignStatus.COMPLETE) {
                reasons += NULL_REASON_CAMPAIGN_INCOMPLETE
            }
            if (!baselineHasSuccessfulRun) {
                reasons += NULL_REASON_NO_SUCCESSFUL_BASELINE
            }
            if (summary.successfulRuns == 0) {
                reasons += NULL_REASON_NO_SUCCESSFUL_CONDITION_RUN
            }
            val mandatoryMetrics = listOf(
                summary.medianTtftMs,
                summary.medianCompletionMs,
                summary.medianStallFraction,
            )
            if (mandatoryMetrics.any { metric -> metric == null }) {
                reasons += NULL_REASON_MANDATORY_METRIC_MISSING
            }
            if (
                mandatoryMetrics.filterNotNull().any { metric -> !metric.isFinite() } ||
                !summary.successRate.isFinite() ||
                summary.successRate !in 0.0..1.0 ||
                summary.medianStallFraction?.let { fraction -> fraction !in 0.0..1.0 } == true
            ) {
                reasons += NULL_REASON_INVALID_EVIDENCE
            }
            if (
                summary.medianTtftMs?.let { ttft -> ttft <= 0.0 } == true ||
                summary.medianCompletionMs?.let { completion -> completion <= 0.0 } == true
            ) {
                reasons += NULL_REASON_NON_POSITIVE_METRIC
            }
            return RPI_NULL_REASON_PRECEDENCE.filter(reasons::contains)
        }

        private fun rpiValue(
            baseline: ConditionSummary,
            current: ConditionSummary,
        ): Int {
            val ttftQuality = (
                checkNotNull(baseline.medianTtftMs) / checkNotNull(current.medianTtftMs)
            ).coerceAtMost(1.0)
            val completionQuality = (
                checkNotNull(baseline.medianCompletionMs) /
                    checkNotNull(current.medianCompletionMs)
            ).coerceAtMost(1.0)
            val stallQuality = (1.0 - checkNotNull(current.medianStallFraction)).coerceIn(0.0, 1.0)
            val raw = 100.0 * current.successRate * (
                0.45 * ttftQuality +
                    0.35 * completionQuality +
                    0.20 * stallQuality
            )
            return floor(raw.coerceIn(0.0, 100.0) + 0.5).toInt()
        }

        private const val EXPECTED_CONTENT_EVENTS = 120
        private const val QUICK_COOLDOWN_MS = 1_000L
        private const val EVIDENCE_SCHEMA_VERSION = "aneb-prototype-evidence-0.1"
        private const val PROTOCOL_VERSION = "prototype-stream-0.1"
        private const val WORKLOAD_ID = "streaming_text_reference_v0.1"
        private const val WORKLOAD_VERSION = "0.1"
        private const val PROFILE_ID = "streaming_text_reference_v0.1"
        private const val PROFILE_VERSION = "0.1"
        private const val PROFILE_MANIFEST_SHA256 =
            "44393ddd5ed11a5091038a85d08ab65ee91a8566997e837d2c40fd3add57d5dc"
        private const val CONDITION_VERSION = "0.1"
        private const val TERMINAL_RECEIPT_VERSION = "prototype-terminal-receipt-0.1"
        private const val ANDROID_CLOCK_SOURCE =
            "android.os.SystemClock.elapsedRealtimeNanos"
        private const val CLOCK_UNIT = "ns"
        private const val CLOCK_EPOCH = "device_boot"
        private const val EVENT_SOURCE = "android"
        private const val DATA_PREFIX = "data: "
        private const val RPI_POLICY_ID = "rpi-0.1"
        private const val NULL_REASON_CAMPAIGN_INCOMPLETE = "campaign_incomplete"
        private const val NULL_REASON_CONTRACT_MISMATCH = "contract_mismatch"
        private const val NULL_REASON_INVALID_EVIDENCE = "invalid_evidence"
        private const val NULL_REASON_NO_SUCCESSFUL_BASELINE = "no_successful_baseline"
        private const val NULL_REASON_NO_SUCCESSFUL_CONDITION_RUN =
            "no_successful_condition_run"
        private const val NULL_REASON_MANDATORY_METRIC_MISSING = "mandatory_metric_missing"
        private const val NULL_REASON_NON_POSITIVE_METRIC = "non_positive_metric"
        private const val NULL_REASON_SCORE_POLICY_UNSUPPORTED = "score_policy_unsupported"
        private val RPI_NULL_REASON_PRECEDENCE = listOf(
            NULL_REASON_CAMPAIGN_INCOMPLETE,
            NULL_REASON_CONTRACT_MISMATCH,
            NULL_REASON_INVALID_EVIDENCE,
            NULL_REASON_NO_SUCCESSFUL_BASELINE,
            NULL_REASON_NO_SUCCESSFUL_CONDITION_RUN,
            NULL_REASON_MANDATORY_METRIC_MISSING,
            NULL_REASON_NON_POSITIVE_METRIC,
            NULL_REASON_SCORE_POLICY_UNSUPPORTED,
        )
        private const val RUN_STARTED_PLAN_AUTHORITY_ERROR =
            "Prototype run_started authority does not match the Quick run plan"
        private val SERVER_CONTENT_DETAIL_KEYS = setOf(
            "seq",
            "planned_offset_ms",
            "payload_id",
            "profile_manifest_sha256",
            "schedule_hash",
        )
        private val QUICK_CONDITIONS = listOf(
            ConditionMetadata(
                id = "baseline_v0.1",
                version = CONDITION_VERSION,
                nominalIntervalMs = 50,
                scheduleHash =
                    "46eced73d2fbc886040a3357f84551d424a95e15d6e9e69c16958f6e52e33d7e",
            ),
            ConditionMetadata(
                id = "slow_v0.1",
                version = CONDITION_VERSION,
                nominalIntervalMs = 125,
                scheduleHash =
                    "b51b27fe8332b3fc8a97472a44312b3001ccd54364a61ed8799816c299d27062",
            ),
            ConditionMetadata(
                id = "unstable_v0.1",
                version = CONDITION_VERSION,
                nominalIntervalMs = 65,
                scheduleHash =
                    "d11dce2a877d7c3772a4552f2d922d5f96730c9a01bb829f0203c65b110a8c58",
            ),
        )
        private val evidenceJson = Json {
            ignoreUnknownKeys = false
            isLenient = false
        }

        private fun productExecution(
            streamAdapter: PrototypeRunStreamAdapter,
        ): suspend (RunPlan, String, (PrototypeRunLiveSnapshot) -> Unit) -> RunResult =
            { plan, clockDomainId, publishLiveSnapshot ->
            val streamResult = streamAdapter.run(
                plan.endpoint,
                plan.requestBody,
                publishLiveSnapshot,
            )
            val evidenceEvents = projectCompleteEvidence(plan, clockDomainId, streamResult)
            val metrics = completeRunMetrics(plan.condition, streamResult)
            RunResult.complete(
                runIndex = plan.runIndex,
                runId = plan.runId,
                conditionId = plan.conditionId,
                streamResult = streamResult,
                evidenceEvents = evidenceEvents,
                metrics = metrics,
            )
        }

        private fun completeRunMetrics(
            condition: ConditionMetadata,
            streamResult: PrototypeRunStreamResult,
        ): RunMetrics {
            val contentTimestamps = streamResult.validatedContentEvents.map { event ->
                event.clientMonotonicNanos
            }
            require(contentTimestamps.size == EXPECTED_CONTENT_EVENTS) {
                "complete Prototype metrics require 120 content timestamps"
            }
            val t0 = streamResult.t0MonotonicNanos
            val firstContent = contentTimestamps.first()
            val lastContent = contentTimestamps.last()
            val terminal = streamResult.terminalClientMonotonicNanos
            require(
                t0 >= 0L &&
                    firstContent > t0 &&
                    contentTimestamps.zipWithNext().all { (previous, next) -> next > previous } &&
                    terminal > lastContent,
            ) {
                "complete Prototype metrics require strictly ordered Android timestamps"
            }

            val contentGaps = contentTimestamps.zipWithNext { previous, next -> next - previous }
            val streamSpanNanos = lastContent - firstContent
            require(streamSpanNanos > 0L) {
                "complete Prototype metrics require a positive stream span"
            }
            val nominalIntervalNanos = condition.nominalIntervalMs.toLong() * 1_000_000L
            val stallThresholdNanos = maxOf(500_000_000L, 4L * nominalIntervalNanos)
            val stallGaps = contentGaps.filter { gap -> gap > stallThresholdNanos }
            val stallDurationNanos = stallGaps.sumOf { gap -> gap - nominalIntervalNanos }

            return RunMetrics(
                ttftMs = (firstContent - t0).toDouble() / 1_000_000.0,
                completionMs = (terminal - t0).toDouble() / 1_000_000.0,
                streamSpanMs = streamSpanNanos.toDouble() / 1_000_000.0,
                streamEventRateEps =
                    (contentTimestamps.size - 1).toDouble() * 1_000_000_000.0 /
                    streamSpanNanos.toDouble(),
                stallThresholdMs = stallThresholdNanos.toDouble() / 1_000_000.0,
                stallCount = stallGaps.size,
                stallDurationMs = stallDurationNanos.toDouble() / 1_000_000.0,
                stallFraction =
                    (stallDurationNanos.toDouble() / streamSpanNanos.toDouble()).coerceIn(0.0, 1.0),
            )
        }

        private fun interruptedRunMetrics(
            condition: ConditionMetadata,
            evidence: PrototypeInterruptedStreamEvidence,
        ): RunMetrics? {
            if (evidence.validatedContentEvents.isEmpty()) return null

            val t0 = evidence.t0MonotonicNanos
            val contentTimestamps = evidence.validatedContentEvents.map { event ->
                event.clientMonotonicNanos
            }
            val firstContent = contentTimestamps.first()
            if (firstContent <= t0) return null
            require(t0 >= 0L) {
                "partial Prototype metrics require ordered Android timestamps"
            }
            if (contentTimestamps.size == 1) {
                return RunMetrics(
                    ttftMs = (firstContent - t0).toDouble() / 1_000_000.0,
                    completionMs = null,
                    streamSpanMs = null,
                    streamEventRateEps = null,
                    stallThresholdMs = null,
                    stallCount = null,
                    stallDurationMs = null,
                    stallFraction = null,
                )
            }

            require(contentTimestamps.zipWithNext().all { (previous, next) -> next > previous }) {
                "partial Prototype metrics require ordered Android timestamps"
            }
            val contentGaps = contentTimestamps.zipWithNext { previous, next -> next - previous }
            val streamSpanNanos = contentTimestamps.last() - firstContent
            val nominalIntervalNanos = condition.nominalIntervalMs.toLong() * 1_000_000L
            val stallThresholdNanos = maxOf(500_000_000L, 4L * nominalIntervalNanos)
            val stallGaps = contentGaps.filter { gap -> gap > stallThresholdNanos }
            val stallDurationNanos = stallGaps.sumOf { gap -> gap - nominalIntervalNanos }
            return RunMetrics(
                ttftMs = (firstContent - t0).toDouble() / 1_000_000.0,
                completionMs = null,
                streamSpanMs = streamSpanNanos.toDouble() / 1_000_000.0,
                streamEventRateEps =
                    (contentTimestamps.size - 1).toDouble() * 1_000_000_000.0 /
                    streamSpanNanos.toDouble(),
                stallThresholdMs = stallThresholdNanos.toDouble() / 1_000_000.0,
                stallCount = stallGaps.size,
                stallDurationMs = stallDurationNanos.toDouble() / 1_000_000.0,
                stallFraction =
                    (stallDurationNanos.toDouble() / streamSpanNanos.toDouble()).coerceIn(0.0, 1.0),
            )
        }

        private fun conditionMetadata(conditionId: String): ConditionMetadata =
            QUICK_CONDITIONS.singleOrNull { it.id == conditionId }
                ?: throw IllegalArgumentException("unknown Prototype campaign condition: $conditionId")

        private fun projectCompleteEvidence(
            plan: RunPlan,
            clockDomainId: String,
            streamResult: PrototypeRunStreamResult,
        ): List<JsonObject> {
            requireRunStartedPlanAuthority(plan, streamResult.rawEvents.firstOrNull())
            require(streamResult.validatedContentEvents.size == EXPECTED_CONTENT_EVENTS) {
                "complete Prototype run does not have 120 validated content events"
            }
            val events = ArrayList<JsonObject>(EXPECTED_CONTENT_EVENTS + 2)
            events += evidenceEvent(
                plan = plan,
                clockDomainId = clockDomainId,
                eventType = "run_started",
                clientMonotonicNanos = streamResult.t0MonotonicNanos,
                details = buildJsonObject {
                    put("t0_monotonic_ns", JsonPrimitive(streamResult.t0MonotonicNanos))
                },
            )
            var previousContentTimestamp: Long? = null
            streamResult.validatedContentEvents.forEachIndexed { index, observed ->
                val expectedSequence = index + 1
                require(observed.sequence == expectedSequence) {
                    "validated Prototype content sequence is not canonical"
                }
                require(
                    observed.clientMonotonicNanos >= streamResult.t0MonotonicNanos &&
                        (previousContentTimestamp == null ||
                            observed.clientMonotonicNanos > previousContentTimestamp!!),
                ) {
                    "Prototype content evidence timestamps are not strictly ordered"
                }
                val details = projectContentDetails(plan, observed, expectedSequence)
                events += evidenceEvent(
                    plan = plan,
                    clockDomainId = clockDomainId,
                    eventType = "content_event",
                    clientMonotonicNanos = observed.clientMonotonicNanos,
                    details = details,
                )
                previousContentTimestamp = observed.clientMonotonicNanos
            }
            val lastContentTimestamp = checkNotNull(previousContentTimestamp)
            require(streamResult.terminalClientMonotonicNanos > lastContentTimestamp) {
                "Prototype terminal evidence timestamp must follow content evidence"
            }
            val serverDetails = streamResult.decodedTerminal.envelope["details"] as? JsonObject
                ?: throw IllegalArgumentException("Prototype terminal receipt details are missing")
            val projectedTerminalDetails = PrototypeTerminalProjection.project(
                serverDetails = serverDetails,
                androidAdditions = buildJsonObject {
                    put("receipt_version", JsonPrimitive(TERMINAL_RECEIPT_VERSION))
                    put("events_expected", JsonPrimitive(EXPECTED_CONTENT_EVENTS))
                    put("events_received", JsonPrimitive(EXPECTED_CONTENT_EVENTS))
                    put("clock_domain_id", JsonPrimitive(clockDomainId))
                    put("clock_source", JsonPrimitive(ANDROID_CLOCK_SOURCE))
                    put("clock_unit", JsonPrimitive(CLOCK_UNIT))
                    put("clock_epoch", JsonPrimitive(CLOCK_EPOCH))
                    put("t0_monotonic_ns", JsonPrimitive(streamResult.t0MonotonicNanos))
                    put(
                        "client_monotonic_ns",
                        JsonPrimitive(streamResult.terminalClientMonotonicNanos),
                    )
                },
            )
            requireTerminalPlanAuthority(plan, projectedTerminalDetails)
            events += evidenceEvent(
                plan = plan,
                clockDomainId = clockDomainId,
                eventType = "terminal_event",
                clientMonotonicNanos = streamResult.terminalClientMonotonicNanos,
                details = projectedTerminalDetails,
            )
            return events
        }

        private fun projectInterruptedEvidence(
            plan: RunPlan,
            clockDomainId: String,
            evidence: PrototypeInterruptedStreamEvidence,
            failureReason: String = "stream_interrupted",
            terminalEventType: String = "run_failed",
        ): List<JsonObject> {
            requireInterruptedRunStartedPlanAuthority(plan, evidence)
            val events = ArrayList<JsonObject>(evidence.validatedContentEvents.size + 2)
            events += evidenceEvent(
                plan = plan,
                clockDomainId = clockDomainId,
                eventType = "run_started",
                clientMonotonicNanos = evidence.t0MonotonicNanos,
                details = buildJsonObject {
                    put("t0_monotonic_ns", JsonPrimitive(evidence.t0MonotonicNanos))
                },
            )
            var previousContentTimestamp: Long? = null
            evidence.validatedContentEvents.forEachIndexed { index, observed ->
                val expectedSequence = index + 1
                require(observed.sequence == expectedSequence) {
                    "validated Prototype content prefix is not canonical"
                }
                require(
                    observed.clientMonotonicNanos > evidence.t0MonotonicNanos &&
                        (previousContentTimestamp == null ||
                            observed.clientMonotonicNanos > previousContentTimestamp!!),
                ) {
                    "Prototype partial content evidence timestamps are not strictly ordered"
                }
                events += evidenceEvent(
                    plan = plan,
                    clockDomainId = clockDomainId,
                    eventType = "content_event",
                    clientMonotonicNanos = observed.clientMonotonicNanos,
                    details = projectContentDetails(plan, observed, expectedSequence),
                )
                previousContentTimestamp = observed.clientMonotonicNanos
            }
            val lastObservedTimestamp = previousContentTimestamp ?: evidence.t0MonotonicNanos
            require(evidence.interruptionClientMonotonicNanos > lastObservedTimestamp) {
                "Prototype failure evidence timestamp must follow observed content"
            }
            events += evidenceEvent(
                plan = plan,
                clockDomainId = clockDomainId,
                eventType = terminalEventType,
                clientMonotonicNanos = evidence.interruptionClientMonotonicNanos,
                details = buildJsonObject {
                    put("failure_reason", JsonPrimitive(failureReason))
                    put("events_received", JsonPrimitive(evidence.validatedContentEvents.size))
                },
            )
            return events
        }

        private fun requireInterruptedRunStartedPlanAuthority(
            plan: RunPlan,
            evidence: PrototypeInterruptedStreamEvidence,
        ) = requireRunStartedPlanAuthority(plan, evidence.rawEvents.firstOrNull())

        private fun requireRunStartedPlanAuthority(
            plan: RunPlan,
            rawEvent: RawSseEvent?,
        ) {
            val matches = try {
                val dataLine = rawEvent
                    ?.bytes
                    ?.toString(Charsets.UTF_8)
                    ?.lineSequence()
                    ?.toList()
                    ?.getOrNull(1)
                if (dataLine?.startsWith(DATA_PREFIX) != true) {
                    false
                } else {
                    val envelope = evidenceJson.parseToJsonElement(dataLine.removePrefix(DATA_PREFIX))
                        as? JsonObject
                    val details = envelope?.get("details") as? JsonObject
                    details?.get("schedule_hash") == JsonPrimitive(plan.condition.scheduleHash) &&
                        details?.get("profile_manifest_sha256") == JsonPrimitive(PROFILE_MANIFEST_SHA256) &&
                        details?.get("profile_id") == JsonPrimitive(PROFILE_ID) &&
                        details?.get("profile_version") == JsonPrimitive(PROFILE_VERSION) &&
                        exactJsonInteger(
                            details?.get("nominal_interval_ms"),
                            plan.condition.nominalIntervalMs,
                        )
                }
            } catch (_: Exception) {
                false
            }
            require(matches) { RUN_STARTED_PLAN_AUTHORITY_ERROR }
        }

        private fun evidenceEvent(
            plan: RunPlan,
            clockDomainId: String,
            eventType: String,
            clientMonotonicNanos: Long,
            details: JsonObject,
        ): JsonObject {
            require(clockDomainId.isNotBlank()) { "Prototype clock-domain identity is empty" }
            require(clientMonotonicNanos >= 0L) { "Prototype evidence timestamp is negative" }
            val condition = plan.condition
            return buildJsonObject {
                put("schema_version", JsonPrimitive(EVIDENCE_SCHEMA_VERSION))
                put("campaign_id", JsonPrimitive(plan.campaignId))
                put("run_id", JsonPrimitive(plan.runId))
                put("campaign_mode", JsonPrimitive(plan.campaignMode.wireValue))
                put("run_index", JsonPrimitive(plan.runIndex))
                put("condition_id", JsonPrimitive(condition.id))
                put("condition_version", JsonPrimitive(condition.version))
                put("nominal_interval_ms", JsonPrimitive(condition.nominalIntervalMs))
                put("profile_manifest_sha256", JsonPrimitive(PROFILE_MANIFEST_SHA256))
                put("schedule_hash", JsonPrimitive(condition.scheduleHash))
                put("event_type", JsonPrimitive(eventType))
                put("client_monotonic_ns", JsonPrimitive(clientMonotonicNanos))
                put("clock_source", JsonPrimitive(ANDROID_CLOCK_SOURCE))
                put("clock_unit", JsonPrimitive(CLOCK_UNIT))
                put("clock_epoch", JsonPrimitive(CLOCK_EPOCH))
                put("clock_domain_id", JsonPrimitive(clockDomainId))
                put("source", JsonPrimitive(EVENT_SOURCE))
                put("details", details)
            }
        }

        private fun projectContentDetails(
            plan: RunPlan,
            observed: PrototypeValidatedContentEvent,
            expectedSequence: Int,
        ): JsonObject {
            val lines = observed.rawEvent.bytes.toString(Charsets.UTF_8).lineSequence().toList()
            require(lines.size == 2 && lines[1].startsWith(DATA_PREFIX)) {
                "Prototype content evidence frame is not canonical"
            }
            val envelope = evidenceJson.parseToJsonElement(lines[1].removePrefix(DATA_PREFIX))
                as? JsonObject
                ?: throw IllegalArgumentException("Prototype content evidence root is not an object")
            require(envelope["condition_id"] == JsonPrimitive(plan.conditionId)) {
                "Prototype content evidence condition does not match the plan"
            }
            val details = envelope["details"] as? JsonObject
                ?: throw IllegalArgumentException("Prototype content evidence details are missing")
            require(details.keys == SERVER_CONTENT_DETAIL_KEYS) {
                "Prototype content evidence details do not match the exact server shape"
            }
            val expectedOffset = plannedOffsetMs(plan.condition, expectedSequence)
            val expectedPayloadId = "ref-${expectedSequence.toString().padStart(4, '0')}"
            require(exactJsonInteger(details["seq"], expectedSequence)) {
                "Prototype content evidence sequence does not match the plan"
            }
            require(exactJsonInteger(details["planned_offset_ms"], expectedOffset)) {
                "Prototype content evidence offset does not match the frozen schedule"
            }
            require(details["payload_id"] == JsonPrimitive(expectedPayloadId)) {
                "Prototype content evidence payload identity does not match the schedule"
            }
            require(details["profile_manifest_sha256"] == JsonPrimitive(PROFILE_MANIFEST_SHA256)) {
                "Prototype content evidence profile identity does not match the plan"
            }
            require(details["schedule_hash"] == JsonPrimitive(plan.condition.scheduleHash)) {
                "Prototype content evidence schedule identity does not match the plan"
            }
            return buildJsonObject {
                put("seq", JsonPrimitive(expectedSequence))
                put("planned_offset_ms", JsonPrimitive(expectedOffset))
                put("payload_id", JsonPrimitive(expectedPayloadId))
            }
        }

        private fun plannedOffsetMs(condition: ConditionMetadata, sequence: Int): Int {
            require(sequence in 1..EXPECTED_CONTENT_EVENTS)
            val initialDelay = when (condition.id) {
                "baseline_v0.1" -> 200
                "slow_v0.1" -> 650
                "unstable_v0.1" -> 350
                else -> throw IllegalArgumentException("unknown Prototype schedule condition")
            }
            val scheduledPauses = if (condition.id == "unstable_v0.1") {
                (if (sequence > 40) 900 else 0) + (if (sequence > 85) 1_400 else 0)
            } else {
                0
            }
            return initialDelay + (sequence - 1) * condition.nominalIntervalMs + scheduledPauses
        }

        private fun exactJsonInteger(value: Any?, expected: Int): Boolean {
            val primitive = value as? JsonPrimitive ?: return false
            return !primitive.isString && primitive.content == expected.toString()
        }

        private fun requireTerminalPlanAuthority(plan: RunPlan, details: JsonObject) {
            val condition = plan.condition
            val expected = mapOf(
                "protocol_version" to JsonPrimitive(PROTOCOL_VERSION),
                "campaign_id" to JsonPrimitive(plan.campaignId),
                "run_id" to JsonPrimitive(plan.runId),
                "campaign_mode" to JsonPrimitive(plan.campaignMode.wireValue),
                "run_index" to JsonPrimitive(plan.runIndex),
                "condition_id" to JsonPrimitive(condition.id),
                "condition_version" to JsonPrimitive(condition.version),
                "profile_id" to JsonPrimitive(PROFILE_ID),
                "profile_version" to JsonPrimitive(PROFILE_VERSION),
                "profile_manifest_sha256" to JsonPrimitive(PROFILE_MANIFEST_SHA256),
                "schedule_hash" to JsonPrimitive(condition.scheduleHash),
                "nominal_interval_ms" to JsonPrimitive(condition.nominalIntervalMs),
                "planned_event_count" to JsonPrimitive(EXPECTED_CONTENT_EVENTS),
                "emitted_event_count" to JsonPrimitive(EXPECTED_CONTENT_EVENTS),
                "terminal_status" to JsonPrimitive("complete"),
            )
            require(expected.all { (key, value) -> details[key] == value }) {
                "Prototype terminal receipt does not match the campaign plan"
            }
        }

        private fun requestBody(
            campaignId: String,
            runId: String,
            runIndex: Int,
            conditionId: String,
            mode: CampaignMode,
        ): String = buildJsonObject {
            put("protocol_version", JsonPrimitive(PROTOCOL_VERSION))
            put("campaign_id", JsonPrimitive(campaignId))
            put("run_id", JsonPrimitive(runId))
            put("campaign_mode", JsonPrimitive(mode.wireValue))
            put("run_index", JsonPrimitive(runIndex))
            put("workload_id", JsonPrimitive(WORKLOAD_ID))
            put("workload_version", JsonPrimitive(WORKLOAD_VERSION))
            put("profile_id", JsonPrimitive(PROFILE_ID))
            put("profile_version", JsonPrimitive(PROFILE_VERSION))
            put("profile_manifest_sha256", JsonPrimitive(PROFILE_MANIFEST_SHA256))
            put("condition_id", JsonPrimitive(conditionId))
            put("condition_version", JsonPrimitive(CONDITION_VERSION))
        }.toString()
    }
}
