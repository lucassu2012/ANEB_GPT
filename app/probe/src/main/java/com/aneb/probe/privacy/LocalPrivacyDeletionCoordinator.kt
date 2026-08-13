package com.aneb.probe.privacy

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

internal enum class LocalPrivacyOwnedDomain(val wireValue: String) {
    CONSENT("CONSENT"),
    ROOM("ROOM"),
    SETTINGS("SETTINGS"),
    EXTERNAL_NETLOG("EXTERNAL_NETLOG"),
    INTERNAL_NETLOG("INTERNAL_NETLOG"),
    GATEWAY_VAULT("GATEWAY_VAULT"),
    PRIMARY_RUNTIME("PRIMARY_RUNTIME"),
    SPECIAL_RUNTIME("SPECIAL_RUNTIME"),
}

internal enum class LocalPrivacyDomainDeletionResult(val wireValue: String) {
    CLEARED("CLEARED"),
    ALREADY_ABSENT("ALREADY_ABSENT"),
    RESIDUE_CONFIRMED("RESIDUE_CONFIRMED"),
    STATE_UNAVAILABLE("STATE_UNAVAILABLE"),
    ;

    val isComplete: Boolean
        get() = this == CLEARED || this == ALREADY_ABSENT
}

internal fun interface LocalPrivacyDomainDeletionPort {
    suspend fun deleteOwnedData(): LocalPrivacyDomainDeletionResult
}

internal data class LocalPrivacyExact8Receipt(
    val consent: LocalPrivacyDomainDeletionResult,
    val room: LocalPrivacyDomainDeletionResult,
    val settings: LocalPrivacyDomainDeletionResult,
    val externalNetlog: LocalPrivacyDomainDeletionResult,
    val internalNetlog: LocalPrivacyDomainDeletionResult,
    val gatewayVault: LocalPrivacyDomainDeletionResult,
    val primaryRuntime: LocalPrivacyDomainDeletionResult,
    val specialRuntime: LocalPrivacyDomainDeletionResult,
) {
    val isComplete: Boolean
        get() = consent.isComplete &&
            room.isComplete &&
            settings.isComplete &&
            externalNetlog.isComplete &&
            internalNetlog.isComplete &&
            gatewayVault.isComplete &&
            primaryRuntime.isComplete &&
            specialRuntime.isComplete
}

internal sealed interface LocalPrivacyDeletionResult {
    data object ConfirmationRequired : LocalPrivacyDeletionResult
    data object BlockedByActiveRun : LocalPrivacyDeletionResult
    data object BlockedByOwnedMutation : LocalPrivacyDeletionResult
    data object DeletionInProgress : LocalPrivacyDeletionResult
    data class Complete(val receipt: LocalPrivacyExact8Receipt) : LocalPrivacyDeletionResult
    data class Incomplete(val receipt: LocalPrivacyExact8Receipt) : LocalPrivacyDeletionResult
}

internal sealed interface LocalPrivacyDeletionAdmission {
    data class Acquired(val lease: LocalPrivacyDeletionLease) : LocalPrivacyDeletionAdmission
    data object BlockedByActiveRun : LocalPrivacyDeletionAdmission
    data object BlockedByOwnedMutation : LocalPrivacyDeletionAdmission
    data object DeletionInProgress : LocalPrivacyDeletionAdmission
}

internal enum class ProbeOwnedRun {
    PRIMARY,
    SPECIAL_CONTINUITY,
    SPECIAL_PROTOCOL_AB,
    API_PROBE,
}

internal sealed interface LocalPrivacyRunAdmission {
    data class Acquired(val lease: LocalPrivacyRunLease) : LocalPrivacyRunAdmission
    data object RunAlreadyActive : LocalPrivacyRunAdmission
    data object DeletionInProgress : LocalPrivacyRunAdmission
}

internal sealed interface LocalPrivacyOwnedMutationAdmission {
    data class Acquired(val lease: LocalPrivacyOwnedMutationLease) : LocalPrivacyOwnedMutationAdmission
    data object DeletionInProgress : LocalPrivacyOwnedMutationAdmission
}

internal class LocalPrivacyRunLease internal constructor(
    val owner: ProbeOwnedRun,
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

internal class LocalPrivacyDeletionLease internal constructor(
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

internal class LocalPrivacyOwnedMutationLease internal constructor(
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

internal class LocalPrivacyOperationGate {
    private val monitor = Any()
    private var activeRunToken: Any? = null
    private var deletionToken: Any? = null
    private val ownedMutationTokens = linkedSetOf<Any>()

    fun tryAcquireRun(owner: ProbeOwnedRun): LocalPrivacyRunAdmission = synchronized(monitor) {
        when {
            deletionToken != null -> LocalPrivacyRunAdmission.DeletionInProgress
            activeRunToken != null -> LocalPrivacyRunAdmission.RunAlreadyActive
            else -> {
                val token = Any()
                activeRunToken = token
                LocalPrivacyRunAdmission.Acquired(
                    LocalPrivacyRunLease(owner) {
                        synchronized(monitor) {
                            if (activeRunToken === token) activeRunToken = null
                        }
                    },
                )
            }
        }
    }

    fun tryAcquireDeletion(): LocalPrivacyDeletionAdmission = synchronized(monitor) {
        when {
            deletionToken != null -> LocalPrivacyDeletionAdmission.DeletionInProgress
            activeRunToken != null -> LocalPrivacyDeletionAdmission.BlockedByActiveRun
            ownedMutationTokens.isNotEmpty() ->
                LocalPrivacyDeletionAdmission.BlockedByOwnedMutation
            else -> {
                val token = Any()
                deletionToken = token
                LocalPrivacyDeletionAdmission.Acquired(
                    LocalPrivacyDeletionLease {
                        synchronized(monitor) {
                            if (deletionToken === token) deletionToken = null
                        }
                    },
                )
            }
        }
    }

    fun tryAcquireOwnedMutation(): LocalPrivacyOwnedMutationAdmission = synchronized(monitor) {
        if (deletionToken != null) {
            LocalPrivacyOwnedMutationAdmission.DeletionInProgress
        } else {
            val token = Any()
            ownedMutationTokens += token
            LocalPrivacyOwnedMutationAdmission.Acquired(
                LocalPrivacyOwnedMutationLease {
                    synchronized(monitor) { ownedMutationTokens.remove(token) }
                },
            )
        }
    }
}

internal class LocalPrivacyDeletionCoordinator(
    private val operationGate: LocalPrivacyOperationGate,
    private val consentDeletion: LocalPrivacyDomainDeletionPort,
    private val roomDeletion: LocalPrivacyDomainDeletionPort,
    private val settingsDeletion: LocalPrivacyDomainDeletionPort,
    private val externalNetlogDeletion: LocalPrivacyDomainDeletionPort,
    private val internalNetlogDeletion: LocalPrivacyDomainDeletionPort,
    private val gatewayVaultDeletion: LocalPrivacyDomainDeletionPort,
    private val primaryRuntimeDeletion: LocalPrivacyDomainDeletionPort,
    private val specialRuntimeDeletion: LocalPrivacyDomainDeletionPort,
) {
    suspend fun delete(confirmed: Boolean): LocalPrivacyDeletionResult {
        if (!confirmed) return LocalPrivacyDeletionResult.ConfirmationRequired

        return when (val admission = operationGate.tryAcquireDeletion()) {
            LocalPrivacyDeletionAdmission.BlockedByActiveRun ->
                LocalPrivacyDeletionResult.BlockedByActiveRun

            LocalPrivacyDeletionAdmission.BlockedByOwnedMutation ->
                LocalPrivacyDeletionResult.BlockedByOwnedMutation

            LocalPrivacyDeletionAdmission.DeletionInProgress ->
                LocalPrivacyDeletionResult.DeletionInProgress

            is LocalPrivacyDeletionAdmission.Acquired -> {
                try {
                    val receipt = deleteEveryFixedDomain()
                    if (receipt.isComplete) {
                        LocalPrivacyDeletionResult.Complete(receipt)
                    } else {
                        LocalPrivacyDeletionResult.Incomplete(receipt)
                    }
                } finally {
                    admission.lease.close()
                }
            }
        }
    }

    private suspend fun deleteEveryFixedDomain(): LocalPrivacyExact8Receipt {
        val consent = deleteDomain(consentDeletion)
        val room = deleteDomain(roomDeletion)
        val settings = deleteDomain(settingsDeletion)
        val externalNetlog = deleteDomain(externalNetlogDeletion)
        val internalNetlog = deleteDomain(internalNetlogDeletion)
        val gatewayVault = deleteDomain(gatewayVaultDeletion)
        val primaryRuntime = deleteDomain(primaryRuntimeDeletion)
        val specialRuntime = deleteDomain(specialRuntimeDeletion)
        return LocalPrivacyExact8Receipt(
            consent = consent,
            room = room,
            settings = settings,
            externalNetlog = externalNetlog,
            internalNetlog = internalNetlog,
            gatewayVault = gatewayVault,
            primaryRuntime = primaryRuntime,
            specialRuntime = specialRuntime,
        )
    }

    private suspend fun deleteDomain(
        deletion: LocalPrivacyDomainDeletionPort,
    ): LocalPrivacyDomainDeletionResult = try {
        deletion.deleteOwnedData()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        LocalPrivacyDomainDeletionResult.STATE_UNAVAILABLE
    }
}
