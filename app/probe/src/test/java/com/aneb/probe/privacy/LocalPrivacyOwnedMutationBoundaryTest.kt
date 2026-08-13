package com.aneb.probe.privacy

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalPrivacyOwnedMutationBoundaryTest {
    @Test
    fun `suspending owned mutation blocks deletion until upload completes and releases its lease`() = runBlocking {
        val operationGate = LocalPrivacyOperationGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val upload = async(start = CoroutineStart.UNDISPATCHED) {
            LocalPrivacyOwnedMutationBoundary(operationGate).executeSuspending {
                entered.complete(Unit)
                release.await()
                "uploaded"
            }
        }

        try {
            entered.await()
            assertEquals(
                LocalPrivacyDeletionAdmission.BlockedByOwnedMutation,
                operationGate.tryAcquireDeletion(),
            )
        } finally {
            release.complete(Unit)
        }

        assertEquals(
            LocalPrivacyOwnedMutationResult.Completed("uploaded"),
            upload.await(),
        )
        val deletion = operationGate.tryAcquireDeletion()
        check(deletion is LocalPrivacyDeletionAdmission.Acquired)
        deletion.lease.close()
    }

    @Test
    fun `active privacy deletion blocks an owned mutation before its callback`() {
        val operationGate = LocalPrivacyOperationGate()
        val deletion = operationGate.tryAcquireDeletion() as LocalPrivacyDeletionAdmission.Acquired
        val mutationCalls = AtomicInteger()

        try {
            val result = LocalPrivacyOwnedMutationBoundary(operationGate).execute {
                mutationCalls.incrementAndGet()
                "mutated"
            }

            assertEquals(LocalPrivacyOwnedMutationResult.DeletionInProgress, result)
            assertEquals(0, mutationCalls.get())
        } finally {
            deletion.lease.close()
        }
    }
}
