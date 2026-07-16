package com.aneb.probe.engine

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenRuntimeIntegrityTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `published token assets are executable and hash bound`() {
        listOf("quick", "standard", "stress").forEach { variant ->
            val base = repositoryRoot().resolve("profiles/published/token_multimodal_$variant")
            val profileText = Files.readAllBytes(base.resolve("profile.json")).toString(Charsets.UTF_8)
            val planText = Files.readAllBytes(base.resolve("runtime_plan.json")).toString(Charsets.UTF_8)
            val profile = ProfileParser.parseSingle(profileText)
            val plan = json.decodeFromString(TokenRuntimePlan.serializer(), planText)

            assertTrue(ProfileCapability.assess(profile).executable)
            assertEquals(profile.executionPlan?.artifactHash, TokenRuntimeIntegrity.canonicalSha256(planText))
            assertEquals(variant, profile.evidenceTier)
            assertEquals(variant, plan.variant)
            assertEquals(plan.taskCount, plan.tasks.size)
        }
    }

    private fun repositoryRoot(): Path {
        var cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(6) {
            if (Files.isDirectory(cursor.resolve("profiles")) && Files.isDirectory(cursor.resolve("app"))) return cursor
            cursor = cursor.parent ?: return@repeat
        }
        error("repository root not found from ${System.getProperty("user.dir")}")
    }
}
