package com.aneb.probe.net

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AnebClientRedirectPolicyTest {
    @Test
    fun `fetchProfiles rejects 307 without following or reading redirect metadata or body`() = runBlocking {
        val locationSentinel = "REDIRECT_LOCATION_SENTINEL_307"
        val originBodySentinel = "REDIRECT_ORIGIN_BODY_SENTINEL_307"
        val targetBodySentinel = "REDIRECT_TARGET_BODY_SENTINEL_307"
        val origin = MockWebServer()
        val target = MockWebServer()

        try {
            origin.start()
            target.start()
            val redirectTarget = target.url("/$locationSentinel")
            origin.enqueue(
                MockResponse()
                    .setResponseCode(307)
                    .setHeader("Location", redirectTarget)
                    .setBody(originBodySentinel)
                    .setBodyDelay(5, TimeUnit.SECONDS),
            )
            target.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(targetBodySentinel),
            )

            val result = withTimeout(2_000) {
                AnebClient().fetchProfiles(origin.url("/api/v1/profiles").toString())
            }

            assertEquals(307, result.httpCode)
            assertNull(result.body)
            assertEquals("http 307", result.error)
            assertEquals(1, origin.requestCount)
            assertEquals(0, target.requestCount)
            val rendered = result.toString()
            assertEquals(0, occurrences(rendered, locationSentinel))
            assertEquals(0, occurrences(rendered, originBodySentinel))
            assertEquals(0, occurrences(rendered, targetBodySentinel))
        } finally {
            runCatching { origin.shutdown() }
            runCatching { target.shutdown() }
        }
    }

    @Test
    fun `fetchProfiles rejects 308 without following or reading redirect metadata or body`() = runBlocking {
        val locationSentinel = "REDIRECT_LOCATION_SENTINEL_308"
        val originBodySentinel = "REDIRECT_ORIGIN_BODY_SENTINEL_308"
        val targetBodySentinel = "REDIRECT_TARGET_BODY_SENTINEL_308"
        val origin = MockWebServer()
        val target = MockWebServer()

        try {
            origin.start()
            target.start()
            val redirectTarget = target.url("/$locationSentinel")
            origin.enqueue(
                MockResponse()
                    .setResponseCode(308)
                    .setHeader("Location", redirectTarget)
                    .setBody(originBodySentinel)
                    .setBodyDelay(5, TimeUnit.SECONDS),
            )
            target.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(targetBodySentinel),
            )

            val result = withTimeout(2_000) {
                AnebClient().fetchProfiles(origin.url("/api/v1/profiles").toString())
            }

            assertEquals(308, result.httpCode)
            assertNull(result.body)
            assertEquals("http 308", result.error)
            assertEquals(1, origin.requestCount)
            assertEquals(0, target.requestCount)
            val rendered = result.toString()
            assertEquals(0, occurrences(rendered, locationSentinel))
            assertEquals(0, occurrences(rendered, originBodySentinel))
            assertEquals(0, occurrences(rendered, targetBodySentinel))
        } finally {
            runCatching { origin.shutdown() }
            runCatching { target.shutdown() }
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
