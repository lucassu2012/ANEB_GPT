package com.aneb.probe.prototype

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Decodes the single terminal `done` frame emitted by the Prototype 0.1 server.
 *
 * This is deliberately a frame-level seam: stream ordering, content-event
 * accounting, identity chronology, and run orchestration remain integration
 * responsibilities. The returned envelope preserves the server JSON object so
 * the caller can validate and project its `details` without renaming fields.
 */
object PrototypeSseTerminalDecoder {
    data class DecodedDoneFrame(
        val eventName: String,
        val envelope: JsonObject,
    )

    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun decodeDoneFrame(rawFrame: String): DecodedDoneFrame {
        require(rawFrame.endsWith("\n\n")) {
            "done SSE frame must end with a blank-line delimiter"
        }

        val frameBody = rawFrame.removeSuffix("\n\n")
        val lines = frameBody.split('\n')
        require(lines.size == 2) {
            "done SSE frame must contain exactly one event line and one data line"
        }
        require(lines[0] == EVENT_LINE) {
            "done SSE event line must be exactly '$EVENT_LINE'"
        }
        require(lines[1].startsWith(DATA_PREFIX)) {
            "done SSE frame must contain a data line"
        }

        val data = lines[1].removePrefix(DATA_PREFIX)
        require(data.isNotBlank()) {
            "done SSE data line must be non-empty"
        }

        val envelope = try {
            json.parseToJsonElement(data)
        } catch (error: Exception) {
            throw IllegalArgumentException("done SSE data must be valid JSON", error)
        }
        require(envelope is JsonObject) {
            "done SSE data root must be a JSON object"
        }
        require(envelope["event_type"] == JsonPrimitive("terminal_event")) {
            "done SSE event_type must be exactly terminal_event"
        }
        require(envelope["details"] is JsonObject) {
            "done SSE details must be a JSON object"
        }

        return DecodedDoneFrame(
            eventName = EVENT_NAME,
            envelope = envelope,
        )
    }

    private const val EVENT_NAME = "done"
    private const val EVENT_LINE = "event: done"
    private const val DATA_PREFIX = "data: "
}
