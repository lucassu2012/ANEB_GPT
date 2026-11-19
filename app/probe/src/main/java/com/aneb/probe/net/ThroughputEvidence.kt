package com.aneb.probe.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.Serializable
import okhttp3.Call

/** Facts about one application HTTP request, not a claim of remote byte delivery. */
@Serializable
data class ThroughputRequestEvidence(
    val requestId: String,
    val workerId: Int,
    val direction: String,
    val requestedBytes: Long,
    val startUs: Long,
    val headersUs: Long? = null,
    val httpCode: Int? = null,
    val bodyStartedUs: Long? = null,
    val bodyCompletedUs: Long? = null,
    val cancelRequestedUs: Long? = null,
    val cancelCause: String? = null,
    val cancelInvokedUs: Long? = null,
    val cancelReturnedUs: Long? = null,
    val callbackFinishedUs: Long? = null,
    val outcome: String = "unknown",
    val failureUs: Long? = null,
    val failure: String? = null,
    val ioAfterCancel: String? = null,
    val ioAfterCancelUs: Long? = null,
    val ioAfterCancelSawCancelled: Boolean? = null,
    val observedBytes: Long = 0L,
)

/** Explicit controller token; ordinary caller cancellation has no inferred source. */
internal class ObservedTransferCancellation(val source: String) : CancellationException(
    if (source == "user_action") "user_cancelled" else source,
)

/** Opt-in request facts. A coroutine ending is deliberately not callback closure. */
internal class ThroughputObservation(
    requestId: String,
    workerId: Int,
    direction: String,
    requestedBytes: Long,
    private val clockUs: () -> Long,
) {
    private val lock = Any()
    private var evidence = ThroughputRequestEvidence(requestId, workerId, direction, requestedBytes, clockUs())
    private var byteCount = 0L
    private val callbackDone = CompletableDeferred<Unit>()

    fun snapshot(): ThroughputRequestEvidence = synchronized(lock) { evidence.copy(observedBytes = byteCount) }

    suspend fun awaitDrained() = callbackDone.await()

    fun headers(code: Int) {
        val at = clockUs()
        synchronized(lock) {
            evidence = evidence.copy(headersUs = at, httpCode = code)
            if (code !in 200..299) retainFailure(at, "http_$code")
        }
    }

    fun bodyStarted() {
        val at = clockUs()
        synchronized(lock) { evidence = evidence.copy(bodyStartedUs = evidence.bodyStartedUs ?: at) }
    }

    fun bytes(count: Int) = synchronized(lock) { byteCount += count.toLong() }

    fun bodyCompleted() {
        val at = clockUs()
        synchronized(lock) { evidence = evidence.copy(bodyCompletedUs = at) }
    }

    fun cancelCall(cause: Throwable?, call: Call) {
        val requested = clockUs()
        var current = cause
        var source = "caller_unknown"
        for (depth in 0 until 8) {
            if (current is ObservedTransferCancellation) {
                source = current.source
                break
            }
            current = current?.cause
        }
        synchronized(lock) {
            evidence = evidence.copy(cancelRequestedUs = requested, cancelCause = source)
        }
        val invoked = clockUs()
        synchronized(lock) { evidence = evidence.copy(cancelInvokedUs = invoked) }
        try {
            call.cancel()
            val returned = clockUs()
            synchronized(lock) { evidence = evidence.copy(cancelReturnedUs = returned) }
        } catch (error: Exception) {
            failure(error, call.isCanceled())
        }
    }

    fun failure(error: Exception, sawCancelled: Boolean) {
        // Capture the observed event before taking the lock: later cleanup cannot wash it away.
        val at = clockUs()
        synchronized(lock) {
            val invoked = evidence.cancelInvokedUs
            if (evidence.failure == null && error is java.io.IOException && sawCancelled && invoked != null && at >= invoked) {
                if (evidence.ioAfterCancel == null) evidence = evidence.copy(
                    ioAfterCancel = error.javaClass.simpleName, ioAfterCancelUs = at, ioAfterCancelSawCancelled = true,
                )
            } else retainFailure(at, error.javaClass.simpleName)
        }
    }

    /** Called only after consume AND Response.close (or onFailure) have exited. */
    fun callbackFinished() {
        val at = clockUs()
        synchronized(lock) {
            val outcome = when {
                evidence.failure != null -> "failed"
                evidence.bodyCompletedUs != null && evidence.httpCode?.let { it in 200..299 } == true -> "completed"
                evidence.cancelInvokedUs != null -> "cancelled"
                else -> "unknown"
            }
            evidence = evidence.copy(callbackFinishedUs = at, outcome = outcome)
        }
        callbackDone.complete(Unit)
    }

    private fun retainFailure(at: Long, reason: String) {
        if (evidence.failure == null || at < checkNotNull(evidence.failureUs)) {
            evidence = evidence.copy(failureUs = at, failure = reason, outcome = "failed")
        }
    }
}
