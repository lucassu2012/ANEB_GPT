package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileGoldenContractTest {
    @Test
    fun `shared token profile golden parses and remains fail closed before runtime wiring`() {
        val resource = checkNotNull(
            javaClass.classLoader?.getResource(
                "profile-v2/golden/token_multimodal_standard.seed-20260716.json",
            ),
        ) { "shared Profile v2 golden is missing from test resources" }
        val profile = ProfileParser.parseSingle(resource.readText())

        assertEquals(ScenarioProfile.CONTRACT_V2, profile.contractVersion)
        assertEquals(ScenarioProfile.MODE_TOKEN_SIMULATION, profile.modeId)
        assertEquals("aneb_probe_simulator", profile.executionTarget)
        assertEquals("application_end_to_end_to_probe_node", profile.claimScope)
        assertEquals("hypothesis", profile.business.calibrationStatus)
        assertEquals(
            "sha256:ee638c9c755d49d8af51074ea57333ca51223559af99b9ca600146af834b503c",
            profile.business.behaviorModelHash,
        )
        assertEquals(26, profile.measurements.size)
        val artifactTarget = profile.measurements
            .single { it.metricId == "TOK-B12" }
            .qualityTarget
        assertEquals(
            "artifact-deadline-v1",
            artifactTarget?.policyId,
        )
        assertNull(artifactTarget?.value)
        assertTrue(artifactTarget?.values?.isEmpty() == true)
        assertEquals("pcg32-v1", profile.trace?.prng)
        assertEquals(20260716L, profile.trace?.seed)

        val assessment = ProfileCapability.assess(profile)
        assertFalse("behavior_trace runtime is not wired yet", assessment.executable)
        assertEquals(setOf(ProfilePhase.TYPE_BEHAVIOR_TRACE), assessment.unsupportedPhaseTypes)
        assertTrue(
            "shared golden must satisfy Kotlin semantic checks: ${assessment.contractIssues}",
            assessment.contractIssues.isEmpty(),
        )
    }
}
