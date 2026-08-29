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

private const val TERMINAL_COMPLETION_ERROR =
    "prototype SSE terminal receipt must report complete 120-event delivery"

class PrototypeRunStreamAdapterTest {
    @Test
    fun runStartedPayloadEventTypeMustMatchSseEvent() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        blocks[0] = blocks[0].replace(
            "\"event_type\":\"run_started\"",
            "\"event_type\":\"content_event\"",
        )
        val transport = FakeRawPostTransport(rawStreamOf(blocks))

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"validated_request\":true}",
            )
            org.junit.Assert.fail("run_started payload event_type mismatch was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE run_started payload event_type must match the SSE event",
                error.message,
            )
        }
    }

    @Test
    fun runStartedPayloadAcceptsReorderedAndEscapedEventTypeRepresentations() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val payloads = listOf(
            "reordered keys" to
                "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\",\"event_type\":\"run_started\"}",
            "escaped key" to
                "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\",\"\\u0065vent_type\":\"run_started\"}",
            "escaped value" to
                "{\"event_type\":\"run_\\u0073tarted\",\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
        )

        payloads.forEach { (label, payload) ->
            val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
            blocks[0] = "event: run_started\ndata: $payload"
            try {
                val result = PrototypeRunStreamAdapter(
                    FakeRawPostTransport(rawStreamOf(blocks)),
                ).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = "{\"validated_request\":true}",
                )
                assertNotNull(result.decodedTerminal)
            } catch (error: Throwable) {
                org.junit.Assert.fail("$label representation was rejected: ${error.message}")
            }
        }
    }

    @Test
    fun runStartedPayloadEventTypeDoesNotStealIdentityError() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val cases = listOf(
            "missing campaign_id" to
                "{\"event_type\":\"content_event\",\"run_id\":\"run-1\"}",
            "null campaign_id" to
                "{\"event_type\":\"content_event\",\"campaign_id\":null,\"run_id\":\"run-1\"}",
            "non-string run_id" to
                "{\"event_type\":\"content_event\",\"campaign_id\":\"campaign-1\",\"run_id\":1}",
            "duplicate campaign_id" to
                "{\"event_type\":\"content_event\",\"campaign_id\":\"forged\",\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            "malformed JSON" to
                "{\"event_type\":\"content_event\",\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"",
        )

        cases.forEach { (label, payload) ->
            val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
            blocks[0] = runStartedBlock(payload)
            try {
                PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(blocks))).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = "{\"validated_request\":true}",
                )
                org.junit.Assert.fail("$label with event_type drift was accepted")
            } catch (error: IllegalArgumentException) {
                assertEquals(
                    "prototype SSE content event identity must match the run",
                    error.message,
                )
            }
        }
    }

    @Test
    fun runStartedPayloadEventTypePrecedesDownstreamErrors() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val cases = listOf("content sequence", "arrival chronology", "terminal identity")

        cases.forEach { label ->
            val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
            blocks[0] = blocks[0].replace(
                "\"event_type\":\"run_started\"",
                "\"event_type\":\"content_event\"",
            )
            val arrivals = if (label == "arrival chronology") {
                blocks.indices.map { (it + 1) * 1_000L }.toMutableList().also {
                    it[42] = it[41] - 1L
                }
            } else {
                null
            }
            when (label) {
                "content sequence" -> blocks[1] = serverContentBlock(2)
                "terminal identity" -> blocks[blocks.lastIndex] =
                    blocks[blocks.lastIndex].replace("\"campaign-1\"", "\"forged-terminal\"")
            }
            val rawStream = arrivals?.let { rawStreamWithArrivals(blocks, it) } ?: rawStreamOf(blocks)
            try {
                PrototypeRunStreamAdapter(FakeRawPostTransport(rawStream)).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = "{\"validated_request\":true}",
                )
                org.junit.Assert.fail("$label error was hidden by acceptance")
            } catch (error: IllegalArgumentException) {
                assertEquals(
                    "prototype SSE run_started payload event_type must match the SSE event",
                    error.message,
                )
            }
        }
    }

    @Test
    fun runStartedPayloadEventTypeRejectsDuplicateWrongAndNormalizedValues() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val cases = listOf(
            "literal duplicate wrong then canonical" to
                "{\"event_type\":\"content_event\",\"event_type\":\"run_started\",\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            "literal duplicate canonical then wrong" to
                "{\"event_type\":\"run_started\",\"event_type\":\"content_event\",\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            "escaped duplicate wrong then canonical" to
                "{\"event_type\":\"content_event\",\"\\u0065vent_type\":\"run_started\",\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            "escaped duplicate canonical then wrong" to
                "{\"\\u0065vent_type\":\"run_started\",\"event_type\":\"content_event\",\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            "number" to
                "{\"event_type\":1,\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            "null" to
                "{\"event_type\":null,\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            "array" to
                "{\"event_type\":[],\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            "object" to
                "{\"event_type\":{},\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            "case drift" to
                "{\"event_type\":\"RUN_STARTED\",\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            "space drift" to
                "{\"event_type\":\" run_started \",\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            "NFKC drift" to
                "{\"event_type\":\"\\uFF52un_started\",\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
        )

        cases.forEach { (label, payload) ->
            val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
            blocks[0] = runStartedBlock(payload)
            try {
                PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(blocks))).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = "{\"validated_request\":true}",
                )
                org.junit.Assert.fail("$label event_type was accepted")
            } catch (error: IllegalArgumentException) {
                assertEquals(
                    "prototype SSE run_started payload event_type must match the SSE event",
                    error.message,
                )
            }
        }
    }

    @Test
    fun runStartedPayloadRequiresEventTypeAndRejectsBoundaryWrongStrings() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val cases = listOf(
            "missing" to producerRunStartedPayload(eventTypeMembers = emptyList()),
            "empty" to producerRunStartedPayload(
                eventTypeMembers = listOf("\"event_type\":\"\""),
            ),
            "prefix" to producerRunStartedPayload(
                eventTypeMembers = listOf("\"event_type\":\"xrun_started\""),
            ),
            "suffix" to producerRunStartedPayload(
                eventTypeMembers = listOf("\"event_type\":\"run_startedx\""),
            ),
            "NUL" to producerRunStartedPayload(
                eventTypeMembers = listOf("\"event_type\":\"run_started\\u0000\""),
            ),
            "TAB" to producerRunStartedPayload(
                eventTypeMembers = listOf("\"event_type\":\"run_started\\t\""),
            ),
            "LF" to producerRunStartedPayload(
                eventTypeMembers = listOf("\"event_type\":\"run_started\\n\""),
            ),
            "CR" to producerRunStartedPayload(
                eventTypeMembers = listOf("\"event_type\":\"run_started\\r\""),
            ),
            "bool" to producerRunStartedPayload(
                eventTypeMembers = listOf("\"event_type\":true"),
            ),
        )

        cases.forEach { (label, payload) ->
            val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
            blocks[0] = runStartedBlock(payload)
            try {
                PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(blocks))).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = "{\"validated_request\":true}",
                )
                org.junit.Assert.fail("$label run_started event_type was accepted")
            } catch (error: IllegalArgumentException) {
                assertEquals(
                    "prototype SSE run_started payload event_type must match the SSE event",
                    error.message,
                )
            }
        }
    }

    @Test
    fun runStartedPayloadAcceptsProducerEnvelopeInAnyKeyOrder() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val payloads = listOf(
            "canonical" to producerRunStartedPayload(),
            "reordered" to producerRunStartedPayload(reordered = true),
        )

        payloads.forEach { (label, payload) ->
            val producer = officialProducerCases.first()
            val blocks = canonicalBlocksForIdentity(
                doneFrame = doneFrame,
                contentCount = 120,
                campaignId = producer.campaignId,
                runId = producer.runId,
            ).toMutableList()
            blocks[0] = runStartedBlock(payload)
            try {
                val result = PrototypeRunStreamAdapter(
                    FakeRawPostTransport(rawStreamOf(blocks)),
                ).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = "{\"validated_request\":true}",
                )
                assertNotNull(result.decodedTerminal)
            } catch (error: Throwable) {
                org.junit.Assert.fail("$label producer-shaped run_started was rejected: ${error.message}")
            }
        }
    }

    @Test
    fun runStartedPayloadRejectsSameValueEventTypeDuplicates() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val cases = listOf(
            "plain/plain" to producerRunStartedPayload(
                eventTypeMembers = listOf(
                    "\"event_type\":\"run_started\"",
                    "\"event_type\":\"run_started\"",
                ),
            ),
            "plain/escaped" to producerRunStartedPayload(
                eventTypeMembers = listOf(
                    "\"event_type\":\"run_started\"",
                    "\"\\u0065vent_type\":\"run_started\"",
                ),
            ),
        )

        cases.forEach { (label, payload) ->
            val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
            blocks[0] = runStartedBlock(payload)
            try {
                PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(blocks))).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = "{\"validated_request\":true}",
                )
                org.junit.Assert.fail("$label event_type duplicate was accepted")
            } catch (error: IllegalArgumentException) {
                assertEquals(
                    "prototype SSE run_started payload event_type must match the SSE event",
                    error.message,
                )
            }
        }
    }

    @Test
    fun runStartedSseEventLinePrecedesBadPayloadEventType() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        blocks[0] = "event: forged\ndata: " + producerRunStartedPayload(
            eventTypeMembers = listOf("\"event_type\":\"content_event\""),
        )

        try {
            PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(blocks))).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"validated_request\":true}",
            )
            org.junit.Assert.fail("topology drift hid behind payload event_type")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE stream must contain run_started, 120 content events, and final done",
                error.message,
            )
        }
    }

    @Test
    fun producerShapedRunStartedEventTypeControlsAcrossOfficialConditions() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        officialProducerCases.forEach { producer ->
            listOf(
                true to "run_started",
                false to "content_event",
            ).forEach { (accepted, eventType) ->
                val payload = producerRunStartedPayload(
                    eventTypeMembers = listOf("\"event_type\":\"$eventType\""),
                    producer = producer,
                )
                val blocks = canonicalBlocksForIdentity(
                    doneFrame = doneFrame,
                    contentCount = 120,
                    campaignId = producer.campaignId,
                    runId = producer.runId,
                ).toMutableList()
                blocks[0] = runStartedBlock(payload)
                val endpoint = if (producer.label == "slow") {
                    "http://127.0.0.1:19001/api/v1/prototype/runs?condition=slow_v0.1"
                } else {
                    "http://127.0.0.1:18088/api/v1/prototype/runs"
                }
                val requestBody = if (producer.label == "slow") {
                    "{\"campaign_id\":\"${producer.campaignId}\",\"run_id\":\"${producer.runId}\",\"condition_id\":\"slow_v0.1\"}"
                } else {
                    "{\"campaign_id\":\"${producer.campaignId}\",\"run_id\":\"${producer.runId}\"}"
                }
                val transport = FakeRawPostTransport(rawStreamOf(blocks))

                try {
                    val result = PrototypeRunStreamAdapter(transport).run(endpoint, requestBody)
                    if (!accepted) {
                        org.junit.Assert.fail(
                            "${producer.label} event_type=$eventType was accepted",
                        )
                    }
                    assertNotNull(result.decodedTerminal)
                    if (producer.label == "slow") {
                        assertEquals(endpoint, transport.postedUrl)
                        assertEquals(requestBody, transport.postedBody)
                    }
                } catch (error: IllegalArgumentException) {
                    if (accepted) {
                        org.junit.Assert.fail(
                            "${producer.label} canonical producer event_type was rejected: ${error.message}",
                        )
                    }
                    assertEquals(
                        "prototype SSE run_started payload event_type must match the SSE event",
                        error.message,
                    )
                }
            }
        }
    }

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
            add(
                "event: run_started\ndata: " +
                    "{\"event_type\":\"run_started\",\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
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
    fun terminalReceiptIdentityMismatchFailsClosedWithStableMessage() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList().also {
            it[it.lastIndex] = doneFrame.removeSuffix("\n\n")
        }
        val transport = FakeRawPostTransport(rawStreamOf(blocks))

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("cross-run terminal receipt identity was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE terminal receipt identity must match the run",
                error.message,
            )
        }
    }

    @Test
    fun terminalCompletionFactsMustReportComplete120() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val variants = listOf(
            "terminal status" to
                ("\"terminal_status\":\"complete\"" to
                    "\"terminal_status\":\"failed\""),
            "planned event count" to
                ("\"planned_event_count\":120" to
                    "\"planned_event_count\":119"),
            "emitted event count" to
                ("\"emitted_event_count\":120" to
                    "\"emitted_event_count\":119"),
        )
        val accepted = mutableListOf<String>()

        variants.forEach { (label, replacement) ->
            val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
            val original = blocks[blocks.lastIndex]
            blocks[blocks.lastIndex] = original.replace(replacement.first, replacement.second)
            require(blocks[blocks.lastIndex] != original) { "$label fixture mutation did not apply" }

            try {
                PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(blocks))).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = "{\"validated_request\":true}",
                )
                accepted += label
            } catch (error: IllegalArgumentException) {
                assertEquals(
                    "prototype SSE terminal receipt must report complete 120-event delivery",
                    error.message,
                )
            }
        }

        assertTrue(
            "terminal completion fact variants were accepted: $accepted",
            accepted.isEmpty(),
        )
    }

    @Test
    fun terminalCompletionStatusRequiresExactString() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val invalidCases = listOf(
            "missing" to null,
            "null" to "null",
            "number" to "1",
            "boolean" to "true",
            "array" to "[]",
            "object" to "{}",
            "empty" to "\"\"",
            "case" to "\"Complete\"",
            "leading space" to "\" complete\"",
            "trailing space" to "\"complete \"",
            "tab" to "\"com\\tplete\"",
            "line feed" to "\"com\\nplete\"",
            "carriage return" to "\"com\\rplete\"",
            "NUL" to "\"com\\u0000plete\"",
            "NFKC" to "\"\\uFF43omplete\"",
            "prefix" to "\"xcomplete\"",
            "suffix" to "\"completex\"",
        )
        invalidCases.forEach { (label, replacement) ->
            val blocks = completionBlocks(doneFrame) { frame ->
                if (replacement == null) {
                    removeTerminalDetailValue(frame, "terminal_status", "\"complete\"")
                } else {
                    replaceTerminalDetailValue(
                        frame,
                        "terminal_status",
                        "\"complete\"",
                        replacement,
                    )
                }
            }
            assertTerminalCompletionRejected(label, blocks)
        }

        val escapedStatus = completionBlocks(doneFrame) { frame ->
            replaceTerminalDetailValue(
                frame,
                "terminal_status",
                "\"complete\"",
                "\"com\\u0070lete\"",
            )
        }
        assertTerminalCompletionAccepted("escaped status", escapedStatus)
    }

    @Test
    fun terminalCompletionCountsRequireExactJsonInteger120() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val fields = listOf(
            "planned_event_count",
            "emitted_event_count",
        )
        val invalidValues = listOf(
            "null" to "null",
            "boolean" to "true",
            "string" to "\"120\"",
            "119" to "119",
            "121" to "121",
            "float" to "120.0",
            "exponent" to "1.2e2",
            "array" to "[]",
            "object" to "{}",
        )
        fields.forEach { field ->
            assertTerminalCompletionRejected(
                "$field missing",
                completionBlocks(doneFrame) { frame ->
                    removeTerminalDetailValue(frame, field, "120")
                },
            )
            invalidValues.forEach { (label, replacement) ->
                assertTerminalCompletionRejected(
                    "$field $label",
                    completionBlocks(doneFrame) { frame ->
                        replaceTerminalDetailValue(frame, field, "120", replacement)
                    },
                )
            }
        }

        val coordinatedMismatch = completionBlocks(doneFrame) { frame ->
            replaceTerminalDetailValue(
                replaceTerminalDetailValue(frame, "planned_event_count", "120", "119"),
                "emitted_event_count",
                "120",
                "119",
            )
        }
        assertTerminalCompletionRejected("coordinated 119/119", coordinatedMismatch)

        val surroundingWhitespace = completionBlocks(doneFrame) { frame ->
            frame
                .replace(
                    "\"planned_event_count\":120",
                    "\"planned_event_count\" : \t120",
                )
                .replace(
                    "\"emitted_event_count\":120",
                    "\"emitted_event_count\" : \t120",
                )
        }
        assertTerminalCompletionAccepted("numeric surrounding whitespace", surroundingWhitespace)
    }

    @Test
    fun terminalCompletionLiteralDuplicatesAreRejectedForEveryField() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val fields = listOf(
            "planned_event_count" to ("120" to "119"),
            "emitted_event_count" to ("120" to "119"),
            "terminal_status" to ("\"complete\"" to "\"failed\""),
        )
        fields.forEach { (field, values) ->
            listOf(
                "canonical/canonical" to (values.first to values.first),
                "canonical/bad" to (values.first to values.second),
                "bad/canonical" to (values.second to values.first),
            ).forEach { (label, order) ->
                assertTerminalCompletionRejected(
                    "$field $label",
                    completionBlocks(doneFrame) { frame ->
                        duplicateTerminalDetailValues(frame, field, order.first, order.second)
                    },
                )
            }
        }
    }

    @Test
    fun terminalCompletionSemanticDuplicatesRejectBothKeyOrdersAndAllowSingleEscapes() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val fields = listOf(
            "planned_event_count" to ("\\u0070lanned_event_count" to "120"),
            "emitted_event_count" to ("\\u0065mitted_event_count" to "120"),
            "terminal_status" to ("terminal_\\u0073tatus" to "\"complete\""),
        )
        fields.forEach { (field, escaped) ->
            listOf(false, true).forEach { escapedFirst ->
                assertTerminalCompletionRejected(
                    "$field plain/escaped order=$escapedFirst",
                    completionBlocks(doneFrame) { frame ->
                        duplicateTerminalDetailKeys(
                            frame,
                            field,
                            escaped.first,
                            escaped.second,
                            escapedFirst,
                        )
                    },
                )
            }
            assertTerminalCompletionAccepted(
                "$field single escaped key",
                completionBlocks(doneFrame) { frame ->
                    replaceTerminalDetailKey(frame, field, escaped.first, escaped.second)
                },
            )
        }
    }

    @Test
    fun terminalCompletionPreservesUnknownOrderAndExistingErrorPrecedence() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val reorderedWithUnknown = completionBlocks(doneFrame) { frame ->
            frame
                .replace(
                    "\"planned_event_count\":120,\"emitted_event_count\":120,\"terminal_status\":\"complete\"",
                    "\"terminal_status\":\"complete\",\"emitted_event_count\":120,\"planned_event_count\":120",
                )
                .replace(
                    "\"terminal_status\":\"complete\"",
                    "\"terminal_status\":\"complete\",\"unknown_nested\":1",
                )
                .replaceFirst(
                    "{",
                    "{\"unknown_root\":1,",
                )
        }
        assertTerminalCompletionAccepted("reordered fields and unknown keys", reorderedWithUnknown)

        val rootNamedExtras = completionBlocks(doneFrame) { frame ->
            frame.replaceFirst(
                "{",
                "{\"terminal_status\":\"failed\",\"planned_event_count\":119,\"emitted_event_count\":119,",
            )
        }
        assertTerminalCompletionAccepted("root-layer completion names", rootNamedExtras)

        val nestedCompletionMissing = completionBlocks(doneFrame) { frame ->
            var mutated = frame
            mutated = removeTerminalDetailValue(mutated, "planned_event_count", "120")
            mutated = removeTerminalDetailValue(mutated, "emitted_event_count", "120")
            mutated = removeTerminalDetailValue(mutated, "terminal_status", "\"complete\"")
            mutated.replaceFirst(
                "{",
                "{\"terminal_status\":\"complete\",\"planned_event_count\":120,\"emitted_event_count\":120,",
            )
        }
        assertTerminalCompletionRejected("root-only completion facts", nestedCompletionMissing)

        val chronologyAndCompletion = completionBlocks(doneFrame) { frame ->
            replaceTerminalDetailValue(frame, "terminal_status", "\"complete\"", "\"failed\"")
        }
        val chronologyArrivals = chronologyAndCompletion.indices.map { (it + 1) * 1_000L }
            .toMutableList()
            .also { it[42] = it[41] - 1L }
        assertTerminalCompletionRejected(
            "chronology before completion",
            chronologyAndCompletion,
            chronologyArrivals,
            "prototype SSE content arrival timestamps must be non-negative and nondecreasing",
        )

        val identityAndCompletion = completionBlocks(doneFrame) { frame ->
            replaceTerminalDetailValue(
                frame
                    .replace("\"campaign-1\"", "\"forged-terminal\"")
                    .replace("\"run-1\"", "\"forged-terminal-run\""),
                "terminal_status",
                "\"complete\"",
                "\"failed\"",
            )
        }
        assertTerminalCompletionRejected(
            "identity before completion",
            identityAndCompletion,
            expectedMessage = "prototype SSE terminal receipt identity must match the run",
        )

        val rootDetailsDuplicate = completionBlocks(doneFrame) { frame ->
            duplicateRootDetails(frame, escapedCanonicalLast = false)
        }
        assertTerminalCompletionRejected(
            "duplicate root details before completion",
            rootDetailsDuplicate,
            expectedMessage = "prototype SSE terminal receipt identity must match the run",
        )
    }

    @Test
    fun terminalReceiptIdentityRequiresBothLayersToMatchRun() = runBlocking {
        val doneFrame = doneFrameForRun(readFixture("prototype_option_a_done_frame.sse"))
        val campaignKey = "\"campaign_id\":\"campaign-1\""
        val runKey = "\"run_id\":\"run-1\""
        val forgedCampaign = "\"campaign_id\":\"forged-campaign\""
        val forgedRun = "\"run_id\":\"forged-run\""
        val variants = listOf(
            "outer-only" to doneFrame
                .replaceFirst(campaignKey, forgedCampaign)
                .replaceFirst(runKey, forgedRun),
            "outer-campaign-only" to doneFrame.replaceFirst(campaignKey, forgedCampaign),
            "outer-run-only" to doneFrame.replaceFirst(runKey, forgedRun),
            "details-only" to replaceSecondOccurrence(
                replaceSecondOccurrence(doneFrame, campaignKey, forgedCampaign),
                runKey,
                forgedRun,
            ),
            "details-campaign-only" to replaceSecondOccurrence(doneFrame, campaignKey, forgedCampaign),
            "details-run-only" to replaceSecondOccurrence(doneFrame, runKey, forgedRun),
            "outer-and-details" to doneFrame
                .replace(campaignKey, forgedCampaign)
                .replace(runKey, forgedRun),
            "outer-and-details-campaign-only" to doneFrame.replace(campaignKey, forgedCampaign),
            "outer-and-details-run-only" to doneFrame.replace(runKey, forgedRun),
        )

        variants.forEach { (label, forgedDoneFrame) ->
            val transport = FakeRawPostTransport(
                rawStreamOf(canonicalBlocks(forgedDoneFrame, contentCount = 120)),
            )
            try {
                PrototypeRunStreamAdapter(transport).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
                )
                org.junit.Assert.fail("$label terminal identity was accepted")
            } catch (error: IllegalArgumentException) {
                assertEquals(
                    "prototype SSE terminal receipt identity must match the run",
                    error.message,
                )
            }
        }
    }

    @Test
    fun terminalReceiptDuplicateIdentityMemberFailsClosedWithStableMessage() = runBlocking {
        val doneFrame = doneFrameForRun(readFixture("prototype_option_a_done_frame.sse"))
        val campaignKey = "\"campaign_id\":\"campaign-1\""
        val runKey = "\"run_id\":\"run-1\""
        val duplicateVariants = listOf(
            "outer campaign" to doneFrame.replaceFirst(
                campaignKey,
                "\"campaign_id\":\"forged-campaign\",\"\\u0063ampaign_id\":\"campaign-1\"",
            ),
            "outer run" to doneFrame.replaceFirst(
                runKey,
                "\"run_id\":\"forged-run\",\"\\u0072un_id\":\"run-1\"",
            ),
            "details campaign" to replaceSecondOccurrence(
                doneFrame,
                campaignKey,
                "\"campaign_id\":\"forged-campaign\",\"\\u0063ampaign_id\":\"campaign-1\"",
            ),
            "details run" to replaceSecondOccurrence(
                doneFrame,
                runKey,
                "\"run_id\":\"forged-run\",\"\\u0072un_id\":\"run-1\"",
            ),
        )

        duplicateVariants.forEach { (label, duplicateDoneFrame) ->
            val transport = FakeRawPostTransport(
                rawStreamOf(canonicalBlocks(duplicateDoneFrame, contentCount = 120)),
            )
            try {
                PrototypeRunStreamAdapter(transport).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
                )
                org.junit.Assert.fail("$label duplicate terminal identity member was accepted")
            } catch (error: IllegalArgumentException) {
                assertEquals(
                    "prototype SSE terminal receipt identity must match the run",
                    error.message,
                )
            }
        }
    }

    @Test
    fun terminalReceiptDuplicateDetailsMemberFailsClosedWithStableMessage() = runBlocking {
        val doneFrame = doneFrameForRun(readFixture("prototype_option_a_done_frame.sse"))
        val forgedDetails = "{\"campaign_id\":\"forged-campaign\",\"run_id\":\"forged-run\"}"
        listOf(
            "literal" to (false to forgedDetails),
            "escaped" to (true to forgedDetails),
            "scalar-first" to (false to "7"),
        ).forEach { (keyKind, variant) ->
            val escapedCanonicalLast = variant.first
            val duplicateDoneFrame = duplicateRootDetails(
                doneFrame,
                escapedCanonicalLast = escapedCanonicalLast,
                firstDetails = variant.second,
            )
            val transport = FakeRawPostTransport(
                rawStreamOf(canonicalBlocks(duplicateDoneFrame, contentCount = 120)),
            )
            try {
                PrototypeRunStreamAdapter(transport).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
                )
                org.junit.Assert.fail(
                    "duplicate " + keyKind + " terminal details member was accepted",
                )
            } catch (error: IllegalArgumentException) {
                assertEquals(
                    "prototype SSE terminal receipt identity must match the run",
                    error.message,
                )
            }
        }
    }

    @Test
    fun terminalReceiptIdentityRejectsMissingNullAndNumberAtEitherLayer() = runBlocking {
        val doneFrame = doneFrameForRun(readFixture("prototype_option_a_done_frame.sse"))
        val campaignKey = "\"campaign_id\":\"campaign-1\""
        val runKey = "\"run_id\":\"run-1\""
        val campaignField = ",$campaignKey"
        val runField = ",$runKey"
        val variants = listOf(
            "outer campaign missing" to removeFirstOccurrence(doneFrame, campaignField),
            "outer campaign null" to doneFrame.replaceFirst(
                campaignKey,
                "\"campaign_id\":null",
            ),
            "outer campaign number" to doneFrame.replaceFirst(
                campaignKey,
                "\"campaign_id\":7",
            ),
            "outer run missing" to removeFirstOccurrence(doneFrame, runField),
            "outer run null" to doneFrame.replaceFirst(runKey, "\"run_id\":null"),
            "outer run number" to doneFrame.replaceFirst(runKey, "\"run_id\":7"),
            "details campaign missing" to removeSecondOccurrence(doneFrame, campaignField),
            "details campaign null" to replaceSecondOccurrence(
                doneFrame,
                campaignKey,
                "\"campaign_id\":null",
            ),
            "details campaign number" to replaceSecondOccurrence(
                doneFrame,
                campaignKey,
                "\"campaign_id\":7",
            ),
            "details run missing" to removeSecondOccurrence(doneFrame, runField),
            "details run null" to replaceSecondOccurrence(
                doneFrame,
                runKey,
                "\"run_id\":null",
            ),
            "details run number" to replaceSecondOccurrence(
                doneFrame,
                runKey,
                "\"run_id\":7",
            ),
        )

        variants.forEach { (label, invalidDoneFrame) ->
            val transport = FakeRawPostTransport(
                rawStreamOf(canonicalBlocks(invalidDoneFrame, contentCount = 120)),
            )
            try {
                PrototypeRunStreamAdapter(transport).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
                )
                org.junit.Assert.fail("$label terminal identity was accepted")
            } catch (error: IllegalArgumentException) {
                assertEquals(
                    "prototype SSE terminal receipt identity must match the run",
                    error.message,
                )
            }
        }
    }

    @Test
    fun terminalReceiptIdentityRejectsSpaceCaseAndNfkcNormalizationAtEitherLayer() = runBlocking {
        val doneFrame = doneFrameForRun(readFixture("prototype_option_a_done_frame.sse"))
        val campaignKey = "\"campaign_id\":\"campaign-1\""
        val runKey = "\"run_id\":\"run-1\""
        val variants = listOf(
            "outer campaign space" to doneFrame.replaceFirst(
                campaignKey,
                "\"campaign_id\":\"campaign-1 " + "\"",
            ),
            "outer campaign case" to doneFrame.replaceFirst(
                campaignKey,
                "\"campaign_id\":\"CAMPAIGN-1\"",
            ),
            "outer campaign nfkc" to doneFrame.replaceFirst(
                campaignKey,
                "\"campaign_id\":\"\\uFF43ampaign-1\"",
            ),
            "outer run space" to doneFrame.replaceFirst(
                runKey,
                "\"run_id\":\" run-1\"",
            ),
            "outer run case" to doneFrame.replaceFirst(
                runKey,
                "\"run_id\":\"RUN-1\"",
            ),
            "outer run nfkc" to doneFrame.replaceFirst(
                runKey,
                "\"run_id\":\"run-\\uFF11\"",
            ),
            "details campaign space" to replaceSecondOccurrence(
                doneFrame,
                campaignKey,
                "\"campaign_id\":\"campaign-1 " + "\"",
            ),
            "details campaign case" to replaceSecondOccurrence(
                doneFrame,
                campaignKey,
                "\"campaign_id\":\"CAMPAIGN-1\"",
            ),
            "details campaign nfkc" to replaceSecondOccurrence(
                doneFrame,
                campaignKey,
                "\"campaign_id\":\"\\uFF43ampaign-1\"",
            ),
            "details run space" to replaceSecondOccurrence(
                doneFrame,
                runKey,
                "\"run_id\":\"run-1 " + "\"",
            ),
            "details run case" to replaceSecondOccurrence(
                doneFrame,
                runKey,
                "\"run_id\":\"RUN-1\"",
            ),
            "details run nfkc" to replaceSecondOccurrence(
                doneFrame,
                runKey,
                "\"run_id\":\"run-\\uFF11\"",
            ),
        )

        variants.forEach { (label, invalidDoneFrame) ->
            val transport = FakeRawPostTransport(
                rawStreamOf(canonicalBlocks(invalidDoneFrame, contentCount = 120)),
            )
            try {
                PrototypeRunStreamAdapter(transport).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
                )
                org.junit.Assert.fail("$label terminal identity was accepted")
            } catch (error: IllegalArgumentException) {
                assertEquals(
                    "prototype SSE terminal receipt identity must match the run",
                    error.message,
                )
            }
        }
    }

    @Test
    fun terminalReceiptIdentityAcceptsEscapedKeysAndReorderedMembers() = runBlocking {
        val doneFrame = doneFrameForRun(readFixture("prototype_option_a_done_frame.sse"))
        val campaignKey = "\"campaign_id\":\"campaign-1\""
        val runKey = "\"run_id\":\"run-1\""
        val escapedCampaignDoneFrame = doneFrame.replaceFirst(
            campaignKey,
            "\"\\u0063ampaign_id\":\"campaign-1\"",
        )
        val reorderedDoneFrame = doneFrame.replaceFirst(
            "$campaignKey,\"run_id\":\"run-1\"",
            "\"run_id\":\"run-1\",$campaignKey",
        )
        val escapedKeysBothLayers = doneFrame
            .replace("\"campaign_id\"", "\"\\u0063ampaign_id\"")
            .replace("\"run_id\"", "\"\\u0072un_id\"")
        val reorderedKeysBothLayers = doneFrame
            .replaceFirst(
                "$campaignKey,\"run_id\":\"run-1\"",
                "\"run_id\":\"run-1\",$campaignKey",
            )
            .replaceFirst(
                "$campaignKey,\"run_id\":\"run-1\"",
                "\"run_id\":\"run-1\",$campaignKey",
            )
        val equivalentEscapedValues = doneFrame
            .replace("\"campaign-1\"", "\"campaign-\\u0031\"")
            .replace("\"run-1\"", "\"run-\\u0031\"")
        val detailsBeforeOuterIdentity = run {
            val frame = doneFrame.removeSuffix("\n\n")
            val payload = frame.substringAfter("data: ")
            val detailsMarker = ",\"details\":"
            val detailsIndex = payload.indexOf(detailsMarker)
            require(detailsIndex >= 0)
            val rootMembers = payload.substring(1, detailsIndex)
            val detailsMember = payload.substring(detailsIndex + 1, payload.length - 1)
            val nestedStart = detailsMember.indexOf('{')
            val nestedBody = detailsMember.substring(nestedStart + 1, detailsMember.length - 1)
            val nestedIdentity = "$campaignKey,$runKey,"
            val nestedWithoutIdentity = nestedBody.replace(nestedIdentity, "")
            val reorderedDetailsMember = detailsMember.substring(0, nestedStart + 1) +
                nestedWithoutIdentity + ",$campaignKey,$runKey}"
            val rootWithoutIdentity = rootMembers
                .replace("$campaignKey,", "")
                .replace("$runKey,", "")
            val reorderedPayload = "{$reorderedDetailsMember,$rootWithoutIdentity,$campaignKey,$runKey}"
            frame.substringBefore("data: ") + "data: " + reorderedPayload
        }
        val escapedRootDetailsOnly = doneFrame.replaceFirst(
            ",\"details\":",
            ",\"\\u0064etails\":",
        )

        listOf(
            escapedCampaignDoneFrame,
            reorderedDoneFrame,
            escapedKeysBothLayers,
            reorderedKeysBothLayers,
            equivalentEscapedValues,
            detailsBeforeOuterIdentity,
            escapedRootDetailsOnly,
        ).forEach { equivalentDoneFrame ->
            PrototypeRunStreamAdapter(FakeRawPostTransport(
                rawStreamOf(canonicalBlocks(equivalentDoneFrame, contentCount = 120)),
            )).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
        }
    }

    @Test
    fun terminalIdentityErrorsFollowArrivalChronology() = runBlocking {
        val doneFrame = doneFrameForRun(readFixture("prototype_option_a_done_frame.sse"))
            .replaceFirst(
                "\"campaign_id\":\"campaign-1\"",
                "\"campaign_id\":\"forged-campaign\"",
            )
        val blocks = canonicalBlocks(doneFrame, contentCount = 120)
        val arrivals = blocks.indices.map { (it + 1) * 1_000L }.toMutableList()
        arrivals[42] = arrivals[41] - 1L

        try {
            PrototypeRunStreamAdapter(FakeRawPostTransport(
                rawStreamWithArrivals(blocks, arrivals),
            )).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("terminal identity was checked before chronology")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE content arrival timestamps must be non-negative and nondecreasing",
                error.message,
            )
        }
    }

    @Test
    fun terminalDuplicateDetailsErrorsFollowArrivalChronology() = runBlocking {
        val doneFrame = duplicateRootDetails(
            doneFrameForRun(readFixture("prototype_option_a_done_frame.sse")),
            escapedCanonicalLast = false,
        )
        val blocks = canonicalBlocks(doneFrame, contentCount = 120)
        val arrivals = blocks.indices.map { (it + 1) * 1_000L }.toMutableList()
        arrivals[42] = arrivals[41] - 1L

        try {
            PrototypeRunStreamAdapter(FakeRawPostTransport(
                rawStreamWithArrivals(blocks, arrivals),
            )).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("terminal duplicate details was checked before chronology")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE content arrival timestamps must be non-negative and nondecreasing",
                error.message,
            )
        }
    }

    @Test
    fun runStartedMissingIdentityCannotAuthorizeContentRun() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val blocks = canonicalBlocks(doneFrame, contentCount = 120).mapIndexed { index, block ->
            if (index == 0) {
                "event: run_started\ndata: {\"event_type\":\"run_started\"}"
            } else if (index in 1..120) {
                block
                    .replace("\"campaign_id\":\"campaign-1\"", "\"campaign_id\":\"forged-campaign\"")
                    .replace("\"run_id\":\"run-1\"", "\"run_id\":\"forged-run\"")
            } else {
                block
            }
        }
        val transport = FakeRawPostTransport(rawStreamOf(blocks))

        try {
            PrototypeRunStreamAdapter(transport).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("content identity was accepted without run_started authority")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE content event identity must match the run",
                error.message,
            )
        }
    }

    @Test
    fun duplicateCampaignIdentityMemberFailsClosedWithStableMessage() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        val original = blocks[1]
        blocks[1] = original.replace(
            "\"campaign_id\":\"campaign-1\",\"run_id\"",
            "\"campaign_id\":\"forged-campaign\",\"\\u0063ampaign_id\":\"campaign-1\",\"run_id\"",
        )
        require(blocks[1] != original)

        try {
            PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(blocks))).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("duplicate campaign_id member was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE content event identity must match the run",
                error.message,
            )
        }
    }

    @Test
    fun duplicateRunIdentityMemberFailsClosedWithStableMessage() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        val original = blocks[1]
        blocks[1] = original.replace(
            "\"run_id\":\"run-1\",\"condition_id\"",
            "\"run_id\":\"forged-run\",\"\\u0072un_id\":\"run-1\",\"condition_id\"",
        )
        require(blocks[1] != original)

        try {
            PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(blocks))).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("duplicate run_id member was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE content event identity must match the run",
                error.message,
            )
        }
    }

    @Test
    fun runStartedIdentityRejectsPartialOrNonStringPair() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val cases = listOf(
            "missing campaign_id" to "{\"event_type\":\"run_started\",\"run_id\":\"run-1\"}",
            "missing run_id" to "{\"event_type\":\"run_started\",\"campaign_id\":\"campaign-1\"}",
            "numeric campaign_id" to "{\"event_type\":\"run_started\",\"campaign_id\":1,\"run_id\":\"run-1\"}",
            "null run_id" to "{\"event_type\":\"run_started\",\"campaign_id\":\"campaign-1\",\"run_id\":null}",
        )
        val accepted = mutableListOf<String>()
        val wrongMessages = mutableListOf<String>()
        cases.forEach { (name, payload) ->
            val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
            blocks[0] = "event: run_started\ndata: $payload"
            try {
                PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(blocks))).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
                )
                accepted += name
            } catch (error: IllegalArgumentException) {
                if (error.message != "prototype SSE content event identity must match the run") {
                    wrongMessages += "$name -> ${error.message}"
                }
            }
        }
        assertTrue(
            "accepted=$accepted; wrongMessages=$wrongMessages",
            accepted.isEmpty() && wrongMessages.isEmpty(),
        )
    }

    @Test
    fun contentIdentityRejectsMissingOrNonStringPair() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val canonical = serverContentBlock(1)
        val cases = listOf(
            "missing campaign_id" to canonical.replace(
                "\"campaign_id\":\"campaign-1\",",
                "",
            ),
            "missing run_id" to canonical.replace(
                "\"run_id\":\"run-1\",",
                "",
            ),
            "numeric campaign_id" to canonical.replace(
                "\"campaign_id\":\"campaign-1\"",
                "\"campaign_id\":1",
            ),
            "null run_id" to canonical.replace(
                "\"run_id\":\"run-1\"",
                "\"run_id\":null",
            ),
        )
        val accepted = mutableListOf<String>()
        val wrongMessages = mutableListOf<String>()
        cases.forEach { (name, block) ->
            val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
            blocks[1] = block
            try {
                PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(blocks))).run(
                    endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                    requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
                )
                accepted += name
            } catch (error: IllegalArgumentException) {
                if (error.message != "prototype SSE content event identity must match the run") {
                    wrongMessages += "$name -> ${error.message}"
                }
            }
        }
        assertTrue(
            "accepted=$accepted; wrongMessages=$wrongMessages",
            accepted.isEmpty() && wrongMessages.isEmpty(),
        )
    }

    @Test
    fun contentArrivalTimestampRegressionFailsClosedWithStableMessage() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val blocks = canonicalBlocks(doneFrame, contentCount = 120)
        val arrivals = blocks.indices.map { (it + 1) * 1_000L }.toMutableList()
        arrivals[42] = arrivals[41] - 1L

        try {
            PrototypeRunStreamAdapter(
                FakeRawPostTransport(rawStreamWithArrivals(blocks, arrivals)),
            ).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("content arrival timestamp regression was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE content arrival timestamps must be non-negative and nondecreasing",
                error.message,
            )
        }
    }

    @Test
    fun contentArrivalTimestampAllowsZeroAndEqualAdjacentValues() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val blocks = canonicalBlocks(doneFrame, contentCount = 120)
        val arrivals = blocks.indices.map { (it + 1) * 1_000L }.toMutableList()
        arrivals[1] = 0L
        arrivals[2] = 0L

        val result = PrototypeRunStreamAdapter(
            FakeRawPostTransport(rawStreamWithArrivals(blocks, arrivals)),
        ).run(
            endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
            requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
        )

        assertEquals(0L, result.rawEvents[1].arrivalNanos)
        assertEquals(0L, result.rawEvents[2].arrivalNanos)
    }

    @Test
    fun negativeContentArrivalTimestampFailsWithStableMessage() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val blocks = canonicalBlocks(doneFrame, contentCount = 120)
        val arrivals = blocks.indices.map { (it + 1) * 1_000L }.toMutableList()
        arrivals[42] = -1L

        val message = runCatching {
            PrototypeRunStreamAdapter(
                FakeRawPostTransport(rawStreamWithArrivals(blocks, arrivals)),
            ).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
        }.exceptionOrNull()?.message

        assertEquals(
            "prototype SSE content arrival timestamps must be non-negative and nondecreasing",
            message,
        )
    }

    @Test
    fun contentSemanticErrorsPrecedeArrivalChronology() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val baseArrivals = canonicalBlocks(doneFrame, contentCount = 120)
            .indices
            .map { (it + 1) * 1_000L }
        val sequenceBlocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        sequenceBlocks[1] = serverContentBlock(2)
        val sequenceArrivals = baseArrivals.toMutableList().also {
            it[42] = it[41] - 1L
        }
        val sequenceMessage = runCatching {
            PrototypeRunStreamAdapter(
                FakeRawPostTransport(rawStreamWithArrivals(sequenceBlocks, sequenceArrivals)),
            ).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
        }.exceptionOrNull()?.message
        assertEquals(
            "prototype SSE content events must have exact seq 1 through 120",
            sequenceMessage,
        )

        val identityBlocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        identityBlocks[1] = serverContentBlock(1).replace(
            "\"campaign_id\":\"campaign-1\"",
            "\"campaign_id\":\"campaign-mismatch\"",
        )
        val identityArrivals = baseArrivals.toMutableList().also {
            it[42] = it[41] - 1L
        }
        val identityMessage = runCatching {
            PrototypeRunStreamAdapter(
                FakeRawPostTransport(rawStreamWithArrivals(identityBlocks, identityArrivals)),
            ).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
        }.exceptionOrNull()?.message
        assertEquals(
            "prototype SSE content event identity must match the run",
            identityMessage,
        )
    }

    @Test
    fun sequenceDuplicatePrecedesIdentityDuplicateWhenIdentityKeyAppearsFirst() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        blocks[1] =
            "event: content_event\ndata: " +
                "{\"campaign_id\":\"forged-campaign\",\"campaign_id\":\"campaign-1\",\"" +
                "run_id\":\"run-1\",\"event_type\":\"content_event\",\"details\":{" +
                "seq\":999,\"seq\":1}}"

        try {
            PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(blocks))).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("identity duplicate ahead of sequence duplicate was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE content events must have exact seq 1 through 120",
                error.message,
            )
        }
    }

    @Test
    fun sequenceDuplicatePrecedesIdentityDuplicateWhenSequenceKeyAppearsFirst() = runBlocking {
        val doneFrame = readFixture("prototype_option_a_done_frame.sse")
        val blocks = canonicalBlocks(doneFrame, contentCount = 120).toMutableList()
        blocks[1] =
            "event: content_event\ndata: " +
                "{\"run_id\":\"run-1\",\"event_type\":\"content_event\",\"details\":{" +
                "seq\":999,\"seq\":1},\"campaign_id\":\"forged-campaign\",\"" +
                "campaign_id\":\"campaign-1\"}"

        try {
            PrototypeRunStreamAdapter(FakeRawPostTransport(rawStreamOf(blocks))).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            org.junit.Assert.fail("sequence duplicate was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "prototype SSE content events must have exact seq 1 through 120",
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
            blocks[1] = "event: content_event\ndata: ${contentPayloadWithIdentity(data)}"
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
                contentPayloadWithIdentity(
                    "{\"event_type\":\"content_event\",\"details\":{\"seq\":999}," +
                        "\"details\":{\"seq\":1}}",
                )
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
                contentPayloadWithIdentity(
                    "{\"event_type\":\"content_event\",\"details\":{\"seq\":999," +
                        "\"\\u0073eq\":1}}",
                )
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
                contentPayloadWithIdentity(
                    "{\"details\":{\"seq\":999},\"det\\u0061ils\":{\"seq\":1}," +
                        "\"event_type\":\"content_event\"}",
                )
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
            blocks[1] = "event: content_event\ndata: ${contentPayloadWithIdentity(data)}"
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

    private fun runStartedBlock(payload: String): String =
        "event: run_started\ndata: $payload"

    private data class OfficialProducerCase(
        val label: String,
        val campaignId: String,
        val runId: String,
        val conditionId: String,
        val scheduleHash: String,
        val nominalIntervalMs: Int,
        val serverMonotonicNs: Long,
        val t0MonotonicNs: Long,
    )

    private val officialProducerCases = listOf(
        OfficialProducerCase(
            label = "baseline",
            campaignId = "campaign-official-baseline",
            runId = "run-official-baseline",
            conditionId = "baseline_v0.1",
            scheduleHash = "46eced73d2fbc886040a3357f84551d424a95e15d6e9e69c16958f6e52e33d7e",
            nominalIntervalMs = 50,
            serverMonotonicNs = 4_200_000_000L,
            t0MonotonicNs = 4_200_000_000L,
        ),
        OfficialProducerCase(
            label = "slow",
            campaignId = "campaign-official-slow",
            runId = "run-official-slow",
            conditionId = "slow_v0.1",
            scheduleHash = "b51b27fe8332b3fc8a97472a44312b3001ccd54364a61ed8799816c299d27062",
            nominalIntervalMs = 125,
            serverMonotonicNs = 5_300_000_000L,
            t0MonotonicNs = 5_300_000_000L,
        ),
        OfficialProducerCase(
            label = "unstable",
            campaignId = "campaign-official-unstable",
            runId = "run-official-unstable",
            conditionId = "unstable_v0.1",
            scheduleHash = "d11dce2a877d7c3772a4552f2d922d5f96730c9a01bb829f0203c65b110a8c58",
            nominalIntervalMs = 65,
            serverMonotonicNs = 6_400_000_000L,
            t0MonotonicNs = 6_400_000_000L,
        ),
    )

    private fun producerRunStartedPayload(
        eventTypeMembers: List<String> = listOf("\"event_type\":\"run_started\""),
        reordered: Boolean = false,
        producer: OfficialProducerCase = officialProducerCases.first(),
    ): String {
        val eventEnvelope = listOf(
            "\"schema_version\":\"aneb-prototype-evidence-0.1\"",
            "\"protocol_version\":\"prototype-stream-0.1\"",
            "\"campaign_id\":\"${producer.campaignId}\"",
            "\"run_id\":\"${producer.runId}\"",
            "\"condition_id\":\"${producer.conditionId}\"",
        )
        val serverClock = listOf(
            "\"server_monotonic_ns\":${producer.serverMonotonicNs}",
            "\"clock_source\":\"server.monotonic\"",
            "\"clock_unit\":\"ns\"",
            "\"clock_epoch\":\"process\"",
            "\"source\":\"server\"",
        )
        val details =
            "\"details\":{" +
                "\"profile_id\":\"streaming_text_reference_v0.1\"," +
                "\"profile_version\":\"0.1\"," +
                "\"profile_manifest_sha256\":\"44393ddd5ed11a5091038a85d08ab65ee91a8566997e837d2c40fd3add57d5dc\"," +
                "\"schedule_hash\":\"${producer.scheduleHash}\"," +
                "\"nominal_interval_ms\":${producer.nominalIntervalMs}," +
                "\"t0_monotonic_ns\":${producer.t0MonotonicNs}}"
        val members = if (reordered) {
            eventEnvelope + serverClock + listOf(details) + eventTypeMembers
        } else {
            eventEnvelope + eventTypeMembers + serverClock + listOf(details)
        }
        return "{${members.joinToString(",")}}"
    }

    private suspend fun assertTerminalCompletionRejected(
        label: String,
        blocks: List<String>,
        arrivals: List<Long>? = null,
        expectedMessage: String = TERMINAL_COMPLETION_ERROR,
    ) {
        val rawStream = arrivals?.let { rawStreamWithArrivals(blocks, it) } ?: rawStreamOf(blocks)
        try {
            PrototypeRunStreamAdapter(FakeRawPostTransport(rawStream)).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"validated_request\":true}",
            )
            org.junit.Assert.fail("$label was accepted")
        } catch (error: IllegalArgumentException) {
            assertEquals(expectedMessage, error.message)
        }
    }

    private suspend fun assertTerminalCompletionAccepted(
        label: String,
        blocks: List<String>,
    ) {
        try {
            val result = PrototypeRunStreamAdapter(
                FakeRawPostTransport(rawStreamOf(blocks)),
            ).run(
                endpoint = "http://127.0.0.1:18088/api/v1/prototype/runs",
                requestBody = "{\"validated_request\":true}",
            )
            assertNotNull(result.decodedTerminal)
        } catch (error: Throwable) {
            org.junit.Assert.fail("$label was rejected: ${error.message}")
        }
    }

    private fun completionBlocks(
        doneFrame: String,
        mutate: (String) -> String,
    ): MutableList<String> = canonicalBlocks(doneFrame, contentCount = 120).toMutableList().also { blocks ->
        val original = blocks[blocks.lastIndex]
        val mutated = mutate(original)
        require(mutated != original) { "terminal completion fixture mutation did not apply" }
        blocks[blocks.lastIndex] = mutated
    }

    private fun replaceTerminalDetailValue(
        frame: String,
        field: String,
        canonicalValue: String,
        replacementValue: String,
    ): String {
        val canonicalMember = "\"$field\":$canonicalValue"
        val replacementMember = "\"$field\":$replacementValue"
        require(frame.contains(canonicalMember)) { "terminal field missing: $field" }
        return frame.replace(canonicalMember, replacementMember)
    }

    private fun removeTerminalDetailValue(
        frame: String,
        field: String,
        canonicalValue: String,
    ): String {
        val canonicalMember = "\"$field\":$canonicalValue"
        val withComma = frame.replace("$canonicalMember,", "")
        if (withComma != frame) return withComma
        val withLeadingComma = frame.replace(",$canonicalMember", "")
        if (withLeadingComma != frame) return withLeadingComma
        val withoutComma = frame.replace(canonicalMember, "")
        require(withoutComma != frame) { "terminal field missing: $field" }
        return withoutComma
    }

    private fun duplicateTerminalDetailValues(
        frame: String,
        field: String,
        firstValue: String,
        secondValue: String,
    ): String {
        val canonicalMember = "\"$field\":120"
        val statusMember = "\"$field\":\"complete\""
        val target = if (field == "terminal_status") statusMember else canonicalMember
        val replacement = "\"$field\":$firstValue,\"$field\":$secondValue"
        require(frame.contains(target)) { "terminal field missing: $field" }
        return frame.replace(target, replacement)
    }

    private fun duplicateTerminalDetailKeys(
        frame: String,
        field: String,
        escapedField: String,
        canonicalValue: String,
        escapedFirst: Boolean,
    ): String {
        val canonicalMember = "\"$field\":$canonicalValue"
        val escapedMember = "\"$escapedField\":$canonicalValue"
        val replacement = if (escapedFirst) {
            "$escapedMember,$canonicalMember"
        } else {
            "$canonicalMember,$escapedMember"
        }
        require(frame.contains(canonicalMember)) { "terminal field missing: $field" }
        return frame.replace(canonicalMember, replacement)
    }

    private fun replaceTerminalDetailKey(
        frame: String,
        field: String,
        escapedField: String,
        canonicalValue: String,
    ): String {
        val canonicalMember = "\"$field\":$canonicalValue"
        val escapedMember = "\"$escapedField\":$canonicalValue"
        require(frame.contains(canonicalMember)) { "terminal field missing: $field" }
        return frame.replace(canonicalMember, escapedMember)
    }

    private fun canonicalBlocks(doneFrame: String, contentCount: Int): List<String> = buildList {
        add(
            "event: run_started\ndata: " +
                "{\"event_type\":\"run_started\",\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
        )
        repeat(contentCount) { index ->
            add(serverContentBlock(index + 1))
        }
        add(doneFrameForRun(doneFrame).removeSuffix("\n\n"))
    }

    private fun canonicalBlocksForIdentity(
        doneFrame: String,
        contentCount: Int,
        campaignId: String,
        runId: String,
    ): List<String> = canonicalBlocks(doneFrame, contentCount).map { block ->
        block
            .replace("\"campaign-1\"", "\"$campaignId\"")
            .replace("\"run-1\"", "\"$runId\"")
    }

    private fun doneFrameForRun(doneFrame: String): String = doneFrame
        .replace("\"campaign-fixture-01\"", "\"campaign-1\"")
        .replace("\"run-fixture-01\"", "\"run-1\"")

    private fun replaceSecondOccurrence(
        input: String,
        target: String,
        replacement: String,
    ): String {
        val first = input.indexOf(target)
        val second = input.indexOf(target, first + target.length)
        require(first >= 0 && second >= 0)
        return input.substring(0, second) + replacement + input.substring(second + target.length)
    }

    private fun removeFirstOccurrence(input: String, target: String): String {
        val first = input.indexOf(target)
        require(first >= 0)
        return input.removeRange(first, first + target.length)
    }

    private fun removeSecondOccurrence(input: String, target: String): String {
        val first = input.indexOf(target)
        val second = input.indexOf(target, first + target.length)
        require(first >= 0 && second >= 0)
        return input.removeRange(second, second + target.length)
    }

    private fun duplicateRootDetails(
        doneFrame: String,
        escapedCanonicalLast: Boolean,
        firstDetails: String = "{\"campaign_id\":\"forged-campaign\",\"run_id\":\"forged-run\"}",
    ): String {
        val frame = doneFrame.removeSuffix("\n\n")
        val marker = ",\"details\":"
        val prefix = frame.substringBefore(marker)
        val canonicalDetails = frame.substringAfter(marker).removeSuffix("}")
        val canonicalMarker = if (escapedCanonicalLast) {
            ",\"\\u0064etails\":"
        } else {
            marker
        }
        return prefix + marker + firstDetails + canonicalMarker + canonicalDetails + "}"
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

    private fun contentPayloadWithIdentity(data: String): String =
        if (data.trimStart().startsWith("{")) {
            data.replaceFirst(
                "{",
                "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\",",
            )
        } else {
            data
        }

    private fun rawStreamOf(
        blocks: List<String>,
        truncatedTail: Boolean = false,
    ): RawSseStream {
        val arrivals = blocks.indices.map { (it + 1) * 1_000L }
        return rawStreamWithArrivals(blocks, arrivals, truncatedTail)
    }

    private fun rawStreamWithArrivals(
        blocks: List<String>,
        arrivals: List<Long>,
        truncatedTail: Boolean = false,
    ): RawSseStream {
        require(arrivals.size == blocks.size)
        val streamText = blocks.joinToString(separator = "\n\n", postfix = "\n\n")
        return RawSseStream(
            events = blocks.mapIndexed { index, block ->
                RawSseEvent(
                    bytes = block.toByteArray(Charsets.UTF_8),
                    arrivalNanos = arrivals[index],
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
