package com.aneb.probe.engine

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

internal enum class ResultUploadConsent {
    UNKNOWN,
    GRANTED,
    REVOKED,
    PERSISTENCE_FAILURE_BLOCKED,
}

internal enum class ResultUploadConsentMutationResult {
    COMMITTED,
    PERSISTENCE_FAILED,
    BLOCKED_BY_PROCESS_REVOCATION,
}

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
    val claimedResultIds: Set<String> = emptySet(),
)

internal interface ResultUploadConsentPersistence {
    fun read(): PersistedResultUploadConsent

    fun write(value: PersistedResultUploadConsent): Boolean
}

/** Process-local fail-closed state shared by every production store reconstruction. */
internal class ResultUploadConsentProcessState {
    internal val absorbingOffScopes = mutableSetOf<ResultUploadConsentScope>()
}

/**
 * Independent result-upload policy store. It is deliberately separate from launch settings so
 * callers cannot smuggle a stale consent Boolean through RunConfig or an Intent.
 */
internal class ResultUploadConsentStore internal constructor(
    private val persistence: ResultUploadConsentPersistence,
    private val processState: ResultUploadConsentProcessState = ResultUploadConsentProcessState(),
) {
    constructor(context: Context) : this(
        SharedPreferencesConsentPersistence(context),
        PROCESS_STATE,
    )

    fun currentConsent(scope: ResultUploadConsentScope): ResultUploadConsent = synchronized(POLICY_LOCK) {
        currentConsentLocked(scope)
    }

    fun grant(scope: ResultUploadConsentScope): ResultUploadConsentMutationResult =
        synchronized(POLICY_LOCK) {
            if (scope in processState.absorbingOffScopes) {
                return@synchronized ResultUploadConsentMutationResult.BLOCKED_BY_PROCESS_REVOCATION
            }
            val stored = readLockedOrNull() ?: run {
                processState.absorbingOffScopes += scope
                return@synchronized ResultUploadConsentMutationResult.PERSISTENCE_FAILED
            }
            if (persistLocked(scope.persisted(ResultUploadConsent.GRANTED, stored.claimedResultIds))) {
                ResultUploadConsentMutationResult.COMMITTED
            } else {
                processState.absorbingOffScopes += scope
                ResultUploadConsentMutationResult.PERSISTENCE_FAILED
            }
        }

    fun revoke(scope: ResultUploadConsentScope): ResultUploadConsentMutationResult =
        synchronized(POLICY_LOCK) {
            processState.absorbingOffScopes += scope
            val stored = readLockedOrNull()
                ?: return@synchronized ResultUploadConsentMutationResult.PERSISTENCE_FAILED
            if (persistLocked(scope.persisted(ResultUploadConsent.REVOKED, stored.claimedResultIds))) {
                processState.absorbingOffScopes -= scope
                ResultUploadConsentMutationResult.COMMITTED
            } else {
                ResultUploadConsentMutationResult.PERSISTENCE_FAILED
            }
        }

    fun openAdmission(
        resultId: String,
        scope: ResultUploadConsentScope,
    ): ResultUploadAdmission {
        require(RESULT_ID.matches(resultId)) { "result_upload_result_id_invalid" }
        return ResultUploadAdmission { adapterConfigured ->
            synchronized(POLICY_LOCK) {
                val stored = readLockedOrNull()
                if (stored == null) {
                    processState.absorbingOffScopes += scope
                    ResultUploadAdmissionDecision.PersistenceFailed
                } else when (currentConsentLocked(scope, stored)) {
                    ResultUploadConsent.PERSISTENCE_FAILURE_BLOCKED ->
                        ResultUploadAdmissionDecision.PersistenceFailed

                    ResultUploadConsent.UNKNOWN,
                    ResultUploadConsent.REVOKED,
                    -> ResultUploadAdmissionDecision.NotConsented

                    ResultUploadConsent.GRANTED -> when {
                        resultId in stored.claimedResultIds ->
                            ResultUploadAdmissionDecision.AlreadyClaimed

                        !adapterConfigured -> ResultUploadAdmissionDecision.NotConfigured

                        !persistLocked(
                            stored.copy(claimedResultIds = stored.claimedResultIds + resultId),
                        ) -> {
                            processState.absorbingOffScopes += scope
                            ResultUploadAdmissionDecision.PersistenceFailed
                        }

                        else -> ResultUploadAdmissionDecision.Permitted(
                            ResultUploadPermit(
                                attemptId = UUID.randomUUID().toString(),
                                resultId = resultId,
                                scope = scope,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun currentConsentLocked(scope: ResultUploadConsentScope): ResultUploadConsent {
        val stored = readLockedOrNull() ?: run {
            processState.absorbingOffScopes += scope
            return ResultUploadConsent.PERSISTENCE_FAILURE_BLOCKED
        }
        return currentConsentLocked(scope, stored)
    }

    private fun currentConsentLocked(
        scope: ResultUploadConsentScope,
        stored: PersistedResultUploadConsent,
    ): ResultUploadConsent {
        if (scope in processState.absorbingOffScopes &&
            (stored.state != ResultUploadConsent.REVOKED.name ||
                stored.recipient != scope.recipient ||
                stored.purpose != scope.purpose.name ||
                stored.policyVersion != scope.policyVersion)
        ) {
            return ResultUploadConsent.PERSISTENCE_FAILURE_BLOCKED
        }
        if (stored.recipient != scope.recipient ||
            stored.purpose != scope.purpose.name ||
            stored.policyVersion != scope.policyVersion
        ) {
            return ResultUploadConsent.UNKNOWN
        }
        return when (stored.state) {
            ResultUploadConsent.GRANTED.name -> ResultUploadConsent.GRANTED
            ResultUploadConsent.REVOKED.name -> ResultUploadConsent.REVOKED
            else -> ResultUploadConsent.UNKNOWN
        }
    }

    private fun readLockedOrNull(): PersistedResultUploadConsent? = try {
        persistence.read()
    } catch (_: Exception) {
        null
    }

    private fun persistLocked(value: PersistedResultUploadConsent): Boolean = try {
        persistence.write(value)
    } catch (_: Exception) {
        false
    }

    private fun ResultUploadConsentScope.persisted(
        state: ResultUploadConsent,
        claimedResultIds: Set<String>,
    ): PersistedResultUploadConsent = PersistedResultUploadConsent(
        state = state.name,
        recipient = recipient,
        purpose = purpose.name,
        policyVersion = policyVersion,
        claimedResultIds = claimedResultIds,
    )

    private class SharedPreferencesConsentPersistence(context: Context) :
        ResultUploadConsentPersistence {
        private val prefs = context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE,
        )

        override fun read(): PersistedResultUploadConsent = PersistedResultUploadConsent(
            state = prefs.getString(KEY_STATE, null),
            recipient = prefs.getString(KEY_RECIPIENT, null),
            purpose = prefs.getString(KEY_PURPOSE, null),
            policyVersion = prefs.getString(KEY_POLICY_VERSION, null),
            claimedResultIds = prefs.getStringSet(KEY_CLAIMED_RESULT_IDS, emptySet())
                ?.toSet()
                .orEmpty(),
        )

        override fun write(value: PersistedResultUploadConsent): Boolean = prefs.edit()
            .putString(KEY_STATE, value.state)
            .putString(KEY_RECIPIENT, value.recipient)
            .putString(KEY_PURPOSE, value.purpose)
            .putString(KEY_POLICY_VERSION, value.policyVersion)
            .putStringSet(KEY_CLAIMED_RESULT_IDS, value.claimedResultIds.toSet())
            .commit()
    }

    private companion object {
        const val PREFS_NAME = "result_upload_consent_v1"
        const val KEY_STATE = "state"
        const val KEY_RECIPIENT = "recipient"
        const val KEY_PURPOSE = "purpose"
        const val KEY_POLICY_VERSION = "policy_version"
        const val KEY_CLAIMED_RESULT_IDS = "claimed_result_ids"
        val RESULT_ID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[47][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        )
        val POLICY_LOCK = Any()
        val PROCESS_STATE = ResultUploadConsentProcessState()
    }
}

internal sealed interface ResultUploadAdmissionDecision {
    data object NotConsented : ResultUploadAdmissionDecision
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

/** The permit itself is also one-shot, protecting against duplicate internal continuations. */
internal class ResultUploadPermit internal constructor(
    val attemptId: String,
    val resultId: String,
    val scope: ResultUploadConsentScope,
) {
    private val attempted = AtomicBoolean(false)

    suspend fun <T> attempt(block: suspend () -> T): T {
        check(attempted.compareAndSet(false, true)) {
            "result_upload_attempt_already_owned"
        }
        return block()
    }
}
