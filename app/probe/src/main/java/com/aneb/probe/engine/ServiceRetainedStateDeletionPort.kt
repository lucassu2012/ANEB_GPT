package com.aneb.probe.engine

internal sealed interface ServiceRetainedStateDeletionResult {
    data object Cleared : ServiceRetainedStateDeletionResult
    data object AlreadyAbsent : ServiceRetainedStateDeletionResult
    data object ResidueConfirmed : ServiceRetainedStateDeletionResult
}

internal class PrimaryServiceRetainedStateDeletionPort {
    fun clearOwnedReferences(): ServiceRetainedStateDeletionResult {
        val wasDefault = ProbeRunService.retainedStateIsPrivacyDefault()
        ProbeRunService.clearRetainedStateForPrivacyDeletion()
        val isDefault = ProbeRunService.retainedStateIsPrivacyDefault()
        return when {
            !isDefault -> ServiceRetainedStateDeletionResult.ResidueConfirmed
            wasDefault -> ServiceRetainedStateDeletionResult.AlreadyAbsent
            else -> ServiceRetainedStateDeletionResult.Cleared
        }
    }
}

internal class SpecialServiceRetainedStateDeletionPort {
    fun clearOwnedReferences(): ServiceRetainedStateDeletionResult {
        val wasDefault = ProbeSpecialRunService.retainedStateIsPrivacyDefault()
        ProbeSpecialRunService.clearRetainedStateForPrivacyDeletion()
        val isDefault = ProbeSpecialRunService.retainedStateIsPrivacyDefault()
        return when {
            !isDefault -> ServiceRetainedStateDeletionResult.ResidueConfirmed
            wasDefault -> ServiceRetainedStateDeletionResult.AlreadyAbsent
            else -> ServiceRetainedStateDeletionResult.Cleared
        }
    }
}

internal class ServiceRetainedStateDeletionPort {
    fun clearOwnedReferences(): ServiceRetainedStateDeletionResult {
        val wasDefault = ProbeRunService.retainedStateIsPrivacyDefault() &&
            ProbeSpecialRunService.retainedStateIsPrivacyDefault()

        ProbeRunService.clearRetainedStateForPrivacyDeletion()
        ProbeSpecialRunService.clearRetainedStateForPrivacyDeletion()

        val isDefault = ProbeRunService.retainedStateIsPrivacyDefault() &&
            ProbeSpecialRunService.retainedStateIsPrivacyDefault()
        return when {
            !isDefault -> ServiceRetainedStateDeletionResult.ResidueConfirmed
            wasDefault -> ServiceRetainedStateDeletionResult.AlreadyAbsent
            else -> ServiceRetainedStateDeletionResult.Cleared
        }
    }
}
