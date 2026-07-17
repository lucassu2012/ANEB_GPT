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
import okhttp3.HttpUrl.Companion.toHttpUrl
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
    private val base = normalizeBase(baseUrl)
    private val authorization = "Bearer ${token.also {
        require(it.matches(Regex("^[A-Fa-f0-9]{64}$"))) { "gateway_token_must_be_64_hex" }
    }}"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(CONTROL_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
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
        @SerialName("created_at") val createdAt: String,
        @SerialName("scheduled_at") val scheduledAt: String,
        @SerialName("expected_active_at") val expectedActiveAt: String,
        @SerialName("active_at") val activeAt: String? = null,
        @SerialName("expected_clear_at") val expectedClearAt: String? = null,
        @SerialName("cleared_at") val clearedAt: String? = null,
        @SerialName("cleanup_verified") val cleanupVerified: Boolean,
        @SerialName("stop_reason") val stopReason: String = "",
        val error: String = "",
    )

    @Serializable
    private data class StartRequest(
        @SerialName("run_id") val runId: String,
        @SerialName("profile_ref") val profileRef: String,
    )

    @Serializable
    private data class StatusResponse(
        @SerialName("active_experiment") val activeExperiment: Experiment? = null,
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

    suspend fun status(): Experiment? {
        val body = requestBody(
            Request.Builder()
                .url("$base/v1/status")
                .header("Authorization", authorization)
                .get()
                .build(),
            expectedCode = 200,
        )
        return runCatching { json.decodeFromString(StatusResponse.serializer(), body).activeExperiment }
            .getOrElse { throw GatewayApiException("gateway_response_invalid", submissionMayHaveSucceeded = true) }
    }

    private suspend fun request(request: Request, expectedCode: Int): Experiment {
        val body = requestBody(request, expectedCode)
        return runCatching { json.decodeFromString(Experiment.serializer(), body) }
            .getOrElse { throw GatewayApiException("gateway_response_invalid", submissionMayHaveSucceeded = true) }
    }

    private suspend fun requestBody(request: Request, expectedCode: Int): String {
        val call = client.newCall(request)
        return try {
            executeCancellable(call) { response ->
                val body = response.body?.string().orEmpty()
                if (response.code != expectedCode) {
                    throw GatewayApiException("gateway_http_${response.code}", submissionMayHaveSucceeded = false)
                }
                body
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GatewayApiException) {
            throw e
        } catch (_: Exception) {
            throw GatewayApiException("gateway_control_unreachable", submissionMayHaveSucceeded = true)
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

    class GatewayApiException internal constructor(
        message: String,
        internal val submissionMayHaveSucceeded: Boolean,
    ) : IOException(message)

    companion object {
        internal const val CONTROL_CALL_TIMEOUT_MS = 7_000L
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        internal fun normalizeBase(value: String): String {
            val url = value.trim().toHttpUrl()
            require(url.scheme == "https") { "gateway_control_requires_https" }
            require(url.username.isEmpty() && url.password.isEmpty()) { "gateway_base_userinfo_forbidden" }
            require(url.query == null && url.fragment == null && url.encodedPath == "/") { "gateway_base_must_not_contain_path_or_query" }
            require(url.host == GATEWAY_MANAGEMENT_IP) { "gateway_base_requires_attested_management_ip" }
            return url.toString().trimEnd('/')
        }

        private const val GATEWAY_MANAGEMENT_IP = "192.168.77.1"

        internal fun isAmbiguousSubmissionFailure(error: Throwable): Boolean =
            error is GatewayApiException && error.submissionMayHaveSucceeded
    }
}
