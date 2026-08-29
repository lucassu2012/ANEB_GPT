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
        // Content sequence and content run identity are claimed in this atom;
        // request/terminal cross-binding and payload remain NONCLAIMS.
        val blocks = canonicalBlocks(doneFrame, contentCount = 120)
        val streamText = blocks.joinToString(separator = "\n\n", postfix = "\n\n")
        val arrivals = blocks.indices.map { (it + 1) * 1_000L }
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
    fun missingContentTopologyFailsClosedWithStableMessage() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val rawStream = rawStreamOf(canonicalBlocks(doneFrame, contentCount = 119))
        val transport = FakeRawPostTransport(rawStream)

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"validated_request\":true}",
            )
            org.junit.Assert.fail("119 content events were accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE stream must contain run_started, 120 content events, and final done",
                error.message,
            )
        }
    }

    @Test
    fun contentSequenceMustBeExactOneThrough120() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val sequenceValues = listOf(1, 1) + (3..120).toList()
        val blocks = buildList {
            add("event: run_started\ndata: {\"event_type\":\"run_started\"}")
            sequenceValues.forEach { seq -> add(serverContentBlock(seq)) }
            add(doneFrame.removeSuffix("\n\n"))
        }
        assertEquals(122, blocks.size)
        val transport = FakeRawPostTransport(rawStreamOf(blocks))

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"validated_request\":true}",
            )
            org.junit.Assert.fail("duplicate content sequence was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE content events must have exact seq 1 through 120",
                error.message,
            )
        }
    }

    @Test
    fun contentRunIdentityMismatchFailsClosedWithStableMessage() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        blocks[0] =
            "event: run_started\ndata: " +
                "{\"event_type\":\"run_started\",\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}"
        val mismatchedContentIndex = 1 + 41
        blocks[mismatchedContentIndex] = blocks[mismatchedContentIndex].replace(
            "\"campaign_id\":\"campaign-1\"",
            "\"campaign_id\":\"campaign-mismatch\"",
        )
        val transport = FakeRawPostTransport(rawStreamOf(blocks))

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("content campaign identity mismatch was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE content event identity must match the run",
                error.message,
            )
        }
    }

    @Test
    fun deeplyNestedContentPayloadFailsWithStableSequenceMessage() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        val nestedSeq = buildString {
            repeat(4_000) { append('[') }
            append('1')
            repeat(4_000) { append(']') }
        }
        blocks[1] = serverContentBlock(1).replace(
            "\"seq\":1,",
            "\"seq\":$nestedSeq,",
        )
        try {
            PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(blocks))).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"validated_request\":true}",
            )
            org.junit.Assert.fail("deeply nested content payload was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE content events must have exact seq 1 through 120",
                error.message,
            )
        }
    }

    @Test
    fun contentEventWithThirdDataLineFailsClosed() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        val contentIndex = 1 + 4
        blocks[contentIndex] = blocks[contentIndex] +
            "\ndata: {\"event_type\":\"content_event\",\"details\":{\"seq\":999}}"
        val transport = FakeRawPostTransport(rawStreamOf(blocks))

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"validated_request\":true}",
            )
            org.junit.Assert.fail("content event with a third data line was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE content events must have exact seq 1 through 120",
                error.message,
            )
        }
    }

    @Test
    fun contentSequenceRejectsNonCanonicalIntegerLexemes() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val acceptedTokens = mutableListOf<String>()

        listOf("1.0", "1e0").forEach { token ->
            val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
            val firstContent = blocks[1]
            blocks[1] = firstContent.replace(
                "\"seq\":1,\"planned_offset_ms\"",
                "\"seq\":$token,\"planned_offset_ms\"",
            )
            require(blocks[1] != firstContent)

            try {
                PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(blocks))).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = "{\"validated_request\":true}",
                )
                acceptedTokens += token
            } catch (error: IllegalArgumentException) {
                assertEquals(
                    "prototype SSE content events must have exact seq 1 through 120",
                    error.message,
                )
            }
        }

        assertTrue(
            "non-canonical seq tokens were accepted: $acceptedTokens",
            acceptedTokens.isEmpty(),
        )
    }

    @Test
    fun contentSequenceMustRespectReceivedOrder() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        val content40Index = 40
        val content41Index = 41
        val content40 = blocks[content40Index]
        blocks[content40Index] = blocks[content41Index]
        blocks[content41Index] = content40
        val transport = FakeRawPostTransport(rawStreamOf(blocks))

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"validated_request\":true}",
            )
            org.junit.Assert.fail("out-of-order content sequence was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE content events must have exact seq 1 through 120",
                error.message,
            )
        }
    }

    @Test
    fun topologyPrecedesContentSequenceValidation() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        blocks[1] = blocks[1].replaceFirst("event: content_event", "event: forged")
        blocks[2] = blocks[2].replace(
            "\"seq\":2,\"planned_offset_ms\"",
            "\"seq\":1,\"planned_offset_ms\"",
        )
        val transport = FakeRawPostTransport(rawStreamOf(blocks))

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"validated_request\":true}",
            )
            org.junit.Assert.fail("invalid topology was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE stream must contain run_started, 120 content events, and final done",
                error.message,
            )
        }
    }

    @Test
    fun contentDataShapeAndTypesFailClosedWithStableSequenceMessage() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val cases = listOf(
            "empty payload" to "",
            "root null" to "null",
            "root array" to "[]",
            "event_type missing" to "{\"details\":{\"seq\":1}}",
            "event_type null" to "{\"event_type\":null,\"details\":{\"seq\":1}}",
            "event_type array" to "{\"event_type\":[],\"details\":{\"seq\":1}}",
            "details missing" to "{\"event_type\":\"content_event\"}",
            "details null" to "{\"event_type\":\"content_event\",\"details\":null}",
            "details array" to "{\"event_type\":\"content_event\",\"details\":[]}",
            "seq missing" to "{\"event_type\":\"content_event\",\"details\":{}}",
            "seq null" to "{\"event_type\":\"content_event\",\"details\":{\"seq\":null}}",
            "seq string" to "{\"event_type\":\"content_event\",\"details\":{\"seq\":\"1\"}}",
            "seq bool" to "{\"event_type\":\"content_event\",\"details\":{\"seq\":true}}",
            "seq array" to "{\"event_type\":\"content_event\",\"details\":{\"seq\":[1]}}",
        )
        val accepted = mutableListOf<String>()
        val wrongMessages = mutableListOf<String>()
        val unexpectedErrors = mutableListOf<String>()
        val stableMessage = "prototype SSE content events must have exact seq 1 through 120"

        cases.forEach { (name, data) ->
            val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
            blocks[1] = "event: content_event\ndata: $data"
            try {
                PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(blocks))).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = "{\"validated_request\":true}",
                )
                accepted += name
            } catch (error: IllegalArgumentException) {
                if (error.message != stableMessage) {
                    wrongMessages += "$name -> ${error.message}"
                }
            } catch (error: Throwable) {
                unexpectedErrors += "$name -> ${error::class.simpleName}: ${error.message}"
            }
        }

        assertTrue(
            "accepted=$accepted; wrongMessages=$wrongMessages; unexpected=$unexpectedErrors",
            accepted.isEmpty() && wrongMessages.isEmpty() && unexpectedErrors.isEmpty(),
        )
    }

    @Test
    fun duplicateSeqMemberFailsClosedWithStableSequenceMessage() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val stableMessage = "prototype SSE content events must have exact seq 1 through 120"
        val acceptedRed = mutableListOf<String>()
        val wrongMessages = mutableListOf<String>()
        val duplicateBlocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        duplicateBlocks[1] = duplicateBlocks[1].replace(
            "\"seq\":1,\"planned_offset_ms\"",
            "\"seq\":999,\"seq\":1,\"planned_offset_ms\"",
        )
        try {
            PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(duplicateBlocks))).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"validated_request\":true}",
            )
            acceptedRed += "literal duplicate seq"
        } catch (error: IllegalArgumentException) {
            if (error.message != stableMessage) {
                wrongMessages += "literal duplicate seq -> ${error.message}"
            }
        }

        val duplicateDetailsBlocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        duplicateDetailsBlocks[1] =
            "event: content_event\ndata: " +
                "{\"event_type\":\"content_event\",\"details\":{\"seq\":999}," +
                "\"details\":{\"seq\":1}}"
        try {
            PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(duplicateDetailsBlocks))).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"validated_request\":true}",
            )
            acceptedRed += "duplicate details"
        } catch (error: IllegalArgumentException) {
            if (error.message != stableMessage) {
                wrongMessages += "duplicate details -> ${error.message}"
            }
        }

        val escapedSeqBlocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        escapedSeqBlocks[1] =
            "event: content_event\ndata: " +
                "{\"event_type\":\"content_event\",\"details\":{\"seq\":999," +
                "\"\\u0073eq\":1}}"
        try {
            PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(escapedSeqBlocks))).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"validated_request\":true}",
            )
            acceptedRed += "escaped duplicate seq"
        } catch (error: IllegalArgumentException) {
            if (error.message != stableMessage) {
                wrongMessages += "escaped duplicate seq -> ${error.message}"
            }
        }

        val reverseControlBlocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        reverseControlBlocks[1] = reverseControlBlocks[1].replace(
            "\"seq\":1,\"planned_offset_ms\"",
            "\"seq\":1,\"seq\":999,\"planned_offset_ms\"",
        )
        try {
            PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(reverseControlBlocks))).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"validated_request\":true}",
            )
            org.junit.Assert.fail("reverse duplicate seq control was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(stableMessage, error.message)
        }

        assertTrue(
            "acceptedRed=$acceptedRed; wrongMessages=$wrongMessages",
            acceptedRed.isEmpty() && wrongMessages.isEmpty(),
        )
    }

    @Test
    fun duplicateKeyBoundaryPreservesDistinctAndEscapedKeys() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val stableMessage = "prototype SSE content events must have exact seq 1 through 120"
        val invalidBlocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        invalidBlocks[1] =
            "event: content_event\ndata: " +
                "{\"details\":{\"seq\":999},\"det\\u0061ils\":{\"seq\":1}," +
                "\"event_type\":\"content_event\"}"
        try {
            PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(invalidBlocks))).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"validated_request\":true}",
            )
            org.junit.Assert.fail("escaped duplicate details member was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(stableMessage, error.message)
        }

        val accepted = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        val bracketText = buildString {
            repeat(256) { append('[') }
            repeat(256) { append(']') }
        }
        val escapedBracketText = "\\\"quote\\\" and \\\\ [$bracketText]"
        val validCases = listOf(
            "single escaped seq" to
                "{\"event_type\":\"content_event\",\"details\":{\"\\u0073eq\":1}}",
            "reordered distinct extras" to
                "{\"extra_root\":{\"v\":1},\"details\":{\"extra_nested\":[1,2],\"seq\":1}," +
                    "\"event_type\":\"content_event\"}",
            "text containing seq and details" to
                "{\"event_type\":\"content_event\",\"details\":{\"seq\":1," +
                    "\"note\":\"seq details\"}}",
            "string brackets" to
                "{\"event_type\":\"content_event\",\"details\":{\"seq\":1," +
                    "\"note\":\"$bracketText\"}}",
            "escaped quote backslash brackets" to
                "{\"event_type\":\"content_event\",\"details\":{\"seq\":1," +
                    "\"note\":\"$escapedBracketText\"}}",
            "duplicate unknown root" to
                "{\"noise\":{\"v\":1},\"noise\":{\"v\":2}," +
                    "\"event_type\":\"content_event\",\"details\":{\"seq\":1}}",
            "duplicate nested planned offset" to
                "{\"event_type\":\"content_event\",\"details\":{\"seq\":1," +
                    "\"planned_offset_ms\":0,\"planned_offset_ms\":0}}",
        )
        validCases.forEach { (name, data) ->
            val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
            blocks[1] = "event: content_event\ndata: $data"
            try {
                PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(blocks))).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = "{\"validated_request\":true}",
                )
                accepted += name
            } catch (error: IllegalArgumentException) {
                rejected += "$name -> ${error.message}"
            } catch (error: Throwable) {
                rejected += "$name -> ${error::class.simpleName}: ${error.message}"
            }
        }

        assertTrue(
            "accepted=$accepted; rejected=$rejected",
            accepted == validCases.map { it.first } && rejected.isEmpty(),
        )
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

    private fun canonicalBlocks(doneFrame: String, contentCount: Int): List<String> = buildList {
        add("event: run_started\ndata: {\"event_type\":\"run_started\"}")
        repeat(contentCount) { index ->
            add(serverContentBlock(index + 1))
        }
        add(doneFrame.removeSuffix("\n\n"))
    }

    private fun serverContentBlock(seq: Int): String =
        "event: content_event\ndata: " +
            "{\"schema_version\":\"aneb-prototype-evidence-0.1\"," +
            "\"protocol_version\":\"prototype-stream-0.1\"," +
            "\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"," +
            "\"condition_id\":\"condition-1\",\"event_type\":\"content_event\"," +
            "\"server_monotonic_ns\":0,\"clock_source\":\"server.monotonic\"," +
            "\"clock_unit\":\"ns\",\"clock_epoch\":\"process\",\"source\":\"server\"," +
            "\"details\":{\"seq\":$seq,\"planned_offset_ms\":0," +
            "\"payload_id\":\"payload-$seq\",\"profile_manifest_sha256\":\"manifest\"," +
            "\"schedule_hash\":\"schedule\"}}"

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
