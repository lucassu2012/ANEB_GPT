package com.aneb.probe.engine

internal sealed interface GatewayCredentialPrivacyDeletionResult {
    data class Cleared(val referenceCount: Int) : GatewayCredentialPrivacyDeletionResult
    data object AlreadyAbsent : GatewayCredentialPrivacyDeletionResult
    data class ResidueConfirmed(val referenceCount: Int) : GatewayCredentialPrivacyDeletionResult
}

internal class GatewayCredentialPrivacyDeletionPort {
    fun clearOwnedReferences(): GatewayCredentialPrivacyDeletionResult {
        val clearedCount = GatewayCredentialVault.clearForPrivacyDeletion()
        val remainingCount = GatewayCredentialVault.ownedReferenceCount()
        return when {
            remainingCount != 0 ->
                GatewayCredentialPrivacyDeletionResult.ResidueConfirmed(remainingCount)
            clearedCount == 0 -> GatewayCredentialPrivacyDeletionResult.AlreadyAbsent
            else -> GatewayCredentialPrivacyDeletionResult.Cleared(clearedCount)
        }
    }
}
