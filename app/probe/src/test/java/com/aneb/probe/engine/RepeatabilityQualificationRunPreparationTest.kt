package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class RepeatabilityQualificationRunPreparationTest {
    @Test
    fun `raw qualification is verified before the dedicated engine variant is selected`() {
        val prepared = RepeatabilityQualificationRunPreparation.verify(
            debug = true,
            autorun = true,
            testMode = AnebTestMode.TOKEN_SIMULATION,
            mode = TestEngine.Mode.QUICK,
            transport = TestEngine.TransportMode.CELLULAR,
            wire = validWire(stageId = "Q2_CELLULAR"),
        )

        assertEquals("repeatability_qualification", prepared.variant)
        assertEquals("Q2_CELLULAR", prepared.qualification?.stageId)
        assertEquals(TestEngine.TransportMode.CELLULAR, prepared.qualification?.transport)
    }

    @Test
    fun `legacy launch remains unqualified and keeps the existing engine variant`() {
        val prepared = RepeatabilityQualificationRunPreparation.verify(
            debug = true,
            autorun = false,
            testMode = AnebTestMode.AI_REALTIME_SIMULATION,
            mode = TestEngine.Mode.STRESS,
            transport = TestEngine.TransportMode.AUTO,
            wire = RepeatabilityQualificationLaunchWireData(requested = false),
        )

        assertNull(prepared.qualification)
        assertEquals("recovery", prepared.variant)
    }

    @Test
    fun `qualification metadata without its request marker fails before variant selection`() {
        val error = try {
            RepeatabilityQualificationRunPreparation.verify(
                debug = true,
                autorun = true,
                testMode = AnebTestMode.TOKEN_SIMULATION,
                mode = TestEngine.Mode.QUICK,
                transport = TestEngine.TransportMode.WIFI,
                wire = RepeatabilityQualificationLaunchWireData(
                    requested = false,
                    stageId = "Q1_WIFI",
                ),
            )
            fail("expected metadata rejection")
            error("unreachable")
        } catch (error: IllegalArgumentException) {
            error
        }

        assertEquals("repeatability_qualification_metadata_without_request", error.message)
    }

    private fun validWire(stageId: String) = RepeatabilityQualificationLaunchWireData(
        requested = true,
        stageId = stageId,
        policyId = "aneb-repeatability-qualification-balanced-v1",
        policyVersion = "1.0.0",
        policySha256 = "505276dc9e72eb68454461bb355b63db6227069274646835020d89a6646fedfa",
        profileId = "token_multimodal_repeatability_qualification",
        profileVersion = "1.0.0",
        profileSha256 = "eaeb0af8c1a38c88a8f341c120701580659625eb3b68b8d7960db2888a01ee7b",
        runtimePlanSha256 = "d8f31633e0c0d91a321bb1007f7cb0c30e84f855fa4e1e7b0a181e80879e7ea4",
    )
}
