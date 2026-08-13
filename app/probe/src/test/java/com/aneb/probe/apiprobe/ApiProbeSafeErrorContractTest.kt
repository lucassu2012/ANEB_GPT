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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ApiProbeSafeErrorContractTest {
    @Test
    fun `non-2xx response body is absent from durable and observable sinks`() = runBlocking {
        val sentinel = "RAW_BODY_SENTINEL_EMAIL_alice.b2@example.invalid"
        val apiKey = "sk-test-only-key"
        val server = MockWebServer()
        val context = RuntimeEnvironment.getApplication().applicationContext
        val database = AnebDatabase.get(context)

        try {
            server.start()
            withContext(Dispatchers.IO) {
                database.openHelper.writableDatabase.execSQL("DELETE FROM api_probe_result")
                val baseUrl = server.url("/v1").toString()
                val portText = server.port.toString()
                server.enqueue(
                    MockResponse()
                        .setResponseCode(400)
                        .setHeader("Content-Type", "application/json")
                        .setBody("$sentinel rejected request"),
                )
                val logs = mutableListOf<String>()
                val probe = ApiProbe(
                    context = context,
                    runBoundary = LocalPrivacyOwnedRunExecutionBoundary(
                        LocalPrivacyOperationGate(),
                    ),
                )

                val returned = probe.run(
                    config = ApiProbe.Config(
                        provider = LlmProvider.OPENAI_COMPAT,
                        baseUrl = baseUrl,
                        model = "test-model",
                        apiKey = apiKey,
                    ),
                    log = logs::add,
                )
                val persisted = database.apiProbeResultDao().recent(1).single()
                val joinedLogs = logs.joinToString("\n")
                val forbidden = listOf(
                    sentinel,
                    "Exception",
                    "java.",
                    "127.0.0.1",
                    "localhost",
                    portText,
                    baseUrl,
                    "test-model",
                    apiKey,
                )

                assertTypedFailure(
                    returned,
                    httpCode = 400,
                    resultCode = "HTTP_REJECTED",
                    transportClass = "HTTP",
                )
                assertTypedFailure(
                    persisted,
                    httpCode = 400,
                    resultCode = "HTTP_REJECTED",
                    transportClass = "HTTP",
                )
                assertTrue(joinedLogs.contains("result_code=HTTP_REJECTED"))
                assertTrue(joinedLogs.contains("transport_class=HTTP"))
                assertTrue(joinedLogs.contains("completion_class=FAILED"))
                assertForbiddenAbsent(joinedLogs, forbidden)
                assertForbiddenAbsent(returned.toString(), forbidden)
                assertForbiddenAbsent(persisted.toString(), forbidden)
                assertEquals(1, server.requestCount)
            }
        } finally {
            runCatching { server.shutdown() }
            clearApiProbeRowsIfOpen(database)
        }
    }

    @Test
    fun `ordinary transport failure exposes only a stable descriptor in durable and observable sinks`() =
        runBlocking {
            val apiKey = "sk-test-only-key"
            val server = MockWebServer()
            val context = RuntimeEnvironment.getApplication().applicationContext
            val database = AnebDatabase.get(context)

            try {
                server.start()
                val baseUrl = server.url("/v1").toString()
                val portText = server.port.toString()
                server.shutdown()
                withContext(Dispatchers.IO) {
                    database.openHelper.writableDatabase.execSQL("DELETE FROM api_probe_result")
                    val logs = mutableListOf<String>()
                    val probe = ApiProbe(
                        context = context,
                        runBoundary = LocalPrivacyOwnedRunExecutionBoundary(
                            LocalPrivacyOperationGate(),
                        ),
                    )

                    val returned = withTimeout(20_000) {
                        probe.run(
                            config = ApiProbe.Config(
                                provider = LlmProvider.OPENAI_COMPAT,
                                baseUrl = baseUrl,
                                model = "test-model",
                                apiKey = apiKey,
                            ),
                            log = logs::add,
                        )
                    }
                    val persisted = database.apiProbeResultDao().recent(1).single()
                    val joinedLogs = logs.joinToString("\n")
                    val forbidden = listOf(
                        "Exception",
                        "java.",
                        "127.0.0.1",
                        "localhost",
                        "127.0.0.1:$portText",
                        "localhost:$portText",
                        portText,
                        baseUrl,
                        "test-model",
                        apiKey,
                    )

                    assertTypedFailure(
                        returned,
                        httpCode = null,
                        resultCode = "TRANSPORT_FAILED",
                        transportClass = "IO",
                    )
                    assertTypedFailure(
                        persisted,
                        httpCode = null,
                        resultCode = "TRANSPORT_FAILED",
                        transportClass = "IO",
                    )
                    assertTrue(joinedLogs.contains("result_code=TRANSPORT_FAILED"))
                    assertTrue(joinedLogs.contains("transport_class=IO"))
                    assertTrue(joinedLogs.contains("completion_class=FAILED"))
                    assertForbiddenAbsent(joinedLogs, forbidden)
                    assertForbiddenAbsent(returned.toString(), forbidden)
                    assertForbiddenAbsent(persisted.toString(), forbidden)
                }
            } finally {
                runCatching { server.shutdown() }
                clearApiProbeRowsIfOpen(database)
            }
        }

    private suspend fun clearApiProbeRowsIfOpen(database: AnebDatabase) {
        runCatching {
            withContext(Dispatchers.IO) {
                if (database.isOpen) {
                    database.openHelper.writableDatabase.execSQL("DELETE FROM api_probe_result")
                }
            }
        }
    }

    private fun assertTypedFailure(
        result: ApiProbeResultEntity,
        httpCode: Int?,
        resultCode: String,
        transportClass: String,
    ) {
        assertEquals(httpCode, result.httpCode)
        assertEquals(resultCode, result.resultCode)
        assertEquals(transportClass, result.transportClass)
        assertEquals("FAILED", result.completionClass)
        assertEquals(0L, result.errorBodyByteCount)
        assertNull(result.errorBodySha256)
        assertEquals("NONE", result.errorBodyDigestScope)
        assertFalse(result.errorBodyTruncated)
    }

    private fun assertForbiddenAbsent(text: String, forbidden: List<String>) {
        forbidden.forEach { token ->
            assertFalse("forbidden token was retained: $token", text.contains(token))
        }
    }
}
