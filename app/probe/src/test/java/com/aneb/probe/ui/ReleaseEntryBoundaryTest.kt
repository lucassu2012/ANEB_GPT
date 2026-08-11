package com.aneb.probe.ui

import com.aneb.probe.engine.AnebTestMode
import com.aneb.probe.engine.TestEngine
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseEntryBoundaryTest {
    @Test
    fun `release launch scrubs every external extra and restores only manual settings`() {
        val saved = ProbeSettings(
            serverUrl = "https://manual.example:8443",
            testMode = AnebTestMode.NETWORK_BASIC,
            mode = TestEngine.Mode.FORENSIC,
            transport = TestEngine.TransportMode.WIFI,
            driveTest = true,
        )
        val maliciousBundle = MaliciousExternalBundle()

        val result = resolvePublicReleaseLaunch(
            saved = saved,
            hasFullRadioEvidence = true,
            scrubExternalLaunch = maliciousBundle::scrubAll,
        )

        assertEquals(saved, result.settings)
        assertFalse(result.startRequested)
        assertEquals(1, maliciousBundle.scrubCalls)
        assertTrue(maliciousBundle.extras.isEmpty())
        assertClosedResultSurface(result)
    }

    @Test
    fun `release launch disables drive test without full radio evidence`() {
        val saved = ProbeSettings(
            serverUrl = "https://manual.example:8443",
            testMode = AnebTestMode.TOKEN_SIMULATION,
            mode = TestEngine.Mode.STRESS,
            transport = TestEngine.TransportMode.CELLULAR,
            driveTest = true,
        )
        val maliciousBundle = MaliciousExternalBundle()

        val result = resolvePublicReleaseLaunch(
            saved = saved,
            hasFullRadioEvidence = false,
            scrubExternalLaunch = maliciousBundle::scrubAll,
        )

        assertEquals(saved.copy(driveTest = false), result.settings)
        assertFalse(result.startRequested)
        assertEquals(1, maliciousBundle.scrubCalls)
        assertTrue(maliciousBundle.extras.isEmpty())
    }

    @Test
    fun `recreation and new intent remain scrubbed manual no-start launches`() {
        val saved = ProbeSettings(
            serverUrl = "https://persisted.example:8443",
            testMode = AnebTestMode.AI_REALTIME_SIMULATION,
            mode = TestEngine.Mode.QUICK,
            transport = TestEngine.TransportMode.AUTO,
            driveTest = false,
        )
        val recreationBundle = MaliciousExternalBundle()
        val newIntentBundle = MaliciousExternalBundle()

        val recreation = resolvePublicReleaseLaunch(
            saved = saved,
            hasFullRadioEvidence = true,
            scrubExternalLaunch = recreationBundle::scrubAll,
        )
        val newIntent = resolvePublicReleaseLaunch(
            saved = saved,
            hasFullRadioEvidence = true,
            scrubExternalLaunch = newIntentBundle::scrubAll,
        )

        assertEquals(saved, recreation.settings)
        assertEquals(saved, newIntent.settings)
        assertFalse(recreation.startRequested)
        assertFalse(newIntent.startRequested)
        assertEquals(1, recreationBundle.scrubCalls)
        assertEquals(1, newIntentBundle.scrubCalls)
        assertTrue(recreationBundle.extras.isEmpty())
        assertTrue(newIntentBundle.extras.isEmpty())
    }

    private fun assertClosedResultSurface(result: PublicReleaseLaunch) {
        val exposedFields = result.javaClass.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()
        assertEquals(setOf("settings", "startRequested"), exposedFields)
        val rendered = result.toString()
        MaliciousExternalBundle.secretValues.forEach { secret ->
            assertFalse("closed result exposed external value", rendered.contains(secret))
        }
    }

    /** Test double for the Android Bundle system boundary; production owns the actual scrub. */
    private class MaliciousExternalBundle {
        val extras = linkedMapOf<String, Any?>(
            "server" to secretValues[0],
            "autorun" to true,
            "mode" to "gateway_recovery",
            "test_mode" to "network_basic",
            "transport" to "cellular",
            "drive_test" to true,
            "c_tokens" to 99_999,
            "c3_idle" to "1,2,3",
            "ab_pairs" to 99_999,
            "ab_netlog" to true,
            "inject" to "truncate:1",
            "gateway_base" to secretValues[1],
            "gateway_token" to secretValues[2],
            "gateway_credential_handle" to secretValues[3],
            "qualification_requested" to true,
            "qualification_stage_id" to secretValues[4],
            "qualification_policy_id" to "attacker-policy",
            "qualification_policy_version" to "999",
            "qualification_policy_sha256" to "A".repeat(64),
            "qualification_profile_id" to "attacker-profile",
            "qualification_profile_version" to "999",
            "qualification_profile_sha256" to "B".repeat(64),
            "qualification_runtime_plan_sha256" to "C".repeat(64),
            "unknown_external_secret" to secretValues[5],
        )
        var scrubCalls: Int = 0
            private set

        fun scrubAll() {
            scrubCalls += 1
            extras.clear()
        }

        companion object {
            val secretValues = listOf(
                "https://attacker.example:9443",
                "https://gateway.attacker.example",
                "secret-gateway-token",
                "secret-credential-handle",
                "secret-stage-id",
                "secret-unknown-extra",
            )
        }
    }
}
