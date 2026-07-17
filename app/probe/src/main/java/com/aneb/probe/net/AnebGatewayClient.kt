package com.aneb.probe.net

import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Dedicated lab-gateway control client. The bearer credential remains in memory,
 * is never persisted, and is deliberately absent from exceptions and logs.
 */
class AnebGatewayClient(
    baseUrl: String,
    token: String,
    bound: BoundNetwork? = null,
) {
    private val base = baseUrl.trim().trimEnd('/').also {
        require(it.startsWith("https://")) { "gateway_control_requires_https" }
    }
    private val authorization = "Bearer ${token.also { require(it.length >= 32) { "gateway_token_too_short" } }}"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .proxy(Proxy.NO_PROXY)
        .apply {
            if (bound != null) {
                socketFactory(bound.socketFactory)
                dns(bound.dns)
            }
        }
        .build()

    @Serializable
    data class Experiment(
        @SerialName("experiment_id") val experimentId: String,
        @SerialName("run_id") val runId: String,
        @SerialName("profile_ref") val profileRef: String,
        @SerialName("profile_fingerprint") val profileFingerprint: String,
        val phase: String,
        @SerialName("claim_scope") val claimScope: String,
        @SerialName("impairment_layer") val impairmentLayer: String,
        @SerialName("created_at") val createdAt: String = "",
        @SerialName("scheduled_at") val scheduledAt: String = "",
        @SerialName("expected_active_at") val expectedActiveAt: String = "",
        @SerialName("active_at") val activeAt: String? = null,
        @SerialName("expected_clear_at") val expectedClearAt: String? = null,
        @SerialName("cleared_at") val clearedAt: String? = null,
        @SerialName("stop_reason") val stopReason: String = "",
        val error: String = "",
    )

    @Serializable
    private data class StartRequest(
        @SerialName("run_id") val runId: String,
        @SerialName("profile_ref") val profileRef: String,
    )

    suspend fun start(runId: String, profileRef: String): Experiment {
        val payload = json.encodeToString(StartRequest.serializer(), StartRequest(runId, profileRef))
        return request(
            Request.Builder()
                .url("$base/v1/experiments")
                .header("Authorization", authorization)
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
            expectedCode = 202,
        )
    }

    suspend fun get(experimentId: String): Experiment = request(
        Request.Builder()
            .url("$base/v1/experiments/${safeId(experimentId)}")
            .header("Authorization", authorization)
            .get()
            .build(),
        expectedCode = 200,
    )

    suspend fun stop(experimentId: String): Experiment = request(
        Request.Builder()
            .url("$base/v1/experiments/${safeId(experimentId)}")
            .header("Authorization", authorization)
            .delete()
            .build(),
        expectedCode = 202,
    )

    private suspend fun request(request: Request, expectedCode: Int): Experiment {
        val call = client.newCall(request)
        return try {
            executeCancellable(call) { response ->
                val body = response.body?.string().orEmpty()
                if (response.code != expectedCode) throw GatewayApiException("gateway_http_${response.code}")
                runCatching { json.decodeFromString(Experiment.serializer(), body) }
                    .getOrElse { throw GatewayApiException("gateway_response_invalid") }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GatewayApiException) {
            throw e
        } catch (_: Exception) {
            throw GatewayApiException("gateway_control_unreachable")
        }
    }

    private fun safeId(value: String): String {
        require(value.matches(Regex("^[A-Za-z0-9._-]{1,128}$"))) { "invalid_gateway_experiment_id" }
        return value
    }

    private suspend fun <T> executeCancellable(call: Call, consume: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCompleted) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        runCatching { consume(it) }
                            .onSuccess { value -> if (!continuation.isCompleted) continuation.resume(value) }
                            .onFailure { error -> if (!continuation.isCompleted) continuation.resumeWithException(error) }
                    }
                }
            })
        }

    class GatewayApiException(message: String) : IOException(message)

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
