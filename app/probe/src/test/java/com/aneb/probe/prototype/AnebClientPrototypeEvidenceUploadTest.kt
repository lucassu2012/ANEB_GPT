package com.aneb.probe.prototype

import com.aneb.probe.net.AnebClient
import com.aneb.probe.net.MonotonicNanosClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class AnebClientPrototypeEvidenceUploadTest {
    @Test
    fun postsOpaqueCanonicalEvidenceThroughPrototypeTransport() = runBlocking {
        val upload = "{\"schema_version\":\"aneb-prototype-upload-0.1\",\"campaign_id\":\"campaign-0001\"}"
        val receipt =
            "{\"campaign_id\":\"campaign-0001\",\"manifest_sha256\":\"${"a".repeat(64)}\"," +
                "\"publication_status\":\"verified\",\"schema_version\":" +
                "\"aneb-prototype-publication-receipt-0.1\"}\n"
        OneShotJsonServer(receipt).use { server ->
            val result = AnebClient.createForTest(null, StrictClock()).postPrototypeEvidence(
                url = server.url("/api/v1/prototype/campaigns/evidence"),
                jsonBody = upload,
            )
            val request = server.awaitRequest()

            assertEquals(200, result.httpCode)
            assertEquals(receipt, result.body)
            assertNull(result.error)
            assertEquals("POST", request.method)
            assertEquals("/api/v1/prototype/campaigns/evidence", request.path)
            assertEquals("application/json; charset=utf-8", request.contentType)
            assertEquals(upload, request.body)
        }
    }

    private class StrictClock(start: Long = 1_000_000L) : MonotonicNanosClock {
        private val next = AtomicLong(start)

        override fun now(): Long = next.getAndIncrement()
    }

    private data class CapturedRequest(
        val method: String,
        val path: String,
        val contentType: String?,
        val body: String,
    )

    private class OneShotJsonServer(private val responseBody: String) : AutoCloseable {
        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val requestReady = CountDownLatch(1)
        private val captured = AtomicReference<CapturedRequest?>()
        private val failure = AtomicReference<Throwable?>()
        private val worker = thread(start = true, isDaemon = true, name = "aneb-evidence-upload-test") {
            serve()
        }

        fun url(path: String): String = "http://127.0.0.1:${socket.localPort}$path"

        fun awaitRequest(): CapturedRequest {
            check(requestReady.await(5, TimeUnit.SECONDS)) { "timed out waiting for upload request" }
            failure.get()?.let { throw AssertionError("test HTTP server failed", it) }
            return checkNotNull(captured.get())
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
                    val lines = readHeaders(input).toString(UTF_8).split("\r\n")
                    val requestLine = lines.first().split(' ', limit = 3)
                    val headers = lines.drop(1).filter { it.contains(':') }.associate { line ->
                        line.substringBefore(':').lowercase() to line.substringAfter(':').trim()
                    }
                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    val body = ByteArray(length)
                    var offset = 0
                    while (offset < length) {
                        val read = input.read(body, offset, length - offset)
                        check(read >= 0) { "request ended before body" }
                        offset += read
                    }
                    captured.set(
                        CapturedRequest(
                            method = requestLine[0],
                            path = requestLine[1],
                            contentType = headers["content-type"],
                            body = body.toString(UTF_8),
                        ),
                    )
                    requestReady.countDown()
                    val response = responseBody.toByteArray(UTF_8)
                    connection.getOutputStream().use { output ->
                        output.write(
                            (
                                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                    "Content-Length: ${response.size}\r\nConnection: close\r\n\r\n"
                            ).toByteArray(UTF_8),
                        )
                        output.write(response)
                    }
                }
            } catch (error: Throwable) {
                failure.set(error)
                requestReady.countDown()
            }
        }

        private fun readHeaders(input: InputStream): ByteArray {
            val output = ByteArrayOutputStream()
            var matched = 0
            while (output.size() < 64 * 1024) {
                val next = input.read()
                check(next >= 0) { "request ended before headers" }
                output.write(next)
                matched = when {
                    matched == 0 && next == '\r'.code -> 1
                    matched == 1 && next == '\n'.code -> 2
                    matched == 2 && next == '\r'.code -> 3
                    matched == 3 && next == '\n'.code -> 4
                    else -> 0
                }
                if (matched == 4) return output.toByteArray()
            }
            error("request headers exceed 64 KiB")
        }
    }
}
