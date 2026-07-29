package com.aneb.probe.engine

import com.aneb.probe.net.AnebClient
import com.aneb.probe.net.NetworkUdpProbeResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkExecutionContractGateTest {
    @Test
    fun `compatible receipt authorizes network traffic after control plane verification`() = runBlocking {
        val transport = FakeNetworkTransport(validReceipt())

        val traffic = NetworkExecutionContractGate.authorize(
            serverBase = "https://aneb.test",
            profile = requiredProfile(),
            profileCanonicalSha256 = PROFILE_SHA,
            transport = transport,
        )

        assertEquals(NetworkExecutionAuthorization.VALIDATED_RECEIPT, traffic.authorization)
        assertEquals(1, transport.serverInfoRequests)
        assertEquals(0, transport.businessRequests)
    }

    @Test
    fun `repeatability qualification requires and accepts its exact capability receipt`() = runBlocking {
        val profileId = "network_comprehensive_repeatability_qualification"
        val version = "1.0.0"
        val transport = FakeNetworkTransport(validReceipt(profileId, version))

        val traffic = NetworkExecutionContractGate.authorize(
            serverBase = "https://aneb.test",
            profile = requiredProfile(profileId, version),
            profileCanonicalSha256 = PROFILE_SHA,
            transport = transport,
        )

        assertEquals(NetworkExecutionAuthorization.VALIDATED_RECEIPT, traffic.authorization)
        assertEquals(1, transport.serverInfoRequests)
        assertEquals(0, transport.businessRequests)
    }

    @Test
    fun `missing receipt rejects before any network business request`() = runBlocking {
        val transport = FakeNetworkTransport("""{"version":"aneb-server/test"}""")

        val error = runCatching {
            NetworkExecutionContractGate.authorize(
                serverBase = "https://aneb.test",
                profile = requiredProfile(),
                profileCanonicalSha256 = PROFILE_SHA,
                transport = transport,
            )
        }.exceptionOrNull() as NetworkExecutionContractException

        assertEquals("receipt_missing", error.reasonCode)
        assertEquals(1, transport.serverInfoRequests)
        assertEquals(0, transport.businessRequests)
    }

    @Test
    fun `legacy network profile keeps the v1 UDP wire without capability traffic`() = runBlocking {
        val transport = FakeNetworkTransport(validReceipt())
        val profile = requiredProfile().copy(
            profileId = "network_comprehensive_standard",
            version = "1.1.0",
            executionRequirements = null,
        )

        val traffic = NetworkExecutionContractGate.authorize(
            serverBase = "https://aneb.test",
            profile = profile,
            profileCanonicalSha256 = PROFILE_SHA,
            transport = transport,
        )
        traffic.udpEcho("https://aneb.test", packets = 1, packetBytes = 256, ratePerSecond = 1)

        assertEquals(NetworkExecutionAuthorization.LEGACY_PROFILE, traffic.authorization)
        assertEquals(0, transport.serverInfoRequests)
        assertEquals("aneb-udp-echo-v1", transport.lastUdpWireContractId)
        assertTrue(transport.businessRequests == 1)
    }

    private class FakeNetworkTransport(
        private val receipt: String,
    ) : NetworkExecutionTransport {
        var serverInfoRequests = 0
        var businessRequests = 0
        var lastUdpWireContractId: String? = null

        override fun evictConnections() = Unit

        override suspend fun fetchServerInfo(url: String): AnebClient.HttpTextResult {
            serverInfoRequests++
            return AnebClient.HttpTextResult(200, receipt, null)
        }

        override suspend fun echo(url: String, callTimeoutMs: Long?): AnebClient.EchoResult =
            noBusiness()

        override suspend fun downloadThroughput(
            url: String,
            onBytes: (Int, Long) -> Unit,
        ): AnebClient.TransferResult = noBusiness()

        override suspend fun uploadThroughput(
            url: String,
            totalBytes: Long,
            chunkBytes: Int,
            onBytes: (Int, Long) -> Unit,
        ): AnebClient.TransferResult = noBusiness()

        override suspend fun udpEcho(
            serverBase: String,
            packets: Int,
            packetBytes: Int,
            ratePerSecond: Int,
            wireContractId: String,
        ): NetworkUdpProbeResult {
            businessRequests++
            lastUdpWireContractId = wireContractId
            return NetworkUdpProbeResult(1, listOf(0), listOf(1.0), null)
        }

        override suspend fun triggerSyntheticOutage(url: String): AnebClient.SyntheticOutageTriggerResult =
            noBusiness()

        private fun noBusiness(): Nothing {
            businessRequests++
            error("business traffic must not be used by authorization")
        }
    }

    private fun requiredProfile(
        profileId: String = "network_comprehensive_quick",
        version: String = "1.2.0",
    ) = ScenarioProfile(
        profileId = profileId,
        version = version,
        executionTarget = "aneb_probe_simulator",
        claimScope = "application_end_to_end_to_probe_node",
        contractVersion = ScenarioProfile.CONTRACT_V2,
        modeId = ScenarioProfile.MODE_NETWORK_COMPREHENSIVE,
        executionRequirements = ProfileExecutionRequirements(
            contractId = "aneb-execution-requirements",
            contractVersion = "1.0.0",
            clientEngine = ProfileExecutionContractRange(
                "aneb-network-comprehensive-engine", "1.0.0", "2.0.0",
            ),
            serverCapabilityReceipt = ProfileExecutionContractRange(
                "aneb-server-capability-receipt", "1.0.0", "2.0.0",
            ),
            requiredPrimitives = listOf(
                ProfileExecutionPrimitive("download", "aneb-download-v1"),
                ProfileExecutionPrimitive("echo", "aneb-echo-v1"),
                ProfileExecutionPrimitive("udp_echo", "aneb-udp-echo-v2"),
                ProfileExecutionPrimitive("upload", "aneb-upload-v1"),
            ),
        ),
    )

    private fun validReceipt(
        profileId: String = "network_comprehensive_quick",
        profileVersion: String = "1.2.0",
    ): String = """
        {
          "version":"aneb-server/test",
          "execution_capabilities":{
            "contract_id":"aneb-server-capability-receipt",
            "contract_version":"1.0.0",
            "primitives":[
              {"primitive_id":"download","wire_contract_id":"aneb-download-v1"},
              {"primitive_id":"echo","wire_contract_id":"aneb-echo-v1"},
              {"primitive_id":"udp_echo","wire_contract_id":"aneb-udp-echo-v2"},
              {"primitive_id":"upload","wire_contract_id":"aneb-upload-v1"}
            ],
            "validated_profiles":[
              {
                "profile_id":"$profileId",
                "profile_version":"$profileVersion",
                "profile_sha256":"$PROFILE_SHA"
              }
            ]
          }
        }
    """.trimIndent()

    private companion object {
        const val PROFILE_SHA =
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
