package com.aneb.probe.prototype

import com.aneb.probe.net.RawSseEvent
import com.aneb.probe.net.RawSseStream

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
        return PrototypeRunStreamResult(
            rawEvents = rawEvents,
            decodedTerminal = decodedTerminal,
        )
    }
}
