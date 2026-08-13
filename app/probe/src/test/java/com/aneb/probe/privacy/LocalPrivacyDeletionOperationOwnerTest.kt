package com.aneb.probe.privacy

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPrivacyDeletionOperationOwnerTest {
    @Test
    fun `settings reset claim is exact once for each completed deletion`() {
        runBlocking {
            val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val owner = LocalPrivacyDeletionOperationOwner(
                operationScope = ownerScope,
                deleteConfirmed = { completeDeletionResult() },
            )
            try {
                assertEquals(
                    LocalPrivacyDeletionStart.Started,
                    owner.requestConfirmedDeletion(),
                )
                withTimeout(5_000L) {
                    owner.state.filterIsInstance<LocalPrivacyDeletionOperationState.Finished>()
                        .first()
                }
                assertTrue(owner.claimSettingsReset())
                assertFalse(owner.claimSettingsReset())

                assertEquals(
                    LocalPrivacyDeletionStart.Started,
                    owner.requestConfirmedDeletion(),
                )
                withTimeout(5_000L) {
                    owner.state.filterIsInstance<LocalPrivacyDeletionOperationState.Finished>()
                        .first()
                }
                assertTrue(owner.claimSettingsReset())
                assertFalse(owner.claimSettingsReset())
            } finally {
                ownerScope.cancel()
            }
        }
    }

    @Test
    fun `request returns before deletion work blocks and work stays on the owner dispatcher`() {
        val ownerThread = AtomicReference<Thread>()
        val ownerExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "privacy-deletion-owner").also(ownerThread::set)
        }
        val callerExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "privacy-deletion-caller")
        }
        val ownerDispatcher = ownerExecutor.asCoroutineDispatcher()
        val ownerScope = CoroutineScope(SupervisorJob() + ownerDispatcher)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requestReturned = CountDownLatch(1)
        val result = AtomicReference<LocalPrivacyDeletionStart>()
        val executionThread = AtomicReference<Thread>()
        val calls = AtomicInteger(0)
        val owner = LocalPrivacyDeletionOperationOwner(
            operationScope = ownerScope,
            deleteConfirmed = {
                calls.incrementAndGet()
                executionThread.set(Thread.currentThread())
                entered.countDown()
                release.await()
                completeDeletionResult()
            },
        )

        try {
            callerExecutor.execute {
                result.set(owner.requestConfirmedDeletion())
                requestReturned.countDown()
            }

            assertTrue("deletion work did not enter", entered.await(5, TimeUnit.SECONDS))
            assertTrue(
                "request stayed blocked behind deletion work",
                requestReturned.await(1, TimeUnit.SECONDS),
            )
            assertEquals(LocalPrivacyDeletionStart.Started, result.get())
            assertSame(ownerThread.get(), executionThread.get())
            assertEquals(
                LocalPrivacyDeletionStart.AlreadyRunning,
                callerExecutor.submit<LocalPrivacyDeletionStart> {
                    owner.requestConfirmedDeletion()
                }.get(1, TimeUnit.SECONDS),
            )
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            ownerScope.cancel()
            ownerDispatcher.close()
            callerExecutor.shutdownNow()
        }
    }

    @Test
    fun `deletion outlives its requester and duplicate requests do not restart it`() {
        runBlocking {
            val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val requesterJob = Job()
            val requesterScope = CoroutineScope(requesterJob + Dispatchers.Default)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger(0)
            val expected = completeDeletionResult()
            val owner = LocalPrivacyDeletionOperationOwner(
                operationScope = ownerScope,
                deleteConfirmed = {
                    calls.incrementAndGet()
                    entered.complete(Unit)
                    release.await()
                    expected
                },
            )

            try {
                val firstRequest = CompletableDeferred<LocalPrivacyDeletionStart>()
                requesterScope.launch {
                    firstRequest.complete(owner.requestConfirmedDeletion())
                }
                assertEquals(LocalPrivacyDeletionStart.Started, firstRequest.await())
                withTimeout(5_000L) { entered.await() }

                requesterJob.cancel()
                assertEquals(LocalPrivacyDeletionOperationState.Running, owner.state.value)
                assertEquals(
                    LocalPrivacyDeletionStart.AlreadyRunning,
                    owner.requestConfirmedDeletion(),
                )
                assertEquals(1, calls.get())

                release.complete(Unit)
                val terminal = withTimeout(5_000L) {
                    owner.state.filterIsInstance<LocalPrivacyDeletionOperationState.Finished>()
                        .first()
                }
                assertEquals(expected, terminal.result)
                assertEquals(1, calls.get())
            } finally {
                release.complete(Unit)
                requesterScope.cancel()
                ownerScope.cancel()
            }
        }
    }

    private fun completeDeletionResult(): LocalPrivacyDeletionResult =
        LocalPrivacyDeletionResult.Complete(
            LocalPrivacyExact8Receipt(
                consent = LocalPrivacyDomainDeletionResult.ALREADY_ABSENT,
                room = LocalPrivacyDomainDeletionResult.ALREADY_ABSENT,
                settings = LocalPrivacyDomainDeletionResult.ALREADY_ABSENT,
                externalNetlog = LocalPrivacyDomainDeletionResult.ALREADY_ABSENT,
                internalNetlog = LocalPrivacyDomainDeletionResult.ALREADY_ABSENT,
                gatewayVault = LocalPrivacyDomainDeletionResult.ALREADY_ABSENT,
                primaryRuntime = LocalPrivacyDomainDeletionResult.ALREADY_ABSENT,
                specialRuntime = LocalPrivacyDomainDeletionResult.ALREADY_ABSENT,
            ),
        )
}
