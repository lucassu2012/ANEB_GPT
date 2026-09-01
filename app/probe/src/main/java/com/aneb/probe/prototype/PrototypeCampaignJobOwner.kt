package com.aneb.probe.prototype

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

data class PrototypeCampaignConfig(
    val nodeTicket: CompatibleNodeTicket,
    val campaignId: String,
    val campaignMode: PrototypeQuickCampaignRunner.CampaignMode =
        PrototypeQuickCampaignRunner.CampaignMode.QUICK,
) {
    val nodeBaseUrl: String
        get() = nodeTicket.nodeBaseUrl
}

fun interface PrototypeCampaignExecutor {
    suspend fun execute(config: PrototypeCampaignConfig): PrototypeQuickCampaignRunner.CampaignResult
}

/** Atomically orders a completed runner result ahead of a competing user cancellation. */
internal class PrototypeCampaignResultReadyAuthority(
    private val capture: (() -> Unit) -> Boolean,
) : AbstractCoroutineContextElement(Key) {
    fun claimCompletedOutcome(): Boolean = capture {}

    fun captureCompletedResult(captureResult: () -> Unit): Boolean = capture(captureResult)

    companion object Key : CoroutineContext.Key<PrototypeCampaignResultReadyAuthority>
}

/**
 * Process-local state for one owned Prototype campaign coroutine.
 *
 * Finished means that the executor returned a persisted result. Cancelled means that a canonical
 * cancellation result was persisted before this owner published the terminal state.
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
        val cancellationAuthority = PrototypeUserCancellationAuthority()
        lateinit var job: Job
        var cancellationRequested = false
        var resultReadyToPersist = false
        var result: PrototypeQuickCampaignRunner.CampaignResult? = null
        var persistedCancellationResult: PrototypeQuickCampaignRunner.CampaignResult? = null
        var failureMessage: String? = null
    }

    private val lock = Any()
    private var active: RunToken? = null

    fun start(config: PrototypeCampaignConfig): Boolean {
        val token = synchronized(lock) {
            if (active != null) return false

            RunToken(config).also { created ->
                created.job = scope.launch(
                    context = created.cancellationAuthority +
                        PrototypeCampaignResultReadyAuthority { captureResult ->
                            synchronized(lock) {
                                if (active !== created || created.cancellationRequested) {
                                    false
                                } else {
                                    captureResult()
                                    created.resultReadyToPersist = true
                                    true
                                }
                            }
                        },
                    start = CoroutineStart.LAZY,
                ) {
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
            if (current.resultReadyToPersist) return false
            current.cancellationRequested = true
            check(current.cancellationAuthority.request())
            publish(PrototypeCampaignSession.Cancelling(current.config))
            current.job.cancel(CancellationException(USER_CANCELLED))
        }
        return true
    }

    private suspend fun execute(token: RunToken) {
        try {
            val result = executor.execute(token.config)
            synchronized(lock) { token.result = result }
        } catch (persisted: PrototypeCampaignCancellationPersisted) {
            synchronized(lock) { token.persistedCancellationResult = persisted.result }
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
                token.result != null -> PrototypeCampaignSession.Finished(
                    token.config,
                    checkNotNull(token.result),
                )
                token.persistedCancellationResult != null ->
                    PrototypeCampaignSession.Cancelled(token.config)
                token.failureMessage != null -> PrototypeCampaignSession.Failed(
                    token.config,
                    checkNotNull(token.failureMessage),
                )
                token.cancellationRequested -> PrototypeCampaignSession.Failed(
                    token.config,
                    CANCELLATION_NOT_PERSISTED,
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
        const val CANCELLATION_NOT_PERSISTED =
            "prototype campaign cancellation evidence was not persisted"
    }
}
