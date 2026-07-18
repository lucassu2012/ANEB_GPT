package com.aneb.probe.engine

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json

class TokenQuickPublishedContractTest {
    @Test
    fun `published quick profile freezes canonical digest and exact execution contract`() {
        val profilePath = repositoryRoot()
            .resolve("profiles/published/token_multimodal_quick/profile.json")
        val profileText = Files.readAllBytes(profilePath).toString(Charsets.UTF_8)
        val runtimeText = Files.readAllBytes(profilePath.resolveSibling("runtime_plan.json")).toString(Charsets.UTF_8)
        val profile = ProfileParser.parseSingle(profileText)
        val runtime = Json { ignoreUnknownKeys = true }
            .decodeFromString(TokenRuntimePlan.serializer(), runtimeText)
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
