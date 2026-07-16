package com.aneb.probe.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TokenSimTaskPlan(
    @SerialName("contract_version") val contractVersion: String = "aneb-token-task-v1",
    @SerialName("task_id") val taskId: String,
    @SerialName("workload_kind") val workloadKind: String,
    val seed: Long,
    @SerialName("processing_ms") val processingMs: Double,
    @SerialName("upload_payload_bytes") val uploadPayloadBytes: Long,
    @SerialName("token_intervals_ms") val tokenIntervalsMs: List<Double>,
    @SerialName("token_sizes_bytes") val tokenSizesBytes: List<Int>,
)

@Serializable
data class TokenSimPrelude(
    @SerialName("contract_version") val contractVersion: String,
    @SerialName("task_id") val taskId: String,
    @SerialName("workload_kind") val workloadKind: String,
    @SerialName("upload_bytes") val uploadBytes: Long,
    @SerialName("upload_recv_start_us") val uploadRecvStartUs: Long,
    @SerialName("upload_recv_end_us") val uploadRecvEndUs: Long,
    @SerialName("processing_start_us") val processingStartUs: Long,
    @SerialName("processing_deadline_us") val processingDeadlineUs: Long,
    val observed: String,
)

@Serializable
data class TokenSimToken(
    val seq: Int,
    @SerialName("sched_us") val schedUs: Long,
    @SerialName("pre_flush_us") val preFlushUs: Long,
    @SerialName("size_bytes") val sizeBytes: Int,
    val payload: String,
)

@Serializable
data class TokenSimSummary(
    @SerialName("task_id") val taskId: String,
    val tokens: Int,
    @SerialName("processing_ready_us") val processingReadyUs: Long,
    @SerialName("flush_return_us") val flushReturnUs: List<Long>,
    @SerialName("timer_late_us") val timerLateUs: List<Long>,
    @SerialName("flush_block_us") val flushBlockUs: List<Long>,
    @SerialName("carryover_us") val carryoverUs: List<Long>,
)

data class TokenSimArrival(
    val event: TokenSimToken,
    val arrivalNanos: Long,
)

data class TokenSimTaskResult(
    val requestStartNanos: Long,
    val uploadWriteStartNanos: Long?,
    val uploadWriteEndNanos: Long?,
    val prelude: TokenSimPrelude?,
    val preludeArrivalNanos: Long?,
    val arrivals: List<TokenSimArrival>,
    val summary: TokenSimSummary?,
    val summaryArrivalNanos: Long?,
    val httpCode: Int?,
    val error: String?,
    val timing: TimingRecord?,
) {
    val completed: Boolean
        get() = error == null && prelude != null && summary != null &&
            arrivals.size == summary.tokens
}
