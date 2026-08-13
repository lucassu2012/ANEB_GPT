package com.aneb.probe.privacy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class LocalPrivacyDeletionCoordinatorTest {
    @Test
    fun `unconfirmed request requires confirmation and touches no owned data`() = runBlocking {
        val operationGate = LocalPrivacyOperationGate()
        val deletionCalls = AtomicInteger()
        val coordinator = coordinator(
            operationGate = operationGate,
            consentDeletion = {
                deletionCalls.incrementAndGet()
                LocalPrivacyDomainDeletionResult.CLEARED
            },
        )

        assertEquals(
            LocalPrivacyDeletionResult.ConfirmationRequired,
            coordinator.delete(confirmed = false),
        )
        assertEquals(0, deletionCalls.get())
    }

    @Test
    fun `confirmed request is blocked atomically while either owned service run holds the lease`() = runBlocking {
        ProbeOwnedRun.entries.forEach { owner ->
            val operationGate = LocalPrivacyOperationGate()
            val runAdmission = operationGate.tryAcquireRun(owner)
            val runLease = (runAdmission as LocalPrivacyRunAdmission.Acquired).lease
            val deletionCalls = AtomicInteger()
            val coordinator = coordinator(
                operationGate = operationGate,
                consentDeletion = {
                    deletionCalls.incrementAndGet()
                    LocalPrivacyDomainDeletionResult.CLEARED
                },
            )

            try {
                assertEquals(
                    LocalPrivacyDeletionResult.BlockedByActiveRun,
                    coordinator.delete(confirmed = true),
                )
                assertEquals(0, deletionCalls.get())
            } finally {
                runLease.close()
            }
        }
    }

    @Test
    fun `concurrent confirmed requests execute the owned deletion only once`() = runBlocking {
        val operationGate = LocalPrivacyOperationGate()
        val deletionEntered = CompletableDeferred<Unit>()
        val releaseDeletion = CompletableDeferred<Unit>()
        val deletionCalls = AtomicInteger()
        val coordinator = coordinator(
            operationGate = operationGate,
            consentDeletion = {
                deletionCalls.incrementAndGet()
                deletionEntered.complete(Unit)
                releaseDeletion.await()
                LocalPrivacyDomainDeletionResult.CLEARED
            },
        )

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.delete(confirmed = true)
        }
        withTimeout(1_000) { deletionEntered.await() }

        assertEquals(
            LocalPrivacyDeletionResult.DeletionInProgress,
            coordinator.delete(confirmed = true),
        )
        assertEquals(1, deletionCalls.get())

        releaseDeletion.complete(Unit)
        assertEquals(completeResult(), first.await())
        assertEquals(1, deletionCalls.get())
    }

    @Test
    fun `confirmed deletion is blocked while an owned mutation holds its lease`() = runBlocking {
        val operationGate = LocalPrivacyOperationGate()
        val mutation = operationGate.tryAcquireOwnedMutation() as
            LocalPrivacyOwnedMutationAdmission.Acquired
        val deletionCalls = AtomicInteger()
        val coordinator = coordinator(
            operationGate = operationGate,
            consentDeletion = {
                deletionCalls.incrementAndGet()
                LocalPrivacyDomainDeletionResult.CLEARED
            },
        )

        try {
            assertEquals(
                LocalPrivacyDeletionResult.BlockedByOwnedMutation,
                coordinator.delete(confirmed = true),
            )
            assertEquals(0, deletionCalls.get())
        } finally {
            mutation.lease.close()
        }

        assertEquals(completeResult(), coordinator.delete(confirmed = true))
        assertEquals(1, deletionCalls.get())
    }

    private fun coordinator(
        operationGate: LocalPrivacyOperationGate,
        consentDeletion: suspend () -> LocalPrivacyDomainDeletionResult,
    ): LocalPrivacyDeletionCoordinator {
        val cleared = LocalPrivacyDomainDeletionPort {
            LocalPrivacyDomainDeletionResult.CLEARED
        }
        return LocalPrivacyDeletionCoordinator(
            operationGate = operationGate,
            consentDeletion = LocalPrivacyDomainDeletionPort { consentDeletion() },
            roomDeletion = cleared,
            settingsDeletion = cleared,
            externalNetlogDeletion = cleared,
            internalNetlogDeletion = cleared,
            gatewayVaultDeletion = cleared,
            primaryRuntimeDeletion = cleared,
            specialRuntimeDeletion = cleared,
        )
    }

    private fun completeResult(): LocalPrivacyDeletionResult =
        LocalPrivacyDeletionResult.Complete(
            LocalPrivacyExact8Receipt(
                consent = LocalPrivacyDomainDeletionResult.CLEARED,
                room = LocalPrivacyDomainDeletionResult.CLEARED,
                settings = LocalPrivacyDomainDeletionResult.CLEARED,
                externalNetlog = LocalPrivacyDomainDeletionResult.CLEARED,
                internalNetlog = LocalPrivacyDomainDeletionResult.CLEARED,
                gatewayVault = LocalPrivacyDomainDeletionResult.CLEARED,
                primaryRuntime = LocalPrivacyDomainDeletionResult.CLEARED,
                specialRuntime = LocalPrivacyDomainDeletionResult.CLEARED,
            ),
        )
}
