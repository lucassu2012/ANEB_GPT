package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class RepeatabilityQualificationLaunchWireTest {
    @Test
    fun `legacy launch without qualification marker or metadata remains unchanged`() {
        assertNull(
            RepeatabilityQualificationLaunchWire.verifyIfRequested(
                debug = true,
                autorun = true,
                testMode = AnebTestMode.TOKEN_SIMULATION,
                transport = TestEngine.TransportMode.WIFI,
                wire = RepeatabilityQualificationLaunchWireData(requested = false),
            ),
        )
    }

    @Test
    fun `qualification metadata without explicit request marker fails closed`() {
        assertRejected(
            "repeatability_qualification_metadata_without_request",
            exactWire(requested = false),
        )
    }

    @Test
    fun `requested wire requires all exact fields before producing verified variant`() {
        val verified = RepeatabilityQualificationLaunchWire.verifyIfRequested(
            debug = true,
            autorun = true,
            testMode = AnebTestMode.TOKEN_SIMULATION,
            transport = TestEngine.TransportMode.WIFI,
            wire = exactWire(),
        )
        assertEquals("repeatability_qualification", verified?.variant)

        assertRejected(
            "repeatability_qualification_profile_identity_mismatch",
            exactWire(runtimePlanSha256 = null),
        )
    }

    private fun assertRejected(reason: String, wire: RepeatabilityQualificationLaunchWireData) {
        val error = try {
            RepeatabilityQualificationLaunchWire.verifyIfRequested(
                debug = true,
                autorun = true,
                testMode = AnebTestMode.TOKEN_SIMULATION,
                transport = TestEngine.TransportMode.WIFI,
                wire = wire,
            )
            fail("expected fail-closed wire rejection")
            error("unreachable")
        } catch (error: IllegalArgumentException) {
            error
        }
        assertEquals(reason, error.message)
    }

    private fun exactWire(
        requested: Boolean = true,
        runtimePlanSha256: String? = "d8f31633e0c0d91a321bb1007f7cb0c30e84f855fa4e1e7b0a181e80879e7ea4",
    ) = RepeatabilityQualificationLaunchWireData(
        requested = requested,
        stageId = "Q1_WIFI",
        policyId = "aneb-repeatability-qualification-balanced-v1",
        policyVersion = "1.0.0",
        policySha256 = "505276dc9e72eb68454461bb355b63db6227069274646835020d89a6646fedfa",
        profileId = "token_multimodal_repeatability_qualification",
        profileVersion = "1.0.0",
        profileSha256 = "eaeb0af8c1a38c88a8f341c120701580659625eb3b68b8d7960db2888a01ee7b",
        runtimePlanSha256 = runtimePlanSha256,
    )
}
