package com.aneb.probe.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ResultSubmissionCoordinatorTest {
    @Test
    fun `unknown consent commits the local result and constructs no upload boundary`() = runBlocking {
        val events = mutableListOf<String>()
        var requestConstructionCount = 0
        var transportConstructionCount = 0

        val outcome = ResultSubmissionCoordinator().submit(
            localCommit = { reportStatus ->
                events += "local:$reportStatus"
            },
            finalStatusCommit = {
                error("unknown consent must not update an upload status")
            },
            requestFactory = {
                requestConstructionCount += 1
                error("upload request must not be constructed without consent")
            },
            transportFactory = {
                transportConstructionCount += 1
                error("upload transport must not be constructed without consent")
            },
        )

        assertEquals(ResultSubmissionOutcome.NotConsented, outcome)
        assertEquals("not_consented", outcome.reportStatus)
        assertEquals(listOf("local:not_consented"), events)
        assertEquals(0, requestConstructionCount)
        assertEquals(0, transportConstructionCount)
    }

    @Test
    fun `local commit failure returns a stable failure and constructs no upload boundary`() = runBlocking {
        var requestConstructionCount = 0
        var transportConstructionCount = 0

        val outcome = ResultSubmissionCoordinator().submit(
            localCommit = {
                error("local commit failed")
            },
            finalStatusCommit = {
                error("local commit failure must not update an upload status")
            },
            requestFactory = {
                requestConstructionCount += 1
                error("upload request must not be constructed after a local commit failure")
            },
            transportFactory = {
                transportConstructionCount += 1
                error("upload transport must not be constructed after a local commit failure")
            },
        )

        assertEquals(ResultSubmissionOutcome.LocalPersistenceFailed, outcome)
        assertEquals("local_persistence_failed", outcome.reportStatus)
        assertEquals(0, requestConstructionCount)
        assertEquals(0, transportConstructionCount)
    }

    @Test
    fun `explicit scoped consent uploads once after local commit and retains local on rejection`() = runBlocking {
        val resultId = "33333333-3333-4333-8333-333333333333"
        val persistence = MemoryConsentPersistence()
        val store = ResultUploadConsentStore(persistence)
        val scope = ResultUploadConsentScope(
            recipient = "aneb-node:e01-production",
            purpose = ResultUploadPurpose.RESULT_SUBMISSION,
            policyVersion = "1",
        )
        store.grant(scope)
        val events = mutableListOf<String>()
        var localCommitted = false

        val outcome = ResultSubmissionCoordinator(
            admission = store.openAdmission(resultId, scope),
        ).submit(
            localCommit = { reportStatus ->
                events += "local:$reportStatus"
                localCommitted = true
            },
            finalStatusCommit = { reportStatus ->
                events += "status:$reportStatus"
            },
            requestFactory = {
                events += "request"
                ResultSubmissionRequest(
                    resultId = resultId,
                    scope = scope,
                    jsonBody = "{\"schema_version\":\"aneb-result-v2\"}",
                )
            },
            transportFactory = {
                events += "transport"
                ResultSubmissionTransport {
                    events += "submit"
                    ResultUploadTransportOutcome.Rejected4xx(statusCode = 400)
                }
            },
        )

        assertEquals(ResultSubmissionOutcome.Rejected(httpCode = 400), outcome)
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
        assertEquals(true, localCommitted)
        assertEquals(1, events.count { it == "submit" })
    }

    @Test
    fun `request scope mismatch fails before transport construction`() = runBlocking {
        val resultId = "44444444-4444-4444-8444-444444444444"
        val persistence = MemoryConsentPersistence()
        val store = ResultUploadConsentStore(persistence)
        val grantedScope = ResultUploadConsentScope(
            recipient = "aneb-node:e01-production",
            purpose = ResultUploadPurpose.RESULT_SUBMISSION,
            policyVersion = "1",
        )
        store.grant(grantedScope)
        val events = mutableListOf<String>()

        val outcome = ResultSubmissionCoordinator(
            admission = store.openAdmission(resultId, grantedScope),
        ).submit(
            localCommit = { events += "local:$it" },
            finalStatusCommit = { events += "status:$it" },
            requestFactory = {
                events += "request"
                ResultSubmissionRequest(
                    resultId = resultId,
                    scope = grantedScope.copy(recipient = "aneb-node:other"),
                    jsonBody = "{}",
                )
            },
            transportFactory = {
                events += "transport"
                error("scope mismatch must not construct transport")
            },
        )

        assertEquals(ResultSubmissionOutcome.ScopeMismatch, outcome)
        assertEquals(
            listOf(
                "local:upload_authorized_local",
                "request",
                "status:upload_scope_mismatch",
            ),
            events,
        )
    }

    @Test
    fun `request result mismatch fails before transport construction`() = runBlocking {
        val permittedResultId = "55555555-5555-4555-8555-555555555555"
        val persistence = MemoryConsentPersistence()
        val store = ResultUploadConsentStore(persistence)
        val scope = ResultUploadConsentScope(
            recipient = "aneb-node:e01-production",
            purpose = ResultUploadPurpose.RESULT_SUBMISSION,
            policyVersion = "1",
        )
        store.grant(scope)
        var transportConstructionCount = 0

        val outcome = ResultSubmissionCoordinator(
            admission = store.openAdmission(permittedResultId, scope),
        ).submit(
            localCommit = {},
            finalStatusCommit = {},
            requestFactory = {
                ResultSubmissionRequest(
                    resultId = "66666666-6666-4666-8666-666666666666",
                    scope = scope,
                    jsonBody = "{}",
                )
            },
            transportFactory = {
                transportConstructionCount += 1
                error("result mismatch must not construct transport")
            },
        )

        assertEquals(ResultSubmissionOutcome.ScopeMismatch, outcome)
        assertEquals(0, transportConstructionCount)
    }

    @Test
    fun `granted local commit failure constructs no upload boundary`() = runBlocking {
        val resultId = "77777777-7777-4777-8777-777777777777"
        val persistence = MemoryConsentPersistence()
        val store = ResultUploadConsentStore(persistence)
        val scope = ResultUploadConsentScope(
            recipient = "aneb-node:e01-production",
            purpose = ResultUploadPurpose.RESULT_SUBMISSION,
            policyVersion = "1",
        )
        store.grant(scope)
        var requestConstructionCount = 0
        var transportConstructionCount = 0

        val outcome = ResultSubmissionCoordinator(
            admission = store.openAdmission(resultId, scope),
        ).submit(
            localCommit = { error("local transaction failed") },
            finalStatusCommit = {
                error("local transaction failure must not update an upload status")
            },
            requestFactory = {
                requestConstructionCount += 1
                error("local failure must not construct request")
            },
            transportFactory = {
                transportConstructionCount += 1
                error("local failure must not construct transport")
            },
        )

        assertEquals(ResultSubmissionOutcome.LocalPersistenceFailed, outcome)
        assertEquals(0, requestConstructionCount)
        assertEquals(0, transportConstructionCount)
        assertNull(store.openAdmission(resultId, scope).claim())
    }

    @Test
    fun `transport failure is attempted once with local result retained`() = runBlocking {
        val resultId = "88888888-8888-4888-8888-888888888888"
        val persistence = MemoryConsentPersistence()
        val store = ResultUploadConsentStore(persistence)
        val scope = ResultUploadConsentScope(
            recipient = "aneb-node:e01-production",
            purpose = ResultUploadPurpose.RESULT_SUBMISSION,
            policyVersion = "1",
        )
        store.grant(scope)
        var localCommitCount = 0
        var finalStatusCommitCount = 0
        var submitCount = 0

        val outcome = ResultSubmissionCoordinator(
            admission = store.openAdmission(resultId, scope),
        ).submit(
            localCommit = { localCommitCount += 1 },
            finalStatusCommit = { finalStatusCommitCount += 1 },
            requestFactory = {
                ResultSubmissionRequest(
                    resultId = resultId,
                    scope = scope,
                    jsonBody = "{}",
                )
            },
            transportFactory = {
                ResultSubmissionTransport {
                    submitCount += 1
                    ResultUploadTransportOutcome.IoFailure
                }
            },
        )

        assertEquals(ResultSubmissionOutcome.TransportFailed, outcome)
        assertEquals(1, localCommitCount)
        assertEquals(1, finalStatusCommitCount)
        assertEquals(1, submitCount)
    }

    @Test
    fun `already claimed result mutates no local or upload boundary`() = runBlocking {
        val resultId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val persistence = MemoryConsentPersistence()
        val store = ResultUploadConsentStore(persistence)
        val scope = ResultUploadConsentScope(
            recipient = "aneb-node:e01-production",
            purpose = ResultUploadPurpose.RESULT_SUBMISSION,
            policyVersion = "1",
        )
        store.grant(scope)
        assertNotNull(store.openAdmission(resultId, scope).claim())
        var localCommitCount = 0
        var finalStatusCommitCount = 0
        var requestConstructionCount = 0
        var transportConstructionCount = 0

        val outcome = ResultSubmissionCoordinator(
            admission = store.openAdmission(resultId, scope),
        ).submit(
            localCommit = { localCommitCount += 1 },
            finalStatusCommit = { finalStatusCommitCount += 1 },
            requestFactory = {
                requestConstructionCount += 1
                error("duplicate result must not construct request")
            },
            transportFactory = {
                transportConstructionCount += 1
                error("duplicate result must not construct transport")
            },
        )

        assertEquals(ResultSubmissionOutcome.AlreadyClaimed, outcome)
        assertEquals(0, localCommitCount)
        assertEquals(0, finalStatusCommitCount)
        assertEquals(0, requestConstructionCount)
        assertEquals(0, transportConstructionCount)
    }

    @Test
    fun `already claimed result remains non mutating when upload adapter is absent`() = runBlocking {
        val resultId = "cdcdcdcd-cdcd-4cdc-8cdc-cdcdcdcdcdcd"
        val persistence = MemoryConsentPersistence()
        val store = ResultUploadConsentStore(persistence)
        val scope = ResultUploadConsentScope(
            recipient = "aneb-node:e01-production",
            purpose = ResultUploadPurpose.RESULT_SUBMISSION,
            policyVersion = "1",
        )
        store.grant(scope)
        assertNotNull(store.openAdmission(resultId, scope).claim())
        var localCommitCount = 0
        var finalStatusCommitCount = 0

        val outcome = ResultSubmissionCoordinator(
            admission = store.openAdmission(resultId, scope),
        ).submit(
            localCommit = { localCommitCount += 1 },
            finalStatusCommit = { finalStatusCommitCount += 1 },
            adapter = null,
        )

        assertEquals(ResultSubmissionOutcome.AlreadyClaimed, outcome)
        assertEquals(0, localCommitCount)
        assertEquals(0, finalStatusCommitCount)
    }

    @Test
    fun `failed revoke is reported as admission persistence failure`() = runBlocking {
        val resultId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        val persistence = MemoryConsentPersistence()
        val store = ResultUploadConsentStore(persistence)
        val scope = ResultUploadConsentScope(
            recipient = "aneb-node:e01-production",
            purpose = ResultUploadPurpose.RESULT_SUBMISSION,
            policyVersion = "1",
        )
        store.grant(scope)
        val admission = store.openAdmission(resultId, scope)
        persistence.writeSucceeds = false
        assertEquals(
            ResultUploadConsentMutationResult.PERSISTENCE_FAILED,
            store.revoke(scope),
        )
        val events = mutableListOf<String>()

        val outcome = ResultSubmissionCoordinator(admission = admission).submit(
            localCommit = { events += "local:$it" },
            finalStatusCommit = { events += "status:$it" },
            requestFactory = {
                events += "request"
                error("blocked policy must not construct request")
            },
            transportFactory = {
                events += "transport"
                error("blocked policy must not construct transport")
            },
        )

        assertEquals(ResultSubmissionOutcome.AdmissionPersistenceFailed, outcome)
        assertEquals(listOf("local:upload_admission_persistence_failed"), events)
    }

    @Test
    fun `request adapter failure is not misclassified as transport failure`() = runBlocking {
        val resultId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
        val persistence = MemoryConsentPersistence()
        val store = ResultUploadConsentStore(persistence)
        val scope = ResultUploadConsentScope(
            recipient = "aneb-node:e01-production",
            purpose = ResultUploadPurpose.RESULT_SUBMISSION,
            policyVersion = "1",
        )
        store.grant(scope)
        val events = mutableListOf<String>()

        val outcome = ResultSubmissionCoordinator(
            admission = store.openAdmission(resultId, scope),
        ).submit(
            localCommit = { events += "local:$it" },
            finalStatusCommit = { events += "status:$it" },
            requestFactory = {
                events += "request"
                error("arbitrary adapter detail must not escape")
            },
            transportFactory = {
                events += "transport"
                error("request failure must not construct transport")
            },
        )

        assertEquals(ResultSubmissionOutcome.AdapterFailed, outcome)
        assertEquals(
            listOf(
                "local:upload_authorized_local",
                "request",
                "status:upload_adapter_failed",
            ),
            events,
        )
    }

    @Test
    fun `transport adapter construction failure is not misclassified as a transport attempt`() = runBlocking {
        val resultId = "12121212-1212-4121-8121-121212121212"
        val persistence = MemoryConsentPersistence()
        val store = ResultUploadConsentStore(persistence)
        val scope = ResultUploadConsentScope(
            recipient = "aneb-node:e01-production",
            purpose = ResultUploadPurpose.RESULT_SUBMISSION,
            policyVersion = "1",
        )
        store.grant(scope)
        val events = mutableListOf<String>()
        var submitCount = 0
        val transport = ResultSubmissionTransport {
            submitCount += 1
            ResultUploadTransportOutcome.Accepted(200)
        }

        val outcome = ResultSubmissionCoordinator(
            admission = store.openAdmission(resultId, scope),
        ).submit(
            localCommit = { events += "local:$it" },
            finalStatusCommit = { events += "status:$it" },
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
                transport
                error("transport factory sentinel must not escape")
            },
        )

        assertEquals(ResultSubmissionOutcome.AdapterFailed, outcome)
        assertEquals(0, submitCount)
        assertEquals(
            listOf(
                "local:upload_authorized_local",
                "request",
                "transport",
                "status:upload_adapter_failed",
            ),
            events,
        )
    }

    @Test
    fun `final status persistence failure blocks successful publication`() = runBlocking {
        val resultId = "ffffffff-ffff-4fff-8fff-ffffffffffff"
        val persistence = MemoryConsentPersistence()
        val store = ResultUploadConsentStore(persistence)
        val scope = ResultUploadConsentScope(
            recipient = "aneb-node:e01-production",
            purpose = ResultUploadPurpose.RESULT_SUBMISSION,
            policyVersion = "1",
        )
        store.grant(scope)
        var submitCount = 0

        val outcome = ResultSubmissionCoordinator(
            admission = store.openAdmission(resultId, scope),
        ).submit(
            localCommit = {},
            finalStatusCommit = { error("final status transaction failed") },
            requestFactory = {
                ResultSubmissionRequest(
                    resultId = resultId,
                    scope = scope,
                    jsonBody = "{}",
                )
            },
            transportFactory = {
                ResultSubmissionTransport {
                    submitCount += 1
                    ResultUploadTransportOutcome.Accepted(200)
                }
            },
        )

        assertEquals(ResultSubmissionOutcome.FinalStatusPersistenceFailed, outcome)
        assertEquals(1, submitCount)
    }

    private class MemoryConsentPersistence : ResultUploadConsentPersistence {
        private var record = PersistedResultUploadConsent()
        var writeSucceeds = true

        override fun read(): PersistedResultUploadConsent = record

        override fun write(value: PersistedResultUploadConsent): Boolean {
            if (!writeSucceeds) return false
            record = value
            return true
        }
    }
}
