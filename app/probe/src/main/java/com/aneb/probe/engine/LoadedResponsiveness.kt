package com.aneb.probe.engine

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
    private val codec = Json { encodeDefaults = true; prettyPrint = true }

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
        val stages = report.stages.map { stage ->
            val stageReason = when {
                report.status != "completed" -> "run_not_completed:${report.status}"
                report.methodVersion != "loaded-responsiveness-v1" -> "unsupported_method_version"
                report.clockSource != "android.os.SystemClock.elapsedRealtimeNanos" || report.clockUnit != "us" -> "unsupported_clock"
                stage.stage !in setOf("idle", "download", "upload") -> "unsupported_stage"
                stage.startUs < 0L || stage.endUs < stage.startUs -> "invalid_stage_bounds"
                stage.stage != "idle" && "no_transfer_completed_before_phase_deadline" in stage.errors -> "no_completed_transfer"
                stage.stage != "idle" && stage.transferBytes <= 0L -> "no_transfer_bytes"
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
        val idle = stages.singleOrNull { it.stage == "idle" }?.valueMs
        val download = stages.singleOrNull { it.stage == "download" }?.valueMs
        val upload = stages.singleOrNull { it.stage == "upload" }?.valueMs
        return LoadedResponsivenessSummary(
            idle, download, upload,
            if (download != null && idle != null) download - idle else null,
            if (upload != null && idle != null) upload - idle else null,
            stages,
        )
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
