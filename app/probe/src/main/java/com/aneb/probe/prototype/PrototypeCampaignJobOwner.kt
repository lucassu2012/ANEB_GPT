package com.aneb.probe.prototype

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class PrototypeCampaignConfig(
    val nodeTicket: CompatibleNodeTicket,
    val campaignId: String,
) {
    val nodeBaseUrl: String
        get() = nodeTicket.nodeBaseUrl
}

fun interface PrototypeCampaignExecutor {
    suspend fun execute(config: PrototypeCampaignConfig): PrototypeQuickCampaignRunner.CampaignResult
}

/**
 * Process-local state for one owned Prototype campaign coroutine.
 *
 * Finished means only that the executor returned a result. Cancelled means only that this owner
 * acknowledged a user cancellation. Neither state claims persistence or canonical partial evidence.
 */
sealed interface PrototypeCampaignSession {
    data object Idle : PrototypeCampaignSession

    data class Running(
        val config: PrototypeCampaignConfig,
    ) : PrototypeCampaignSession

    data class Cancelling(
        val config: PrototypeCampaignConfig,
    ) : PrototypeCampaignSession

    data class Finished(
        val config: PrototypeCampaignConfig,
        val result: PrototypeQuickCampaignRunner.CampaignResult,
    ) : PrototypeCampaignSession

    data class Failed(
        val config: PrototypeCampaignConfig,
        val message: String,
    ) : PrototypeCampaignSession

    data class Cancelled(
        val config: PrototypeCampaignConfig,
    ) : PrototypeCampaignSession
}

/** Owns at most one process-local Prototype campaign job at a time. */
class PrototypeCampaignJobOwner(
    private val scope: CoroutineScope,
    private val executor: PrototypeCampaignExecutor,
    private val publish: (PrototypeCampaignSession) -> Unit,
) {
    private class RunToken(
        val config: PrototypeCampaignConfig,
    ) {
        lateinit var job: Job
        var cancellationRequested = false
        var result: PrototypeQuickCampaignRunner.CampaignResult? = null
        var failureMessage: String? = null
    }

    private val lock = Any()
    private var active: RunToken? = null

    fun start(config: PrototypeCampaignConfig): Boolean {
        val token = synchronized(lock) {
            if (active != null) return false

            RunToken(config).also { created ->
                created.job = scope.launch(start = CoroutineStart.LAZY) {
                    execute(created)
                }
                active = created
                created.job.invokeOnCompletion { cause -> completeAfterExit(created, cause) }
                publish(PrototypeCampaignSession.Running(config))
            }
        }

        token.job.start()
        return true
    }

    fun cancel(): Boolean {
        synchronized(lock) {
            val current = active ?: return false
            if (current.cancellationRequested) return false
            current.cancellationRequested = true
            publish(PrototypeCampaignSession.Cancelling(current.config))
            current.job.cancel(CancellationException(USER_CANCELLED))
        }
        return true
    }

    private suspend fun execute(token: RunToken) {
        try {
            val result = executor.execute(token.config)
            synchronized(lock) { token.result = result }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            synchronized(lock) {
                token.failureMessage = failure.message ?: "prototype campaign failed"
            }
        }
    }

    private fun completeAfterExit(token: RunToken, cause: Throwable?) {
        synchronized(lock) {
            if (active !== token) return
            val terminal = when {
                token.cancellationRequested -> PrototypeCampaignSession.Cancelled(token.config)
                token.result != null -> PrototypeCampaignSession.Finished(
                    token.config,
                    checkNotNull(token.result),
                )
                else -> PrototypeCampaignSession.Failed(
                    token.config,
                    token.failureMessage ?: cause?.message ?: "prototype campaign stopped",
                )
            }
            try {
                publish(terminal)
            } finally {
                active = null
            }
        }
    }

    private companion object {
        const val USER_CANCELLED = "prototype campaign cancelled by user"
    }
}
