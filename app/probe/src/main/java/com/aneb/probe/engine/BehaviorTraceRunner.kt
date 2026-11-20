package com.aneb.probe.engine

import com.aneb.probe.net.AndroidMonotonicNanosClock
import com.aneb.probe.net.AnebClient
import com.aneb.probe.net.MonotonicNanosClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * End-to-end runtime executor for `aneb-profile-v2` (`mode_id = "token_simulation"`,
 * `phase = "behavior_trace"`).
 *
 * Executes genuine network traffic via [AnebClient] (`/api/v1/echo`, `/api/v1/upload`,
 * `/api/v1/stream`, `/api/v1/download`) driven by a deterministic [BehaviorTracePlan],
 * computes real observed metrics (`TOK-B01`..`TOK-B15`, `TOK-N03`..`TOK-N07`), and enforces
 * fail-closed R-10 semantics (`missing_required_metric = "score_null"`,
 * `invalid_run = "retain_raw_suppress_score"`).
 */
class BehaviorTraceRunner internal constructor(
    private val client: AnebClient,
    private val clock: MonotonicNanosClock = AndroidMonotonicNanosClock,
) {
    constructor() : this(AnebClient(), AndroidMonotonicNanosClock)

    data class Config(
        val serverBase: String,
        val profile: ScenarioProfile,
        val tracePlan: BehaviorTracePlan,
        val runId: String,
        val echoSamples: Int? = null,
        val maxTasks: Int? = null,
    )

    data class TaskExecutionOutcome(
        val taskIndex: Int,
        val taskId: String,
        val workloadKind: String,
        val plannedTokens: Int,
        val receivedUniqueTokens: Int,
        val totalDeliveredTokens: Int,
        val truncatedEarly: Boolean,
        val uploadBytes: Long,
        val uploadOk: Boolean,
        val uploadLatencyMs: Double?,
        val uploadGoodputMbps: Double?,
        val uploadDeviationMs: Double?,
        val streamOk: Boolean,
        val e2eTtftMs: Double?,
        val ttftExcessMs: Double?,
        val onTimeRatio: Double?,
        val itlResidualsMs: List<Double>,
        val artifactBytes: Long,
        val downloadOk: Boolean,
        val artifactLatencyMs: Double?,
        val downGoodputMbps: Double?,
        val plannedDurationMs: Double,
        val actualDurationMs: Double,
        val durationDeviationMs: Double,
        val succeeded: Boolean,
        val errors: List<String>,
    )

    data class BehaviorTraceRunResult(
        val runId: String,
        val profileId: String,
        val profileVersion: String,
        val seed: Long,
        val status: String,
        val tasksPlanned: Int,
        val tasksExecuted: Int,
        val tasksSucceeded: Int,
        val plannedTotalDurationMs: Double,
        val actualTotalDurationMs: Double,
        val durationDeviationMs: Double,
        val metrics: Map<String, Double?>,
        val tasks: List<TaskExecutionOutcome>,
        val guardrailViolated: Boolean,
        val missingRequiredMetrics: List<String>,
        val belowSampleFloorMetrics: List<String>,
        val computable: Boolean,
        val score: Double?,
        val suppressionReasons: List<String>,
    )

    private val _result = MutableStateFlow<BehaviorTraceRunResult?>(null)
    val result: StateFlow<BehaviorTraceRunResult?> = _result.asStateFlow()

    fun run(config: Config): Flow<String> = channelFlow {
        _result.value = null
        val assessment = ProfileCapability.assess(config.profile)
        require(assessment.executable) {
            "profile_not_executable:${config.profile.profileId}:${assessment.unsupportedPhaseTypes}:${assessment.contractIssues}"
        }
        require(config.profile.modeId == ScenarioProfile.MODE_TOKEN_SIMULATION) {
            "unsupported_v2_mode:${config.profile.modeId}"
        }

        val base = config.serverBase.trim().trimEnd('/')
        val tasksToRun = config.maxTasks?.let { config.tracePlan.tasks.take(it.coerceAtLeast(1)) }
            ?: config.tracePlan.tasks
        require(tasksToRun.isNotEmpty()) { "trace_plan_has_no_tasks" }
        val measurementsById = config.profile.measurements.associateBy { it.metricId }

        send(
            "V2_RUN_START run_id=${config.runId} profile=${config.profile.profileId}@${config.profile.version} " +
                "seed=${config.tracePlan.seed} tasks=${tasksToRun.size}",
        )

        val runStartNanos = clock.now()
        val sampleCount = (config.echoSamples ?: run {
            val maxEchoFloor = listOf("TOK-N03", "TOK-N04")
                .mapNotNull { id -> measurementsById[id]?.minimumSampleCount }
                .maxOrNull() ?: 4
            maxEchoFloor + 1
        }).coerceAtLeast(1)
        val echoRttsMs = ArrayList<Double>(sampleCount)
        var totalRequests = 0
        var failedRequests = 0

        for (i in 0 until sampleCount) {
            totalRequests++
            val echo = client.echo("$base/api/v1/echo")
            val ok = echo.error == null && echo.httpCode in 200..299 && echo.rttUs != null
            if (ok) {
                // Drop warmup echo sample 0 when >2 samples are collected.
                if (sampleCount <= 2 || i > 0) {
                    echoRttsMs.add(checkNotNull(echo.rttUs) / 1_000.0)
                }
            } else {
                failedRequests++
            }
        }

        val echoP50Ms = percentile(echoRttsMs, 0.50)
        val echoP95Ms = percentile(echoRttsMs, 0.95)
        val echoJitterMs = if (echoP95Ms != null && echoP50Ms != null) (echoP95Ms - echoP50Ms).coerceAtLeast(0.0) else null

        val taskOutcomes = ArrayList<TaskExecutionOutcome>(tasksToRun.size)
        val allItlResidualsMs = ArrayList<Double>()
        var totalPlannedTokens = 0
        var totalDeliveredTokens = 0
        var totalUniqueTokens = 0
        var anyStreamRead = false
        var anyStreamTruncated = false
        var totalOnTimeTokens = 0
        var totalEvaluatedOnTimeTokens = 0

        for ((idx, task) in tasksToRun.withIndex()) {
            val taskStartNanos = clock.now()
            val taskErrors = mutableListOf<String>()

            // 1. Upload
            var uploadOk = task.uploadBytes <= 0L
            var uploadLatencyMs: Double? = null
            var uploadGoodputMbps: Double? = null
            var uploadDeviationMs: Double? = null
            if (task.uploadBytes > 0L) {
                totalRequests++
                val up = client.uploadTraceStream(
                    url = "$base/api/v1/upload?run=${config.runId}",
                    totalBytes = task.uploadBytes,
                    chunkSizes = task.uploadChunks,
                    chunkCadenceMs = task.uploadChunkCadenceMs.roundToLong(),
                )
                uploadOk = up.error == null && up.httpCode in 200..299
                if (!uploadOk) {
                    failedRequests++
                    taskErrors += "upload:${up.error ?: "http_${up.httpCode}"}"
                } else {
                    val endNs = up.timing?.responseHeadersStartNs ?: up.responseNanos
                    if (endNs != null && endNs > up.startNanos) {
                        val ms = (endNs - up.startNanos) / 1e6
                        uploadLatencyMs = ms
                        uploadGoodputMbps = (task.uploadBytes * 8.0) / (ms * 1_000.0)
                        val plannedUploadMs = (task.uploadChunks.size - 1).coerceAtLeast(0) * task.uploadChunkCadenceMs
                        uploadDeviationMs = ms - plannedUploadMs
                    }
                }
            }

            // 2. Token stream (SSE)
            val plannedTokens = task.tokenPlans.size
            totalPlannedTokens += plannedTokens
            var streamOk = plannedTokens == 0
            var receivedUnique = 0
            var deliveredCount = 0
            var truncatedEarly = false
            var e2eTtftMs: Double? = null
            var ttftExcessMs: Double? = null
            var onTimeRatio: Double? = null
            val taskResiduals = ArrayList<Double>()

            if (plannedTokens > 0) {
                totalRequests++
                val firstOffset = task.tokenPlans.first().plannedOffsetMs
                val lastOffset = task.tokenPlans.last().plannedOffsetMs
                val spanMs = (lastOffset - firstOffset).coerceAtLeast(1.0)
                val rateTps = if (plannedTokens > 1) {
                    ((plannedTokens - 1) * 1000.0 / spanMs).coerceIn(0.1, 100_000.0)
                } else {
                    20.0
                }
                val requestedIntervalMs = if (plannedTokens > 1) 1000.0 / rateTps else 0.0
                val ttftInjectUs = (task.processingDelayMs * 1000.0).roundToLong().coerceIn(0L, 10_000_000L)
                val rateStr = String.format(Locale.ROOT, "%.3f", rateTps)
                val streamUrl = "$base/api/v1/stream?tokens=$plannedTokens&rate_tps=$rateStr" +
                    "&seed=${config.tracePlan.seed + task.taskIndex}&ttft_inject_us=$ttftInjectUs&run=${config.runId}"
                val sr = client.stream(streamUrl, expectedTokens = plannedTokens)
                val body = sr.stream
                val httpOk = sr.error == null && sr.httpCode in 200..299
                if (!httpOk || body == null) {
                    failedRequests++
                    taskErrors += "stream:${sr.error ?: "http_${sr.httpCode}"}"
                }
                if (body != null && (sr.httpCode == null || sr.httpCode in 200..299)) {
                    anyStreamRead = true
                    deliveredCount = body.events.size
                    totalDeliveredTokens += deliveredCount
                    val bySeq = linkedMapOf<Long, com.aneb.probe.net.TokenEvent>()
                    for (ev in body.events) {
                        if (ev.seq !in bySeq) bySeq[ev.seq] = ev
                    }
                    receivedUnique = bySeq.size
                    totalUniqueTokens += receivedUnique
                    truncatedEarly = sr.truncatedEarly || body.truncatedTail || receivedUnique < plannedTokens
                    if (truncatedEarly) anyStreamTruncated = true

                    val preludeTtftUs = body.prelude?.raw
                        ?.let { PRELUDE_TTFT_INJECT_REGEX.find(it)?.groupValues?.get(1)?.toLongOrNull() }
                    val injectedProcessingMs = (preludeTtftUs ?: ttftInjectUs) / 1000.0

                    val firstEv = bySeq[0L] ?: body.events.minByOrNull { it.seq }
                    if (firstEv != null) {
                        val ttft = (firstEv.arrivalNanos - sr.requestStartNanos) / 1e6
                        e2eTtftMs = ttft
                        ttftExcessMs = ttft - injectedProcessingMs

                        var taskOnTime = 0
                        var taskEvaluated = 0
                        for (plan in task.tokenPlans) {
                            val ev = bySeq[plan.seq] ?: continue
                            taskEvaluated++
                            val actualRelMs = (ev.arrivalNanos - firstEv.arrivalNanos) / 1e6
                            val expectedRelMs = if (ev.schedUs >= 0L && firstEv.schedUs >= 0L) {
                                (ev.schedUs - firstEv.schedUs) / 1e3
                            } else {
                                plan.seq * requestedIntervalMs
                            }
                            if (actualRelMs - expectedRelMs <= 200.0) {
                                taskOnTime++
                            }
                        }
                        if (taskEvaluated > 0) {
                            onTimeRatio = taskOnTime.toDouble() / taskEvaluated.toDouble()
                            totalOnTimeTokens += taskOnTime
                            totalEvaluatedOnTimeTokens += taskEvaluated
                        }
                    }

                    for (k in 1 until task.tokenPlans.size) {
                        val prevPlan = task.tokenPlans[k - 1]
                        val curPlan = task.tokenPlans[k]
                        val prevEv = bySeq[prevPlan.seq] ?: continue
                        val curEv = bySeq[curPlan.seq] ?: continue
                        if (!curEv.sameReadBatch) {
                            val actualItlMs = (curEv.arrivalNanos - prevEv.arrivalNanos) / 1e6
                            val serverItlMs = when {
                                curEv.preFlushUs >= 0L && prevEv.preFlushUs >= 0L ->
                                    (curEv.preFlushUs - prevEv.preFlushUs) / 1e3
                                curEv.schedUs >= 0L && prevEv.schedUs >= 0L ->
                                    (curEv.schedUs - prevEv.schedUs) / 1e3
                                else -> requestedIntervalMs
                            }
                            val residualMs = actualItlMs - serverItlMs
                            taskResiduals.add(residualMs)
                            allItlResidualsMs.add(residualMs)
                        }
                    }
                    streamOk = httpOk && !truncatedEarly && receivedUnique >= plannedTokens
                    if (!streamOk && httpOk) {
                        taskErrors += "stream_truncated:$receivedUnique/$plannedTokens"
                    }
                }
            }

            // 3. Artifact download
            var downloadOk = task.artifactBytes <= 0L
            var artifactLatencyMs: Double? = null
            var downGoodputMbps: Double? = null
            if (task.artifactBytes > 0L) {
                totalRequests++
                val chunkKb = (task.artifactChunkBytes / 1024).coerceIn(16, 1024)
                val dl = client.downloadThroughput(
                    "$base/api/v1/download?bytes=${task.artifactBytes}&chunk_kb=$chunkKb",
                ) { _, _ -> }
                val endNs = dl.endNanos
                downloadOk = dl.error == null && dl.httpCode in 200..299 && endNs != null && dl.totalBytes >= task.artifactBytes
                if (!downloadOk) {
                    failedRequests++
                    taskErrors += "download:${dl.error ?: "http_${dl.httpCode}"}"
                } else if (endNs != null && endNs > dl.startNanos) {
                    val ms = (endNs - dl.startNanos) / 1e6
                    artifactLatencyMs = ms
                    downGoodputMbps = (dl.totalBytes * 8.0) / (ms * 1_000.0)
                }
            }

            val taskEndNanos = clock.now()
            val actualDurMs = (taskEndNanos - taskStartNanos) / 1e6
            val succeeded = uploadOk && streamOk && downloadOk
            val outcome = TaskExecutionOutcome(
                taskIndex = task.taskIndex,
                taskId = task.taskId,
                workloadKind = task.workloadKind,
                plannedTokens = plannedTokens,
                receivedUniqueTokens = receivedUnique,
                totalDeliveredTokens = deliveredCount,
                truncatedEarly = truncatedEarly,
                uploadBytes = task.uploadBytes,
                uploadOk = uploadOk,
                uploadLatencyMs = uploadLatencyMs,
                uploadGoodputMbps = uploadGoodputMbps,
                uploadDeviationMs = uploadDeviationMs,
                streamOk = streamOk,
                e2eTtftMs = e2eTtftMs,
                ttftExcessMs = ttftExcessMs,
                onTimeRatio = onTimeRatio,
                itlResidualsMs = taskResiduals,
                artifactBytes = task.artifactBytes,
                downloadOk = downloadOk,
                artifactLatencyMs = artifactLatencyMs,
                downGoodputMbps = downGoodputMbps,
                plannedDurationMs = task.plannedDurationMs,
                actualDurationMs = actualDurMs,
                durationDeviationMs = actualDurMs - task.plannedDurationMs,
                succeeded = succeeded,
                errors = taskErrors,
            )
            taskOutcomes.add(outcome)
            send(
                "V2_TASK_DONE task=${task.taskId} kind=${task.workloadKind} ok=$succeeded " +
                    "tokens=$receivedUnique/$plannedTokens ttft_ms=${e2eTtftMs?.let { fmt1(it) } ?: "null"} " +
                    "dur_ms=${fmt1(actualDurMs)}",
            )

            if (idx < tasksToRun.lastIndex && task.interTaskGapMs > 0.0) {
                delay(task.interTaskGapMs.roundToLong())
            }
        }

        val actualTotalDurationMs = (clock.now() - runStartNanos) / 1e6
        val plannedTotalDurationMs = tasksToRun.sumOf { it.plannedDurationMs } +
            tasksToRun.dropLast(1).sumOf { it.interTaskGapMs }
        val durationDeviationMs = actualTotalDurationMs - plannedTotalDurationMs

        val uploadLatencies = taskOutcomes.mapNotNull { it.uploadLatencyMs }
        val uploadGoodputs = taskOutcomes.mapNotNull { it.uploadGoodputMbps }
        val ttfts = taskOutcomes.mapNotNull { it.e2eTtftMs }
        val ttftExcesses = taskOutcomes.mapNotNull { it.ttftExcessMs }
        val artifactLatencies = taskOutcomes.mapNotNull { it.artifactLatencyMs }
        val downGoodputs = taskOutcomes.mapNotNull { it.downGoodputMbps }

        val taskSuccessRatio = if (taskOutcomes.isNotEmpty()) {
            taskOutcomes.count { it.succeeded }.toDouble() / taskOutcomes.size.toDouble()
        } else null
        val onTimeRatio = if (totalEvaluatedOnTimeTokens > 0) {
            totalOnTimeTokens.toDouble() / totalEvaluatedOnTimeTokens.toDouble()
        } else null
        val itlP95Ms = percentile(allItlResidualsMs, 0.95)
        val stallRatio = if (allItlResidualsMs.isNotEmpty()) {
            allItlResidualsMs.count { it > 200.0 }.toDouble() / allItlResidualsMs.size.toDouble()
        } else null
        val severeStallRatio = if (allItlResidualsMs.isNotEmpty()) {
            allItlResidualsMs.count { it > 1000.0 }.toDouble() / allItlResidualsMs.size.toDouble()
        } else null
        val streamCompleteness = if (anyStreamRead && totalPlannedTokens > 0) {
            totalUniqueTokens.toDouble() / totalPlannedTokens.toDouble()
        } else null
        val tokenRedundancy = if (anyStreamRead && totalPlannedTokens > 0 && totalDeliveredTokens > 0) {
            (totalDeliveredTokens - totalUniqueTokens).coerceAtLeast(0).toDouble() / totalPlannedTokens.toDouble()
        } else null
        val reqFailureRate = if (totalRequests > 0) {
            failedRequests.toDouble() / totalRequests.toDouble()
        } else null

        val metrics = linkedMapOf<String, Double?>(
            "TOK-B01" to taskSuccessRatio,
            "TOK-B02" to percentile(uploadLatencies, 0.95),
            "TOK-B04" to percentile(ttfts, 0.95),
            "TOK-B05" to percentile(ttftExcesses, 0.95),
            "TOK-B07" to onTimeRatio,
            "TOK-B08" to itlP95Ms,
            "TOK-B09" to stallRatio,
            "TOK-B10" to severeStallRatio,
            "TOK-B11" to streamCompleteness,
            "TOK-B12" to percentile(artifactLatencies, 0.95),
            "TOK-B14" to tokenRedundancy,
            "TOK-N03" to echoP95Ms,
            "TOK-N04" to echoJitterMs,
            "TOK-N05" to reqFailureRate,
            "TOK-N06" to percentile(uploadGoodputs, 0.05),
            "TOK-N07" to percentile(downGoodputs, 0.05),
        )

        val sampleCounts = mapOf(
            "TOK-B01" to taskOutcomes.size,
            "TOK-B02" to uploadLatencies.size,
            "TOK-B04" to ttfts.size,
            "TOK-B05" to ttftExcesses.size,
            "TOK-B07" to totalEvaluatedOnTimeTokens,
            "TOK-B08" to allItlResidualsMs.size,
            "TOK-B09" to allItlResidualsMs.size,
            "TOK-B10" to allItlResidualsMs.size,
            "TOK-B11" to totalUniqueTokens,
            "TOK-B12" to artifactLatencies.size,
            "TOK-B14" to if (anyStreamRead) taskOutcomes.size else 0,
            "TOK-N03" to echoRttsMs.size,
            "TOK-N04" to echoRttsMs.size,
            "TOK-N05" to totalRequests,
            "TOK-N06" to uploadGoodputs.size,
            "TOK-N07" to downGoodputs.size,
        )

        val evaluation = config.profile.evaluation
        val missingRequired = evaluation.requiredMetricIds.filter { metrics[it] == null }
        val belowFloor = evaluation.requiredMetricIds.filter { id ->
            val minSamples = measurementsById[id]?.minimumSampleCount ?: 1
            (sampleCounts[id] ?: 0) < minSamples
        }
        val guardrailViolated = evaluation.guardrailMetricIds.any { id ->
            val v = metrics[id]
            v != null && v > 0.0
        }
        val suppressionReasons = buildList {
            if (missingRequired.isNotEmpty()) add("missing_required:${missingRequired.joinToString(",")}")
            if (belowFloor.isNotEmpty()) add("below_sample_floor:${belowFloor.joinToString(",")}")
            if (anyStreamTruncated) add("stream_truncated")
            if (guardrailViolated) add("guardrail_violated")
        }
        val computable = suppressionReasons.isEmpty()
        val score = if (computable) computeCompositeScore(config.profile, metrics, sampleCounts, taskOutcomes) else null
        val tasksSucceeded = taskOutcomes.count { it.succeeded }
        val status = when {
            tasksSucceeded == taskOutcomes.size && computable -> "completed"
            tasksSucceeded == taskOutcomes.size -> "completed_unscored"
            tasksSucceeded > 0 -> "partial"
            else -> "failed"
        }

        val runResult = BehaviorTraceRunResult(
            runId = config.runId,
            profileId = config.profile.profileId,
            profileVersion = config.profile.version,
            seed = config.tracePlan.seed,
            status = status,
            tasksPlanned = tasksToRun.size,
            tasksExecuted = taskOutcomes.size,
            tasksSucceeded = tasksSucceeded,
            plannedTotalDurationMs = plannedTotalDurationMs,
            actualTotalDurationMs = actualTotalDurationMs,
            durationDeviationMs = durationDeviationMs,
            metrics = metrics,
            tasks = taskOutcomes,
            guardrailViolated = guardrailViolated,
            missingRequiredMetrics = missingRequired,
            belowSampleFloorMetrics = belowFloor,
            computable = computable,
            score = score,
            suppressionReasons = suppressionReasons,
        )
        _result.value = runResult
        send(
            "V2_RUN_END run_id=${config.runId} status=$status tasks=$tasksSucceeded/${taskOutcomes.size} " +
                "computable=$computable score=${score?.let { fmt1(it) } ?: "null"}",
        )
    }.flowOn(Dispatchers.IO)

    private fun computeCompositeScore(
        profile: ScenarioProfile,
        metrics: Map<String, Double?>,
        sampleCounts: Map<String, Int>,
        outcomes: List<TaskExecutionOutcome>,
    ): Double? {
        val groupMap = mapOf(
            "task_completion" to listOf("TOK-B01", "TOK-B02"),
            "interaction" to listOf("TOK-B05", "TOK-B07", "TOK-B09", "TOK-B10"),
            "multimodal_transfer" to listOf("TOK-N06", "TOK-N07", "TOK-B12"),
            "network_stability" to listOf("TOK-N03", "TOK-N04", "TOK-N05"),
            "efficiency" to listOf("TOK-B11", "TOK-B14"),
        )
        val measurements = profile.measurements.associateBy { it.metricId }
        var weightedSum = 0.0
        var totalWeight = 0.0

        for ((groupName, weight) in profile.evaluation.groupWeights) {
            if (!weight.isFinite() || weight < 0.0) return null
            val ids = groupMap[groupName].orEmpty()
            val metricScores = ArrayList<Double>()
            for (id in ids) {
                val measurement = measurements[id] ?: continue
                val target = measurement.qualityTarget ?: continue
                val value = metrics[id]
                val count = sampleCounts[id] ?: 0
                if (!measurement.requiredForScore && (value == null || count < measurement.minimumSampleCount)) {
                    continue
                }
                if (value == null) return null
                val scored = scoreMetric(value, target, measurements, outcomes) ?: return null
                metricScores.add(scored)
            }
            if (metricScores.isEmpty()) return null
            weightedSum += weight * metricScores.average()
            totalWeight += weight
        }
        return if (totalWeight > 0.0) (weightedSum / totalWeight).coerceIn(0.0, 100.0) else null
    }

    private fun scoreMetric(
        value: Double,
        target: ProfileQualityTarget,
        measurements: Map<String, ProfileMeasurement>,
        outcomes: List<TaskExecutionOutcome>,
    ): Double? = when (target.operator) {
        "lte_by_workload" -> scoreByWorkload(target, outcomes, higherIsBetter = false)
        "gte_by_workload" -> scoreByWorkload(target, outcomes, higherIsBetter = true)
        "deadline_by_artifact_size" -> scoreArtifactDeadlines(target, measurements, outcomes)
        else -> scoreAgainstTarget(value, target)
    }

    private fun scoreByWorkload(
        target: ProfileQualityTarget,
        outcomes: List<TaskExecutionOutcome>,
        higherIsBetter: Boolean,
    ): Double? {
        val ratios = ArrayList<Double>()
        for (outcome in outcomes) {
            val measured = if (higherIsBetter) outcome.uploadGoodputMbps else outcome.uploadLatencyMs
            if (measured == null) continue
            if (!measured.isFinite()) return null
            val threshold = resolveWorkloadThreshold(target.values, outcome.workloadKind) ?: return null
            if (!threshold.isFinite() || (higherIsBetter && threshold < 0.0) || (!higherIsBetter && threshold <= 0.0)) {
                return null
            }
            val ratio = if (higherIsBetter) {
                if (threshold == 0.0) 1.0 else measured / threshold
            } else {
                measured / threshold
            }
            ratios.add(ratio)
        }
        if (ratios.isEmpty()) return null
        return if (higherIsBetter) {
            val p05Ratio = percentile(ratios, 0.05) ?: return null
            (p05Ratio * 100.0).coerceIn(0.0, 100.0)
        } else {
            val p95Ratio = percentile(ratios, 0.95) ?: return null
            if (p95Ratio <= 1.0) 100.0 else (100.0 / p95Ratio).coerceIn(0.0, 100.0)
        }
    }

    private fun scoreArtifactDeadlines(
        target: ProfileQualityTarget,
        measurements: Map<String, ProfileMeasurement>,
        outcomes: List<TaskExecutionOutcome>,
    ): Double? {
        if (target.policyId != "artifact-deadline-v1") return null
        val downlinkTargetMbps = measurements["TOK-N07"]?.qualityTarget?.value ?: 25.0
        if (!downlinkTargetMbps.isFinite() || downlinkTargetMbps <= 0.0) return null
        val ratios = outcomes.mapNotNull { outcome ->
            val actualMs = outcome.artifactLatencyMs
            if (outcome.artifactBytes <= 0L || actualMs == null) return@mapNotNull null
            val deadlineMs = artifactDeadlineMs(outcome.artifactBytes, downlinkTargetMbps)
            actualMs / deadlineMs
        }
        if (ratios.isEmpty()) return null
        val p95Ratio = percentile(ratios, 0.95) ?: return null
        return if (p95Ratio <= 1.0) 100.0 else (100.0 / p95Ratio).coerceIn(0.0, 100.0)
    }

    private fun fmt1(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

    companion object {
        private const val ARTIFACT_DEADLINE_BASE_MS = 1_000.0
        private val PRELUDE_TTFT_INJECT_REGEX = Regex("\"ttft_inject_us\"\\s*:\\s*(\\d+)")

        internal fun artifactDeadlineMs(artifactBytes: Long, downlinkTargetMbps: Double): Double =
            ARTIFACT_DEADLINE_BASE_MS + (artifactBytes * 8.0) / (downlinkTargetMbps * 1000.0)

        internal fun resolveWorkloadThreshold(values: Map<String, Double>, workload: String): Double? {
            values[workload]?.let { return it }
            return when (workload) {
                "document" -> values["document_5mib"]
                "document_5mib" -> values["document"]
                "image" -> values["image_10mib"]
                "image_10mib" -> values["image"]
                "video" -> values["video_100mib"]
                "video_100mib" -> values["video"]
                else -> null
            }
        }

        internal fun scoreAgainstTarget(value: Double, target: ProfileQualityTarget): Double? {
            if (!value.isFinite()) return null
            return when (target.operator) {
                "gte" -> {
                    val th = target.value ?: return null
                    if (!th.isFinite()) return null
                    if (th <= 0.0) 100.0 else ((value / th) * 100.0).coerceIn(0.0, 100.0)
                }
                "lte" -> {
                    val th = target.value ?: return null
                    if (!th.isFinite()) return null
                    if (value <= th) 100.0 else if (th <= 0.0) 0.0 else ((th / value) * 100.0).coerceIn(0.0, 100.0)
                }
                "eq" -> {
                    val th = target.value ?: return null
                    if (!th.isFinite()) return null
                    if (abs(value - th) <= 1e-9) 100.0 else 0.0
                }
                else -> null
            }
        }

        private fun percentile(values: List<Double>, fraction: Double): Double? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val at = (sorted.size - 1) * fraction
            val lower = floor(at).toInt()
            val upper = (lower + 1).coerceAtMost(sorted.lastIndex)
            return sorted[lower] + (sorted[upper] - sorted[lower]) * (at - lower)
        }
    }
}
