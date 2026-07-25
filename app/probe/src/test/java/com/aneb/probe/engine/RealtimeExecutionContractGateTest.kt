package com.aneb.probe.engine

import com.aneb.probe.net.AnebClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RealtimeExecutionContractGateTest {
    @Test
    fun `compatible receipt authorizes realtime traffic after control plane verification`() = runBlocking {
        val transport = FakeCapabilityTransport(validReceipt())

        val authorization = RealtimeExecutionContractGate.authorize(
            serverBase = "https://aneb.test",
            profile = requiredProfile(),
            profileCanonicalSha256 = PROFILE_SHA,
            transport = transport,
        )

        assertEquals(ExecutionAuthorization.VALIDATED_RECEIPT, authorization)
        assertEquals(1, transport.serverInfoRequests)
    }

    private class FakeCapabilityTransport(
        private val receipt: String,
    ) : ExecutionCapabilityTransport {
        var serverInfoRequests = 0

        override suspend fun fetchServerInfo(url: String): AnebClient.HttpTextResult {
            serverInfoRequests++
            return AnebClient.HttpTextResult(200, receipt, null)
        }
    }

    private fun requiredProfile() = ScenarioProfile(
        profileId = "ai_realtime_voice_quick",
        version = "1.1.1",
        executionTarget = "aneb_probe_simulator",
        claimScope = "application_end_to_end_to_probe_node",
        contractVersion = ScenarioProfile.CONTRACT_V2,
        modeId = ScenarioProfile.MODE_AI_REALTIME_SIMULATION,
        executionRequirements = ProfileExecutionRequirements(
            contractId = "aneb-execution-requirements",
            contractVersion = "1.0.0",
            clientEngine = ProfileExecutionContractRange(
                "aneb-realtime-simulation-engine", "1.0.0", "2.0.0",
            ),
            serverCapabilityReceipt = ProfileExecutionContractRange(
                "aneb-server-capability-receipt", "1.0.0", "2.0.0",
            ),
            requiredPrimitives = listOf(
                ProfileExecutionPrimitive("realtime_sim", "aneb-realtime-session-v1"),
            ),
        ),
    )

    private fun validReceipt(): String = """
        {
          "version":"aneb-server/test",
          "execution_capabilities":{
            "contract_id":"aneb-server-capability-receipt",
            "contract_version":"1.0.0",
            "primitives":[
              {"primitive_id":"realtime_sim","wire_contract_id":"aneb-realtime-session-v1"}
            ],
            "validated_profiles":[
              {
                "profile_id":"ai_realtime_voice_quick",
                "profile_version":"1.1.1",
                "profile_sha256":"$PROFILE_SHA"
              }
            ]
          }
        }
    """.trimIndent()

    private companion object {
        const val PROFILE_SHA =
            "sha256:701c43cb19644e732c59faa6141b5b8bbc069e6c2ef006c410ee2bc0b51b30f7"
    }
}
