package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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
            ResultUploadConsentMutationResult.BLOCKED_BY_PROCESS_REVOCATION,
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

    private class MemoryConsentPersistence : ResultUploadConsentPersistence {
        var record = PersistedResultUploadConsent()
        var writeSucceeds = true

        override fun read(): PersistedResultUploadConsent = record

        override fun write(value: PersistedResultUploadConsent): Boolean {
            if (!writeSucceeds) return false
            record = value
            return true
        }
    }
}
