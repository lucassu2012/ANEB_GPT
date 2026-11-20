package com.aneb.probe.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Deterministic `aneb-behavior-trace-v1` JSONL plan parser & structural validator.
 *
 * Enforces contract alignment with [ScenarioProfile] (`trace_contract_version`, `model_id`,
 * `model_version`, `seed`), contiguous `event_index`, monotonic `planned_offset_ms`, and zero
 * leaked network outcome fields (`arrival_ms`, `network_delay_ms`, `packet_loss`, `measured_rtt_ms`).
 */
@Serializable
data class BehaviorTraceEvent(
    @SerialName("trace_contract_version") val traceContractVersion: String,
    @SerialName("model_id") val modelId: String,
    @SerialName("model_version") val modelVersion: String,
    val seed: Long,
    @SerialName("event_index") val eventIndex: Int,
    @SerialName("business_type") val businessType: String = "",
    @SerialName("task_id") val taskId: String = "",
    @SerialName("workload_kind") val workloadKind: String = "",
    val repetition: Int = 0,
    @SerialName("event_type") val eventType: String,
    @SerialName("planned_offset_ms") val plannedOffsetMs: Double,
    val bytes: Long? = null,
    val chunks: Int? = null,
    @SerialName("chunk_index") val chunkIndex: Int? = null,
    @SerialName("duration_ms") val durationMs: Double? = null,
    val seq: Long? = null,
    @SerialName("interval_state") val intervalState: String? = null,
    @SerialName("planned_interval_ms") val plannedIntervalMs: Double? = null,
    @SerialName("token_bytes") val tokenBytes: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
)

data class PlannedToken(
    val seq: Long,
    val plannedOffsetMs: Double,
    val plannedIntervalMs: Double,
    val intervalState: String,
    val tokenBytes: Int,
)

data class PlannedTask(
    val taskIndex: Int,
    val taskId: String,
    val workloadKind: String,
    val repetition: Int,
    val uploadBytes: Long,
    val uploadChunks: List<Int>,
    val uploadChunkCadenceMs: Double,
    val processingDelayMs: Double,
    val tokenPlans: List<PlannedToken>,
    val artifactBytes: Long,
    val artifactChunkBytes: Int,
    val plannedDurationMs: Double,
    val interTaskGapMs: Double,
)

data class BehaviorTracePlan(
    val contractVersion: String,
    val modelId: String,
    val modelVersion: String,
    val seed: Long,
    val eventCount: Int,
    val totalPlannedSpanMs: Double,
    val tasks: List<PlannedTask>,
) {
    val totalPlannedTokens: Int get() = tasks.sumOf { it.tokenPlans.size }

    companion object {
        const val CONTRACT_VERSION = "aneb-behavior-trace-v1"
        private val FORBIDDEN_NETWORK_KEYS = setOf(
            "arrival_ms",
            "network_delay_ms",
            "packet_loss",
            "measured_rtt_ms",
        )
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(jsonl: String, profile: ScenarioProfile): BehaviorTracePlan {
            val lines = jsonl.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            require(lines.isNotEmpty()) { "behavior trace is empty" }

            val phase = profile.phases.firstOrNull { it.type == ProfilePhase.TYPE_BEHAVIOR_TRACE }
            val expectedSeed = profile.trace?.seed?.takeIf { it != 0L } ?: phase?.seed ?: 0L
            val expectedModelId = profile.business.behaviorModelId
            val expectedModelVersion = profile.business.behaviorModelVersion

            val events = ArrayList<BehaviorTraceEvent>(lines.size)
            var prevOffset = Double.NEGATIVE_INFINITY
            lines.forEachIndexed { idx, line ->
                val obj = json.parseToJsonElement(line) as? JsonObject
                    ?: throw IllegalArgumentException("trace line $idx is not a JSON object")
                val leaked = FORBIDDEN_NETWORK_KEYS.filter { it in obj }
                require(leaked.isEmpty()) {
                    "network_outcome_leaked_into_business_trace:${leaked.joinToString(",")}"
                }
                val ev = json.decodeFromJsonElement(BehaviorTraceEvent.serializer(), obj)
                require(ev.traceContractVersion == CONTRACT_VERSION) {
                    "unexpected trace_contract_version:${ev.traceContractVersion}"
                }
                if (expectedModelId.isNotBlank()) {
                    require(ev.modelId == expectedModelId) {
                        "model_id mismatch: expected=$expectedModelId actual=${ev.modelId}"
                    }
                }
                if (expectedModelVersion.isNotBlank()) {
                    require(ev.modelVersion == expectedModelVersion) {
                        "model_version mismatch: expected=$expectedModelVersion actual=${ev.modelVersion}"
                    }
                }
                if (expectedSeed != 0L) {
                    require(ev.seed == expectedSeed) {
                        "seed mismatch: expected=$expectedSeed actual=${ev.seed}"
                    }
                }
                require(ev.eventIndex == idx) {
                    "event_index_not_contiguous: expected=$idx actual=${ev.eventIndex}"
                }
                require(ev.plannedOffsetMs >= prevOffset) {
                    "planned_offsets_not_monotonic at index=$idx: ${ev.plannedOffsetMs} < $prevOffset"
                }
                prevOffset = ev.plannedOffsetMs
                events.add(ev)
            }

            val grouped = linkedMapOf<String, MutableList<BehaviorTraceEvent>>()
            var currentTaskId: String? = null
            val completedTaskIds = HashSet<String>()
            for (ev in events) {
                require(ev.taskId.isNotBlank()) { "missing task_id at event_index=${ev.eventIndex}" }
                if (ev.taskId != currentTaskId) {
                    require(ev.taskId !in completedTaskIds) {
                        "interleaved_task_id:${ev.taskId} at event_index=${ev.eventIndex}"
                    }
                    currentTaskId?.let { completedTaskIds.add(it) }
                    currentTaskId = ev.taskId
                }
                grouped.getOrPut(ev.taskId) { mutableListOf() }.add(ev)
            }

            val rawTasks = grouped.entries.mapIndexed { taskIndex, (taskId, taskEvents) ->
                val first = taskEvents.first()
                val startMs = first.plannedOffsetMs
                val endMs = taskEvents.last().plannedOffsetMs
                val uploadChunks = taskEvents
                    .filter { it.eventType == "upload_chunk_plan" }
                    .mapNotNull { it.bytes?.toInt() }
                val uploadComplete = taskEvents.firstOrNull { it.eventType == "upload_complete_plan" }
                val uploadBytes = uploadComplete?.bytes ?: uploadChunks.sumOf { it.toLong() }
                val chunkOffsets = taskEvents
                    .filter { it.eventType == "upload_chunk_plan" }
                    .map { it.plannedOffsetMs }
                val cadenceMs = if (chunkOffsets.size >= 2) {
                    (chunkOffsets.last() - chunkOffsets.first()) / (chunkOffsets.size - 1)
                } else {
                    uploadComplete?.let { it.plannedOffsetMs - startMs } ?: 0.0
                }
                val procEnd = taskEvents.firstOrNull { it.eventType == "processing_end_plan" }
                val processingDelayMs = procEnd?.durationMs ?: 0.0
                val tokenPlans = taskEvents
                    .filter { it.eventType == "token_emit_plan" }
                    .map { ev ->
                        PlannedToken(
                            seq = ev.seq ?: 0L,
                            plannedOffsetMs = ev.plannedOffsetMs,
                            plannedIntervalMs = ev.plannedIntervalMs ?: 0.0,
                            intervalState = ev.intervalState ?: "NORMAL",
                            tokenBytes = ev.tokenBytes ?: 64,
                        )
                    }
                val artifactComplete = taskEvents.firstOrNull { it.eventType == "artifact_complete_plan" }
                val artifactBytes = artifactComplete?.bytes
                    ?: taskEvents.firstOrNull { it.eventType == "artifact_start_plan" }?.bytes
                    ?: 0L
                val artifactChunkBytes = taskEvents
                    .firstOrNull { it.eventType == "artifact_chunk_plan" }
                    ?.bytes?.toInt()
                    ?: (64 * 1024)

                RawTask(
                    taskIndex = taskIndex,
                    taskId = taskId,
                    workloadKind = first.workloadKind,
                    repetition = first.repetition,
                    startMs = startMs,
                    endMs = endMs,
                    uploadBytes = uploadBytes,
                    uploadChunks = uploadChunks,
                    uploadChunkCadenceMs = cadenceMs.coerceAtLeast(0.0),
                    processingDelayMs = processingDelayMs.coerceAtLeast(0.0),
                    tokenPlans = tokenPlans,
                    artifactBytes = artifactBytes,
                    artifactChunkBytes = artifactChunkBytes,
                )
            }

            val tasks = rawTasks.mapIndexed { idx, raw ->
                val gapMs = if (idx < rawTasks.lastIndex) {
                    (rawTasks[idx + 1].startMs - raw.endMs).coerceAtLeast(0.0)
                } else {
                    0.0
                }
                PlannedTask(
                    taskIndex = raw.taskIndex,
                    taskId = raw.taskId,
                    workloadKind = raw.workloadKind,
                    repetition = raw.repetition,
                    uploadBytes = raw.uploadBytes,
                    uploadChunks = raw.uploadChunks,
                    uploadChunkCadenceMs = raw.uploadChunkCadenceMs,
                    processingDelayMs = raw.processingDelayMs,
                    tokenPlans = raw.tokenPlans,
                    artifactBytes = raw.artifactBytes,
                    artifactChunkBytes = raw.artifactChunkBytes,
                    plannedDurationMs = (raw.endMs - raw.startMs).coerceAtLeast(0.0),
                    interTaskGapMs = gapMs,
                )
            }

            val firstEvent = events.first()
            return BehaviorTracePlan(
                contractVersion = firstEvent.traceContractVersion,
                modelId = firstEvent.modelId,
                modelVersion = firstEvent.modelVersion,
                seed = firstEvent.seed,
                eventCount = events.size,
                totalPlannedSpanMs = events.last().plannedOffsetMs - firstEvent.plannedOffsetMs,
                tasks = tasks,
            )
        }

        private data class RawTask(
            val taskIndex: Int,
            val taskId: String,
            val workloadKind: String,
            val repetition: Int,
            val startMs: Double,
            val endMs: Double,
            val uploadBytes: Long,
            val uploadChunks: List<Int>,
            val uploadChunkCadenceMs: Double,
            val processingDelayMs: Double,
            val tokenPlans: List<PlannedToken>,
            val artifactBytes: Long,
            val artifactChunkBytes: Int,
        )
    }
}
