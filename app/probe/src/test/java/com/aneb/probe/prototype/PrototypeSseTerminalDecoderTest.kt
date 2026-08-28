package com.aneb.probe.prototype

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class PrototypeSseTerminalDecoderTest {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    @Test
    fun sharedDoneFrameIsDecodedAndProjected() {
        val projectionFixture = readJsonFixture("prototype_option_a_terminal_projection.json")
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")

        val decoded = PrototypeSseTerminalDecoder.decodeDoneFrame(doneFrame)

        assertEquals("done", decoded.eventName)
        val envelope = decoded.envelope as JsonObject
        assertEquals(
            "terminal_event",
            envelope.getValue("event_type").jsonPrimitive.content,
        )
        val details = envelope.getValue("details")
        assertTrue("done details must be a JSON object", details is JsonObject)

        val actual = PrototypeTerminalProjection.project(
            serverDetails = details.jsonObject,
            androidAdditions = projectionFixture.getValue("android_additions").jsonObject,
        )
        assertEquals(
            projectionFixture.getValue("canonical_terminal_event_details").jsonObject,
            actual,
        )
    }

    @Test
    fun equivalentDoneFrameRepresentationsRemainCanonical() {
        val projectionFixture = readJsonFixture("prototype_option_a_terminal_projection.json")
        val canonical = readFixture("prototype_option_a_done_frame.sse")
        val expectedCanonical = projectionFixture.getValue("canonical_terminal_event_details").jsonObject
        val variants = listOf(
            "server monotonic only" to frameWithField(canonical, "server_monotonic_ns", JsonPrimitive(42)),
            "outer keys reordered" to frameWithReorderedKeys(canonical),
            "data whitespace only" to frameWithDataWhitespace(canonical),
            "all three representations" to frameWithDataWhitespace(
                frameWithReorderedKeys(frameWithField(canonical, "server_monotonic_ns", JsonPrimitive(42))),
            ),
        )

        variants.forEach { (label, frame) ->
            val decoded = PrototypeSseTerminalDecoder.decodeDoneFrame(frame)
            assertEquals("$label event", "done", decoded.eventName)
            val envelope = decoded.envelope as JsonObject
            assertEquals(
                "$label event_type",
                "terminal_event",
                envelope.getValue("event_type").jsonPrimitive.content,
            )
            val details = envelope.getValue("details")
            assertTrue("$label details must be a JSON object", details is JsonObject)
            val actual = PrototypeTerminalProjection.project(
                serverDetails = details.jsonObject,
                androidAdditions = projectionFixture.getValue("android_additions").jsonObject,
            )
            assertEquals("$label canonical projection", expectedCanonical, actual)
        }
    }

    @Test
    fun malformedDoneFramesFailClosed() {
        val canonical = readFixture("prototype_option_a_done_frame.sse")
        val bodyWithoutDelimiter = canonical.removeSuffix("\n\n")
        val payload = dataPayload(canonical)
        val strictEventTypeFrames = listOf(
            "uppercase event_type" to "TERMINAL_EVENT",
            "leading space event_type" to " terminal_event",
            "trailing space event_type" to "terminal_event ",
            "leading tab event_type" to "\tterminal_event",
            "trailing tab event_type" to "terminal_event\t",
            "leading newline event_type" to "\nterminal_event",
            "trailing newline event_type" to "terminal_event\n",
            "prefixed event_type" to "xterminal_event",
            "suffixed event_type" to "terminal_eventx",
            "fullwidth event_type" to "\uFF54erminal_event",
        ).map { (label, value) ->
            label to frameWithField(canonical, "event_type", JsonPrimitive(value))
        }
        val strictEventLineFrames = listOf(
            "Event line case" to "Event: done",
            "Done line case" to "event: Done",
            "leading space event line" to " event: done",
            "trailing space event line" to "event: done ",
            "tab event separator" to "event:\tdone",
            "prefixed event line" to "xevent: done",
            "suffixed event line" to "event: donex",
            "fullwidth event line" to "\uFF45vent: done",
        ).map { (label, eventLine) ->
            label to frameWithLines(eventLine, "data: $payload")
        }
        val dataAuthorityFrames = listOf(
            "Data field case" to "Data: $payload",
            "space before data colon" to "data : $payload",
            "leading space data line" to " data: $payload",
            "wrong data field name" to "body: $payload",
        ).map { (label, dataLine) ->
            label to frameWithLines("event: done", dataLine)
        }
        val exactTwoLineBoundaryFrames = listOf(
            "only event line" to frameWithLines("event: done"),
            "only data line" to frameWithLines("data: $payload"),
        )
        val invalidFrames = listOf(
            "missing final delimiter" to bodyWithoutDelimiter,
            "single LF" to "$bodyWithoutDelimiter\n",
            "extra third line" to "$bodyWithoutDelimiter\nextra\n\n",
            "two concatenated frames" to canonical + canonical,
            "wrong event" to canonical.replaceFirst("event: done", "event: content_event"),
            "missing data prefix" to canonical.replaceFirst("data: ", "payload: "),
            "empty data" to frameWithData(""),
            "blank data" to frameWithData("   "),
            "invalid JSON" to frameWithData("{"),
            "JSON array root" to frameWithData("[]"),
            "JSON string root" to frameWithData("\"done\""),
            "JSON null root" to frameWithData("null"),
            "JSON number root" to frameWithData("42"),
            "JSON boolean root" to frameWithData("true"),
            "missing event_type" to frameWithField(canonical, "event_type", null),
            "wrong event_type" to frameWithField(canonical, "event_type", JsonPrimitive("content_event")),
            "non-string event_type" to frameWithField(canonical, "event_type", JsonPrimitive(7)),
            "null event_type" to frameWithField(canonical, "event_type", JsonNull),
            "boolean event_type" to frameWithField(canonical, "event_type", JsonPrimitive(true)),
            "array event_type" to frameWithField(canonical, "event_type", json.parseToJsonElement("[]")),
            "object event_type" to frameWithField(canonical, "event_type", json.parseToJsonElement("{}")),
            "missing details" to frameWithField(canonical, "details", null),
            "null details" to frameWithField(canonical, "details", JsonNull),
            "array details" to frameWithField(canonical, "details", json.parseToJsonElement("[]")),
            "scalar details" to frameWithField(canonical, "details", JsonPrimitive("not-an-object")),
            "number details" to frameWithField(canonical, "details", JsonPrimitive(7)),
            "boolean details" to frameWithField(canonical, "details", JsonPrimitive(true)),
        ) + strictEventTypeFrames + strictEventLineFrames + dataAuthorityFrames + exactTwoLineBoundaryFrames

        invalidFrames.forEach { (label, frame) ->
            assertIllegalArgument(label, frame)
        }
    }

    private fun readJsonFixture(name: String): JsonObject =
        json.parseToJsonElement(readFixture(name)).jsonObject

    private fun frameWithData(data: String): String =
        "event: done\ndata: $data\n\n"

    private fun frameWithLines(vararg lines: String): String =
        lines.joinToString(separator = "\n", postfix = "\n\n")

    private fun frameWithDataWhitespace(rawFrame: String): String {
        val data = rawFrame
            .removeSuffix("\n\n")
            .lineSequence()
            .single { it.startsWith("data: ") }
            .removePrefix("data: ")
        return frameWithData("\t  $data  \t")
    }

    private fun frameWithReorderedKeys(rawFrame: String): String {
        val original = envelopeFrom(rawFrame)
        val reordered = buildJsonObject {
            original.entries.toList().asReversed().forEach { (key, value) ->
                put(key, value)
            }
        }
        return frameWithData(reordered.toString())
    }

    private fun frameWithField(rawFrame: String, key: String, value: JsonElement?): String {
        val original = envelopeFrom(rawFrame)
        val mutated = buildJsonObject {
            original.forEach { (existingKey, existingValue) ->
                if (existingKey != key) put(existingKey, existingValue)
            }
            if (value != null) put(key, value)
        }
        return frameWithData(mutated.toString())
    }

    private fun dataPayload(rawFrame: String): String =
        rawFrame
            .removeSuffix("\n\n")
            .lineSequence()
            .single { it.startsWith("data: ") }
            .removePrefix("data: ")

    private fun envelopeFrom(rawFrame: String): JsonObject {
        return json.parseToJsonElement(dataPayload(rawFrame)).jsonObject
    }

    private fun assertIllegalArgument(label: String, rawFrame: String) {
        try {
            PrototypeSseTerminalDecoder.decodeDoneFrame(rawFrame)
            fail("$label was accepted")
        } catch (_: IllegalArgumentException) {
            // Every malformed frame must fail closed; exact diagnostics are not part of this slice.
        }
    }

    private fun readFixture(name: String): String {
        val candidates = listOf(
            Path.of("server/testdata/$name"),
            Path.of("../../server/testdata/$name"),
        )
        val path = candidates.firstOrNull { Files.isRegularFile(it) }
            ?: error("shared fixture not found: ${candidates.joinToString()}")
        return Files.readAllBytes(path).toString(Charsets.UTF_8)
    }
}
