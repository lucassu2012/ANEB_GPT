package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException

class ResultUploadConsentStoreTest {
    private val firstResultId = "11111111-1111-4111-8111-111111111111"
    private val secondResultId = "22222222-2222-4222-8222-222222222222"
    private val scope = ResultUploadConsentScope(
        recipient = "aneb-node:e01-production",
        purpose = ResultUploadPurpose.RESULT_SUBMISSION,
        policyVersion = "1",
    )

    @Test
    fun `missing and unknown persisted consent remain default off`() {
        val persistence = MemoryConsentPersistence()

        assertEquals(
            ResultUploadConsent.UNKNOWN,
            ResultUploadConsentStore(persistence).currentConsent(scope),
        )
        assertNull(
            ResultUploadConsentStore(persistence)
                .openAdmission(firstResultId, scope)
                .claim(),
        )

        persistence.record = PersistedResultUploadConsent(
            state = "granted",
            recipient = scope.recipient,
            purpose = scope.purpose.name,
            policyVersion = scope.policyVersion,
        )

        assertEquals(
            ResultUploadConsent.UNKNOWN,
            ResultUploadConsentStore(persistence).currentConsent(scope),
        )
        assertNull(
            ResultUploadConsentStore(persistence)
                .openAdmission(firstResultId, scope)
                .claim(),
        )
    }

    @Test
    fun `explicit scoped grant survives reconstruction and admission is one shot`() {
        val persistence = MemoryConsentPersistence()
        val first = ResultUploadConsentStore(persistence)

        first.grant(scope)

        val granted = ResultUploadConsentStore(persistence)
        assertEquals(ResultUploadConsent.GRANTED, granted.currentConsent(scope))
        val admission = granted.openAdmission(firstResultId, scope)
        val permit = admission.claim()
        assertNotNull(permit)
        val claimedPermit = requireNotNull(permit)
        assertEquals(firstResultId, claimedPermit.resultId)
        assertTrue(
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
                .matches(claimedPermit.attemptId),
        )
        assertNull(admission.claim())
    }

    @Test
    fun `scope mismatch and revoke before claim both fail closed`() {
        val persistence = MemoryConsentPersistence()
        val store = ResultUploadConsentStore(persistence)
        store.grant(scope)

        val liveAdmission = store.openAdmission(firstResultId, scope)
        assertNull(
            store.openAdmission(
                firstResultId,
                scope.copy(recipient = "aneb-node:other"),
            )
                .claim(),
        )

        store.revoke(scope)

        val reconstructed = ResultUploadConsentStore(persistence)
        assertEquals(ResultUploadConsent.REVOKED, reconstructed.currentConsent(scope))
        assertNull(liveAdmission.claim())
        assertNull(reconstructed.openAdmission(firstResultId, scope).claim())
    }

    @Test
    fun `failed grant is explicit and creates no permit`() {
        val persistence = MemoryConsentPersistence().apply {
            writeSucceeds = false
        }
        val store = ResultUploadConsentStore(persistence)

        assertEquals(
            ResultUploadConsentMutationResult.PERSISTENCE_FAILED,
            store.grant(scope),
        )
        assertEquals(
            ResultUploadConsent.PERSISTENCE_FAILURE_BLOCKED,
            store.currentConsent(scope),
        )
        assertNull(store.openAdmission(firstResultId, scope).claim())
    }

    @Test
    fun `failed revoke is process absorbing even when durable grant remains`() {
        val persistence = MemoryConsentPersistence()
        val processState = ResultUploadConsentProcessState()
        val first = ResultUploadConsentStore(persistence, processState)
        assertEquals(
            ResultUploadConsentMutationResult.COMMITTED,
            first.grant(scope),
        )
        val admissionOpenedBeforeRevoke = first.openAdmission(firstResultId, scope)
        persistence.writeSucceeds = false

        assertEquals(
            ResultUploadConsentMutationResult.PERSISTENCE_FAILED,
            first.revoke(scope),
        )
        assertEquals(ResultUploadConsent.GRANTED.name, persistence.record.state)
        assertNull(admissionOpenedBeforeRevoke.claim())
        assertNull(
            ResultUploadConsentStore(persistence, processState)
                .openAdmission(firstResultId, scope)
                .claim(),
        )

        persistence.writeSucceeds = true
        assertEquals(
            ResultUploadConsentMutationResult.STATE_UNAVAILABLE,
            first.grant(scope),
        )
        assertNull(first.openAdmission(firstResultId, scope).claim())
    }

    @Test
    fun `result claim is durable once while a different result remains eligible`() {
        val persistence = MemoryConsentPersistence()
        val store = ResultUploadConsentStore(persistence)
        assertEquals(
            ResultUploadConsentMutationResult.COMMITTED,
            store.grant(scope),
        )

        val firstAdmission = store.openAdmission(firstResultId, scope)
        val duplicateAdmission = store.openAdmission(firstResultId, scope)
        assertNotNull(firstAdmission.claim())
        assertNull(duplicateAdmission.claim())
        assertNull(
            ResultUploadConsentStore(persistence)
                .openAdmission(firstResultId, scope)
                .claim(),
        )

        assertEquals(
            ResultUploadConsentMutationResult.COMMITTED,
            store.revoke(scope),
        )
        val afterProcessRestart = ResultUploadConsentStore(
            persistence,
            ResultUploadConsentProcessState(),
        )
        assertEquals(
            ResultUploadConsentMutationResult.COMMITTED,
            afterProcessRestart.grant(scope),
        )
        assertNull(
            afterProcessRestart
                .openAdmission(firstResultId, scope)
                .claim(),
        )
        assertEquals(
            secondResultId,
            afterProcessRestart
                .openAdmission(secondResultId, scope)
                .claim()
                ?.resultId,
        )
    }

    @Test
    fun `claim persistence failure produces no permit`() {
        val persistence = MemoryConsentPersistence()
        val store = ResultUploadConsentStore(persistence)
        assertEquals(
            ResultUploadConsentMutationResult.COMMITTED,
            store.grant(scope),
        )
        persistence.writeSucceeds = false

        assertNull(store.openAdmission(firstResultId, scope).claim())
    }

    @Test
    fun `canonical UUIDv7 run identity is accepted and corrupt identity is rejected`() {
        val persistence = MemoryConsentPersistence()
        val store = ResultUploadConsentStore(persistence)
        val uuidV7ResultId = "01890f95-a4f2-7bcd-8a12-123456789abc"
        store.grant(scope)

        assertEquals(
            uuidV7ResultId,
            store.openAdmission(uuidV7ResultId, scope).claim()?.resultId,
        )
        assertThrows(IllegalArgumentException::class.java) {
            store.openAdmission("not-a-canonical-result-id", scope)
        }
    }

    @Test
    fun `successful durable revoke can be explicitly regranted without losing claims`() {
        val persistence = MemoryConsentPersistence()
        val processState = ResultUploadConsentProcessState()
        val store = ResultUploadConsentStore(persistence, processState)
        assertEquals(ResultUploadConsentMutationResult.COMMITTED, store.grant(scope))
        assertNotNull(store.openAdmission(firstResultId, scope).claim())
        assertEquals(ResultUploadConsentMutationResult.COMMITTED, store.revoke(scope))

        assertEquals(ResultUploadConsentMutationResult.COMMITTED, store.grant(scope))
        assertNull(store.openAdmission(firstResultId, scope).claim())
        assertEquals(
            secondResultId,
            store.openAdmission(secondResultId, scope).claim()?.resultId,
        )
    }

    @Test
    fun `concurrent admissions for one result issue exactly one permit`() {
        val persistence = MemoryConsentPersistence()
        val store = ResultUploadConsentStore(persistence)
        store.grant(scope)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<ResultUploadAdmissionDecision>())
        val workers = List(2) {
            thread(start = true) {
                val admission = store.openAdmission(firstResultId, scope)
                ready.countDown()
                check(start.await(10, TimeUnit.SECONDS))
                results += admission.decide(adapterConfigured = true)
            }
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        start.countDown()
        workers.forEach { it.join(10_000) }

        assertEquals(2, results.size)
        assertEquals(1, results.count { it is ResultUploadAdmissionDecision.Permitted })
        assertEquals(1, results.count { it is ResultUploadAdmissionDecision.AlreadyClaimed })
        assertEquals(setOf(firstResultId), persistence.record.claimedResultIds)
    }

    @Test
    fun `invalid or overflowing consent epoch fails closed without rewriting persistence`() {
        val negativeRecord = PersistedResultUploadConsent(
            state = ResultUploadConsent.GRANTED.name,
            recipient = scope.recipient,
            purpose = scope.purpose.name,
            policyVersion = scope.policyVersion,
            consentEpoch = -1L,
        )
        val negativePersistence = MemoryConsentPersistence().apply { record = negativeRecord }
        val negativeStore = ResultUploadConsentStore(
            negativePersistence,
            ResultUploadConsentProcessState(),
        )

        assertEquals(
            ResultUploadConsent.PERSISTENCE_FAILURE_BLOCKED,
            negativeStore.currentConsent(scope),
        )
        assertNull(negativeStore.openAdmission(firstResultId, scope).claim())
        assertEquals(negativeRecord, negativePersistence.record)

        val maxRecord = negativeRecord.copy(
            state = ResultUploadConsent.REVOKED.name,
            consentEpoch = Long.MAX_VALUE,
        )
        val maxPersistence = MemoryConsentPersistence().apply { record = maxRecord }
        val maxStore = ResultUploadConsentStore(
            maxPersistence,
            ResultUploadConsentProcessState(),
        )

        assertEquals(
            ResultUploadConsentMutationResult.PERSISTENCE_FAILED,
            maxStore.grant(scope),
        )
        assertEquals(maxRecord, maxPersistence.record)
        assertNull(maxStore.openAdmission(secondResultId, scope).claim())
    }

    @Test
    fun `admission opened before revoke cannot be revived by a later grant epoch`() {
        val persistence = MemoryConsentPersistence()
        val store = ResultUploadConsentStore(persistence)

        assertEquals(ResultUploadConsentMutationResult.COMMITTED, store.grant(scope))
        val staleAdmission = store.openAdmission(firstResultId, scope)
        assertEquals(ResultUploadConsentMutationResult.COMMITTED, store.revoke(scope))
        assertEquals(ResultUploadConsentMutationResult.COMMITTED, store.grant(scope))

        assertNull(staleAdmission.claim())
        assertEquals(
            secondResultId,
            store.openAdmission(secondResultId, scope).claim()?.resultId,
        )
    }

    @Test
    fun `durable revoke completion cannot clear a permanent unavailable latch`() {
        val persistence = MemoryConsentPersistence()
        val store = ResultUploadConsentStore(persistence, ResultUploadConsentProcessState())

        assertEquals(ResultUploadConsentMutationResult.COMMITTED, store.grant(scope))
        val permit = checkNotNull(store.openAdmission(firstResultId, scope).claim())
        persistence.record = persistence.record.copy(claimedResultIds = emptySet())
        assertTrue(store.observePermit(permit).state == ResultUploadPermitState.STATE_UNAVAILABLE)

        assertEquals(ResultUploadConsentMutationResult.STATE_UNAVAILABLE, store.revoke(scope))
        assertEquals(
            ResultUploadConsentMutationResult.STATE_UNAVAILABLE,
            store.grant(scope),
        )
    }

    @Test
    fun `preexisting corrupt scoped state is unavailable and cannot be overwritten by grant`() {
        val persistence = MemoryConsentPersistence().apply {
            record = PersistedResultUploadConsent(
                state = "CORRUPT_STATE",
                recipient = scope.recipient,
                purpose = scope.purpose.name,
                policyVersion = scope.policyVersion,
                consentEpoch = 1L,
            )
        }
        val store = ResultUploadConsentStore(persistence, ResultUploadConsentProcessState())

        assertEquals(ResultUploadConsent.PERSISTENCE_FAILURE_BLOCKED, store.currentConsent(scope))
        assertEquals(
            ResultUploadAdmissionDecision.PersistenceFailed,
            store.openAdmission(firstResultId, scope).decide(adapterConfigured = true),
        )
        assertEquals(
            ResultUploadConsentMutationResult.STATE_UNAVAILABLE,
            store.grant(scope),
        )
        assertEquals("CORRUPT_STATE", persistence.record.state)
    }

    @Test
    fun `grant refuses max minus one epoch instead of committing an invalid max epoch`() {
        val original = PersistedResultUploadConsent(
            state = ResultUploadConsent.REVOKED.name,
            recipient = scope.recipient,
            purpose = scope.purpose.name,
            policyVersion = scope.policyVersion,
            consentEpoch = Long.MAX_VALUE - 1L,
        )
        val persistence = MemoryConsentPersistence().apply { record = original }
        val store = ResultUploadConsentStore(persistence, ResultUploadConsentProcessState())

        assertEquals(ResultUploadConsentMutationResult.PERSISTENCE_FAILED, store.grant(scope))
        assertEquals(original, persistence.record)
        assertEquals(ResultUploadConsent.PERSISTENCE_FAILURE_BLOCKED, store.currentConsent(scope))
        assertEquals(ResultUploadConsentMutationResult.STATE_UNAVAILABLE, store.grant(scope))
    }

    @Test
    fun `healthy pending revoke is observable without masquerading as persistence failure`() {
        val persistence = MemoryConsentPersistence()
        val store = ResultUploadConsentStore(persistence, ResultUploadConsentProcessState())

        assertEquals(ResultUploadConsentMutationResult.COMMITTED, store.grant(scope))
        val durableGrant = persistence.record
        val revoke = (store.beginRevoke(scope) as ResultUploadRevokeStart.Started).token

        assertEquals(durableGrant, persistence.record)
        assertEquals(ResultUploadConsent.REVOKING, store.currentConsent(scope))

        assertEquals(ResultUploadConsentMutationResult.COMMITTED, store.completeRevoke(revoke))
        assertEquals(ResultUploadConsent.REVOKED, store.currentConsent(scope))
    }

    @Test
    fun `corrupt durable state dominates a pending revoke and permanently blocks the scope`() {
        val corrupt = PersistedResultUploadConsent(
            state = "CORRUPT_STATE",
            recipient = scope.recipient,
            purpose = scope.purpose.name,
            policyVersion = scope.policyVersion,
            consentEpoch = 1L,
        )
        val persistence = MemoryConsentPersistence().apply { record = corrupt }
        val processState = ResultUploadConsentProcessState()
        val store = ResultUploadConsentStore(persistence, processState)
        val revoke = (store.beginRevoke(scope) as ResultUploadRevokeStart.Started).token

        assertEquals(
            ResultUploadConsent.PERSISTENCE_FAILURE_BLOCKED,
            store.currentConsent(scope),
        )
        assertEquals(ResultUploadConsentMutationResult.STATE_UNAVAILABLE, store.grant(scope))
        assertEquals(ResultUploadConsentMutationResult.STATE_UNAVAILABLE, store.completeRevoke(revoke))
        assertEquals(corrupt, persistence.record)
    }

    @Test
    fun `idempotent grant cannot report committed after a revoke wave begins during its read`() {
        val persistence = BlockingReadConsentPersistence()
        val processState = ResultUploadConsentProcessState()
        val store = ResultUploadConsentStore(persistence, processState)
        assertEquals(ResultUploadConsentMutationResult.COMMITTED, store.grant(scope))
        persistence.blockNextRead = true
        val grantResult = AtomicReference<ResultUploadConsentMutationResult>()
        val grantThread = thread(start = true) {
            grantResult.set(store.grant(scope))
        }

        assertTrue(persistence.readEntered.await(5, TimeUnit.SECONDS))
        val revoke = (store.beginRevoke(scope) as ResultUploadRevokeStart.Started).token
        persistence.allowRead.countDown()
        grantThread.join(5_000)

        assertFalse(grantThread.isAlive)
        assertEquals(
            ResultUploadConsentMutationResult.BLOCKED_BY_PROCESS_REVOCATION,
            grantResult.get(),
        )
        assertEquals(ResultUploadConsent.REVOKING, store.currentConsent(scope))
        assertEquals(ResultUploadConsentMutationResult.COMMITTED, store.completeRevoke(revoke))
    }

    @Test
    fun `overlapping same scope revokes remain blocked until each exact operation completes`() {
        val persistence = MemoryConsentPersistence()
        val store = ResultUploadConsentStore(persistence, ResultUploadConsentProcessState())

        assertEquals(ResultUploadConsentMutationResult.COMMITTED, store.grant(scope))
        val firstRevoke = (store.beginRevoke(scope) as ResultUploadRevokeStart.Started).token
        val secondRevoke = (store.beginRevoke(scope) as ResultUploadRevokeStart.Started).token

        assertEquals(
            ResultUploadConsentMutationResult.COMMITTED,
            store.completeRevoke(firstRevoke),
        )
        assertEquals(
            ResultUploadConsentMutationResult.BLOCKED_BY_PROCESS_REVOCATION,
            store.grant(scope),
        )

        assertEquals(
            ResultUploadConsentMutationResult.COMMITTED,
            store.completeRevoke(secondRevoke),
        )
        assertEquals(ResultUploadConsentMutationResult.COMMITTED, store.grant(scope))
    }

    @Test
    fun `failed overlapping revoke retires every token and prevents a peer durable rewrite`() {
        val persistence = MemoryConsentPersistence()
        val processState = ResultUploadConsentProcessState()
        val store = ResultUploadConsentStore(persistence, processState)

        assertEquals(ResultUploadConsentMutationResult.COMMITTED, store.grant(scope))
        val firstRevoke = (store.beginRevoke(scope) as ResultUploadRevokeStart.Started).token
        val secondRevoke = (store.beginRevoke(scope) as ResultUploadRevokeStart.Started).token
        persistence.writeSucceeds = false

        assertEquals(
            ResultUploadConsentMutationResult.PERSISTENCE_FAILED,
            store.completeRevoke(firstRevoke),
        )
        assertEquals(
            setOf(secondRevoke.operationId),
            processState.pendingRevokeOperations[scope],
        )
        assertEquals(ResultUploadConsent.GRANTED.name, persistence.record.state)

        persistence.writeSucceeds = true
        val writesAfterFailure = persistence.writeCount
        assertEquals(
            ResultUploadConsentMutationResult.STATE_UNAVAILABLE,
            store.completeRevoke(secondRevoke),
        )
        assertEquals(writesAfterFailure, persistence.writeCount)
        assertFalse(processState.pendingRevokeOperations.containsKey(scope))
        assertEquals(ResultUploadConsent.GRANTED.name, persistence.record.state)
        assertEquals(ResultUploadConsent.PERSISTENCE_FAILURE_BLOCKED, store.currentConsent(scope))
        assertEquals(ResultUploadConsentMutationResult.STATE_UNAVAILABLE, store.grant(scope))
    }

    @Test
    fun `cancelled revoke retires its token and propagates the same cancellation object`() {
        val persistence = MemoryConsentPersistence()
        val processState = ResultUploadConsentProcessState()
        val store = ResultUploadConsentStore(persistence, processState)
        val marker = CancellationException("revoke_persistence_cancel_marker")

        assertEquals(ResultUploadConsentMutationResult.COMMITTED, store.grant(scope))
        val revoke = (store.beginRevoke(scope) as ResultUploadRevokeStart.Started).token
        persistence.writeCancellation = marker
        val cancellation = assertThrows(CancellationException::class.java) {
            store.completeRevoke(revoke)
        }

        assertTrue(cancellation === marker)
        assertFalse(processState.pendingRevokeOperations.containsKey(scope))
        assertEquals(ResultUploadConsent.PERSISTENCE_FAILURE_BLOCKED, store.currentConsent(scope))
        assertEquals(ResultUploadConsentMutationResult.STATE_UNAVAILABLE, store.grant(scope))
    }

    @Test
    fun `corrupt persisted record shapes are absorbing before requested scope comparison`() {
        val canonical = PersistedResultUploadConsent(
            state = ResultUploadConsent.GRANTED.name,
            recipient = scope.recipient,
            purpose = scope.purpose.name,
            policyVersion = scope.policyVersion,
            consentEpoch = 1L,
        )
        val corruptRecords = listOf(
            canonical.copy(recipient = null),
            canonical.copy(purpose = null),
            canonical.copy(policyVersion = null),
            canonical.copy(state = null),
            canonical.copy(claimedResultIds = setOf("not-a-canonical-result-id")),
            canonical.copy(
                state = "CORRUPT_STATE",
                recipient = "aneb-node:foreign",
            ),
        )

        corruptRecords.forEachIndexed { index, original ->
            val persistence = MemoryConsentPersistence().apply { record = original }
            val store = ResultUploadConsentStore(
                persistence,
                ResultUploadConsentProcessState(),
            )

            assertEquals(
                "record[$index]",
                ResultUploadConsent.PERSISTENCE_FAILURE_BLOCKED,
                store.currentConsent(scope),
            )
            assertEquals(
                "record[$index]",
                ResultUploadConsentMutationResult.STATE_UNAVAILABLE,
                store.grant(scope),
            )
            assertEquals("record[$index]", original, persistence.record)
        }
    }

    @Test
    fun `revoke as first observer cannot rewrite a corrupt record`() {
        val original = PersistedResultUploadConsent(
            state = "CORRUPT_STATE",
            recipient = scope.recipient,
            purpose = scope.purpose.name,
            policyVersion = scope.policyVersion,
            consentEpoch = 1L,
        )
        val persistence = MemoryConsentPersistence().apply { record = original }
        val store = ResultUploadConsentStore(persistence, ResultUploadConsentProcessState())

        assertEquals(ResultUploadConsentMutationResult.PERSISTENCE_FAILED, store.revoke(scope))
        assertEquals(original, persistence.record)
        assertEquals(
            ResultUploadConsentMutationResult.STATE_UNAVAILABLE,
            store.grant(scope),
        )
    }

    private class MemoryConsentPersistence : ResultUploadConsentPersistence {
        var record = PersistedResultUploadConsent()
        var writeSucceeds = true
        var writeCount = 0
        var writeCancellation: CancellationException? = null

        override fun read(): PersistedResultUploadConsent = record

        override fun write(value: PersistedResultUploadConsent): Boolean {
            writeCount += 1
            writeCancellation?.let { throw it }
            if (!writeSucceeds) return false
            record = value
            return true
        }
    }

    private class BlockingReadConsentPersistence : ResultUploadConsentPersistence {
        var record = PersistedResultUploadConsent()
        var blockNextRead = false
        val readEntered = CountDownLatch(1)
        val allowRead = CountDownLatch(1)

        override fun read(): PersistedResultUploadConsent {
            val snapshot = record
            if (blockNextRead) {
                blockNextRead = false
                readEntered.countDown()
                check(allowRead.await(5, TimeUnit.SECONDS))
            }
            return snapshot
        }

        override fun write(value: PersistedResultUploadConsent): Boolean {
            record = value
            return true
        }
    }
}
