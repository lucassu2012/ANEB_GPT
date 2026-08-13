package com.aneb.probe.engine

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import kotlinx.coroutines.CancellationException

internal enum class ResultUploadConsent {
    UNKNOWN,
    GRANTED,
    REVOKED,
    REVOKING,
    PERSISTENCE_FAILURE_BLOCKED,
}

internal enum class ResultUploadConsentMutationResult {
    COMMITTED,
    PERSISTENCE_FAILED,
    BLOCKED_BY_PROCESS_REVOCATION,
    STATE_UNAVAILABLE,
}

internal enum class ResultUploadPermitState {
    CURRENT,
    CONSENT_CHANGED,
    STATE_UNAVAILABLE,
}

internal data class ResultUploadPermitObservation(
    val state: ResultUploadPermitState,
    val authorizationRevision: Long,
)

internal enum class ResultUploadPurpose {
    RESULT_SUBMISSION,
}

internal data class ResultUploadConsentScope(
    val recipient: String,
    val purpose: ResultUploadPurpose,
    val policyVersion: String,
) {
    init {
        require(RECIPIENT.matches(recipient)) { "result_upload_recipient_invalid" }
        require(POLICY_VERSION.matches(policyVersion)) { "result_upload_policy_version_invalid" }
    }

    private companion object {
        val RECIPIENT = Regex("^[a-z0-9][a-z0-9._:-]{2,127}$")
        val POLICY_VERSION = Regex("^[1-9][0-9]{0,8}$")
    }
}

internal data class PersistedResultUploadConsent(
    val state: String? = null,
    val recipient: String? = null,
    val purpose: String? = null,
    val policyVersion: String? = null,
    val consentEpoch: Long = 0L,
    val claimedResultIds: Set<String> = emptySet(),
    val authorizedResultId: String? = null,
)

internal interface ResultUploadConsentPersistence {
    fun read(): PersistedResultUploadConsent

    fun write(value: PersistedResultUploadConsent): Boolean
}

internal interface ResultUploadConsentPrivacyResetPersistence {
    fun isPhysicallyEmpty(): Boolean

    fun clearAndCommit(): Boolean
}

internal sealed interface ResultUploadPrivacyResetResult {
    data object Cleared : ResultUploadPrivacyResetResult
    data object AlreadyEmpty : ResultUploadPrivacyResetResult
    data object BlockedByActiveAttempt : ResultUploadPrivacyResetResult
    data object BlockedByPendingRevoke : ResultUploadPrivacyResetResult
    data object ResetInProgress : ResultUploadPrivacyResetResult
    data object StateUnavailable : ResultUploadPrivacyResetResult
}

internal class ResultUploadRevokeToken internal constructor(
    val operationId: String,
    val scope: ResultUploadConsentScope,
    internal val capabilityGeneration: Any,
)

internal sealed interface ResultUploadRevokeStart {
    data class Started(val token: ResultUploadRevokeToken) : ResultUploadRevokeStart
    data object StateUnavailable : ResultUploadRevokeStart
}

/** Process-local fail-closed state shared by every production store reconstruction. */
internal class ResultUploadConsentProcessState {
    internal val attemptRegistry = ResultUploadAttemptRegistry()
    internal val persistenceIoLock = Any()
    internal val permanentlyUnavailableScopes = mutableSetOf<ResultUploadConsentScope>()
    internal val pendingRevokeOperations =
        mutableMapOf<ResultUploadConsentScope, MutableSet<String>>()
    internal var authorizationRevision = 0L
    internal var revisionExhausted = false
    internal var capabilityGeneration: Any = Any()
    internal var privacyResetToken: Any? = null
    internal var privacyResetUnavailable = false
    internal val activeDirectPermitAttempts = mutableSetOf<Any>()
    internal val activeConsentMutationTokens = mutableSetOf<Any>()
}

/**
 * Independent result-upload policy store. It is deliberately separate from launch settings so
 * callers cannot smuggle a stale consent Boolean through RunConfig or an Intent.
 */
internal class ResultUploadConsentStore internal constructor(
    private val persistence: ResultUploadConsentPersistence,
    private val processState: ResultUploadConsentProcessState = ResultUploadConsentProcessState(),
) {
    internal val attemptRegistry: ResultUploadAttemptRegistry
        get() = processState.attemptRegistry

    private val privacyResetPersistence: ResultUploadConsentPrivacyResetPersistence?
        get() = persistence as? ResultUploadConsentPrivacyResetPersistence

    constructor(context: Context) : this(
        SharedPreferencesConsentPersistence(context),
        PROCESS_STATE,
    )

    fun currentConsent(scope: ResultUploadConsentScope): ResultUploadConsent =
        synchronized(processState.persistenceIoLock) {
            val stored = readPersistenceOrNull() ?: run {
                markPermanentlyUnavailable(scope)
                return@synchronized ResultUploadConsent.PERSISTENCE_FAILURE_BLOCKED
            }
            synchronized(processState.attemptRegistry.lock) {
                when {
                    processState.privacyResetUnavailable ||
                        processState.privacyResetToken != null ||
                        processState.revisionExhausted ||
                        scope in processState.permanentlyUnavailableScopes ->
                        ResultUploadConsent.PERSISTENCE_FAILURE_BLOCKED

                    stored.recordShape() == PersistedConsentRecordShape.CORRUPT -> {
                        markPermanentlyUnavailableLocked(scope)
                        ResultUploadConsent.PERSISTENCE_FAILURE_BLOCKED
                    }

                    processState.isRevoking(scope) -> ResultUploadConsent.REVOKING

                    else -> decodeConsent(scope, stored).also { decoded ->
                        if (decoded == ResultUploadConsent.PERSISTENCE_FAILURE_BLOCKED) {
                            markPermanentlyUnavailableLocked(scope)
                        }
                    }
                }
            }
        }

    internal fun observePermit(permit: ResultUploadPermit): ResultUploadPermitObservation =
        synchronized(processState.persistenceIoLock) observation@{
            val revisionBeforeRead = synchronized(processState.attemptRegistry.lock) {
                when {
                    processState.privacyResetUnavailable ||
                        processState.privacyResetToken != null ||
                        permit.capabilityGeneration !== processState.capabilityGeneration ||
                        permit.scope in processState.permanentlyUnavailableScopes ||
                        processState.revisionExhausted ->
                        return@observation ResultUploadPermitObservation(
                            ResultUploadPermitState.STATE_UNAVAILABLE,
                            processState.authorizationRevision,
                        )

                    processState.isRevoking(permit.scope) ->
                        return@observation ResultUploadPermitObservation(
                            ResultUploadPermitState.CONSENT_CHANGED,
                            processState.authorizationRevision,
                        )

                    else -> processState.authorizationRevision
                }
            }
            val stored = readPersistenceOrNull() ?: run {
                markPermanentlyUnavailable(permit.scope)
                return@observation ResultUploadPermitObservation(
                    ResultUploadPermitState.STATE_UNAVAILABLE,
                    currentAuthorizationRevision(),
                )
            }
            synchronized(processState.attemptRegistry.lock) {
                if (revisionBeforeRead != processState.authorizationRevision) {
                    return@observation ResultUploadPermitObservation(
                        ResultUploadPermitState.CONSENT_CHANGED,
                        processState.authorizationRevision,
                    )
                }
                val state = when (decodeConsent(permit.scope, stored)) {
                    ResultUploadConsent.PERSISTENCE_FAILURE_BLOCKED -> {
                        markPermanentlyUnavailableLocked(permit.scope)
                        ResultUploadPermitState.STATE_UNAVAILABLE
                    }

                    ResultUploadConsent.UNKNOWN,
                    ResultUploadConsent.REVOKED,
                    ResultUploadConsent.REVOKING,
                    -> ResultUploadPermitState.CONSENT_CHANGED

                    ResultUploadConsent.GRANTED -> when {
                        stored.consentEpoch != permit.consentEpoch ->
                            ResultUploadPermitState.CONSENT_CHANGED

                        permit.resultId !in stored.claimedResultIds -> {
                            markPermanentlyUnavailableLocked(permit.scope)
                            ResultUploadPermitState.STATE_UNAVAILABLE
                        }

                        else -> ResultUploadPermitState.CURRENT
                    }
                }
                ResultUploadPermitObservation(state, processState.authorizationRevision)
            }
        }

    internal fun consumeObservationLocked(
        scope: ResultUploadConsentScope,
        observation: ResultUploadPermitObservation,
    ): ResultUploadPermitState {
        check(Thread.holdsLock(processState.attemptRegistry.lock)) {
            "result_upload_registry_lock_required"
        }
        return when {
            processState.privacyResetUnavailable ||
                processState.privacyResetToken != null ||
                scope in processState.permanentlyUnavailableScopes ||
                processState.revisionExhausted -> ResultUploadPermitState.STATE_UNAVAILABLE

            processState.isRevoking(scope) -> ResultUploadPermitState.CONSENT_CHANGED

            observation.state == ResultUploadPermitState.STATE_UNAVAILABLE ->
                ResultUploadPermitState.STATE_UNAVAILABLE

            observation.state != ResultUploadPermitState.CURRENT ||
                observation.authorizationRevision != processState.authorizationRevision ->
                ResultUploadPermitState.CONSENT_CHANGED

            else -> ResultUploadPermitState.CURRENT
        }
    }

    internal fun isPermitCurrent(permit: ResultUploadPermit): Boolean =
        observePermit(permit).state == ResultUploadPermitState.CURRENT

    fun grant(scope: ResultUploadConsentScope): ResultUploadConsentMutationResult =
        grant(scope = scope, authorizedResultId = null)

    fun grantForResult(
        resultId: String,
        scope: ResultUploadConsentScope,
    ): ResultUploadConsentMutationResult {
        require(RESULT_ID.matches(resultId)) { "result_upload_result_id_invalid" }
        return grant(scope = scope, authorizedResultId = resultId)
    }

    private fun grant(
        scope: ResultUploadConsentScope,
        authorizedResultId: String?,
    ): ResultUploadConsentMutationResult =
        synchronized(processState.persistenceIoLock) grant@{
            val policyBeforeRead = synchronized(processState.attemptRegistry.lock) {
                when {
                    processState.privacyResetUnavailable ||
                        processState.privacyResetToken != null ||
                        processState.revisionExhausted ||
                        scope in processState.permanentlyUnavailableScopes ->
                        return@grant ResultUploadConsentMutationResult.STATE_UNAVAILABLE

                    processState.hasPendingRevoke() ->
                        return@grant ResultUploadConsentMutationResult.BLOCKED_BY_PROCESS_REVOCATION

                    else -> GrantPolicySnapshot(
                        authorizationRevision = processState.authorizationRevision,
                        capabilityGeneration = processState.capabilityGeneration,
                    )
                }
            }
            val stored = readPersistenceOrNull() ?: run {
                markPermanentlyUnavailable(scope)
                return@grant ResultUploadConsentMutationResult.PERSISTENCE_FAILED
            }
            when (decodeConsent(scope, stored)) {
                ResultUploadConsent.PERSISTENCE_FAILURE_BLOCKED -> {
                    markPermanentlyUnavailable(scope)
                    return@grant ResultUploadConsentMutationResult.PERSISTENCE_FAILED
                }

                ResultUploadConsent.GRANTED -> {
                    val idempotentBlock = synchronized(processState.attemptRegistry.lock) {
                        when {
                            processState.privacyResetUnavailable ||
                                processState.privacyResetToken != null ||
                                processState.capabilityGeneration !==
                                policyBeforeRead.capabilityGeneration ||
                                processState.revisionExhausted ||
                                scope in processState.permanentlyUnavailableScopes ->
                                ResultUploadConsentMutationResult.STATE_UNAVAILABLE

                            processState.hasPendingRevoke() ||
                                processState.authorizationRevision !=
                                policyBeforeRead.authorizationRevision ->
                                ResultUploadConsentMutationResult.BLOCKED_BY_PROCESS_REVOCATION

                            stored.authorizedResultId != authorizedResultId ->
                                ResultUploadConsentMutationResult.STATE_UNAVAILABLE

                            else -> null
                        }
                    }
                    return@grant idempotentBlock
                        ?: ResultUploadConsentMutationResult.COMMITTED
                }

                ResultUploadConsent.UNKNOWN,
                ResultUploadConsent.REVOKED,
                ResultUploadConsent.REVOKING,
                -> Unit
            }
            if (!stored.canAdvanceConsentEpoch()) {
                markPermanentlyUnavailable(scope)
                return@grant ResultUploadConsentMutationResult.PERSISTENCE_FAILED
            }
            val writeToken = Any()
            val revisionBlock = synchronized(processState.attemptRegistry.lock) {
                when {
                    processState.privacyResetUnavailable ||
                        processState.privacyResetToken != null ||
                        processState.capabilityGeneration !==
                        policyBeforeRead.capabilityGeneration ||
                        processState.revisionExhausted ||
                        scope in processState.permanentlyUnavailableScopes ->
                        ResultUploadConsentMutationResult.STATE_UNAVAILABLE

                    processState.hasPendingRevoke() ||
                        processState.authorizationRevision !=
                        policyBeforeRead.authorizationRevision ->
                        ResultUploadConsentMutationResult.BLOCKED_BY_PROCESS_REVOCATION

                    !advanceRevisionLocked() -> {
                        processState.permanentlyUnavailableScopes += scope
                        ResultUploadConsentMutationResult.STATE_UNAVAILABLE
                    }

                    else -> {
                        processState.activeConsentMutationTokens += writeToken
                        null
                    }
                }
            }
            if (revisionBlock != null) return@grant revisionBlock
            try {
                if (persistForScope(
                        scope,
                        scope.persisted(
                            state = ResultUploadConsent.GRANTED,
                            consentEpoch = stored.consentEpoch + 1L,
                            claimedResultIds = stored.claimedResultIds,
                            authorizedResultId = authorizedResultId,
                        ),
                    )
                ) {
                    ResultUploadConsentMutationResult.COMMITTED
                } else {
                    markPermanentlyUnavailable(scope)
                    ResultUploadConsentMutationResult.PERSISTENCE_FAILED
                }
            } finally {
                synchronized(processState.attemptRegistry.lock) {
                    processState.activeConsentMutationTokens.remove(writeToken)
                }
            }
        }

    fun revoke(scope: ResultUploadConsentScope): ResultUploadConsentMutationResult {
        return when (val start = beginRevoke(scope)) {
            is ResultUploadRevokeStart.Started -> completeRevoke(start.token)
            ResultUploadRevokeStart.StateUnavailable ->
                ResultUploadConsentMutationResult.STATE_UNAVAILABLE
        }
    }

    internal fun resetForPrivacyDeletion(): ResultUploadPrivacyResetResult {
        val resetPersistence = privacyResetPersistence
            ?: return ResultUploadPrivacyResetResult.StateUnavailable
        val token = Any()
        val blocked = synchronized(processState.attemptRegistry.lock) {
            when {
                processState.privacyResetToken != null ->
                    ResultUploadPrivacyResetResult.ResetInProgress

                processState.attemptRegistry.activeAttempts.isNotEmpty() ||
                    processState.activeDirectPermitAttempts.isNotEmpty() ||
                    processState.activeConsentMutationTokens.isNotEmpty() ->
                    ResultUploadPrivacyResetResult.BlockedByActiveAttempt

                processState.hasPendingRevoke() ->
                    ResultUploadPrivacyResetResult.BlockedByPendingRevoke

                else -> {
                    processState.privacyResetToken = token
                    processState.capabilityGeneration = Any()
                    null
                }
            }
        }
        if (blocked != null) return blocked

        var resetSucceeded = false
        val result = try {
            synchronized(processState.persistenceIoLock) {
                val wasEmpty = resetPersistence.isPhysicallyEmpty()
                when {
                    !resetPersistence.clearAndCommit() ->
                        ResultUploadPrivacyResetResult.StateUnavailable

                    !resetPersistence.isPhysicallyEmpty() ->
                        ResultUploadPrivacyResetResult.StateUnavailable

                    wasEmpty -> {
                        resetSucceeded = true
                        ResultUploadPrivacyResetResult.AlreadyEmpty
                    }

                    else -> {
                        resetSucceeded = true
                        ResultUploadPrivacyResetResult.Cleared
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            synchronized(processState.attemptRegistry.lock) {
                if (processState.privacyResetToken === token) {
                    processState.privacyResetToken = null
                    processState.privacyResetUnavailable = true
                }
            }
            throw cancelled
        } catch (_: Exception) {
            ResultUploadPrivacyResetResult.StateUnavailable
        }

        synchronized(processState.attemptRegistry.lock) {
            if (processState.privacyResetToken === token) {
                processState.privacyResetToken = null
                if (resetSucceeded) {
                    processState.privacyResetUnavailable = false
                    processState.permanentlyUnavailableScopes.clear()
                    processState.revisionExhausted = false
                    processState.authorizationRevision = 0L
                } else {
                    processState.privacyResetUnavailable = true
                }
            }
        }
        return result
    }

    /** Installs the process-local off latch without performing persistence I/O. */
    internal fun beginRevoke(scope: ResultUploadConsentScope): ResultUploadRevokeStart =
        synchronized(processState.attemptRegistry.lock) {
            if (processState.privacyResetToken != null || processState.privacyResetUnavailable) {
                return@synchronized ResultUploadRevokeStart.StateUnavailable
            }
            val token = ResultUploadRevokeToken(
                operationId = UUID.randomUUID().toString(),
                scope = scope,
                capabilityGeneration = processState.capabilityGeneration,
            )
            processState.pendingRevokeOperations
                .getOrPut(scope) { mutableSetOf() }
                .add(token.operationId)
            if (!advanceRevisionLocked()) {
                processState.permanentlyUnavailableScopes += scope
            }
            ResultUploadRevokeStart.Started(token)
        }

    internal fun hasPendingRevokeLocked(scope: ResultUploadConsentScope): Boolean {
        check(Thread.holdsLock(processState.attemptRegistry.lock)) {
            "result_upload_registry_lock_required"
        }
        return processState.isRevoking(scope)
    }

    /** Persists a previously begun revoke. Failure intentionally leaves the local off latch set. */
    internal fun completeRevoke(
        token: ResultUploadRevokeToken,
    ): ResultUploadConsentMutationResult = synchronized(processState.persistenceIoLock) complete@{
        var retireToken = false
        try {
            val scope = token.scope
            val blockedByPrivacyReset = synchronized(processState.attemptRegistry.lock) {
                processState.privacyResetToken != null || processState.privacyResetUnavailable
            }
            if (blockedByPrivacyReset) {
                return@complete ResultUploadConsentMutationResult.STATE_UNAVAILABLE
            }
            val generationChanged = synchronized(processState.attemptRegistry.lock) {
                token.capabilityGeneration !== processState.capabilityGeneration
            }
            if (generationChanged) {
                return@complete ResultUploadConsentMutationResult.STATE_UNAVAILABLE
            }
            val tokenIsPending = synchronized(processState.attemptRegistry.lock) {
                token.operationId in processState.pendingRevokeOperations[scope].orEmpty()
            }
            if (!tokenIsPending) {
                markPermanentlyUnavailable(scope)
                return@complete ResultUploadConsentMutationResult.PERSISTENCE_FAILED
            }
            retireToken = true
            val initiallyUnavailable = synchronized(processState.attemptRegistry.lock) {
                processState.revisionExhausted ||
                    scope in processState.permanentlyUnavailableScopes
            }
            if (initiallyUnavailable) {
                return@complete ResultUploadConsentMutationResult.STATE_UNAVAILABLE
            }
            val stored = readPersistenceOrNull() ?: run {
                markPermanentlyUnavailable(scope)
                return@complete ResultUploadConsentMutationResult.PERSISTENCE_FAILED
            }
            if (stored.recordShape() == PersistedConsentRecordShape.CORRUPT) {
                markPermanentlyUnavailable(scope)
                return@complete ResultUploadConsentMutationResult.PERSISTENCE_FAILED
            }
            if (!stored.hasValidConsentEpoch()) {
                markPermanentlyUnavailable(scope)
                return@complete ResultUploadConsentMutationResult.PERSISTENCE_FAILED
            }
            val unavailableBeforeWrite = synchronized(processState.attemptRegistry.lock) {
                processState.revisionExhausted ||
                    scope in processState.permanentlyUnavailableScopes
            }
            if (unavailableBeforeWrite) {
                return@complete ResultUploadConsentMutationResult.STATE_UNAVAILABLE
            }
            if (persistForScope(
                    scope,
                    scope.persisted(
                        state = ResultUploadConsent.REVOKED,
                        consentEpoch = stored.consentEpoch,
                        claimedResultIds = stored.claimedResultIds,
                        authorizedResultId = null,
                    ),
                )
            ) {
                ResultUploadConsentMutationResult.COMMITTED
            } else {
                markPermanentlyUnavailable(scope)
                ResultUploadConsentMutationResult.PERSISTENCE_FAILED
            }
        } catch (cancelled: CancellationException) {
            markPermanentlyUnavailable(token.scope)
            throw cancelled
        } finally {
            if (retireToken) retireRevokeToken(token)
        }
    }

    private fun retireRevokeToken(token: ResultUploadRevokeToken) {
        synchronized(processState.attemptRegistry.lock) {
            processState.pendingRevokeOperations[token.scope]?.let { operations ->
                operations -= token.operationId
                if (operations.isEmpty()) {
                    processState.pendingRevokeOperations -= token.scope
                }
            }
            advanceRevisionLocked()
        }
    }

    fun openAdmission(
        resultId: String,
        scope: ResultUploadConsentScope,
    ): ResultUploadAdmission {
        require(RESULT_ID.matches(resultId)) { "result_upload_result_id_invalid" }
        val snapshot = captureAdmissionSnapshot(resultId, scope)
        return ResultUploadAdmission { adapterConfigured ->
            decideAdmission(
                resultId = resultId,
                scope = scope,
                snapshot = snapshot,
                adapterConfigured = adapterConfigured,
                beforeClaim = {},
            )
        }
    }

    /**
     * Captures the consent epoch without consuming the result claim. The returned ticket can be
     * committed only while that exact epoch is still granted, so revoke/regrant cannot revive an
     * operation that prepared its local result under an older consent generation.
     */
    internal fun prepareAdmission(
        resultId: String,
        scope: ResultUploadConsentScope,
    ): ResultUploadPreparedAdmission {
        require(RESULT_ID.matches(resultId)) { "result_upload_result_id_invalid" }
        val snapshot = captureAdmissionSnapshot(resultId, scope)
        return ResultUploadPreparedAdmission(
            preparationBlocker = snapshot.terminalDecision
                .takeIf {
                    it == ResultUploadAdmissionDecision.AlreadyClaimed ||
                        it == ResultUploadAdmissionDecision.PersistenceFailed
                },
        ) { adapterConfigured, beforeClaim ->
            decideAdmission(
                resultId = resultId,
                scope = scope,
                snapshot = snapshot,
                adapterConfigured = adapterConfigured,
                beforeClaim = beforeClaim,
            )
        }
    }

    private fun captureAdmissionSnapshot(
        resultId: String,
        scope: ResultUploadConsentScope,
    ): PreparedAdmissionSnapshot = synchronized(processState.persistenceIoLock) capture@{
        val stored = readPersistenceOrNull() ?: run {
            markPermanentlyUnavailable(scope)
            return@capture PreparedAdmissionSnapshot(
                terminalDecision = ResultUploadAdmissionDecision.PersistenceFailed,
            )
        }
        synchronized(processState.attemptRegistry.lock) {
            when {
                processState.privacyResetUnavailable ||
                    processState.privacyResetToken != null ||
                    scope in processState.permanentlyUnavailableScopes ||
                    processState.revisionExhausted -> PreparedAdmissionSnapshot(
                    terminalDecision = ResultUploadAdmissionDecision.PersistenceFailed,
                )

                stored.recordShape() == PersistedConsentRecordShape.CORRUPT -> {
                    markPermanentlyUnavailableLocked(scope)
                    PreparedAdmissionSnapshot(
                        terminalDecision = ResultUploadAdmissionDecision.PersistenceFailed,
                    )
                }

                stored.recordShape() == PersistedConsentRecordShape.CANONICAL &&
                    resultId in stored.claimedResultIds -> PreparedAdmissionSnapshot(
                    terminalDecision = ResultUploadAdmissionDecision.AlreadyClaimed,
                )

                processState.isRevoking(scope) -> PreparedAdmissionSnapshot(
                    terminalDecision = ResultUploadAdmissionDecision.NotConsented,
                )

                else -> when (decodeConsent(scope, stored)) {
                    ResultUploadConsent.PERSISTENCE_FAILURE_BLOCKED ->
                        error("result_upload_corrupt_snapshot_unreachable")

                    ResultUploadConsent.UNKNOWN,
                    ResultUploadConsent.REVOKED,
                    ResultUploadConsent.REVOKING,
                    -> PreparedAdmissionSnapshot(
                        terminalDecision = ResultUploadAdmissionDecision.NotConsented,
                    )

                    ResultUploadConsent.GRANTED -> {
                        if (stored.authorizedResultId != null &&
                            stored.authorizedResultId != resultId
                        ) {
                            PreparedAdmissionSnapshot(
                                terminalDecision = ResultUploadAdmissionDecision.NotConsented,
                            )
                        } else {
                            PreparedAdmissionSnapshot(
                                expectedConsentEpoch = stored.consentEpoch,
                                capabilityGeneration = processState.capabilityGeneration,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun decideAdmission(
        resultId: String,
        scope: ResultUploadConsentScope,
        snapshot: PreparedAdmissionSnapshot,
        adapterConfigured: Boolean,
        beforeClaim: () -> Unit,
    ): ResultUploadAdmissionDecision = snapshot.terminalDecision ?: synchronized(
        processState.persistenceIoLock,
    ) decision@{
        val stored = readPersistenceOrNull() ?: run {
            markPermanentlyUnavailable(scope)
            return@decision ResultUploadAdmissionDecision.PersistenceFailed
        }
        val preWriteDecision = synchronized(processState.attemptRegistry.lock) {
            when {
                processState.privacyResetUnavailable ||
                    processState.privacyResetToken != null ->
                    ResultUploadAdmissionDecision.PersistenceFailed

                snapshot.capabilityGeneration !== processState.capabilityGeneration ->
                    ResultUploadAdmissionDecision.AuthorizationChanged

                scope in processState.permanentlyUnavailableScopes ||
                    processState.revisionExhausted ->
                    ResultUploadAdmissionDecision.PersistenceFailed

                processState.isRevoking(scope) ->
                    ResultUploadAdmissionDecision.NotConsented

                else -> when (decodeConsent(scope, stored)) {
                    ResultUploadConsent.PERSISTENCE_FAILURE_BLOCKED -> {
                        markPermanentlyUnavailableLocked(scope)
                        ResultUploadAdmissionDecision.PersistenceFailed
                    }

                    ResultUploadConsent.UNKNOWN ->
                        ResultUploadAdmissionDecision.AuthorizationChanged

                    ResultUploadConsent.REVOKED ->
                        ResultUploadAdmissionDecision.NotConsented

                    ResultUploadConsent.REVOKING ->
                        ResultUploadAdmissionDecision.NotConsented

                    ResultUploadConsent.GRANTED -> when {
                        stored.consentEpoch != snapshot.expectedConsentEpoch ->
                            ResultUploadAdmissionDecision.AuthorizationChanged

                        stored.authorizedResultId != null &&
                            stored.authorizedResultId != resultId ->
                            ResultUploadAdmissionDecision.NotConsented

                        resultId in stored.claimedResultIds ->
                            ResultUploadAdmissionDecision.AlreadyClaimed

                        !adapterConfigured -> ResultUploadAdmissionDecision.NotConfigured

                        else -> {
                            beforeClaim()
                            if (!advanceRevisionLocked()) {
                                processState.permanentlyUnavailableScopes += scope
                                ResultUploadAdmissionDecision.PersistenceFailed
                            } else {
                                null
                            }
                        }

                    }
                }
            }
        }
        if (preWriteDecision != null) {
            return@decision preWriteDecision
        }
        if (!persistForScope(
                scope,
                stored.copy(claimedResultIds = stored.claimedResultIds + resultId),
            )
        ) {
            markPermanentlyUnavailable(scope)
            return@decision ResultUploadAdmissionDecision.PersistenceFailed
        }
        ResultUploadAdmissionDecision.Permitted(
            ResultUploadPermit(
                attemptId = UUID.randomUUID().toString(),
                resultId = resultId,
                scope = scope,
                consentEpoch = stored.consentEpoch,
                capabilityGeneration = checkNotNull(snapshot.capabilityGeneration),
                processState = processState,
            ),
        )
    }

    private fun decodeConsent(
        scope: ResultUploadConsentScope,
        stored: PersistedResultUploadConsent,
    ): ResultUploadConsent = when (stored.recordShape()) {
        PersistedConsentRecordShape.EMPTY,
        PersistedConsentRecordShape.LEGACY_DEFAULT_OFF,
        -> ResultUploadConsent.UNKNOWN

        PersistedConsentRecordShape.CORRUPT ->
            ResultUploadConsent.PERSISTENCE_FAILURE_BLOCKED

        PersistedConsentRecordShape.CANONICAL -> if (!stored.matches(scope)) {
            ResultUploadConsent.UNKNOWN
        } else {
            when (stored.state) {
                ResultUploadConsent.GRANTED.name -> ResultUploadConsent.GRANTED
                ResultUploadConsent.REVOKED.name -> ResultUploadConsent.REVOKED
                else -> error("result_upload_canonical_state_unreachable")
            }
        }
    }

    private fun PersistedResultUploadConsent.recordShape(): PersistedConsentRecordShape {
        val scopeFieldsEmpty = recipient == null && purpose == null && policyVersion == null
        if (state == null &&
            scopeFieldsEmpty &&
            consentEpoch == 0L &&
            claimedResultIds.isEmpty() &&
            authorizedResultId == null
        ) {
            return PersistedConsentRecordShape.EMPTY
        }
        val canonicalScope = hasCanonicalStoredScope()
        if (state == LEGACY_LOWERCASE_GRANTED &&
            canonicalScope &&
            consentEpoch == 0L &&
            claimedResultIds.isEmpty() &&
            authorizedResultId == null
        ) {
            return PersistedConsentRecordShape.LEGACY_DEFAULT_OFF
        }
        if (hasCanonicalConsentState() &&
            canonicalScope &&
            hasValidConsentEpoch() &&
            claimedResultIds.all(RESULT_ID::matches) &&
            (authorizedResultId == null || RESULT_ID.matches(authorizedResultId)) &&
            (state != ResultUploadConsent.REVOKED.name || authorizedResultId == null)
        ) {
            return PersistedConsentRecordShape.CANONICAL
        }
        return PersistedConsentRecordShape.CORRUPT
    }

    private fun PersistedResultUploadConsent.hasCanonicalStoredScope(): Boolean {
        val storedRecipient = recipient ?: return false
        val storedPurpose = ResultUploadPurpose.entries
            .singleOrNull { it.name == purpose }
            ?: return false
        val storedPolicyVersion = policyVersion ?: return false
        return try {
            ResultUploadConsentScope(
                recipient = storedRecipient,
                purpose = storedPurpose,
                policyVersion = storedPolicyVersion,
            )
            true
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun PersistedResultUploadConsent.hasValidConsentEpoch(): Boolean =
        consentEpoch >= 0L && consentEpoch < Long.MAX_VALUE

    private fun PersistedResultUploadConsent.canAdvanceConsentEpoch(): Boolean =
        consentEpoch >= 0L && consentEpoch < Long.MAX_VALUE - 1L

    private fun PersistedResultUploadConsent.matches(scope: ResultUploadConsentScope): Boolean =
        recipient == scope.recipient &&
            purpose == scope.purpose.name &&
            policyVersion == scope.policyVersion

    private fun PersistedResultUploadConsent.hasCanonicalConsentState(): Boolean =
        state == ResultUploadConsent.GRANTED.name || state == ResultUploadConsent.REVOKED.name

    private fun readPersistenceOrNull(): PersistedResultUploadConsent? {
        check(!Thread.holdsLock(processState.attemptRegistry.lock)) {
            "result_upload_persistence_read_under_registry_lock"
        }
        return try {
            persistence.read()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun persistPersistence(value: PersistedResultUploadConsent): Boolean {
        check(!Thread.holdsLock(processState.attemptRegistry.lock)) {
            "result_upload_persistence_write_under_registry_lock"
        }
        return try {
            persistence.write(value)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private fun persistForScope(
        scope: ResultUploadConsentScope,
        value: PersistedResultUploadConsent,
    ): Boolean = try {
        persistPersistence(value)
    } catch (cancelled: CancellationException) {
        markPermanentlyUnavailable(scope)
        throw cancelled
    }

    private fun markPermanentlyUnavailable(scope: ResultUploadConsentScope) {
        synchronized(processState.attemptRegistry.lock) {
            markPermanentlyUnavailableLocked(scope)
        }
    }

    private fun markPermanentlyUnavailableLocked(scope: ResultUploadConsentScope) {
        check(Thread.holdsLock(processState.attemptRegistry.lock)) {
            "result_upload_registry_lock_required"
        }
        if (processState.permanentlyUnavailableScopes.add(scope)) {
            advanceRevisionLocked()
        }
    }

    private fun advanceRevisionLocked(): Boolean {
        check(Thread.holdsLock(processState.attemptRegistry.lock)) {
            "result_upload_registry_lock_required"
        }
        if (processState.revisionExhausted ||
            processState.authorizationRevision == Long.MAX_VALUE
        ) {
            processState.revisionExhausted = true
            return false
        }
        processState.authorizationRevision += 1L
        return true
    }

    private fun currentAuthorizationRevision(): Long =
        synchronized(processState.attemptRegistry.lock) {
            processState.authorizationRevision
        }

    private fun ResultUploadConsentProcessState.isRevoking(
        scope: ResultUploadConsentScope,
    ): Boolean = pendingRevokeOperations[scope]?.isNotEmpty() == true

    private fun ResultUploadConsentProcessState.hasPendingRevoke(): Boolean =
        pendingRevokeOperations.values.any { it.isNotEmpty() }

    private fun ResultUploadConsentScope.persisted(
        state: ResultUploadConsent,
        consentEpoch: Long,
        claimedResultIds: Set<String>,
        authorizedResultId: String?,
    ): PersistedResultUploadConsent = PersistedResultUploadConsent(
        state = state.name,
        recipient = recipient,
        purpose = purpose.name,
        policyVersion = policyVersion,
        consentEpoch = consentEpoch,
        claimedResultIds = claimedResultIds,
        authorizedResultId = authorizedResultId,
    )

    private data class PreparedAdmissionSnapshot(
        val terminalDecision: ResultUploadAdmissionDecision? = null,
        val expectedConsentEpoch: Long? = null,
        val capabilityGeneration: Any? = null,
    )

    private data class GrantPolicySnapshot(
        val authorizationRevision: Long,
        val capabilityGeneration: Any,
    )

    private enum class PersistedConsentRecordShape {
        EMPTY,
        LEGACY_DEFAULT_OFF,
        CANONICAL,
        CORRUPT,
    }

    private class SharedPreferencesConsentPersistence(context: Context) :
        ResultUploadConsentPersistence,
        ResultUploadConsentPrivacyResetPersistence {
        private val prefs = context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE,
        )

        override fun read(): PersistedResultUploadConsent = PersistedResultUploadConsent(
            state = prefs.getString(KEY_STATE, null),
            recipient = prefs.getString(KEY_RECIPIENT, null),
            purpose = prefs.getString(KEY_PURPOSE, null),
            policyVersion = prefs.getString(KEY_POLICY_VERSION, null),
            consentEpoch = prefs.getLong(KEY_CONSENT_EPOCH, 0L),
            claimedResultIds = prefs.getStringSet(KEY_CLAIMED_RESULT_IDS, emptySet())
                ?.toSet()
                .orEmpty(),
            authorizedResultId = prefs.getString(KEY_AUTHORIZED_RESULT_ID, null),
        )

        override fun write(value: PersistedResultUploadConsent): Boolean = prefs.edit()
            .putString(KEY_STATE, value.state)
            .putString(KEY_RECIPIENT, value.recipient)
            .putString(KEY_PURPOSE, value.purpose)
            .putString(KEY_POLICY_VERSION, value.policyVersion)
            .putLong(KEY_CONSENT_EPOCH, value.consentEpoch)
            .putStringSet(KEY_CLAIMED_RESULT_IDS, value.claimedResultIds.toSet())
            .putString(KEY_AUTHORIZED_RESULT_ID, value.authorizedResultId)
            .commit()

        override fun isPhysicallyEmpty(): Boolean = prefs.all.isEmpty()

        override fun clearAndCommit(): Boolean = prefs.edit().clear().commit()
    }

    private companion object {
        const val PREFS_NAME = "result_upload_consent_v1"
        const val KEY_STATE = "state"
        const val KEY_RECIPIENT = "recipient"
        const val KEY_PURPOSE = "purpose"
        const val KEY_POLICY_VERSION = "policy_version"
        const val KEY_CONSENT_EPOCH = "consent_epoch"
        const val KEY_CLAIMED_RESULT_IDS = "claimed_result_ids"
        const val KEY_AUTHORIZED_RESULT_ID = "authorized_result_id"
        const val LEGACY_LOWERCASE_GRANTED = "granted"
        val RESULT_ID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[47][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        )
        val PROCESS_STATE = ResultUploadConsentProcessState()
    }
}

internal sealed interface ResultUploadAdmissionDecision {
    data object NotConsented : ResultUploadAdmissionDecision
    data object AuthorizationChanged : ResultUploadAdmissionDecision
    data object NotConfigured : ResultUploadAdmissionDecision
    data object AlreadyClaimed : ResultUploadAdmissionDecision
    data object PersistenceFailed : ResultUploadAdmissionDecision
    data class Permitted(val permit: ResultUploadPermit) : ResultUploadAdmissionDecision
}

/** A result-scoped capability. Its decision is linearized with live policy and claim persistence. */
internal class ResultUploadAdmission internal constructor(
    private val decideAdmission: (adapterConfigured: Boolean) -> ResultUploadAdmissionDecision,
) {
    fun decide(adapterConfigured: Boolean): ResultUploadAdmissionDecision =
        decideAdmission(adapterConfigured)

    fun claim(): ResultUploadPermit? =
        (decide(adapterConfigured = true) as? ResultUploadAdmissionDecision.Permitted)?.permit
}

/** A non-authorizing ticket. Claim persistence is deferred until local preparation has succeeded. */
internal class ResultUploadPreparedAdmission internal constructor(
    private val preparationBlocker: ResultUploadAdmissionDecision?,
    private val decidePrepared: (
        adapterConfigured: Boolean,
        beforeClaim: () -> Unit,
    ) -> ResultUploadAdmissionDecision,
) {
    fun decisionBeforePreparation(): ResultUploadAdmissionDecision? = preparationBlocker

    /**
     * [beforeClaim] is invoked under the shared policy lock immediately before the durable claim
     * revision advances. Cancellation observed there wins with no claim; after it returns, the
     * claim owns the race and may remain durable even if its caller is cancelled afterward.
     */
    fun decide(
        adapterConfigured: Boolean,
        beforeClaim: () -> Unit = {},
    ): ResultUploadAdmissionDecision = decidePrepared(adapterConfigured, beforeClaim)
}

internal sealed interface ResultUploadPermitAttemptResult<out T> {
    data class Executed<T>(val value: T) : ResultUploadPermitAttemptResult<T>
    data object AuthorizationInvalidated : ResultUploadPermitAttemptResult<Nothing>
}

/** The permit itself is also one-shot, protecting against duplicate internal continuations. */
internal class ResultUploadPermit internal constructor(
    val attemptId: String,
    val resultId: String,
    val scope: ResultUploadConsentScope,
    val consentEpoch: Long,
    internal val capabilityGeneration: Any,
    private val processState: ResultUploadConsentProcessState,
) {
    private val attempted = AtomicBoolean(false)

    suspend fun <T> attempt(block: suspend () -> T): ResultUploadPermitAttemptResult<T> {
        check(attempted.compareAndSet(false, true)) {
            "result_upload_attempt_already_owned"
        }
        val activeToken = Any()
        val valid = synchronized(processState.attemptRegistry.lock) {
            if (capabilityGeneration !== processState.capabilityGeneration ||
                processState.privacyResetToken != null ||
                processState.privacyResetUnavailable
            ) {
                false
            } else {
                processState.activeDirectPermitAttempts += activeToken
                true
            }
        }
        if (!valid) return ResultUploadPermitAttemptResult.AuthorizationInvalidated
        return try {
            ResultUploadPermitAttemptResult.Executed(block())
        } finally {
            synchronized(processState.attemptRegistry.lock) {
                processState.activeDirectPermitAttempts.remove(activeToken)
            }
        }
    }
}
