package com.aneb.probe.privacy

import java.util.UUID
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job

internal enum class OwnedRunBlockReason {
    PRIVACY_DELETION_IN_PROGRESS,
    ANOTHER_OWNED_RUN_ACTIVE,
}

internal sealed interface LocalPrivacyRunStartResult {
    data class Started(val job: Job) : LocalPrivacyRunStartResult
    data object RunAlreadyActive : LocalPrivacyRunStartResult
    data object DeletionInProgress : LocalPrivacyRunStartResult
    data object RejectedBeforeStart : LocalPrivacyRunStartResult
}

internal sealed interface LocalPrivacyRunReservationResult {
    data class Reserved(val handle: String) : LocalPrivacyRunReservationResult
    data object RunAlreadyActive : LocalPrivacyRunReservationResult
    data object DeletionInProgress : LocalPrivacyRunReservationResult
}

internal class LocalPrivacyRunStartBoundary(
    private val operationGate: LocalPrivacyOperationGate,
    private val nowNanos: () -> Long = System::nanoTime,
    private val pendingDeliveryTtlNanos: Long = DEFAULT_PENDING_DELIVERY_TTL_NANOS,
    private val schedulePendingDeliveryExpiry: (
        delayNanos: Long,
        task: () -> Unit,
    ) -> AutoCloseable = ::scheduleDefaultPendingDeliveryExpiry,
) {
    private val reservationMonitor = Any()
    private val reservations = mutableMapOf<String, PendingRunReservation>()

    init {
        require(pendingDeliveryTtlNanos > 0L) { "pending_delivery_ttl_must_be_positive" }
    }

    fun reserve(owner: ProbeOwnedRun): LocalPrivacyRunReservationResult =
        when (val admission = operationGate.tryAcquireRun(owner)) {
            LocalPrivacyRunAdmission.RunAlreadyActive ->
                LocalPrivacyRunReservationResult.RunAlreadyActive

            LocalPrivacyRunAdmission.DeletionInProgress ->
                LocalPrivacyRunReservationResult.DeletionInProgress

            is LocalPrivacyRunAdmission.Acquired -> {
                val handle = UUID.randomUUID().toString()
                val reservation = PendingRunReservation(
                    owner = owner,
                    lease = admission.lease,
                    deadlineNanos = nowNanos() + pendingDeliveryTtlNanos,
                )
                synchronized(reservationMonitor) {
                    reservations[handle] = reservation
                }
                val scheduled = try {
                    schedulePendingDeliveryExpiry(pendingDeliveryTtlNanos) {
                        expirePendingDelivery(handle, reservation)
                    }
                } catch (error: Throwable) {
                    synchronized(reservationMonitor) {
                        if (reservations[handle] === reservation) reservations.remove(handle)
                    }
                    reservation.lease.close()
                    throw error
                }
                val retained = synchronized(reservationMonitor) {
                    if (reservations[handle] === reservation) {
                        reservation.scheduledExpiry = scheduled
                        true
                    } else {
                        false
                    }
                }
                if (!retained) closeScheduledExpiry(scheduled)
                LocalPrivacyRunReservationResult.Reserved(handle)
            }
        }

    fun armPendingDelivery(
        owner: ProbeOwnedRun,
        handle: String,
        prepare: () -> Unit = {},
        cleanup: () -> Unit,
    ): Boolean {
        var retired: PendingRunReservation? = null
        var prepareFailure: Throwable? = null
        val armed = synchronized(reservationMonitor) {
            val reservation = reservations[handle]
                ?.takeIf { it.owner == owner && !it.armed }
                ?: return@synchronized false
            if (isDue(reservation)) {
                reservations.remove(handle)
                retired = reservation
                false
            } else {
                try {
                    prepare()
                    reservation.cleanup = cleanup
                    reservation.armed = true
                    true
                } catch (error: Throwable) {
                    reservations.remove(handle)
                    reservation.cleanup = cleanup
                    retired = reservation
                    prepareFailure = error
                    false
                }
            }
        }
        retired?.let(::retirePendingDelivery)
        prepareFailure?.let { throw it }
        return armed
    }

    fun claimAndStart(
        owner: ProbeOwnedRun,
        handle: String,
        createJob: () -> Job?,
    ): LocalPrivacyRunStartResult {
        var expired: PendingRunReservation? = null
        val reservation = synchronized(reservationMonitor) {
            val pending = reservations[handle]?.takeIf { it.owner == owner && it.armed }
                ?: return@synchronized null
            reservations.remove(handle)
            if (isDue(pending)) {
                expired = pending
                null
            } else {
                pending
            }
        }
        if (reservation == null) {
            expired?.let(::retirePendingDelivery)
            return LocalPrivacyRunStartResult.RejectedBeforeStart
        }
        closeScheduledExpiry(reservation.scheduledExpiry)
        return startWithLease(reservation.lease, createJob)
    }

    fun abandon(handle: String): Boolean {
        val reservation = synchronized(reservationMonitor) { reservations.remove(handle) }
            ?: return false
        retirePendingDelivery(reservation)
        return true
    }

    fun tryStart(
        owner: ProbeOwnedRun,
        createJob: () -> Job?,
    ): LocalPrivacyRunStartResult = when (val admission = operationGate.tryAcquireRun(owner)) {
        LocalPrivacyRunAdmission.RunAlreadyActive ->
            LocalPrivacyRunStartResult.RunAlreadyActive

        LocalPrivacyRunAdmission.DeletionInProgress ->
            LocalPrivacyRunStartResult.DeletionInProgress

        is LocalPrivacyRunAdmission.Acquired -> startWithLease(admission.lease, createJob)
    }

    private fun startWithLease(
        lease: LocalPrivacyRunLease,
        createJob: () -> Job?,
    ): LocalPrivacyRunStartResult {
        val job = try {
            createJob()
        } catch (error: Throwable) {
            lease.close()
            throw error
        }
        return if (job == null) {
            lease.close()
            LocalPrivacyRunStartResult.RejectedBeforeStart
        } else {
            job.invokeOnCompletion { lease.close() }
            LocalPrivacyRunStartResult.Started(job)
        }
    }

    private fun expirePendingDelivery(
        handle: String,
        expected: PendingRunReservation,
    ) {
        val expired = synchronized(reservationMonitor) {
            reservations[handle]
                ?.takeIf { it === expected && isDue(it) }
                ?.also { reservations.remove(handle) }
        } ?: return
        retirePendingDelivery(expired)
    }

    private fun isDue(reservation: PendingRunReservation): Boolean =
        nowNanos() - reservation.deadlineNanos >= 0L

    private fun retirePendingDelivery(reservation: PendingRunReservation) {
        closeScheduledExpiry(reservation.scheduledExpiry)
        try {
            reservation.cleanup?.invoke()
        } catch (_: Exception) {
            // Cleanup is best-effort, but the run lease must always be retired.
        } finally {
            reservation.lease.close()
        }
    }

    private fun closeScheduledExpiry(expiry: AutoCloseable?) {
        try {
            expiry?.close()
        } catch (_: Exception) {
            // Cancellation is an optimization; entry identity is authoritative.
        }
    }

    private class PendingRunReservation(
        val owner: ProbeOwnedRun,
        val lease: LocalPrivacyRunLease,
        val deadlineNanos: Long,
        var armed: Boolean = false,
        var cleanup: (() -> Unit)? = null,
        var scheduledExpiry: AutoCloseable? = null,
    )

    companion object {
        private const val DEFAULT_PENDING_DELIVERY_TTL_NANOS = 60_000_000_000L

        private val pendingDeliveryExpiryExecutor =
            ScheduledThreadPoolExecutor(1) { runnable ->
                Thread(runnable, "privacy-run-delivery-expiry").apply { isDaemon = true }
            }.apply {
                removeOnCancelPolicy = true
            }

        private fun scheduleDefaultPendingDeliveryExpiry(
            delayNanos: Long,
            task: () -> Unit,
        ): AutoCloseable {
            val future = pendingDeliveryExpiryExecutor.schedule(
                task,
                delayNanos,
                TimeUnit.NANOSECONDS,
            )
            return AutoCloseable { future.cancel(false) }
        }
    }
}

internal sealed interface LocalPrivacyOwnedRunExecutionResult<out T> {
    data class Completed<T>(val value: T) : LocalPrivacyOwnedRunExecutionResult<T>
    data object RunAlreadyActive : LocalPrivacyOwnedRunExecutionResult<Nothing>
    data object DeletionInProgress : LocalPrivacyOwnedRunExecutionResult<Nothing>
}

internal class LocalPrivacyOwnedRunExecutionBoundary(
    private val operationGate: LocalPrivacyOperationGate,
) {
    suspend fun <T> execute(
        owner: ProbeOwnedRun,
        block: suspend () -> T,
    ): LocalPrivacyOwnedRunExecutionResult<T> = when (val admission = operationGate.tryAcquireRun(owner)) {
        LocalPrivacyRunAdmission.RunAlreadyActive ->
            LocalPrivacyOwnedRunExecutionResult.RunAlreadyActive

        LocalPrivacyRunAdmission.DeletionInProgress ->
            LocalPrivacyOwnedRunExecutionResult.DeletionInProgress

        is LocalPrivacyRunAdmission.Acquired -> {
            try {
                LocalPrivacyOwnedRunExecutionResult.Completed(block())
            } finally {
                admission.lease.close()
            }
        }
    }
}

internal sealed interface LocalPrivacyOwnedMutationResult<out T> {
    data class Completed<T>(val value: T) : LocalPrivacyOwnedMutationResult<T>
    data object DeletionInProgress : LocalPrivacyOwnedMutationResult<Nothing>
}

internal class LocalPrivacyOwnedMutationBoundary(
    private val operationGate: LocalPrivacyOperationGate,
) {
    fun <T> execute(block: () -> T): LocalPrivacyOwnedMutationResult<T> =
        when (val admission = operationGate.tryAcquireOwnedMutation()) {
            LocalPrivacyOwnedMutationAdmission.DeletionInProgress ->
                LocalPrivacyOwnedMutationResult.DeletionInProgress

            is LocalPrivacyOwnedMutationAdmission.Acquired -> {
                try {
                    LocalPrivacyOwnedMutationResult.Completed(block())
                } finally {
                    admission.lease.close()
                }
            }
        }

    suspend fun <T> executeSuspending(
        block: suspend () -> T,
    ): LocalPrivacyOwnedMutationResult<T> =
        when (val admission = operationGate.tryAcquireOwnedMutation()) {
            LocalPrivacyOwnedMutationAdmission.DeletionInProgress ->
                LocalPrivacyOwnedMutationResult.DeletionInProgress

            is LocalPrivacyOwnedMutationAdmission.Acquired -> {
                try {
                    LocalPrivacyOwnedMutationResult.Completed(block())
                } finally {
                    admission.lease.close()
                }
            }
        }
}

internal object LocalPrivacyProcessOperations {
    val operationGate = LocalPrivacyOperationGate()
    val runStartBoundary = LocalPrivacyRunStartBoundary(operationGate)
    val ownedRunExecutionBoundary = LocalPrivacyOwnedRunExecutionBoundary(operationGate)
    val ownedMutationBoundary = LocalPrivacyOwnedMutationBoundary(operationGate)
}
