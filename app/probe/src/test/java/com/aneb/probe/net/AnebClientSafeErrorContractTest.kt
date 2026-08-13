package com.aneb.probe.net

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AnebClientSafeErrorContractTest {
    @Test
    fun `result submit 400 exposes status only without consuming response body`() = runBlocking {
        val sentinel = "POST_BODY_SENTINEL_400"
        val server = MockWebServer()

        try {
            server.start()
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setBody(sentinel)
                    .setBodyDelay(5, TimeUnit.SECONDS),
            )

            val result = withTimeout(2_000) {
                AnebClient().submitResult(
                    url = server.url("/api/v1/results").toString(),
                    jsonBody = "{}",
                    bearerToken = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    idempotencyKey = "123e4567-e89b-47d3-a456-426614174000",
                )
            }

            assertEquals(1, server.requestCount)
            val request = server.takeRequest(1, TimeUnit.SECONDS)
            assertNotNull(request)
            assertEquals("POST", request?.method)
            assertEquals(400, result.httpCode)
            assertNull(result.body)
            assertEquals("http 400", result.error)
            assertEquals(0, occurrences(result.toString(), sentinel))
        } finally {
            runCatching { server.shutdown() }
        }
    }

    private fun occurrences(text: String, needle: String): Int {
        var count = 0
        var fromIndex = 0
        while (true) {
            val next = text.indexOf(needle, fromIndex)
            if (next < 0) return count
            count += 1
            fromIndex = next + needle.length
        }
    }
}
