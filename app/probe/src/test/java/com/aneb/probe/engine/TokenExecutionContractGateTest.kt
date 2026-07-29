package com.aneb.probe.engine

import com.aneb.probe.net.AnebClient
import com.aneb.probe.net.TokenSimTaskPlan
import com.aneb.probe.net.TokenSimTaskResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TokenExecutionContractGateTest {
    @Test
    fun `compatible receipt authorizes business traffic after control plane verification`() = runBlocking {
        val fakeServer = FakeTokenServer(validReceipt(PROFILE_SHA))

        val traffic = TokenExecutionContractGate.authorize(
            serverBase = "https://aneb.test",
            profile = requiredProfile(),
            profileCanonicalSha256 = PROFILE_SHA,
            transport = fakeServer,
        )
        traffic.echo("https://aneb.test/api/v1/echo")

        assertEquals(TokenExecutionAuthorization.VALIDATED_RECEIPT, traffic.authorization)
        assertEquals(1, fakeServer.serverInfoRequests)
        assertEquals(1, fakeServer.businessRequests)
    }

    @Test
    fun `repeatability qualification requires and accepts its exact capability receipt`() = runBlocking {
        val profileId = "token_multimodal_repeatability_qualification"
        val version = "1.0.0"
        val fakeServer = FakeTokenServer(validReceipt(PROFILE_SHA, profileId, version))

        val traffic = TokenExecutionContractGate.authorize(
            serverBase = "https://aneb.test",
            profile = requiredProfile(profileId, version),
            profileCanonicalSha256 = PROFILE_SHA,
            transport = fakeServer,
        )

        assertEquals(TokenExecutionAuthorization.VALIDATED_RECEIPT, traffic.authorization)
        assertEquals(1, fakeServer.serverInfoRequests)
        assertEquals(0, fakeServer.businessRequests)
    }

    private class FakeTokenServer(private val serverInfoBody: String) : TokenExecutionTransport {
        var serverInfoRequests = 0
        var businessRequests = 0

        override suspend fun fetchServerInfo(url: String): AnebClient.HttpTextResult {
            serverInfoRequests++
            return AnebClient.HttpTextResult(200, serverInfoBody, null)
        }

        override suspend fun echo(url: String): AnebClient.EchoResult {
            businessRequests++
            return AnebClient.EchoResult(
                t0Us = 1L,
                t1Us = 1L,
                t2Us = 1L,
                t3Us = 1L,
                offsetUs = 0L,
                rttUs = 0L,
                httpCode = 200,
                error = null,
                timing = null,
            )
        }

        override suspend fun tokenSim(
            url: String,
            plan: TokenSimTaskPlan,
            uploadChunkBytes: Int,
            uploadChunkCadenceMs: Double,
            onUploadBytes: (Long, Long) -> Unit,
            onPrelude: (com.aneb.probe.net.TokenSimPrelude, Long) -> Unit,
            onToken: (com.aneb.probe.net.TokenSimArrival) -> Unit,
        ): TokenSimTaskResult = error("unexpected tokenSim")

        override suspend fun downloadThroughput(
            url: String,
            onBytes: (Int, Long) -> Unit,
        ): AnebClient.TransferResult = error("unexpected download")
    }

    private fun requiredProfile(
        profileId: String = "token_multimodal_quick",
        version: String = "1.2.1",
    ): ScenarioProfile = ScenarioProfile(
        profileId = profileId,
        version = version,
        executionTarget = "aneb_probe_simulator",
        claimScope = "application_end_to_end_to_probe_node",
        contractVersion = ScenarioProfile.CONTRACT_V2,
        modeId = ScenarioProfile.MODE_TOKEN_SIMULATION,
        executionRequirements = ProfileExecutionRequirements(
            contractId = "aneb-execution-requirements",
            contractVersion = "1.0.0",
            clientEngine = ProfileExecutionContractRange(
                contractId = "aneb-token-simulation-engine",
                minVersion = "1.0.0",
                maxVersionExclusive = "2.0.0",
            ),
            serverCapabilityReceipt = ProfileExecutionContractRange(
                contractId = "aneb-server-capability-receipt",
                minVersion = "1.0.0",
                maxVersionExclusive = "2.0.0",
            ),
            requiredPrimitives = listOf(
                ProfileExecutionPrimitive("echo", "aneb-echo-v1"),
                ProfileExecutionPrimitive("token_sim", "aneb-token-task-v1"),
                ProfileExecutionPrimitive("download", "aneb-download-v1"),
            ),
        ),
    )

    private fun validReceipt(
        profileSha: String,
        profileId: String = "token_multimodal_quick",
        profileVersion: String = "1.2.1",
    ): String = """
        {
          "version":"aneb-server/test",
          "execution_capabilities":{
            "contract_id":"aneb-server-capability-receipt",
            "contract_version":"1.0.0",
            "primitives":[
              {"primitive_id":"download","wire_contract_id":"aneb-download-v1"},
              {"primitive_id":"echo","wire_contract_id":"aneb-echo-v1"},
              {"primitive_id":"token_sim","wire_contract_id":"aneb-token-task-v1"}
            ],
            "validated_profiles":[
              {"profile_id":"$profileId","profile_version":"$profileVersion","profile_sha256":"$profileSha"}
            ]
          }
        }
    """.trimIndent()

    private companion object {
        const val PROFILE_SHA = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
