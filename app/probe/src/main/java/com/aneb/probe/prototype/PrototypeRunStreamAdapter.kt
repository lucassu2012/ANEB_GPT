package com.aneb.probe.prototype

import com.aneb.probe.net.RawSseEvent
import com.aneb.probe.net.RawSseStream
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

private const val CONTENT_SEQUENCE_ERROR =
    "prototype SSE content events must have exact seq 1 through 120"
private const val CONTENT_IDENTITY_ERROR =
    "prototype SSE content event identity must match the run"
private const val MAX_JSON_NESTING_DEPTH = 64

private data class ContentRunIdentity(
    val campaignId: String,
    val runId: String,
)

private class DuplicateContentIdentityKeyException : IllegalArgumentException()

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
    }

    override fun deserialize(decoder: Decoder) {
        decoder.decodeStructure(descriptor) {
            var seenSeq = false
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break
                    0 -> {
                        require(!seenSeq) { CONTENT_SEQUENCE_ERROR }
                        seenSeq = true
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
                        require(!seenDetails) { CONTENT_SEQUENCE_ERROR }
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

/** Transport seam for the Prototype 0.1 POST run stream. */
interface PrototypeRawPostTransport {
    suspend fun post(url: String, requestBody: String): RawSseStream
}

/** The raw transport evidence plus the decoded terminal frame. */
data class PrototypeRunStreamResult(
    val rawEvents: List<RawSseEvent>,
    val decodedTerminal: PrototypeSseTerminalDecoder.DecodedDoneFrame,
)

/** Connects the injected POST transport to the existing terminal-frame decoder. */
class PrototypeRunStreamAdapter(
    private val transport: PrototypeRawPostTransport,
) {
    suspend fun run(endpoint: String, requestBody: String): PrototypeRunStreamResult {
        val stream = transport.post(endpoint, requestBody)
        require(!stream.truncatedTail) { "prototype SSE stream has a truncated tail" }
        val rawEvents = stream.events
        val finalDoneCount = rawEvents.count { rawEvent ->
            rawEvent.bytes.toString(Charsets.UTF_8).lineSequence().firstOrNull() == "event: done"
        }
        require(finalDoneCount == 1) {
            "prototype SSE stream must contain exactly one final done event"
        }
        val terminalFrame = rawEvents.last().bytes.toString(Charsets.UTF_8) + "\n\n"
        val decodedTerminal = PrototypeSseTerminalDecoder.decodeDoneFrame(terminalFrame)
        val eventLines = rawEvents.map { rawEvent ->
            rawEvent.bytes.toString(Charsets.UTF_8).lineSequence().firstOrNull()
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
        rawEvents.subList(1, rawEvents.lastIndex).forEachIndexed { index, rawEvent ->
            val expectedSequence = index + 1
            val lines = rawEvent.bytes.toString(Charsets.UTF_8).lineSequence().toList()
            require(lines.size == 2) {
                CONTENT_SEQUENCE_ERROR
            }
            val dataLine = lines[1]
            require(dataLine.startsWith(DATA_PREFIX)) {
                CONTENT_SEQUENCE_ERROR
            }
            val dataPayload = dataLine.removePrefix(DATA_PREFIX)
            requireJsonNestingWithinBudget(dataPayload)
            try {
                probeJson.decodeFromString(ContentRootDuplicateKeyProbe, dataPayload)
            } catch (error: DuplicateContentIdentityKeyException) {
                throw IllegalArgumentException(CONTENT_IDENTITY_ERROR, error)
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
            require(
                sequence != null &&
                    !sequence.isString &&
                    sequence.content == expectedSequence.toString(),
            ) {
                CONTENT_SEQUENCE_ERROR
            }
            try {
                probeJson.decodeFromString(ContentIdentityDuplicateKeyProbe, dataPayload)
            } catch (error: DuplicateContentIdentityKeyException) {
                throw IllegalArgumentException(CONTENT_IDENTITY_ERROR, error)
            } catch (error: Exception) {
                throw IllegalArgumentException(CONTENT_IDENTITY_ERROR, error)
            }
            val eventIdentity = identityFromEnvelope(envelope)
            require(eventIdentity == expectedIdentity) { CONTENT_IDENTITY_ERROR }
        }
        return PrototypeRunStreamResult(
            rawEvents = rawEvents,
            decodedTerminal = decodedTerminal,
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
    }
}
