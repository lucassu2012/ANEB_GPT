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
        listOf("quick", "standard", "stress", "repeatability_qualification").forEach { variant ->
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
            if (variant == "repeatability_qualification") {
                val qualification = requireNotNull(profile.qualification)
                assertEquals("aneb-repeatability-qualification-balanced-v1", qualification.policyId)
                assertEquals("505276dc9e72eb68454461bb355b63db6227069274646835020d89a6646fedfa", qualification.policySha256)
                assertEquals(listOf("Q1_WIFI", "Q2_CELLULAR"), qualification.stageOrder)
                assertEquals(10, qualification.runsPerFamily)
                assertTrue(qualification.singleRunConfidenceUnchanged)
            }
        }
    }

    @Test
    fun `canonical numbers match frozen Python serialization vectors`() {
        val thresholdVector = """
            {
              "tiny": 4.2E-5,
              "edge": 0.0001000,
              "big_fixed": 1.0E15,
              "big_exp": 1.0E16,
              "negative_zero": -0.0,
              "one": 1.00
            }
        """.trimIndent()
        assertEquals(
            "sha256:c30c678fa35de21db6ad844a97b630aec9c36fd5e63fb619f4b34b0845579109",
            TokenRuntimeIntegrity.canonicalSha256(thresholdVector),
        )

        val measuredResidualVector = """
            {"variation":-0.0005200000000016303,"residual":4.2000000000541604E-5}
        """.trimIndent()
        assertEquals(
            "sha256:2648099cd155408aa6f7ebd6551d02288f842d466bcafb05449d854dc4307dcd",
            TokenRuntimeIntegrity.canonicalSha256(measuredResidualVector),
        )
    }

    @Test
    fun `equivalent floating lexical forms have one canonical digest`() {
        assertEquals(
            TokenRuntimeIntegrity.canonicalSha256("""{"value":0.000042}"""),
            TokenRuntimeIntegrity.canonicalSha256("""{"value":4.2e-05}"""),
        )
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
