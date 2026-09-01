package com.aneb.probe.prototype

import com.aneb.probe.net.RawSseEvent
import com.aneb.probe.net.RawSseStream
import com.aneb.probe.net.AndroidMonotonicNanosClock
import com.aneb.probe.net.MonotonicNanosClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val CONTENT_SEQUENCE_ERROR =
    "prototype SSE content events must have exact seq 1 through 120"
private const val CONTENT_IDENTITY_ERROR =
    "prototype SSE content event identity must match the run"
private const val CONTENT_ARRIVAL_CHRONOLOGY_ERROR =
    "prototype SSE content arrival timestamps must be non-negative and nondecreasing"
private const val TERMINAL_IDENTITY_ERROR =
    "prototype SSE terminal receipt identity must match the run"
private const val RUN_STARTED_EVENT_TYPE_ERROR =
    "prototype SSE run_started payload event_type must match the SSE event"
private const val TERMINAL_COMPLETION_ERROR =
    "prototype SSE terminal receipt must report complete 120-event delivery"
private const val REQUEST_RUN_IDENTITY_ERROR =
    "prototype SSE run identity must match the outgoing request"
private const val CONDITION_IDENTITY_ERROR =
    "prototype SSE condition identity must match the outgoing request"
private const val TRUNCATED_STREAM_ERROR = "prototype SSE stream has a truncated tail"
private const val MISSING_TERMINAL_STREAM_ERROR =
    "prototype SSE stream ended without a terminal done event"
private const val MAX_JSON_NESTING_DEPTH = 64
private const val UNSET_TIMESTAMP = -1L

private data class ContentRunIdentity(
    val campaignId: String,
    val runId: String,
)

private data class ValidatedContentPayload(
    val dataPayload: String,
    val envelope: JsonObject,
)

private class DuplicateContentIdentityKeyException : IllegalArgumentException()

private class DuplicateContentSequenceKeyException : IllegalArgumentException()

private class DuplicateTerminalIdentityKeyException : IllegalArgumentException()

private class DuplicateTerminalCompletionFactKeyException : IllegalArgumentException()

private class DuplicateRunStartedEventTypeKeyException : IllegalArgumentException()

private class DuplicateConditionIdentityKeyException : IllegalArgumentException()

private fun requireJsonNestingWithinBudget(payload: String) {
    var depth = 0
    var inString = false
    var escaped = false
    payload.forEach { character ->
        if (inString) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> inString = false
            }
            return@forEach
        }
        when (character) {
            '"' -> inString = true
            '{', '[' -> {
                depth += 1
                require(depth <= MAX_JSON_NESTING_DEPTH) { CONTENT_SEQUENCE_ERROR }
            }
            '}', ']' -> if (depth > 0) depth -= 1
        }
    }
}

private object ContentDetailsDuplicateKeyProbe : DeserializationStrategy<Unit> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "PrototypeContentDetailsDuplicateKeyProbe",
    ) {
        element("seq", JsonElement.serializer().descriptor, isOptional = true)
        element("planned_offset_ms", JsonElement.serializer().descriptor, isOptional = true)
        element("payload_id", JsonElement.serializer().descriptor, isOptional = true)
        element("profile_manifest_sha256", JsonElement.serializer().descriptor, isOptional = true)
        element("schedule_hash", JsonElement.serializer().descriptor, isOptional = true)
    }

    override fun deserialize(decoder: Decoder) {
        decoder.decodeStructure(descriptor) {
            val seen = BooleanArray(descriptor.elementsCount)
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break
                    in seen.indices -> {
                        if (seen[index]) {
                            if (index == 0) throw DuplicateContentSequenceKeyException()
                            throw IllegalArgumentException(CONTENT_SEQUENCE_ERROR)
                        }
                        seen[index] = true
                        decodeSerializableElement(descriptor, index, JsonElement.serializer())
                    }
                    else -> decodeSerializableElement(descriptor, index, JsonElement.serializer())
                }
            }
        }
    }
}

private object ContentRootDuplicateKeyProbe : DeserializationStrategy<Unit> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "PrototypeContentRootDuplicateKeyProbe",
    ) {
        element("details", ContentDetailsDuplicateKeyProbe.descriptor, isOptional = true)
    }

    override fun deserialize(decoder: Decoder) {
        decoder.decodeStructure(descriptor) {
            var seenDetails = false
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break
                    0 -> {
                        if (seenDetails) throw DuplicateContentSequenceKeyException()
                        seenDetails = true
                        decodeSerializableElement(
                            descriptor,
                            index,
                            ContentDetailsDuplicateKeyProbe,
                        )
                    }
                    else -> decodeSerializableElement(descriptor, index, JsonElement.serializer())
                }
            }
        }
    }
}

private object ContentIdentityDuplicateKeyProbe : DeserializationStrategy<Unit> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "PrototypeContentIdentityDuplicateKeyProbe",
    ) {
        element("campaign_id", JsonElement.serializer().descriptor, isOptional = true)
        element("run_id", JsonElement.serializer().descriptor, isOptional = true)
    }

    override fun deserialize(decoder: Decoder) {
        decoder.decodeStructure(descriptor) {
            var seenCampaignId = false
            var seenRunId = false
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break
                    0 -> {
                        if (seenCampaignId) throw DuplicateContentIdentityKeyException()
                        seenCampaignId = true
                        decodeSerializableElement(descriptor, index, JsonElement.serializer())
                    }
                    1 -> {
                        if (seenRunId) throw DuplicateContentIdentityKeyException()
                        seenRunId = true
                        decodeSerializableElement(descriptor, index, JsonElement.serializer())
                    }
                    else -> decodeSerializableElement(descriptor, index, JsonElement.serializer())
                }
            }
        }
    }
}

private object RunStartedEventTypeDuplicateKeyProbe : DeserializationStrategy<Unit> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "PrototypeRunStartedEventTypeDuplicateKeyProbe",
    ) {
        element("event_type", JsonElement.serializer().descriptor, isOptional = true)
    }

    override fun deserialize(decoder: Decoder) {
        decoder.decodeStructure(descriptor) {
            var seenEventType = false
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break
                    0 -> {
                        if (seenEventType) throw DuplicateRunStartedEventTypeKeyException()
                        seenEventType = true
                        decodeSerializableElement(descriptor, index, JsonElement.serializer())
                    }
                    else -> decodeSerializableElement(descriptor, index, JsonElement.serializer())
                }
            }
        }
    }
}

private object TerminalDetailsIdentityDuplicateKeyProbe : DeserializationStrategy<Unit> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "PrototypeTerminalDetailsIdentityDuplicateKeyProbe",
    ) {
        element("campaign_id", JsonElement.serializer().descriptor, isOptional = true)
        element("run_id", JsonElement.serializer().descriptor, isOptional = true)
    }

    override fun deserialize(decoder: Decoder) {
        decoder.decodeStructure(descriptor) {
            var seenCampaignId = false
            var seenRunId = false
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break
                    0 -> {
                        if (seenCampaignId) throw DuplicateTerminalIdentityKeyException()
                        seenCampaignId = true
                        decodeSerializableElement(descriptor, index, JsonElement.serializer())
                    }
                    1 -> {
                        if (seenRunId) throw DuplicateTerminalIdentityKeyException()
                        seenRunId = true
                        decodeSerializableElement(descriptor, index, JsonElement.serializer())
                    }
                    else -> decodeSerializableElement(descriptor, index, JsonElement.serializer())
                }
            }
        }
    }
}

private object TerminalIdentityDuplicateKeyProbe : DeserializationStrategy<Unit> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "PrototypeTerminalIdentityDuplicateKeyProbe",
    ) {
        element("campaign_id", JsonElement.serializer().descriptor, isOptional = true)
        element("run_id", JsonElement.serializer().descriptor, isOptional = true)
        element(
            "details",
            TerminalDetailsIdentityDuplicateKeyProbe.descriptor,
            isOptional = true,
        )
    }

    override fun deserialize(decoder: Decoder) {
        decoder.decodeStructure(descriptor) {
            var seenCampaignId = false
            var seenRunId = false
            var seenDetails = false
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break
                    0 -> {
                        if (seenCampaignId) throw DuplicateTerminalIdentityKeyException()
                        seenCampaignId = true
                        decodeSerializableElement(descriptor, index, JsonElement.serializer())
                    }
                    1 -> {
                        if (seenRunId) throw DuplicateTerminalIdentityKeyException()
                        seenRunId = true
                        decodeSerializableElement(descriptor, index, JsonElement.serializer())
                    }
                    2 -> {
                        if (seenDetails) throw DuplicateTerminalIdentityKeyException()
                        seenDetails = true
                        decodeSerializableElement(
                            descriptor,
                            index,
                            TerminalDetailsIdentityDuplicateKeyProbe,
                        )
                    }
                    else -> decodeSerializableElement(descriptor, index, JsonElement.serializer())
                }
            }
        }
    }
}

private object TerminalCompletionDetailsDuplicateKeyProbe : DeserializationStrategy<Unit> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "PrototypeTerminalCompletionDetailsDuplicateKeyProbe",
    ) {
        element("planned_event_count", JsonElement.serializer().descriptor, isOptional = true)
        element("emitted_event_count", JsonElement.serializer().descriptor, isOptional = true)
        element("terminal_status", JsonElement.serializer().descriptor, isOptional = true)
    }

    override fun deserialize(decoder: Decoder) {
        decoder.decodeStructure(descriptor) {
            var seenPlannedEventCount = false
            var seenEmittedEventCount = false
            var seenTerminalStatus = false
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break
                    0 -> {
                        if (seenPlannedEventCount) throw DuplicateTerminalCompletionFactKeyException()
                        seenPlannedEventCount = true
                        decodeSerializableElement(descriptor, index, JsonElement.serializer())
                    }
                    1 -> {
                        if (seenEmittedEventCount) throw DuplicateTerminalCompletionFactKeyException()
                        seenEmittedEventCount = true
                        decodeSerializableElement(descriptor, index, JsonElement.serializer())
                    }
                    2 -> {
                        if (seenTerminalStatus) throw DuplicateTerminalCompletionFactKeyException()
                        seenTerminalStatus = true
                        decodeSerializableElement(descriptor, index, JsonElement.serializer())
                    }
                    else -> decodeSerializableElement(descriptor, index, JsonElement.serializer())
                }
            }
        }
    }
}

private object TerminalCompletionRootDuplicateKeyProbe : DeserializationStrategy<Unit> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "PrototypeTerminalCompletionRootDuplicateKeyProbe",
    ) {
        element(
            "details",
            TerminalCompletionDetailsDuplicateKeyProbe.descriptor,
            isOptional = true,
        )
    }

    override fun deserialize(decoder: Decoder) {
        decoder.decodeStructure(descriptor) {
            var seenDetails = false
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break
                    0 -> {
                        require(!seenDetails) { TERMINAL_COMPLETION_ERROR }
                        seenDetails = true
                        decodeSerializableElement(
                            descriptor,
                            index,
                            TerminalCompletionDetailsDuplicateKeyProbe,
                        )
                    }
                    else -> decodeSerializableElement(descriptor, index, JsonElement.serializer())
                }
            }
        }
    }
}

private object ConditionRootDuplicateKeyProbe : DeserializationStrategy<Unit> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "PrototypeConditionRootDuplicateKeyProbe",
    ) {
        element("condition_id", JsonElement.serializer().descriptor, isOptional = true)
    }

    override fun deserialize(decoder: Decoder) {
        decoder.decodeStructure(descriptor) {
            var seenConditionId = false
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break
                    0 -> {
                        if (seenConditionId) throw DuplicateConditionIdentityKeyException()
                        seenConditionId = true
                        decodeSerializableElement(descriptor, index, JsonElement.serializer())
                    }
                    else -> decodeSerializableElement(descriptor, index, JsonElement.serializer())
                }
            }
        }
    }
}

private object TerminalConditionDetailsDuplicateKeyProbe : DeserializationStrategy<Unit> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "PrototypeTerminalConditionDetailsDuplicateKeyProbe",
    ) {
        element("condition_id", JsonElement.serializer().descriptor, isOptional = true)
    }

    override fun deserialize(decoder: Decoder) {
        decoder.decodeStructure(descriptor) {
            var seenConditionId = false
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break
                    0 -> {
                        if (seenConditionId) throw DuplicateConditionIdentityKeyException()
                        seenConditionId = true
                        decodeSerializableElement(descriptor, index, JsonElement.serializer())
                    }
                    else -> decodeSerializableElement(descriptor, index, JsonElement.serializer())
                }
            }
        }
    }
}

private object TerminalConditionRootDuplicateKeyProbe : DeserializationStrategy<Unit> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "PrototypeTerminalConditionRootDuplicateKeyProbe",
    ) {
        element("condition_id", JsonElement.serializer().descriptor, isOptional = true)
        element(
            "details",
            TerminalConditionDetailsDuplicateKeyProbe.descriptor,
            isOptional = true,
        )
    }

    override fun deserialize(decoder: Decoder) {
        decoder.decodeStructure(descriptor) {
            var seenConditionId = false
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break
                    0 -> {
                        if (seenConditionId) throw DuplicateConditionIdentityKeyException()
                        seenConditionId = true
                        decodeSerializableElement(descriptor, index, JsonElement.serializer())
                    }
                    1 -> decodeSerializableElement(
                        descriptor,
                        index,
                        TerminalConditionDetailsDuplicateKeyProbe,
                    )
                    else -> decodeSerializableElement(descriptor, index, JsonElement.serializer())
                }
            }
        }
    }
}

/** Transport seam for the Prototype 0.1 POST run stream. */
interface PrototypeRawPostTransport {
    suspend fun post(url: String, requestBody: String): RawSseStream

    /**
     * Observed product path. Generic fakes retain source compatibility through this default;
     * the AnebClient transport overrides it so dispatch/frame callbacks occur at their real
     * network boundaries.
     */
    suspend fun postObserved(
        url: String,
        requestBody: String,
        observer: PrototypeRawPostObserver,
    ): RawSseStream {
        observer.beforeDispatch()
        return post(url, requestBody).also { stream ->
            stream.events.forEach(observer.onRawEvent)
        }
    }
}

data class PrototypeRawPostObserver(
    val beforeDispatch: () -> Unit,
    val onRawEvent: (RawSseEvent) -> Unit,
)

data class PrototypeValidatedContentEvent(
    val sequence: Int,
    val rawEvent: RawSseEvent,
    val clientMonotonicNanos: Long,
)

/** Evidence observed before a Prototype stream ended without a valid terminal receipt. */
data class PrototypeInterruptedStreamEvidence(
    val rawEvents: List<RawSseEvent>,
    val t0MonotonicNanos: Long,
    val validatedContentEvents: List<PrototypeValidatedContentEvent>,
    val interruptionClientMonotonicNanos: Long,
) {
    constructor(
        rawEvents: List<RawSseEvent>,
        t0MonotonicNanos: Long,
        validatedContentEvents: List<PrototypeValidatedContentEvent>,
    ) : this(
        rawEvents = rawEvents,
        t0MonotonicNanos = t0MonotonicNanos,
        validatedContentEvents = validatedContentEvents,
        interruptionClientMonotonicNanos = UNSET_TIMESTAMP,
    )
}

internal class PrototypeRunStreamInterruptedException(
    message: String,
    val evidence: PrototypeInterruptedStreamEvidence,
) : IllegalArgumentException(message) {
    constructor(
        message: String,
        evidence: PrototypeInterruptedStreamEvidence,
        cause: IOException,
    ) : this(message, evidence) {
        initCause(cause)
    }
}

internal class PrototypeRunInvalidSequenceException(
    val evidence: PrototypeInterruptedStreamEvidence,
    cause: IllegalArgumentException,
) : IllegalArgumentException(CONTENT_SEQUENCE_ERROR, cause)

private class PrototypeContentSequenceException :
    IllegalArgumentException(CONTENT_SEQUENCE_ERROR)

/** The raw transport evidence plus the decoded terminal frame. */
data class PrototypeRunStreamResult(
    val rawEvents: List<RawSseEvent>,
    val decodedTerminal: PrototypeSseTerminalDecoder.DecodedDoneFrame,
    val t0MonotonicNanos: Long,
    val validatedContentEvents: List<PrototypeValidatedContentEvent>,
    val terminalClientMonotonicNanos: Long,
)

/** Immutable live view derived only from dispatch and already validated stream observations. */
internal data class PrototypeRunLiveSnapshot(
    val t0MonotonicNanos: Long,
    val validatedContentTimestampsNanos: List<Long>,
    val terminalClientMonotonicNanos: Long?,
    val runStartedObserved: Boolean = false,
)

/** Connects the injected POST transport to the existing terminal-frame decoder. */
class PrototypeRunStreamAdapter internal constructor(
    private val transport: PrototypeRawPostTransport,
    private val clock: MonotonicNanosClock,
) {
    constructor(transport: PrototypeRawPostTransport) : this(
        transport = transport,
        clock = AndroidMonotonicNanosClock,
    )

    suspend fun run(endpoint: String, requestBody: String): PrototypeRunStreamResult =
        run(endpoint, requestBody, publishLiveSnapshot = {})

    internal suspend fun run(
        endpoint: String,
        requestBody: String,
        publishLiveSnapshot: (PrototypeRunLiveSnapshot) -> Unit,
    ): PrototypeRunStreamResult {
        val t0MonotonicNanos = AtomicLong(UNSET_TIMESTAMP)
        val terminalClientMonotonicNanos = AtomicLong(UNSET_TIMESTAMP)
        val observedContentEvents = Collections.synchronizedList(
            mutableListOf<PrototypeValidatedContentEvent>(),
        )
        val observedRawEvents = Collections.synchronizedList(mutableListOf<RawSseEvent>())
        val observedInvalidSequence = AtomicReference<PrototypeContentSequenceException?>(null)
        val observedRawEventCount = AtomicInteger(0)
        val observedContentCount = AtomicInteger(0)
        val runStartedTopologyObserved = AtomicBoolean(false)
        val runStartedObserved = AtomicBoolean(false)
        val terminalObserved = AtomicBoolean(false)
        val completeTerminalObserved = AtomicBoolean(false)
        val unreportableTopologyFault = AtomicBoolean(false)
        val contentBeforeRunStartedObserved = AtomicBoolean(false)
        val observationIdentity = requestIdentity(requestBody)
        val observationConditionId = runCatching { requestConditionId(requestBody) }.getOrNull()
        fun markInvalidSequence() {
            if (runStartedObserved.get() && observedContentEvents.size < 120) {
                observedInvalidSequence.compareAndSet(null, PrototypeContentSequenceException())
            } else {
                unreportableTopologyFault.set(true)
            }
        }
        fun runStartedMatchesObservation(rawEvent: RawSseEvent): Boolean = runCatching {
            val expectedIdentity = requireNotNull(observationIdentity)
            val expectedConditionId = requireNotNull(observationConditionId)
            requireRunStartedEventType(rawEvent)
            runStartedIdentity(rawEvent) == expectedIdentity &&
                conditionIdFromRawEvent(rawEvent) == expectedConditionId
        }.getOrDefault(false)
        fun completeObservedStreamOrNull(): RawSseStream? {
            if (!completeTerminalObserved.get() || unreportableTopologyFault.get()) return null
            val events = synchronized(observedRawEvents) { observedRawEvents.toList() }
            if (events.size != 122) return null
            return RawSseStream(
                events = events,
                readCount = events.size,
                totalBytes = events.sumOf { event -> event.bytes.size.toLong() },
                truncatedTail = false,
                eofNanos = terminalClientMonotonicNanos.get(),
            )
        }
        val stream = try {
            transport.postObserved(
                url = endpoint,
                requestBody = requestBody,
                observer = PrototypeRawPostObserver(
                    beforeDispatch = {
                        val observedT0 = clock.now()
                        check(t0MonotonicNanos.compareAndSet(UNSET_TIMESTAMP, observedT0)) {
                            "prototype transport dispatch was observed more than once"
                        }
                        publishLiveSnapshot(
                            PrototypeRunLiveSnapshot(
                                t0MonotonicNanos = observedT0,
                                validatedContentTimestampsNanos = emptyList(),
                                terminalClientMonotonicNanos = null,
                                runStartedObserved = false,
                            ),
                        )
                    },
                    onRawEvent = { rawEvent ->
                        observedRawEvents += rawEvent
                        val rawOrdinal = observedRawEventCount.incrementAndGet()
                        when (rawEvent.bytes.toString(Charsets.UTF_8).lineSequence().firstOrNull()) {
                            "event: run_started" -> {
                                if (
                                    rawOrdinal != 1 ||
                                    !runStartedTopologyObserved.compareAndSet(false, true)
                                ) {
                                    if (
                                        contentBeforeRunStartedObserved.get() &&
                                        runStartedMatchesObservation(rawEvent)
                                    ) {
                                        observedInvalidSequence.compareAndSet(
                                            null,
                                            PrototypeContentSequenceException(),
                                        )
                                    }
                                    markInvalidSequence()
                                } else {
                                    val valid = runStartedMatchesObservation(rawEvent)
                                    if (valid && runStartedObserved.compareAndSet(false, true)) {
                                        publishLiveSnapshot(
                                            PrototypeRunLiveSnapshot(
                                                t0MonotonicNanos = t0MonotonicNanos.get(),
                                                validatedContentTimestampsNanos = emptyList(),
                                                terminalClientMonotonicNanos = null,
                                                runStartedObserved = true,
                                            ),
                                        )
                                    }
                                }
                            }
                            "event: content_event" -> {
                                if (!runStartedTopologyObserved.get()) {
                                    contentBeforeRunStartedObserved.set(true)
                                    markInvalidSequence()
                                } else if (terminalObserved.get()) {
                                    markInvalidSequence()
                                } else {
                                    val expectedSequence = observedContentCount.incrementAndGet()
                                    val identity = observationIdentity
                                    val conditionId = observationConditionId
                                    if (expectedSequence > 120) {
                                        markInvalidSequence()
                                    } else if (
                                        observedInvalidSequence.get() == null &&
                                        !unreportableTopologyFault.get() &&
                                        identity != null &&
                                        conditionId != null
                                    ) {
                                        try {
                                            val validated = validateContentEvent(
                                                rawEvent,
                                                expectedSequence,
                                                identity,
                                            )
                                            val observedConditionId = runCatching {
                                                conditionIdFromPayload(validated.dataPayload)
                                            }.getOrNull()
                                            if (
                                                observedConditionId == conditionId &&
                                                runStartedObserved.get()
                                            ) {
                                                val observedEvent = PrototypeValidatedContentEvent(
                                                    sequence = expectedSequence,
                                                    rawEvent = rawEvent,
                                                    clientMonotonicNanos = clock.now(),
                                                )
                                                observedContentEvents += observedEvent
                                                publishLiveSnapshot(
                                                    PrototypeRunLiveSnapshot(
                                                        t0MonotonicNanos = t0MonotonicNanos.get(),
                                                        validatedContentTimestampsNanos =
                                                            synchronized(observedContentEvents) {
                                                                observedContentEvents.map { event ->
                                                                    event.clientMonotonicNanos
                                                                }
                                                            },
                                                        terminalClientMonotonicNanos = null,
                                                        runStartedObserved = true,
                                                    ),
                                                )
                                            }
                                        } catch (error: PrototypeContentSequenceException) {
                                            if (runStartedObserved.get()) {
                                                observedInvalidSequence.compareAndSet(null, error)
                                            }
                                        } catch (_: IllegalArgumentException) {
                                            // Complete-stream validation owns non-sequence error priority.
                                        }
                                    }
                                }
                            }
                            "event: done" -> {
                                if (
                                    !runStartedTopologyObserved.get() ||
                                    !terminalObserved.compareAndSet(false, true)
                                ) {
                                    markInvalidSequence()
                                } else {
                                    val identity = observationIdentity
                                    val conditionId = observationConditionId
                                    val valid = runCatching {
                                        requireNotNull(identity)
                                        requireNotNull(conditionId)
                                        validateTerminalForObservation(rawEvent, identity, conditionId)
                                    }.isSuccess
                                    if (valid) {
                                        val observedTerminal = clock.now()
                                        val accepted = terminalClientMonotonicNanos.compareAndSet(
                                            UNSET_TIMESTAMP,
                                            observedTerminal,
                                        )
                                        val contentTimestamps = synchronized(observedContentEvents) {
                                            observedContentEvents.map { event ->
                                                event.clientMonotonicNanos
                                            }
                                        }
                                        val rawContentCount = observedContentCount.get()
                                        val validatedContentCount = contentTimestamps.size
                                        if (
                                            accepted &&
                                            observedInvalidSequence.get() == null &&
                                            !unreportableTopologyFault.get() &&
                                            rawContentCount < 120
                                        ) {
                                            markInvalidSequence()
                                        } else if (
                                            accepted &&
                                            observedInvalidSequence.get() == null &&
                                            !unreportableTopologyFault.get() &&
                                            validatedContentCount == 120
                                        ) {
                                            completeTerminalObserved.set(true)
                                            publishLiveSnapshot(
                                                PrototypeRunLiveSnapshot(
                                                    t0MonotonicNanos = t0MonotonicNanos.get(),
                                                    validatedContentTimestampsNanos = contentTimestamps,
                                                    terminalClientMonotonicNanos = observedTerminal,
                                                    runStartedObserved = true,
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                            else -> markInvalidSequence()
                        }
                    },
                ),
            )
        } catch (error: CancellationException) {
            val authority = currentCoroutineContext()[PrototypeUserCancellationAuthority]
            if (authority?.isRequested() != true) throw error
            observedInvalidSequence.get()?.let { sequenceError ->
                throw PrototypeRunInvalidSequenceException(
                    evidence = invalidSequenceEvidence(
                        observedRawEvents = observedRawEvents,
                        observedContentEvents = observedContentEvents,
                        requestBody = requestBody,
                        t0MonotonicNanos = t0MonotonicNanos.get(),
                        failureClientMonotonicNanos = clock.now(),
                    ),
                    cause = sequenceError,
                )
            }
            completeObservedStreamOrNull() ?: throw PrototypeRunCancellationObservation(
                    evidence = synchronized(observedRawEvents) {
                        observedRawEvents.toList()
                    }.takeIf { rawEvents -> rawEvents.isNotEmpty() }?.let { rawEvents ->
                        validateInterruptedPrefix(
                            rawEvents = rawEvents,
                            requestBody = requestBody,
                            t0MonotonicNanos = t0MonotonicNanos.get(),
                            observedContentEvents = synchronized(observedContentEvents) {
                                observedContentEvents.toList()
                            },
                            interruptionClientMonotonicNanos = clock.now(),
                        )
                    },
                    cause = error,
                )
        } catch (error: IOException) {
            observedInvalidSequence.get()?.let { sequenceError ->
                throw PrototypeRunInvalidSequenceException(
                    evidence = invalidSequenceEvidence(
                        observedRawEvents = observedRawEvents,
                        observedContentEvents = observedContentEvents,
                        requestBody = requestBody,
                        t0MonotonicNanos = t0MonotonicNanos.get(),
                        failureClientMonotonicNanos = clock.now(),
                    ),
                    cause = sequenceError,
                )
            }
            completeObservedStreamOrNull() ?: run {
                val rawEvents = synchronized(observedRawEvents) { observedRawEvents.toList() }
                if (rawEvents.isEmpty()) {
                    throw error
                }
                val interruptionClientMonotonicNanos = clock.now()
                val contentEvents = synchronized(observedContentEvents) {
                    observedContentEvents.toList()
                }
                val evidence = validateInterruptedPrefix(
                    rawEvents = rawEvents,
                    requestBody = requestBody,
                    t0MonotonicNanos = t0MonotonicNanos.get(),
                    observedContentEvents = contentEvents,
                    interruptionClientMonotonicNanos = interruptionClientMonotonicNanos,
                )
                throw PrototypeRunStreamInterruptedException(
                    message = MISSING_TERMINAL_STREAM_ERROR,
                    evidence = evidence,
                    cause = error,
                )
            }
        }
        observedInvalidSequence.get()?.let { sequenceError ->
            throw PrototypeRunInvalidSequenceException(
                evidence = invalidSequenceEvidence(
                    observedRawEvents = observedRawEvents,
                    observedContentEvents = observedContentEvents,
                    requestBody = requestBody,
                    t0MonotonicNanos = t0MonotonicNanos.get(),
                    failureClientMonotonicNanos = clock.now(),
                ),
                cause = sequenceError,
            )
        }
        val rawEvents = stream.events
        val finalDoneCount = rawEvents.count { rawEvent ->
            rawEvent.bytes.toString(Charsets.UTF_8).lineSequence().firstOrNull() == "event: done"
        }
        if (stream.truncatedTail || finalDoneCount == 0) {
            val interruptionClientMonotonicNanos = clock.now()
            val evidence = validateInterruptedPrefix(
                rawEvents = rawEvents,
                requestBody = requestBody,
                t0MonotonicNanos = t0MonotonicNanos.get(),
                observedContentEvents = observedContentEvents.toList(),
                interruptionClientMonotonicNanos = interruptionClientMonotonicNanos,
            )
            throw PrototypeRunStreamInterruptedException(
                message = if (stream.truncatedTail) {
                    TRUNCATED_STREAM_ERROR
                } else {
                    MISSING_TERMINAL_STREAM_ERROR
                },
                evidence = evidence,
            )
        }
        require(finalDoneCount == 1) {
            "prototype SSE stream must contain exactly one final done event"
        }
        val terminalFrame = rawEvents.last().bytes.toString(Charsets.UTF_8) + "\n\n"
        val decodedTerminal = PrototypeSseTerminalDecoder.decodeDoneFrame(terminalFrame)
        val eventLines = rawEvents.map { rawEvent ->
            rawEvent.bytes.toString(Charsets.UTF_8).lineSequence().firstOrNull()
        }
        val earlyDone = rawEvents.size in 2..121 &&
            eventLines.firstOrNull() == "event: run_started" &&
            eventLines.drop(1).dropLast(1).all { it == "event: content_event" } &&
            eventLines.lastOrNull() == "event: done" &&
            runStartedObserved.get() &&
            !unreportableTopologyFault.get() &&
            terminalClientMonotonicNanos.get() != UNSET_TIMESTAMP
        if (earlyDone) {
            val evidence = validateInterruptedPrefix(
                rawEvents = rawEvents.dropLast(1),
                requestBody = requestBody,
                t0MonotonicNanos = t0MonotonicNanos.get(),
                observedContentEvents = observedContentEvents.toList(),
                interruptionClientMonotonicNanos = terminalClientMonotonicNanos.get(),
            )
            throw PrototypeRunInvalidSequenceException(
                evidence = evidence,
                cause = PrototypeContentSequenceException(),
            )
        }
        require(
            rawEvents.size == 122 &&
                eventLines.firstOrNull() == "event: run_started" &&
                eventLines.drop(1).dropLast(1).all { it == "event: content_event" } &&
                eventLines.lastOrNull() == "event: done",
        ) {
            "prototype SSE stream must contain run_started, 120 content events, and final done"
        }
        val expectedIdentity = runStartedIdentity(rawEvents.first())
        require(expectedIdentity != null) { CONTENT_IDENTITY_ERROR }
        val contentDataPayloads = mutableListOf<String>()
        requireRunStartedEventType(rawEvents.first())
        rawEvents.subList(1, rawEvents.lastIndex).forEachIndexed { index, rawEvent ->
            val expectedSequence = index + 1
            contentDataPayloads += validateContentEvent(
                rawEvent = rawEvent,
                expectedSequence = expectedSequence,
                expectedIdentity = expectedIdentity,
            ).dataPayload
        }
        var previousArrivalNanos: Long? = null
        rawEvents.subList(1, rawEvents.lastIndex).forEach { rawEvent ->
            val arrivalNanos = rawEvent.arrivalNanos
            require(
                arrivalNanos >= 0L &&
                    (previousArrivalNanos == null || arrivalNanos >= previousArrivalNanos!!),
            ) {
                CONTENT_ARRIVAL_CHRONOLOGY_ERROR
            }
            previousArrivalNanos = arrivalNanos
        }
        val terminalDataPayload = rawEvents.last().bytes.toString(Charsets.UTF_8)
            .lineSequence()
            .toList()
            .getOrNull(1)
            ?.removePrefix(DATA_PREFIX)
            ?: throw IllegalArgumentException(TERMINAL_IDENTITY_ERROR)
        try {
            probeJson.decodeFromString(TerminalIdentityDuplicateKeyProbe, terminalDataPayload)
        } catch (error: DuplicateTerminalIdentityKeyException) {
            throw IllegalArgumentException(TERMINAL_IDENTITY_ERROR, error)
        } catch (error: Exception) {
            throw IllegalArgumentException(TERMINAL_IDENTITY_ERROR, error)
        }
        val terminalDetailsIdentity = (decodedTerminal.envelope["details"] as? JsonObject)
            ?.let(::identityFromEnvelope)
        require(
            identityFromEnvelope(decodedTerminal.envelope) == expectedIdentity &&
                terminalDetailsIdentity == expectedIdentity,
        ) {
            TERMINAL_IDENTITY_ERROR
        }
        try {
            probeJson.decodeFromString(TerminalCompletionRootDuplicateKeyProbe, terminalDataPayload)
        } catch (error: Exception) {
            throw IllegalArgumentException(TERMINAL_COMPLETION_ERROR, error)
        }
        requireTerminalCompletionFacts(decodedTerminal.envelope)
        val requestIdentity = requestIdentity(requestBody)
        require(requestIdentity == expectedIdentity) { REQUEST_RUN_IDENTITY_ERROR }
        try {
            val outgoingConditionId = requestConditionId(requestBody)
            val runStartedConditionId = conditionIdFromRawEvent(rawEvents.first())
            val contentConditionIds = contentDataPayloads.map(::conditionIdFromPayload)
            requireNoDuplicateTerminalConditionKeys(terminalDataPayload)
            val terminalConditionId = conditionIdFromEnvelope(decodedTerminal.envelope)
            val terminalDetailsConditionId = (decodedTerminal.envelope["details"] as? JsonObject)
                ?.let(::conditionIdFromEnvelope)
            require(
                outgoingConditionId != null &&
                    runStartedConditionId == outgoingConditionId &&
                    contentConditionIds.all { it == outgoingConditionId } &&
                    terminalConditionId == outgoingConditionId &&
                    terminalDetailsConditionId == outgoingConditionId,
            ) {
                CONDITION_IDENTITY_ERROR
            }
        } catch (error: DuplicateConditionIdentityKeyException) {
            throw IllegalArgumentException(CONDITION_IDENTITY_ERROR, error)
        }
        val observedT0 = t0MonotonicNanos.get().also { timestamp ->
            check(timestamp >= 0L) { "prototype transport dispatch was not observed" }
        }
        val validatedEvents = observedContentEvents.toList().also { events ->
            check(events.map { it.sequence } == (1..120).toList()) {
                "prototype content validation timestamps are incomplete"
            }
        }
        requireContentObservationChronology(observedT0, validatedEvents)
        val observedTerminal = terminalClientMonotonicNanos.get().also { timestamp ->
            check(timestamp >= 0L) { "prototype terminal validation timestamp is missing" }
        }
        require(observedTerminal > validatedEvents.last().clientMonotonicNanos) {
            "prototype terminal validation timestamp must follow content observations"
        }
        return PrototypeRunStreamResult(
            rawEvents = rawEvents,
            decodedTerminal = decodedTerminal,
            t0MonotonicNanos = observedT0,
            validatedContentEvents = validatedEvents,
            terminalClientMonotonicNanos = observedTerminal,
        )
    }

    private companion object {
        private val probeJson = Json {
            ignoreUnknownKeys = true
            isLenient = false
        }
        private val contentJson = Json {
            ignoreUnknownKeys = false
            isLenient = false
        }
        private const val DATA_PREFIX = "data: "

        private fun validateContentEvent(
            rawEvent: RawSseEvent,
            expectedSequence: Int,
            expectedIdentity: ContentRunIdentity,
        ): ValidatedContentPayload {
            val lines = rawEvent.bytes.toString(Charsets.UTF_8).lineSequence().toList()
            require(lines.size == 2) { CONTENT_SEQUENCE_ERROR }
            val dataLine = lines[1]
            require(dataLine.startsWith(DATA_PREFIX)) { CONTENT_SEQUENCE_ERROR }
            val dataPayload = dataLine.removePrefix(DATA_PREFIX)
            requireJsonNestingWithinBudget(dataPayload)
            try {
                probeJson.decodeFromString(ContentRootDuplicateKeyProbe, dataPayload)
            } catch (error: DuplicateContentIdentityKeyException) {
                throw IllegalArgumentException(CONTENT_IDENTITY_ERROR, error)
            } catch (error: DuplicateContentSequenceKeyException) {
                throw PrototypeContentSequenceException()
            } catch (error: Exception) {
                throw IllegalArgumentException(CONTENT_SEQUENCE_ERROR, error)
            }
            val envelope = try {
                contentJson.parseToJsonElement(dataPayload)
            } catch (error: Exception) {
                throw IllegalArgumentException(CONTENT_SEQUENCE_ERROR, error)
            }
            require(envelope is JsonObject) { CONTENT_SEQUENCE_ERROR }
            require(envelope["event_type"] == JsonPrimitive("content_event")) {
                CONTENT_SEQUENCE_ERROR
            }
            val details = envelope["details"]
            require(details is JsonObject) { CONTENT_SEQUENCE_ERROR }
            val sequence = details["seq"] as? JsonPrimitive
            if (
                sequence == null ||
                sequence.isString ||
                sequence.content != expectedSequence.toString()
            ) {
                throw PrototypeContentSequenceException()
            }
            try {
                probeJson.decodeFromString(ContentIdentityDuplicateKeyProbe, dataPayload)
            } catch (error: DuplicateContentIdentityKeyException) {
                throw IllegalArgumentException(CONTENT_IDENTITY_ERROR, error)
            } catch (error: Exception) {
                throw IllegalArgumentException(CONTENT_IDENTITY_ERROR, error)
            }
            require(identityFromEnvelope(envelope) == expectedIdentity) { CONTENT_IDENTITY_ERROR }
            return ValidatedContentPayload(dataPayload, envelope)
        }

        private fun validateTerminalForObservation(
            rawEvent: RawSseEvent,
            expectedIdentity: ContentRunIdentity,
            expectedConditionId: String,
        ) {
            val rawFrame = rawEvent.bytes.toString(Charsets.UTF_8) + "\n\n"
            val decodedTerminal = PrototypeSseTerminalDecoder.decodeDoneFrame(rawFrame)
            val terminalDataPayload = rawEvent.bytes.toString(Charsets.UTF_8)
                .lineSequence()
                .toList()
                .getOrNull(1)
                ?.takeIf { it.startsWith(DATA_PREFIX) }
                ?.removePrefix(DATA_PREFIX)
                ?: throw IllegalArgumentException(TERMINAL_IDENTITY_ERROR)
            probeJson.decodeFromString(TerminalIdentityDuplicateKeyProbe, terminalDataPayload)
            val terminalDetails = decodedTerminal.envelope["details"] as? JsonObject
            require(
                identityFromEnvelope(decodedTerminal.envelope) == expectedIdentity &&
                    terminalDetails?.let(::identityFromEnvelope) == expectedIdentity,
            ) {
                TERMINAL_IDENTITY_ERROR
            }
            probeJson.decodeFromString(
                TerminalCompletionRootDuplicateKeyProbe,
                terminalDataPayload,
            )
            requireTerminalCompletionFacts(decodedTerminal.envelope)
            requireNoDuplicateTerminalConditionKeys(terminalDataPayload)
            require(
                conditionIdFromEnvelope(decodedTerminal.envelope) == expectedConditionId &&
                    terminalDetails?.let(::conditionIdFromEnvelope) == expectedConditionId,
            ) {
                CONDITION_IDENTITY_ERROR
            }
        }

        private fun validateInterruptedPrefix(
            rawEvents: List<RawSseEvent>,
            requestBody: String,
            t0MonotonicNanos: Long,
            observedContentEvents: List<PrototypeValidatedContentEvent>,
            interruptionClientMonotonicNanos: Long,
        ): PrototypeInterruptedStreamEvidence {
            val eventLines = rawEvents.map { rawEvent ->
                rawEvent.bytes.toString(Charsets.UTF_8).lineSequence().firstOrNull()
            }
            require(
                rawEvents.isNotEmpty() &&
                    rawEvents.size <= 121 &&
                    eventLines.first() == "event: run_started" &&
                    eventLines.drop(1).all { it == "event: content_event" },
            ) {
                CONTENT_SEQUENCE_ERROR
            }
            val expectedIdentity = runStartedIdentity(rawEvents.first())
            require(expectedIdentity != null) { CONTENT_IDENTITY_ERROR }
            requireRunStartedEventType(rawEvents.first())
            val contentPayloads = rawEvents.drop(1).mapIndexed { index, rawEvent ->
                validateContentEvent(
                    rawEvent = rawEvent,
                    expectedSequence = index + 1,
                    expectedIdentity = expectedIdentity,
                ).dataPayload
            }
            var previousArrivalNanos: Long? = null
            rawEvents.drop(1).forEach { rawEvent ->
                val arrivalNanos = rawEvent.arrivalNanos
                require(
                    arrivalNanos >= 0L &&
                        (previousArrivalNanos == null || arrivalNanos >= previousArrivalNanos!!),
                ) {
                    CONTENT_ARRIVAL_CHRONOLOGY_ERROR
                }
                previousArrivalNanos = arrivalNanos
            }
            require(requestIdentity(requestBody) == expectedIdentity) { REQUEST_RUN_IDENTITY_ERROR }
            try {
                val outgoingConditionId = requestConditionId(requestBody)
                require(
                    outgoingConditionId != null &&
                        conditionIdFromRawEvent(rawEvents.first()) == outgoingConditionId &&
                        contentPayloads.all { payload ->
                            conditionIdFromPayload(payload) == outgoingConditionId
                        },
                ) {
                    CONDITION_IDENTITY_ERROR
                }
            } catch (error: DuplicateConditionIdentityKeyException) {
                throw IllegalArgumentException(CONDITION_IDENTITY_ERROR, error)
            }
            require(
                observedContentEvents.map { it.sequence } == contentPayloads.indices.map { it + 1 } &&
                    observedContentEvents.map { it.rawEvent } == rawEvents.drop(1),
            ) {
                CONTENT_SEQUENCE_ERROR
            }
            check(t0MonotonicNanos >= 0L) { "prototype transport dispatch was not observed" }
            requireContentObservationChronology(t0MonotonicNanos, observedContentEvents)
            val lastObservation = observedContentEvents.lastOrNull()?.clientMonotonicNanos
                ?: t0MonotonicNanos
            require(interruptionClientMonotonicNanos > lastObservation) {
                "prototype interruption validation timestamp must follow observed content"
            }
            return PrototypeInterruptedStreamEvidence(
                rawEvents = rawEvents.toList(),
                t0MonotonicNanos = t0MonotonicNanos,
                validatedContentEvents = observedContentEvents.toList(),
                interruptionClientMonotonicNanos = interruptionClientMonotonicNanos,
            )
        }

        private fun invalidSequenceEvidence(
            observedRawEvents: List<RawSseEvent>,
            observedContentEvents: List<PrototypeValidatedContentEvent>,
            requestBody: String,
            t0MonotonicNanos: Long,
            failureClientMonotonicNanos: Long,
        ): PrototypeInterruptedStreamEvidence {
            val rawSnapshot = synchronized(observedRawEvents) { observedRawEvents.toList() }
            val contentSnapshot = synchronized(observedContentEvents) {
                observedContentEvents.toList()
            }
            val runStarted = rawSnapshot.firstOrNull { rawEvent ->
                rawEvent.bytes.toString(Charsets.UTF_8)
                    .lineSequence()
                    .firstOrNull() == "event: run_started"
            }
                ?: throw IllegalArgumentException(CONTENT_SEQUENCE_ERROR)
            return validateInterruptedPrefix(
                rawEvents = listOf(runStarted) + contentSnapshot.map { it.rawEvent },
                requestBody = requestBody,
                t0MonotonicNanos = t0MonotonicNanos,
                observedContentEvents = contentSnapshot,
                interruptionClientMonotonicNanos = failureClientMonotonicNanos,
            )
        }

        private fun requireContentObservationChronology(
            t0MonotonicNanos: Long,
            events: List<PrototypeValidatedContentEvent>,
        ) {
            var previousContentTimestamp: Long? = null
            events.forEach { event ->
                require(
                    event.clientMonotonicNanos >= t0MonotonicNanos &&
                        (previousContentTimestamp == null ||
                            event.clientMonotonicNanos > previousContentTimestamp!!),
                ) {
                    "prototype Android content observations must be nondecreasing from t0 and strictly ordered"
                }
                previousContentTimestamp = event.clientMonotonicNanos
            }
        }

        private fun requireRunStartedEventType(rawEvent: RawSseEvent) {
            val dataPayload = rawEvent.bytes.toString(Charsets.UTF_8)
                .lineSequence()
                .toList()
                .getOrNull(1)
                ?.takeIf { it.startsWith(DATA_PREFIX) }
                ?.removePrefix(DATA_PREFIX)
                ?: throw IllegalArgumentException(RUN_STARTED_EVENT_TYPE_ERROR)
            try {
                probeJson.decodeFromString(RunStartedEventTypeDuplicateKeyProbe, dataPayload)
            } catch (error: Exception) {
                throw IllegalArgumentException(RUN_STARTED_EVENT_TYPE_ERROR, error)
            }
            val envelope = try {
                contentJson.parseToJsonElement(dataPayload)
            } catch (error: Exception) {
                throw IllegalArgumentException(RUN_STARTED_EVENT_TYPE_ERROR, error)
            }
            val eventType = (envelope as? JsonObject)?.get("event_type") as? JsonPrimitive
            require(
                eventType != null &&
                    eventType.isString &&
                    eventType.content == "run_started",
            ) {
                RUN_STARTED_EVENT_TYPE_ERROR
            }
        }

        private fun runStartedIdentity(rawEvent: RawSseEvent): ContentRunIdentity? {
            val dataPayload = rawEvent.bytes.toString(Charsets.UTF_8)
                .lineSequence()
                .toList()
                .getOrNull(1)
                ?.takeIf { it.startsWith(DATA_PREFIX) }
                ?.removePrefix(DATA_PREFIX)
                ?: return null
            val envelope = try {
                probeJson.decodeFromString(ContentIdentityDuplicateKeyProbe, dataPayload)
                contentJson.parseToJsonElement(dataPayload)
            } catch (_: Exception) {
                return null
            }
            return (envelope as? JsonObject)?.let(::identityFromEnvelope)
        }

        private fun identityFromEnvelope(envelope: JsonObject): ContentRunIdentity? {
            val campaignId = (envelope["campaign_id"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
            val runId = (envelope["run_id"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
            return if (campaignId != null && runId != null) {
                ContentRunIdentity(campaignId, runId)
            } else {
                null
            }
        }

        private fun conditionIdFromEnvelope(envelope: JsonObject): String? =
            (envelope["condition_id"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content

        private fun conditionIdFromRawEvent(rawEvent: RawSseEvent): String? {
            val dataPayload = rawEvent.bytes.toString(Charsets.UTF_8)
                .lineSequence()
                .toList()
                .getOrNull(1)
                ?.takeIf { it.startsWith(DATA_PREFIX) }
                ?.removePrefix(DATA_PREFIX)
                ?: return null
            return conditionIdFromPayload(dataPayload)
        }

        private fun conditionIdFromPayload(dataPayload: String): String? {
            probeJson.decodeFromString(ConditionRootDuplicateKeyProbe, dataPayload)
            val envelope = try {
                contentJson.parseToJsonElement(dataPayload)
            } catch (_: Exception) {
                return null
            }
            return (envelope as? JsonObject)?.let(::conditionIdFromEnvelope)
        }

        private fun requestConditionId(requestBody: String): String? {
            probeJson.decodeFromString(ConditionRootDuplicateKeyProbe, requestBody)
            val envelope = try {
                contentJson.parseToJsonElement(requestBody)
            } catch (_: Exception) {
                return null
            }
            return (envelope as? JsonObject)?.let(::conditionIdFromEnvelope)
        }

        private fun requireNoDuplicateTerminalConditionKeys(dataPayload: String) {
            probeJson.decodeFromString(TerminalConditionRootDuplicateKeyProbe, dataPayload)
        }

        private fun requestIdentity(requestBody: String): ContentRunIdentity? {
            val envelope = try {
                probeJson.decodeFromString(ContentIdentityDuplicateKeyProbe, requestBody)
                contentJson.parseToJsonElement(requestBody)
            } catch (_: Exception) {
                return null
            }
            return (envelope as? JsonObject)?.let(::identityFromEnvelope)
        }

        private fun requireTerminalCompletionFacts(envelope: JsonObject) {
            val details = envelope["details"] as? JsonObject
            val terminalStatus = details?.get("terminal_status") as? JsonPrimitive
            val plannedEventCount = details?.get("planned_event_count") as? JsonPrimitive
            val emittedEventCount = details?.get("emitted_event_count") as? JsonPrimitive
            require(
                terminalStatus != null &&
                    terminalStatus.isString &&
                    terminalStatus.content == "complete" &&
                    plannedEventCount != null &&
                    !plannedEventCount.isString &&
                    plannedEventCount.content == "120" &&
                    emittedEventCount != null &&
                    !emittedEventCount.isString &&
                    emittedEventCount.content == "120",
            ) {
                TERMINAL_COMPLETION_ERROR
            }
        }
    }
}
