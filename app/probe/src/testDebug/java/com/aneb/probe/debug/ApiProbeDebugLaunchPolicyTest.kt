package com.aneb.probe.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiProbeDebugLaunchPolicyTest {
    private val valid = ApiProbeDebugLaunchPolicy.RawRequest(
        autorun = true,
        server = "https://api.example.test",
        apiKey = "secret-test-key",
        provider = "openai_compat",
        model = "test-model",
    )

    @Test fun validOpenAiCompatibleLaunchIsAccepted() {
        val decision = decide(valid)

        assertTrue(decision is ApiProbeDebugLaunchPolicy.Decision.Run)
        decision as ApiProbeDebugLaunchPolicy.Decision.Run
        assertEquals(ApiProbeDebugLaunchPolicy.Provider.OPENAI_COMPAT, decision.provider)
        assertEquals("https://api.example.test", decision.server)
        assertEquals("test-model", decision.model)
    }

    @Test fun providerIsExplicitAndUnknownValuesFailClosed() {
        val anthropic = decide(valid.copy(provider = "AnThRoPiC"))
        assertEquals(
            ApiProbeDebugLaunchPolicy.Provider.ANTHROPIC,
            (anthropic as ApiProbeDebugLaunchPolicy.Decision.Run).provider,
        )

        val defaultProvider = decide(valid.copy(provider = null))
        assertEquals(
            ApiProbeDebugLaunchPolicy.Provider.OPENAI_COMPAT,
            (defaultProvider as ApiProbeDebugLaunchPolicy.Decision.Run).provider,
        )

        assertRejected(
            valid.copy(provider = "other"),
            ApiProbeDebugLaunchPolicy.RejectReason.PROVIDER_INVALID,
        )
    }

    @Test fun requiredLaunchParametersFailClosed() {
        assertRejected(
            valid.copy(autorun = false),
            ApiProbeDebugLaunchPolicy.RejectReason.AUTORUN_NOT_REQUESTED,
        )
        assertRejected(
            valid.copy(server = "  "),
            ApiProbeDebugLaunchPolicy.RejectReason.SERVER_MISSING,
        )
        assertRejected(
            valid.copy(apiKey = null),
            ApiProbeDebugLaunchPolicy.RejectReason.KEY_MISSING,
        )
    }

    @Test fun recreationAndConcurrentRepeatAreRejected() {
        assertRejected(
            valid,
            ApiProbeDebugLaunchPolicy.RejectReason.RECREATED,
            firstCreation = false,
        )
        assertRejected(
            valid,
            ApiProbeDebugLaunchPolicy.RejectReason.ALREADY_RUNNING,
            singleFlightAcquired = false,
        )
    }

    @Test fun rawAndAcceptedDecisionsNeverRenderCallerSecrets() {
        val decision = decide(valid) as ApiProbeDebugLaunchPolicy.Decision.Run
        val rendered = valid.toString() + decision.toString()

        assertFalse(rendered.contains(valid.apiKey!!))
        assertFalse(rendered.contains(valid.server!!))
        assertFalse(rendered.contains(valid.model!!))
    }

    private fun decide(
        request: ApiProbeDebugLaunchPolicy.RawRequest,
        firstCreation: Boolean = true,
        singleFlightAcquired: Boolean = true,
    ) = ApiProbeDebugLaunchPolicy.decide(firstCreation, singleFlightAcquired, request)

    private fun assertRejected(
        request: ApiProbeDebugLaunchPolicy.RawRequest,
        expected: ApiProbeDebugLaunchPolicy.RejectReason,
        firstCreation: Boolean = true,
        singleFlightAcquired: Boolean = true,
    ) {
        val decision = decide(request, firstCreation, singleFlightAcquired)
        assertEquals(expected, (decision as ApiProbeDebugLaunchPolicy.Decision.Reject).reason)
    }
}
