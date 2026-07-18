package com.aneb.probe.net

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class ReachabilityRunIdHeaderTest {
    @Test
    fun `token reachability pair carries one identical run id per serverinfo request`() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setResponseCode(200)) }
            val clock = AtomicLong(1_000_000L)
            val probe = ReachabilityProbe { clock.addAndGet(1_000_000L) }
            val runId = "019f731f-602a-72b3-abeb-85afa315e0f0"

            val result = probe.probeDual(
                server.url("/").toString(),
                server.url("/").toString(),
                runId,
            )

            assertEquals("ok", result.sni.status)
            assertEquals("ok", result.ip.status)
            repeat(2) {
                val request = server.takeRequest()
                assertEquals("/api/v1/serverinfo", request.path)
                assertEquals(listOf(runId), request.headers.values(ANEB_RUN_ID_HEADER))
                assertEquals(listOf("reachability"), request.headers.values(ANEB_AUDIT_ROLE_HEADER))
            }
        }
    }

    @Test
    fun `legacy reachability pair remains unscoped when run id is omitted`() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setResponseCode(200)) }
            val clock = AtomicLong(1_000_000L)
            val probe = ReachabilityProbe { clock.addAndGet(1_000_000L) }

            probe.probeDual(server.url("/").toString(), server.url("/").toString())

            repeat(2) {
                val request = server.takeRequest()
                assertNull(request.getHeader(ANEB_RUN_ID_HEADER))
                assertNull(request.getHeader(ANEB_AUDIT_ROLE_HEADER))
            }
        }
    }
}
