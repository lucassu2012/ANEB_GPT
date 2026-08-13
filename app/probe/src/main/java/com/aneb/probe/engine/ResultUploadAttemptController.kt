package com.aneb.probe.engine

import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal sealed interface ResultUploadAttemptResult<out T> {
    data class Completed<T>(val value: T) : ResultUploadAttemptResult<T>

    data class FinalStatusPersistenceFailed<T>(
        val value: T,
    ) : ResultUploadAttemptResult<T>

    data class NotAdmitted(
        val decision: ResultUploadAdmissionDecision,
    ) : ResultUploadAttemptResult<Nothing>

    data object RevokedBeforeStart : ResultUploadAttemptResult<Nothing>

    data object AuthorizationChangedBeforeStart : ResultUploadAttemptResult<Nothing>

    data object CancelledMayHaveStarted : ResultUploadAttemptResult<Nothing>

    data object UploadStateUnavailable : ResultUploadAttemptResult<Nothing>
}

internal enum class ResultUploadFinalStatusCommit {
    COMMITTED,
    NOT_COMMITTED,
}

internal fun selectResultUploadCancellation(
    caught: CancellationException,
    finalizerCancellation: CancellationException?,
): CancellationException {
    if (finalizerCancellation == null) return caught
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = caught
    while (current != null && visited.add(current)) {
        if (current === finalizerCancellation) return finalizerCancellation
        current = current.cause
    }
    return caught
}

internal enum class ResultUploadRevokeDisposition {
    NO_ACTIVE,
    REVOKED_BEFORE_START,
    CANCELLED_MAY_HAVE_STARTED,
}

internal data class ResultUploadRevokeResult(
    val consentMutation: ResultUploadConsentMutationResult,
    val attemptDisposition: ResultUploadRevokeDisposition,
)

/**
 * Process-scoped owner domain. A result ID may have one provisional or active owner inside this
 * registry. Controllers reconstructed with the same consent process state share this monitor.
 */
internal class ResultUploadAttemptRegistry {
    internal val lock = Any()
    internal val activeAttempts = mutableMapOf<String, ResultUploadOwnedAttempt>()
    internal val revokeWaveDispositions =
        mutableMapOf<ResultUploadConsentScope, ResultUploadRevokeDisposition>()
}

/**
 * Owns the linearization boundary between provisional local preparation, durable result claim,
 * lazy attempt registration, transport start, and consent revocation. This controller never
 * interprets cancellation as server-side deletion: after start, delivery remains unknown.
 */
internal class ResultUploadAttemptController(
    private val consentStore: ResultUploadConsentStore,
    private val beforePermitStartCas: () -> Unit = {},
    private val afterRevokePersistenceBeforeWaveCleanup: () -> Unit = {},
) {
    private val registry: ResultUploadAttemptRegistry = consentStore.attemptRegistry

    fun grant(scope: ResultUploadConsentScope): ResultUploadConsentMutationResult =
        consentStore.grant(scope)

    fun resetForPrivacyDeletion(): ResultUploadPrivacyResetResult =
        consentStore.resetForPrivacyDeletion()

    fun revoke(scope: ResultUploadConsentScope): ResultUploadRevokeResult {
        var disposition: ResultUploadRevokeDisposition
        val cancellations = mutableListOf<Pair<Job, ResultUploadRevokedCancellationException>>()
        lateinit var revokeToken: ResultUploadRevokeToken
        synchronized(registry.lock) {
            val waveWasOpen = consentStore.hasPendingRevokeLocked(scope)
            when (val start = consentStore.beginRevoke(scope)) {
                is ResultUploadRevokeStart.Started -> revokeToken = start.token
                ResultUploadRevokeStart.StateUnavailable ->
                    return ResultUploadRevokeResult(
                        consentMutation = ResultUploadConsentMutationResult.STATE_UNAVAILABLE,
                        attemptDisposition = ResultUploadRevokeDisposition.NO_ACTIVE,
                    )
            }
            disposition = if (waveWasOpen) {
                registry.revokeWaveDispositions[scope]
                    ?: ResultUploadRevokeDisposition.NO_ACTIVE
            } else {
                ResultUploadRevokeDisposition.NO_ACTIVE
            }
            registry.activeAttempts.values
                .filter { it.scope == scope }
                .forEach { attempt ->
                    when (attempt.state) {
                        AttemptState.PREPARING,
                        AttemptState.ARMED -> {
                            if (disposition == ResultUploadRevokeDisposition.NO_ACTIVE) {
                                disposition = ResultUploadRevokeDisposition.REVOKED_BEFORE_START
                            }
                            attempt.state = AttemptState.REVOKED_BEFORE_START
                            attempt.cancellationDispatchCount += 1
                            cancellations += attempt.job to
                                ResultUploadRevokedCancellationException(started = false)
                        }

                        AttemptState.STARTED -> {
                            disposition = ResultUploadRevokeDisposition.CANCELLED_MAY_HAVE_STARTED
                            attempt.state = AttemptState.CANCELLED_MAY_HAVE_STARTED
                            attempt.cancellationDispatchCount += 1
                            cancellations += attempt.job to
                                ResultUploadRevokedCancellationException(started = true)
                        }

                        AttemptState.FINALIZING,
                        AttemptState.REVOKED_BEFORE_START,
                        AttemptState.CANCELLED_MAY_HAVE_STARTED,
                        AttemptState.UPLOAD_STATE_UNAVAILABLE,
                        AttemptState.AUTHORIZATION_CHANGED,
                        -> Unit
                    }
                }
            registry.revokeWaveDispositions[scope] = disposition
        }
        cancellations.forEach { (job, cause) -> job.cancel(cause) }
        val consentMutation = try {
            consentStore.completeRevoke(revokeToken)
        } finally {
            afterRevokePersistenceBeforeWaveCleanup()
            synchronized(registry.lock) {
                if (!consentStore.hasPendingRevokeLocked(scope)) {
                    registry.revokeWaveDispositions -= scope
                }
            }
        }
        return ResultUploadRevokeResult(
            consentMutation = consentMutation,
            attemptDisposition = disposition,
        )
    }

    suspend fun <T> runAttempt(
        resultId: String,
        scope: ResultUploadConsentScope,
        prepare: suspend () -> Unit,
        execute: suspend () -> T,
        finalize: suspend (T) -> ResultUploadFinalStatusCommit,
    ): ResultUploadAttemptResult<T> {
        val ownerJob = currentCoroutineContext()[Job]
        var frozenTransport: FrozenTransport<T>? = null
        var finalizationObservation: FinalizationObservation? = null
        suspend fun finalizeFrozen(value: T): FinalizationObservation {
            finalizationObservation?.let { return it }
            withContext(NonCancellable) {
                finalizationObservation = try {
                    FinalizationObservation.Receipt(finalize(value))
                } catch (cancelled: CancellationException) {
                    FinalizationObservation.Cancelled(cancelled)
                } catch (_: Exception) {
                    FinalizationObservation.Receipt(
                        ResultUploadFinalStatusCommit.NOT_COMMITTED,
                    )
                }
            }
            return checkNotNull(finalizationObservation)
        }
        val scopedResult: ResultUploadAttemptResult<T> = coroutineScope {
        lateinit var owned: ResultUploadOwnedAttempt
        val deferred = async(start = CoroutineStart.LAZY) {
            val value = execute()
            val outcomeFrozen = synchronized(registry.lock) {
                if (registry.activeAttempts[owned.resultId] === owned &&
                    owned.state == AttemptState.STARTED
                ) {
                    owned.state = AttemptState.FINALIZING
                    frozenTransport = FrozenTransport(value)
                    true
                } else {
                    false
                }
            }
            if (!outcomeFrozen) {
                val state = synchronized(registry.lock) { owned.state }
                if (state == AttemptState.CANCELLED_MAY_HAVE_STARTED) {
                    throw ResultUploadRevokedCancellationException(started = true)
                }
                throw CancellationException("result_upload_outcome_not_owned")
            }
            value
        }
        val registered = synchronized(registry.lock) {
            if (registry.activeAttempts.containsKey(resultId)) {
                false
            } else {
                owned = ResultUploadOwnedAttempt(
                    resultId = resultId,
                    scope = scope,
                    job = deferred,
                )
                registry.activeAttempts[resultId] = owned
                true
            }
        }
        if (!registered) {
            deferred.cancel()
            return@coroutineScope ResultUploadAttemptResult.NotAdmitted(
                ResultUploadAdmissionDecision.AlreadyClaimed,
            )
        }

        try {
            val preparedAdmission = consentStore.prepareAdmission(resultId, scope)
            synchronized(registry.lock) {
                if (registry.activeAttempts[owned.resultId] === owned &&
                    owned.state == AttemptState.PREPARING
                ) {
                    owned.preparedAdmission = preparedAdmission
                }
            }
            preparedAdmission.decisionBeforePreparation()?.let { blocker ->
                return@coroutineScope ResultUploadAttemptResult.NotAdmitted(blocker)
            }
            prepare()
            currentCoroutineContext().ensureActive()
            val mayDecide = synchronized(registry.lock) {
                registry.activeAttempts[owned.resultId] === owned &&
                    owned.state == AttemptState.PREPARING
            }
            if (!mayDecide) {
                deferred.cancel()
                return@coroutineScope ResultUploadAttemptResult.RevokedBeforeStart
            }
            val decision = preparedAdmission.decide(
                adapterConfigured = true,
                beforeClaim = { ownerJob?.ensureActive() },
            )
            val permit = (decision as? ResultUploadAdmissionDecision.Permitted)?.permit
            val armed = synchronized(registry.lock) {
                if (registry.activeAttempts[owned.resultId] === owned &&
                    owned.state == AttemptState.PREPARING &&
                    permit != null
                ) {
                    owned.permit = permit
                    owned.state = AttemptState.ARMED
                    true
                } else {
                    false
                }
            }
            if (!armed) {
                deferred.cancel()
                return@coroutineScope when {
                    owned.state == AttemptState.REVOKED_BEFORE_START ->
                        ResultUploadAttemptResult.RevokedBeforeStart

                    decision == ResultUploadAdmissionDecision.AuthorizationChanged ->
                        ResultUploadAttemptResult.AuthorizationChangedBeforeStart

                    else -> ResultUploadAttemptResult.NotAdmitted(decision)
                }
            }

            val observation = consentStore.observePermit(checkNotNull(owned.permit))
            beforePermitStartCas()
            val startState = synchronized(registry.lock) {
                if (registry.activeAttempts[owned.resultId] === owned &&
                    owned.state == AttemptState.ARMED
                ) {
                    when (consentStore.consumeObservationLocked(scope, observation)) {
                        ResultUploadPermitState.CURRENT ->
                            owned.state = AttemptState.STARTED

                        ResultUploadPermitState.STATE_UNAVAILABLE ->
                            owned.state = AttemptState.UPLOAD_STATE_UNAVAILABLE

                        ResultUploadPermitState.CONSENT_CHANGED ->
                            owned.state = AttemptState.AUTHORIZATION_CHANGED
                    }
                }
                owned.state
            }
            when (startState) {
                AttemptState.STARTED -> Unit
                AttemptState.REVOKED_BEFORE_START -> {
                    deferred.cancel()
                    return@coroutineScope ResultUploadAttemptResult.RevokedBeforeStart
                }

                AttemptState.UPLOAD_STATE_UNAVAILABLE -> {
                    deferred.cancel()
                    return@coroutineScope ResultUploadAttemptResult.UploadStateUnavailable
                }

                else -> {
                    deferred.cancel()
                    return@coroutineScope ResultUploadAttemptResult.AuthorizationChangedBeforeStart
                }
            }

            deferred.start()
            val value = deferred.await()
            val state = synchronized(registry.lock) { owned.state }
            if (state == AttemptState.CANCELLED_MAY_HAVE_STARTED) {
                ResultUploadAttemptResult.CancelledMayHaveStarted
            } else {
                when (val finalization = finalizeFrozen(value)) {
                    is FinalizationObservation.Cancelled -> throw finalization.cancellation
                    is FinalizationObservation.Receipt ->
                        if (finalization.commit == ResultUploadFinalStatusCommit.COMMITTED) {
                            ResultUploadAttemptResult.Completed(value)
                        } else {
                            ResultUploadAttemptResult.FinalStatusPersistenceFailed(value)
                        }
                }
            }
        } catch (revoked: ResultUploadRevokedCancellationException) {
            if (revoked.started) {
                ResultUploadAttemptResult.CancelledMayHaveStarted
            } else {
                ResultUploadAttemptResult.RevokedBeforeStart
            }
        } catch (cancelled: CancellationException) {
            val frozen = synchronized(registry.lock) {
                if (registry.activeAttempts[owned.resultId] === owned &&
                    owned.state == AttemptState.FINALIZING
                ) {
                    frozenTransport
                } else {
                    null
                }
            }
            if (frozen != null && finalizationObservation == null) {
                try {
                    finalizeFrozen(frozen.value)
                } catch (_: CancellationException) {
                    // The original parent cancellation remains authoritative after the protected
                    // finalizer has produced its local receipt (or its own typed cancellation).
                }
            }
            val propagatedCancellation = selectResultUploadCancellation(
                caught = cancelled,
                finalizerCancellation =
                    (finalizationObservation as? FinalizationObservation.Cancelled)?.cancellation,
            )
            deferred.cancel(propagatedCancellation)
            PropagateCancellation(propagatedCancellation)
        } finally {
            if (!deferred.isCompleted) deferred.cancel()
            synchronized(registry.lock) {
                if (registry.activeAttempts[owned.resultId] === owned) {
                    registry.activeAttempts.remove(owned.resultId)
                }
            }
        }
        }
        if (scopedResult is PropagateCancellation) {
            throw scopedResult.cancellation
        }
        return scopedResult
    }

    private class ResultUploadRevokedCancellationException(
        val started: Boolean,
    ) : CancellationException(
        if (started) "result_upload_cancelled_may_have_started" else "result_upload_revoked_before_start",
    )

    private data class PropagateCancellation(
        val cancellation: CancellationException,
    ) : ResultUploadAttemptResult<Nothing>

    private data class FrozenTransport<T>(
        val value: T,
    )

    private sealed interface FinalizationObservation {
        data class Receipt(
            val commit: ResultUploadFinalStatusCommit,
        ) : FinalizationObservation

        data class Cancelled(
            val cancellation: CancellationException,
        ) : FinalizationObservation
    }
}

internal data class ResultUploadOwnedAttempt(
    val resultId: String,
    val scope: ResultUploadConsentScope,
    val job: Job,
    var preparedAdmission: ResultUploadPreparedAdmission? = null,
    var permit: ResultUploadPermit? = null,
    var state: AttemptState = AttemptState.PREPARING,
    var cancellationDispatchCount: Int = 0,
)

internal enum class AttemptState {
    PREPARING,
    ARMED,
    STARTED,
    FINALIZING,
    REVOKED_BEFORE_START,
    CANCELLED_MAY_HAVE_STARTED,
    UPLOAD_STATE_UNAVAILABLE,
    AUTHORIZATION_CHANGED,
}
