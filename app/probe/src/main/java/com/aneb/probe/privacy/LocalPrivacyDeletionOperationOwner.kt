package com.aneb.probe.privacy

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal sealed interface LocalPrivacyDeletionOperationState {
    data object Idle : LocalPrivacyDeletionOperationState
    data object Running : LocalPrivacyDeletionOperationState
    data class Finished(
        val result: LocalPrivacyDeletionResult,
    ) : LocalPrivacyDeletionOperationState
    data object Cancelled : LocalPrivacyDeletionOperationState
    data object Failed : LocalPrivacyDeletionOperationState
}

internal sealed interface LocalPrivacyDeletionStart {
    data object Started : LocalPrivacyDeletionStart
    data object AlreadyRunning : LocalPrivacyDeletionStart
}

internal class LocalPrivacyDeletionOperationOwner internal constructor(
    private val operationScope: CoroutineScope,
    private val deleteConfirmed: suspend () -> LocalPrivacyDeletionResult,
) {
    private val monitor = Any()
    private var activeToken: Any? = null
    private var settingsResetToken: Any? = null
    private var claimedSettingsResetToken: Any? = null
    private val mutableState = MutableStateFlow<LocalPrivacyDeletionOperationState>(
        LocalPrivacyDeletionOperationState.Idle,
    )

    val state: StateFlow<LocalPrivacyDeletionOperationState> = mutableState.asStateFlow()

    fun requestConfirmedDeletion(): LocalPrivacyDeletionStart = synchronized(monitor) {
        if (activeToken != null) return@synchronized LocalPrivacyDeletionStart.AlreadyRunning

        val token = Any()
        activeToken = token
        settingsResetToken = null
        mutableState.value = LocalPrivacyDeletionOperationState.Running
        operationScope.launch {
            try {
                val result = deleteConfirmed()
                finish(token, LocalPrivacyDeletionOperationState.Finished(result))
            } catch (cancellation: CancellationException) {
                finish(token, LocalPrivacyDeletionOperationState.Cancelled)
                throw cancellation
            } catch (_: Exception) {
                finish(token, LocalPrivacyDeletionOperationState.Failed)
            }
        }
        LocalPrivacyDeletionStart.Started
    }

    fun claimSettingsReset(): Boolean = synchronized(monitor) {
        val token = settingsResetToken ?: return@synchronized false
        if (claimedSettingsResetToken === token) {
            false
        } else {
            claimedSettingsResetToken = token
            true
        }
    }

    private fun finish(
        token: Any,
        terminalState: LocalPrivacyDeletionOperationState,
    ) {
        synchronized(monitor) {
            if (activeToken === token) {
                mutableState.value = terminalState
                settingsResetToken = if (
                    terminalState is LocalPrivacyDeletionOperationState.Finished &&
                    terminalState.result.settingsDeletionIsComplete()
                ) {
                    token
                } else {
                    null
                }
                activeToken = null
            }
        }
    }

    companion object {
        fun create(context: Context): LocalPrivacyDeletionOperationOwner {
            val applicationContext = context.applicationContext
            val coordinator = LocalPrivacyDeletionAssembly.create(applicationContext)
            return LocalPrivacyDeletionOperationOwner(
                operationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                deleteConfirmed = { coordinator.delete(confirmed = true) },
            )
        }
    }
}

private fun LocalPrivacyDeletionResult.settingsDeletionIsComplete(): Boolean = when (this) {
    is LocalPrivacyDeletionResult.Complete -> receipt.settings.isComplete
    is LocalPrivacyDeletionResult.Incomplete -> receipt.settings.isComplete
    LocalPrivacyDeletionResult.ConfirmationRequired,
    LocalPrivacyDeletionResult.BlockedByActiveRun,
    LocalPrivacyDeletionResult.BlockedByOwnedMutation,
    LocalPrivacyDeletionResult.DeletionInProgress,
    -> false
}
