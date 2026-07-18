package com.aneb.probe.engine

import com.aneb.probe.net.ANEB_AUDIT_ROLE_HEADER
import com.aneb.probe.net.ANEB_RUN_ID_HEADER
import com.aneb.probe.net.AnebClient
import com.aneb.probe.net.TokenSimTaskPlan
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class TokenRunIdTransportIntegrationTest {
    @Test
    fun `real token transport sends one identical run id across all primitives and concurrent echo`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = tokenPrimitiveDispatcher()
            val ticks = AtomicLong(10_000_000L)
            val client = AnebClient(
                OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
                monotonicNanos = { ticks.addAndGet(1_000_000L) },
            )
            val runId = "019f731f-602a-72b3-abeb-85afa315e0f0"
            val transport = AnebTokenExecutionTransport(client, runId)

            val serverInfo = transport.fetchServerInfo(server.url("/api/v1/serverinfo").toString())
            val echoes = coroutineScope {
                List(CONCURRENT_ECHOES) {
                    async { transport.echo(server.url("/api/v1/echo").toString()) }
                }.awaitAll()
            }
            val token = transport.tokenSim(
                url = server.url("/api/v1/token-sim").toString(),
                plan = tokenPlan(),
                uploadChunkBytes = 8,
                uploadChunkCadenceMs = 0.0,
                onUploadBytes = { _, _ -> },
                onPrelude = { _, _ -> },
                onToken = {},
            )
            val download = transport.downloadThroughput(
                server.url("/api/v1/download?bytes=32").toString(),
            ) { _, _ -> }

            assertEquals(200, serverInfo.httpCode)
            assertTrue(echoes.all { it.httpCode == 200 && it.error == null })
            assertTrue(token.completed)
            assertEquals(32L, download.totalBytes)

            val requests = List(CONCURRENT_ECHOES + 3) {
                requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)) { "missing request $it" }
            }
            requests.forEach { request ->
                assertEquals(listOf(runId), request.headers.values(ANEB_RUN_ID_HEADER))
            }
            val capabilityRequest = requests.single {
                it.requestUrl?.encodedPath == "/api/v1/serverinfo"
            }
            assertEquals(
                listOf("capability"),
                capabilityRequest.headers.values(ANEB_AUDIT_ROLE_HEADER),
            )
            requests.filterNot { it === capabilityRequest }.forEach { businessRequest ->
                assertNull(businessRequest.getHeader(ANEB_AUDIT_ROLE_HEADER))
            }
            assertEquals(
                mapOf(
                    "/api/v1/serverinfo" to 1,
                    "/api/v1/echo" to CONCURRENT_ECHOES,
                    "/api/v1/token-sim" to 1,
                    "/api/v1/download" to 1,
                ),
                requests.groupingBy { it.requestUrl?.encodedPath }.eachCount(),
            )
        }
    }

    @Test
    fun `direct non token client calls keep all primitive requests unscoped`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = tokenPrimitiveDispatcher()
            val ticks = AtomicLong(20_000_000L)
            val client = AnebClient(
                OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
                monotonicNanos = { ticks.addAndGet(1_000_000L) },
            )

            val serverInfo = client.fetchServerInfo(server.url("/api/v1/serverinfo").toString())
            val echo = client.echo(server.url("/api/v1/echo").toString())
            val token = client.tokenSim(
                url = server.url("/api/v1/token-sim").toString(),
                plan = tokenPlan(),
                uploadChunkBytes = 8,
                uploadChunkCadenceMs = 0.0,
            )
            val download = client.downloadThroughput(
                server.url("/api/v1/download?bytes=32").toString(),
            ) { _, _ -> }

            assertEquals(200, serverInfo.httpCode)
            assertEquals(200, echo.httpCode)
            assertTrue(token.completed)
            assertEquals(32L, download.totalBytes)
            repeat(4) {
                val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)) { "missing request $it" }
                assertNull(request.getHeader(ANEB_RUN_ID_HEADER))
                assertNull(request.getHeader(ANEB_AUDIT_ROLE_HEADER))
            }
        }
    }

    private fun tokenPrimitiveDispatcher(): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
            "/api/v1/serverinfo" -> MockResponse().setResponseCode(200).setBody("{}")
            "/api/v1/echo" -> MockResponse().setResponseCode(200).setBody(
                """{"t1_us":1000,"t2_us":1100,"observed":"mock"}""",
            )
            "/api/v1/token-sim" -> MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(tokenSse())
            "/api/v1/download" -> MockResponse().setResponseCode(200).setBody(
                Buffer().write(ByteArray(32) { it.toByte() }),
            )
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun tokenPlan() = TokenSimTaskPlan(
        taskId = "task-1",
        workloadKind = "text",
        seed = 7,
        processingMs = 0.0,
        uploadPayloadBytes = 16,
        tokenIntervalsMs = listOf(0.0),
        tokenSizesBytes = listOf(1),
    )

    private fun tokenSse(): String = """
        event: prelude
        data: {"contract_version":"aneb-token-task-v1","task_id":"task-1","workload_kind":"text","upload_bytes":16,"upload_recv_start_us":1,"upload_recv_end_us":2,"processing_start_us":2,"processing_deadline_us":2,"observed":"mock"}

        event: token
        data: {"seq":0,"sched_us":3,"pre_flush_us":3,"size_bytes":1,"payload":"x"}

        event: summary
        data: {"task_id":"task-1","tokens":1,"processing_ready_us":3,"flush_return_us":[3],"timer_late_us":[0],"flush_block_us":[0],"carryover_us":[0]}

    """.trimIndent() + "\n\n"

    private companion object {
        const val CONCURRENT_ECHOES = 8
    }
}
