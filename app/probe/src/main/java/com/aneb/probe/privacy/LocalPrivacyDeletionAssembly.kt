package com.aneb.probe.privacy

import android.content.Context
import com.aneb.probe.data.AnebDatabase
import com.aneb.probe.engine.GatewayCredentialPrivacyDeletionPort
import com.aneb.probe.engine.GatewayCredentialPrivacyDeletionResult
import com.aneb.probe.engine.PrimaryServiceRetainedStateDeletionPort
import com.aneb.probe.engine.ResultUploadConsentStore
import com.aneb.probe.engine.ResultUploadPrivacyResetResult
import com.aneb.probe.engine.ServiceRetainedStateDeletionResult
import com.aneb.probe.engine.SpecialServiceRetainedStateDeletionPort

internal class LocalPrivacyDeletionDomainOperations(
    val consent: suspend () -> ResultUploadPrivacyResetResult,
    val room: suspend () -> OwnedRoomDeletionResult,
    val settings: suspend () -> ProbeSettingsDeletionResult,
    val externalNetlog: suspend () -> ExactNetlogDeletionResult,
    val internalNetlog: suspend () -> ExactNetlogDeletionResult,
    val gatewayVault: suspend () -> GatewayCredentialPrivacyDeletionResult,
    val primaryRuntime: suspend () -> ServiceRetainedStateDeletionResult,
    val specialRuntime: suspend () -> ServiceRetainedStateDeletionResult,
)

internal object LocalPrivacyDeletionAssembly {
    fun create(context: Context): LocalPrivacyDeletionCoordinator {
        val applicationContext = context.applicationContext
        return create(
            LocalPrivacyDeletionDomainOperations(
                consent = {
                    ResultUploadConsentStore(applicationContext).resetForPrivacyDeletion()
                },
                room = {
                    AndroidOwnedRoomDeletionPort(
                        databaseProvider = { AnebDatabase.get(applicationContext) },
                    ).deleteOwnedRoomData()
                },
                settings = {
                    AndroidProbeSettingsDeletionPort(applicationContext).deleteOwnedSettings()
                },
                externalNetlog = {
                    ExternalNetlogDeletionPort(applicationContext).deleteOwnedNetlogs()
                },
                internalNetlog = {
                    InternalNetlogDeletionPort(applicationContext).deleteOwnedNetlog()
                },
                gatewayVault = {
                    GatewayCredentialPrivacyDeletionPort().clearOwnedReferences()
                },
                primaryRuntime = {
                    PrimaryServiceRetainedStateDeletionPort().clearOwnedReferences()
                },
                specialRuntime = {
                    SpecialServiceRetainedStateDeletionPort().clearOwnedReferences()
                },
            ),
        )
    }

    fun create(
        operations: LocalPrivacyDeletionDomainOperations,
    ): LocalPrivacyDeletionCoordinator = LocalPrivacyDeletionCoordinator(
        operationGate = LocalPrivacyProcessOperations.operationGate,
        consentDeletion = LocalPrivacyDomainDeletionPort {
            operations.consent().toLocalPrivacyResult()
        },
        roomDeletion = LocalPrivacyDomainDeletionPort {
            operations.room().toLocalPrivacyResult()
        },
        settingsDeletion = LocalPrivacyDomainDeletionPort {
            operations.settings().toLocalPrivacyResult()
        },
        externalNetlogDeletion = LocalPrivacyDomainDeletionPort {
            operations.externalNetlog().toLocalPrivacyResult()
        },
        internalNetlogDeletion = LocalPrivacyDomainDeletionPort {
            operations.internalNetlog().toLocalPrivacyResult()
        },
        gatewayVaultDeletion = LocalPrivacyDomainDeletionPort {
            operations.gatewayVault().toLocalPrivacyResult()
        },
        primaryRuntimeDeletion = LocalPrivacyDomainDeletionPort {
            operations.primaryRuntime().toLocalPrivacyResult()
        },
        specialRuntimeDeletion = LocalPrivacyDomainDeletionPort {
            operations.specialRuntime().toLocalPrivacyResult()
        },
    )
}

private fun ResultUploadPrivacyResetResult.toLocalPrivacyResult():
    LocalPrivacyDomainDeletionResult = when (this) {
    ResultUploadPrivacyResetResult.Cleared -> LocalPrivacyDomainDeletionResult.CLEARED
    ResultUploadPrivacyResetResult.AlreadyEmpty ->
        LocalPrivacyDomainDeletionResult.ALREADY_ABSENT
    ResultUploadPrivacyResetResult.BlockedByActiveAttempt,
    ResultUploadPrivacyResetResult.BlockedByPendingRevoke,
    ResultUploadPrivacyResetResult.ResetInProgress,
    ResultUploadPrivacyResetResult.StateUnavailable,
    -> LocalPrivacyDomainDeletionResult.STATE_UNAVAILABLE
}

private fun OwnedRoomDeletionResult.toLocalPrivacyResult(): LocalPrivacyDomainDeletionResult =
    when (this) {
        is OwnedRoomDeletionResult.Deleted -> when {
            after.hasOwnedRoomState() -> LocalPrivacyDomainDeletionResult.RESIDUE_CONFIRMED
            before.hasOwnedRoomState() -> LocalPrivacyDomainDeletionResult.CLEARED
            else -> LocalPrivacyDomainDeletionResult.ALREADY_ABSENT
        }
        is OwnedRoomDeletionResult.ResidueDetected ->
            LocalPrivacyDomainDeletionResult.RESIDUE_CONFIRMED
        is OwnedRoomDeletionResult.SchemaMismatch,
        is OwnedRoomDeletionResult.Failed,
        -> LocalPrivacyDomainDeletionResult.STATE_UNAVAILABLE
    }

private fun OwnedRoomInventory.hasOwnedRoomState(): Boolean =
    rows.values.any { it != 0L } || sequences.isNotEmpty()

private fun ProbeSettingsDeletionResult.toLocalPrivacyResult():
    LocalPrivacyDomainDeletionResult = when (this) {
    ProbeSettingsDeletionResult.Cleared -> LocalPrivacyDomainDeletionResult.CLEARED
    ProbeSettingsDeletionResult.AlreadyAbsent -> LocalPrivacyDomainDeletionResult.ALREADY_ABSENT
    ProbeSettingsDeletionResult.ResidueConfirmed ->
        LocalPrivacyDomainDeletionResult.RESIDUE_CONFIRMED
    ProbeSettingsDeletionResult.StateUnavailable ->
        LocalPrivacyDomainDeletionResult.STATE_UNAVAILABLE
}

private fun ExactNetlogDeletionResult.toLocalPrivacyResult():
    LocalPrivacyDomainDeletionResult = when (this) {
    is ExactNetlogDeletionResult.Cleared -> LocalPrivacyDomainDeletionResult.CLEARED
    ExactNetlogDeletionResult.AlreadyAbsent -> LocalPrivacyDomainDeletionResult.ALREADY_ABSENT
    is ExactNetlogDeletionResult.ResidueConfirmed ->
        LocalPrivacyDomainDeletionResult.RESIDUE_CONFIRMED
    ExactNetlogDeletionResult.StateUnavailable ->
        LocalPrivacyDomainDeletionResult.STATE_UNAVAILABLE
}

private fun GatewayCredentialPrivacyDeletionResult.toLocalPrivacyResult():
    LocalPrivacyDomainDeletionResult = when (this) {
    is GatewayCredentialPrivacyDeletionResult.Cleared ->
        LocalPrivacyDomainDeletionResult.CLEARED
    GatewayCredentialPrivacyDeletionResult.AlreadyAbsent ->
        LocalPrivacyDomainDeletionResult.ALREADY_ABSENT
    is GatewayCredentialPrivacyDeletionResult.ResidueConfirmed ->
        LocalPrivacyDomainDeletionResult.RESIDUE_CONFIRMED
}

private fun ServiceRetainedStateDeletionResult.toLocalPrivacyResult():
    LocalPrivacyDomainDeletionResult = when (this) {
    ServiceRetainedStateDeletionResult.Cleared -> LocalPrivacyDomainDeletionResult.CLEARED
    ServiceRetainedStateDeletionResult.AlreadyAbsent ->
        LocalPrivacyDomainDeletionResult.ALREADY_ABSENT
    ServiceRetainedStateDeletionResult.ResidueConfirmed ->
        LocalPrivacyDomainDeletionResult.RESIDUE_CONFIRMED
}
