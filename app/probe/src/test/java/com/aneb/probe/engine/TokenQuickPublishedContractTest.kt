package com.aneb.probe.engine

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TokenQuickPublishedContractTest {
    @Test
    fun `published quick profile freezes canonical digest and exact execution contract`() {
        val root = repositoryRoot()
        val profilePath = root
            .resolve("profiles/published/token_multimodal_quick/profile.json")
        val profileText = Files.readAllBytes(profilePath).toString(Charsets.UTF_8)
        val runtimeText = Files.readAllBytes(profilePath.resolveSibling("runtime_plan.json")).toString(Charsets.UTF_8)
        val requestEntryContractText = Files.readAllBytes(
            root.resolve("spec/execution-contracts/token_multimodal_quick-1.2.1.request-entry.json"),
        ).toString(Charsets.UTF_8)
        val profile = ProfileParser.parseSingle(profileText)
        val runtime = Json { ignoreUnknownKeys = true }
            .decodeFromString(TokenRuntimePlan.serializer(), runtimeText)
        val requestEntryContract = Json.parseToJsonElement(requestEntryContractText).jsonObject
        val requirements = requireNotNull(profile.executionRequirements)

        assertEquals("token_multimodal_quick", profile.profileId)
        assertEquals("1.2.1", profile.version)
        assertEquals(ScenarioProfile.MODE_TOKEN_SIMULATION, profile.modeId)
        assertEquals("aneb_probe_simulator", profile.executionTarget)
        assertEquals("application_end_to_end_to_probe_node", profile.claimScope)
        assertEquals(
            "sha256:caeda36fc11046385fd2ca3052e68d02e4e49ad72ab4125015fd61c91a592773",
            TokenRuntimeIntegrity.canonicalSha256(profileText),
        )
        assertEquals(
            "sha256:83e5c828784e1df89939f1c42fbdd296e3bb02c362676e82603a34575f17e926",
            TokenRuntimeIntegrity.canonicalSha256(runtimeText),
        )
        assertTrue(runtime.tasks.any { it.responseArtifactBytes > 0L })
        assertEquals("aneb-execution-requirements", requirements.contractId)
        assertEquals("1.0.0", requirements.contractVersion)
        assertEquals(
            ProfileExecutionContractRange(
                contractId = "aneb-token-simulation-engine",
                minVersion = "1.0.0",
                maxVersionExclusive = "2.0.0",
            ),
            requirements.clientEngine,
        )
        assertEquals(
            ProfileExecutionContractRange(
                contractId = "aneb-server-capability-receipt",
                minVersion = "1.0.0",
                maxVersionExclusive = "2.0.0",
            ),
            requirements.serverCapabilityReceipt,
        )
        assertEquals(
            listOf(
                ProfileExecutionPrimitive("echo", "aneb-echo-v1"),
                ProfileExecutionPrimitive("token_sim", "aneb-token-task-v1"),
                ProfileExecutionPrimitive("download", "aneb-download-v1"),
            ),
            requirements.requiredPrimitives,
        )

        assertEquals(
            "aneb-request-entry-exact-count-contract",
            requestEntryContract.getValue("schema").jsonPrimitive.content,
        )
        assertEquals(
            "aneb-token-quick-request-entry-counts",
            requestEntryContract.getValue("contract_id").jsonPrimitive.content,
        )
        assertEquals("1.0.0", requestEntryContract.getValue("version").jsonPrimitive.content)
        assertEquals(
            listOf("positive_completed"),
            requestEntryContract.getValue("applies_to").jsonArray.map { it.jsonPrimitive.content },
        )

        val sidecarProfile = requestEntryContract.getValue("profile").jsonObject
        assertEquals(profile.profileId, sidecarProfile.getValue("id").jsonPrimitive.content)
        assertEquals(profile.version, sidecarProfile.getValue("version").jsonPrimitive.content)
        assertEquals(
            TokenRuntimeIntegrity.canonicalSha256(profileText),
            sidecarProfile.getValue("canonical_sha256").jsonPrimitive.content,
        )

        val clientEngine = requestEntryContract.getValue("client_engine").jsonObject
        assertEquals(
            "aneb-token-simulation-engine",
            clientEngine.getValue("contract_id").jsonPrimitive.content,
        )
        assertEquals("1.0.0", clientEngine.getValue("version").jsonPrimitive.content)

        val sidecarRuntime = requestEntryContract.getValue("runtime").jsonObject
        assertEquals(
            TokenRuntimeIntegrity.canonicalSha256(runtimeText),
            sidecarRuntime.getValue("canonical_sha256").jsonPrimitive.content,
        )
        assertEquals(runtime.tasks.size, sidecarRuntime.getValue("task_count").jsonPrimitive.int)
        assertEquals(
            runtime.tasks.count { it.responseArtifactBytes > 0L },
            sidecarRuntime.getValue("positive_response_artifact_task_count").jsonPrimitive.int,
        )

        val exactCounts = requestEntryContract.getValue("exact_business_counts").jsonObject
        assertEquals(TokenSimulationEngine.ECHO_SAMPLES, exactCounts.getValue("echo").jsonPrimitive.int)
        assertEquals(runtime.tasks.size, exactCounts.getValue("token_sim").jsonPrimitive.int)
        assertEquals(
            runtime.tasks.count { it.responseArtifactBytes > 0L },
            exactCounts.getValue("download").jsonPrimitive.int,
        )
        assertEquals(20, exactCounts.getValue("echo").jsonPrimitive.int)
        assertEquals(3, exactCounts.getValue("token_sim").jsonPrimitive.int)
        assertEquals(1, exactCounts.getValue("download").jsonPrimitive.int)
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
}
