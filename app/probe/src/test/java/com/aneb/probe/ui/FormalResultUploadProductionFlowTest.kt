package com.aneb.probe.ui

import com.aneb.probe.data.ResultEnvelopeEntity
import com.aneb.probe.engine.FormalResultDelivery
import com.aneb.probe.engine.FormalResultEnvelopeSource
import com.aneb.probe.engine.FormalResultUploadCredential
import com.aneb.probe.engine.FormalResultUploadCredentialSource
import com.aneb.probe.engine.FormalResultUploadOutcome
import com.aneb.probe.engine.FormalResultUploadOwner
import com.aneb.probe.engine.FormalResultUploadReceipt
import com.aneb.probe.engine.FormalResultUploadReceiptStore
import com.aneb.probe.engine.FormalResultUploadReceiptTerminal
import com.aneb.probe.engine.FormalResultUploadRequest
import com.aneb.probe.engine.FormalResultUploadSubmitter
import com.aneb.probe.engine.PersistedResultUploadConsent
import com.aneb.probe.engine.ResultUploadAttemptController
import com.aneb.probe.engine.ResultUploadConsent
import com.aneb.probe.engine.ResultUploadConsentPersistence
import com.aneb.probe.engine.ResultUploadConsentScope
import com.aneb.probe.engine.ResultUploadConsentStore
import com.aneb.probe.engine.ResultUploadPurpose
import com.aneb.probe.engine.TokenRuntimeIntegrity
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

class FormalResultUploadProductionFlowTest {
    @Test
    fun `user confirmation uploads only the selected verified result once and exposes a stable receipt`() =
        runBlocking {
            val selectedId = "123e4567-e89b-47d3-a456-426614174000"
            val otherId = "123e4567-e89b-47d3-a456-426614174001"
            val token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            val endpoint = "https://upload.aneb.invalid/api/v1/results"
            val selectedBody = resultBody(selectedId, "token_simulation")
            val otherBody = resultBody(otherId, "realtime_simulation")
            val envelopes = mapOf(
                selectedId to envelope(selectedId, "token_simulation", selectedBody),
                otherId to envelope(otherId, "realtime_simulation", otherBody),
            )
            val scope = ResultUploadConsentScope(
                recipient = "aneb-node:e01-private",
                purpose = ResultUploadPurpose.RESULT_SUBMISSION,
                policyVersion = "1",
            )
            val persistence = MemoryConsentPersistence()
            val consentStore = ResultUploadConsentStore(persistence)
            val attemptController = ResultUploadAttemptController(consentStore)
            val gate = LocalPrivacyOperationGate()
            val selectedEntered = CompletableDeferred<Unit>()
            val releaseSelected = CompletableDeferred<Unit>()
            val submitCalls = AtomicInteger()
            val otherSubmitCalls = AtomicInteger()
            val requests = mutableListOf<FormalResultUploadRequest>()
            val receipts = mutableListOf<FormalResultUploadReceipt>()
            val credentialSource = FormalResultUploadCredentialSource {
                FormalResultUploadCredential(endpoint, token, scope)
            }
            val envelopeSource = FormalResultEnvelopeSource(envelopes::get)
            val submitter = FormalResultUploadSubmitter { request ->
                submitCalls.incrementAndGet()
                requests += request
                if (request.idempotencyKey == selectedId) {
                    selectedEntered.complete(Unit)
                    releaseSelected.await()
                } else {
                    otherSubmitCalls.incrementAndGet()
                }
                FormalResultDelivery.Accepted(201)
            }
            val receiptStore = FormalResultUploadReceiptStore { receipt ->
                receipts += receipt
                true
            }
            val flow = FormalResultUploadProductionFlow(
                envelopeSource = envelopeSource,
                credentialSource = credentialSource,
                attemptController = attemptController,
                submitter = submitter,
                receiptStore = receiptStore,
                mutationBoundary = LocalPrivacyOwnedMutationBoundary(gate),
            )

            val preparation = flow.prepare(selectedId)
            check(preparation is FormalResultUploadPreparation.Ready)
            assertEquals("仅提交这条已验证结果。确认后将发送到 ANEB 服务。", preparation.prompt)
            assertEquals(0, submitCalls.get())
            assertEquals(ResultUploadConsent.UNKNOWN, consentStore.currentConsent(scope))

            val selectedUpload = async(start = CoroutineStart.UNDISPATCHED) {
                flow.confirm(preparation.confirmation)
            }
            selectedEntered.await()

            val competingOwner = FormalResultUploadOwner(
                envelopeSource = envelopeSource,
                credentialSource = credentialSource,
                attemptController = attemptController,
                submitter = submitter,
                receiptStore = receiptStore,
                mutationBoundary = LocalPrivacyOwnedMutationBoundary(gate),
            )
            assertEquals(FormalResultUploadOutcome.NotConsented, competingOwner.upload(otherId))
            assertEquals(0, otherSubmitCalls.get())

            releaseSelected.complete(Unit)
            assertEquals(
                FormalResultUploadUiReceipt.Accepted(
                    httpCode = 201,
                    message = "已提交：服务器接受了这次请求。",
                ),
                selectedUpload.await(),
            )
            assertEquals(ResultUploadConsent.REVOKED, consentStore.currentConsent(scope))
            assertEquals(1, submitCalls.get())
            assertEquals(selectedBody, requests.single().bodyJson)
            assertEquals(selectedId, requests.single().idempotencyKey)
            assertEquals(
                listOf(
                    FormalResultUploadReceipt.from(
                        delivery = FormalResultDelivery.Accepted(201),
                        envelope = envelopes.getValue(selectedId),
                        scope = scope,
                    ),
                ),
                receipts,
            )

            assertEquals(
                FormalResultUploadUiReceipt.AlreadyHandled,
                flow.confirm(preparation.confirmation),
            )
            assertEquals(1, submitCalls.get())
            val observable = listOf(preparation.prompt, selectedUpload.await().toString()).joinToString()
            listOf(token, endpoint, selectedBody, otherBody, "FormalResultUploadOutcome").forEach {
                forbidden -> assertFalse(observable.contains(forbidden))
            }
        }

    private fun resultBody(runId: String, testType: String): String =
        """{"schema_version":"aneb-result-v2","test_type":"$testType","run":{"run_id":"$runId"}}"""

    private fun envelope(runId: String, testType: String, body: String) = ResultEnvelopeEntity(
        runId = runId,
        schemaVersion = "aneb-result-v2",
        testType = testType,
        startedAtEpochMs = 1_700_000_000_000L,
        serializedAtEpochMs = 1_700_000_000_001L,
        canonicalSha256 = TokenRuntimeIntegrity.canonicalSha256(body),
        bodyJson = body,
    )

    private class MemoryConsentPersistence : ResultUploadConsentPersistence {
        private var value = PersistedResultUploadConsent()

        override fun read(): PersistedResultUploadConsent = value

        override fun write(value: PersistedResultUploadConsent): Boolean {
            this.value = value
            return true
        }
    }
}
