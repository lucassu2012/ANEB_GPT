package com.aneb.probe.ui

import com.aneb.probe.engine.TestEngine
import com.aneb.probe.engine.AnebTestMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeSettingsStoreTest {
    @Test
    fun `decode restores valid settings and trims server`() {
        val decoded = ProbeSettingsCodec.decode(
            serverUrl = "  http://10.0.2.2:8443/  ",
            mode = "FORENSIC",
            transport = "CELLULAR",
            driveTest = true,
            testMode = "NETWORK_BASIC",
        )

        assertEquals("http://10.0.2.2:8443/", decoded.serverUrl)
        assertEquals(TestEngine.Mode.FORENSIC, decoded.mode)
        assertEquals(AnebTestMode.NETWORK_BASIC, decoded.testMode)
        assertEquals(TestEngine.TransportMode.CELLULAR, decoded.transport)
        assertTrue(decoded.driveTest)
    }

    @Test
    fun `decode safely falls back after unknown or empty persisted values`() {
        val decoded = ProbeSettingsCodec.decode("   ", "FUTURE", "SATELLITE", false)

        assertEquals(ProbeSettings.DEFAULT_SERVER_URL, decoded.serverUrl)
        assertEquals(TestEngine.Mode.QUICK, decoded.mode)
        assertEquals(AnebTestMode.TOKEN_SIMULATION, decoded.testMode)
        assertEquals(TestEngine.TransportMode.AUTO, decoded.transport)
        assertFalse(decoded.driveTest)
    }

    @Test
    fun `legacy sni default migrates once to bare ip main channel`() {
        assertEquals(
            ProbeSettings.DEFAULT_SERVER_URL,
            migrateLegacyDefaultServerUrl(ProbeSettings.LEGACY_SNI_SERVER_URL + "/", migrationApplied = false),
        )
        assertEquals(
            ProbeSettings.LEGACY_SNI_SERVER_URL,
            migrateLegacyDefaultServerUrl(ProbeSettings.LEGACY_SNI_SERVER_URL, migrationApplied = true),
        )
        assertEquals(
            "https://node.example:8443",
            migrateLegacyDefaultServerUrl("https://node.example:8443", migrationApplied = false),
        )
    }

    @Test
    fun `legacy hidden mode migrates to public token simulation`() {
        val decoded = ProbeSettingsCodec.decode(null, null, null, false, "TOKEN_EXPERIENCE")
        assertEquals(AnebTestMode.TOKEN_SIMULATION, decoded.testMode)
    }

    @Test
    fun `decode restores token stress mode`() {
        val decoded = ProbeSettingsCodec.decode(null, "STRESS", null, false, "TOKEN_SIMULATION")

        assertEquals(TestEngine.Mode.STRESS, decoded.mode)
        assertEquals(AnebTestMode.TOKEN_SIMULATION, decoded.testMode)
    }

    @Test
    fun `manual launch restores saved values`() {
        val saved = ProbeSettings(
            serverUrl = "https://node.example:8443",
            testMode = AnebTestMode.NETWORK_BASIC,
            mode = TestEngine.Mode.FORENSIC,
            transport = TestEngine.TransportMode.WIFI,
            driveTest = true,
        )

        assertEquals(
            saved,
            resolveLaunchSettings(saved, ProbeLaunchOverrides(), autorun = false, hasFullRadioEvidence = true),
        )
    }

    @Test
    fun `autorun is deterministic and explicit overrides win`() {
        val saved = ProbeSettings(
            serverUrl = "https://stale.example",
            mode = TestEngine.Mode.FORENSIC,
            transport = TestEngine.TransportMode.WIFI,
            driveTest = true,
        )
        val resolved = resolveLaunchSettings(
            saved = saved,
            overrides = ProbeLaunchOverrides(
                serverUrl = "https://automation.example",
                testMode = AnebTestMode.NETWORK_BASIC,
                transport = TestEngine.TransportMode.CELLULAR,
            ),
            autorun = true,
            hasFullRadioEvidence = true,
        )

        assertEquals("https://automation.example", resolved.serverUrl)
        assertEquals(TestEngine.Mode.QUICK, resolved.mode)
        assertEquals(AnebTestMode.NETWORK_BASIC, resolved.testMode)
        assertEquals(TestEngine.TransportMode.CELLULAR, resolved.transport)
        assertFalse(resolved.driveTest)
    }

    @Test
    fun `drive test never restores without full permission evidence`() {
        val resolved = resolveLaunchSettings(
            saved = ProbeSettings(driveTest = true),
            overrides = ProbeLaunchOverrides(driveTest = true),
            autorun = false,
            hasFullRadioEvidence = false,
        )

        assertFalse(resolved.driveTest)
    }
}
