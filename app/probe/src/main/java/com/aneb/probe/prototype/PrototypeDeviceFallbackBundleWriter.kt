package com.aneb.probe.prototype

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a deterministic, explicitly non-canonical local fallback snapshot.
 *
 * This layer accepts only the persisted campaign/result projection required by the fallback and
 * deliberately has no Room, URL, MediaStore, sharing, device identity, or canonical report input.
 */
internal object PrototypeDeviceFallbackBundleWriter {
    data class Snapshot(
        val summary: PrototypeQuickCampaignRunner.CampaignSummary,
        val runs: List<Run>,
        val capabilityResponseUtf8: ByteArray,
        val eventJsonUtf8Records: List<ByteArray>,
    )

    data class Run(
        val runIndex: Int,
        val runId: String,
        val conditionId: String,
        val status: PrototypeQuickCampaignRunner.RunStatus,
        val taskSuccess: Boolean,
        val scoreEligible: Boolean,
        val eventsExpected: Int,
        val eventsReceived: Int,
        val failureReason: String?,
        val terminalReceiptValid: Boolean?,
        val metrics: PrototypeQuickCampaignRunner.RunMetrics?,
    )

    fun write(snapshot: Snapshot, destination: OutputStream) {
        val marker = MARKER.toByteArray(Charsets.UTF_8)
        val capability = snapshot.capabilityResponseUtf8.copyOf()
        val campaign = campaignSnapshot(snapshot).toString().toByteArray(Charsets.UTF_8)
        val events = ByteArrayOutputStream().apply {
            snapshot.eventJsonUtf8Records.forEach { record ->
                write(record.copyOf())
                write(LF)
            }
        }.toByteArray()
        val payloads = linkedMapOf(
            MARKER_NAME to marker,
            CAPABILITY_NAME to capability,
            CAMPAIGN_NAME to campaign,
            EVENTS_NAME to events,
        )
        val checksums = payloads.entries.joinToString(separator = "") { (name, bytes) ->
            "${sha256(bytes)}  $name\n"
        }.toByteArray(Charsets.UTF_8)

        ZipOutputStream(NonClosingOutputStream(destination)).use { zip ->
            payloads.forEach { (name, bytes) -> zip.writeStored(name, bytes) }
            zip.writeStored(CHECKSUMS_NAME, checksums)
        }
    }

    private fun campaignSnapshot(snapshot: Snapshot): JsonObject = buildJsonObject {
        val summary = snapshot.summary
        put("format_version", JsonPrimitive(FORMAT_VERSION))
        put("publication_status", JsonPrimitive(PUBLICATION_STATUS))
        put("canonical_bundle", JsonPrimitive(false))
        put("campaign_id", JsonPrimitive(summary.campaignId))
        put("campaign_mode", JsonPrimitive(summary.campaignMode))
        put("campaign_status", JsonPrimitive(summary.status.name))
        put("planned_runs", JsonPrimitive(summary.plannedRuns))
        put("attempted_runs", JsonPrimitive(summary.attemptedRuns))
        put("successful_runs", JsonPrimitive(summary.successfulRuns))
        put("failed_runs", JsonPrimitive(summary.failedRuns))
        put("not_started_runs", JsonPrimitive(summary.notStartedRuns))
        put("success_rate", JsonPrimitive(summary.successRate))
        put("rpi_label", JsonPrimitive(RPI_LABEL))
        put("rpi_disclosure", JsonPrimitive(RPI_DISCLOSURE))
        put("runs", buildJsonArray {
            snapshot.runs.forEach { run -> add(runJson(run)) }
        })
        put("condition_summaries", buildJsonArray {
            summary.conditionSummaries.forEach { condition -> add(conditionJson(condition)) }
        })
    }

    private fun runJson(run: Run): JsonObject = buildJsonObject {
        put("run_index", JsonPrimitive(run.runIndex))
        put("run_id", JsonPrimitive(run.runId))
        put("condition_id", JsonPrimitive(run.conditionId))
        put("status", JsonPrimitive(run.status.name))
        put("task_success", JsonPrimitive(run.taskSuccess))
        put("score_eligible", JsonPrimitive(run.scoreEligible))
        put("events_expected", JsonPrimitive(run.eventsExpected))
        put("events_received", JsonPrimitive(run.eventsReceived))
        put("failure_reason", run.failureReason.jsonStringOrNull())
        put("terminal_receipt_valid", run.terminalReceiptValid.jsonBooleanOrNull())
        put("metrics", run.metrics?.let(::metricsJson) ?: JsonNull)
    }

    private fun metricsJson(metrics: PrototypeQuickCampaignRunner.RunMetrics): JsonObject =
        buildJsonObject {
            put("ttft_ms", metrics.ttftMs.jsonNumberOrNull())
            put("completion_ms", metrics.completionMs.jsonNumberOrNull())
            put("stream_span_ms", metrics.streamSpanMs.jsonNumberOrNull())
            put("stream_event_rate_eps", metrics.streamEventRateEps.jsonNumberOrNull())
            put("stall_threshold_ms", metrics.stallThresholdMs.jsonNumberOrNull())
            put("stall_count", metrics.stallCount.jsonNumberOrNull())
            put("stall_duration_ms", metrics.stallDurationMs.jsonNumberOrNull())
            put("stall_fraction", metrics.stallFraction.jsonNumberOrNull())
        }

    private fun conditionJson(
        condition: PrototypeQuickCampaignRunner.ConditionSummary,
    ): JsonObject = buildJsonObject {
        put("condition_id", JsonPrimitive(condition.conditionId))
        put("planned_runs", JsonPrimitive(condition.plannedRuns))
        put("attempted_runs", JsonPrimitive(condition.attemptedRuns))
        put("successful_runs", JsonPrimitive(condition.successfulRuns))
        put("failed_runs", JsonPrimitive(condition.failedRuns))
        put("not_started_runs", JsonPrimitive(condition.notStartedRuns))
        put("success_rate", JsonPrimitive(condition.successRate))
        put("confidence", JsonPrimitive(condition.confidence.name))
        put("median_ttft_ms", condition.medianTtftMs.jsonNumberOrNull())
        put("min_ttft_ms", condition.minTtftMs.jsonNumberOrNull())
        put("max_ttft_ms", condition.maxTtftMs.jsonNumberOrNull())
        put("median_completion_ms", condition.medianCompletionMs.jsonNumberOrNull())
        put("min_completion_ms", condition.minCompletionMs.jsonNumberOrNull())
        put("max_completion_ms", condition.maxCompletionMs.jsonNumberOrNull())
        put("median_stream_event_rate_eps", condition.medianStreamEventRateEps.jsonNumberOrNull())
        put("median_stall_count", condition.medianStallCount.jsonNumberOrNull())
        put("median_stall_duration_ms", condition.medianStallDurationMs.jsonNumberOrNull())
        put("median_stall_fraction", condition.medianStallFraction.jsonNumberOrNull())
        put("rpi", condition.rpi.jsonNumberOrNull())
        put("rpi_policy_id", JsonPrimitive(condition.rpiPolicyId))
        put("primary_null_reason", condition.primaryNullReason.jsonStringOrNull())
        put(
            "all_null_reasons",
            condition.allNullReasons?.let { reasons ->
                JsonArray(reasons.map(::JsonPrimitive))
            } ?: JsonNull,
        )
    }

    private fun ZipOutputStream.writeStored(name: String, bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes) }
        val entry = ZipEntry(name).apply {
            time = FIXED_ZIP_TIMESTAMP_MS
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            this.crc = crc.value
        }
        putNextEntry(entry)
        write(bytes)
        closeEntry()
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val encoded = CharArray(digest.size * 2)
        digest.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            encoded[index * 2] = HEX[value ushr 4]
            encoded[index * 2 + 1] = HEX[value and 0x0f]
        }
        return encoded.concatToString()
    }

    private fun String?.jsonStringOrNull() = this?.let(::JsonPrimitive) ?: JsonNull
    private fun Boolean?.jsonBooleanOrNull() = this?.let(::JsonPrimitive) ?: JsonNull
    private fun Double?.jsonNumberOrNull() = this?.let(::JsonPrimitive) ?: JsonNull
    private fun Int?.jsonNumberOrNull() = this?.let(::JsonPrimitive) ?: JsonNull

    private class NonClosingOutputStream(destination: OutputStream) :
        FilterOutputStream(destination) {
        override fun close() {
            flush()
        }
    }

    private const val FORMAT_VERSION = "aneb-prototype-device-fallback-0.1"
    private const val PUBLICATION_STATUS = "device_fallback_unverified"
    private const val MARKER_NAME = "DEVICE_FALLBACK_UNVERIFIED.txt"
    private const val CAPABILITY_NAME = "capability-response.json"
    private const val CAMPAIGN_NAME = "campaign-snapshot.json"
    private const val EVENTS_NAME = "events.jsonl"
    private const val CHECKSUMS_NAME = "SHA256SUMS.txt"
    private const val FIXED_ZIP_TIMESTAMP_MS = 0L
    private const val LF = '\n'.code
    private const val RPI_LABEL = "Relative Prototype Index (same-campaign synthetic comparison)"
    private const val RPI_DISCLOSURE =
        "This score compares deterministic application-layer conditions against this campaign's Baseline. " +
            "It is not a formal ANEB industry score and does not represent a third-party AI application's " +
            "network requirement."
    private const val CLAIM =
        "ANEB Prototype 0.1 measures Android-client-observed timing against a local ANEB probe under " +
            "deterministic synthetic application-layer schedules. It does not emulate or measure packet " +
            "loss, RAN/core/operator quality, public-Internet quality, a real application, or model inference."
    private const val MARKER =
        "device_fallback_unverified\n" +
            "Local device evidence · unverified\n" +
            "This local fallback does not satisfy G4/G5 and is not canonical evidence.\n" +
            "$CLAIM\n"
    private val HEX = "0123456789abcdef".toCharArray()
}
