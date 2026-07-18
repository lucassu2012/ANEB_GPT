package com.aneb.probe.engine

import com.aneb.probe.net.AnebClient
import com.aneb.probe.net.TokenSimArrival
import com.aneb.probe.net.TokenSimPrelude
import com.aneb.probe.net.TokenSimTaskPlan
import com.aneb.probe.net.TokenSimTaskResult
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TokenExecutionContractGateFailClosedTest {
    @Test
    fun `missing receipt rejects before every business request`() = runBlocking {
        assertRemoteRejection(
            serverInfoBody = """{"version":"aneb-server/test"}""",
            expectedReason = "receipt_missing",
        )
    }

    @Test
    fun `incompatible receipt major rejects before every business request`() = runBlocking {
        assertRemoteRejection(
            serverInfoBody = validReceipt(contractVersion = "2.0.0"),
            expectedReason = "receipt_version_incompatible",
        )
    }

    @Test
    fun `profile digest drift rejects before every business request`() = runBlocking {
        assertRemoteRejection(
            serverInfoBody = validReceipt(profileSha = OTHER_SHA),
            expectedReason = "receipt_profile_digest_mismatch",
            expectedChineseDetail = "摘要",
        )
    }

    @Test
    fun `missing required primitive rejects before every business request`() = runBlocking {
        assertRemoteRejection(
            serverInfoBody = validReceipt(
                primitives = listOf(
                    "echo" to "aneb-echo-v1",
                    "token_sim" to "aneb-token-task-v1",
                ),
            ),
            expectedReason = "receipt_primitive_missing",
        )
    }

    @Test
    fun `duplicate receipt primitive rejects before every business request`() = runBlocking {
        assertRemoteRejection(
            serverInfoBody = validReceipt(
                primitives = listOf(
                    "echo" to "aneb-echo-v1",
                    "echo" to "aneb-echo-v1",
                    "token_sim" to "aneb-token-task-v1",
                    "download" to "aneb-download-v1",
                ),
            ),
            expectedReason = "receipt_primitive_duplicate",
        )
    }

    @Test
    fun `incompatible client engine is rejected locally without control or business traffic`() = runBlocking {
        val fakeServer = CountingTransport(validReceipt())

        val error = rejected {
            TokenExecutionContractGate.authorize(
                serverBase = "https://aneb.test",
                profile = requiredProfile(engineMinVersion = "1.1.0"),
                profileCanonicalSha256 = PROFILE_SHA,
                transport = fakeServer,
            )
        }

        assertEquals("client_engine_incompatible", error.reasonCode)
        assertEquals(0, fakeServer.serverInfoRequests)
        assertEquals(0, fakeServer.businessRequests)
    }

    @Test
    fun `requirements marker rejects every profile outside frozen quick migration identity`() = runBlocking {
        val requirements = requireNotNull(requiredProfile().executionRequirements)
        val (publishedStandard, _) = publishedProfile("token_multimodal_standard")
        val notMigrated = listOf(
            publishedStandard.copy(executionRequirements = requirements),
            requiredProfile().copy(version = "1.3.0"),
        )

        notMigrated.forEach { profile ->
            val fakeServer = CountingTransport(validReceipt())
            val error = rejected {
                TokenExecutionContractGate.authorize(
                    serverBase = "https://aneb.test",
                    profile = profile,
                    profileCanonicalSha256 = PROFILE_SHA,
                    transport = fakeServer,
                )
            }

            assertEquals("execution_requirements_profile_not_migrated", error.reasonCode)
            assertEquals(0, fakeServer.serverInfoRequests)
            assertEquals(0, fakeServer.businessRequests)
        }
    }

    @Test
    fun `quick migration profile missing requirements is rejected locally with zero traffic`() = runBlocking {
        val fakeServer = CountingTransport(validReceipt())

        val error = rejected {
            TokenExecutionContractGate.authorize(
                serverBase = "https://aneb.test",
                profile = requiredProfile().copy(executionRequirements = null),
                profileCanonicalSha256 = PROFILE_SHA,
                transport = fakeServer,
            )
        }

        assertEquals("execution_requirements_missing", error.reasonCode)
        assertEquals(0, fakeServer.serverInfoRequests)
        assertEquals(0, fakeServer.businessRequests)
    }

    @Test
    fun `quick migration identity fields are locked before control and business traffic`() = runBlocking {
        val driftedProfiles = listOf(
            requiredProfile().copy(modeId = ScenarioProfile.MODE_TOKEN_EXPERIENCE),
            requiredProfile().copy(executionTarget = "other_target"),
            requiredProfile().copy(claimScope = "other_scope"),
        )

        driftedProfiles.forEach { profile ->
            val fakeServer = CountingTransport(validReceipt())
            val error = rejected {
                TokenExecutionContractGate.authorize(
                    serverBase = "https://aneb.test",
                    profile = profile,
                    profileCanonicalSha256 = PROFILE_SHA,
                    transport = fakeServer,
                )
            }

            assertEquals("quick_identity_mismatch", error.reasonCode)
            assertEquals(0, fakeServer.serverInfoRequests)
            assertEquals(0, fakeServer.businessRequests)
        }
    }
    @Test
    fun `profiles outside frozen quick and legacy allowlists fail locally without requirements`() = runBlocking {
        val (publishedStandard, _) = publishedProfile("token_multimodal_standard")
        val rejectedProfiles = listOf(
            requiredProfile().copy(profileId = "token_multimodal_quik", executionRequirements = null),
            requiredProfile().copy(version = "1.2.1", executionRequirements = null),
            publishedStandard.copy(version = "1.1.1"),
            publishedStandard.copy(claimScope = "other_scope"),
        )

        rejectedProfiles.forEach { profile ->
            val fakeServer = CountingTransport(validReceipt())
            val error = rejected {
                TokenExecutionContractGate.authorize(
                    serverBase = "https://aneb.test",
                    profile = profile,
                    profileCanonicalSha256 = PROFILE_SHA,
                    transport = fakeServer,
                )
            }

            assertEquals("token_profile_identity_not_allowed", error.reasonCode)
            assertEquals(0, fakeServer.serverInfoRequests)
            assertEquals(0, fakeServer.businessRequests)
        }
    }


    @Test
    fun `published standard and stress profiles preserve legacy behavior without receipt requests`() = runBlocking {
        listOf("token_multimodal_standard", "token_multimodal_stress").forEach { profileId ->
            val (profile, profileText) = publishedProfile(profileId)
            val fakeServer = CountingTransport("not used")

            assertEquals(null, profile.executionRequirements)
            val traffic = TokenExecutionContractGate.authorize(
                serverBase = "https://aneb.test",
                profile = profile,
                profileCanonicalSha256 = TokenRuntimeIntegrity.canonicalSha256(profileText),
                transport = fakeServer,
            )
            traffic.echo("https://aneb.test/api/v1/echo")

            assertEquals(TokenExecutionAuthorization.LEGACY_PROFILE, traffic.authorization)
            assertEquals(0, fakeServer.serverInfoRequests)
            assertEquals(1, fakeServer.businessRequests)
        }
    }

    @Test
    fun `unique extra server primitive is ignored`() = runBlocking {
        val fakeServer = CountingTransport(
            validReceipt(
                primitives = REQUIRED_PRIMITIVES + ("future_probe" to "aneb-future-v1"),
            ),
        )

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
    fun `unique unknown server capability field remains forward compatible`() = runBlocking {
        val receiptWithFutureCapability = validReceipt().replace(
            "\"contract_version\":\"1.0.0\",",
            "\"contract_version\":\"1.0.0\",\"future_capability\":{\"version\":1},",
        )
        val fakeServer = CountingTransport(receiptWithFutureCapability)

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
    fun `required primitive wire mismatch rejects before every business request`() = runBlocking {
        assertRemoteRejection(
            serverInfoBody = validReceipt(
                primitives = listOf(
                    "echo" to "aneb-echo-v2",
                    "token_sim" to "aneb-token-task-v1",
                    "download" to "aneb-download-v1",
                ),
            ),
            expectedReason = "receipt_wire_mismatch",
        )
    }

    @Test
    fun `validated profile version mismatch rejects before every business request`() = runBlocking {
        assertRemoteRejection(
            serverInfoBody = validReceipt().replace(
                "\"profile_version\":\"1.2.0\"",
                "\"profile_version\":\"1.3.0\"",
            ),
            expectedReason = "receipt_profile_version_mismatch",
        )
    }

    @Test
    fun `duplicate required primitive is rejected locally without control or business traffic`() = runBlocking {
        val fakeServer = CountingTransport(validReceipt())
        val profile = requiredProfile()
        val requirements = requireNotNull(profile.executionRequirements)

        val error = rejected {
            TokenExecutionContractGate.authorize(
                serverBase = "https://aneb.test",
                profile = profile.copy(
                    executionRequirements = requirements.copy(
                        requiredPrimitives = requirements.requiredPrimitives + requirements.requiredPrimitives.first(),
                    ),
                ),
                profileCanonicalSha256 = PROFILE_SHA,
                transport = fakeServer,
            )
        }

        assertEquals("required_primitive_duplicate", error.reasonCode)
        assertEquals(0, fakeServer.serverInfoRequests)
        assertEquals(0, fakeServer.businessRequests)
    }

    @Test
    fun `profile missing download primitive is rejected locally with zero traffic`() = runBlocking {
        val fakeServer = CountingTransport(validReceipt())
        val profile = requiredProfile()
        val requirements = requireNotNull(profile.executionRequirements)

        val error = rejected {
            TokenExecutionContractGate.authorize(
                serverBase = "https://aneb.test",
                profile = profile.copy(
                    executionRequirements = requirements.copy(
                        requiredPrimitives = requirements.requiredPrimitives.filterNot { it.primitiveId == "download" },
                    ),
                ),
                profileCanonicalSha256 = PROFILE_SHA,
                transport = fakeServer,
            )
        }

        assertEquals("required_primitive_set_incomplete", error.reasonCode)
        assertTrue(error.userMessage.contains("\u5b8c\u6574\u58f0\u660e"))
        assertEquals(0, fakeServer.serverInfoRequests)
        assertEquals(0, fakeServer.businessRequests)
    }

    private suspend fun assertRemoteRejection(
        serverInfoBody: String,
        expectedReason: String,
        expectedChineseDetail: String = "测试已停止",
    ) {
        val fakeServer = CountingTransport(serverInfoBody)

        val error = rejected {
            TokenExecutionContractGate.authorize(
                serverBase = "https://aneb.test",
                profile = requiredProfile(),
                profileCanonicalSha256 = PROFILE_SHA,
                transport = fakeServer,
            )
        }

        assertEquals(expectedReason, error.reasonCode)
        assertTrue(error.userMessage.startsWith("节点能力合同校验失败："))
        assertTrue(error.userMessage.contains(expectedChineseDetail))
        assertEquals(1, fakeServer.serverInfoRequests)
        assertEquals(0, fakeServer.businessRequests)
    }

    private suspend fun rejected(block: suspend () -> Unit): TokenExecutionContractException {
        return try {
            block()
            fail("expected TokenExecutionContractException")
            error("unreachable")
        } catch (error: TokenExecutionContractException) {
            error
        }
    }

    private class CountingTransport(private val serverInfoBody: String) : TokenExecutionTransport {
        var serverInfoRequests = 0
        var echoRequests = 0
        var tokenSimRequests = 0
        var downloadRequests = 0
        val businessRequests: Int get() = echoRequests + tokenSimRequests + downloadRequests

        override suspend fun fetchServerInfo(url: String): AnebClient.HttpTextResult {
            serverInfoRequests++
            return AnebClient.HttpTextResult(200, serverInfoBody, null)
        }

        override suspend fun echo(url: String): AnebClient.EchoResult {
            echoRequests++
            return AnebClient.EchoResult(1L, 1L, 1L, 1L, 0L, 0L, 200, null, null)
        }

        override suspend fun tokenSim(
            url: String,
            plan: TokenSimTaskPlan,
            uploadChunkBytes: Int,
            uploadChunkCadenceMs: Double,
            onUploadBytes: (Long, Long) -> Unit,
            onPrelude: (TokenSimPrelude, Long) -> Unit,
            onToken: (TokenSimArrival) -> Unit,
        ): TokenSimTaskResult {
            tokenSimRequests++
            error("business request must not run")
        }

        override suspend fun downloadThroughput(
            url: String,
            onBytes: (Int, Long) -> Unit,
        ): AnebClient.TransferResult {
            downloadRequests++
            error("business request must not run")
        }
    }

    private fun requiredProfile(engineMinVersion: String = "1.0.0"): ScenarioProfile = ScenarioProfile(
        profileId = "token_multimodal_quick",
        version = "1.2.0",
        executionTarget = "aneb_probe_simulator",
        claimScope = "application_end_to_end_to_probe_node",
        contractVersion = ScenarioProfile.CONTRACT_V2,
        modeId = ScenarioProfile.MODE_TOKEN_SIMULATION,
        executionRequirements = ProfileExecutionRequirements(
            contractId = "aneb-execution-requirements",
            contractVersion = "1.0.0",
            clientEngine = ProfileExecutionContractRange(
                contractId = "aneb-token-simulation-engine",
                minVersion = engineMinVersion,
                maxVersionExclusive = "2.0.0",
            ),
            serverCapabilityReceipt = ProfileExecutionContractRange(
                contractId = "aneb-server-capability-receipt",
                minVersion = "1.0.0",
                maxVersionExclusive = "2.0.0",
            ),
            requiredPrimitives = REQUIRED_PRIMITIVES.map { (id, wire) ->
                ProfileExecutionPrimitive(id, wire)
            },
        ),
    )

    private fun validReceipt(
        contractVersion: String = "1.0.0",
        profileSha: String = PROFILE_SHA,
        primitives: List<Pair<String, String>> = REQUIRED_PRIMITIVES,
    ): String {
        val encodedPrimitives = primitives.joinToString(",") { (id, wire) ->
            """{"primitive_id":"$id","wire_contract_id":"$wire"}"""
        }
        return """
            {
              "execution_capabilities":{
                "contract_id":"aneb-server-capability-receipt",
                "contract_version":"$contractVersion",
                "primitives":[$encodedPrimitives],
                "validated_profiles":[
                  {"profile_id":"token_multimodal_quick","profile_version":"1.2.0","profile_sha256":"$profileSha"}
                ]
              }
            }
        """.trimIndent()
    }
    private fun publishedProfile(profileId: String): Pair<ScenarioProfile, String> {
        val profilePath = repositoryRoot().resolve("profiles/published/$profileId/profile.json")
        val profileText = Files.readAllBytes(profilePath).toString(Charsets.UTF_8)
        return ProfileParser.parseSingle(profileText) to profileText
    }

    private fun repositoryRoot(): Path {
        var cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(6) {
            if (Files.isDirectory(cursor.resolve("profiles")) && Files.isDirectory(cursor.resolve("app"))) {
                return cursor
            }
            cursor = cursor.parent ?: return@repeat
        }
        error("repository root not found from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val PROFILE_SHA = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val OTHER_SHA = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val REQUIRED_PRIMITIVES = listOf(
            "echo" to "aneb-echo-v1",
            "token_sim" to "aneb-token-task-v1",
            "download" to "aneb-download-v1",
        )
    }
}
