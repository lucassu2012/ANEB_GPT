package com.aneb.probe.engine

import com.aneb.probe.data.ResultEnvelopeEntity
import com.aneb.probe.privacy.LocalPrivacyDeletionAdmission
import com.aneb.probe.privacy.LocalPrivacyOperationGate
import com.aneb.probe.privacy.LocalPrivacyOwnedMutationBoundary
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FormalResultUploadOwnerTest {
    @Test
    fun `configured verified result owns privacy through upload and body-free receipt commit`() = runBlocking {
        val runId = "123e4567-e89b-47d3-a456-426614174000"
        val token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val body = """{"schema_version":"aneb-result-v2","test_type":"token_simulation","run":{"run_id":"$runId"}}"""
        val envelope = ResultEnvelopeEntity(
            runId = runId,
            schemaVersion = "aneb-result-v2",
            testType = "token_simulation",
            startedAtEpochMs = 1_700_000_000_000L,
            serializedAtEpochMs = 1_700_000_000_001L,
            canonicalSha256 = TokenRuntimeIntegrity.canonicalSha256(body),
            bodyJson = body,
        )
        val scope = ResultUploadConsentScope(
            recipient = "aneb-node:e01-private",
            purpose = ResultUploadPurpose.RESULT_SUBMISSION,
            policyVersion = "1",
        )
        val consentStore = ResultUploadConsentStore(MemoryConsentPersistence())
        val attemptController = ResultUploadAttemptController(consentStore)
        assertEquals(ResultUploadConsentMutationResult.COMMITTED, attemptController.grant(scope))
        val gate = LocalPrivacyOperationGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val envelopeReads = AtomicInteger()
        val submitCalls = AtomicInteger()
        val receipts = mutableListOf<FormalResultUploadReceipt>()
        lateinit var capturedRequest: FormalResultUploadRequest
        val owner = FormalResultUploadOwner(
            envelopeSource = FormalResultEnvelopeSource { requestedId ->
                envelopeReads.incrementAndGet()
                envelope.takeIf { requestedId == runId }
            },
            credentialSource = FormalResultUploadCredentialSource {
                FormalResultUploadCredential(
                    endpoint = "https://upload.aneb.invalid/api/v1/results",
                    bearerToken = token,
                    scope = scope,
                )
            },
            attemptController = attemptController,
            submitter = FormalResultUploadSubmitter { request ->
                submitCalls.incrementAndGet()
                capturedRequest = request
                entered.complete(Unit)
                release.await()
                FormalResultDelivery.Accepted(201)
            },
            receiptStore = FormalResultUploadReceiptStore { receipt ->
                receipts += receipt
                true
            },
            mutationBoundary = LocalPrivacyOwnedMutationBoundary(gate),
        )
        val upload = async(start = CoroutineStart.UNDISPATCHED) { owner.upload(runId) }

        try {
            entered.await()
            assertEquals(
                LocalPrivacyDeletionAdmission.BlockedByOwnedMutation,
                gate.tryAcquireDeletion(),
            )
            assertEquals(0, receipts.size)
        } finally {
            release.complete(Unit)
        }

        assertEquals(FormalResultUploadOutcome.Uploaded(201), upload.await())
        assertEquals(1, envelopeReads.get())
        assertEquals(1, submitCalls.get())
        assertEquals(body, capturedRequest.bodyJson)
        assertEquals(runId, capturedRequest.idempotencyKey)
        assertEquals(token, capturedRequest.bearerToken)
        assertEquals(
            listOf(
                FormalResultUploadReceipt(
                    resultId = runId,
                    terminal = FormalResultUploadReceiptTerminal.ACCEPTED,
                    httpCode = 201,
                ),
            ),
            receipts,
        )
        assertFalse(receipts.single().toString().contains(token))
        assertFalse(receipts.single().toString().contains(body))
        val deletion = gate.tryAcquireDeletion()
        check(deletion is LocalPrivacyDeletionAdmission.Acquired)
        deletion.lease.close()
    }

    @Test
    fun `default missing credential keeps formal result upload disabled without touching data or transport`() = runBlocking {
        val envelopeReads = AtomicInteger()
        val submitCalls = AtomicInteger()
        val receiptCalls = AtomicInteger()
        val gate = LocalPrivacyOperationGate()
        val owner = FormalResultUploadOwner(
            envelopeSource = FormalResultEnvelopeSource {
                envelopeReads.incrementAndGet()
                error("envelope source must stay dormant")
            },
            attemptController = ResultUploadAttemptController(
                ResultUploadConsentStore(MemoryConsentPersistence()),
            ),
            submitter = FormalResultUploadSubmitter {
                submitCalls.incrementAndGet()
                error("transport must stay dormant")
            },
            receiptStore = FormalResultUploadReceiptStore {
                receiptCalls.incrementAndGet()
                error("receipt store must stay dormant")
            },
            mutationBoundary = LocalPrivacyOwnedMutationBoundary(gate),
        )

        assertEquals(
            FormalResultUploadOutcome.Disabled,
            owner.upload("123e4567-e89b-47d3-a456-426614174000"),
        )
        assertEquals(0, envelopeReads.get())
        assertEquals(0, submitCalls.get())
        assertEquals(0, receiptCalls.get())
        val deletion = gate.tryAcquireDeletion()
        check(deletion is LocalPrivacyDeletionAdmission.Acquired)
        deletion.lease.close()
    }

    @Test
    fun `envelope source cannot substitute a different verified result identity`() = runBlocking {
        val requestedRunId = "123e4567-e89b-47d3-a456-426614174000"
        val storedRunId = "123e4567-e89b-47d3-a456-426614174001"
        val token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val body = """{"schema_version":"aneb-result-v2","test_type":"token_simulation","run":{"run_id":"$storedRunId"}}"""
        val envelope = ResultEnvelopeEntity(
            runId = storedRunId,
            schemaVersion = "aneb-result-v2",
            testType = "token_simulation",
            startedAtEpochMs = 1_700_000_000_000L,
            serializedAtEpochMs = 1_700_000_000_001L,
            canonicalSha256 = TokenRuntimeIntegrity.canonicalSha256(body),
            bodyJson = body,
        )
        val scope = ResultUploadConsentScope(
            recipient = "aneb-node:e01-private",
            purpose = ResultUploadPurpose.RESULT_SUBMISSION,
            policyVersion = "1",
        )
        val consentStore = ResultUploadConsentStore(MemoryConsentPersistence())
        val attemptController = ResultUploadAttemptController(consentStore)
        assertEquals(ResultUploadConsentMutationResult.COMMITTED, attemptController.grant(scope))
        val submitCalls = AtomicInteger()
        val receiptCalls = AtomicInteger()
        val gate = LocalPrivacyOperationGate()
        val owner = FormalResultUploadOwner(
            envelopeSource = FormalResultEnvelopeSource { envelope },
            credentialSource = FormalResultUploadCredentialSource {
                FormalResultUploadCredential(
                    endpoint = "https://upload.aneb.invalid/api/v1/results",
                    bearerToken = token,
                    scope = scope,
                )
            },
            attemptController = attemptController,
            submitter = FormalResultUploadSubmitter {
                submitCalls.incrementAndGet()
                FormalResultDelivery.Accepted(201)
            },
            receiptStore = FormalResultUploadReceiptStore {
                receiptCalls.incrementAndGet()
                true
            },
            mutationBoundary = LocalPrivacyOwnedMutationBoundary(gate),
        )

        assertEquals(
            FormalResultUploadOutcome.Unverifiable(
                AnebResultJsonlExporter.RejectionKind.INTEGRITY,
            ),
            owner.upload(requestedRunId),
        )
        assertEquals(0, submitCalls.get())
        assertEquals(0, receiptCalls.get())
        assertEquals(FormalResultUploadOutcome.Uploaded(201), owner.upload(storedRunId))
        assertEquals(1, submitCalls.get())
        assertEquals(1, receiptCalls.get())
        val deletion = gate.tryAcquireDeletion()
        check(deletion is LocalPrivacyDeletionAdmission.Acquired)
        deletion.lease.close()
    }

    private class MemoryConsentPersistence : ResultUploadConsentPersistence {
        private var record = PersistedResultUploadConsent()

        override fun read(): PersistedResultUploadConsent = record

        override fun write(value: PersistedResultUploadConsent): Boolean {
            record = value
            return true
        }
    }
}
