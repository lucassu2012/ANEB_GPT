package com.aneb.probe.prototype

import com.aneb.probe.net.AnebClient
import com.aneb.probe.net.MonotonicNanosClock
import com.aneb.probe.net.RawSseStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
 * Natural RED for the real AnebClient Prototype POST/SSE seam.
 *
 * The request body is an opaque caller-provided UTF-8 carrier: this test does
 * not construct, reorder, or canonicalize the 12-field run request. The
 * run_started/content_event frames are also transport carriers only; 122-frame
 * topology, runner sequencing, and chronology are explicit NONCLAIMS here.
 */
class AnebClientPrototypeRunSeamTest {
    @Test
    fun postPrototypeRunPreservesOpaqueRequestAndRawEvents() = runBlocking {
        val requestBody = "{\"opaque\":true,\"order\":[3,1,2],\"unicode\":\"端到端\"}"
        val doneFrame = readSharedDoneFixture()
        val expectedBlocks = listOf(
            "event: run_started\ndata: {\"event_type\":\"run_started\"}",
            "event: content_event\ndata: {\"event_type\":\"content_event\"}",
            doneFrame.removeSuffix("\n\n"),
        )
        val responseBody = expectedBlocks.joinToString("\n\n", postfix = "\n\n")
            .toByteArray(UTF_8)

        OneShotHttpServer(
            status = 200,
            contentType = "text/event-stream",
            responseBody = responseBody,
            responseChunks = responseBody.asList().chunked(11).map { it.toByteArray() },
        ).use { server ->
            val result: RawSseStream = AnebClient.createForTest(null, StrictClock()).postPrototypeRawSse(
                url = server.url("/api/v1/prototype/runs"),
                requestBody = requestBody,
            )

            assertFalse(result.truncatedTail)
            assertEquals(expectedBlocks, result.events.map { it.bytes.toString(UTF_8) })
            assertEquals(responseBody.size.toLong(), result.totalBytes)
            assertTrue(
                result.events.zipWithNext().all { (previous, current) ->
                    current.arrivalNanos >= previous.arrivalNanos
                },
            )
            val lastArrival = result.events.maxOf { it.arrivalNanos }
            assertTrue(result.eofNanos >= lastArrival)

            val request = server.awaitRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/prototype/runs", request.path)
            assertTrue(request.headers.getValue("content-type").startsWith("application/json"))
            assertTrue(request.headers.getValue("accept").contains("text/event-stream"))
            assertArrayEquals(requestBody.toByteArray(UTF_8), request.body)
        }
    }

    @Test
    fun non2xxPrototypeRunPreservesStatusAndBodyWithoutSseDecode() = runBlocking {
        val requestBody = "{\"opaque\":\"non-2xx\"}"
        val errorBody = "{\"error\":\"server_rejected\",\"reason\":\"invalid request\"}"

        OneShotHttpServer(
            status = 409,
            contentType = "application/json",
            responseBody = errorBody.toByteArray(UTF_8),
        ).use { server ->
            try {
                AnebClient.createForTest(null, StrictClock()).postPrototypeRawSse(
                    url = server.url("/api/v1/prototype/runs"),
                    requestBody = requestBody,
                )
                fail("non-2xx JSON response was accepted as an SSE stream")
            } catch (error: Exception) {
                // Do not lock an exception class or full message. The seam must
                // expose both status and response body to its caller.
                val message = error.message.orEmpty()
                assertTrue("missing HTTP status in failure: $message", message.contains("409"))
                assertTrue("missing response body in failure: $message", message.contains(errorBody))
            }

            val request = server.awaitRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/prototype/runs", request.path)
            assertArrayEquals(requestBody.toByteArray(UTF_8), request.body)
        }
    }

    @Test
    fun cancelledPrototypeRunPropagatesCancellationAndClosesConnection() = runBlocking {
        val cancellation = CancellationException("cancelled by test")
        val client = AnebClient.createForTest(null, StrictClock())
        val thrown = AtomicReference<Throwable?>()

        BlockingBodyHttpServer().use { server ->
            val job = launch(Dispatchers.IO) {
                try {
                    client.postPrototypeRawSse(
                        url = server.url("/api/v1/prototype/runs"),
                        requestBody = "{\"opaque\":\"cancel\"}",
                    )
                    thrown.set(AssertionError("blocked SSE request unexpectedly returned"))
                } catch (error: Throwable) {
                    thrown.set(error)
                }
            }

            server.awaitHeadersSent()
            job.cancel(cancellation)
            job.join()

            val propagated = thrown.get()
            assertTrue(propagated is CancellationException)
            assertEquals(cancellation.message, propagated?.message)
            assertTrue("server connection did not close after cancellation", server.awaitConnectionClosed())
            assertEquals(0, client.activeTimingRecordCountForTest())
        }
    }

    @Test
    fun partialFinalFrameAtEofReturnsTruncatedTailAndKeepsCompleteFrames() = runBlocking {
        val completeFrame = "event: content_event\ndata: {\"event_type\":\"content_event\"}"
        val partialFinalFrame = "event: done\ndata: {\"event_type\":\"terminal_event\"}"
        val responseBody = (completeFrame + "\n\n" + partialFinalFrame).toByteArray(UTF_8)

        OneShotHttpServer(
            status = 200,
            contentType = "text/event-stream",
            responseBody = responseBody,
        ).use { server ->
            val result = AnebClient.createForTest(null, StrictClock()).postPrototypeRawSse(
                url = server.url("/api/v1/prototype/runs"),
                requestBody = "{\"opaque\":\"partial\"}",
            )

            assertTrue(result.truncatedTail)
            assertEquals(listOf(completeFrame), result.events.map { it.bytes.toString(UTF_8) })
            assertEquals(responseBody.size.toLong(), result.totalBytes)
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

    /** A dependency-free local HTTP test server; production must still use OkHttp. */
    private class OneShotHttpServer(
        private val status: Int,
        private val contentType: String,
        private val responseBody: ByteArray,
        private val responseChunks: List<ByteArray> = listOf(responseBody),
    ) : AutoCloseable {
        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val requestReady = CountDownLatch(1)
        private val captured = AtomicReference<CapturedRequest?>()
        private val failure = AtomicReference<Throwable?>()
        private val worker = thread(
            start = true,
            isDaemon = true,
            name = "aneb-prototype-seam-http",
        ) { serve() }

        fun url(path: String): String = "http://127.0.0.1:${socket.localPort}$path"

        fun awaitRequest(): CapturedRequest {
            check(requestReady.await(5, TimeUnit.SECONDS)) { "timed out waiting for HTTP request" }
            failure.get()?.let { throw AssertionError("test HTTP server failed", it) }
            return checkNotNull(captured.get()) { "test HTTP server captured no request" }
        }

        override fun close() {
            runCatching { socket.close() }
            worker.join(5_000)
            failure.get()?.let { throw AssertionError("test HTTP server failed", it) }
        }

        private fun serve() {
            try {
                socket.accept().use { connection ->
                    val input = connection.getInputStream()
                    val headerBytes = readHeaders(input)
                    val headerText = headerBytes.toString(UTF_8)
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
                        )
                    )
                    requestReady.countDown()

                    val reason = when (status) {
                        200 -> "OK"
                        400 -> "Bad Request"
                        409 -> "Conflict"
                        else -> "Test"
                    }
                    val output = connection.getOutputStream()
                    output.write(
                        "HTTP/1.1 $status $reason\r\n".toByteArray(UTF_8),
                    )
                    output.write("Content-Type: $contentType\r\n".toByteArray(UTF_8))
                    output.write("Content-Length: ${responseBody.size}\r\n".toByteArray(UTF_8))
                    output.write("Connection: close\r\n\r\n".toByteArray(UTF_8))
                    for (chunk in responseChunks) {
                        output.write(chunk)
                        output.flush()
                        Thread.sleep(2)
                    }
                }
            } catch (error: Throwable) {
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

    /** Sends 200 headers, then holds the body open until the client cancels. */
    private class BlockingBodyHttpServer : AutoCloseable {
        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val headersSent = CountDownLatch(1)
        private val connectionClosed = CountDownLatch(1)
        private val failure = AtomicReference<Throwable?>()
        private val worker = thread(
            start = true,
            isDaemon = true,
            name = "aneb-prototype-cancel-http",
        ) { serve() }

        fun url(path: String): String = "http://127.0.0.1:${socket.localPort}$path"

        fun awaitHeadersSent() {
            check(headersSent.await(5, TimeUnit.SECONDS)) { "timed out waiting for 200 response headers" }
            failure.get()?.let { throw AssertionError("blocking HTTP server failed", it) }
        }

        fun awaitConnectionClosed(): Boolean = connectionClosed.await(5, TimeUnit.SECONDS)

        override fun close() {
            runCatching { socket.close() }
            worker.join(5_000)
            failure.get()?.let { throw AssertionError("blocking HTTP server failed", it) }
        }

        private fun serve() {
            try {
                socket.accept().use { connection ->
                    val input = connection.getInputStream()
                    val headerBytes = readHeaders(input)
                    val headerText = headerBytes.toString(UTF_8)
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
                        // Wait for OkHttp cancellation to close the client socket.
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
