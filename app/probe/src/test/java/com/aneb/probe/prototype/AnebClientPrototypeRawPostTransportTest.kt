package com.aneb.probe.prototype

import com.aneb.probe.net.AnebClient
import com.aneb.probe.net.MonotonicNanosClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Natural RED for the AnebClient-backed Prototype raw POST/SSE bridge.
 *
 * Ordinary raw transport tests treat request JSON as an opaque caller-provided
 * carrier and do not construct or canonicalize it. The capability-checked Quick
 * integration exercises the Runner-built request plus Runner metrics, summaries,
 * and RPI. Persistence and UI remain NONCLAIMS.
 */
class AnebClientPrototypeRawPostTransportTest {
    @Test
    fun compatibleCapabilityIdentityDriftAfterNodePreflightStopsBeforeRunPost() = runBlocking {
        val initialBody = canonicalCapabilityResponse()
            .replaceExactlyOnce(
                "\"server_version\":\"prototype-server-0.1\"",
                "\"server_version\":\"prototype-server-a\"",
            )
        val driftedBody = canonicalCapabilityResponse()
            .replaceExactlyOnce(
                "\"server_version\":\"prototype-server-0.1\"",
                "\"server_version\":\"prototype-server-b\"",
            )
            .replaceExactlyOnce(
                "\"server_binary_sha256\":\"${"0".repeat(64)}\"",
                "\"server_binary_sha256\":\"${"1".repeat(64)}\"",
            )
        CapabilityAwareSseServer(
            capabilityBody = initialBody.toByteArray(UTF_8),
            capabilityBodies = listOf(
                initialBody.toByteArray(UTF_8),
                driftedBody.toByteArray(UTF_8),
            ),
            runBody = canonicalRunResponseBody(),
            expectedRequests = 2,
        ).use { server ->
            val transport = AnebClientPrototypeRawPostTransport(
                AnebClient.createForTest(null, StrictClock()),
            )
            val runUrl = server.url("/api/v1/prototype/runs")
            val ticket = transport.check(runUrl)
            assertEquals("prototype-server-a", ticket.identity.serverVersion)
            assertEquals(initialBody, ticket.rawCapabilityBody)

            val thrown = runCatching {
                PrototypeRunStreamAdapter(transport.forTicket(ticket), StrictClock()).run(
                    endpoint = runUrl,
                    requestBody =
                        "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"," +
                            "\"condition_id\":\"baseline_v0.1\"}",
                )
            }.exceptionOrNull()

            assertEquals("prototype capability changed since node preflight", thrown?.message)
            val requests = server.awaitRequests()
            assertEquals(listOf("GET", "GET"), requests.map(CapturedRequest::method))
        }
    }

    @Test
    fun ticketIsRecheckedBeforeEveryQuickRunAndIdentityDriftAbortsBeforeThatPost() = runBlocking {
        val initialBody = canonicalCapabilityResponse()
        val driftedBody = canonicalCapabilityResponse()
            .replaceExactlyOnce(
                "\"server_version\":\"prototype-server-0.1\"",
                "\"server_version\":\"prototype-server-drifted\"",
            )
            .replaceExactlyOnce(
                "\"server_binary_sha256\":\"${"0".repeat(64)}\"",
                "\"server_binary_sha256\":\"${"2".repeat(64)}\"",
            )

        for (driftRunIndex in 1..3) {
            val capabilityBodies = buildList {
                add(initialBody.toByteArray(UTF_8))
                repeat(3) { runOffset ->
                    add(
                        if (runOffset + 1 == driftRunIndex) {
                            driftedBody.toByteArray(UTF_8)
                        } else {
                            initialBody.toByteArray(UTF_8)
                        },
                    )
                }
            }
            CapabilityAwareSseServer(
                capabilityBody = initialBody.toByteArray(UTF_8),
                capabilityBodies = capabilityBodies,
                runBody = ByteArray(0),
                runBodyForRequest = ::producerShapedRunResponseBody,
                expectedRequests = driftRunIndex * 2,
            ).use { server ->
                val clock = StrictClock()
                val transport = AnebClientPrototypeRawPostTransport(
                    AnebClient.createForTest(null, clock),
                )
                val runUrl = server.url("/api/v1/prototype/runs")
                val ticket = transport.check(runUrl)
                val runner = PrototypeQuickCampaignRunner(
                    streamAdapter = PrototypeRunStreamAdapter(transport.forTicket(ticket), clock),
                    runIdFactory = { index -> "run-ticket-$driftRunIndex-$index" },
                    waitBetweenRuns = { _ -> },
                )

                val thrown = runCatching {
                    runner.run(runUrl, "campaign-ticket-$driftRunIndex")
                }.exceptionOrNull()

                assertEquals(
                    "drift at run $driftRunIndex",
                    "prototype capability changed since node preflight",
                    thrown?.message,
                )
                val expectedMethods = buildList {
                    add("GET")
                    repeat(driftRunIndex - 1) {
                        add("GET")
                        add("POST")
                    }
                    add("GET")
                }
                val requests = server.awaitRequests()
                assertEquals("drift at run $driftRunIndex", expectedMethods, requests.map(CapturedRequest::method))
                assertEquals(
                    "drift at run $driftRunIndex POST count",
                    driftRunIndex - 1,
                    requests.count { it.method == "POST" },
                )
            }
        }
    }

    @Test
    fun ticketRuntimeComparisonUsesTypedIdentityInsteadOfRawJsonText() = runBlocking {
        val initialBody = canonicalCapabilityResponse()
        val equivalentBody = sameIdentityReorderedCapabilityResponse()
        CapabilityAwareSseServer(
            capabilityBody = initialBody.toByteArray(UTF_8),
            capabilityBodies = listOf(
                initialBody.toByteArray(UTF_8),
                equivalentBody.toByteArray(UTF_8),
            ),
            runBody = canonicalRunResponseBody(),
            expectedRequests = 3,
        ).use { server ->
            val clock = StrictClock()
            val transport = AnebClientPrototypeRawPostTransport(
                AnebClient.createForTest(null, clock),
            )
            val runUrl = server.url("/api/v1/prototype/runs")
            val ticket = transport.check(runUrl)

            val result = PrototypeRunStreamAdapter(
                transport.forTicket(ticket),
                clock,
            ).run(
                endpoint = runUrl,
                requestBody =
                    "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"," +
                        "\"condition_id\":\"baseline_v0.1\"}",
            )

            assertFalse(result.decodedTerminal.envelope.isEmpty())
            assertEquals(initialBody, ticket.rawCapabilityBody)
            assertTrue(initialBody != equivalentBody)
            assertEquals(
                listOf("GET", "GET", "POST"),
                server.awaitRequests().map(CapturedRequest::method),
            )
        }
    }

    @Test
    fun ticketForOneCanonicalNodeCannotStartAnotherNodeUrl() = runBlocking {
        CapabilityAwareSseServer(
            capabilityBody = canonicalCapabilityResponse().toByteArray(UTF_8),
            runBody = ByteArray(0),
            expectedRequests = 1,
        ).use { server ->
            val transport = AnebClientPrototypeRawPostTransport(
                AnebClient.createForTest(null, StrictClock()),
            )
            val ticket = transport.check(server.url("/api/v1/prototype/runs"))

            val thrown = runCatching {
                transport.forTicket(ticket).post(
                    url = "http://127.0.0.1:1/api/v1/prototype/runs",
                    requestBody = "{}",
                )
            }.exceptionOrNull()

            assertTrue(thrown is IllegalArgumentException)
            assertEquals("prototype node ticket does not match run URL", thrown?.message)
            assertEquals(listOf("GET"), server.awaitRequests().map(CapturedRequest::method))
        }
    }

    @Test
    fun compatibleCapabilityCheckReturnsUiDetailsWithoutPostingRun() = runBlocking {
        val capabilityBody = canonicalCapabilityResponse()
            .replaceExactlyOnce(
                "\"server_version\":\"prototype-server-0.1\"",
                "\"server_version\":\"prototype-server-ticket-build-7\"",
            )
            .replaceExactlyOnce(
                "\"server_binary_sha256\":\"${"0".repeat(64)}\"",
                "\"server_binary_sha256\":\"${"a".repeat(64)}\"",
            )
        CapabilityAwareSseServer(
            capabilityBody = capabilityBody.toByteArray(UTF_8),
            runBody = ByteArray(0),
            expectedRequests = 1,
        ).use { server ->
            val transport = AnebClientPrototypeRawPostTransport(
                AnebClient.createForTest(null, StrictClock()),
            )

            val ticket = transport.check(
                server.url("/api/v1/prototype/runs"),
            )
            val capability = ticket.capability

            assertEquals(server.url("").removeSuffix("/"), ticket.nodeBaseUrl)
            assertEquals(server.url("/api/v1/prototype/runs"), ticket.runUrl)
            assertEquals(server.url("/api/v1/prototype/capabilities"), ticket.capabilityUrl)
            assertEquals(capabilityBody, ticket.rawCapabilityBody)
            assertEquals("prototype-server-ticket-build-7", capability.serverVersion)
            assertEquals("aneb-prototype-capabilities-0.1", ticket.identity.schemaVersion)
            assertEquals("prototype-0.1", ticket.identity.productVersion)
            assertEquals("prototype-stream-0.1", ticket.identity.protocolVersion)
            assertEquals("a".repeat(64), ticket.identity.serverBinarySha256)
            assertEquals(
                "application_end_to_end_to_probe_node",
                ticket.identity.claimScope,
            )
            assertEquals("synthetic_application_impairment", ticket.identity.evidenceMode)
            assertEquals("application", ticket.identity.impairmentLayer)
            assertEquals("0.1", capability.workloadVersion)
            assertEquals("streaming_text_reference_v0.1", ticket.identity.workload.id)
            assertEquals(120, ticket.identity.workload.contentEventCount)
            assertEquals(
                "44393ddd5ed11a5091038a85d08ab65ee91a8566997e837d2c40fd3add57d5dc",
                capability.profileManifestSha256,
            )
            assertEquals(
                listOf("baseline_v0.1", "slow_v0.1", "unstable_v0.1"),
                capability.conditions,
            )
            assertEquals("aneb-prototype-evidence-0.1", capability.evidenceSchemaVersion)
            assertEquals("rpi-0.1", capability.scorePolicyId)
            assertEquals(
                listOf(50, 125, 65),
                ticket.identity.conditions.map(PrototypeCapabilityConditionIdentity::nominalIntervalMs),
            )
            assertEquals(
                listOf("0.1", "0.1", "0.1"),
                ticket.identity.conditions.map(PrototypeCapabilityConditionIdentity::version),
            )
            assertEquals(
                listOf(
                    "46eced73d2fbc886040a3357f84551d424a95e15d6e9e69c16958f6e52e33d7e",
                    "b51b27fe8332b3fc8a97472a44312b3001ccd54364a61ed8799816c299d27062",
                    "d11dce2a877d7c3772a4552f2d922d5f96730c9a01bb829f0203c65b110a8c58",
                ),
                ticket.identity.conditions.map(PrototypeCapabilityConditionIdentity::scheduleSha256),
            )
            assertEquals(
                "prototype-terminal-receipt-0.1",
                ticket.identity.terminalReceiptVersion,
            )
            val requests = server.awaitRequests()
            assertEquals(listOf("GET"), requests.map(CapturedRequest::method))
            assertEquals(
                listOf("/api/v1/prototype/capabilities"),
                requests.map(CapturedRequest::path),
            )
        }
    }

    @Test
    fun quickCampaignRunnerUsesCapabilityCheckedAnebClientStreamAdapterInOfficialOrder() = runBlocking {
        val campaignId = "campaign-g2a-integration"
        CapabilityAwareSseServer(
            capabilityBody = canonicalCapabilityResponse().toByteArray(UTF_8),
            runBody = ByteArray(0),
            expectedRequests = 6,
            runBodyForRequest = ::producerShapedRunResponseBody,
        ).use { server ->
            val clock = StrictClock()
            val transport = AnebClientPrototypeRawPostTransport(
                AnebClient.createForTest(null, clock),
            )
            val streamAdapter = PrototypeRunStreamAdapter(
                transport.boundForTest(server.url("/api/v1/prototype/runs")),
                clock,
            )
            val runner = PrototypeQuickCampaignRunner(
                streamAdapter = streamAdapter,
                runIdFactory = { index -> "run-g2a-0$index" },
                waitBetweenRuns = { _ -> },
            )

            val result = runner.run(
                endpoint = server.url("/api/v1/prototype/runs"),
                campaignId = campaignId,
            )

            assertEquals(
                listOf("baseline_v0.1", "slow_v0.1", "unstable_v0.1"),
                result.runs.map { it.conditionId },
            )
            assertEquals(listOf(1, 2, 3), result.runs.map { it.runIndex })
            result.runs.forEach { run ->
                val stream = run.streamResult
                assertTrue(stream.t0MonotonicNanos >= 0L)
                assertEquals(122, stream.rawEvents.size)
                assertEquals("done", stream.decodedTerminal.eventName)
                assertTrue(stream.rawEvents.first().arrivalNanos > stream.t0MonotonicNanos)
                assertTrue(
                    stream.rawEvents.zipWithNext().all { (previous, current) ->
                        current.arrivalNanos >= previous.arrivalNanos
                    },
                )
                assertEquals((1..120).toList(), stream.validatedContentEvents.map { it.sequence })
                assertTrue(
                    stream.validatedContentEvents.all { event ->
                        event.clientMonotonicNanos > event.rawEvent.arrivalNanos
                    },
                )
                assertTrue(
                    stream.validatedContentEvents.zipWithNext().all { (previous, current) ->
                        current.clientMonotonicNanos >= previous.clientMonotonicNanos
                    },
                )
                assertTrue(
                    stream.terminalClientMonotonicNanos >=
                        stream.validatedContentEvents.last().clientMonotonicNanos,
                )
                assertTrue(
                    stream.terminalClientMonotonicNanos > stream.rawEvents.last().arrivalNanos,
                )
            }

            val requests = server.awaitRequests()
            assertEquals(
                listOf("GET", "POST", "GET", "POST", "GET", "POST"),
                requests.map(CapturedRequest::method),
            )
            assertEquals(
                listOf(
                    "/api/v1/prototype/capabilities",
                    "/api/v1/prototype/runs",
                    "/api/v1/prototype/capabilities",
                    "/api/v1/prototype/runs",
                    "/api/v1/prototype/capabilities",
                    "/api/v1/prototype/runs",
                ),
                requests.map(CapturedRequest::path),
            )
            val runRequests = requests.filter { it.method == "POST" }.map { request ->
                Json.parseToJsonElement(request.body.toString(UTF_8)).jsonObject
            }
            assertEquals(3, runRequests.size)
            assertEquals(listOf(1, 2, 3), runRequests.map { it.getValue("run_index").jsonPrimitive.int })
            assertEquals(
                listOf("baseline_v0.1", "slow_v0.1", "unstable_v0.1"),
                runRequests.map { it.getValue("condition_id").jsonPrimitive.content },
            )
            assertTrue(runRequests.all { it.size == 12 })
            assertTrue(runRequests.all { it.getValue("campaign_id").jsonPrimitive.content == campaignId })
            assertEquals(
                listOf("run-g2a-01", "run-g2a-02", "run-g2a-03"),
                runRequests.map { it.getValue("run_id").jsonPrimitive.content },
            )

            assertEquals(3, result.runs.size)
            result.runs.forEach { run ->
                assertEquals(PrototypeQuickCampaignRunner.RunStatus.COMPLETE, run.status)
                assertTrue(run.taskSuccess)
                assertTrue(run.scoreEligible)
                assertEquals(true, run.terminalReceiptValid)
                val metrics = requireNotNull(run.metrics)
                assertTrue(requireNotNull(metrics.ttftMs) > 0.0)
                assertTrue(requireNotNull(metrics.completionMs) > 0.0)
                assertTrue(requireNotNull(metrics.streamSpanMs) > 0.0)
                assertTrue(requireNotNull(metrics.streamEventRateEps) > 0.0)
                assertTrue(requireNotNull(metrics.stallThresholdMs) > 0.0)
                assertTrue(requireNotNull(metrics.stallCount) >= 0)
                assertTrue(requireNotNull(metrics.stallDurationMs) >= 0.0)
                assertTrue(requireNotNull(metrics.stallFraction) in 0.0..1.0)
            }

            val summary = result.summary
            assertEquals(campaignId, summary.campaignId)
            assertEquals("quick", summary.campaignMode)
            assertEquals(PrototypeQuickCampaignRunner.CampaignStatus.COMPLETE, summary.status)
            assertEquals(3, summary.plannedRuns)
            assertEquals(3, summary.attemptedRuns)
            assertEquals(3, summary.successfulRuns)
            assertEquals(0, summary.failedRuns)
            assertEquals(0, summary.notStartedRuns)
            assertEquals(1.0, summary.successRate, 0.0)

            val conditionSummaries = summary.conditionSummaries
            assertEquals(
                listOf("baseline_v0.1", "slow_v0.1", "unstable_v0.1"),
                conditionSummaries.map { it.conditionId },
            )
            result.runs.zip(conditionSummaries).forEach { (run, conditionSummary) ->
                val metrics = requireNotNull(run.metrics)
                assertEquals(1, conditionSummary.plannedRuns)
                assertEquals(1, conditionSummary.attemptedRuns)
                assertEquals(1, conditionSummary.successfulRuns)
                assertEquals(0, conditionSummary.failedRuns)
                assertEquals(0, conditionSummary.notStartedRuns)
                assertEquals(1.0, conditionSummary.successRate, 0.0)
                assertEquals(PrototypeQuickCampaignRunner.Confidence.LOW, conditionSummary.confidence)
                assertEquals(metrics.ttftMs, conditionSummary.medianTtftMs)
                assertEquals(metrics.ttftMs, conditionSummary.minTtftMs)
                assertEquals(metrics.ttftMs, conditionSummary.maxTtftMs)
                assertEquals(metrics.completionMs, conditionSummary.medianCompletionMs)
                assertEquals(metrics.completionMs, conditionSummary.minCompletionMs)
                assertEquals(metrics.completionMs, conditionSummary.maxCompletionMs)
                assertEquals(metrics.streamEventRateEps, conditionSummary.medianStreamEventRateEps)
                assertEquals(metrics.stallCount?.toDouble(), conditionSummary.medianStallCount)
                assertEquals(metrics.stallDurationMs, conditionSummary.medianStallDurationMs)
                assertEquals(metrics.stallFraction, conditionSummary.medianStallFraction)
                val rpi = requireNotNull(conditionSummary.rpi)
                assertTrue(rpi in 0..100)
                assertEquals("rpi-0.1", conditionSummary.rpiPolicyId)
                assertNull(conditionSummary.primaryNullReason)
                assertNull(conditionSummary.allNullReasons)
            }
        }
    }

    @Test
    fun loopbackPostPreservesOpaqueRequestHeadersAndFramesThroughAdapter() = runBlocking {
        val requestBody =
            "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\",\"condition_id\":\"baseline_v0.1\",\"opaque\":true,\"order\":[3,1,2],\"unicode\":\"端到端\"}"
        val doneFrame = doneFrameForRun(readSharedDoneFixture()).removeSuffix("\n\n")
        val terminalConditionMember = "\"condition_id\":\"baseline_v0.1\""
        assertEquals(2, doneFrame.split(terminalConditionMember).size - 1)
        val expectedFrames = buildList {
            add(
                "event: run_started\ndata: " +
                    "{\"event_type\":\"run_started\",\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\",\"condition_id\":\"baseline_v0.1\"}",
            )
            repeat(120) { seq ->
                add(serverContentBlock(seq + 1))
            }
            add(doneFrame)
        }
        val responseBody = expectedFrames.joinToString("\n\n", postfix = "\n\n").toByteArray(UTF_8)

        CapabilityAwareSseServer(
            capabilityBody = canonicalCapabilityResponse().toByteArray(UTF_8),
            runBody = responseBody,
            expectedRequests = 2,
        ).use { server ->
            val clock = StrictClock()
            val transport = AnebClientPrototypeRawPostTransport(
                AnebClient.createForTest(null, clock),
            )
            val result = PrototypeRunStreamAdapter(
                transport.boundForTest(server.url("/api/v1/prototype/runs")),
                clock,
            ).run(
                endpoint = server.url("/api/v1/prototype/runs"),
                requestBody = requestBody,
            )

            assertFalse(result.decodedTerminal.envelope.isEmpty())
            assertEquals("done", result.decodedTerminal.eventName)
            assertEquals(
                "terminal_event",
                result.decodedTerminal.envelope.getValue("event_type").jsonPrimitive.content,
            )
            assertEquals(expectedFrames, result.rawEvents.map { it.bytes.toString(UTF_8) })
            assertFalse(server.responseStreamWasTruncated)
            assertTrue(
                result.rawEvents.zipWithNext().all { (previous, current) ->
                    current.arrivalNanos >= previous.arrivalNanos
                },
            )

            val requests = server.awaitRequests()
            assertEquals(
                listOf(
                    "GET /api/v1/prototype/capabilities",
                    "POST /api/v1/prototype/runs",
                ),
                requests.map { "${it.method} ${it.path}" },
            )
            val request = requests.last()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/prototype/runs", request.path)
            assertTrue(request.headers.getValue("content-type").startsWith("application/json"))
            assertTrue(request.headers.getValue("accept").contains("text/event-stream"))
            assertArrayEquals(requestBody.toByteArray(UTF_8), request.body)
        }
    }

    @Test
    fun cancellationThroughBridgeClosesConnectionAndReleasesTimingRecord() = runBlocking {
        val clock = StrictClock()
        val client = AnebClient.createForTest(null, clock)
        val transport = AnebClientPrototypeRawPostTransport(client)
        val thrown = AtomicReference<Throwable?>()
        val cancellation = CancellationException("cancelled by bridge test")

        BlockingSseServer(canonicalCapabilityResponse().toByteArray(UTF_8)).use { server ->
            val job = launch(Dispatchers.IO) {
                try {
                    PrototypeRunStreamAdapter(
                        transport.boundForTest(server.url("/api/v1/prototype/runs")),
                        clock,
                    ).run(
                        endpoint = server.url("/api/v1/prototype/runs"),
                        requestBody = "{\"opaque\":\"cancel\"}",
                    )
                    thrown.set(AssertionError("blocked Prototype run unexpectedly returned"))
                } catch (error: Throwable) {
                    thrown.set(error)
                }
            }

            server.awaitHeadersSent()
            job.cancel(cancellation)
            job.join()

            val propagated = thrown.get()
            assertTrue("cancellation was swallowed: $propagated", propagated is CancellationException)
            assertEquals(cancellation.message, propagated?.message)
            assertTrue("loopback connection did not close", server.awaitConnectionClosed())
            assertEquals(0, client.activeTimingRecordCountForTest())
        }
    }

    @Test
    fun capabilityPreflightCancellationClosesGetAndDoesNotPost() = runBlocking {
        val client = AnebClient.createForTest(null, StrictClock())
        val transport = AnebClientPrototypeRawPostTransport(client)
        val thrown = AtomicReference<Throwable?>()
        val cancellation = CancellationException("cancelled during capability preflight")

        BlockingCapabilityServer().use { server ->
            val job = launch(Dispatchers.IO) {
                try {
                    PrototypeRunStreamAdapter(
                        transport.boundForTest(server.url("/api/v1/prototype/runs")),
                    ).run(
                        endpoint = server.url("/api/v1/prototype/runs"),
                        requestBody =
                            "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"," +
                                "\"condition_id\":\"baseline_v0.1\"}",
                    )
                } catch (error: Throwable) {
                    thrown.set(error)
                }
            }

            server.awaitPartialBodySent()
            job.cancel(cancellation)
            job.join()

            val propagated = thrown.get()
            assertTrue("capability cancellation was swallowed: $propagated", propagated is CancellationException)
            assertEquals(cancellation.message, propagated?.message)
            assertTrue("capability GET connection did not close", server.awaitConnectionClosed())
            val requests = server.requests()
            assertEquals(
                listOf("GET /api/v1/prototype/capabilities"),
                requests.map { "${it.method} ${it.path}" },
            )
            assertEquals(
                0,
                requests.count { it.method == "POST" && it.path.startsWith("/api/v1/prototype/runs") },
            )
            assertEquals(0, client.activeTimingRecordCountForTest())
        }
    }

    @Test
    fun incompatibleCapabilityStopsBeforePrototypeRunPost() = runBlocking {
        val requestBody =
            "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\",\"condition_id\":\"baseline_v0.1\"}"
        val capabilityBody = incompatibleCapabilityResponse().toByteArray(UTF_8)
        val runBody = canonicalRunResponseBody()

        CapabilityAwareSseServer(capabilityBody, runBody).use { server ->
            val client = AnebClient.createForTest(null, StrictClock())
            val transport = AnebClientPrototypeRawPostTransport(client)
            var thrown: Throwable? = null
            try {
                PrototypeRunStreamAdapter(
                    transport.boundForTest(server.url("/api/v1/prototype/runs")),
                ).run(
                    endpoint = server.url("/api/v1/prototype/runs"),
                    requestBody = requestBody,
                )
            } catch (error: Throwable) {
                thrown = error
            }

            val requests = server.awaitRequests()
            assertTrue(
                "incompatible capability was not rejected: thrown=$thrown requests=$requests",
                thrown is IllegalArgumentException,
            )
            assertEquals("prototype capability response is incompatible", thrown?.message)
            assertEquals(
                listOf("GET /api/v1/prototype/capabilities"),
                requests.map { "${it.method} ${it.path}" },
            )
            assertEquals(
                0,
                requests.count { it.method == "POST" && it.path.startsWith("/api/v1/prototype/runs") },
            )
        }
    }

    @Test
    fun capabilityNumericEquivalentLexemesRemainCompatible() = runBlocking {
        val requestBody =
            "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\",\"condition_id\":\"baseline_v0.1\",\"opaque\":true}"
        val runBody = canonicalRunResponseBody()
        val capabilityBody = equivalentNumericCapabilityResponse().toByteArray(UTF_8)

        CapabilityAwareSseServer(
            capabilityBody = capabilityBody,
            runBody = runBody,
            expectedRequests = 2,
        ).use { server ->
            val clock = StrictClock()
            val transport = AnebClientPrototypeRawPostTransport(
                AnebClient.createForTest(null, clock),
            )
            val result = PrototypeRunStreamAdapter(
                transport.boundForTest(server.url("/api/v1/prototype/runs")),
                clock,
            ).run(
                endpoint = server.url("/api/v1/prototype/runs"),
                requestBody = requestBody,
            )

            assertFalse(result.decodedTerminal.envelope.isEmpty())
            val requests = server.awaitRequests()
            assertEquals(
                listOf(
                    "GET /api/v1/prototype/capabilities",
                    "POST /api/v1/prototype/runs",
                ),
                requests.map { "${it.method} ${it.path}" },
            )
            assertArrayEquals(requestBody.toByteArray(UTF_8), requests.last().body)
        }
    }

    @Test
    fun duplicateCapabilityClaimScopeIsRejectedBeforePrototypeRunPost() = runBlocking {
        val requestBody =
            "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\",\"condition_id\":\"baseline_v0.1\"}"
        val capabilityBody = duplicateClaimScopeCapabilityResponse().toByteArray(UTF_8)

        CapabilityAwareSseServer(
            capabilityBody = capabilityBody,
            runBody = canonicalRunResponseBody(),
            expectedRequests = 1,
        ).use { server ->
            val client = AnebClient.createForTest(null, StrictClock())
            val transport = AnebClientPrototypeRawPostTransport(client)
            var thrown: Throwable? = null
            try {
                PrototypeRunStreamAdapter(
                    transport.boundForTest(server.url("/api/v1/prototype/runs")),
                ).run(
                    endpoint = server.url("/api/v1/prototype/runs"),
                    requestBody = requestBody,
                )
            } catch (error: Throwable) {
                thrown = error
            }

            val requests = server.awaitRequests()
            assertTrue(
                "duplicate capability claim_scope was not rejected: thrown=$thrown requests=$requests",
                thrown is IllegalArgumentException,
            )
            assertEquals("prototype capability response is incompatible", thrown?.message)
            assertEquals(
                listOf("GET /api/v1/prototype/capabilities"),
                requests.map { "${it.method} ${it.path}" },
            )
            assertEquals(
                0,
                requests.count { it.method == "POST" && it.path.startsWith("/api/v1/prototype/runs") },
            )
        }
    }

    @Test
    fun duplicateCapabilityWorkloadContentEventCountIsRejectedBeforePrototypeRunPost() = runBlocking {
        val requestBody =
            "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\",\"condition_id\":\"baseline_v0.1\"}"
        val capabilityBody = duplicateWorkloadContentEventCountCapabilityResponse().toByteArray(UTF_8)

        CapabilityAwareSseServer(
            capabilityBody = capabilityBody,
            runBody = canonicalRunResponseBody(),
            expectedRequests = 1,
        ).use { server ->
            val client = AnebClient.createForTest(null, StrictClock())
            val transport = AnebClientPrototypeRawPostTransport(client)
            var thrown: Throwable? = null
            try {
                PrototypeRunStreamAdapter(
                    transport.boundForTest(server.url("/api/v1/prototype/runs")),
                ).run(
                    endpoint = server.url("/api/v1/prototype/runs"),
                    requestBody = requestBody,
                )
            } catch (error: Throwable) {
                thrown = error
            }

            val requests = server.awaitRequests()
            assertTrue(
                "duplicate capability workload content_event_count was not rejected: " +
                    "thrown=$thrown requests=$requests",
                thrown is IllegalArgumentException,
            )
            assertEquals("prototype capability response is incompatible", thrown?.message)
            assertEquals(
                listOf("GET /api/v1/prototype/capabilities"),
                requests.map { "${it.method} ${it.path}" },
            )
            assertEquals(
                0,
                requests.count { it.method == "POST" && it.path.startsWith("/api/v1/prototype/runs") },
            )
        }
    }

    @Test
    fun duplicateCapabilityConditionScheduleHashIsRejectedBeforePrototypeRunPost() = runBlocking {
        val requestBody =
            "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\",\"condition_id\":\"baseline_v0.1\"}"
        val capabilityBody = duplicateBaselineScheduleHashCapabilityResponse().toByteArray(UTF_8)

        CapabilityAwareSseServer(
            capabilityBody = capabilityBody,
            runBody = canonicalRunResponseBody(),
            expectedRequests = 1,
        ).use { server ->
            val client = AnebClient.createForTest(null, StrictClock())
            val transport = AnebClientPrototypeRawPostTransport(client)
            var thrown: Throwable? = null
            try {
                PrototypeRunStreamAdapter(
                    transport.boundForTest(server.url("/api/v1/prototype/runs")),
                ).run(
                    endpoint = server.url("/api/v1/prototype/runs"),
                    requestBody = requestBody,
                )
            } catch (error: Throwable) {
                thrown = error
            }

            val requests = server.awaitRequests()
            assertTrue(
                "duplicate capability condition schedule_sha256 was not rejected: " +
                    "thrown=$thrown requests=$requests",
                thrown is IllegalArgumentException,
            )
            assertEquals("prototype capability response is incompatible", thrown?.message)
            assertEquals(
                listOf("GET /api/v1/prototype/capabilities"),
                requests.map { "${it.method} ${it.path}" },
            )
            assertEquals(
                0,
                requests.count { it.method == "POST" && it.path.startsWith("/api/v1/prototype/runs") },
            )
        }
    }

    @Test
    fun semanticDuplicateCapabilityKeysAreRejectedBeforePrototypeRunPost() = runBlocking {
        val requestBody =
            "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\",\"condition_id\":\"baseline_v0.1\"}"
        val canonicalCapability = canonicalCapabilityResponse()
        val baselineSchedule = "46eced73d2fbc886040a3357f84551d424a95e15d6e9e69c16958f6e52e33d7e"
        val carriers = listOf(
            "root claim_scope" to canonicalCapability.replace(
                "\"claim_scope\":\"application_end_to_end_to_probe_node\"",
                "\"claim_sc\\u006fpe\":\"wrong_claim_scope\"," +
                    "\"claim_scope\":\"application_end_to_end_to_probe_node\"",
            ),
            "workload content_event_count" to canonicalCapability.replace(
                "\"content_event_count\":120",
                "\"content_event_c\\u006funt\":121,\"content_event_count\":120",
            ),
            "condition schedule_sha256" to canonicalCapability.replace(
                "\"schedule_sha256\":\"$baselineSchedule\"",
                "\"schedule_sha25\\u0036\":\"${"f".repeat(64)}\"," +
                    "\"schedule_sha256\":\"$baselineSchedule\"",
            ),
        )

        for ((name, capabilityBody) in carriers) {
            CapabilityAwareSseServer(
                capabilityBody = capabilityBody.toByteArray(UTF_8),
                runBody = canonicalRunResponseBody(),
                expectedRequests = 1,
            ).use { server ->
                val client = AnebClient.createForTest(null, StrictClock())
                val transport = AnebClientPrototypeRawPostTransport(client)
                var thrown: Throwable? = null
                try {
                    PrototypeRunStreamAdapter(
                        transport.boundForTest(server.url("/api/v1/prototype/runs")),
                    ).run(
                        endpoint = server.url("/api/v1/prototype/runs"),
                        requestBody = requestBody,
                    )
                } catch (error: Throwable) {
                    thrown = error
                }

                val requests = server.awaitRequests()
                assertTrue(
                    "$name semantic duplicate was not rejected: thrown=$thrown requests=$requests",
                    thrown is IllegalArgumentException,
                )
                assertEquals(
                    "$name stable error",
                    "prototype capability response is incompatible",
                    thrown?.message,
                )
                assertEquals(
                    "$name request sequence",
                    listOf("GET /api/v1/prototype/capabilities"),
                    requests.map { "${it.method} ${it.path}" },
                )
                assertEquals(
                    "$name prototype POST count",
                    0,
                    requests.count {
                        it.method == "POST" && it.path.startsWith("/api/v1/prototype/runs")
                    },
                )
            }
        }
    }

    @Test
    fun capabilityPreflightFailuresStopBeforePrototypeRunPost() = runBlocking {
        val requestBody =
            "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\",\"condition_id\":\"baseline_v0.1\"}"
        val stableError = "prototype capability response is incompatible"
        val unknownRootCapability = canonicalCapabilityResponse().replace(
            "\"terminal_receipt_version\":\"prototype-terminal-receipt-0.1\"",
            "\"terminal_receipt_version\":\"prototype-terminal-receipt-0.1\"," +
                "\"unknown_root\":true",
        )
        val cases = listOf(
            Triple("HTTP 500", 500, "{\"error\":\"capability unavailable\"}"),
            Triple("empty 200 body", 200, ""),
            Triple("malformed 200 JSON", 200, "{not-json"),
            Triple("unknown 200 root member", 200, unknownRootCapability),
        )

        for ((name, capabilityStatus, capabilityBody) in cases) {
            CapabilityAwareSseServer(
                capabilityBody = capabilityBody.toByteArray(UTF_8),
                runBody = canonicalRunResponseBody(),
                expectedRequests = 1,
                capabilityStatus = capabilityStatus,
            ).use { server ->
                val client = AnebClient.createForTest(null, StrictClock())
                val transport = AnebClientPrototypeRawPostTransport(client)
                var thrown: Throwable? = null
                try {
                    PrototypeRunStreamAdapter(
                        transport.boundForTest(server.url("/api/v1/prototype/runs")),
                    ).run(
                        endpoint = server.url("/api/v1/prototype/runs"),
                        requestBody = requestBody,
                    )
                } catch (error: Throwable) {
                    thrown = error
                }

                val requests = server.awaitRequests()
                assertTrue("$name did not fail closed", thrown != null)
                if (capabilityStatus == 500) {
                    assertTrue("$name lost HTTP status: $thrown", thrown?.message?.contains("500") == true)
                    assertTrue("$name was misreported as compatibility failure", thrown?.message != stableError)
                } else {
                    assertEquals("$name stable error", stableError, thrown?.message)
                }
                assertEquals(
                    "$name request sequence",
                    listOf("GET /api/v1/prototype/capabilities"),
                    requests.map { "${it.method} ${it.path}" },
                )
                assertEquals(
                    "$name prototype POST count",
                    0,
                    requests.count {
                        it.method == "POST" && it.path.startsWith("/api/v1/prototype/runs")
                    },
                )
            }
        }
    }

    @Test
    fun fixedCapabilityAuthorityDriftsAreRejectedBeforePrototypeRunPost() = runBlocking {
        val requestBody =
            "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\",\"condition_id\":\"baseline_v0.1\"}"
        val canonical = canonicalCapabilityResponse()
        val forgedHash = "f".repeat(64)
        val conditions = listOf(
            Triple(
                "baseline_v0.1",
                50,
                "46eced73d2fbc886040a3357f84551d424a95e15d6e9e69c16958f6e52e33d7e",
            ),
            Triple(
                "slow_v0.1",
                125,
                "b51b27fe8332b3fc8a97472a44312b3001ccd54364a61ed8799816c299d27062",
            ),
            Triple(
                "unstable_v0.1",
                65,
                "d11dce2a877d7c3772a4552f2d922d5f96730c9a01bb829f0203c65b110a8c58",
            ),
        )
        val cases = buildList<Pair<String, String>> {
            val rootDrifts = listOf(
                Triple("schema_version", "aneb-prototype-capabilities-0.1", "aneb-prototype-capabilities-0.2"),
                Triple("product_version", "prototype-0.1", "prototype-0.2"),
                Triple("protocol_version", "prototype-stream-0.1", "prototype-stream-0.2"),
                Triple("server_version", "prototype-server-0.1", ""),
                Triple("server_binary_sha256", "0".repeat(64), "not-a-sha256"),
                Triple("claim_scope", "application_end_to_end_to_probe_node", "wrong_claim_scope"),
                Triple("evidence_mode", "synthetic_application_impairment", "wrong_evidence_mode"),
                Triple("impairment_layer", "application", "transport"),
                Triple(
                    "profile_manifest_sha256",
                    "44393ddd5ed11a5091038a85d08ab65ee91a8566997e837d2c40fd3add57d5dc",
                    forgedHash,
                ),
                Triple("evidence_schema_version", "aneb-prototype-evidence-0.1", "aneb-prototype-evidence-0.2"),
                Triple("score_policy_id", "rpi-0.1", "rpi-0.2"),
                Triple(
                    "terminal_receipt_version",
                    "prototype-terminal-receipt-0.1",
                    "prototype-terminal-receipt-0.2",
                ),
            )
            for ((field, expected, drift) in rootDrifts) {
                add(
                    "root $field" to canonical.replaceExactlyOnce(
                        "\"$field\":\"$expected\"",
                        "\"$field\":\"$drift\"",
                    ),
                )
            }

            val workload =
                "\"workload\":{\"id\":\"streaming_text_reference_v0.1\"," +
                    "\"version\":\"0.1\",\"content_event_count\":120}"
            add(
                "workload id" to canonical.replaceExactlyOnce(
                    workload,
                    workload.replaceExactlyOnce(
                        "\"id\":\"streaming_text_reference_v0.1\"",
                        "\"id\":\"streaming_text_reference_v0.2\"",
                    ),
                ),
            )
            add(
                "workload version" to canonical.replaceExactlyOnce(
                    workload,
                    workload.replaceExactlyOnce("\"version\":\"0.1\"", "\"version\":\"0.2\""),
                ),
            )
            add(
                "workload content_event_count" to canonical.replaceExactlyOnce(
                    workload,
                    workload.replaceExactlyOnce("\"content_event_count\":120", "\"content_event_count\":121"),
                ),
            )

            val conditionObjects = conditions.map { (id, nominal, schedule) ->
                "{\"id\":\"$id\",\"version\":\"0.1\",\"nominal_interval_ms\":$nominal," +
                    "\"schedule_sha256\":\"$schedule\"}"
            }
            for ((index, condition) in conditions.withIndex()) {
                val (id, nominal, schedule) = condition
                val canonicalCondition = conditionObjects[index]
                val drifts = listOf(
                    "id" to canonicalCondition.replaceExactlyOnce(
                        "\"id\":\"$id\"",
                        "\"id\":\"$id-forged\"",
                    ),
                    "version" to canonicalCondition.replaceExactlyOnce(
                        "\"version\":\"0.1\"",
                        "\"version\":\"0.2\"",
                    ),
                    "nominal_interval_ms" to canonicalCondition.replaceExactlyOnce(
                        "\"nominal_interval_ms\":$nominal",
                        "\"nominal_interval_ms\":${nominal + 1}",
                    ),
                    "schedule_sha256" to canonicalCondition.replaceExactlyOnce(
                        "\"schedule_sha256\":\"$schedule\"",
                        "\"schedule_sha256\":\"$forgedHash\"",
                    ),
                )
                for ((field, driftedCondition) in drifts) {
                    add(
                        "condition[$index] $field" to canonical.replaceExactlyOnce(
                            canonicalCondition,
                            driftedCondition,
                        ),
                    )
                }
            }

            add(
                "missing root claim_scope" to canonical.replaceExactlyOnce(
                    "  \"claim_scope\":\"application_end_to_end_to_probe_node\",\n",
                    "",
                ),
            )
            add(
                "missing workload content_event_count" to canonical.replaceExactlyOnce(
                    workload,
                    workload.replaceExactlyOnce(",\"content_event_count\":120", ""),
                ),
            )
            add(
                "missing condition[0] schedule_sha256" to canonical.replaceExactlyOnce(
                    conditionObjects[0],
                    conditionObjects[0].replaceExactlyOnce(
                        ",\"schedule_sha256\":\"${conditions[0].third}\"",
                        "",
                    ),
                ),
            )

            val conditionsBlock =
                "\"conditions\":[\n    ${conditionObjects[0]},\n    ${conditionObjects[1]}," +
                    "\n    ${conditionObjects[2]}\n  ]"
            add(
                "conditions swapped" to canonical.replaceExactlyOnce(
                    conditionsBlock,
                    "\"conditions\":[\n    ${conditionObjects[1]},\n    ${conditionObjects[0]}," +
                        "\n    ${conditionObjects[2]}\n  ]",
                ),
            )
            add(
                "conditions missing" to canonical.replaceExactlyOnce(
                    conditionsBlock,
                    "\"conditions\":[\n    ${conditionObjects[0]},\n    ${conditionObjects[1]}\n  ]",
                ),
            )
            add(
                "conditions extra" to canonical.replaceExactlyOnce(
                    conditionsBlock,
                    "\"conditions\":[\n    ${conditionObjects[0]},\n    ${conditionObjects[1]}," +
                        "\n    ${conditionObjects[2]},\n    ${conditionObjects[0]}\n  ]",
                ),
            )
        }

        for ((name, capabilityBody) in cases) {
            CapabilityAwareSseServer(
                capabilityBody = capabilityBody.toByteArray(UTF_8),
                runBody = canonicalRunResponseBody(),
                expectedRequests = 1,
            ).use { server ->
                val transport = AnebClientPrototypeRawPostTransport(
                    AnebClient.createForTest(null, StrictClock()),
                )
                var thrown: Throwable? = null
                try {
                    PrototypeRunStreamAdapter(
                        transport.boundForTest(server.url("/api/v1/prototype/runs")),
                    ).run(
                        endpoint = server.url("/api/v1/prototype/runs"),
                        requestBody = requestBody,
                    )
                } catch (error: Throwable) {
                    thrown = error
                }

                val requests = server.awaitRequests()
                assertTrue("$name did not fail closed: $thrown", thrown is IllegalArgumentException)
                assertEquals(
                    "$name stable error",
                    "prototype capability response is incompatible",
                    thrown?.message,
                )
                assertEquals(
                    "$name request sequence",
                    listOf("GET /api/v1/prototype/capabilities"),
                    requests.map { "${it.method} ${it.path}" },
                )
                assertEquals(
                    "$name prototype POST count",
                    0,
                    requests.count {
                        it.method == "POST" && it.path.startsWith("/api/v1/prototype/runs")
                    },
                )
            }
        }
    }

    @Test
    fun capabilityObjectOrderWhitespaceAndNonEmptyServerVersionRemainCompatible() = runBlocking {
        val requestBody =
            "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\",\"condition_id\":\"baseline_v0.1\"}"
        val carriers = listOf(
            "object order and whitespace" to reorderedWhitespaceCapabilityResponse(),
            "alternate non-empty server_version" to canonicalCapabilityResponse().replaceExactlyOnce(
                "\"server_version\":\"prototype-server-0.1\"",
                "\"server_version\":\"prototype-server-2026.08.30+build.7\"",
            ),
        )

        for ((name, capabilityBody) in carriers) {
            CapabilityAwareSseServer(
                capabilityBody = capabilityBody.toByteArray(UTF_8),
                runBody = canonicalRunResponseBody(),
                expectedRequests = 2,
            ).use { server ->
                val clock = StrictClock()
                val transport = AnebClientPrototypeRawPostTransport(
                    AnebClient.createForTest(null, clock),
                )
                val result = PrototypeRunStreamAdapter(
                    transport.boundForTest(
                        server.url("/api/v1/prototype/runs"),
                        capabilityBody,
                    ),
                    clock,
                ).run(
                    endpoint = server.url("/api/v1/prototype/runs"),
                    requestBody = requestBody,
                )

                assertFalse("$name rejected a compatible capability", result.decodedTerminal.envelope.isEmpty())
                val requests = server.awaitRequests()
                assertEquals(
                    "$name request sequence",
                    listOf(
                        "GET /api/v1/prototype/capabilities",
                        "POST /api/v1/prototype/runs",
                    ),
                    requests.map { "${it.method} ${it.path}" },
                )
                assertArrayEquals(requestBody.toByteArray(UTF_8), requests.last().body)
            }
        }
    }

    private fun readSharedDoneFixture(): String {
        val candidates = listOf(
            Path.of("server/testdata/prototype_option_a_done_frame.sse"),
            Path.of("../../server/testdata/prototype_option_a_done_frame.sse"),
        )
        val path = candidates.firstOrNull { Files.isRegularFile(it) }
            ?: error("shared fixture not found: ${candidates.joinToString()}")
        val normalized = Files.readAllBytes(path).toString(UTF_8).replace("\r\n", "\n")
        require('\r' !in normalized) { "shared fixture contains a bare CR" }
        require(normalized.endsWith("\n\n")) { "shared done fixture must end with LF-LF" }
        return normalized
    }

    private fun doneFrameForRun(doneFrame: String): String = doneFrame
        .replace("\"campaign-fixture-01\"", "\"campaign-1\"")
        .replace("\"run-fixture-01\"", "\"run-1\"")

    private fun serverContentBlock(seq: Int): String =
        "event: content_event\ndata: " +
            "{\"schema_version\":\"aneb-prototype-evidence-0.1\"," +
            "\"protocol_version\":\"prototype-stream-0.1\"," +
            "\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"," +
            "\"condition_id\":\"baseline_v0.1\",\"event_type\":\"content_event\"," +
            "\"server_monotonic_ns\":0,\"clock_source\":\"server.monotonic\"," +
            "\"clock_unit\":\"ns\",\"clock_epoch\":\"process\",\"source\":\"server\"," +
            "\"details\":{\"seq\":$seq,\"planned_offset_ms\":0," +
            "\"payload_id\":\"payload-$seq\",\"profile_manifest_sha256\":\"manifest\"," +
            "\"schedule_hash\":\"schedule\"}}"

    private fun canonicalRunResponseBody(): ByteArray {
        val doneFrame = doneFrameForRun(readSharedDoneFixture()).removeSuffix("\n\n")
        val frames = buildList {
            add(
                "event: run_started\ndata: " +
                    "{\"event_type\":\"run_started\",\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\",\"condition_id\":\"baseline_v0.1\"}",
            )
            repeat(120) { seq ->
                add(serverContentBlock(seq + 1))
            }
            add(doneFrame)
        }
        return frames.joinToString("\n\n", postfix = "\n\n").toByteArray(UTF_8)
    }

    private fun producerShapedRunResponseBody(request: CapturedRequest): ByteArray {
        val requestBody = Json.parseToJsonElement(request.body.toString(UTF_8)).jsonObject
        val campaignId = requestBody.getValue("campaign_id").jsonPrimitive.content
        val runId = requestBody.getValue("run_id").jsonPrimitive.content
        val campaignMode = requestBody.getValue("campaign_mode").jsonPrimitive.content
        val runIndex = requestBody.getValue("run_index").jsonPrimitive.int
        val conditionId = requestBody.getValue("condition_id").jsonPrimitive.content
        val condition = when (conditionId) {
            "baseline_v0.1" -> ProducerCondition(
                initialDelayMs = 200,
                nominalIntervalMs = 50,
                scheduleHash = "46eced73d2fbc886040a3357f84551d424a95e15d6e9e69c16958f6e52e33d7e",
            )
            "slow_v0.1" -> ProducerCondition(
                initialDelayMs = 650,
                nominalIntervalMs = 125,
                scheduleHash = "b51b27fe8332b3fc8a97472a44312b3001ccd54364a61ed8799816c299d27062",
            )
            "unstable_v0.1" -> ProducerCondition(
                initialDelayMs = 350,
                nominalIntervalMs = 65,
                scheduleHash = "d11dce2a877d7c3772a4552f2d922d5f96730c9a01bb829f0203c65b110a8c58",
            )
            else -> error("unsupported test condition: $conditionId")
        }
        val t0Nanos = 1_000_000_000L * runIndex
        val runStarted =
            "event: run_started\ndata: " +
                "{\"schema_version\":\"aneb-prototype-evidence-0.1\"," +
                "\"protocol_version\":\"prototype-stream-0.1\"," +
                "\"campaign_id\":\"$campaignId\",\"run_id\":\"$runId\"," +
                "\"condition_id\":\"$conditionId\",\"event_type\":\"run_started\"," +
                "\"server_monotonic_ns\":$t0Nanos,\"clock_source\":\"server.monotonic\"," +
                "\"clock_unit\":\"ns\",\"clock_epoch\":\"process\",\"source\":\"server\"," +
                "\"details\":{\"profile_id\":\"streaming_text_reference_v0.1\"," +
                "\"profile_version\":\"0.1\"," +
                "\"profile_manifest_sha256\":\"44393ddd5ed11a5091038a85d08ab65ee91a8566997e837d2c40fd3add57d5dc\"," +
                "\"schedule_hash\":\"${condition.scheduleHash}\"," +
                "\"nominal_interval_ms\":${condition.nominalIntervalMs}," +
                "\"t0_monotonic_ns\":$t0Nanos}}"
        val contentFrames = (1..120).map { sequence ->
            val pauseMs = if (conditionId == "unstable_v0.1") {
                (if (sequence > 40) 900 else 0) + (if (sequence > 85) 1_400 else 0)
            } else {
                0
            }
            val plannedOffsetMs =
                condition.initialDelayMs + (sequence - 1) * condition.nominalIntervalMs + pauseMs
            "event: content_event\ndata: " +
                "{\"schema_version\":\"aneb-prototype-evidence-0.1\"," +
                "\"protocol_version\":\"prototype-stream-0.1\"," +
                "\"campaign_id\":\"$campaignId\",\"run_id\":\"$runId\"," +
                "\"condition_id\":\"$conditionId\",\"event_type\":\"content_event\"," +
                "\"server_monotonic_ns\":${t0Nanos + plannedOffsetMs * 1_000_000L}," +
                "\"clock_source\":\"server.monotonic\",\"clock_unit\":\"ns\"," +
                "\"clock_epoch\":\"process\",\"source\":\"server\"," +
                "\"details\":{\"seq\":$sequence,\"planned_offset_ms\":$plannedOffsetMs," +
                "\"payload_id\":\"ref-${"%04d".format(sequence)}\"," +
                "\"profile_manifest_sha256\":\"44393ddd5ed11a5091038a85d08ab65ee91a8566997e837d2c40fd3add57d5dc\"," +
                "\"schedule_hash\":\"${condition.scheduleHash}\"}}"
        }
        val doneFrame = readSharedDoneFixture()
            .replace("\"campaign-fixture-01\"", "\"$campaignId\"")
            .replace("\"run-fixture-01\"", "\"$runId\"")
            .replace("\"condition_id\":\"baseline_v0.1\"", "\"condition_id\":\"$conditionId\"")
            .replace("\"campaign_mode\":\"quick\"", "\"campaign_mode\":\"$campaignMode\"")
            .replace("\"run_index\":1", "\"run_index\":$runIndex")
            .replace(
                "\"schedule_hash\":\"46eced73d2fbc886040a3357f84551d424a95e15d6e9e69c16958f6e52e33d7e\"",
                "\"schedule_hash\":\"${condition.scheduleHash}\"",
            )
            .replace("\"nominal_interval_ms\":50", "\"nominal_interval_ms\":${condition.nominalIntervalMs}")
            .removeSuffix("\n\n")
        return (listOf(runStarted) + contentFrames + doneFrame)
            .joinToString("\n\n", postfix = "\n\n")
            .toByteArray(UTF_8)
    }

    private data class ProducerCondition(
        val initialDelayMs: Int,
        val nominalIntervalMs: Int,
        val scheduleHash: String,
    )

    private fun incompatibleCapabilityResponse(): String =
        """
        {
          "schema_version":"aneb-prototype-capabilities-0.1",
          "product_version":"prototype-0.1",
          "protocol_version":"prototype-stream-0.1",
          "server_version":"prototype-server-0.1",
          "server_binary_sha256":"${"0".repeat(64)}",
          "claim_scope":"wrong_claim_scope",
          "evidence_mode":"synthetic_application_impairment",
          "impairment_layer":"application",
          "profile_manifest_sha256":"44393ddd5ed11a5091038a85d08ab65ee91a8566997e837d2c40fd3add57d5dc",
          "workload":{"id":"streaming_text_reference_v0.1","version":"0.1","content_event_count":120},
          "conditions":[
            {"id":"baseline_v0.1","version":"0.1","nominal_interval_ms":50,"schedule_sha256":"46eced73d2fbc886040a3357f84551d424a95e15d6e9e69c16958f6e52e33d7e"},
            {"id":"slow_v0.1","version":"0.1","nominal_interval_ms":125,"schedule_sha256":"b51b27fe8332b3fc8a97472a44312b3001ccd54364a61ed8799816c299d27062"},
            {"id":"unstable_v0.1","version":"0.1","nominal_interval_ms":65,"schedule_sha256":"d11dce2a877d7c3772a4552f2d922d5f96730c9a01bb829f0203c65b110a8c58"}
          ],
          "evidence_schema_version":"aneb-prototype-evidence-0.1",
          "score_policy_id":"rpi-0.1",
          "terminal_receipt_version":"prototype-terminal-receipt-0.1"
        }
        """.trimIndent()

    private fun canonicalCapabilityResponse(): String =
        incompatibleCapabilityResponse().replace(
            "\"claim_scope\":\"wrong_claim_scope\"",
            "\"claim_scope\":\"application_end_to_end_to_probe_node\"",
        )

    private fun AnebClientPrototypeRawPostTransport.boundForTest(
        runUrl: String,
        capabilityBody: String = canonicalCapabilityResponse(),
    ): PrototypeRawPostTransport = forTicket(
        ticketFromValidatedSnapshot(runUrl, capabilityBody),
    )

    private fun reorderedWhitespaceCapabilityResponse(): String =
        """
        {
          "terminal_receipt_version" : "prototype-terminal-receipt-0.1",
          "score_policy_id" : "rpi-0.1",
          "evidence_schema_version" : "aneb-prototype-evidence-0.1",
          "conditions" : [
            {
              "schedule_sha256" : "46eced73d2fbc886040a3357f84551d424a95e15d6e9e69c16958f6e52e33d7e",
              "nominal_interval_ms" : 50,
              "version" : "0.1",
              "id" : "baseline_v0.1"
            },
            {
              "schedule_sha256" : "b51b27fe8332b3fc8a97472a44312b3001ccd54364a61ed8799816c299d27062",
              "nominal_interval_ms" : 125,
              "version" : "0.1",
              "id" : "slow_v0.1"
            },
            {
              "schedule_sha256" : "d11dce2a877d7c3772a4552f2d922d5f96730c9a01bb829f0203c65b110a8c58",
              "nominal_interval_ms" : 65,
              "version" : "0.1",
              "id" : "unstable_v0.1"
            }
          ],
          "workload" : {
            "content_event_count" : 120,
            "version" : "0.1",
            "id" : "streaming_text_reference_v0.1"
          },
          "profile_manifest_sha256" : "44393ddd5ed11a5091038a85d08ab65ee91a8566997e837d2c40fd3add57d5dc",
          "impairment_layer" : "application",
          "evidence_mode" : "synthetic_application_impairment",
          "claim_scope" : "application_end_to_end_to_probe_node",
          "server_binary_sha256" : "${"0".repeat(64)}",
          "server_version" : "prototype-server-reordered-2026.08.30",
          "protocol_version" : "prototype-stream-0.1",
          "product_version" : "prototype-0.1",
          "schema_version" : "aneb-prototype-capabilities-0.1"
        }
        """.trimIndent()

    private fun sameIdentityReorderedCapabilityResponse(): String =
        reorderedWhitespaceCapabilityResponse().replaceExactlyOnce(
            "\"server_version\" : \"prototype-server-reordered-2026.08.30\"",
            "\"server_version\" : \"prototype-server-0.1\"",
        )

    private fun String.replaceExactlyOnce(oldValue: String, newValue: String): String {
        val first = indexOf(oldValue)
        require(first >= 0) { "missing fixture fragment: $oldValue" }
        require(indexOf(oldValue, first + oldValue.length) < 0) {
            "fixture fragment is not unique: $oldValue"
        }
        return replaceRange(first, first + oldValue.length, newValue)
    }

    private fun equivalentNumericCapabilityResponse(): String =
        canonicalCapabilityResponse()
            .replace("\"content_event_count\":120", "\"content_event_count\":120.0")
            .replace("\"nominal_interval_ms\":50", "\"nominal_interval_ms\":5e1")
            .replace("\"nominal_interval_ms\":125", "\"nominal_interval_ms\":125.0")
            .replace("\"nominal_interval_ms\":65", "\"nominal_interval_ms\":6.5e1")

    private fun duplicateClaimScopeCapabilityResponse(): String =
        canonicalCapabilityResponse().replace(
            "\"claim_scope\":\"application_end_to_end_to_probe_node\"",
            "\"claim_scope\":\"wrong_claim_scope\",\"claim_scope\":\"application_end_to_end_to_probe_node\"",
        )

    private fun duplicateWorkloadContentEventCountCapabilityResponse(): String =
        canonicalCapabilityResponse().replace(
            "\"content_event_count\":120",
            "\"content_event_count\":121,\"content_event_count\":120",
        )

    private fun duplicateBaselineScheduleHashCapabilityResponse(): String {
        val canonical = "46eced73d2fbc886040a3357f84551d424a95e15d6e9e69c16958f6e52e33d7e"
        return canonicalCapabilityResponse().replace(
            "\"schedule_sha256\":\"$canonical\"",
            "\"schedule_sha256\":\"${"f".repeat(64)}\",\"schedule_sha256\":\"$canonical\"",
        )
    }

    private data class CapturedRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private class StrictClock(start: Long = 1_000_000L) : MonotonicNanosClock {
        private val ticks = AtomicLong(start)

        override fun now(): Long = ticks.incrementAndGet()
    }

    /** Dependency-free loopback HTTP server; production continues to use OkHttp. */
    private class LoopbackSseServer(
        private val responseBody: ByteArray,
    ) : AutoCloseable {
        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val requestReady = CountDownLatch(1)
        private val captured = AtomicReference<CapturedRequest?>()
        private val failure = AtomicReference<Throwable?>()
        @Volatile
        var responseStreamWasTruncated: Boolean = false
            private set
        private val worker = thread(
            start = true,
            isDaemon = true,
            name = "aneb-prototype-raw-post-loopback",
        ) { serve() }

        fun url(path: String): String = "http://127.0.0.1:${socket.localPort}$path"

        fun awaitRequest(): CapturedRequest {
            check(requestReady.await(5, TimeUnit.SECONDS)) { "timed out waiting for HTTP request" }
            failure.get()?.let { throw AssertionError("loopback HTTP server failed", it) }
            return checkNotNull(captured.get()) { "loopback HTTP server captured no request" }
        }

        override fun close() {
            runCatching { socket.close() }
            worker.join(5_000)
            failure.get()?.let { throw AssertionError("loopback HTTP server failed", it) }
        }

        private fun serve() {
            try {
                socket.accept().use { connection ->
                    val input = connection.getInputStream()
                    val headerText = readHeaders(input).toString(UTF_8)
                    val lines = headerText.split("\r\n")
                    val requestLine = lines.first().split(' ', limit = 3)
                    val headers = lines.drop(1)
                        .filter { it.contains(':') }
                        .associate { line ->
                            line.substringBefore(':').lowercase() to line.substringAfter(':').trim()
                        }
                    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                    val body = readExactly(input, contentLength)
                    captured.set(
                        CapturedRequest(
                            method = requestLine[0],
                            path = requestLine[1],
                            headers = headers,
                            body = body,
                        ),
                    )
                    requestReady.countDown()

                    val output = connection.getOutputStream()
                    output.write(
                        "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n".toByteArray(UTF_8),
                    )
                    output.write("Content-Length: ${responseBody.size}\r\nConnection: close\r\n\r\n".toByteArray(UTF_8))
                    output.flush()
                    responseBody.asList().chunked(11).forEach { chunk ->
                        output.write(chunk.toByteArray())
                        output.flush()
                        Thread.sleep(2)
                    }
                }
            } catch (error: Throwable) {
                responseStreamWasTruncated = true
                failure.set(error)
                requestReady.countDown()
            }
        }

        private fun readHeaders(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            var matched = 0
            while (out.size() < 64 * 1024) {
                val next = input.read()
                check(next >= 0) { "request ended before headers" }
                out.write(next)
                matched = when {
                    matched == 0 && next == '\r'.code -> 1
                    matched == 1 && next == '\n'.code -> 2
                    matched == 2 && next == '\r'.code -> 3
                    matched == 3 && next == '\n'.code -> 4
                    else -> 0
                }
                if (matched == 4) return out.toByteArray()
            }
            error("request headers exceed 64 KiB")
        }

        private fun readExactly(input: InputStream, count: Int): ByteArray {
            val bytes = ByteArray(count)
            var offset = 0
            while (offset < count) {
                val read = input.read(bytes, offset, count - offset)
                check(read >= 0) { "request ended before body" }
                offset += read
            }
            return bytes
        }
    }

    /** Routes capability GET and run POST while preserving observed request order. */
    private class CapabilityAwareSseServer(
        private val capabilityBody: ByteArray,
        private val runBody: ByteArray,
        private val expectedRequests: Int = 1,
        private val capabilityStatus: Int = 200,
        private val runBodyForRequest: ((CapturedRequest) -> ByteArray)? = null,
        private val capabilityBodies: List<ByteArray> = listOf(capabilityBody),
    ) : AutoCloseable {
        private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        private val requestReady = CountDownLatch(expectedRequests)
        private val captured = CopyOnWriteArrayList<CapturedRequest>()
        private val failure = AtomicReference<Throwable?>()
        private val capabilityRequestIndex = AtomicInteger(0)
        @Volatile
        var responseStreamWasTruncated: Boolean = false
            private set

        private val worker = thread(
            start = true,
            isDaemon = true,
            name = "aneb-prototype-capability-ordering",
        ) { serve() }

        fun url(path: String): String = "http://127.0.0.1:${socket.localPort}$path"

        fun awaitRequests(): List<CapturedRequest> {
            check(requestReady.await(5, TimeUnit.SECONDS)) {
                "timed out waiting for capability/run request"
            }
            worker.join(2_000)
            failure.get()?.let { throw AssertionError("capability HTTP server failed", it) }
            return captured.toList()
        }

        override fun close() {
            runCatching { socket.close() }
            worker.join(5_000)
            failure.get()?.let { throw AssertionError("capability HTTP server failed", it) }
        }

        private fun serve() {
            try {
                while (!socket.isClosed && captured.size < expectedRequests) {
                    val connection = socket.accept()
                    connection.use { handle(it) }
                }
            } catch (error: Throwable) {
                if (!socket.isClosed) {
                    failure.set(error)
                    requestReady.countDown()
                }
            }
        }

        private fun handle(connection: java.net.Socket) {
            val input = connection.getInputStream()
            val headerText = readHeaders(input).toString(UTF_8)
            val lines = headerText.split("\r\n")
            val requestLine = lines.first().split(' ', limit = 3)
            val headers = lines.drop(1)
                .filter { it.contains(':') }
                .associate { line ->
                    line.substringBefore(':').lowercase() to line.substringAfter(':').trim()
                }
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = readExactly(input, contentLength)
            val capturedRequest = CapturedRequest(
                method = requestLine[0],
                path = requestLine[1],
                headers = headers,
                body = body,
            )
            captured += capturedRequest
            requestReady.countDown()

            val (status, contentType, responseBody) = when {
                requestLine[0] == "GET" && requestLine[1] == "/api/v1/prototype/capabilities" ->
                    Triple(
                        capabilityStatus,
                        "application/json",
                        capabilityBodies.getOrElse(capabilityRequestIndex.getAndIncrement()) {
                            capabilityBodies.last()
                        },
                    )

                requestLine[0] == "POST" &&
                    requestLine[1].substringBefore('?') == "/api/v1/prototype/runs" ->
                    Triple(
                        200,
                        "text/event-stream",
                        runBodyForRequest?.invoke(capturedRequest) ?: runBody,
                    )

                else -> Triple(404, "application/json", "{}".toByteArray(UTF_8))
            }
            val reason = when (status) {
                in 200..299 -> "OK"
                500 -> "Internal Server Error"
                else -> "Not Found"
            }
            val output = connection.getOutputStream()
            output.write(
                "HTTP/1.1 $status $reason\r\n".toByteArray(UTF_8),
            )
            output.write("Content-Type: $contentType\r\n".toByteArray(UTF_8))
            output.write(
                "Content-Length: ${responseBody.size}\r\nConnection: close\r\n\r\n".toByteArray(UTF_8),
            )
            output.write(responseBody)
            output.flush()
        }

        private fun readHeaders(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            var matched = 0
            while (out.size() < 64 * 1024) {
                val next = input.read()
                check(next >= 0) { "request ended before headers" }
                out.write(next)
                matched = when {
                    matched == 0 && next == '\r'.code -> 1
                    matched == 1 && next == '\n'.code -> 2
                    matched == 2 && next == '\r'.code -> 3
                    matched == 3 && next == '\n'.code -> 4
                    else -> 0
                }
                if (matched == 4) return out.toByteArray()
            }
            error("request headers exceed 64 KiB")
        }

        private fun readExactly(input: InputStream, count: Int): ByteArray {
            val bytes = ByteArray(count)
            var offset = 0
            while (offset < count) {
                val read = input.read(bytes, offset, count - offset)
                check(read >= 0) { "request ended before body" }
                offset += read
            }
            return bytes
        }
    }

    /** Keeps a partial capability response body open until the client cancels the GET. */
    private class BlockingCapabilityServer : AutoCloseable {
        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val partialBodySent = CountDownLatch(1)
        private val connectionClosed = CountDownLatch(1)
        private val captured = CopyOnWriteArrayList<CapturedRequest>()
        private val failure = AtomicReference<Throwable?>()
        private val worker = thread(
            start = true,
            isDaemon = true,
            name = "aneb-prototype-capability-get-cancel",
        ) { serve() }

        fun url(path: String): String = "http://127.0.0.1:${socket.localPort}$path"

        fun awaitPartialBodySent() {
            check(partialBodySent.await(5, TimeUnit.SECONDS)) {
                "timed out waiting for partial capability body"
            }
            failure.get()?.let { throw AssertionError("blocking capability server failed", it) }
        }

        fun awaitConnectionClosed(): Boolean = connectionClosed.await(5, TimeUnit.SECONDS)

        fun requests(): List<CapturedRequest> = captured.toList()

        override fun close() {
            runCatching { socket.close() }
            worker.join(5_000)
            failure.get()?.let { throw AssertionError("blocking capability server failed", it) }
        }

        private fun serve() {
            try {
                socket.accept().use { connection ->
                    val input = connection.getInputStream()
                    val headerText = readHeaders(input).toString(UTF_8)
                    val lines = headerText.split("\r\n")
                    val requestLine = lines.first().split(' ', limit = 3)
                    val headers = lines.drop(1)
                        .filter { it.contains(':') }
                        .associate { line ->
                            line.substringBefore(':').lowercase() to line.substringAfter(':').trim()
                        }
                    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                    val body = readExactly(input, contentLength)
                    captured += CapturedRequest(
                        method = requestLine[0],
                        path = requestLine[1],
                        headers = headers,
                        body = body,
                    )
                    check(requestLine[0] == "GET") { "expected capability GET, got ${requestLine[0]}" }
                    check(requestLine[1] == "/api/v1/prototype/capabilities") {
                        "unexpected capability path ${requestLine[1]}"
                    }

                    val partialBody = "{\"schema_version\":\"aneb-prototype".toByteArray(UTF_8)
                    val output = connection.getOutputStream()
                    output.write(
                        (
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${partialBody.size + 4096}\r\n" +
                                "Connection: close\r\n\r\n"
                        ).toByteArray(UTF_8),
                    )
                    output.write(partialBody)
                    output.flush()
                    partialBodySent.countDown()

                    runCatching {
                        while (input.read() >= 0) {
                            // Wait for OkHttp cancellation to close the capability GET socket.
                        }
                    }
                    connectionClosed.countDown()
                }
            } catch (error: Throwable) {
                if (!socket.isClosed) failure.set(error)
                partialBodySent.countDown()
                connectionClosed.countDown()
            }
        }

        private fun readHeaders(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            var matched = 0
            while (out.size() < 64 * 1024) {
                val next = input.read()
                check(next >= 0) { "request ended before headers" }
                out.write(next)
                matched = when {
                    matched == 0 && next == '\r'.code -> 1
                    matched == 1 && next == '\n'.code -> 2
                    matched == 2 && next == '\r'.code -> 3
                    matched == 3 && next == '\n'.code -> 4
                    else -> 0
                }
                if (matched == 4) return out.toByteArray()
            }
            error("request headers exceed 64 KiB")
        }

        private fun readExactly(input: InputStream, count: Int): ByteArray {
            val bytes = ByteArray(count)
            var offset = 0
            while (offset < count) {
                val read = input.read(bytes, offset, count - offset)
                check(read >= 0) { "request ended before body" }
                offset += read
            }
            return bytes
        }
    }

    /** Sends response headers and keeps the SSE body open until the client cancels. */
    private class BlockingSseServer(
        private val capabilityBody: ByteArray,
    ) : AutoCloseable {
        private val socket = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        private val headersSent = CountDownLatch(1)
        private val connectionClosed = CountDownLatch(1)
        private val failure = AtomicReference<Throwable?>()
        private val worker = thread(
            start = true,
            isDaemon = true,
            name = "aneb-prototype-raw-post-cancel",
        ) { serve() }

        fun url(path: String): String = "http://127.0.0.1:${socket.localPort}$path"

        fun awaitHeadersSent() {
            check(headersSent.await(5, TimeUnit.SECONDS)) { "timed out waiting for response headers" }
            failure.get()?.let { throw AssertionError("blocking loopback server failed", it) }
        }

        fun awaitConnectionClosed(): Boolean = connectionClosed.await(5, TimeUnit.SECONDS)

        override fun close() {
            runCatching { socket.close() }
            worker.join(5_000)
            failure.get()?.let { throw AssertionError("blocking loopback server failed", it) }
        }

        private fun serve() {
            try {
                socket.accept().use { connection ->
                    val request = readRequest(connection)
                    check(request.method == "GET") { "expected capability GET, got ${request.method}" }
                    check(request.path == "/api/v1/prototype/capabilities") {
                        "unexpected capability path ${request.path}"
                    }
                    writeResponse(connection, "application/json", capabilityBody)
                }
                socket.accept().use { connection ->
                    val request = readRequest(connection)
                    check(request.method == "POST") { "expected run POST, got ${request.method}" }
                    check(request.path.startsWith("/api/v1/prototype/runs")) {
                        "unexpected run path ${request.path}"
                    }

                    val output = connection.getOutputStream()
                    output.write(
                        (
                            "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
                                "Connection: close\r\n\r\n"
                        ).toByteArray(UTF_8),
                    )
                    output.flush()
                    headersSent.countDown()

                    while (request.input.read() >= 0) {
                        // Wait for OkHttp cancellation to close the socket.
                    }
                    connectionClosed.countDown()
                }
            } catch (error: Throwable) {
                failure.set(error)
                headersSent.countDown()
                connectionClosed.countDown()
            }
        }

        private data class Request(
            val method: String,
            val path: String,
            val input: InputStream,
        )

        private fun readRequest(connection: java.net.Socket): Request {
            val input = connection.getInputStream()
            val headerText = readHeaders(input).toString(UTF_8)
            val lines = headerText.split("\r\n")
            val requestLine = lines.first().split(' ', limit = 3)
            val headers = lines.drop(1)
                .filter { it.contains(':') }
                .associate { line ->
                    line.substringBefore(':').lowercase() to line.substringAfter(':').trim()
                }
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            readExactly(input, contentLength)
            return Request(requestLine[0], requestLine[1], input)
        }

        private fun writeResponse(
            connection: java.net.Socket,
            contentType: String,
            body: ByteArray,
        ) {
            val output = connection.getOutputStream()
            output.write(
                (
                    "HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\n" +
                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                ).toByteArray(UTF_8),
            )
            output.write(body)
            output.flush()
        }

        private fun readHeaders(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            var matched = 0
            while (out.size() < 64 * 1024) {
                val next = input.read()
                check(next >= 0) { "request ended before headers" }
                out.write(next)
                matched = when {
                    matched == 0 && next == '\r'.code -> 1
                    matched == 1 && next == '\n'.code -> 2
                    matched == 2 && next == '\r'.code -> 3
                    matched == 3 && next == '\n'.code -> 4
                    else -> 0
                }
                if (matched == 4) return out.toByteArray()
            }
            error("request headers exceed 64 KiB")
        }

        private fun readExactly(input: InputStream, count: Int) {
            val bytes = ByteArray(count)
            var offset = 0
            while (offset < count) {
                val read = input.read(bytes, offset, count - offset)
                check(read >= 0) { "request ended before body" }
                offset += read
            }
        }
    }
}
