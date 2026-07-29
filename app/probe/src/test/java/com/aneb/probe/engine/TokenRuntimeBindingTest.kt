package com.aneb.probe.engine

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class TokenRuntimeBindingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `published token bundles bind every requested variant exactly`() {
        listOf("quick", "standard", "stress", "repeatability_qualification").forEach { variant ->
            val (profile, plan) = bundle(variant)
            TokenRuntimeBinding.validate(profile, plan, variant)
        }
    }

    @Test
    fun `self consistent standard bundle cannot substitute the requested quick path`() {
        val (standardProfile, standardPlan) = bundle("standard")

        val error = try {
            TokenRuntimeBinding.validate(standardProfile, standardPlan, "quick")
            fail("expected requested variant binding rejection")
            error("unreachable")
        } catch (error: IllegalArgumentException) {
            error
        }

        assertEquals("token_profile_id_variant_mismatch", error.message)
    }

    private fun bundle(variant: String): Pair<ScenarioProfile, TokenRuntimePlan> {
        val base = repositoryRoot().resolve("profiles/published/token_multimodal_$variant")
        val profileText = Files.readAllBytes(base.resolve("profile.json")).toString(Charsets.UTF_8)
        val planText = Files.readAllBytes(base.resolve("runtime_plan.json")).toString(Charsets.UTF_8)
        return ProfileParser.parseSingle(profileText) to
            json.decodeFromString(TokenRuntimePlan.serializer(), planText)
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
