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
class AnebClientResultUploadTest {
    @Test
    fun `submitResult sends bearer and idempotency key without consuming response body`() = runBlocking {
        val token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val runId = "123e4567-e89b-47d3-a456-426614174000"
        val jsonBody = """{"schema_version":"aneb-result-v2","run":{"run_id":"$runId"}}"""
        val responseBodySentinel = "RESULT_UPLOAD_RESPONSE_BODY_SENTINEL"
        val server = MockWebServer()

        try {
            server.start()
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(responseBodySentinel)
                    .setBodyDelay(5, TimeUnit.SECONDS),
            )

            val result = withTimeout(2_000) {
                AnebClient().submitResult(
                    url = server.url("/api/v1/results").toString(),
                    jsonBody = jsonBody,
                    bearerToken = token,
                    idempotencyKey = runId,
                )
            }

            assertEquals(200, result.httpCode)
            assertNull(result.body)
            assertNull(result.error)
            assertEquals(1, server.requestCount)
            val request = server.takeRequest(1, TimeUnit.SECONDS)
            assertNotNull(request)
            assertEquals("POST", request?.method)
            assertEquals("Bearer $token", request?.getHeader("Authorization"))
            assertEquals(runId, request?.getHeader("Idempotency-Key"))
            assertEquals(jsonBody, request?.body?.readUtf8())
            assertEquals(0, result.toString().countOccurrences(token))
            assertEquals(0, result.toString().countOccurrences(responseBodySentinel))
        } finally {
            runCatching { server.shutdown() }
        }
    }

    private fun String.countOccurrences(needle: String): Int {
        var count = 0
        var fromIndex = 0
        while (true) {
            val next = indexOf(needle, fromIndex)
            if (next < 0) return count
            count += 1
            fromIndex = next + needle.length
        }
    }
}
