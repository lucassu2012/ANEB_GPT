package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class RepeatabilityQualificationEngineVariantTest {
    @Test
    fun `verified qualification selects the dedicated variant for every formal family`() {
        listOf(
            AnebTestMode.TOKEN_SIMULATION,
            AnebTestMode.AI_REALTIME_SIMULATION,
            AnebTestMode.NETWORK_BASIC,
        ).forEach { testMode ->
            assertEquals(
                "repeatability_qualification",
                RepeatabilityQualificationEngineVariant.resolve(
                    testMode = testMode,
                    mode = TestEngine.Mode.QUICK,
                    qualification = verified(testMode),
                ),
            )
        }
    }

    @Test
    fun `legacy launch keeps its family specific variants when qualification is absent`() {
        assertEquals(
            "stress",
            RepeatabilityQualificationEngineVariant.resolve(
                testMode = AnebTestMode.TOKEN_SIMULATION,
                mode = TestEngine.Mode.STRESS,
                qualification = null,
            ),
        )
        assertEquals(
            "recovery",
            RepeatabilityQualificationEngineVariant.resolve(
                testMode = AnebTestMode.AI_REALTIME_SIMULATION,
                mode = TestEngine.Mode.STRESS,
                qualification = null,
            ),
        )
        assertEquals(
            "gateway_recovery",
            RepeatabilityQualificationEngineVariant.resolve(
                testMode = AnebTestMode.NETWORK_BASIC,
                mode = TestEngine.Mode.GATEWAY_RECOVERY,
                qualification = null,
            ),
        )
    }

    @Test
    fun `verified qualification cannot cross a business family`() {
        val error = try {
            RepeatabilityQualificationEngineVariant.resolve(
                testMode = AnebTestMode.NETWORK_BASIC,
                mode = TestEngine.Mode.QUICK,
                qualification = verified(AnebTestMode.TOKEN_SIMULATION),
            )
            fail("expected family mismatch rejection")
            error("unreachable")
        } catch (error: IllegalArgumentException) {
            error
        }
        assertEquals("repeatability_qualification_test_mode_mismatch", error.message)
    }

    private fun verified(testMode: AnebTestMode): VerifiedRepeatabilityQualificationLaunch {
        val profile = when (testMode) {
            AnebTestMode.TOKEN_SIMULATION -> arrayOf(
                "token_multimodal_repeatability_qualification",
                "eaeb0af8c1a38c88a8f341c120701580659625eb3b68b8d7960db2888a01ee7b",
                "d8f31633e0c0d91a321bb1007f7cb0c30e84f855fa4e1e7b0a181e80879e7ea4",
            )
            AnebTestMode.AI_REALTIME_SIMULATION -> arrayOf(
                "ai_realtime_voice_repeatability_qualification",
                "ad86006f48bb06716c9d69d430d84f511c206ebd9114feffd0ca8679aeace75c",
                "883b36003dbb84cb264c7742908c9f045f3fa7c2938db9a339566f6b32b70eda",
            )
            AnebTestMode.NETWORK_BASIC -> arrayOf(
                "network_comprehensive_repeatability_qualification",
                "e39dcabd2276a19c193e0a6b0c3126af734ff7c8d2fba17c91d0d48019a0c375",
                "f430fba09fd7453872690fd0d5cf9ad130637f87f347a6247c04ad069b2e4aab",
            )
            AnebTestMode.TOKEN_EXPERIENCE -> error("legacy family has no qualification profile")
        }
        return RepeatabilityQualificationLaunch.verify(
            RepeatabilityQualificationLaunchRequest(
                debug = true,
                autorun = true,
                stageId = "Q1_WIFI",
                transport = TestEngine.TransportMode.WIFI,
                testMode = testMode,
                policyId = "aneb-repeatability-qualification-balanced-v1",
                policyVersion = "1.0.0",
                policySha256 = "505276dc9e72eb68454461bb355b63db6227069274646835020d89a6646fedfa",
                profileId = profile[0],
                profileVersion = "1.0.0",
                profileSha256 = profile[1],
                runtimePlanSha256 = profile[2],
            ),
        )
    }
}
