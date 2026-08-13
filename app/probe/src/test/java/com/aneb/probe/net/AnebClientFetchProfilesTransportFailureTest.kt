package com.aneb.probe.net

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AnebClientFetchProfilesTransportFailureTest {
    @Test
    fun `fetchProfiles contains ordinary IO failure without retaining exception text`() = runBlocking {
        val marker = "IO_FAILURE_SENTINEL endpoint=https://private.example.invalid/api/v1/profiles"
        val interceptorCalls = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor {
                interceptorCalls.incrementAndGet()
                throw IOException(marker)
            }
            .build()

        val result = AnebClient(client, monotonicNanos = { 0L })
            .fetchProfiles("https://profiles.example.test/api/v1/profiles")

        assertEquals(1, interceptorCalls.get())
        assertNull(result.httpCode)
        assertNull(result.body)
        assertEquals("TRANSPORT_FAILED transport=IO", result.error)
        assertFalse(result.toString().contains(marker))
        assertFalse(result.toString().contains("private.example.invalid"))
        assertFalse(result.toString().contains("IOException"))
        assertFalse(result.toString().contains("java."))
        Unit
    }

    @Test
    fun `fetchProfiles contains local client failure without retaining exception text`() = runBlocking {
        val marker = "LOCAL_FAILURE_SENTINEL endpoint=https://private.example.invalid/api/v1/profiles"
        val interceptorCalls = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                interceptorCalls.incrementAndGet()
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        object : okhttp3.ResponseBody() {
                            override fun contentType(): okhttp3.MediaType? = null
                            override fun contentLength(): Long = -1L
                            override fun source(): okio.BufferedSource = throw IllegalStateException(marker)
                        },
                    )
                    .build()
            }
            .build()

        val result = AnebClient(client, monotonicNanos = { 0L })
            .fetchProfiles("https://profiles.example.test/api/v1/profiles")

        assertEquals(1, interceptorCalls.get())
        assertNull(result.httpCode)
        assertNull(result.body)
        assertEquals("CONTROL_PLANE_FAILED transport=LOCAL", result.error)
        assertFalse(result.toString().contains(marker))
        assertFalse(result.toString().contains("private.example.invalid"))
        assertFalse(result.toString().contains("IllegalStateException"))
        assertFalse(result.toString().contains("java."))
        Unit
    }
}
