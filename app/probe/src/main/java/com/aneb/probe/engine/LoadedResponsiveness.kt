package com.aneb.probe.engine

import com.aneb.probe.net.ThroughputRequestEvidence
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.math.floor

@Serializable
data class ResponsivenessEcho(
    val stage: String,
    val sequence: Int,
    val t0Us: Long,
    val t1Us: Long?,
    val t2Us: Long?,
    val t3Us: Long?,
    val httpCode: Int?,
    val error: String?,
    val bytesBefore: Long = 0L,
    val bytesAfter: Long = 0L,
)

@Serializable
data class ResponsivenessByteObservation(
    val atUs: Long,
    val totalBytes: Long,
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val requestId: String? = null,
)

@Serializable
data class ResponsivenessStage(
    val stage: String,
    val startUs: Long,
    val endUs: Long,
    val transferBytes: Long,
    val errors: List<String> = emptyList(),
    val samples: List<ResponsivenessEcho> = emptyList(),
    val byteObservations: List<ResponsivenessByteObservation> = emptyList(),
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val executionV2: ResponsivenessExecutionV2? = null,
)

@Serializable
data class ResponsivenessExecutionV2(
    val plannedEndUs: Long,
    val stopRequestedUs: Long?,
    val stopReason: String,
    val snapshotUs: Long,
    val requests: List<ThroughputRequestEvidence>,
)

@Serializable
data class LoadedResponsivenessReport(
    val runId: String,
    val serverBase: String,
    val transport: String,
    val status: String,
    val stages: List<ResponsivenessStage>,
    val methodVersion: String = "loaded-responsiveness-v1",
    val clockSource: String = "android.os.SystemClock.elapsedRealtimeNanos",
    val clockUnit: String = "us",
    val sourceProfileId: String = "basic_network",
    val sourceProfileVersion: String = "unknown",
)

@Serializable
data class ResponsivenessSampleResult(
    val sequence: Int,
    val rttMs: Double?,
    val exclusionReason: String?,
)

@Serializable
data class ResponsivenessStageSummary(
    val stage: String,
    val valueMs: Double?,
    val validSampleCount: Int,
    val totalSampleCount: Int,
    val missingReason: String?,
    val sampleResults: List<ResponsivenessSampleResult>,
    val rttDominanceOk: Boolean? = null,
    val rttDominanceRatio: Double? = null,
)

@Serializable
data class LoadedResponsivenessSummary(
    val idleP50Ms: Double?,
    val downloadP95Ms: Double?,
    val uploadP95Ms: Double?,
    val downloadDeltaMs: Double?,
    val uploadDeltaMs: Double?,
    val stages: List<ResponsivenessStageSummary>,
)

/** Independent, unscored application-echo evidence. Never changes Basic/AQS/RPI. */
object LoadedResponsiveness {
    private val codec = Json { encodeDefaults = true; prettyPrint = false }

    /**
     * 无损压缩字节观测序列：以阶段边界（startUs/endUs）和每次 echo 采样点（t0Us/t3Us）
     * 为切分点，把同一区间内同一 requestId 的高频分片累加为单条观测。
     *
     * 数学不变式：
     * 1. 每个 requestId 累加字节和保持完全一致；
     * 2. 保留的 atUs 均为该 requestId 的真实观测戳，严格位于 [bodyStartedUs, callbackFinishedUs]；
     * 3. 每次 echo 对应的 bytesBefore (< t0Us) 与 bytesAfter (<= t3Us) 与原始序列逐位一致；
     * 4. 若输入本身存在异常（逆序/回退），原样返回以保留审计拒判证据。
     */
    fun compactByteObservations(
        stageStartUs: Long,
        stageEndUs: Long,
        samples: List<ResponsivenessEcho>,
        observations: List<ResponsivenessByteObservation>,
    ): List<ResponsivenessByteObservation> {
        if (observations.size <= 2) return observations

        data class Cut(val us: Long, val inclusiveOnLeft: Boolean)
        // Strict (< us) cuts come BEFORE inclusive (<= us) cuts at the same timestamp.
        val cuts = buildList {
            add(Cut(stageStartUs, inclusiveOnLeft = false))
            add(Cut(stageEndUs, inclusiveOnLeft = true))
            samples.forEach { sample ->
                add(Cut(sample.t0Us, inclusiveOnLeft = false))
                sample.t3Us?.let { add(Cut(it, inclusiveOnLeft = true)) }
            }
        }.sortedWith(compareBy<Cut>({ it.us }, { if (it.inclusiveOnLeft) 1 else 0 }))
            .distinct()

        fun bucketIndex(atUs: Long): Int {
            var lo = 0
            var hi = cuts.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                val cut = cuts[mid]
                val onRight = if (cut.inclusiveOnLeft) atUs > cut.us else atUs >= cut.us
                if (onRight) lo = mid + 1 else hi = mid
            }
            return lo
        }

        // Preserve anomalous raw evidence verbatim so summarize() catches it accurately.
        var checkPrevAt = Long.MIN_VALUE
        var checkPrevBytes = 0L
        for (obs in observations) {
            if (obs.atUs < checkPrevAt || obs.totalBytes < checkPrevBytes) return observations
            checkPrevAt = obs.atUs
            checkPrevBytes = obs.totalBytes
        }

        data class RequestAgg(
            val requestId: String?,
            var deltaBytes: Long,
            var lastAtUs: Long,
            val firstOrder: Int,
        )

        val result = ArrayList<ResponsivenessByteObservation>()
        var currentBucket = -1
        var runningTotal = 0L
        var rawPrevTotal = 0L
        var orderSeq = 0
        val bucketAgg = LinkedHashMap<String?, RequestAgg>()

        fun flushBucket() {
            if (bucketAgg.isEmpty()) return
            val entries = bucketAgg.values.sortedWith(compareBy<RequestAgg>({ it.lastAtUs }, { it.firstOrder }))
            for (entry in entries) {
                if (entry.deltaBytes == 0L) {
                    if (result.isEmpty()) {
                        result += ResponsivenessByteObservation(entry.lastAtUs, runningTotal, entry.requestId)
                    }
                } else {
                    runningTotal += entry.deltaBytes
                    result += ResponsivenessByteObservation(entry.lastAtUs, runningTotal, entry.requestId)
                }
            }
            bucketAgg.clear()
        }

        for (obs in observations) {
            val b = bucketIndex(obs.atUs)
            if (b != currentBucket) {
                flushBucket()
                currentBucket = b
            }
            val delta = obs.totalBytes - rawPrevTotal
            rawPrevTotal = obs.totalBytes
            if (delta == 0L) {
                if (result.isEmpty() && bucketAgg.isEmpty()) {
                    bucketAgg[obs.requestId] = RequestAgg(obs.requestId, 0L, obs.atUs, orderSeq++)
                }
            } else {
                val existing = bucketAgg[obs.requestId]
                if (existing == null) {
                    bucketAgg[obs.requestId] = RequestAgg(obs.requestId, delta, obs.atUs, orderSeq++)
                } else {
                    existing.deltaBytes += delta
                    existing.lastAtUs = obs.atUs
                }
            }
        }
        flushBucket()
        return if (result.isEmpty()) observations.take(1) else result
    }

    /** Raw observations remain authoritative; the summary is a reproducible export convenience. */
    fun encode(report: LoadedResponsivenessReport): String {
        val raw = codec.encodeToJsonElement(LoadedResponsivenessReport.serializer(), report).jsonObject
        val document = buildJsonObject {
            raw.forEach { (key, value) -> put(key, value) }
            put("derived_summary", codec.encodeToJsonElement(LoadedResponsivenessSummary.serializer(), summarize(report)))
        }
        return codec.encodeToString(JsonObject.serializer(), document)
    }

    /** Readers recalculate from raw instead of trusting the exported derived summary. */
    fun decode(json: String): LoadedResponsivenessReport {
        val document = codec.parseToJsonElement(json).jsonObject
        return codec.decodeFromJsonElement(
            LoadedResponsivenessReport.serializer(),
            JsonObject(document.filterKeys { it != "derived_summary" }),
        )
    }

    fun summarize(report: LoadedResponsivenessReport): LoadedResponsivenessSummary {
        val rawStages = report.stages.map { stage ->
            val stageReason = when {
                report.status != "completed" -> "run_not_completed:${report.status}"
                report.methodVersion !in setOf("loaded-responsiveness-v1", "loaded-responsiveness-v2") -> "unsupported_method_version"
                report.clockSource != "android.os.SystemClock.elapsedRealtimeNanos" || report.clockUnit != "us" -> "unsupported_clock"
                stage.stage !in setOf("idle", "download", "upload") -> "unsupported_stage"
                stage.startUs < 0L || stage.endUs < stage.startUs -> "invalid_stage_bounds"
                stage.stage != "idle" && report.methodVersion == "loaded-responsiveness-v1" &&
                    "no_transfer_completed_before_phase_deadline" in stage.errors -> "no_completed_transfer"
                stage.stage != "idle" && stage.transferBytes <= 0L -> "no_transfer_bytes"
                stage.stage != "idle" && report.methodVersion == "loaded-responsiveness-v2" -> v2StageReason(stage)
                else -> null
            }
            val samples = stage.samples.map { sample ->
                val reason = stageReason ?: exclusionReason(stage, sample)
                ResponsivenessSampleResult(
                    sequence = sample.sequence,
                    rttMs = if (reason == null) {
                        ((checkNotNull(sample.t3Us) - sample.t0Us) -
                            (checkNotNull(sample.t2Us) - checkNotNull(sample.t1Us))) / 1_000.0
                    } else null,
                    exclusionReason = reason,
                )
            }
            val values = samples.mapNotNull { it.rttMs }
            val value = percentile(values, if (stage.stage == "idle") 0.50 else 0.95)
            ResponsivenessStageSummary(
                stage = stage.stage,
                valueMs = value,
                validSampleCount = values.size,
                totalSampleCount = stage.samples.size,
                missingReason = stageReason ?: if (stage.samples.isEmpty()) "no_samples" else if (values.isEmpty()) "no_valid_samples" else null,
                sampleResults = samples,
            )
        }
        val idle = rawStages.singleOrNull { it.stage == "idle" }?.valueMs
        val stages = rawStages.mapIndexed { idx, summary ->
            if (summary.stage == "idle") {
                summary
            } else {
                val rawStage = report.stages[idx]
                val windowMs = (rawStage.endUs - rawStage.startUs).coerceAtLeast(0L) / 1_000.0
                val verdict = RttDominanceGuard.evaluate(windowMs, idle, rawStage.transferBytes)
                summary.copy(
                    rttDominanceOk = verdict.ratio?.let { verdict.ok },
                    rttDominanceRatio = verdict.ratio,
                )
            }
        }
        val download = stages.singleOrNull { it.stage == "download" }?.valueMs
        val upload = stages.singleOrNull { it.stage == "upload" }?.valueMs
        return LoadedResponsivenessSummary(
            idle, download, upload,
            if (download != null && idle != null) download - idle else null,
            if (upload != null && idle != null) upload - idle else null,
            stages,
        )
    }

    private fun v2StageReason(stage: ResponsivenessStage): String? {
        val execution = stage.executionV2 ?: return "missing_execution_evidence"
        val stop = execution.stopRequestedUs ?: return "missing_stop_evidence"
        if (execution.stopReason != "phase_deadline" || execution.plannedEndUs != stage.endUs ||
            stop < execution.plannedEndUs || execution.snapshotUs < stop) return "phase_not_normally_closed"
        if (stage.errors.isNotEmpty()) return "transfer_error"
        val requests = execution.requests
        if (requests.isEmpty() || requests.map { it.requestId }.distinct().size != requests.size ||
            requests.any { it.requestId.isBlank() || it.workerId < 0 || it.direction != stage.stage ||
                it.startUs < stage.startUs || it.startUs >= stage.endUs || it.requestedBytes <= 0L || it.observedBytes < 0L }) {
            return "invalid_request_identity"
        }
        if (requests.sumOf { it.observedBytes } != stage.transferBytes) return "request_byte_mismatch"
        for (request in requests) {
            val finished = request.callbackFinishedUs ?: return "callback_drain_unconfirmed"
            if (finished < request.startUs || finished > execution.snapshotUs) return "invalid_request_times"
            if (request.failure != null || request.failureUs != null || request.outcome == "failed") return "transfer_error"
            if (request.httpCode != null && request.httpCode !in 200..299) return "transfer_http_not_successful"
            if (request.observedBytes > 0L) {
                val bodyStart = request.bodyStartedUs ?: return "missing_body_start"
                if (bodyStart < request.startUs || bodyStart > finished) return "invalid_request_times"
                if (stage.stage == "download") {
                    val headers = request.headersUs ?: return "missing_transfer_headers"
                    if (request.httpCode == null || headers < request.startUs || headers > bodyStart) return "missing_transfer_headers"
                }
            }
            when (request.outcome) {
                "completed" -> {
                    val completed = request.bodyCompletedUs ?: return "missing_transfer_completion"
                    if (request.httpCode == null || completed < request.startUs || completed > finished) return "invalid_request_times"
                }
                "cancelled" -> {
                    val requested = request.cancelRequestedUs ?: return "missing_cancel_evidence"
                    val invoked = request.cancelInvokedUs ?: return "missing_cancel_evidence"
                    val returned = request.cancelReturnedUs ?: return "missing_cancel_evidence"
                    if (request.cancelCause != "phase_deadline" || requested < execution.plannedEndUs ||
                        invoked < requested || returned < invoked || returned > execution.snapshotUs || finished < invoked) {
                        return "unconfirmed_deadline_cancellation"
                    }
                    if (request.ioAfterCancel != null && (request.ioAfterCancelSawCancelled != true ||
                            request.ioAfterCancelUs == null || request.ioAfterCancelUs < invoked || request.ioAfterCancelUs > finished)) {
                        return "unconfirmed_cancel_io"
                    }
                }
                else -> return "unknown_request_termination"
            }
        }
        val byId = requests.associateBy { it.requestId }
        val byteTotals = mutableMapOf<String, Long>()
        var previous = 0L
        var previousAt = stage.startUs
        for (observation in stage.byteObservations) {
            if (observation.atUs < previousAt || observation.totalBytes < previous ||
                observation.totalBytes > stage.transferBytes) return "invalid_byte_observations"
            val added = observation.totalBytes - previous
            if (added > 0L) {
                val source = byId[observation.requestId] ?: return "missing_byte_source"
                val bodyStart = source.bodyStartedUs ?: return "missing_body_start"
                if (observation.atUs < bodyStart || observation.atUs > checkNotNull(source.callbackFinishedUs) ||
                    (source.bodyCompletedUs != null && observation.atUs > source.bodyCompletedUs)) return "byte_source_time_mismatch"
                byteTotals[source.requestId] = (byteTotals[source.requestId] ?: 0L) + added
            }
            previous = observation.totalBytes
            previousAt = observation.atUs
        }
        if (previous != stage.transferBytes || requests.any { (byteTotals[it.requestId] ?: 0L) != it.observedBytes }) {
            return "request_byte_mismatch"
        }
        return null
    }

    private fun exclusionReason(stage: ResponsivenessStage, sample: ResponsivenessEcho): String? {
        if (sample.stage != stage.stage) return "stage_mismatch"
        if (sample.error != null) return "request_error"
        if (sample.httpCode == null || sample.httpCode !in 200..299) return "http_not_successful"
        val t1 = sample.t1Us ?: return "missing_timestamp"
        val t2 = sample.t2Us ?: return "missing_timestamp"
        val t3 = sample.t3Us ?: return "missing_timestamp"
        if (sample.t0Us < 0L || t1 < 0L || t2 < t1 || t3 < sample.t0Us) return "invalid_timestamp_order"
        val elapsed = t3 - sample.t0Us
        if (t2 - t1 > elapsed) return "server_processing_exceeds_elapsed"
        if (sample.t0Us < stage.startUs || t3 > stage.endUs) return "outside_stage"
        if (stage.stage != "idle" && (sample.bytesBefore < 0L || sample.bytesAfter <= sample.bytesBefore)) {
            return "no_observed_byte_overlap"
        }
        if (stage.stage != "idle") {
            // Cancellation may finish draining after the measurement phase; retain those points
            // in raw, but do not let them certify or invalidate an in-phase observation.
            val observations = stage.byteObservations.filter { it.atUs >= stage.startUs && it.atUs <= stage.endUs }
            if (observations.isEmpty()) return "missing_byte_observations"
            if (observations.any { it.totalBytes < 0L || it.totalBytes > stage.transferBytes } ||
                observations.zipWithNext().any { (before, after) -> after.atUs < before.atUs || after.totalBytes < before.totalBytes }
            ) return "invalid_byte_observations"
            val before = observations.lastOrNull { it.atUs < sample.t0Us }?.totalBytes ?: 0L
            val after = observations.lastOrNull { it.atUs <= t3 }?.totalBytes ?: 0L
            if (sample.bytesBefore != before || sample.bytesAfter != after) return "byte_observation_mismatch"
            if (after <= before) return "no_observed_byte_overlap"
        }
        return null
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
