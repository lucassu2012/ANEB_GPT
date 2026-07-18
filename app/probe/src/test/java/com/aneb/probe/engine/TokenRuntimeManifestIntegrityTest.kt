package com.aneb.probe.engine

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TokenRuntimeManifestIntegrityTest {
    @Test
    fun `published token manifests bind exact canonical profile and runtime hashes`() {
        listOf("quick", "standard", "stress").forEach { variant ->
            val base = repositoryRoot().resolve("profiles/published/token_multimodal_$variant")
            val profileText = read(base.resolve("profile.json"))
            val runtimeText = read(base.resolve("runtime_plan.json"))
            val manifestText = read(base.resolve("manifest.sha256"))

            val verified = TokenRuntimeManifestIntegrity.verify(manifestText, profileText, runtimeText)

            assertEquals(TokenRuntimeIntegrity.canonicalSha256(profileText), verified.profileSha256)
            assertEquals(TokenRuntimeIntegrity.canonicalSha256(runtimeText), verified.runtimePlanSha256)
        }
    }

    @Test
    fun `manifest rejects format names cardinality case and digest drift`() {
        val profileText = """{"profile_id":"sample","value":1}"""
        val runtimeText = """{"runtime":"sample","value":2}"""
        val profileHash = TokenRuntimeIntegrity.canonicalSha256(profileText).removePrefix("sha256:")
        val runtimeHash = TokenRuntimeIntegrity.canonicalSha256(runtimeText).removePrefix("sha256:")
        val valid = "$profileHash  profile.json\n$runtimeHash  runtime_plan.json\n"
        val invalidManifests = listOf(
            valid.replace(profileHash, profileHash.uppercase()),
            valid.replace("  profile.json", " profile.json"),
            valid.replace("profile.json", "./profile.json"),
            "$profileHash  profile.json\n",
            "$profileHash  profile.json\n$runtimeHash  profile.json\n",
            valid + "$runtimeHash  extra.json\n",
            valid.replace(profileHash, "0".repeat(64)),
            valid + "\n",
        )

        invalidManifests.forEach { manifest ->
            val error = try {
                TokenRuntimeManifestIntegrity.verify(manifest, profileText, runtimeText)
                fail("expected manifest rejection")
                error("unreachable")
            } catch (error: IllegalArgumentException) {
                error
            }
            assertTrue(error.message?.startsWith("token_manifest_") == true)
        }
    }

    private fun read(path: Path): String = Files.readAllBytes(path).toString(Charsets.UTF_8)

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
