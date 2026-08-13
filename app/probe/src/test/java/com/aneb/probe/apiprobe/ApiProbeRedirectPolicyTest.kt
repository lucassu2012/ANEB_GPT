package com.aneb.probe.apiprobe

import com.aneb.probe.data.AnebDatabase
import com.aneb.probe.data.ApiProbeResultEntity
import com.aneb.probe.privacy.LocalPrivacyOperationGate
import com.aneb.probe.privacy.LocalPrivacyOwnedRunExecutionBoundary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ApiProbeRedirectPolicyTest {
    @Test
    fun `cross origin redirect is rejected without contacting target or retaining metadata`() = runBlocking {
        val locationSentinel = "LOCATION_SENTINEL_PRIVATE_REDIRECT"
        val bodySentinel = "BODY_SENTINEL_PRIVATE_REDIRECT"
        val apiKeySentinel = "X_API_KEY_SENTINEL_PRIVATE_REDIRECT"
        val modelSentinel = "MODEL_SENTINEL_PRIVATE_REDIRECT"
        val origin = MockWebServer()
        val target = MockWebServer()
        val context = RuntimeEnvironment.getApplication().applicationContext
        val database = AnebDatabase.get(context)

        try {
            origin.start()
            target.start()
            val targetUrl = target.url("/collect?metadata=$locationSentinel").toString()
            origin.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", targetUrl)
                    .setBody("$bodySentinel redirect response"),
            )
            target.enqueue(MockResponse().setResponseCode(400).setBody("target must remain untouched"))

            withContext(Dispatchers.IO) {
                database.openHelper.writableDatabase.execSQL("DELETE FROM api_probe_result")
                val logs = mutableListOf<String>()
                val result = withTimeout(20_000) {
                    ApiProbe(
                        context = context,
                        runBoundary = LocalPrivacyOwnedRunExecutionBoundary(
                            LocalPrivacyOperationGate(),
                        ),
                    ).run(
                        config = ApiProbe.Config(
                            provider = LlmProvider.ANTHROPIC,
                            baseUrl = origin.url("").toString(),
                            model = modelSentinel,
                            apiKey = apiKeySentinel,
                        ),
                        log = logs::add,
                    )
                }
                val persisted = database.apiProbeResultDao().recent(1).single()
                val originRequest = origin.takeRequest(1, TimeUnit.SECONDS)

                assertNotNull(originRequest)
                assertEquals("POST", originRequest?.method)
                assertEquals("/v1/messages", originRequest?.path)
                assertEquals(1, origin.requestCount)
                assertEquals(0, target.requestCount)

                assertRedirectRejected(result)
                assertRedirectRejected(persisted)
                val observable = listOf(
                    logs.joinToString("\n"),
                    result.toString(),
                    persisted.toString(),
                )
                val forbidden = listOf(
                    locationSentinel,
                    bodySentinel,
                    apiKeySentinel,
                    modelSentinel,
                    targetUrl,
                    target.hostName,
                    target.port.toString(),
                )
                observable.forEach { surface ->
                    forbidden.forEach { token ->
                        assertFalse("redirect metadata escaped: $token", surface.contains(token))
                    }
                }
                assertTrue(logs.any { it.contains("result_code=HTTP_REJECTED") })
                assertTrue(logs.any { it.contains("transport_class=HTTP") })
                assertTrue(logs.any { it.contains("completion_class=FAILED") })
            }
        } finally {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (database.isOpen) {
                        database.openHelper.writableDatabase.execSQL("DELETE FROM api_probe_result")
                    }
                }
            }
            runCatching { origin.shutdown() }
            runCatching { target.shutdown() }
        }
    }

    private fun assertRedirectRejected(result: ApiProbeResultEntity) {
        assertEquals(302, result.httpCode)
        assertEquals("HTTP_REJECTED", result.resultCode)
        assertEquals("HTTP", result.transportClass)
        assertEquals("FAILED", result.completionClass)
        assertEquals(0L, result.errorBodyByteCount)
        assertNull(result.errorBodySha256)
        assertEquals("NONE", result.errorBodyDigestScope)
        assertFalse(result.errorBodyTruncated)
    }
}
