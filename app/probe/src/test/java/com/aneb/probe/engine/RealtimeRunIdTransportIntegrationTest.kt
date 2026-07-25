package com.aneb.probe.engine

import com.aneb.probe.net.ANEB_AUDIT_ROLE_HEADER
import com.aneb.probe.net.ANEB_AUDIT_SCOPE_HEADER
import com.aneb.probe.net.ANEB_RUN_ID_HEADER
import com.aneb.probe.net.AnebAuditScope
import com.aneb.probe.net.AnebClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeRunIdTransportIntegrationTest {
    @Test
    fun `realtime capability echo and websocket share exact run id and audit scope`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"t1_us":1000,"t2_us":1100,"observed":"mock"}"""),
            )
            server.enqueue(
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}),
            )
            val ticks = AtomicLong(10_000_000L)
            val client = AnebClient(
                OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
                monotonicNanos = { ticks.addAndGet(1_000_000L) },
            )
            val opened = CountDownLatch(1)
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.countDown()
                }
            }

            val serverInfo = AnebRealtimeExecutionTransport(client, RUN_ID).fetchServerInfo(
                server.url("/api/v1/serverinfo").toString(),
            )
            val echo = client.echo(
                url = server.url("/api/v1/echo").toString(),
                runId = RUN_ID,
                auditScope = AnebAuditScope.REALTIME_RUN,
            )
            val socket = client.openWebSocket(
                url = server.url("/api/v1/realtime-sim").toString(),
                listener = listener,
                runId = RUN_ID,
                auditScope = AnebAuditScope.REALTIME_RUN,
            )

            assertEquals(200, serverInfo.httpCode)
            assertEquals(200, echo.httpCode)
            assertTrue(opened.await(2, TimeUnit.SECONDS))
            socket.cancel()

            val requests = List(3) {
                requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)) { "missing request $it" }
            }
            requests.forEach { request ->
                assertEquals(listOf(RUN_ID), request.headers.values(ANEB_RUN_ID_HEADER))
                assertEquals(
                    listOf("realtime_run"),
                    request.headers.values(ANEB_AUDIT_SCOPE_HEADER),
                )
            }
            val capability = requests.single {
                it.requestUrl?.encodedPath == "/api/v1/serverinfo"
            }
            assertEquals("capability", capability.getHeader(ANEB_AUDIT_ROLE_HEADER))
            requests.filterNot { it === capability }.forEach {
                assertNull(it.getHeader(ANEB_AUDIT_ROLE_HEADER))
            }
            assertEquals(
                setOf("/api/v1/serverinfo", "/api/v1/echo", "/api/v1/realtime-sim"),
                requests.mapNotNull { it.requestUrl?.encodedPath }.toSet(),
            )
        }
    }

    private companion object {
        const val RUN_ID = "00000000-0000-7000-8000-000000000192"
    }
}
