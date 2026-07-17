package com.aneb.probe.engine

import android.content.Context
import com.aneb.probe.data.EnvEvent
import com.aneb.probe.data.EnvEventEntity
import com.aneb.probe.data.RadioSampleEntity
import com.aneb.probe.radio.RadioCollector
import com.aneb.probe.radio.RadioSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentLinkedQueue

/** Frozen public-Android radio evidence. Coordinates never enter this shareable structure. */
internal data class FormalRadioEvidence(
    val collectionStatus: String,
    val unavailableReason: String?,
    val samples: List<RadioSample>,
    val rawSamples: List<RadioSample>,
    val events: List<EnvEvent>,
) {
    init {
        require(collectionStatus in setOf("collected", "not_collected", "permission_denied", "unavailable"))
        require((collectionStatus == "collected") == samples.isNotEmpty())
        require((collectionStatus == "collected") == (unavailableReason == null))
    }

    val representative: RadioSample?
        get() = samples.lastOrNull { !it.stale } ?: samples.lastOrNull()

    fun radioEntities(runId: String): List<RadioSampleEntity> = rawSamples.map { it.toEntity(runId) }

    fun eventEntities(runId: String): List<EnvEventEntity> = events.map { it.toEntity(runId) }

    companion object {
        fun from(rawSamples: List<RadioSample>, events: List<EnvEvent>): FormalRadioEvidence {
            val orderedSamples = rawSamples.sortedBy { it.tsNanos }
            val orderedEvents = events.sortedBy { it.tsNanos }
            val permissionDenied = orderedSamples.any { it.networkType == "permission_denied" }
            val telephonyUnavailable = orderedSamples.any { it.networkType == "telephony_unavailable" }
            val observed = orderedSamples.filterNot {
                it.networkType == "permission_denied" || it.networkType == "telephony_unavailable"
            }
            return when {
                observed.isNotEmpty() -> FormalRadioEvidence(
                    collectionStatus = "collected",
                    unavailableReason = null,
                    samples = observed,
                    rawSamples = orderedSamples,
                    events = orderedEvents,
                )
                permissionDenied -> unavailable(
                    status = "permission_denied",
                    reason = "android_radio_permissions_denied",
                    rawSamples = orderedSamples,
                    events = orderedEvents,
                )
                telephonyUnavailable -> unavailable(
                    status = "unavailable",
                    reason = "telephony_service_unavailable",
                    rawSamples = orderedSamples,
                    events = orderedEvents,
                )
                else -> unavailable(
                    status = "not_collected",
                    reason = "no_radio_sample_before_result_finalization",
                    rawSamples = orderedSamples,
                    events = orderedEvents,
                )
            }
        }

        fun notCollected(reason: String): FormalRadioEvidence = unavailable("not_collected", reason)

        private fun unavailable(
            status: String,
            reason: String,
            rawSamples: List<RadioSample> = emptyList(),
            events: List<EnvEvent> = emptyList(),
        ) = FormalRadioEvidence(
            collectionStatus = status,
            unavailableReason = reason,
            samples = emptyList(),
            rawSamples = rawSamples,
            events = events,
        )
    }
}

/**
 * Per-run lifecycle wrapper. It subscribes to RadioCollector before sampling starts, and freeze()
 * cancels and joins the whole private subtree before producing an immutable snapshot.
 */
internal class FormalRadioEvidenceCollector(context: Context) {
    private val source = RadioCollector(context)
    private val samples = ConcurrentLinkedQueue<RadioSample>()
    private val events = ConcurrentLinkedQueue<EnvEvent>()
    private val freezeMutex = Mutex()
    private var lifecycleJob: Job? = null
    private var frozen: FormalRadioEvidence? = null

    fun start(parentScope: CoroutineScope) {
        check(lifecycleJob == null && frozen == null) { "formal_radio_collector_already_started" }
        val job = SupervisorJob(parentScope.coroutineContext[Job])
        lifecycleJob = job
        val scope = CoroutineScope(parentScope.coroutineContext + job)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            source.events.collect(events::add)
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            source.start(scope).collect(samples::add)
        }
    }

    suspend fun freeze(): FormalRadioEvidence = freezeMutex.withLock {
        frozen?.let { return@withLock it }
        lifecycleJob?.cancelAndJoin()
        lifecycleJob = null
        FormalRadioEvidence.from(samples.toList(), events.toList()).also { frozen = it }
    }

    suspend fun close() {
        withContext(NonCancellable) { freeze() }
    }
}

internal fun FormalRadioEvidence.contextJson(): JsonObject {
    val sample = representative
    return buildJsonObject {
        put("collection_status", collectionStatus)
        put("unavailable_reason", unavailableReason)
        put("operator_name", sample?.operatorName)
        put("network_type", sample?.networkType)
        put("override_type", sample?.overrideType)
        put("nr_state", sample?.nrState)
        put("rat", sample?.rat)
        put("rsrp_dbm", sample?.rsrp)
        put("rsrq_db", sample?.rsrq)
        put("sinr_db", sample?.sinr)
        put("sample_count", samples.size)
        put("samples", buildJsonArray {
            samples.forEach { radio -> add(radio.sampleJson()) }
        })
        put(
            "evidence_ref_ids",
            if (collectionStatus == "collected") JsonArray(listOf(JsonPrimitive("radio-context")))
            else buildJsonArray { },
        )
    }
}

internal fun FormalRadioEvidence.evidenceRefJson(): JsonObject = buildJsonObject {
    put("ref_id", "radio-context")
    put("kind", "inline_json_pointer")
    put("uri", "#/context/radio/samples")
    put("media_type", "application/json")
    put("digest", JsonNull)
    put("record_count", samples.size)
    put("redaction", "location_removed")
    put(
        "description",
        "Inline 1Hz public Android radio observations; coordinates are excluded and active path transport is recorded separately.",
    )
}

internal fun FormalRadioEvidence.environmentEventsJson(): JsonArray = buildJsonArray {
    events.forEach { event ->
        add(buildJsonObject {
            put("elapsed_realtime_nanos", event.tsNanos)
            put("type", event.type.name.lowercase())
            put("detail", event.detail)
        })
    }
}

private fun RadioSample.sampleJson(): JsonObject = buildJsonObject {
    put("elapsed_realtime_nanos", tsNanos)
    put("cell_elapsed_realtime_nanos", cellTsNanos)
    put("stale", stale)
    put("sub_id", subId.takeIf { it >= 0 })
    put("sub_switched", subSwitched)
    put("network_type", networkType)
    put("override_type", overrideType)
    put("nr_state", nrState)
    put("rat", rat)
    put("pci", pci)
    put("tac", tac)
    put("arfcn", arfcn)
    put("rsrp_dbm", rsrp)
    put("rsrq_db", rsrq)
    put("sinr_db", sinr)
    put("operator_name", operatorName)
}
