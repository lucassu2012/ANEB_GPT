package com.aneb.probe.engine

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ResultUploadConsentPrivacyResetTest {
    private val scope = ResultUploadConsentScope(
        recipient = "aneb-node:e01-production",
        purpose = ResultUploadPurpose.RESULT_SUBMISSION,
        policyVersion = "1",
    )

    @Test
    fun `privacy reset clearing phase blocks a reentrant grant without rebuilding consent`() {
        val persistence = CallbackClearPersistence()
        val store = ResultUploadConsentStore(
            persistence = persistence,
            processState = ResultUploadConsentProcessState(),
        )
        assertEquals(ResultUploadConsentMutationResult.COMMITTED, store.grant(scope))
        val writesBeforeReset = persistence.writeCount
        lateinit var lateGrant: ResultUploadConsentMutationResult
        persistence.onClear = { lateGrant = store.grant(scope) }

        val reset = store.resetForPrivacyDeletion()

        assertEquals(
            ResultUploadConsentMutationResult.STATE_UNAVAILABLE,
            lateGrant,
        )
        assertEquals(ResultUploadPrivacyResetResult.Cleared, reset)
        assertEquals(writesBeforeReset, persistence.writeCount)
        assertTrue(persistence.isPhysicallyEmpty())
    }

    @Test
    fun `grant cannot rebuild consent after a privacy reset completes during its read`() {
        val persistence = ResetDuringReadPersistence()
        val store = ResultUploadConsentStore(
            persistence = persistence,
            processState = ResultUploadConsentProcessState(),
        )
        lateinit var nestedReset: ResultUploadPrivacyResetResult
        persistence.onFirstRead = { nestedReset = store.resetForPrivacyDeletion() }

        val grant = store.grant(scope)

        assertEquals(ResultUploadPrivacyResetResult.Cleared, nestedReset)
        assertEquals(ResultUploadConsentMutationResult.STATE_UNAVAILABLE, grant)
        assertEquals(0, persistence.writeCount)
        assertEquals(PersistedResultUploadConsent(), persistence.record)
        assertTrue(persistence.isPhysicallyEmpty())
    }

    @Test
    fun `privacy reset cannot clear while a grant durable write owns the consent mutation`() {
        val persistence = ResetDuringWritePersistence()
        val store = ResultUploadConsentStore(
            persistence = persistence,
            processState = ResultUploadConsentProcessState(),
        )
        lateinit var nestedReset: ResultUploadPrivacyResetResult
        persistence.onFirstWrite = { nestedReset = store.resetForPrivacyDeletion() }

        val grant = store.grant(scope)

        assertEquals(ResultUploadConsentMutationResult.COMMITTED, grant)
        assertEquals(ResultUploadPrivacyResetResult.BlockedByActiveAttempt, nestedReset)
        assertEquals(0, persistence.clearCount)
        assertEquals(1, persistence.writeCount)
        assertEquals(ResultUploadConsent.GRANTED, store.currentConsent(scope))
        assertTrue(!persistence.isPhysicallyEmpty())

        persistence.onFirstWrite = {}
        assertEquals(ResultUploadPrivacyResetResult.Cleared, store.resetForPrivacyDeletion())
        assertTrue(persistence.isPhysicallyEmpty())
    }

    @Test
    fun `failed grant durable write retires its owner before an explicit privacy reset retry`() {
        val persistence = FailingWriteWithResetPersistence()
        val store = ResultUploadConsentStore(
            persistence = persistence,
            processState = ResultUploadConsentProcessState(),
        )
        lateinit var nestedReset: ResultUploadPrivacyResetResult
        persistence.onWrite = { nestedReset = store.resetForPrivacyDeletion() }

        val grant = store.grant(scope)

        assertEquals(ResultUploadConsentMutationResult.PERSISTENCE_FAILED, grant)
        assertEquals(ResultUploadPrivacyResetResult.BlockedByActiveAttempt, nestedReset)
        assertEquals(0, persistence.clearCount)
        assertEquals(1, persistence.writeCount)
        assertEquals(PersistedResultUploadConsent(), persistence.record)

        assertEquals(ResultUploadPrivacyResetResult.Cleared, store.resetForPrivacyDeletion())
        assertTrue(persistence.isPhysicallyEmpty())
        assertEquals(1, persistence.writeCount)
        assertEquals(1, persistence.clearCount)
    }

    @Test
    fun `throwing grant durable write retires its owner and exposes only a stable failure`() {
        val marker = IllegalStateException("sensitive_write_marker")
        val persistence = ThrowingWriteWithResetPersistence(marker)
        val store = ResultUploadConsentStore(
            persistence = persistence,
            processState = ResultUploadConsentProcessState(),
        )
        lateinit var nestedReset: ResultUploadPrivacyResetResult
        persistence.onWrite = { nestedReset = store.resetForPrivacyDeletion() }

        val grant = store.grant(scope)

        assertEquals(ResultUploadConsentMutationResult.PERSISTENCE_FAILED, grant)
        assertTrue(!grant.toString().contains(marker.message.orEmpty()))
        assertEquals(ResultUploadPrivacyResetResult.BlockedByActiveAttempt, nestedReset)
        assertEquals(0, persistence.clearCount)
        assertEquals(1, persistence.writeCount)
        assertEquals(PersistedResultUploadConsent(), persistence.record)

        assertEquals(ResultUploadPrivacyResetResult.Cleared, store.resetForPrivacyDeletion())
        assertTrue(persistence.isPhysicallyEmpty())
        assertEquals(1, persistence.writeCount)
        assertEquals(1, persistence.clearCount)
    }

    @Test
    fun `cancelled grant durable write preserves the exact cancellation and retires its owner`() {
        val marker = CancellationException("caller_owned_cancellation")
        val existingSuppressed = IllegalStateException("existing_suppressed")
        marker.addSuppressed(existingSuppressed)
        val persistence = CancellingWriteWithResetPersistence(marker)
        val store = ResultUploadConsentStore(
            persistence = persistence,
            processState = ResultUploadConsentProcessState(),
        )
        lateinit var nestedReset: ResultUploadPrivacyResetResult
        persistence.onWrite = { nestedReset = store.resetForPrivacyDeletion() }

        val caught = try {
            store.grant(scope)
            null
        } catch (cancelled: CancellationException) {
            cancelled
        }

        assertTrue(caught === marker)
        assertEquals(1, marker.suppressed.size)
        assertTrue(marker.suppressed.single() === existingSuppressed)
        assertEquals(ResultUploadPrivacyResetResult.BlockedByActiveAttempt, nestedReset)
        assertEquals(0, persistence.clearCount)
        assertEquals(1, persistence.writeCount)
        assertEquals(PersistedResultUploadConsent(), persistence.record)

        assertEquals(ResultUploadPrivacyResetResult.Cleared, store.resetForPrivacyDeletion())
        assertTrue(persistence.isPhysicallyEmpty())
        assertEquals(1, persistence.writeCount)
        assertEquals(1, persistence.clearCount)
    }

    @Test
    fun `cross thread privacy reset does not wait for a grant durable write owner`() = runBlocking {
        val persistence = BlockingWritePersistence()
        val store = ResultUploadConsentStore(
            persistence = persistence,
            processState = ResultUploadConsentProcessState(),
        )
        val grant = async(Dispatchers.Default) { store.grant(scope) }
        assertTrue(persistence.writeEntered.await(10, TimeUnit.SECONDS))
        val reset = async(Dispatchers.Default) { store.resetForPrivacyDeletion() }

        try {
            assertEquals(
                ResultUploadPrivacyResetResult.BlockedByActiveAttempt,
                withTimeout(5_000) { reset.await() },
            )
            assertEquals(0, persistence.clearCount)
            assertTrue(!grant.isCancelled)
        } finally {
            persistence.allowWrite.countDown()
        }

        assertEquals(ResultUploadConsentMutationResult.COMMITTED, grant.await())
        assertEquals(1, persistence.writeCount)
        assertEquals(ResultUploadConsent.GRANTED, store.currentConsent(scope))
        assertEquals(ResultUploadPrivacyResetResult.Cleared, store.resetForPrivacyDeletion())
        assertTrue(persistence.isPhysicallyEmpty())
    }

    @Test
    fun `privacy reset clearing phase rejects late revoke before token creation`() = runBlocking {
        val persistence = BlockingClearPersistence()
        val processState = ResultUploadConsentProcessState()
        val store = ResultUploadConsentStore(persistence, processState)
        assertEquals(ResultUploadConsentMutationResult.COMMITTED, store.grant(scope))
        val reset = async(Dispatchers.Default) { store.resetForPrivacyDeletion() }
        assertTrue(persistence.clearEntered.await(10, TimeUnit.SECONDS))

        try {
            assertEquals(
                ResultUploadRevokeStart.StateUnavailable,
                store.beginRevoke(scope),
            )
            assertTrue(processState.pendingRevokeOperations.isEmpty())
        } finally {
            persistence.allowClear.countDown()
        }

        assertEquals(ResultUploadPrivacyResetResult.Cleared, reset.await())
        assertEquals(
            ResultUploadPrivacyResetResult.AlreadyEmpty,
            store.resetForPrivacyDeletion(),
        )
    }

    @Test
    fun `failed privacy reset blocks every later consent mutation including a retired revoke token`() {
        val persistence = FailingClearPersistence()
        val processState = ResultUploadConsentProcessState()
        val store = ResultUploadConsentStore(persistence, processState)
        assertEquals(ResultUploadConsentMutationResult.COMMITTED, store.grant(scope))
        val retiredToken = (store.beginRevoke(scope) as ResultUploadRevokeStart.Started).token
        assertEquals(
            ResultUploadConsentMutationResult.COMMITTED,
            store.completeRevoke(retiredToken),
        )

        assertEquals(
            ResultUploadPrivacyResetResult.StateUnavailable,
            store.resetForPrivacyDeletion(),
        )
        val writesAfterReset = persistence.writeCount
        val reconstructed = ResultUploadConsentStore(persistence, processState)

        assertEquals(
            ResultUploadConsentMutationResult.STATE_UNAVAILABLE,
            reconstructed.grant(scope),
        )
        assertEquals(ResultUploadRevokeStart.StateUnavailable, reconstructed.beginRevoke(scope))
        assertEquals(
            ResultUploadConsentMutationResult.STATE_UNAVAILABLE,
            reconstructed.completeRevoke(retiredToken),
        )
        assertEquals(writesAfterReset, persistence.writeCount)
    }

    @Test
    fun `successful privacy reset invalidates a previously completed revoke token without IO`() {
        val persistence = CountingResetPersistence()
        val processState = ResultUploadConsentProcessState()
        val store = ResultUploadConsentStore(persistence, processState)
        assertEquals(ResultUploadConsentMutationResult.COMMITTED, store.grant(scope))
        val retiredToken = (store.beginRevoke(scope) as ResultUploadRevokeStart.Started).token
        assertEquals(
            ResultUploadConsentMutationResult.COMMITTED,
            store.completeRevoke(retiredToken),
        )
        assertEquals(
            ResultUploadPrivacyResetResult.Cleared,
            store.resetForPrivacyDeletion(),
        )
        val readsBeforeReplay = persistence.readCount
        val writesBeforeReplay = persistence.writeCount

        val replay = store.completeRevoke(retiredToken)

        assertEquals(ResultUploadConsentMutationResult.STATE_UNAVAILABLE, replay)
        assertEquals(readsBeforeReplay, persistence.readCount)
        assertEquals(writesBeforeReplay, persistence.writeCount)
        assertTrue(persistence.isPhysicallyEmpty())
        assertEquals(
            ResultUploadPrivacyResetResult.AlreadyEmpty,
            store.resetForPrivacyDeletion(),
        )
    }

    @Test
    fun `successful dormant privacy reset retires the issued capability generation before execution`() =
        runBlocking {
            val persistence = MemoryResetPersistence()
            val store = ResultUploadConsentStore(
                persistence = persistence,
                processState = ResultUploadConsentProcessState(),
            )
            val controller = ResultUploadAttemptController(store)
            assertEquals(ResultUploadConsentMutationResult.COMMITTED, controller.grant(scope))
            val staleAdmission = store.openAdmission(FIRST_RESULT_ID, scope)
            val stalePermit = checkNotNull(store.openAdmission(SECOND_RESULT_ID, scope).claim())
            var executeCount = 0

            val reset = controller.resetForPrivacyDeletion()
            val permitAttempt = stalePermit.attempt { executeCount += 1 }

            assertEquals(ResultUploadPrivacyResetResult.Cleared, reset)
            assertEquals(
                ResultUploadAdmissionDecision.AuthorizationChanged,
                staleAdmission.decide(adapterConfigured = true),
            )
            assertEquals(
                ResultUploadPermitAttemptResult.AuthorizationInvalidated,
                permitAttempt,
            )
            assertEquals(0, executeCount)
            assertEquals(PersistedResultUploadConsent(), persistence.record)
        }

    @Test
    fun `privacy reset physically clears the dedicated preferences including unknown future keys`() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val prefs = context.getSharedPreferences(
            "result_upload_consent_v1",
            Context.MODE_PRIVATE,
        )
        prefs.edit()
            .putString("state", ResultUploadConsent.GRANTED.name)
            .putString("future_private_key", "must-disappear")
            .commit()
        val controller = ResultUploadAttemptController(ResultUploadConsentStore(context))

        val reset = controller.resetForPrivacyDeletion()

        assertEquals(ResultUploadPrivacyResetResult.Cleared, reset)
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun `active direct permit blocks privacy reset without cancelling the owned execution`() =
        runBlocking {
            val persistence = MemoryResetPersistence()
            val store = ResultUploadConsentStore(
                persistence = persistence,
                processState = ResultUploadConsentProcessState(),
            )
            val controller = ResultUploadAttemptController(store)
            assertEquals(ResultUploadConsentMutationResult.COMMITTED, controller.grant(scope))
            val permit = checkNotNull(store.openAdmission(FIRST_RESULT_ID, scope).claim())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val ownedAttempt = async(start = CoroutineStart.UNDISPATCHED) {
                permit.attempt {
                    entered.complete(Unit)
                    release.await()
                    "done"
                }
            }
            entered.await()

            val reset = controller.resetForPrivacyDeletion()

            assertEquals(ResultUploadPrivacyResetResult.BlockedByActiveAttempt, reset)
            assertTrue(!ownedAttempt.isCancelled)
            assertTrue(persistence.record.claimedResultIds.contains(FIRST_RESULT_ID))
            release.complete(Unit)
            assertEquals(
                ResultUploadPermitAttemptResult.Executed("done"),
                ownedAttempt.await(),
            )
        }

    @Test
    fun `coordinator local commit owns the permit before privacy reset can clear consent`() =
        runBlocking {
            val persistence = MemoryResetPersistence()
            val processState = ResultUploadConsentProcessState()
            val store = ResultUploadConsentStore(persistence, processState)
            assertEquals(ResultUploadConsentMutationResult.COMMITTED, store.grant(scope))
            val localCommitEntered = CompletableDeferred<Unit>()
            val releaseLocalCommit = CompletableDeferred<Unit>()
            var localCommitCount = 0
            var requestCount = 0
            var transportCount = 0
            var submitCount = 0
            var finalStatusCount = 0
            val submission = async(start = CoroutineStart.UNDISPATCHED) {
                ResultSubmissionCoordinator(
                    admission = store.openAdmission(FIRST_RESULT_ID, scope),
                ).submit(
                    localCommit = {
                        localCommitCount += 1
                        localCommitEntered.complete(Unit)
                        releaseLocalCommit.await()
                    },
                    finalStatusCommit = {
                        finalStatusCount += 1
                    },
                    adapter = ResultSubmissionAdapter(
                        requestFactory = {
                            requestCount += 1
                            ResultSubmissionRequest(
                                resultId = FIRST_RESULT_ID,
                                scope = scope,
                                jsonBody = "{}",
                            )
                        },
                        transportFactory = {
                            transportCount += 1
                            ResultSubmissionTransport {
                                submitCount += 1
                                ResultUploadTransportOutcome.Accepted(statusCode = 201)
                            }
                        },
                    ),
                )
            }
            localCommitEntered.await()
            val writesBeforeReset = persistence.writeCount

            val reset = try {
                store.resetForPrivacyDeletion()
            } finally {
                releaseLocalCommit.complete(Unit)
            }
            val outcome = submission.await()

            assertEquals(ResultUploadPrivacyResetResult.BlockedByActiveAttempt, reset)
            assertEquals(0, persistence.clearCount)
            assertEquals(writesBeforeReset, persistence.writeCount)
            assertTrue(!submission.isCancelled)
            assertEquals(ResultSubmissionOutcome.Uploaded(httpCode = 201), outcome)
            assertEquals(1, localCommitCount)
            assertEquals(1, requestCount)
            assertEquals(1, transportCount)
            assertEquals(1, submitCount)
            assertEquals(1, finalStatusCount)
        }

    @Test
    fun `coordinator final status commit owns the permit before privacy reset can clear consent`() =
        runBlocking {
            val persistence = MemoryResetPersistence()
            val processState = ResultUploadConsentProcessState()
            val store = ResultUploadConsentStore(persistence, processState)
            assertEquals(ResultUploadConsentMutationResult.COMMITTED, store.grant(scope))
            val finalStatusEntered = CompletableDeferred<Unit>()
            val releaseFinalStatus = CompletableDeferred<Unit>()
            var localCommitCount = 0
            var requestCount = 0
            var transportCount = 0
            var submitCount = 0
            var finalStatusCount = 0
            val submission = async(start = CoroutineStart.UNDISPATCHED) {
                ResultSubmissionCoordinator(
                    admission = store.openAdmission(SECOND_RESULT_ID, scope),
                ).submit(
                    localCommit = {
                        localCommitCount += 1
                    },
                    finalStatusCommit = {
                        finalStatusCount += 1
                        finalStatusEntered.complete(Unit)
                        releaseFinalStatus.await()
                    },
                    adapter = ResultSubmissionAdapter(
                        requestFactory = {
                            requestCount += 1
                            ResultSubmissionRequest(
                                resultId = SECOND_RESULT_ID,
                                scope = scope,
                                jsonBody = "{}",
                            )
                        },
                        transportFactory = {
                            transportCount += 1
                            ResultSubmissionTransport {
                                submitCount += 1
                                ResultUploadTransportOutcome.Accepted(statusCode = 201)
                            }
                        },
                    ),
                )
            }
            finalStatusEntered.await()
            val writesBeforeReset = persistence.writeCount

            val reset = try {
                store.resetForPrivacyDeletion()
            } finally {
                releaseFinalStatus.complete(Unit)
            }
            val outcome = submission.await()

            assertEquals(ResultUploadPrivacyResetResult.BlockedByActiveAttempt, reset)
            assertEquals(0, persistence.clearCount)
            assertEquals(writesBeforeReset, persistence.writeCount)
            assertTrue(!submission.isCancelled)
            assertEquals(ResultSubmissionOutcome.Uploaded(httpCode = 201), outcome)
            assertEquals(1, localCommitCount)
            assertEquals(1, requestCount)
            assertEquals(1, transportCount)
            assertEquals(1, submitCount)
            assertEquals(1, finalStatusCount)
        }

    private class MemoryResetPersistence :
        ResultUploadConsentPersistence,
        ResultUploadConsentPrivacyResetPersistence {
        var record = PersistedResultUploadConsent()
        var writeCount = 0
            private set
        var clearCount = 0
            private set

        override fun read(): PersistedResultUploadConsent = record

        override fun write(value: PersistedResultUploadConsent): Boolean {
            writeCount += 1
            record = value
            return true
        }

        override fun isPhysicallyEmpty(): Boolean = record == PersistedResultUploadConsent()

        override fun clearAndCommit(): Boolean {
            clearCount += 1
            record = PersistedResultUploadConsent()
            return true
        }
    }

    private class BlockingClearPersistence :
        ResultUploadConsentPersistence,
        ResultUploadConsentPrivacyResetPersistence {
        val clearEntered = CountDownLatch(1)
        val allowClear = CountDownLatch(1)
        private var record = PersistedResultUploadConsent()

        override fun read(): PersistedResultUploadConsent = record

        override fun write(value: PersistedResultUploadConsent): Boolean {
            record = value
            return true
        }

        override fun isPhysicallyEmpty(): Boolean = record == PersistedResultUploadConsent()

        override fun clearAndCommit(): Boolean {
            record = PersistedResultUploadConsent()
            clearEntered.countDown()
            check(allowClear.await(10, TimeUnit.SECONDS)) { "clear_release_timeout" }
            return true
        }
    }

    private class CallbackClearPersistence :
        ResultUploadConsentPersistence,
        ResultUploadConsentPrivacyResetPersistence {
        var onClear: () -> Unit = {}
        var writeCount = 0
        private var record = PersistedResultUploadConsent()

        override fun read(): PersistedResultUploadConsent = record

        override fun write(value: PersistedResultUploadConsent): Boolean {
            writeCount += 1
            record = value
            return true
        }

        override fun isPhysicallyEmpty(): Boolean = record == PersistedResultUploadConsent()

        override fun clearAndCommit(): Boolean {
            record = PersistedResultUploadConsent()
            onClear()
            return true
        }
    }

    private class FailingClearPersistence :
        ResultUploadConsentPersistence,
        ResultUploadConsentPrivacyResetPersistence {
        private var record = PersistedResultUploadConsent()
        var writeCount = 0
            private set

        override fun read(): PersistedResultUploadConsent = record

        override fun write(value: PersistedResultUploadConsent): Boolean {
            writeCount += 1
            record = value
            return true
        }

        override fun isPhysicallyEmpty(): Boolean = record == PersistedResultUploadConsent()

        override fun clearAndCommit(): Boolean = false
    }

    private class ResetDuringReadPersistence :
        ResultUploadConsentPersistence,
        ResultUploadConsentPrivacyResetPersistence {
        var onFirstRead: () -> Unit = {}
        var record = PersistedResultUploadConsent()
            private set
        var writeCount = 0
            private set
        private var unknownFutureKeyPresent = true
        private var readCallbackPending = true

        override fun read(): PersistedResultUploadConsent {
            val captured = record
            if (readCallbackPending) {
                readCallbackPending = false
                onFirstRead()
            }
            return captured
        }

        override fun write(value: PersistedResultUploadConsent): Boolean {
            writeCount += 1
            record = value
            return true
        }

        override fun isPhysicallyEmpty(): Boolean =
            record == PersistedResultUploadConsent() && !unknownFutureKeyPresent

        override fun clearAndCommit(): Boolean {
            record = PersistedResultUploadConsent()
            unknownFutureKeyPresent = false
            return true
        }
    }

    private class CountingResetPersistence :
        ResultUploadConsentPersistence,
        ResultUploadConsentPrivacyResetPersistence {
        private var record = PersistedResultUploadConsent()
        var readCount = 0
            private set
        var writeCount = 0
            private set

        override fun read(): PersistedResultUploadConsent {
            readCount += 1
            return record
        }

        override fun write(value: PersistedResultUploadConsent): Boolean {
            writeCount += 1
            record = value
            return true
        }

        override fun isPhysicallyEmpty(): Boolean = record == PersistedResultUploadConsent()

        override fun clearAndCommit(): Boolean {
            record = PersistedResultUploadConsent()
            return true
        }
    }

    private class ResetDuringWritePersistence :
        ResultUploadConsentPersistence,
        ResultUploadConsentPrivacyResetPersistence {
        var onFirstWrite: () -> Unit = {}
        private var record = PersistedResultUploadConsent()
        var writeCount = 0
            private set
        var clearCount = 0
            private set
        private var unknownFutureKeyPresent = true
        private var writeCallbackPending = true

        override fun read(): PersistedResultUploadConsent = record

        override fun write(value: PersistedResultUploadConsent): Boolean {
            if (writeCallbackPending) {
                writeCallbackPending = false
                onFirstWrite()
            }
            writeCount += 1
            record = value
            return true
        }

        override fun isPhysicallyEmpty(): Boolean =
            record == PersistedResultUploadConsent() && !unknownFutureKeyPresent

        override fun clearAndCommit(): Boolean {
            clearCount += 1
            record = PersistedResultUploadConsent()
            unknownFutureKeyPresent = false
            return true
        }
    }

    private class FailingWriteWithResetPersistence :
        ResultUploadConsentPersistence,
        ResultUploadConsentPrivacyResetPersistence {
        var onWrite: () -> Unit = {}
        var record = PersistedResultUploadConsent()
            private set
        var writeCount = 0
            private set
        var clearCount = 0
            private set
        private var unknownFutureKeyPresent = true

        override fun read(): PersistedResultUploadConsent = record

        override fun write(value: PersistedResultUploadConsent): Boolean {
            onWrite()
            writeCount += 1
            return false
        }

        override fun isPhysicallyEmpty(): Boolean =
            record == PersistedResultUploadConsent() && !unknownFutureKeyPresent

        override fun clearAndCommit(): Boolean {
            clearCount += 1
            record = PersistedResultUploadConsent()
            unknownFutureKeyPresent = false
            return true
        }
    }

    private class ThrowingWriteWithResetPersistence(
        private val marker: RuntimeException,
    ) : ResultUploadConsentPersistence,
        ResultUploadConsentPrivacyResetPersistence {
        var onWrite: () -> Unit = {}
        var record = PersistedResultUploadConsent()
            private set
        var writeCount = 0
            private set
        var clearCount = 0
            private set
        private var unknownFutureKeyPresent = true

        override fun read(): PersistedResultUploadConsent = record

        override fun write(value: PersistedResultUploadConsent): Boolean {
            onWrite()
            writeCount += 1
            throw marker
        }

        override fun isPhysicallyEmpty(): Boolean =
            record == PersistedResultUploadConsent() && !unknownFutureKeyPresent

        override fun clearAndCommit(): Boolean {
            clearCount += 1
            record = PersistedResultUploadConsent()
            unknownFutureKeyPresent = false
            return true
        }
    }

    private class CancellingWriteWithResetPersistence(
        private val marker: CancellationException,
    ) : ResultUploadConsentPersistence,
        ResultUploadConsentPrivacyResetPersistence {
        var onWrite: () -> Unit = {}
        var record = PersistedResultUploadConsent()
            private set
        var writeCount = 0
            private set
        var clearCount = 0
            private set
        private var unknownFutureKeyPresent = true

        override fun read(): PersistedResultUploadConsent = record

        override fun write(value: PersistedResultUploadConsent): Boolean {
            onWrite()
            writeCount += 1
            throw marker
        }

        override fun isPhysicallyEmpty(): Boolean =
            record == PersistedResultUploadConsent() && !unknownFutureKeyPresent

        override fun clearAndCommit(): Boolean {
            clearCount += 1
            record = PersistedResultUploadConsent()
            unknownFutureKeyPresent = false
            return true
        }
    }

    private class BlockingWritePersistence :
        ResultUploadConsentPersistence,
        ResultUploadConsentPrivacyResetPersistence {
        val writeEntered = CountDownLatch(1)
        val allowWrite = CountDownLatch(1)
        private var record = PersistedResultUploadConsent()
        var writeCount = 0
            private set
        var clearCount = 0
            private set
        private var unknownFutureKeyPresent = true

        override fun read(): PersistedResultUploadConsent = record

        override fun write(value: PersistedResultUploadConsent): Boolean {
            writeEntered.countDown()
            check(allowWrite.await(20, TimeUnit.SECONDS)) { "write_release_timeout" }
            writeCount += 1
            record = value
            return true
        }

        override fun isPhysicallyEmpty(): Boolean =
            record == PersistedResultUploadConsent() && !unknownFutureKeyPresent

        override fun clearAndCommit(): Boolean {
            clearCount += 1
            record = PersistedResultUploadConsent()
            unknownFutureKeyPresent = false
            return true
        }
    }

    private companion object {
        const val FIRST_RESULT_ID = "11111111-1111-4111-8111-111111111111"
        const val SECOND_RESULT_ID = "22222222-2222-4222-8222-222222222222"
    }
}
