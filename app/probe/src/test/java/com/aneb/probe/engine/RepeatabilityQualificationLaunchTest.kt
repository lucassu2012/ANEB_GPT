package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class RepeatabilityQualificationLaunchTest {
    @Test
    fun `exact q1 token launch resolves the qualification variant`() {
        val verified = RepeatabilityQualificationLaunch.verify(
            request(
                stageId = "Q1_WIFI",
                transport = TestEngine.TransportMode.WIFI,
                testMode = AnebTestMode.TOKEN_SIMULATION,
                profileId = "token_multimodal_repeatability_qualification",
                profileSha256 = TOKEN_PROFILE_SHA,
                runtimePlanSha256 = TOKEN_RUNTIME_SHA,
            ),
        )

        assertEquals("repeatability_qualification", verified.variant)
        assertEquals("Q1_WIFI", verified.stageId)
        assertEquals("token_multimodal_repeatability_qualification", verified.profileId)
    }

    @Test
    fun `exact q2 realtime and network launches remain distinct from quick`() {
        val realtime = RepeatabilityQualificationLaunch.verify(
            request(
                stageId = "Q2_CELLULAR",
                transport = TestEngine.TransportMode.CELLULAR,
                testMode = AnebTestMode.AI_REALTIME_SIMULATION,
                profileId = "ai_realtime_voice_repeatability_qualification",
                profileSha256 = REALTIME_PROFILE_SHA,
                runtimePlanSha256 = REALTIME_RUNTIME_SHA,
            ),
        )
        val network = RepeatabilityQualificationLaunch.verify(
            request(
                stageId = "Q2_CELLULAR",
                transport = TestEngine.TransportMode.CELLULAR,
                testMode = AnebTestMode.NETWORK_BASIC,
                profileId = "network_comprehensive_repeatability_qualification",
                profileSha256 = NETWORK_PROFILE_SHA,
                runtimePlanSha256 = NETWORK_RUNTIME_SHA,
            ),
        )

        assertEquals("repeatability_qualification", realtime.variant)
        assertEquals("repeatability_qualification", network.variant)
    }

    @Test
    fun `missing or mismatched qualification identity fails closed without quick fallback`() {
        assertRejected(
            "repeatability_qualification_requires_debug_autorun",
            request(debug = false),
        )
        assertRejected(
            "repeatability_qualification_stage_transport_mismatch",
            request(stageId = "Q2_CELLULAR", transport = TestEngine.TransportMode.WIFI),
        )
        assertRejected(
            "repeatability_qualification_policy_identity_mismatch",
            request(policySha256 = "0".repeat(64)),
        )
        assertRejected(
            "repeatability_qualification_profile_identity_mismatch",
            request(profileSha256 = "0".repeat(64)),
        )
    }

    private fun request(
        debug: Boolean = true,
        autorun: Boolean = true,
        stageId: String = "Q1_WIFI",
        transport: TestEngine.TransportMode = TestEngine.TransportMode.WIFI,
        testMode: AnebTestMode = AnebTestMode.TOKEN_SIMULATION,
        policySha256: String = POLICY_SHA,
        profileId: String = "token_multimodal_repeatability_qualification",
        profileSha256: String = TOKEN_PROFILE_SHA,
        runtimePlanSha256: String = TOKEN_RUNTIME_SHA,
    ) = RepeatabilityQualificationLaunchRequest(
        debug = debug,
        autorun = autorun,
        stageId = stageId,
        transport = transport,
        testMode = testMode,
        policyId = "aneb-repeatability-qualification-balanced-v1",
        policyVersion = "1.0.0",
        policySha256 = policySha256,
        profileId = profileId,
        profileVersion = "1.0.0",
        profileSha256 = profileSha256,
        runtimePlanSha256 = runtimePlanSha256,
    )

    private fun assertRejected(reason: String, request: RepeatabilityQualificationLaunchRequest) {
        val error = try {
            RepeatabilityQualificationLaunch.verify(request)
            fail("expected fail-closed qualification launch rejection")
            error("unreachable")
        } catch (error: IllegalArgumentException) {
            error
        }
        assertEquals(reason, error.message)
    }

    private companion object {
        const val POLICY_SHA = "505276dc9e72eb68454461bb355b63db6227069274646835020d89a6646fedfa"
        const val TOKEN_PROFILE_SHA = "eaeb0af8c1a38c88a8f341c120701580659625eb3b68b8d7960db2888a01ee7b"
        const val TOKEN_RUNTIME_SHA = "d8f31633e0c0d91a321bb1007f7cb0c30e84f855fa4e1e7b0a181e80879e7ea4"
        const val REALTIME_PROFILE_SHA = "ad86006f48bb06716c9d69d430d84f511c206ebd9114feffd0ca8679aeace75c"
        const val REALTIME_RUNTIME_SHA = "883b36003dbb84cb264c7742908c9f045f3fa7c2938db9a339566f6b32b70eda"
        const val NETWORK_PROFILE_SHA = "e39dcabd2276a19c193e0a6b0c3126af734ff7c8d2fba17c91d0d48019a0c375"
        const val NETWORK_RUNTIME_SHA = "f430fba09fd7453872690fd0d5cf9ad130637f87f347a6247c04ad069b2e4aab"
    }
}
