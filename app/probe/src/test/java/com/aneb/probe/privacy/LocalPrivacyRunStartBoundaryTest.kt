package com.aneb.probe.privacy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalPrivacyRunStartBoundaryTest {
    @Test
    fun `accepted but undelivered reservation expires before deletion and rejects late claim`() {
        val time = FakePendingDeliveryTime()
        val operationGate = LocalPrivacyOperationGate()
        val boundary = LocalPrivacyRunStartBoundary(
            operationGate = operationGate,
            nowNanos = time::nowNanos,
            pendingDeliveryTtlNanos = 60L,
            schedulePendingDeliveryExpiry = time::schedule,
        )
        val reserved = boundary.reserve(ProbeOwnedRun.PRIMARY)
            as LocalPrivacyRunReservationResult.Reserved
        var cleanupCalls = 0
        var cleanupSawLease = false

        assertTrue(
            boundary.armPendingDelivery(ProbeOwnedRun.PRIMARY, reserved.handle) {
                cleanupCalls += 1
                cleanupSawLease = operationGate.tryAcquireDeletion() is
                    LocalPrivacyDeletionAdmission.BlockedByActiveRun
            },
        )

        time.advanceBy(59L)
        assertEquals(0, cleanupCalls)
        assertTrue(
            operationGate.tryAcquireDeletion() is
                LocalPrivacyDeletionAdmission.BlockedByActiveRun,
        )

        time.advanceBy(1L)
        assertEquals(1, cleanupCalls)
        assertTrue(cleanupSawLease)
        val deletion = operationGate.tryAcquireDeletion()
        assertTrue(deletion is LocalPrivacyDeletionAdmission.Acquired)
        (deletion as LocalPrivacyDeletionAdmission.Acquired).lease.close()

        var factoryCalls = 0
        val late = boundary.claimAndStart(ProbeOwnedRun.PRIMARY, reserved.handle) {
            factoryCalls += 1
            Job()
        }
        assertEquals(LocalPrivacyRunStartResult.RejectedBeforeStart, late)
        assertEquals(0, factoryCalls)
    }

    @Test
    fun `unarmed reservation cannot be claimed before delivery ownership is registered`() = runBlocking {
        val operationGate = LocalPrivacyOperationGate()
        val boundary = LocalPrivacyRunStartBoundary(operationGate)
        val reserved = boundary.reserve(ProbeOwnedRun.SPECIAL_CONTINUITY)
            as LocalPrivacyRunReservationResult.Reserved
        var factoryCalls = 0

        val unarmed = boundary.claimAndStart(
            ProbeOwnedRun.SPECIAL_CONTINUITY,
            reserved.handle,
        ) {
            factoryCalls += 1
            Job().apply { complete() }
        }
        if (unarmed is LocalPrivacyRunStartResult.Started) unarmed.job.join()

        try {
            assertEquals(LocalPrivacyRunStartResult.RejectedBeforeStart, unarmed)
            assertEquals(0, factoryCalls)
            assertTrue(
                operationGate.tryAcquireDeletion() is
                    LocalPrivacyDeletionAdmission.BlockedByActiveRun,
            )
        } finally {
            boundary.abandon(reserved.handle)
        }

        val deletion = operationGate.tryAcquireDeletion()
        assertTrue(deletion is LocalPrivacyDeletionAdmission.Acquired)
        (deletion as LocalPrivacyDeletionAdmission.Acquired).lease.close()
    }

    @Test
    fun `a consumed exact-kind reservation cannot replay its job factory`() = runBlocking {
        val operationGate = LocalPrivacyOperationGate()
        val boundary = LocalPrivacyRunStartBoundary(operationGate)
        val reserved = boundary.reserve(ProbeOwnedRun.SPECIAL_PROTOCOL_AB)
            as LocalPrivacyRunReservationResult.Reserved
        assertTrue(
            boundary.armPendingDelivery(
                ProbeOwnedRun.SPECIAL_PROTOCOL_AB,
                reserved.handle,
                cleanup = {},
            ),
        )
        var factoryCalls = 0

        val first = boundary.claimAndStart(
            ProbeOwnedRun.SPECIAL_PROTOCOL_AB,
            reserved.handle,
        ) {
            factoryCalls += 1
            Job().apply { complete() }
        }
        assertTrue(first is LocalPrivacyRunStartResult.Started)
        (first as LocalPrivacyRunStartResult.Started).job.join()

        val replay = boundary.claimAndStart(
            ProbeOwnedRun.SPECIAL_PROTOCOL_AB,
            reserved.handle,
        ) {
            factoryCalls += 1
            Job()
        }

        assertEquals(LocalPrivacyRunStartResult.RejectedBeforeStart, replay)
        assertEquals(1, factoryCalls)
        val deletion = operationGate.tryAcquireDeletion()
        assertTrue(deletion is LocalPrivacyDeletionAdmission.Acquired)
        (deletion as LocalPrivacyDeletionAdmission.Acquired).lease.close()
    }

    @Test
    fun `wrong owner cannot consume a valid pending run reservation`() = runBlocking {
        val operationGate = LocalPrivacyOperationGate()
        val boundary = LocalPrivacyRunStartBoundary(operationGate)
        val reserved = boundary.reserve(ProbeOwnedRun.PRIMARY)
            as LocalPrivacyRunReservationResult.Reserved
        assertTrue(
            boundary.armPendingDelivery(
                ProbeOwnedRun.PRIMARY,
                reserved.handle,
                cleanup = {},
            ),
        )

        val wrongOwner = boundary.claimAndStart(
            ProbeOwnedRun.SPECIAL_CONTINUITY,
            reserved.handle,
        ) { Job() }

        assertEquals(LocalPrivacyRunStartResult.RejectedBeforeStart, wrongOwner)
        assertTrue(
            operationGate.tryAcquireDeletion() is
                LocalPrivacyDeletionAdmission.BlockedByActiveRun,
        )
        val rightOwner = boundary.claimAndStart(
            ProbeOwnedRun.PRIMARY,
            reserved.handle,
        ) { Job().apply { complete() } }
        assertTrue(rightOwner is LocalPrivacyRunStartResult.Started)
        (rightOwner as LocalPrivacyRunStartResult.Started).job.join()
        val deletion = operationGate.tryAcquireDeletion()
        assertTrue(deletion is LocalPrivacyDeletionAdmission.Acquired)
        (deletion as LocalPrivacyDeletionAdmission.Acquired).lease.close()
    }

    @Test
    fun `cancelled owned job keeps deletion blocked until cleanup finishes and then releases`() = runBlocking {
        val operationGate = LocalPrivacyOperationGate()
        val boundary = LocalPrivacyRunStartBoundary(operationGate)
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()

        val started = boundary.tryStart(ProbeOwnedRun.PRIMARY) {
            launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    cleanupEntered.complete(Unit)
                    withContext(NonCancellable) { releaseCleanup.await() }
                }
            }
        } as LocalPrivacyRunStartResult.Started

        started.job.cancel()
        withTimeout(1_000) { cleanupEntered.await() }
        assertTrue(
            operationGate.tryAcquireDeletion() is
                LocalPrivacyDeletionAdmission.BlockedByActiveRun,
        )

        releaseCleanup.complete(Unit)
        started.job.join()
        val deletion = operationGate.tryAcquireDeletion()
        assertTrue(deletion is LocalPrivacyDeletionAdmission.Acquired)
        (deletion as LocalPrivacyDeletionAdmission.Acquired).lease.close()
    }

    private class FakePendingDeliveryTime {
        private data class Task(
            val dueNanos: Long,
            val action: () -> Unit,
            var cancelled: Boolean = false,
        )

        private val tasks = mutableListOf<Task>()
        private var now = 0L

        fun nowNanos(): Long = now

        fun schedule(delayNanos: Long, action: () -> Unit): AutoCloseable {
            val task = Task(now + delayNanos, action)
            tasks += task
            return AutoCloseable { task.cancelled = true }
        }

        fun advanceBy(deltaNanos: Long) {
            require(deltaNanos >= 0L)
            now += deltaNanos
            while (true) {
                val task = tasks
                    .filter { !it.cancelled && it.dueNanos <= now }
                    .minByOrNull(Task::dueNanos)
                    ?: return
                task.cancelled = true
                task.action()
            }
        }
    }
}
