package com.aneb.probe.apiprobe

import com.aneb.probe.privacy.LocalPrivacyDeletionAdmission
import com.aneb.probe.privacy.LocalPrivacyOperationGate
import com.aneb.probe.privacy.LocalPrivacyOwnedRunExecutionBoundary
import com.aneb.probe.privacy.OwnedRunBlockReason
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ApiProbePrivacyGateTest {
    @Test
    fun `privacy deletion blocks api probe before any probe work`() = runBlocking {
        val operationGate = LocalPrivacyOperationGate()
        val deletion = operationGate.tryAcquireDeletion() as LocalPrivacyDeletionAdmission.Acquired
        val logs = mutableListOf<String>()
        val probe = ApiProbe(
            context = RuntimeEnvironment.getApplication(),
            runBoundary = LocalPrivacyOwnedRunExecutionBoundary(operationGate),
        )

        try {
            val error = try {
                probe.run(
                    config = ApiProbe.Config(
                        provider = LlmProvider.OPENAI_COMPAT,
                        baseUrl = "https://must-not-be-used.invalid/v1",
                        model = "must-not-be-used",
                        apiKey = "must-not-be-used",
                    ),
                    log = logs::add,
                )
                null
            } catch (blocked: ApiProbeRunBlockedException) {
                blocked
            }

            requireNotNull(error)
            assertEquals(OwnedRunBlockReason.PRIVACY_DELETION_IN_PROGRESS, error.reason)
            assertTrue(logs.isEmpty())
        } finally {
            deletion.lease.close()
        }
    }
}
