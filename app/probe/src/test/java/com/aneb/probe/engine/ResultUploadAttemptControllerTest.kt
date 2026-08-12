package com.aneb.probe.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultUploadAttemptControllerTest {
    @Test
    fun `attempt registry owner domain cannot be injected independently of the consent store`() {
        val exposesRegistryParameter = ResultUploadAttemptController::class.java
            .declaredConstructors
            .flatMap { it.parameterTypes.asIterable() }
            .any { it == ResultUploadAttemptRegistry::class.java }

        assertFalse(exposesRegistryParameter)
    }

    @Test
    fun `revoke between durable claim and lazy registration prevents attempt start`() = runBlocking {
        val persistence = MemoryConsentPersistence()
        val controller = ResultUploadAttemptController(ResultUploadConsentStore(persistence))
        val scope = ResultUploadConsentScope(
            recipient = "aneb-node:e01-production",
            purpose = ResultUploadPurpose.RESULT_SUBMISSION,
            policyVersion = "1",
        )
        val prepareEntered = CountDownLatch(1)
        val allowRegistration = CountDownLatch(1)
        val allowUnrelatedCompletion = CountDownLatch(1)
        val localCommitCount = AtomicInteger()
        val transportStartCount = AtomicInteger()
        val finalStatusCommitCount = AtomicInteger()

        assertEquals(ResultUploadConsentMutationResult.COMMITTED, controller.grant(scope))
        val unrelated = async(Dispatchers.Default) {
            check(allowUnrelatedCompletion.await(5, TimeUnit.SECONDS))
            "unrelated-completed"
        }
        val attempt = async(Dispatchers.Default) {
            controller.runAttempt(
                resultId = "019cde91-cf2e-7c95-8e6a-3f2720e59377",
                scope = scope,
                prepare = {
                    localCommitCount.incrementAndGet()
                    prepareEntered.countDown()
                    check(allowRegistration.await(5, TimeUnit.SECONDS))
                },
                execute = {
                    transportStartCount.incrementAndGet()
                    "uploaded"
                },
                finalize = {
                    finalStatusCommitCount.incrementAndGet()
                    ResultUploadFinalStatusCommit.COMMITTED
                },
            )
        }

        assertTrue(prepareEntered.await(5, TimeUnit.SECONDS))
        assertEquals(
            ResultUploadRevokeResult(
                consentMutation = ResultUploadConsentMutationResult.COMMITTED,
                attemptDisposition = ResultUploadRevokeDisposition.REVOKED_BEFORE_START,
            ),
            controller.revoke(scope),
        )
        allowRegistration.countDown()

        assertEquals(ResultUploadAttemptResult.RevokedBeforeStart, attempt.await())
        assertEquals(1, localCommitCount.get())
        assertEquals(0, transportStartCount.get())
        assertEquals(0, finalStatusCommitCount.get())
        assertEquals(emptySet<String>(), persistence.record.claimedResultIds)
        assertTrue(currentCoroutineContext().isActive)
        assertTrue(unrelated.isActive)
        allowUnrelatedCompletion.countDown()
        assertEquals("unrelated-completed", unrelated.await())
    }

    @Test
    fun `regrant cannot revive a permit from the revoked consent epoch`() = runBlocking {
        val persistence = MemoryConsentPersistence()
        val controller = ResultUploadAttemptController(ResultUploadConsentStore(persistence))
        val scope = ResultUploadConsentScope(
            recipient = "aneb-node:e01-production",
            purpose = ResultUploadPurpose.RESULT_SUBMISSION,
            policyVersion = "1",
        )
        val prepareEntered = CountDownLatch(1)
        val allowRegistration = CountDownLatch(1)
        val transportStartCount = AtomicInteger()

        assertEquals(ResultUploadConsentMutationResult.COMMITTED, controller.grant(scope))
        val attempt = async(Dispatchers.Default) {
            controller.runAttempt(
                resultId = "019cde91-cf2e-7c95-8e6a-3f2720e59378",
                scope = scope,
                prepare = {
                    prepareEntered.countDown()
                    check(allowRegistration.await(5, TimeUnit.SECONDS))
                },
                execute = {
                    transportStartCount.incrementAndGet()
                    "uploaded"
                },
                finalize = { ResultUploadFinalStatusCommit.COMMITTED },
            )
        }

        assertTrue(prepareEntered.await(5, TimeUnit.SECONDS))
        assertEquals(
            ResultUploadRevokeResult(
                consentMutation = ResultUploadConsentMutationResult.COMMITTED,
                attemptDisposition = ResultUploadRevokeDisposition.REVOKED_BEFORE_START,
            ),
            controller.revoke(scope),
        )
        assertEquals(ResultUploadConsentMutationResult.COMMITTED, controller.grant(scope))
        allowRegistration.countDown()

        assertEquals(ResultUploadAttemptResult.RevokedBeforeStart, attempt.await())
        assertEquals(0, transportStartCount.get())
    }

    @Test
    fun `revoke after attempt start cancels only owned upload and reports delivery uncertainty`() = runBlocking {
        val persistence = MemoryConsentPersistence()
        val controller = ResultUploadAttemptController(ResultUploadConsentStore(persistence))
        val scope = ResultUploadConsentScope(
            recipient = "aneb-node:e01-production",
            purpose = ResultUploadPurpose.RESULT_SUBMISSION,
            policyVersion = "1",
        )
        val transportEntered = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<String>()
        val allowUnrelatedCompletion = CompletableDeferred<Unit>()
        val finalStatusCommitCount = AtomicInteger()

        assertEquals(ResultUploadConsentMutationResult.COMMITTED, controller.grant(scope))
        val unrelated = async(Dispatchers.Default) {
            allowUnrelatedCompletion.await()
            "unrelated-completed"
        }
        val attempt = async(Dispatchers.Default) {
            controller.runAttempt(
                resultId = "019cde91-cf2e-7c95-8e6a-3f2720e59379",
                scope = scope,
                prepare = {},
                execute = {
                    transportEntered.complete(Unit)
                    neverCompletes.await()
                },
                finalize = {
                    finalStatusCommitCount.incrementAndGet()
                    ResultUploadFinalStatusCommit.COMMITTED
                },
            )
        }

        withTimeout(5_000) { transportEntered.await() }
        assertEquals(
            ResultUploadRevokeResult(
                consentMutation = ResultUploadConsentMutationResult.COMMITTED,
                attemptDisposition = ResultUploadRevokeDisposition.CANCELLED_MAY_HAVE_STARTED,
            ),
            controller.revoke(scope),
        )

        assertEquals(
            ResultUploadAttemptResult.CancelledMayHaveStarted,
            withTimeout(5_000) { attempt.await() },
        )
        assertEquals(0, finalStatusCommitCount.get())
        assertTrue(currentCoroutineContext().isActive)
        assertTrue(unrelated.isActive)
        allowUnrelatedCompletion.complete(Unit)
        assertEquals("unrelated-completed", unrelated.await())
    }

    @Test
    fun `parent cancellation propagates unchanged and is not classified as consent revocation`() = runBlocking {
        val persistence = MemoryConsentPersistence()
        val controller = ResultUploadAttemptController(ResultUploadConsentStore(persistence))
        val scope = ResultUploadConsentScope(
            recipient = "aneb-node:e01-production",
            purpose = ResultUploadPurpose.RESULT_SUBMISSION,
            policyVersion = "1",
        )
        val transportEntered = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<String>()
        val finalStatusCommitCount = AtomicInteger()

        assertEquals(ResultUploadConsentMutationResult.COMMITTED, controller.grant(scope))
        val attempt = async(Dispatchers.Default) {
            controller.runAttempt(
                resultId = "019cde91-cf2e-7c95-8e6a-3f2720e5937a",
                scope = scope,
                prepare = {},
                execute = {
                    transportEntered.complete(Unit)
                    neverCompletes.await()
                },
                finalize = {
                    finalStatusCommitCount.incrementAndGet()
                    ResultUploadFinalStatusCommit.COMMITTED
                },
            )
        }

        withTimeout(5_000) { transportEntered.await() }
        attempt.cancel(CancellationException("parent_cancel_marker"))
        val cancellation = try {
            attempt.await()
            error("parent cancellation must not produce an upload result")
        } catch (cancelled: CancellationException) {
            cancelled
        }

        assertEquals("parent_cancel_marker", cancellation.message)
        assertEquals(0, finalStatusCommitCount.get())
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test
    fun `revoke during finalization blocks future attempts without rewriting frozen outcome`() = runBlocking {
        val persistence = MemoryConsentPersistence()
        val controller = ResultUploadAttemptController(ResultUploadConsentStore(persistence))
        val scope = ResultUploadConsentScope(
            recipient = "aneb-node:e01-production",
            purpose = ResultUploadPurpose.RESULT_SUBMISSION,
            policyVersion = "1",
        )
        val finalizationEntered = CompletableDeferred<Unit>()
        val allowFinalization = CompletableDeferred<Unit>()

        assertEquals(ResultUploadConsentMutationResult.COMMITTED, controller.grant(scope))
        val attempt = async(Dispatchers.Default) {
            controller.runAttempt(
                resultId = "019cde91-cf2e-7c95-8e6a-3f2720e5937b",
                scope = scope,
                prepare = {},
                execute = { "uploaded" },
                finalize = {
                    finalizationEntered.complete(Unit)
                    allowFinalization.await()
                    ResultUploadFinalStatusCommit.COMMITTED
                },
            )
        }

        withTimeout(5_000) { finalizationEntered.await() }
        assertEquals(
            ResultUploadRevokeResult(
                consentMutation = ResultUploadConsentMutationResult.COMMITTED,
                attemptDisposition = ResultUploadRevokeDisposition.NO_ACTIVE,
            ),
            controller.revoke(scope),
        )
        allowFinalization.complete(Unit)

        assertEquals(
            ResultUploadAttemptResult.Completed("uploaded"),
            withTimeout(5_000) { attempt.await() },
        )
        assertEquals(
            ResultUploadAttemptResult.NotAdmitted(ResultUploadAdmissionDecision.NotConsented),
            controller.runAttempt(
                resultId = "019cde91-cf2e-7c95-8e6a-3f2720e5937c",
                scope = scope,
                prepare = {},
                execute = { error("revoked consent must not execute a future attempt") },
                finalize = { error("revoked consent must not finalize a future attempt") },
            ),
        )
    }

    @Test
    fun `parent cancellation after transport commits final status and preserves runtime cause`() =
        runBlocking {
            val persistence = MemoryConsentPersistence()
            val controller = ResultUploadAttemptController(ResultUploadConsentStore(persistence))
            val scope = ResultUploadConsentScope(
                recipient = "aneb-node:e01-production",
                purpose = ResultUploadPurpose.RESULT_SUBMISSION,
                policyVersion = "1",
            )
            val finalizationEntered = CompletableDeferred<Unit>()
            val allowFinalization = CompletableDeferred<Unit>()
            val finalStatusCommitCount = AtomicInteger()
            val marker = CancellationException("parent_cancel_after_transport_marker")
            val ownerObservedCancellation = CompletableDeferred<CancellationException>()

            assertEquals(ResultUploadConsentMutationResult.COMMITTED, controller.grant(scope))
            val attempt = async(Dispatchers.Default) {
                try {
                    controller.runAttempt(
                        resultId = "019cde91-cf2e-7c95-8e6a-3f2720e59393",
                        scope = scope,
                        prepare = {},
                        execute = { "uploaded" },
                        finalize = {
                            finalizationEntered.complete(Unit)
                            allowFinalization.await()
                            finalStatusCommitCount.incrementAndGet()
                            ResultUploadFinalStatusCommit.COMMITTED
                        },
                    )
                } catch (cancelled: CancellationException) {
                    ownerObservedCancellation.complete(cancelled)
                    throw cancelled
                }
            }

            withTimeout(5_000) { finalizationEntered.await() }
            attempt.cancel(marker)
            allowFinalization.complete(Unit)
            val cancellation = try {
                attempt.await()
                error("parent cancellation must propagate after final status")
            } catch (cancelled: CancellationException) {
                cancelled
            }

            assertTrue(
                withTimeout(5_000) { ownerObservedCancellation.await() }
                    .hasCauseIdentity(marker),
            )
            assertTrue(cancellation.hasCauseIdentity(marker))
            assertEquals(1, finalStatusCommitCount.get())
        }

    @Test
    fun `concurrent parent cancellation leaves caller owned finalizer marker unchanged`() =
        runBlocking {
            val persistence = MemoryConsentPersistence()
            val controller = ResultUploadAttemptController(ResultUploadConsentStore(persistence))
            val scope = ResultUploadConsentScope(
                recipient = "aneb-node:e01-production",
                purpose = ResultUploadPurpose.RESULT_SUBMISSION,
                policyVersion = "1",
            )
            val finalizationEntered = CompletableDeferred<Unit>()
            val allowFinalizerCancellation = CompletableDeferred<Unit>()
            val parentMarker = CancellationException("concurrent_parent_cancel_marker")
            val finalizerMarker = CancellationException("concurrent_finalizer_cancel_marker")
            val existingSuppressed = IllegalStateException("existing_finalizer_suppressed_marker")
            finalizerMarker.addSuppressed(existingSuppressed)
            val finalizerCount = AtomicInteger(0)
            val ownerObservedCancellation = CompletableDeferred<CancellationException>()

            assertEquals(ResultUploadConsentMutationResult.COMMITTED, controller.grant(scope))
            val attempt = async(Dispatchers.Default) {
                try {
                    controller.runAttempt(
                        resultId = "019cde91-cf2e-7c95-8e6a-3f2720e59394",
                        scope = scope,
                        prepare = {},
                        execute = { "uploaded" },
                        finalize = {
                            finalizerCount.incrementAndGet()
                            finalizationEntered.complete(Unit)
                            allowFinalizerCancellation.await()
                            throw finalizerMarker
                        },
                    )
                } catch (cancelled: CancellationException) {
                    ownerObservedCancellation.complete(cancelled)
                    throw cancelled
                }
            }

            withTimeout(5_000) { finalizationEntered.await() }
            attempt.cancel(parentMarker)
            allowFinalizerCancellation.complete(Unit)
            val outerCancellation = try {
                attempt.await()
                error("concurrent cancellation must not publish a result")
            } catch (cancelled: CancellationException) {
                cancelled
            }

            val ownerCancellation = withTimeout(5_000) { ownerObservedCancellation.await() }
            assertTrue(ownerCancellation.hasCauseIdentity(parentMarker))
            assertFalse(ownerCancellation.hasCauseIdentity(finalizerMarker))
            assertEquals(1, finalizerCount.get())
            assertEquals(1, finalizerMarker.suppressed.size)
            assertTrue(finalizerMarker.suppressed.single() === existingSuppressed)
            assertTrue(outerCancellation.hasCauseIdentity(parentMarker))
        }

    @Test
    fun `independent parent cancellation remains authoritative after finalizer cancellation`() {
        val parentMarker = CancellationException("independent_parent_marker")
        val finalizerMarker = CancellationException("independent_finalizer_marker")
        val existingSuppressed = IllegalStateException("existing_suppressed_marker")
        finalizerMarker.addSuppressed(existingSuppressed)

        val selected = selectResultUploadCancellation(
            caught = parentMarker,
            finalizerCancellation = finalizerMarker,
        )

        assertTrue(selected === parentMarker)
        assertEquals(1, finalizerMarker.suppressed.size)
        assertTrue(finalizerMarker.suppressed.single() === existingSuppressed)
    }

    @Test
    fun `finalizer cancellation is preserved only when caught chain contains its identity`() {
        val finalizerMarker = CancellationException("owned_finalizer_marker")
        val runtimeWrapper = CancellationException("runtime_wrapper").apply {
            initCause(finalizerMarker)
        }

        val selected = selectResultUploadCancellation(
            caught = runtimeWrapper,
            finalizerCancellation = finalizerMarker,
        )

        assertTrue(selected === finalizerMarker)
    }

    @Test
    fun `failed local preparation consumes no durable claim and the result can be retried`() = runBlocking {
        val persistence = MemoryConsentPersistence()
        val controller = ResultUploadAttemptController(ResultUploadConsentStore(persistence))
        val scope = ResultUploadConsentScope(
            recipient = "aneb-node:e01-production",
            purpose = ResultUploadPurpose.RESULT_SUBMISSION,
            policyVersion = "1",
        )
        val transportStartCount = AtomicInteger()
        val finalStatusCommitCount = AtomicInteger()

        assertEquals(ResultUploadConsentMutationResult.COMMITTED, controller.grant(scope))
        val failure = try {
            controller.runAttempt(
                resultId = "019cde91-cf2e-7c95-8e6a-3f2720e5937d",
                scope = scope,
                prepare = { error("local_commit_failed") },
                execute = {
                    transportStartCount.incrementAndGet()
                    "uploaded"
                },
                finalize = {
                    finalStatusCommitCount.incrementAndGet()
                    ResultUploadFinalStatusCommit.COMMITTED
                },
            )
            error("local preparation failure must propagate")
        } catch (error: IllegalStateException) {
            error
        }

        assertEquals("local_commit_failed", failure.message)
        assertEquals(emptySet<String>(), persistence.record.claimedResultIds)
        assertEquals(0, transportStartCount.get())
        assertEquals(0, finalStatusCommitCount.get())

        assertEquals(
            ResultUploadAttemptResult.Completed("uploaded"),
            controller.runAttempt(
                resultId = "019cde91-cf2e-7c95-8e6a-3f2720e5937d",
                scope = scope,
                prepare = {},
                execute = {
                    transportStartCount.incrementAndGet()
                    "uploaded"
                },
                finalize = {
                    finalStatusCommitCount.incrementAndGet()
                    ResultUploadFinalStatusCommit.COMMITTED
                },
            ),
        )
        assertEquals(
            setOf("019cde91-cf2e-7c95-8e6a-3f2720e5937d"),
            persistence.record.claimedResultIds,
        )
        assertEquals(1, transportStartCount.get())
        assertEquals(1, finalStatusCommitCount.get())
    }

    @Test
    fun `duplicate grant for the same scope keeps the epoch and does not revoke a preparing attempt`() =
        runBlocking {
            val persistence = MemoryConsentPersistence()
            val controller = ResultUploadAttemptController(ResultUploadConsentStore(persistence))
            val scope = ResultUploadConsentScope(
                recipient = "aneb-node:e01-production",
                purpose = ResultUploadPurpose.RESULT_SUBMISSION,
                policyVersion = "1",
            )
            val prepareEntered = CompletableDeferred<Unit>()
            val allowPrepare = CompletableDeferred<Unit>()
            val transportStartCount = AtomicInteger()

            assertEquals(ResultUploadConsentMutationResult.COMMITTED, controller.grant(scope))
            val originalEpoch = persistence.record.consentEpoch
            val attempt = async(Dispatchers.Default) {
                controller.runAttempt(
                    resultId = "019cde91-cf2e-7c95-8e6a-3f2720e5937e",
                    scope = scope,
                    prepare = {
                        prepareEntered.complete(Unit)
                        allowPrepare.await()
                    },
                    execute = {
                        transportStartCount.incrementAndGet()
                        "uploaded"
                    },
                    finalize = { ResultUploadFinalStatusCommit.COMMITTED },
                )
            }

            withTimeout(5_000) { prepareEntered.await() }
            assertEquals(ResultUploadConsentMutationResult.COMMITTED, controller.grant(scope))
            assertEquals(originalEpoch, persistence.record.consentEpoch)
            allowPrepare.complete(Unit)

            assertEquals(
                ResultUploadAttemptResult.Completed("uploaded"),
                withTimeout(5_000) { attempt.await() },
            )
            assertEquals(1, transportStartCount.get())
        }

    @Test
    fun `default off prepares the local result once and consumes no upload claim`() = runBlocking {
        val persistence = MemoryConsentPersistence()
        val controller = ResultUploadAttemptController(ResultUploadConsentStore(persistence))
        val scope = ResultUploadConsentScope(
            recipient = "aneb-node:e01-production",
            purpose = ResultUploadPurpose.RESULT_SUBMISSION,
            policyVersion = "1",
        )
        val localCommitCount = AtomicInteger()

        assertEquals(
            ResultUploadAttemptResult.NotAdmitted(ResultUploadAdmissionDecision.NotConsented),
            controller.runAttempt(
                resultId = "019cde91-cf2e-7c95-8e6a-3f2720e5937f",
                scope = scope,
                prepare = { localCommitCount.incrementAndGet() },
                execute = { error("default-off attempt must not enter transport") },
                finalize = { error("default-off attempt must not finalize upload") },
            ),
        )
        assertEquals(1, localCommitCount.get())
        assertEquals(emptySet<String>(), persistence.record.claimedResultIds)
    }

    @Test
    fun `parent cancellation during local preparation consumes no claim and propagates unchanged`() =
        runBlocking {
            val persistence = MemoryConsentPersistence()
            val controller = ResultUploadAttemptController(ResultUploadConsentStore(persistence))
            val scope = ResultUploadConsentScope(
                recipient = "aneb-node:e01-production",
                purpose = ResultUploadPurpose.RESULT_SUBMISSION,
                policyVersion = "1",
            )
            val prepareEntered = CompletableDeferred<Unit>()
            val neverCompletes = CompletableDeferred<Unit>()
            val transportStartCount = AtomicInteger()

            assertEquals(ResultUploadConsentMutationResult.COMMITTED, controller.grant(scope))
            val attempt = async(Dispatchers.Default) {
                controller.runAttempt(
                    resultId = "019cde91-cf2e-7c95-8e6a-3f2720e59380",
                    scope = scope,
                    prepare = {
                        prepareEntered.complete(Unit)
                        neverCompletes.await()
                    },
                    execute = {
                        transportStartCount.incrementAndGet()
                        "uploaded"
                    },
                    finalize = { ResultUploadFinalStatusCommit.COMMITTED },
                )
            }

            withTimeout(5_000) { prepareEntered.await() }
            attempt.cancel(CancellationException("parent_prepare_cancel_marker"))
            val cancellation = try {
                attempt.await()
                error("parent cancellation must propagate")
            } catch (cancelled: CancellationException) {
                cancelled
            }

            assertEquals("parent_prepare_cancel_marker", cancellation.message)
            assertEquals(emptySet<String>(), persistence.record.claimedResultIds)
            assertEquals(0, transportStartCount.get())
        }

    @Test
    fun `parent cancellation observed after noncooperative preparation cannot consume a claim`() =
        runBlocking {
            val persistence = MemoryConsentPersistence()
            val controller = ResultUploadAttemptController(ResultUploadConsentStore(persistence))
            val scope = ResultUploadConsentScope(
                recipient = "aneb-node:e01-production",
                purpose = ResultUploadPurpose.RESULT_SUBMISSION,
                policyVersion = "1",
            )
            val prepareEntered = CountDownLatch(1)
            val allowPrepareReturn = CountDownLatch(1)
            val transportStartCount = AtomicInteger()

            assertEquals(ResultUploadConsentMutationResult.COMMITTED, controller.grant(scope))
            val attempt = async(Dispatchers.Default) {
                controller.runAttempt(
                    resultId = "019cde91-cf2e-7c95-8e6a-3f2720e593a0",
                    scope = scope,
                    prepare = {
                        prepareEntered.countDown()
                        check(allowPrepareReturn.await(5, TimeUnit.SECONDS))
                    },
                    execute = {
                        transportStartCount.incrementAndGet()
                        "uploaded"
                    },
                    finalize = { ResultUploadFinalStatusCommit.COMMITTED },
                )
            }

            assertTrue(prepareEntered.await(5, TimeUnit.SECONDS))
            attempt.cancel(CancellationException("parent_noncooperative_prepare_cancel_marker"))
            allowPrepareReturn.countDown()
            try {
                attempt.await()
                error("parent cancellation must propagate")
            } catch (_: CancellationException) {
                // The claim and transport assertions below are the contract under test.
            }

            assertEquals(emptySet<String>(), persistence.record.claimedResultIds)
            assertEquals(0, transportStartCount.get())
        }

    @Test
    fun `parent cancellation during admission read wins before the claim linearization gate`() =
        runBlocking {
            val persistence = MemoryConsentPersistence()
            val controller = ResultUploadAttemptController(ResultUploadConsentStore(persistence))
            val scope = ResultUploadConsentScope(
                recipient = "aneb-node:e01-production",
                purpose = ResultUploadPurpose.RESULT_SUBMISSION,
                policyVersion = "1",
            )
            val transportStartCount = AtomicInteger()

            assertEquals(ResultUploadConsentMutationResult.COMMITTED, controller.grant(scope))
            persistence.blockReadAt = persistence.readCount + 2
            val attempt = async(Dispatchers.Default) {
                controller.runAttempt(
                    resultId = "019cde91-cf2e-7c95-8e6a-3f2720e593a1",
                    scope = scope,
                    prepare = {},
                    execute = {
                        transportStartCount.incrementAndGet()
                        "uploaded"
                    },
                    finalize = { ResultUploadFinalStatusCommit.COMMITTED },
                )
            }

            assertTrue(persistence.readEntered.await(5, TimeUnit.SECONDS))
            attempt.cancel(CancellationException("parent_admission_read_cancel_marker"))
            persistence.allowRead.countDown()
            try {
                attempt.await()
                error("parent cancellation must propagate")
            } catch (_: CancellationException) {
                // The durable claim and transport assertions below bind the winner.
            }

            assertEquals(emptySet<String>(), persistence.record.claimedResultIds)
            assertEquals(0, transportStartCount.get())
        }

    @Test
    fun `permit is not current when its durable result claim membership is missing`() {
        val persistence = MemoryConsentPersistence()
        val store = ResultUploadConsentStore(persistence)
        val scope = ResultUploadConsentScope(
            recipient = "aneb-node:e01-production",
            purpose = ResultUploadPurpose.RESULT_SUBMISSION,
            policyVersion = "1",
        )
        val resultId = "019cde91-cf2e-7c95-8e6a-3f2720e59381"

        assertEquals(ResultUploadConsentMutationResult.COMMITTED, store.grant(scope))
        val permit = checkNotNull(store.openAdmission(resultId, scope).claim())
        persistence.record = persistence.record.copy(claimedResultIds = emptySet())

        assertFalse(store.isPermitCurrent(permit))
    }

    @Test
    fun `reconstructed controller revokes an attempt owned by the same app process`() = runBlocking {
        val persistence = MemoryConsentPersistence()
        val store = ResultUploadConsentStore(persistence)
        val firstController = ResultUploadAttemptController(store)
        val reconstructedController = ResultUploadAttemptController(store)
        val scope = ResultUploadConsentScope(
            recipient = "aneb-node:e01-production",
            purpose = ResultUploadPurpose.RESULT_SUBMISSION,
            policyVersion = "1",
        )
        val transportEntered = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<String>()

        assertEquals(ResultUploadConsentMutationResult.COMMITTED, firstController.grant(scope))
        val attempt = async(Dispatchers.Default) {
            firstController.runAttempt(
                resultId = "019cde91-cf2e-7c95-8e6a-3f2720e59382",
                scope = scope,
                prepare = {},
                execute = {
                    transportEntered.complete(Unit)
                    neverCompletes.await()
                },
                finalize = { error("revoked attempt must not finalize as uploaded") },
            )
        }

        withTimeout(5_000) { transportEntered.await() }
        assertEquals(
            ResultUploadRevokeResult(
                consentMutation = ResultUploadConsentMutationResult.COMMITTED,
                attemptDisposition = ResultUploadRevokeDisposition.CANCELLED_MAY_HAVE_STARTED,
            ),
            reconstructedController.revoke(scope),
        )
        assertEquals(
            ResultUploadAttemptResult.CancelledMayHaveStarted,
            withTimeout(5_000) { attempt.await() },
        )
    }

    @Test
    fun `overlapping reconstructed revoke preserves an already requested cancellation disposition`() =
        runBlocking {
            val persistence = BlockingRevokePersistence()
            val processState = ResultUploadConsentProcessState()
            val store = ResultUploadConsentStore(persistence, processState)
            val firstController = ResultUploadAttemptController(store)
            val reconstructedController = ResultUploadAttemptController(store)
            val scope = ResultUploadConsentScope(
                recipient = "aneb-node:e01-production",
                purpose = ResultUploadPurpose.RESULT_SUBMISSION,
                policyVersion = "1",
            )
            val transportEntered = CompletableDeferred<Unit>()
            val neverCompletes = CompletableDeferred<String>()
            val cleanupEntered = CountDownLatch(1)
            val allowCleanup = CountDownLatch(1)
            val cleanupCount = AtomicInteger()
            val resultId = "019cde91-cf2e-7c95-8e6a-3f2720e59402"

            assertEquals(ResultUploadConsentMutationResult.COMMITTED, firstController.grant(scope))
            val attempt = async(Dispatchers.Default) {
                firstController.runAttempt(
                    resultId = resultId,
                    scope = scope,
                    prepare = {},
                    execute = {
                        transportEntered.complete(Unit)
                        try {
                            neverCompletes.await()
                        } finally {
                            cleanupCount.incrementAndGet()
                            cleanupEntered.countDown()
                            check(allowCleanup.await(5, TimeUnit.SECONDS))
                        }
                    },
                    finalize = { error("cancelled attempt must not finalize") },
                )
            }
            withTimeout(5_000) { transportEntered.await() }
            val ownedAttempt = synchronized(processState.attemptRegistry.lock) {
                checkNotNull(processState.attemptRegistry.activeAttempts[resultId])
            }
            persistence.blockNextWrite = true

            val firstRevoke = async(Dispatchers.Default) { firstController.revoke(scope) }
            assertTrue(persistence.writeEntered.await(5, TimeUnit.SECONDS))
            assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS))
            val secondRevoke = async(Dispatchers.Default) { reconstructedController.revoke(scope) }
            withTimeout(5_000) {
                while (synchronized(processState.attemptRegistry.lock) {
                        processState.pendingRevokeOperations[scope]?.size ?: 0
                    } < 2
                ) {
                    kotlinx.coroutines.yield()
                }
            }
            persistence.allowWrite.countDown()

            assertEquals(
                ResultUploadRevokeDisposition.CANCELLED_MAY_HAVE_STARTED,
                withTimeout(5_000) { firstRevoke.await() }.attemptDisposition,
            )
            assertEquals(
                ResultUploadRevokeDisposition.CANCELLED_MAY_HAVE_STARTED,
                withTimeout(5_000) { secondRevoke.await() }.attemptDisposition,
            )
            assertEquals(1, cleanupCount.get())
            assertEquals(1, ownedAttempt.cancellationDispatchCount)
            allowCleanup.countDown()
            assertEquals(
                ResultUploadAttemptResult.CancelledMayHaveStarted,
                withTimeout(5_000) { attempt.await() },
            )
        }

    @Test
    fun `new revoke wave ignores a stale terminal slot from a completed revoke`() = runBlocking {
        val persistence = MemoryConsentPersistence()
        val processState = ResultUploadConsentProcessState()
        val store = ResultUploadConsentStore(persistence, processState)
        val firstController = ResultUploadAttemptController(store)
        val reconstructedController = ResultUploadAttemptController(store)
        val scope = ResultUploadConsentScope(
            recipient = "aneb-node:e01-production",
            purpose = ResultUploadPurpose.RESULT_SUBMISSION,
            policyVersion = "1",
        )
        val resultId = "019cde91-cf2e-7c95-8e6a-3f2720e59403"
        val transportEntered = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<String>()
        val cleanupEntered = CountDownLatch(1)
        val allowCleanup = CountDownLatch(1)
        val cleanupCount = AtomicInteger()

        assertEquals(ResultUploadConsentMutationResult.COMMITTED, firstController.grant(scope))
        val attempt = async(Dispatchers.Default) {
            firstController.runAttempt(
                resultId = resultId,
                scope = scope,
                prepare = {},
                execute = {
                    transportEntered.complete(Unit)
                    try {
                        neverCompletes.await()
                    } finally {
                        cleanupCount.incrementAndGet()
                        cleanupEntered.countDown()
                        check(allowCleanup.await(5, TimeUnit.SECONDS))
                    }
                },
                finalize = { error("cancelled attempt must not finalize") },
            )
        }
        withTimeout(5_000) { transportEntered.await() }
        val ownedAttempt = synchronized(processState.attemptRegistry.lock) {
            checkNotNull(processState.attemptRegistry.activeAttempts[resultId])
        }
        assertEquals(
            ResultUploadRevokeDisposition.CANCELLED_MAY_HAVE_STARTED,
            firstController.revoke(scope).attemptDisposition,
        )
        assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS))
        assertFalse(synchronized(processState.attemptRegistry.lock) {
            processState.pendingRevokeOperations.containsKey(scope)
        })

        assertEquals(
            ResultUploadRevokeDisposition.NO_ACTIVE,
            reconstructedController.revoke(scope).attemptDisposition,
        )
        assertEquals(1, cleanupCount.get())
        assertEquals(1, ownedAttempt.cancellationDispatchCount)
        allowCleanup.countDown()
        assertEquals(
            ResultUploadAttemptResult.CancelledMayHaveStarted,
            withTimeout(5_000) { attempt.await() },
        )
    }

    @Test
    fun `new revoke cannot inherit a retired wave before prior controller cleanup`() = runBlocking {
        val persistence = MemoryConsentPersistence()
        val processState = ResultUploadConsentProcessState()
        val store = ResultUploadConsentStore(persistence, processState)
        val retiredBeforeWaveCleanup = CountDownLatch(1)
        val allowWaveCleanup = CountDownLatch(1)
        val firstController = ResultUploadAttemptController(
            consentStore = store,
            afterRevokePersistenceBeforeWaveCleanup = {
                retiredBeforeWaveCleanup.countDown()
                check(allowWaveCleanup.await(5, TimeUnit.SECONDS))
            },
        )
        val reconstructedController = ResultUploadAttemptController(store)
        val scope = ResultUploadConsentScope(
            recipient = "aneb-node:e01-production",
            purpose = ResultUploadPurpose.RESULT_SUBMISSION,
            policyVersion = "1",
        )
        val resultId = "019cde91-cf2e-7c95-8e6a-3f2720e59404"
        val transportEntered = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<String>()
        val cleanupEntered = CountDownLatch(1)
        val allowCleanup = CountDownLatch(1)

        assertEquals(ResultUploadConsentMutationResult.COMMITTED, firstController.grant(scope))
        val attempt = async(Dispatchers.Default) {
            firstController.runAttempt(
                resultId = resultId,
                scope = scope,
                prepare = {},
                execute = {
                    transportEntered.complete(Unit)
                    try {
                        neverCompletes.await()
                    } finally {
                        cleanupEntered.countDown()
                        check(allowCleanup.await(5, TimeUnit.SECONDS))
                    }
                },
                finalize = { error("cancelled attempt must not finalize") },
            )
        }
        withTimeout(5_000) { transportEntered.await() }
        val ownedAttempt = synchronized(processState.attemptRegistry.lock) {
            checkNotNull(processState.attemptRegistry.activeAttempts[resultId])
        }
        val firstRevoke = async(Dispatchers.Default) { firstController.revoke(scope) }
        assertTrue(retiredBeforeWaveCleanup.await(5, TimeUnit.SECONDS))
        assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS))
        assertFalse(processState.pendingRevokeOperations.containsKey(scope))

        assertEquals(
            ResultUploadRevokeDisposition.NO_ACTIVE,
            reconstructedController.revoke(scope).attemptDisposition,
        )
        assertEquals(1, ownedAttempt.cancellationDispatchCount)
        allowWaveCleanup.countDown()
        assertEquals(
            ResultUploadRevokeDisposition.CANCELLED_MAY_HAVE_STARTED,
            withTimeout(5_000) { firstRevoke.await() }.attemptDisposition,
        )
        allowCleanup.countDown()
        assertEquals(
            ResultUploadAttemptResult.CancelledMayHaveStarted,
            withTimeout(5_000) { attempt.await() },
        )
    }

    @Test
    fun `revoke cancels a started owned attempt before durable consent persistence completes`() =
        runBlocking {
            val persistence = BlockingRevokePersistence()
            val controller = ResultUploadAttemptController(ResultUploadConsentStore(persistence))
            val scope = ResultUploadConsentScope(
                recipient = "aneb-node:e01-production",
                purpose = ResultUploadPurpose.RESULT_SUBMISSION,
                policyVersion = "1",
            )
            val transportEntered = CompletableDeferred<Unit>()
            val transportCancelled = CompletableDeferred<Unit>()
            val neverCompletes = CompletableDeferred<String>()

            assertEquals(ResultUploadConsentMutationResult.COMMITTED, controller.grant(scope))
            val attempt = async(Dispatchers.Default) {
                controller.runAttempt(
                    resultId = "019cde91-cf2e-7c95-8e6a-3f2720e59383",
                    scope = scope,
                    prepare = {},
                    execute = {
                        transportEntered.complete(Unit)
                        try {
                            neverCompletes.await()
                        } finally {
                            transportCancelled.complete(Unit)
                        }
                    },
                    finalize = { error("revoked attempt must not finalize as uploaded") },
                )
            }
            withTimeout(5_000) { transportEntered.await() }
            persistence.blockNextWrite = true

            val revoke = async(Dispatchers.Default) { controller.revoke(scope) }
            assertTrue(persistence.writeEntered.await(5, TimeUnit.SECONDS))
            val cancelledBeforePersistenceRelease = withTimeoutOrNull(750) {
                transportCancelled.await()
                true
            } ?: false
            persistence.allowWrite.countDown()

            assertEquals(
                ResultUploadRevokeResult(
                    consentMutation = ResultUploadConsentMutationResult.COMMITTED,
                    attemptDisposition = ResultUploadRevokeDisposition.CANCELLED_MAY_HAVE_STARTED,
                ),
                withTimeout(5_000) { revoke.await() },
            )
            assertEquals(
                ResultUploadAttemptResult.CancelledMayHaveStarted,
                withTimeout(5_000) { attempt.await() },
            )
            assertTrue(cancelledBeforePersistenceRelease)
        }

    @Test
    fun `concurrent same-result provisional owner alone prepares and claims the local result`() =
        runBlocking {
            val persistence = MemoryConsentPersistence()
            val controller = ResultUploadAttemptController(ResultUploadConsentStore(persistence))
            val scope = ResultUploadConsentScope(
                recipient = "aneb-node:e01-production",
                purpose = ResultUploadPurpose.RESULT_SUBMISSION,
                policyVersion = "1",
            )
            val prepareEntered = CompletableDeferred<Unit>()
            val allowPrepare = CompletableDeferred<Unit>()
            val localCommitCount = AtomicInteger()

            assertEquals(ResultUploadConsentMutationResult.COMMITTED, controller.grant(scope))
            val first = async(Dispatchers.Default) {
                controller.runAttempt(
                    resultId = "019cde91-cf2e-7c95-8e6a-3f2720e59384",
                    scope = scope,
                    prepare = {
                        localCommitCount.incrementAndGet()
                        prepareEntered.complete(Unit)
                        allowPrepare.await()
                    },
                    execute = { "uploaded" },
                    finalize = { ResultUploadFinalStatusCommit.COMMITTED },
                )
            }
            withTimeout(5_000) { prepareEntered.await() }

            assertEquals(
                ResultUploadAttemptResult.NotAdmitted(ResultUploadAdmissionDecision.AlreadyClaimed),
                controller.runAttempt(
                    resultId = "019cde91-cf2e-7c95-8e6a-3f2720e59384",
                    scope = scope,
                    prepare = { localCommitCount.incrementAndGet() },
                    execute = { error("duplicate provisional owner must not execute") },
                    finalize = { error("duplicate provisional owner must not finalize") },
                ),
            )
            assertEquals(1, localCommitCount.get())
            allowPrepare.complete(Unit)
            assertEquals(
                ResultUploadAttemptResult.Completed("uploaded"),
                withTimeout(5_000) { first.await() },
            )
            assertEquals(
                setOf("019cde91-cf2e-7c95-8e6a-3f2720e59384"),
                persistence.record.claimedResultIds,
            )
        }

    @Test
    fun `claim persistence failure occurs after local preparation and starts no transport`() =
        runBlocking {
            val persistence = MemoryConsentPersistence()
            val controller = ResultUploadAttemptController(ResultUploadConsentStore(persistence))
            val scope = ResultUploadConsentScope(
                recipient = "aneb-node:e01-production",
                purpose = ResultUploadPurpose.RESULT_SUBMISSION,
                policyVersion = "1",
            )
            val localCommitCount = AtomicInteger()
            val transportStartCount = AtomicInteger()

            assertEquals(ResultUploadConsentMutationResult.COMMITTED, controller.grant(scope))
            assertEquals(
                ResultUploadAttemptResult.NotAdmitted(ResultUploadAdmissionDecision.PersistenceFailed),
                controller.runAttempt(
                    resultId = "019cde91-cf2e-7c95-8e6a-3f2720e59385",
                    scope = scope,
                    prepare = {
                        localCommitCount.incrementAndGet()
                        persistence.writeSucceeds = false
                    },
                    execute = {
                        transportStartCount.incrementAndGet()
                        "uploaded"
                    },
                    finalize = { ResultUploadFinalStatusCommit.COMMITTED },
                ),
            )
            assertEquals(1, localCommitCount.get())
            assertEquals(0, transportStartCount.get())
            assertEquals(emptySet<String>(), persistence.record.claimedResultIds)
        }

    @Test
    fun `durably claimed result rejects a later owner before any local rewrite`() = runBlocking {
        val persistence = MemoryConsentPersistence()
        val controller = ResultUploadAttemptController(ResultUploadConsentStore(persistence))
        val scope = ResultUploadConsentScope(
            recipient = "aneb-node:e01-production",
            purpose = ResultUploadPurpose.RESULT_SUBMISSION,
            policyVersion = "1",
        )
        val localCommitCount = AtomicInteger()
        val transportStartCount = AtomicInteger()
        val finalStatusCommitCount = AtomicInteger()
        val resultId = "019cde91-cf2e-7c95-8e6a-3f2720e59386"

        assertEquals(ResultUploadConsentMutationResult.COMMITTED, controller.grant(scope))
        assertEquals(
            ResultUploadAttemptResult.Completed("uploaded"),
            controller.runAttempt(
                resultId = resultId,
                scope = scope,
                prepare = { localCommitCount.incrementAndGet() },
                execute = {
                    transportStartCount.incrementAndGet()
                    "uploaded"
                },
                finalize = {
                    finalStatusCommitCount.incrementAndGet()
                    ResultUploadFinalStatusCommit.COMMITTED
                },
            ),
        )
        assertEquals(
            ResultUploadAttemptResult.NotAdmitted(ResultUploadAdmissionDecision.AlreadyClaimed),
            controller.runAttempt(
                resultId = resultId,
                scope = scope,
                prepare = { localCommitCount.incrementAndGet() },
                execute = {
                    transportStartCount.incrementAndGet()
                    "duplicate"
                },
                finalize = {
                    finalStatusCommitCount.incrementAndGet()
                    ResultUploadFinalStatusCommit.COMMITTED
                },
            ),
        )

        assertEquals(1, localCommitCount.get())
        assertEquals(1, transportStartCount.get())
        assertEquals(1, finalStatusCommitCount.get())
    }

    @Test
    fun `durable claim prevents local rewrite after revoke and foreign scope replacement`() =
        runBlocking {
            val persistence = MemoryConsentPersistence()
            val store = ResultUploadConsentStore(persistence)
            val controller = ResultUploadAttemptController(store)
            val firstScope = ResultUploadConsentScope(
                recipient = "aneb-node:e01-production",
                purpose = ResultUploadPurpose.RESULT_SUBMISSION,
                policyVersion = "1",
            )
            val foreignScope = firstScope.copy(recipient = "aneb-node:foreign-production")
            val resultId = "019cde91-cf2e-7c95-8e6a-3f2720e59398"
            val prepareCount = AtomicInteger()
            val executeCount = AtomicInteger()
            val finalizeCount = AtomicInteger()

            assertEquals(ResultUploadConsentMutationResult.COMMITTED, controller.grant(firstScope))
            assertEquals(
                ResultUploadAttemptResult.Completed("uploaded"),
                controller.runAttempt(
                    resultId = resultId,
                    scope = firstScope,
                    prepare = { prepareCount.incrementAndGet() },
                    execute = {
                        executeCount.incrementAndGet()
                        "uploaded"
                    },
                    finalize = {
                        finalizeCount.incrementAndGet()
                        ResultUploadFinalStatusCommit.COMMITTED
                    },
                ),
            )

            controller.revoke(firstScope)
            val revokedRecord = persistence.record
            assertEquals(
                ResultUploadAttemptResult.NotAdmitted(ResultUploadAdmissionDecision.AlreadyClaimed),
                controller.runAttempt(
                    resultId = resultId,
                    scope = firstScope,
                    prepare = { prepareCount.incrementAndGet() },
                    execute = { error("revoked duplicate must not execute") },
                    finalize = { error("revoked duplicate must not finalize") },
                ),
            )
            assertEquals(revokedRecord, persistence.record)

            assertEquals(ResultUploadConsentMutationResult.COMMITTED, controller.grant(foreignScope))
            val foreignRecord = persistence.record
            assertEquals(
                ResultUploadAttemptResult.NotAdmitted(ResultUploadAdmissionDecision.AlreadyClaimed),
                controller.runAttempt(
                    resultId = resultId,
                    scope = firstScope,
                    prepare = { prepareCount.incrementAndGet() },
                    execute = { error("foreign-scope duplicate must not execute") },
                    finalize = { error("foreign-scope duplicate must not finalize") },
                ),
            )
            assertEquals(foreignRecord, persistence.record)
            assertEquals(1, prepareCount.get())
            assertEquals(1, executeCount.get())
            assertEquals(1, finalizeCount.get())
        }

    @Test
    fun `missing durable claim membership blocks transport as unavailable rather than user revocation`() =
        runBlocking {
            val persistence = MemoryConsentPersistence()
            val controller = ResultUploadAttemptController(ResultUploadConsentStore(persistence))
            val scope = ResultUploadConsentScope(
                recipient = "aneb-node:e01-production",
                purpose = ResultUploadPurpose.RESULT_SUBMISSION,
                policyVersion = "1",
            )
            val localCommitCount = AtomicInteger()
            val transportStartCount = AtomicInteger()
            val finalStatusCommitCount = AtomicInteger()

            assertEquals(ResultUploadConsentMutationResult.COMMITTED, controller.grant(scope))
            persistence.dropClaimMembershipOnWrite = true
            assertEquals(
                ResultUploadAttemptResult.UploadStateUnavailable,
                controller.runAttempt(
                    resultId = "019cde91-cf2e-7c95-8e6a-3f2720e59387",
                    scope = scope,
                    prepare = { localCommitCount.incrementAndGet() },
                    execute = {
                        transportStartCount.incrementAndGet()
                        "uploaded"
                    },
                    finalize = {
                        finalStatusCommitCount.incrementAndGet()
                        ResultUploadFinalStatusCommit.COMMITTED
                    },
                ),
            )
            assertEquals(1, localCommitCount.get())
            assertEquals(0, transportStartCount.get())
            assertEquals(0, finalStatusCommitCount.get())
            assertEquals(
                ResultUploadConsentMutationResult.STATE_UNAVAILABLE,
                controller.grant(scope),
            )
            assertEquals(
                ResultUploadAttemptResult.NotAdmitted(
                    ResultUploadAdmissionDecision.PersistenceFailed,
                ),
                controller.runAttempt(
                    resultId = "019cde91-cf2e-7c95-8e6a-3f2720e59389",
                    scope = scope,
                    prepare = { localCommitCount.incrementAndGet() },
                    execute = {
                        transportStartCount.incrementAndGet()
                        "uploaded-again"
                    },
                    finalize = {
                        finalStatusCommitCount.incrementAndGet()
                        ResultUploadFinalStatusCommit.COMMITTED
                    },
                ),
            )
            assertEquals(1, localCommitCount.get())
            assertEquals(0, transportStartCount.get())
            assertEquals(0, finalStatusCommitCount.get())
        }

    @Test
    fun `unavailable or corrupt consent state cannot rewrite the local result`() = runBlocking {
        val scope = ResultUploadConsentScope(
            recipient = "aneb-node:e01-production",
            purpose = ResultUploadPurpose.RESULT_SUBMISSION,
            policyVersion = "1",
        )
        val corruptPersistence = MemoryConsentPersistence().apply {
            record = PersistedResultUploadConsent(
                state = "CORRUPT_STATE",
                recipient = scope.recipient,
                purpose = scope.purpose.name,
                policyVersion = scope.policyVersion,
                consentEpoch = 1L,
            )
        }
        val exhaustedProcessState = ResultUploadConsentProcessState().apply {
            revisionExhausted = true
        }
        val unavailablePersistence = MemoryConsentPersistence()
        val prepareCount = AtomicInteger()

        listOf(
            ResultUploadAttemptController(ResultUploadConsentStore(corruptPersistence)),
            ResultUploadAttemptController(
                ResultUploadConsentStore(unavailablePersistence, exhaustedProcessState),
            ),
        ).forEachIndexed { index, controller ->
            assertEquals(
                ResultUploadAttemptResult.NotAdmitted(
                    ResultUploadAdmissionDecision.PersistenceFailed,
                ),
                controller.runAttempt(
                    resultId = "019cde91-cf2e-7c95-8e6a-3f2720e5940$index",
                    scope = scope,
                    prepare = { prepareCount.incrementAndGet() },
                    execute = { error("unavailable state must not execute") },
                    finalize = { error("unavailable state must not finalize") },
                ),
            )
        }
        assertEquals(0, prepareCount.get())
    }

    @Test
    fun `corrupt persisted consent state blocks transport as unavailable and latches the process off`() =
        runBlocking {
            val persistence = MemoryConsentPersistence()
            val controller = ResultUploadAttemptController(ResultUploadConsentStore(persistence))
            val scope = ResultUploadConsentScope(
                recipient = "aneb-node:e01-production",
                purpose = ResultUploadPurpose.RESULT_SUBMISSION,
                policyVersion = "1",
            )
            val transportStartCount = AtomicInteger()
            val finalStatusCommitCount = AtomicInteger()

            assertEquals(ResultUploadConsentMutationResult.COMMITTED, controller.grant(scope))
            persistence.corruptStateOnClaimWrite = true
            assertEquals(
                ResultUploadAttemptResult.UploadStateUnavailable,
                controller.runAttempt(
                    resultId = "019cde91-cf2e-7c95-8e6a-3f2720e59388",
                    scope = scope,
                    prepare = {},
                    execute = {
                        transportStartCount.incrementAndGet()
                        "uploaded"
                    },
                    finalize = {
                        finalStatusCommitCount.incrementAndGet()
                        ResultUploadFinalStatusCommit.COMMITTED
                    },
                ),
            )
            assertEquals(0, transportStartCount.get())
            assertEquals(0, finalStatusCommitCount.get())
            assertEquals(
                ResultUploadConsentMutationResult.STATE_UNAVAILABLE,
                controller.grant(scope),
            )
        }

    @Test
    fun `permit persistence read cancellation propagates unchanged before transport`() = runBlocking {
        val persistence = MemoryConsentPersistence()
        val controller = ResultUploadAttemptController(ResultUploadConsentStore(persistence))
        val scope = ResultUploadConsentScope(
            recipient = "aneb-node:e01-production",
            purpose = ResultUploadPurpose.RESULT_SUBMISSION,
            policyVersion = "1",
        )
        val marker = CancellationException("permit_read_cancel_marker")
        val transportStartCount = AtomicInteger()
        val finalStatusCommitCount = AtomicInteger()

        assertEquals(ResultUploadConsentMutationResult.COMMITTED, controller.grant(scope))
        persistence.readCancellationAt = persistence.readCount + 3
        persistence.readCancellation = marker
        val cancellation = try {
            controller.runAttempt(
                resultId = "019cde91-cf2e-7c95-8e6a-3f2720e5938a",
                scope = scope,
                prepare = {},
                execute = {
                    transportStartCount.incrementAndGet()
                    "uploaded"
                },
                finalize = {
                    finalStatusCommitCount.incrementAndGet()
                    ResultUploadFinalStatusCommit.COMMITTED
                },
            )
            error("permit persistence cancellation must propagate")
        } catch (cancelled: CancellationException) {
            cancelled
        }

        assertTrue(cancellation === marker)
        assertEquals(0, transportStartCount.get())
        assertEquals(0, finalStatusCommitCount.get())
    }

    @Test
    fun `policy switch after permit read cannot start transport with a stale current verdict`() =
        runBlocking {
            val persistence = MemoryConsentPersistence()
            val store = ResultUploadConsentStore(persistence)
            val firstScope = ResultUploadConsentScope(
                recipient = "aneb-node:e01-production",
                purpose = ResultUploadPurpose.RESULT_SUBMISSION,
                policyVersion = "1",
            )
            val secondScope = firstScope.copy(recipient = "aneb-node:e02-production")
            val transportStartCount = AtomicInteger()
            val finalStatusCommitCount = AtomicInteger()
            lateinit var controller: ResultUploadAttemptController
            controller = ResultUploadAttemptController(
                consentStore = store,
                beforePermitStartCas = {
                    assertEquals(
                        ResultUploadConsentMutationResult.COMMITTED,
                        controller.grant(secondScope),
                    )
                },
            )

            assertEquals(ResultUploadConsentMutationResult.COMMITTED, controller.grant(firstScope))
            assertEquals(
                ResultUploadAttemptResult.AuthorizationChangedBeforeStart,
                controller.runAttempt(
                    resultId = "019cde91-cf2e-7c95-8e6a-3f2720e5938b",
                    scope = firstScope,
                    prepare = {},
                    execute = {
                        transportStartCount.incrementAndGet()
                        "uploaded"
                    },
                    finalize = {
                        finalStatusCommitCount.incrementAndGet()
                        ResultUploadFinalStatusCommit.COMMITTED
                    },
                ),
            )
            assertEquals(0, transportStartCount.get())
            assertEquals(0, finalStatusCommitCount.get())
        }

    @Test
    fun `permanent unavailable latch at start CAS is not misclassified as authorization change`() =
        runBlocking {
            val persistence = MemoryConsentPersistence()
            val store = ResultUploadConsentStore(persistence)
            val scope = ResultUploadConsentScope(
                recipient = "aneb-node:e01-production",
                purpose = ResultUploadPurpose.RESULT_SUBMISSION,
                policyVersion = "1",
            )
            val transportStartCount = AtomicInteger()
            val finalStatusCommitCount = AtomicInteger()
            val controller = ResultUploadAttemptController(
                consentStore = store,
                beforePermitStartCas = {
                    persistence.record = persistence.record.copy(state = "CORRUPT_STATE")
                    assertEquals(
                        ResultUploadConsent.PERSISTENCE_FAILURE_BLOCKED,
                        store.currentConsent(scope),
                    )
                },
            )

            assertEquals(ResultUploadConsentMutationResult.COMMITTED, controller.grant(scope))
            assertEquals(
                ResultUploadAttemptResult.UploadStateUnavailable,
                controller.runAttempt(
                    resultId = "019cde91-cf2e-7c95-8e6a-3f2720e59395",
                    scope = scope,
                    prepare = {},
                    execute = {
                        transportStartCount.incrementAndGet()
                        "uploaded"
                    },
                    finalize = {
                        finalStatusCommitCount.incrementAndGet()
                        ResultUploadFinalStatusCommit.COMMITTED
                    },
                ),
            )
            assertEquals(0, transportStartCount.get())
            assertEquals(0, finalStatusCommitCount.get())
        }

    @Test
    fun `cross scope grant during local preparation is authorization changed not no consent`() =
        runBlocking {
            val persistence = MemoryConsentPersistence()
            val controller = ResultUploadAttemptController(ResultUploadConsentStore(persistence))
            val firstScope = ResultUploadConsentScope(
                recipient = "aneb-node:e01-production",
                purpose = ResultUploadPurpose.RESULT_SUBMISSION,
                policyVersion = "1",
            )
            val secondScope = firstScope.copy(recipient = "aneb-node:e02-production")
            val prepareEntered = CompletableDeferred<Unit>()
            val releasePrepare = CompletableDeferred<Unit>()
            val transportStartCount = AtomicInteger()
            val finalStatusCommitCount = AtomicInteger()

            assertEquals(ResultUploadConsentMutationResult.COMMITTED, controller.grant(firstScope))
            val attempt = async(Dispatchers.Default) {
                controller.runAttempt(
                    resultId = "019cde91-cf2e-7c95-8e6a-3f2720e59392",
                    scope = firstScope,
                    prepare = {
                        prepareEntered.complete(Unit)
                        releasePrepare.await()
                    },
                    execute = {
                        transportStartCount.incrementAndGet()
                        "uploaded"
                    },
                    finalize = {
                        finalStatusCommitCount.incrementAndGet()
                        ResultUploadFinalStatusCommit.COMMITTED
                    },
                )
            }
            withTimeout(5_000) { prepareEntered.await() }
            assertEquals(ResultUploadConsentMutationResult.COMMITTED, controller.grant(secondScope))
            releasePrepare.complete(Unit)

            assertEquals(
                ResultUploadAttemptResult.AuthorizationChangedBeforeStart,
                withTimeout(5_000) { attempt.await() },
            )
            assertEquals(0, transportStartCount.get())
            assertEquals(0, finalStatusCommitCount.get())
        }

    @Test
    fun `blocked admission persistence cannot delay cancellation of another started attempt`() =
        runBlocking {
            val persistence = MemoryConsentPersistence()
            val controller = ResultUploadAttemptController(ResultUploadConsentStore(persistence))
            val scope = ResultUploadConsentScope(
                recipient = "aneb-node:e01-production",
                purpose = ResultUploadPurpose.RESULT_SUBMISSION,
                policyVersion = "1",
            )
            val firstTransportEntered = CompletableDeferred<Unit>()
            val firstNeverCompletes = CompletableDeferred<String>()

            assertEquals(ResultUploadConsentMutationResult.COMMITTED, controller.grant(scope))
            val firstAttempt = async(Dispatchers.Default) {
                controller.runAttempt(
                    resultId = "019cde91-cf2e-7c95-8e6a-3f2720e5938c",
                    scope = scope,
                    prepare = {},
                    execute = {
                        firstTransportEntered.complete(Unit)
                        firstNeverCompletes.await()
                    },
                    finalize = { ResultUploadFinalStatusCommit.COMMITTED },
                )
            }
            withTimeout(5_000) { firstTransportEntered.await() }

            persistence.blockReadAt = persistence.readCount + 1
            val secondAttempt = async(Dispatchers.Default) {
                controller.runAttempt(
                    resultId = "019cde91-cf2e-7c95-8e6a-3f2720e5938d",
                    scope = scope,
                    prepare = {},
                    execute = { error("revoking policy must block the second transport") },
                    finalize = { ResultUploadFinalStatusCommit.COMMITTED },
                )
            }
            assertTrue(persistence.readEntered.await(5, TimeUnit.SECONDS))

            val revoke = async(Dispatchers.Default) { controller.revoke(scope) }
            assertEquals(
                ResultUploadAttemptResult.CancelledMayHaveStarted,
                withTimeout(5_000) { firstAttempt.await() },
            )
            assertFalse(revoke.isCompleted)
            persistence.allowRead.countDown()

            assertEquals(
                ResultUploadRevokeResult(
                    consentMutation = ResultUploadConsentMutationResult.COMMITTED,
                    attemptDisposition = ResultUploadRevokeDisposition.CANCELLED_MAY_HAVE_STARTED,
                ),
                withTimeout(5_000) { revoke.await() },
            )
            assertEquals(
                ResultUploadAttemptResult.RevokedBeforeStart,
                withTimeout(5_000) { secondAttempt.await() },
            )
        }

    @Test
    fun `claim persistence cancellation propagates unchanged and leaves the scope unavailable`() =
        runBlocking {
            val persistence = MemoryConsentPersistence()
            val controller = ResultUploadAttemptController(ResultUploadConsentStore(persistence))
            val scope = ResultUploadConsentScope(
                recipient = "aneb-node:e01-production",
                purpose = ResultUploadPurpose.RESULT_SUBMISSION,
                policyVersion = "1",
            )
            val marker = CancellationException("claim_write_cancel_marker")
            val localCommitCount = AtomicInteger()
            val transportStartCount = AtomicInteger()
            val finalStatusCommitCount = AtomicInteger()

            assertEquals(ResultUploadConsentMutationResult.COMMITTED, controller.grant(scope))
            persistence.writeCancellationAt = persistence.writeCount + 1
            persistence.writeCancellation = marker
            val cancellation = try {
                controller.runAttempt(
                    resultId = "019cde91-cf2e-7c95-8e6a-3f2720e5938e",
                    scope = scope,
                    prepare = { localCommitCount.incrementAndGet() },
                    execute = {
                        transportStartCount.incrementAndGet()
                        "uploaded"
                    },
                    finalize = {
                        finalStatusCommitCount.incrementAndGet()
                        ResultUploadFinalStatusCommit.COMMITTED
                    },
                )
                error("claim persistence cancellation must propagate")
            } catch (cancelled: CancellationException) {
                cancelled
            }

            assertTrue(cancellation === marker)
            assertEquals(1, localCommitCount.get())
            assertEquals(0, transportStartCount.get())
            assertEquals(0, finalStatusCommitCount.get())
            assertEquals(
                ResultUploadConsentMutationResult.STATE_UNAVAILABLE,
                controller.grant(scope),
            )
        }

    @Test
    fun `uncommitted final status cannot publish a completed attempt`() = runBlocking {
        val persistence = MemoryConsentPersistence()
        val controller = ResultUploadAttemptController(ResultUploadConsentStore(persistence))
        val scope = ResultUploadConsentScope(
            recipient = "aneb-node:e01-production",
            purpose = ResultUploadPurpose.RESULT_SUBMISSION,
            policyVersion = "1",
        )

        assertEquals(ResultUploadConsentMutationResult.COMMITTED, controller.grant(scope))
        assertEquals(
            ResultUploadAttemptResult.FinalStatusPersistenceFailed("uploaded"),
            controller.runAttempt(
                resultId = "019cde91-cf2e-7c95-8e6a-3f2720e5938f",
                scope = scope,
                prepare = {},
                execute = { "uploaded" },
                finalize = { ResultUploadFinalStatusCommit.NOT_COMMITTED },
            ),
        )
    }

    @Test
    fun `final status exception cannot publish a completed attempt`() = runBlocking {
        val persistence = MemoryConsentPersistence()
        val controller = ResultUploadAttemptController(ResultUploadConsentStore(persistence))
        val scope = ResultUploadConsentScope(
            recipient = "aneb-node:e01-production",
            purpose = ResultUploadPurpose.RESULT_SUBMISSION,
            policyVersion = "1",
        )

        assertEquals(ResultUploadConsentMutationResult.COMMITTED, controller.grant(scope))
        assertEquals(
            ResultUploadAttemptResult.FinalStatusPersistenceFailed("uploaded"),
            controller.runAttempt(
                resultId = "019cde91-cf2e-7c95-8e6a-3f2720e59390",
                scope = scope,
                prepare = {},
                execute = { "uploaded" },
                finalize = { error("final_status_private_marker") },
            ),
        )
    }

    @Test
    fun `direct owner preserves finalizer cancellation object and state`() = runBlocking {
        val persistence = MemoryConsentPersistence()
        val controller = ResultUploadAttemptController(ResultUploadConsentStore(persistence))
        val scope = ResultUploadConsentScope(
            recipient = "aneb-node:e01-production",
            purpose = ResultUploadPurpose.RESULT_SUBMISSION,
            policyVersion = "1",
        )
        val marker = CancellationException("final_status_cancel_marker")
        val existingSuppressed = IllegalStateException("final_status_existing_suppressed")
        marker.addSuppressed(existingSuppressed)

        assertEquals(ResultUploadConsentMutationResult.COMMITTED, controller.grant(scope))
        val cancellation = try {
            controller.runAttempt(
                resultId = "019cde91-cf2e-7c95-8e6a-3f2720e59391",
                scope = scope,
                prepare = {},
                execute = { "uploaded" },
                finalize = { throw marker },
            )
            error("final status cancellation must propagate")
        } catch (cancelled: CancellationException) {
            cancelled
        }

        assertTrue(cancellation === marker)
        assertEquals(1, marker.suppressed.size)
        assertTrue(marker.suppressed.single() === existingSuppressed)
    }

    private fun Throwable.hasCauseIdentity(target: Throwable): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current === target) return true
            val next = current.cause
            if (next === current) return false
            current = next
        }
        return false
    }

    private class MemoryConsentPersistence : ResultUploadConsentPersistence {
        var record = PersistedResultUploadConsent()
        var writeSucceeds = true
        var dropClaimMembershipOnWrite = false
        var corruptStateOnClaimWrite = false
        var readCount = 0
        var readCancellationAt: Int? = null
        var readCancellation: CancellationException? = null
        var blockReadAt: Int? = null
        val readEntered = CountDownLatch(1)
        val allowRead = CountDownLatch(1)
        var writeCount = 0
        var writeCancellationAt: Int? = null
        var writeCancellation: CancellationException? = null

        override fun read(): PersistedResultUploadConsent {
            readCount += 1
            val snapshot = record
            if (readCancellationAt == readCount) {
                throw checkNotNull(readCancellation)
            }
            if (blockReadAt == readCount) {
                readEntered.countDown()
                check(allowRead.await(5, TimeUnit.SECONDS))
            }
            return snapshot
        }

        override fun write(value: PersistedResultUploadConsent): Boolean {
            writeCount += 1
            if (writeCancellationAt == writeCount) {
                throw checkNotNull(writeCancellation)
            }
            if (!writeSucceeds) return false
            record = if (dropClaimMembershipOnWrite && value.claimedResultIds.isNotEmpty()) {
                value.copy(claimedResultIds = emptySet())
            } else if (corruptStateOnClaimWrite && value.claimedResultIds.isNotEmpty()) {
                value.copy(state = "CORRUPT_STATE")
            } else {
                value
            }
            return true
        }
    }

    private class BlockingRevokePersistence : ResultUploadConsentPersistence {
        var record = PersistedResultUploadConsent()
        var blockNextWrite = false
        val writeEntered = CountDownLatch(1)
        val allowWrite = CountDownLatch(1)

        override fun read(): PersistedResultUploadConsent = record

        override fun write(value: PersistedResultUploadConsent): Boolean {
            if (blockNextWrite) {
                blockNextWrite = false
                writeEntered.countDown()
                check(allowWrite.await(5, TimeUnit.SECONDS))
            }
            record = value
            return true
        }
    }
}
