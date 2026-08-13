package com.aneb.probe.engine

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.util.ReflectionHelpers

class ServiceRetainedStateDeletionPortTest {
    @Test
    fun `clears primary and special service retained state to exact defaults`() {
        mutableStatic<ProbeRunSession>(ProbeRunService::class.java, "_session").value =
            ProbeRunSession.Failed(false, "private")
        mutableStatic<List<String>>(ProbeRunService::class.java, "_logs").value =
            listOf("private")
        mutableStatic<LiveTelemetry>(ProbeRunService::class.java, "_telemetry").value =
            LiveTelemetry(phase = "private", tokensReceived = 7)
        mutableStatic<BasicSpeedTelemetry>(ProbeRunService::class.java, "_basicTelemetry").value =
            BasicSpeedTelemetry(phase = BasicSpeedPhase.COMPLETE, progress = 1.0)
        mutableStatic<TokenSimulationTelemetry>(
            ProbeRunService::class.java,
            "_tokenSimulationTelemetry",
        ).value = TokenSimulationTelemetry(phase = TokenSimulationPhase.COMPLETE, progress = 1.0)
        mutableStatic<RealtimeSimulationTelemetry>(
            ProbeRunService::class.java,
            "_realtimeSimulationTelemetry",
        ).value = RealtimeSimulationTelemetry(phase = RealtimeSimulationPhase.COMPLETE, progress = 1.0)
        mutableStatic<SpecialRunSession>(ProbeSpecialRunService::class.java, "_session").value =
            SpecialRunSession.Failed(SpecialRunKind.CONTINUITY, "private")
        mutableStatic<List<String>>(ProbeSpecialRunService::class.java, "_logs").value =
            listOf("private")

        val result = ServiceRetainedStateDeletionPort().clearOwnedReferences()

        assertEquals(ServiceRetainedStateDeletionResult.Cleared, result)
        assertEquals(ProbeRunSession.Idle, ProbeRunService.session.value)
        assertEquals(emptyList<String>(), ProbeRunService.logs.value)
        assertEquals(LiveTelemetry(), ProbeRunService.telemetry.value)
        assertEquals(BasicSpeedTelemetry(), ProbeRunService.basicTelemetry.value)
        assertNull(ProbeRunService.basicResult.value)
        assertEquals(TokenSimulationTelemetry(), ProbeRunService.tokenSimulationTelemetry.value)
        assertNull(ProbeRunService.tokenSimulationResult.value)
        assertEquals(RealtimeSimulationTelemetry(), ProbeRunService.realtimeSimulationTelemetry.value)
        assertNull(ProbeRunService.realtimeSimulationResult.value)
        assertEquals(SpecialRunSession.Idle, ProbeSpecialRunService.session.value)
        assertEquals(emptyList<String>(), ProbeSpecialRunService.logs.value)
    }

    @Test
    fun `primary runtime port clears only primary retained state`() {
        val specialSession = SpecialRunSession.Failed(SpecialRunKind.PROTOCOL_AB, "special-private")
        val specialLogs = listOf("special-private")
        try {
            mutableStatic<ProbeRunSession>(ProbeRunService::class.java, "_session").value =
                ProbeRunSession.Failed(false, "primary-private")
            mutableStatic<List<String>>(ProbeRunService::class.java, "_logs").value =
                listOf("primary-private")
            mutableStatic<SpecialRunSession>(ProbeSpecialRunService::class.java, "_session").value =
                specialSession
            mutableStatic<List<String>>(ProbeSpecialRunService::class.java, "_logs").value = specialLogs

            val result = PrimaryServiceRetainedStateDeletionPort().clearOwnedReferences()

            assertEquals(ServiceRetainedStateDeletionResult.Cleared, result)
            assertTrue(ProbeRunService.retainedStateIsPrivacyDefault())
            assertEquals(specialSession, ProbeSpecialRunService.session.value)
            assertEquals(specialLogs, ProbeSpecialRunService.logs.value)
        } finally {
            ProbeRunService.clearRetainedStateForPrivacyDeletion()
            ProbeSpecialRunService.clearRetainedStateForPrivacyDeletion()
        }
    }

    @Test
    fun `special runtime port clears only special retained state`() {
        val primarySession = ProbeRunSession.Failed(false, "primary-private")
        val primaryLogs = listOf("primary-private")
        try {
            mutableStatic<ProbeRunSession>(ProbeRunService::class.java, "_session").value =
                primarySession
            mutableStatic<List<String>>(ProbeRunService::class.java, "_logs").value = primaryLogs
            mutableStatic<SpecialRunSession>(ProbeSpecialRunService::class.java, "_session").value =
                SpecialRunSession.Failed(SpecialRunKind.CONTINUITY, "special-private")
            mutableStatic<List<String>>(ProbeSpecialRunService::class.java, "_logs").value =
                listOf("special-private")

            val result = SpecialServiceRetainedStateDeletionPort().clearOwnedReferences()

            assertEquals(ServiceRetainedStateDeletionResult.Cleared, result)
            assertTrue(ProbeSpecialRunService.retainedStateIsPrivacyDefault())
            assertEquals(primarySession, ProbeRunService.session.value)
            assertEquals(primaryLogs, ProbeRunService.logs.value)
        } finally {
            ProbeRunService.clearRetainedStateForPrivacyDeletion()
            ProbeSpecialRunService.clearRetainedStateForPrivacyDeletion()
        }
    }

    private fun <T> mutableStatic(owner: Class<*>, name: String): MutableStateFlow<T> =
        ReflectionHelpers.getStaticField(owner, name)
}
