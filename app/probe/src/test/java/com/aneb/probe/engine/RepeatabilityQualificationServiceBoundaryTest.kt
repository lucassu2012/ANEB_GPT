package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepeatabilityQualificationServiceBoundaryTest {
    @Test
    fun `legacy token experience remains unqualified at the service boundary`() {
        val config = ProbeRunService.Config(
            serverBase = "https://example.invalid",
            testMode = AnebTestMode.TOKEN_EXPERIENCE,
            mode = TestEngine.Mode.QUICK,
            transport = TestEngine.TransportMode.AUTO,
            inject = null,
            driveTest = false,
        )

        assertNull(
            RepeatabilityQualificationServiceIntentBoundary.prepare(
                debug = true,
                autorun = false,
                config = config,
            ),
        )
    }

    @Test
    fun `service intent round trip is removed and prepared before engine selection`() {
        val extras = linkedMapOf<String, Any?>()
        val wire = validNetworkWire()
        RepeatabilityQualificationServiceIntentBoundary.write(
            wire = wire,
            putBoolean = { key, value -> extras[key] = value },
            putString = { key, value -> extras[key] = value },
        )
        val removed = mutableListOf<String>()
        val decoded = RepeatabilityQualificationServiceIntentBoundary.readAndRemove(
            getBoolean = { key, default -> extras[key] as? Boolean ?: default },
            getString = { key -> extras[key] as? String },
            remove = { key -> removed += key },
        )
        val config = ProbeRunService.Config(
            serverBase = "https://example.invalid",
            testMode = AnebTestMode.NETWORK_BASIC,
            mode = TestEngine.Mode.QUICK,
            transport = TestEngine.TransportMode.WIFI,
            inject = null,
            driveTest = false,
            qualificationWire = decoded,
        )

        val prepared = RepeatabilityQualificationServiceIntentBoundary.prepare(
            debug = true,
            autorun = true,
            config = config,
        )

        assertEquals("repeatability_qualification", prepared?.variant)
        assertEquals(wire, decoded)
        assertEquals(EXACT_KEYS, removed)
    }

    @Test
    fun `activity hands the raw qualification wire to at most one service launch`() {
        val wire = RepeatabilityQualificationLaunchWireData(
            requested = true,
            stageId = "Q1_WIFI",
        )
        val handoff = RepeatabilityQualificationActivityHandoff()

        handoff.replace(wire)

        assertEquals(wire, handoff.take())
        assertEquals(RepeatabilityQualificationLaunchWireData(requested = false), handoff.take())
    }

    @Test
    fun `service config carries the raw qualification wire without interpreting it`() {
        val wire = RepeatabilityQualificationLaunchWireData(
            requested = true,
            stageId = "Q1_WIFI",
        )

        val config = ProbeRunService.Config(
            serverBase = "https://example.invalid",
            testMode = AnebTestMode.NETWORK_BASIC,
            mode = TestEngine.Mode.QUICK,
            transport = TestEngine.TransportMode.WIFI,
            inject = null,
            driveTest = false,
            qualificationWire = wire,
        )

        assertEquals(wire, config.qualificationWire)
    }

    private fun validNetworkWire() = RepeatabilityQualificationLaunchWireData(
        requested = true,
        stageId = "Q1_WIFI",
        policyId = "aneb-repeatability-qualification-balanced-v1",
        policyVersion = "1.0.0",
        policySha256 = "505276dc9e72eb68454461bb355b63db6227069274646835020d89a6646fedfa",
        profileId = "network_comprehensive_repeatability_qualification",
        profileVersion = "1.0.0",
        profileSha256 = "e39dcabd2276a19c193e0a6b0c3126af734ff7c8d2fba17c91d0d48019a0c375",
        runtimePlanSha256 = "f430fba09fd7453872690fd0d5cf9ad130637f87f347a6247c04ad069b2e4aab",
    )

    private companion object {
        val EXACT_KEYS = listOf(
            "qualification_requested",
            "qualification_stage_id",
            "qualification_policy_id",
            "qualification_policy_version",
            "qualification_policy_sha256",
            "qualification_profile_id",
            "qualification_profile_version",
            "qualification_profile_sha256",
            "qualification_runtime_plan_sha256",
        )
    }
}
