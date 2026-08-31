package com.aneb.probe.engine

/** One process-local execution slot shared by every measurement Service. */
internal class ProbeExecutionLease {
    internal class Token internal constructor()

    private val lock = Any()
    private var activeToken: Token? = null

    fun tryAcquire(): Token? = synchronized(lock) {
        if (activeToken != null) return null
        Token().also { activeToken = it }
    }

    fun release(token: Token): Boolean = synchronized(lock) {
        if (activeToken !== token) return false
        activeToken = null
        true
    }

    companion object {
        val process = ProbeExecutionLease()
    }
}

internal sealed interface ProbeExecutionStartResult {
    data class Started(
        val token: ProbeExecutionLease.Token,
    ) : ProbeExecutionStartResult

    data object AlreadyActive : ProbeExecutionStartResult

    data object ProcessBusy : ProbeExecutionStartResult

    data object OwnerRejected : ProbeExecutionStartResult
}

/** Couples one Service instance's local ownership to the shared process execution slot. */
internal class ProbeExecutionLeaseHost<Request>(
    private val beginForeground: (Request) -> Unit,
    private val publishBusy: (Request) -> Unit,
    private val finishRejected: () -> Unit,
    private val executionLease: ProbeExecutionLease = ProbeExecutionLease.process,
) {
    private val lock = Any()
    private var activeToken: ProbeExecutionLease.Token? = null

    fun start(
        request: Request,
        startOwned: (ProbeExecutionLease.Token) -> Boolean,
    ): ProbeExecutionStartResult {
        val token = synchronized(lock) {
            if (activeToken != null) return ProbeExecutionStartResult.AlreadyActive
            beginForeground(request)
            executionLease.tryAcquire()?.also { activeToken = it }
        }
        if (token == null) {
            try {
                publishBusy(request)
            } finally {
                finishRejected()
            }
            return ProbeExecutionStartResult.ProcessBusy
        }
        val accepted = try {
            startOwned(token)
        } catch (failure: Throwable) {
            finish(token)
            finishRejected()
            throw failure
        }
        if (!accepted) {
            finish(token)
            finishRejected()
            return ProbeExecutionStartResult.OwnerRejected
        }
        return ProbeExecutionStartResult.Started(token)
    }

    fun finish(token: ProbeExecutionLease.Token): Boolean = synchronized(lock) {
        if (activeToken !== token) return false
        if (!executionLease.release(token)) return false
        activeToken = null
        true
    }
}
