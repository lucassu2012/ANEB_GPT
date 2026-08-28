package com.aneb.probe.prototype

import com.aneb.probe.net.RawSseEvent
import com.aneb.probe.net.RawSseStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

class PrototypeRunStreamAdapterTest {
    @Test
    fun postTransportPreservesProvidedRawEventsAndDecodesSharedTerminalFixture() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        // `run_started` and `content_event` are transport carriers only in this
        // atom; full 122-frame topology and outer-sequence validation are NONCLAIMS.
        val streamText = buildString {
            append("event: run_started\ndata: {\"event_type\":\"run_started\"}\n\n")
            append("event: content_event\ndata: {\"event_type\":\"content_event\"}\n\n")
            append(doneFrame)
        }
        val blocks = streamText.split("\n\n").filter(String::isNotBlank)
        val arrivals = listOf(1_000L, 2_000L, 3_000L)
        val rawStream = RawSseStream(
            events = blocks.mapIndexed { index, block ->
                RawSseEvent(
                    bytes = block.toByteArray(Charsets.UTF_8),
                    arrivalNanos = arrivals[index],
                    sameReadBatch = false,
                )
            },
            readCount = blocks.size,
            totalBytes = streamText.toByteArray(Charsets.UTF_8).size.toLong(),
            truncatedTail = false,
            eofNanos = 4_000L,
        )
        val endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs"
        val requestBody = "{\"validated_request\":true}"
        val transport = FakeRawPostTransport(rawStream)

        val result = PrototypeRunStreamAdapter(transport).run(endpoint, requestBody)

        assertEquals(endpoint, transport.postedUrl)
        assertEquals(requestBody, transport.postedBody)
        assertEquals(1, transport.callCount)
        assertSame(rawStream.events, result.rawEvents)
        assertEquals(arrivals, result.rawEvents.map(RawSseEvent::arrivalNanos))
        assertEquals(
            blocks,
            result.rawEvents.map { it.bytes.toString(Charsets.UTF_8) },
        )
        assertNotNull(result.decodedTerminal)
        assertEquals("done", result.decodedTerminal.eventName)
        assertEquals(
            "terminal_event",
            result.decodedTerminal.envelope.getValue("event_type").jsonPrimitive.content,
        )
        assertTrue(result.rawEvents.last().bytes.contentEquals(blocks.last().toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun truncatedPostStreamFailsClosedBeforeTerminalDecode() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val streamText = buildString {
            append("event: run_started\ndata: {\"event_type\":\"run_started\"}\n\n")
            append("event: content_event\ndata: {\"event_type\":\"content_event\"}\n\n")
            append(doneFrame)
        }
        val blocks = streamText.split("\n\n").filter(String::isNotBlank)
        val arrivals = listOf(1_000L, 2_000L, 3_000L)
        val rawStream = RawSseStream(
            events = blocks.mapIndexed { index, block ->
                RawSseEvent(
                    bytes = block.toByteArray(Charsets.UTF_8),
                    arrivalNanos = arrivals[index],
                    sameReadBatch = false,
                )
            },
            readCount = blocks.size,
            totalBytes = streamText.toByteArray(Charsets.UTF_8).size.toLong(),
            truncatedTail = true,
            eofNanos = 4_000L,
        )
        var postCalls = 0
        val transport = object : PrototypeRawPostTransport {
            override suspend fun post(url: String, requestBody: String): RawSseStream {
                postCalls += 1
                return rawStream
            }
        }

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"validated_request\":true}",
            )
            org.junit.Assert.fail("truncated stream was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals("prototype SSE stream has a truncated tail", error.message)
        }
        assertEquals(1, postCalls)
    }

    @Test
    fun duplicateDonePostStreamFailsClosedBeforeTerminalDecode() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val streamText = buildString {
            append("event: run_started\ndata: {\"event_type\":\"run_started\"}\n\n")
            append("event: content_event\ndata: {\"event_type\":\"content_event\"}\n\n")
            append(doneFrame)
            append(doneFrame)
        }
        val blocks = streamText.split("\n\n").filter(String::isNotBlank)
        val arrivals = listOf(1_000L, 2_000L, 3_000L, 4_000L)
        val rawStream = RawSseStream(
            events = blocks.mapIndexed { index, block ->
                RawSseEvent(
                    bytes = block.toByteArray(Charsets.UTF_8),
                    arrivalNanos = arrivals[index],
                    sameReadBatch = false,
                )
            },
            readCount = blocks.size,
            totalBytes = streamText.toByteArray(Charsets.UTF_8).size.toLong(),
            truncatedTail = false,
            eofNanos = 5_000L,
        )
        val transport = FakeRawPostTransport(rawStream)

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"validated_request\":true}",
            )
            org.junit.Assert.fail("duplicate final done stream was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE stream must contain exactly one final done event",
                error.message,
            )
        }
    }

    @Test
    fun emptyPostStreamFailsClosedWithUniqueFinalDoneMessage() = runBlocking {
        val transport = FakeRawPostTransport(rawStreamOf(emptyList()))

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"validated_request\":true}",
            )
            org.junit.Assert.fail("empty stream was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE stream must contain exactly one final done event",
                error.message,
            )
        }
    }

    @Test
    fun postStreamWithoutDoneCarrierFailsClosedWithUniqueFinalDoneMessage() = runBlocking {
        val rawStream = rawStreamOf(carrierBlocks())
        val transport = FakeRawPostTransport(rawStream)

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"validated_request\":true}",
            )
            org.junit.Assert.fail("stream without done was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE stream must contain exactly one final done event",
                error.message,
            )
        }
    }

    @Test
    fun canonicalDoneFollowedByContentIsRejectedByTerminalDecoder() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val content = "event: content_event\ndata: {\"event_type\":\"content_event\"}"
        val rawStream = rawStreamOf(carrierBlocks() + doneFrame.removeSuffix("\n\n") + content)
        val transport = FakeRawPostTransport(rawStream)

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"validated_request\":true}",
            )
            org.junit.Assert.fail("content after done was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals("done SSE event line must be exactly 'event: done'", error.message)
        }
    }

    @Test
    fun malformedFinalDoneIsRejectedByTerminalDecoder() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val malformedDone = doneFrame.replace(
            "\"event_type\":\"terminal_event\"",
            "\"event_type\":\"content_event\"",
        )
        val transport = FakeRawPostTransport(rawStreamOf(listOf(malformedDone.removeSuffix("\n\n"))))

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"validated_request\":true}",
            )
            org.junit.Assert.fail("malformed final done was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals("done SSE event_type must be exactly terminal_event", error.message)
        }
    }

    @Test
    fun postTransportIOExceptionIsPropagatedUnchanged() = runBlocking {
        val failure = IOException("prototype transport failed")
        var postCalls = 0
        val transport = object : PrototypeRawPostTransport {
            override suspend fun post(url: String, requestBody: String): RawSseStream {
                postCalls += 1
                throw failure
            }
        }

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"validated_request\":true}",
            )
            org.junit.Assert.fail("IOException was swallowed")
        } catch (error: IOException) {
            assertSame(failure, error)
        }
        assertEquals(1, postCalls)
    }

    @Test
    fun postTransportCancellationIsPropagatedUnchanged() = runBlocking {
        val failure = CancellationException("prototype transport cancelled")
        var postCalls = 0
        val transport = object : PrototypeRawPostTransport {
            override suspend fun post(url: String, requestBody: String): RawSseStream {
                postCalls += 1
                throw failure
            }
        }

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"validated_request\":true}",
            )
            org.junit.Assert.fail("CancellationException was swallowed")
        } catch (error: CancellationException) {
            assertSame(failure, error)
        }
        assertEquals(1, postCalls)
    }

    private class FakeRawPostTransport(
        private val response: RawSseStream,
    ) : PrototypeRawPostTransport {
        var callCount = 0
        var postedUrl: String? = null
        var postedBody: String? = null

        override suspend fun post(url: String, requestBody: String): RawSseStream {
            callCount += 1
            postedUrl = url
            postedBody = requestBody
            return response
        }
    }

    private fun carrierBlocks(): List<String> = listOf(
        "event: run_started\ndata: {\"event_type\":\"run_started\"}",
        "event: content_event\ndata: {\"event_type\":\"content_event\"}",
    )

    private fun rawStreamOf(
        blocks: List<String>,
        truncatedTail: Boolean = false,
    ): RawSseStream {
        val streamText = blocks.joinToString(separator = "\n\n", postfix = "\n\n")
        return RawSseStream(
            events = blocks.mapIndexed { index, block ->
                RawSseEvent(
                    bytes = block.toByteArray(Charsets.UTF_8),
                    arrivalNanos = (index + 1) * 1_000L,
                    sameReadBatch = false,
                )
            },
            readCount = blocks.size,
            totalBytes = streamText.toByteArray(Charsets.UTF_8).size.toLong(),
            truncatedTail = truncatedTail,
            eofNanos = (blocks.size + 1) * 1_000L,
        )
    }

    private fun readFixture(name: String): String {
        val candidates = listOf(
            Path.of("server/testdata/$name"),
            Path.of("../../server/testdata/$name"),
        )
        val path = candidates.firstOrNull { Files.isRegularFile(it) }
            ?: error("shared fixture not found: ${candidates.joinToString()}")
        val raw = Files.readAllBytes(path).toString(Charsets.UTF_8)
        val normalized = raw.replace("\r\n", "\n")
        require('\r' !in normalized) { "shared fixture contains a bare CR" }
        return normalized
    }
}
