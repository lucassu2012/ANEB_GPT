package com.aneb.probe.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TestEngineResultSubmissionTest {
    @Test
    fun `test engine completion seam commits locally and never enters upload when consent is unknown`() =
        runBlocking {
            val events = mutableListOf<String>()
            var requestConstructionCount = 0
            var transportConstructionCount = 0

            val outcome = TestEngine.completeResultSubmission(
                coordinator = ResultSubmissionCoordinator(),
                localCommit = { reportStatus ->
                    events += "local:$reportStatus"
                },
                finalStatusCommit = {
                    error("unknown consent must not update an upload status")
                },
                adapter = ResultSubmissionAdapter(
                    requestFactory = {
                        requestConstructionCount += 1
                        error("TestEngine must not construct an upload request without consent")
                    },
                    transportFactory = {
                        transportConstructionCount += 1
                        error("TestEngine must not construct an upload transport without consent")
                    },
                ),
            )

            assertEquals(ResultSubmissionOutcome.NotConsented, outcome)
            assertEquals(listOf("local:not_consented"), events)
            assertEquals(0, requestConstructionCount)
            assertEquals(0, transportConstructionCount)
        }

    @Test
    fun `explicit admission without adapter commits not configured and consumes no permit`() =
        runBlocking {
            val resultId = "99999999-9999-4999-8999-999999999999"
            val persistence = MemoryConsentPersistence()
            val store = ResultUploadConsentStore(persistence)
            val scope = ResultUploadConsentScope(
                recipient = "aneb-node:e01-production",
                purpose = ResultUploadPurpose.RESULT_SUBMISSION,
                policyVersion = "1",
            )
            store.grant(scope)
            val admission = store.openAdmission(resultId, scope)
            val events = mutableListOf<String>()

            val outcome = TestEngine.completeResultSubmission(
                coordinator = ResultSubmissionCoordinator(admission = admission),
                localCommit = { reportStatus -> events += "local:$reportStatus" },
                finalStatusCommit = {
                    error("missing adapter must not update an upload status")
                },
                adapter = null,
            )

            assertEquals(ResultSubmissionOutcome.NotConfigured, outcome)
            assertEquals(listOf("local:upload_not_configured"), events)
            assertNotNull(admission.claim())
        }

    @Test
    fun `revoked admission without adapter remains not consented`() = runBlocking {
        val resultId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val persistence = MemoryConsentPersistence()
        val store = ResultUploadConsentStore(persistence)
        val scope = ResultUploadConsentScope(
            recipient = "aneb-node:e01-production",
            purpose = ResultUploadPurpose.RESULT_SUBMISSION,
            policyVersion = "1",
        )
        store.grant(scope)
        val admission = store.openAdmission(resultId, scope)
        store.revoke(scope)
        val events = mutableListOf<String>()

        val outcome = TestEngine.completeResultSubmission(
            coordinator = ResultSubmissionCoordinator(admission = admission),
            localCommit = { reportStatus -> events += "local:$reportStatus" },
            finalStatusCommit = {
                error("revoked admission must not update an upload status")
            },
            adapter = null,
        )

        assertEquals(ResultSubmissionOutcome.NotConsented, outcome)
        assertEquals(listOf("local:not_consented"), events)
        assertEquals(null, admission.claim())
    }

    @Test
    fun `configured rejection commits the final local status after one attempt`() = runBlocking {
        val resultId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val persistence = MemoryConsentPersistence()
        val store = ResultUploadConsentStore(persistence)
        val scope = ResultUploadConsentScope(
            recipient = "aneb-node:e01-production",
            purpose = ResultUploadPurpose.RESULT_SUBMISSION,
            policyVersion = "1",
        )
        store.grant(scope)
        val events = mutableListOf<String>()

        val outcome = TestEngine.completeResultSubmission(
            coordinator = ResultSubmissionCoordinator(
                admission = store.openAdmission(resultId, scope),
            ),
            localCommit = { reportStatus -> events += "local:$reportStatus" },
            finalStatusCommit = { reportStatus -> events += "status:$reportStatus" },
            adapter = ResultSubmissionAdapter(
                requestFactory = {
                    events += "request"
                    ResultSubmissionRequest(
                        resultId = resultId,
                        scope = scope,
                        jsonBody = "{}",
                    )
                },
                transportFactory = {
                    events += "transport"
                    ResultSubmissionTransport {
                        events += "submit"
                        ResultUploadTransportOutcome.Rejected4xx(400)
                    }
                },
            ),
        )

        assertEquals(ResultSubmissionOutcome.Rejected(400), outcome)
        assertEquals(
            listOf(
                "local:upload_authorized_local",
                "request",
                "transport",
                "submit",
                "status:upload_rejected",
            ),
            events,
        )
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
