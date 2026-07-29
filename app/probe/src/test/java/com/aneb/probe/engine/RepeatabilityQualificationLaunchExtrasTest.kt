package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class RepeatabilityQualificationLaunchExtrasTest {
    @Test
    fun `canonical qualification wire round trips through the exact one-shot extras`() {
        val wire = RepeatabilityQualificationLaunchWireData(
            requested = true,
            stageId = "Q2_CELLULAR",
            policyId = "policy-id",
            policyVersion = "1.0.0",
            policySha256 = "a".repeat(64),
            profileId = "profile-id",
            profileVersion = "2.0.0",
            profileSha256 = "b".repeat(64),
            runtimePlanSha256 = "c".repeat(64),
        )
        val extras = linkedMapOf<String, Any?>()
        RepeatabilityQualificationLaunchExtras.write(
            wire = wire,
            putBoolean = { key, value -> extras[key] = value },
            putString = { key, value -> extras[key] = value },
        )
        assertEquals(EXACT_KEYS, extras.keys.toList())

        val removed = mutableListOf<String>()
        val consumed = RepeatabilityQualificationLaunchExtras.readAndRemove(
            getBoolean = { key, default -> extras[key] as? Boolean ?: default },
            getString = { key -> extras[key] as? String },
            remove = { key -> removed += key },
        )

        assertEquals(wire, consumed)
        assertEquals(EXACT_KEYS, removed)
    }

    @Test
    fun `missing extras decode as legacy launch and are still removed once`() {
        val removed = mutableListOf<String>()
        val consumed = RepeatabilityQualificationLaunchExtras.readAndRemove(
            getBoolean = { _, default -> default },
            getString = { null },
            remove = { key -> removed += key },
        )

        assertEquals(RepeatabilityQualificationLaunchWireData(requested = false), consumed)
        assertEquals(EXACT_KEYS, removed)
    }

    @Test
    fun `activity accepts the wire only on its first debug creation and always removes it`() {
        val extras = linkedMapOf<String, Any?>(
            "qualification_requested" to true,
            "qualification_stage_id" to "Q1_WIFI",
        )
        val removed = mutableListOf<String>()

        val consumed = RepeatabilityQualificationLaunchExtras.readAndRemoveForActivity(
            isFirstCreation = true,
            enabled = true,
            getBoolean = { key, default -> extras[key] as? Boolean ?: default },
            getString = { key -> extras[key] as? String },
            remove = { key -> removed += key },
        )

        assertEquals(true, consumed.requested)
        assertEquals("Q1_WIFI", consumed.stageId)
        assertEquals(EXACT_KEYS, removed)
    }

    @Test
    fun `activity discards and removes qualification metadata outside the first debug creation`() {
        listOf(false to true, true to false).forEach { (isFirstCreation, enabled) ->
            val extras = linkedMapOf<String, Any?>(
                "qualification_requested" to true,
                "qualification_stage_id" to "Q1_WIFI",
            )
            val removed = mutableListOf<String>()

            val consumed = RepeatabilityQualificationLaunchExtras.readAndRemoveForActivity(
                isFirstCreation = isFirstCreation,
                enabled = enabled,
                getBoolean = { key, default -> extras[key] as? Boolean ?: default },
                getString = { key -> extras[key] as? String },
                remove = { key -> removed += key },
            )

            assertEquals(RepeatabilityQualificationLaunchWireData(requested = false), consumed)
            assertEquals(EXACT_KEYS, removed)
        }
    }

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
