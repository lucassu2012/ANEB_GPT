package com.aneb.probe.net

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * 接线层守卫：通过真实 loopback TCP 请求证明账本被 [AnebClient.executeCancellable] 逐样本调用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AnebClientProtocolLogTest {

    private val serverHeaderLiteral = "X-Aneb-Proto"

    private data class PlannedResponse(
        val code: Int,
        val protoHeader: String? = null,
        val body: String = "{}",
        val disconnectMidBody: Boolean = false,
    )

    private lateinit var server: LoopbackHttpServer

    @Before
    fun setUp() {
        server = LoopbackHttpServer(serverHeaderLiteral)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `一次真实请求 → 账本记下 http11 与服务端证据头`() {
        val log = NegotiatedProtocolLog()
        val client = AnebClient.createForLoopbackTest(AndroidMonotonicNanosClock, protocolLog = log)
        server.enqueue(PlannedResponse(code = 200, protoHeader = "HTTP/1.1;via=tcp", body = "{}"))

        val r = runBlocking { client.fetchProfiles(server.url("/api/v1/profiles")) }
        assertEquals(200, r.httpCode)

        val s = log.snapshot()
        assertEquals("一次请求＝一条样本", 1L, s.samples)
        assertEquals(mapOf("http/1.1" to 1L), s.byProtocol)
        assertEquals(mapOf("HTTP/1.1;via=tcp" to 1L), s.byProtoHeader)
        assertEquals(0L, s.headerAbsent)
    }

    @Test
    fun `服务端不发证据头 → 记为缺席而不是编一个值`() {
        val log = NegotiatedProtocolLog()
        val client = AnebClient.createForLoopbackTest(AndroidMonotonicNanosClock, protocolLog = log)
        server.enqueue(PlannedResponse(code = 200, protoHeader = null, body = "{}"))

        runBlocking { client.fetchProfiles(server.url("/api/v1/profiles")) }

        val s = log.snapshot()
        assertEquals(1L, s.samples)
        assertEquals("头缺席不得进分表（R-10：无事件不造数）", emptyMap<String, Long>(), s.byProtoHeader)
        assertEquals(1L, s.headerAbsent)
    }

    @Test
    fun `非2xx 也要记 —— 失败样本恰恰最该看协议`() {
        val log = NegotiatedProtocolLog()
        val client = AnebClient.createForLoopbackTest(AndroidMonotonicNanosClock, protocolLog = log)
        server.enqueue(PlannedResponse(code = 500, protoHeader = "HTTP/1.1;via=tcp", body = "boom"))

        runBlocking { client.fetchProfiles(server.url("/api/v1/profiles")) }
        assertEquals(1L, log.snapshot().samples)
    }

    @Test
    fun `响应体读取中途断开 → 仍记一条（观测点在 try 之外）`() {
        val log = NegotiatedProtocolLog()
        val client = AnebClient.createForLoopbackTest(AndroidMonotonicNanosClock, protocolLog = log)
        server.enqueue(
            PlannedResponse(
                code = 200,
                protoHeader = "HTTP/1.1;via=tcp",
                body = "x".repeat(4096),
                disconnectMidBody = true,
            ),
        )

        val r = runBlocking { client.fetchProfiles(server.url("/api/v1/profiles")) }
        assertTrue("本例要的就是一次读体失败", r.error != null || r.body == null)
        assertEquals("响应头已到手 ⇒ 协议已协商完 ⇒ 这条样本必须在账本里", 1L, log.snapshot().samples)
    }

    @Test
    fun `多次请求累加 —— 账本按 client 实例持有`() {
        val log = NegotiatedProtocolLog()
        val client = AnebClient.createForLoopbackTest(AndroidMonotonicNanosClock, protocolLog = log)
        repeat(3) {
            server.enqueue(PlannedResponse(code = 200, protoHeader = "HTTP/1.1;via=tcp", body = "{}"))
        }
        repeat(3) { runBlocking { client.fetchProfiles(server.url("/api/v1/profiles")) } }
        assertEquals(3L, log.snapshot().samples)
    }

    @Test
    fun `不传账本时 client 自带一本 —— 默认构造器与自定义账本均可用`() {
        val defaultClient = AnebClient()
        assertEquals(0L, defaultClient.protocolLog.snapshot().samples)

        val customLog = NegotiatedProtocolLog()
        val customClient = AnebClient(protocolLog = customLog)
        assertEquals(0L, customClient.protocolLog.snapshot().samples)

        val loopbackClient = AnebClient.createForLoopbackTest(AndroidMonotonicNanosClock)
        server.enqueue(PlannedResponse(code = 200, body = "{}"))
        runBlocking { loopbackClient.fetchProfiles(server.url("/api/v1/profiles")) }
        assertEquals(1L, loopbackClient.protocolLog.snapshot().samples)
    }

    @Test
    fun `头名与服务端源码逐字一致`() {
        var cur: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var go: File? = null
        while (cur != null && go == null) {
            val c = File(cur, "server/h3.go")
            if (c.isFile) go = c
            cur = cur.parentFile
        }
        assertTrue("找不到 server/h3.go", go != null)
        val src = go!!.readText()
        assertTrue(
            "server/h3.go 未以字面量 \"$serverHeaderLiteral\" 设置该头",
            src.contains("Set(\"$serverHeaderLiteral\""),
        )
        assertEquals(
            "Kotlin 常量与服务端字面量必须逐字相同",
            serverHeaderLiteral,
            AnebClient.PROTO_EVIDENCE_HEADER,
        )
    }

    @Test
    fun `空白 X-Aneb-Proto 头归一为 headerAbsent 且不进分表`() {
        val log = NegotiatedProtocolLog()
        val client = AnebClient.createForLoopbackTest(AndroidMonotonicNanosClock, protocolLog = log)
        server.enqueue(PlannedResponse(code = 200, protoHeader = "", body = "{}"))
        server.enqueue(PlannedResponse(code = 200, protoHeader = "   ", body = "{}"))

        runBlocking {
            client.fetchProfiles(server.url("/api/v1/profiles"))
            client.fetchProfiles(server.url("/api/v1/profiles"))
        }

        val s = log.snapshot()
        assertEquals(2L, s.samples)
        assertEquals(2L, s.headerAbsent)
        assertEquals(emptyMap<String, Long>(), s.byProtoHeader)
    }

    @Test
    fun `createForLoopbackTest 拒绝非 loopback 明文地址走 IOException 不触发未捕获异常`() {
        val client = AnebClient.createForLoopbackTest(AndroidMonotonicNanosClock)
        val uncaught = ConcurrentLinkedQueue<Throwable>()
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.add(e) }
        try {
            val r1 = runBlocking { client.fetchProfiles("http://127.0.0.2:18080/api/v1/profiles") }
            val r2 = runBlocking { client.fetchProfiles("http://localhost:18080/api/v1/profiles") }
            assertEquals(null, r1.httpCode)
            assertEquals(null, r2.httpCode)
            assertTrue(r1.error?.contains("loopback_test_http_only") == true)
            assertTrue(r2.error?.contains("loopback_test_http_only") == true)
            assertTrue("must not trigger Thread.UncaughtExceptionHandler: $uncaught", uncaught.isEmpty())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(prevHandler)
        }
    }

    private class LoopbackHttpServer(private val headerName: String) : AutoCloseable {
        private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val queue = ConcurrentLinkedQueue<PlannedResponse>()
        @Volatile private var closed = false
        private val worker = thread(isDaemon = true, name = "protocol-log-loopback") {
            while (!closed) {
                val socket = try {
                    listener.accept()
                } catch (_: Exception) {
                    break
                }
                try {
                    socket.use { s ->
                        s.soTimeout = 5_000
                        val reader = s.getInputStream().bufferedReader(Charsets.US_ASCII)
                        while (!reader.readLine().isNullOrEmpty()) { /* drain request headers */ }
                        val planned = queue.poll() ?: PlannedResponse(code = 500, body = "unexpected")
                        val bodyBytes = planned.body.toByteArray(Charsets.UTF_8)
                        val statusText = if (planned.code == 200) "OK" else "Error"
                        val headers = buildString {
                            append("HTTP/1.1 ${planned.code} $statusText\r\n")
                            append("Content-Type: application/json\r\n")
                            append("Content-Length: ${bodyBytes.size}\r\n")
                            if (planned.protoHeader != null) {
                                append("$headerName: ${planned.protoHeader}\r\n")
                            }
                            append("Connection: close\r\n\r\n")
                        }.toByteArray(Charsets.US_ASCII)
                        val out = s.getOutputStream()
                        out.write(headers)
                        if (planned.disconnectMidBody) {
                            out.write(bodyBytes, 0, minOf(256, bodyBytes.size))
                            out.flush()
                            // socket.use immediately closes connection before sending remaining Content-Length bytes
                        } else {
                            out.write(bodyBytes)
                            out.flush()
                        }
                    }
                } catch (_: Exception) {
                    // Ignore transient socket errors during teardown.
                }
            }
        }

        fun enqueue(resp: PlannedResponse) {
            queue.add(resp)
        }

        fun url(path: String): String = "http://127.0.0.1:${listener.localPort}$path"

        override fun close() {
            closed = true
            runCatching { listener.close() }
            worker.join(3_000L)
        }
    }
}
