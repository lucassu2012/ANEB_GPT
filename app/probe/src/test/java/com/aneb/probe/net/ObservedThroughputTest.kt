package com.aneb.probe.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ObservedThroughputTest {
    @Test
    fun `upload writes at deadline retain client bytes without inventing response success`() = runBlocking {
        val ticks = AtomicLong(1_000_000L)
        val trace = ThroughputObservation("upload-0-0", 0, "upload", 33_554_432L) { ticks.incrementAndGet() }
        val sawBytes = CountDownLatch(1)
        PendingUploadServer().use { server ->
            val job = launch(Dispatchers.IO) {
                AnebClient.forLoadedResponsiveness(null).uploadObservedThroughput(
                    server.url, 33_554_432L, 65_536, trace,
                ) { _, _ -> sawBytes.countDown() }
            }
            assertTrue("client did not write body bytes", sawBytes.await(5, TimeUnit.SECONDS))
            job.cancel(ObservedTransferCancellation("phase_deadline"))
            job.join()

            assertTrue("written client bytes must survive cancelled continuation", trace.snapshot().observedBytes > 0L)
            withTimeout(2_000L) { trace.awaitDrained() }
            val receipt = trace.snapshot()
            assertNotNull(receipt.bodyStartedUs)
            assertNull(receipt.httpCode)
            assertNull(receipt.headersUs)
            assertNull(receipt.bodyCompletedUs)
            assertEquals("cancelled", receipt.outcome)
            assertEquals("phase_deadline", receipt.cancelCause)
            assertNull(receipt.failure)
            assertTrue(server.closed.await(3, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `real download cancelled at controller deadline preserves headers bytes and callback closure without EOF`() = runBlocking {
        val ticks = AtomicLong(1_000_000L)
        val trace = ThroughputObservation("download-0-0", 0, "download", 33_554_432L) { ticks.incrementAndGet() }
        val sawBytes = CountDownLatch(1)
        PartialDownloadServer().use { server ->
            val client = AnebClient.forLoadedResponsiveness(null)
            val job = launch(Dispatchers.IO) {
                client.downloadObservedThroughput(server.url, trace) { _, _ -> sawBytes.countDown() }
            }
            assertTrue("download body was not observed", sawBytes.await(5, TimeUnit.SECONDS))
            job.cancel(ObservedTransferCancellation("phase_deadline"))
            job.join()

            assertNotNull("real response headers must survive cancelled continuation", trace.snapshot().headersUs)
            withTimeout(2_000L) { trace.awaitDrained() }
            val receipt = trace.snapshot()
            assertEquals(200, receipt.httpCode)
            assertTrue(receipt.observedBytes > 0L)
            assertNull(receipt.bodyCompletedUs)
            assertEquals("cancelled", receipt.outcome)
            assertEquals("phase_deadline", receipt.cancelCause)
            assertNotNull(receipt.cancelInvokedUs)
            assertNotNull(receipt.cancelReturnedUs)
            assertNotNull(receipt.callbackFinishedUs)
            assertNull(receipt.failure)
            assertTrue("cancel must close the actual connection", server.closed.await(3, TimeUnit.SECONDS))
        }
    }
}

/** Accepts an upload but never sends HTTP response headers. */
private class PendingUploadServer : AutoCloseable {
    private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    @Volatile private var connection: Socket? = null
    val closed = CountDownLatch(1)
    val url = "http://127.0.0.1:${listener.localPort}/api/v1/upload?run=fixture-upload"
    private val worker = thread(isDaemon = true, name = "observed-upload-fixture") {
        try {
            listener.accept().use { socket ->
                connection = socket
                socket.soTimeout = 5_000
                val input = socket.getInputStream()
                val block = ByteArray(8_192)
                while (input.read(block) != -1) { /* consume, but do not certify remote success */ }
            }
        } catch (_: java.io.IOException) {
            // Closing this test's socket is also valid fixture cleanup.
        } finally {
            closed.countDown()
        }
    }

    override fun close() {
        connection?.close()
        listener.close()
        worker.join(3_000L)
    }
}

/** Finite loopback fixture, not an ANEB node or a research sample. */
private class PartialDownloadServer : AutoCloseable {
    private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    @Volatile private var connection: Socket? = null
    val closed = CountDownLatch(1)
    val url = "http://127.0.0.1:${listener.localPort}/api/v1/download?bytes=33554432&chunk_kb=256"
    private val worker = thread(isDaemon = true, name = "observed-throughput-fixture") {
        try {
            listener.accept().use { socket ->
                connection = socket
                socket.soTimeout = 5_000
                val input = socket.getInputStream()
                val reader = input.bufferedReader(Charsets.US_ASCII)
                while (!reader.readLine().isNullOrEmpty()) { /* request headers only */ }
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: 33554432\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    write(ByteArray(16_384))
                    flush()
                }
                while (input.read() != -1) { /* wait for the client's real cancellation */ }
            }
        } catch (_: java.io.IOException) {
            // Test-side cleanup may close the fixture while an assertion unwinds.
        } finally {
            closed.countDown()
        }
    }

    override fun close() {
        connection?.close()
        listener.close()
        worker.join(3_000L)
    }
}
