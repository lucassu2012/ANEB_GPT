package com.aneb.probe.engine

import android.content.Context
import com.aneb.probe.net.AnebClient
import com.aneb.probe.net.MonotonicNanosClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class NetworkSpeedEngineV2Test {
    @Test
    fun `v2 download and upload phases without EOF before deadline yield valid loaded RTT summary`() = runBlocking {
        LoopbackLoadedServer().use { server ->
            val context: Context = RuntimeEnvironment.getApplication()
            val origin = System.nanoTime() - 1_000_000_000L
            val clockNanos: () -> Long = { System.nanoTime() - origin }
            val engine = NetworkSpeedEngine(context, clockNanos)
            val client = AnebClient.forLoadedResponsiveness(null, MonotonicNanosClock(clockNanos))
            val stages = mutableListOf<ResponsivenessStage>()

            val downPhase = ProfilePhase(
                type = ProfilePhase.TYPE_DOWNLOAD_THROUGHPUT,
                bytes = 33_554_432L,
                chunkKb = 64,
                parallel = 2,
                durationMs = 1_200,
            )
            val downSummary = engine.runTransferPhase(
                client = client,
                base = server.baseUrl,
                runId = "test-v2-run",
                phase = downPhase,
                direction = NetworkSpeedEngine.TransferDirection.DOWNLOAD,
                progressStart = 0.2,
                progressEnd = 0.5,
                onResponsivenessStage = { stages += it },
            )
            assertTrue("download errors should be empty: ${downSummary.errors}", downSummary.errors.isEmpty())
            assertTrue("download bytes should be positive", downSummary.totalBytes > 0L)

            val upPhase = ProfilePhase(
                type = ProfilePhase.TYPE_UPLOAD_THROUGHPUT,
                bytes = 33_554_432L,
                chunkKb = 64,
                parallel = 2,
                durationMs = 1_200,
            )
            val upSummary = engine.runTransferPhase(
                client = client,
                base = server.baseUrl,
                runId = "test-v2-run",
                phase = upPhase,
                direction = NetworkSpeedEngine.TransferDirection.UPLOAD,
                progressStart = 0.6,
                progressEnd = 0.9,
                onResponsivenessStage = { stages += it },
            )
            assertTrue("upload errors should be empty: ${upSummary.errors}", upSummary.errors.isEmpty())
            assertTrue("upload bytes should be positive", upSummary.totalBytes > 0L)

            val report = LoadedResponsivenessReport(
                runId = "test-v2-run",
                serverBase = server.baseUrl,
                transport = "wifi",
                status = "completed",
                stages = stages,
                methodVersion = "loaded-responsiveness-v2",
            )
            val encoded = LoadedResponsiveness.encode(report)
            val decoded = LoadedResponsiveness.decode(encoded)
            val summary = LoadedResponsiveness.summarize(decoded)

            val dlStage = summary.stages.single { it.stage == "download" }
            val ulStage = summary.stages.single { it.stage == "upload" }
            assertNull("download missingReason: ${dlStage.missingReason}, samples=${dlStage.sampleResults}", dlStage.missingReason)
            assertNull("upload missingReason: ${ulStage.missingReason}, samples=${ulStage.sampleResults}", ulStage.missingReason)
            assertTrue("download valid samples > 0", dlStage.validSampleCount > 0)
            assertTrue("upload valid samples > 0", ulStage.validSampleCount > 0)
            assertNotNull(summary.downloadP95Ms)
            assertNotNull(summary.uploadP95Ms)
            assertEquals("phase_deadline", decoded.stages[0].executionV2?.stopReason)
            assertEquals("phase_deadline", decoded.stages[1].executionV2?.stopReason)
            assertTrue(decoded.stages[0].executionV2!!.requests.all { it.callbackFinishedUs != null })
            assertTrue(decoded.stages[1].executionV2!!.requests.all { it.callbackFinishedUs != null })
        }
    }
}

private class LoopbackLoadedServer : AutoCloseable {
    private val listener = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    private val sockets = CopyOnWriteArrayList<Socket>()
    @Volatile private var closed = false
    val baseUrl = "http://127.0.0.1:${listener.localPort}"

    private val acceptThread = thread(isDaemon = true, name = "loopback-loaded-accept") {
        while (!closed) {
            val socket = try { listener.accept() } catch (_: IOException) { break }
            sockets += socket
            thread(isDaemon = true, name = "loopback-loaded-conn") { handle(socket) }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 5_000
                val inp = s.getInputStream()
                val out = s.getOutputStream()
                val reqLine = readLine(inp) ?: return
                var contentLength = 0L
                while (true) {
                    val hdr = readLine(inp) ?: break
                    if (hdr.isEmpty()) break
                    if (hdr.lowercase().startsWith("content-length:")) {
                        contentLength = hdr.substringAfter(":").trim().toLongOrNull() ?: 0L
                    }
                }
                when {
                    reqLine.contains("/api/v1/echo") -> {
                        if (contentLength > 0L) {
                            val drain = ByteArray(contentLength.toInt().coerceAtMost(4096))
                            var rem = contentLength
                            while (rem > 0L) {
                                val n = inp.read(drain, 0, minOf(drain.size.toLong(), rem).toInt())
                                if (n <= 0) break
                                rem -= n
                            }
                        }
                        // Simulate 25ms WAN RTT so in-flight download/upload bytes overlap each echo.
                        Thread.sleep(25L)
                        val nowUs = System.nanoTime() / 1_000L
                        val body = """{"t1_us":$nowUs,"t2_us":${nowUs + 50},"observed":"127.0.0.1:1"}""".toByteArray(Charsets.UTF_8)
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        out.write(body)
                        out.flush()
                    }
                    reqLine.startsWith("GET /api/v1/download") -> {
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: 33554432\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        val chunk = ByteArray(16_384)
                        while (!closed) {
                            out.write(chunk)
                            out.flush()
                            Thread.sleep(3L)
                        }
                    }
                    reqLine.startsWith("POST /api/v1/upload") -> {
                        val buf = ByteArray(8_192)
                        var rem = contentLength
                        while (rem > 0L && !closed) {
                            val n = inp.read(buf, 0, minOf(buf.size.toLong(), rem).toInt())
                            if (n <= 0) break
                            rem -= n
                            Thread.sleep(3L)
                        }
                    }
                    else -> {
                        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        out.flush()
                    }
                }
            }
        } catch (_: Exception) {
            // Expected on client deadline cancellation or server close
        }
    }

    private fun readLine(inp: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    override fun close() {
        closed = true
        runCatching { listener.close() }
        sockets.forEach { runCatching { it.close() } }
        acceptThread.join(2_000L)
    }
}
