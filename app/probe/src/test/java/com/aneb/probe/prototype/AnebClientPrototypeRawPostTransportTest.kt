package com.aneb.probe.prototype

import com.aneb.probe.net.AnebClient
import com.aneb.probe.net.MonotonicNanosClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Natural RED for the AnebClient-backed Prototype raw POST/SSE bridge.
 *
 * The request JSON is an opaque caller-provided carrier; this atom does not
 * construct or canonicalize the RunRequest. Content sequence/identity is
 * validated by the adapter; metrics, persistence, and UI remain NONCLAIMS.
 */
class AnebClientPrototypeRawPostTransportTest {
    @Test
    fun loopbackPostPreservesOpaqueRequestHeadersAndFramesThroughAdapter() = runBlocking {
        val requestBody =
            "{\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\",\"opaque\":true,\"order\":[3,1,2],\"unicode\":\"端到端\"}"
        val doneFrame = doneFrameForRun(readSharedDoneFixture()).removeSuffix("\n\n")
        val expectedFrames = buildList {
            add(
                "event: run_started\ndata: " +
                    "{\"event_type\":\"run_started\",\"campaign_id\":\"campaign-1\",\"run_id\":\"run-1\"}",
            )
            repeat(120) { seq ->
                add(serverContentBlock(seq + 1))
            }
            add(doneFrame)
        }
        val responseBody = expectedFrames.joinToString("\n\n", postfix = "\n\n").toByteArray(UTF_8)

        LoopbackSseServer(responseBody).use { server ->
            val transport = AnebClientPrototypeRawPostTransport(
                AnebClient.createForTest(null, StrictClock()),
            )
            val result = PrototypeRunStreamAdapter(transport).run(
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

            val request = server.awaitRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/prototype/runs", request.path)
            assertTrue(request.headers.getValue("content-type").startsWith("application/json"))
            assertTrue(request.headers.getValue("accept").contains("text/event-stream"))
            assertArrayEquals(requestBody.toByteArray(UTF_8), request.body)
        }
    }

    @Test
    fun cancellationThroughBridgeClosesConnectionAndReleasesTimingRecord() = runBlocking {
        val client = AnebClient.createForTest(null, StrictClock())
        val transport = AnebClientPrototypeRawPostTransport(client)
        val thrown = AtomicReference<Throwable?>()
        val cancellation = CancellationException("cancelled by bridge test")

        BlockingSseServer().use { server ->
            val job = launch(Dispatchers.IO) {
                try {
                    PrototypeRunStreamAdapter(transport).run(
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
            "\"condition_id\":\"condition-1\",\"event_type\":\"content_event\"," +
            "\"server_monotonic_ns\":0,\"clock_source\":\"server.monotonic\"," +
            "\"clock_unit\":\"ns\",\"clock_epoch\":\"process\",\"source\":\"server\"," +
            "\"details\":{\"seq\":$seq,\"planned_offset_ms\":0," +
            "\"payload_id\":\"payload-$seq\",\"profile_manifest_sha256\":\"manifest\"," +
            "\"schedule_hash\":\"schedule\"}}"

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

    /** Sends response headers and keeps the SSE body open until the client cancels. */
    private class BlockingSseServer : AutoCloseable {
        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
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
                    val input = connection.getInputStream()
                    val headerText = readHeaders(input).toString(UTF_8)
                    val headers = headerText.split("\r\n")
                        .drop(1)
                        .filter { it.contains(':') }
                        .associate { line ->
                            line.substringBefore(':').lowercase() to line.substringAfter(':').trim()
                        }
                    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                    readExactly(input, contentLength)

                    val output = connection.getOutputStream()
                    output.write(
                        (
                            "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
                                "Connection: close\r\n\r\n"
                        ).toByteArray(UTF_8),
                    )
                    output.flush()
                    headersSent.countDown()

                    while (input.read() >= 0) {
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
