package com.aneb.probe.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCapabilityTest {
    private fun completeProfile(mode: String, phases: List<ProfilePhase>) = ScenarioProfile(
        profileId = "test",
        version = "1.0.0",
        modeId = mode,
        description = "测试业务",
        presentation = ProfilePresentation(
            liveMetricId = "rate",
            liveMetricLabel = "实时速率",
            liveMetricUnit = "Mbps",
            metricIds = listOf("rate"),
            conclusionPolicyId = "policy-v1",
        ),
        phases = phases,
    )

    @Test
    fun `known basic profile is executable`() {
        val result = ProfileCapability.assess(
            completeProfile(
                ScenarioProfile.MODE_NETWORK_BASIC,
                listOf(
                    ProfilePhase(ProfilePhase.TYPE_CLOCK_SYNC),
                    ProfilePhase(ProfilePhase.TYPE_DOWNLOAD_THROUGHPUT),
                    ProfilePhase(ProfilePhase.TYPE_UPLOAD_THROUGHPUT),
                ),
            ),
        )
        assertTrue(result.executable)
        assertTrue(result.unsupportedPhaseTypes.isEmpty())
    }

    @Test
    fun `unknown phase requires an engine plugin`() {
        val result = ProfileCapability.assess(
            completeProfile(
                ScenarioProfile.MODE_TOKEN_EXPERIENCE,
                listOf(ProfilePhase("future_transport_probe")),
            ),
        )
        assertFalse(result.executable)
        assertTrue("future_transport_probe" in result.unsupportedPhaseTypes)
    }

    @Test
    fun `incomplete presentation is not executable`() {
        val result = ProfileCapability.assess(
            ScenarioProfile(
                profileId = "incomplete",
                version = "1.0.0",
                description = "测试业务",
                phases = listOf(ProfilePhase(ProfilePhase.TYPE_TOKEN_STREAM)),
            ),
        )
        assertFalse(result.executable)
        assertTrue(result.contractIssues.isNotEmpty())
    }
}
