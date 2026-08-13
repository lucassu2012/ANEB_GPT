package com.aneb.probe.release

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseSigningBuildContractTest {
    @Test
    fun `unprovisioned release signer fails closed before packaging`() {
        val script = Files.readAllBytes(findRepositoryRoot().resolve("app/probe/build.gradle.kts"))
            .toString(Charsets.UTF_8)

        assertTrue(
            script.contains(
                "val approvedLocalReleaseSigningCertificateSha256: Set<String> = emptySet()",
            ),
        )
        assertFalse(script.contains("ANEB_RELEASE_CERTIFICATE_SHA256"))
        assertFalse(script.contains("aneb.release.certificateSha256"))
        assertTrue(script.contains("KeyStore.getInstance(storeType)"))
        assertTrue(script.contains("MessageDigest.getInstance(\"SHA-256\")"))
        assertTrue(script.contains(".digest(certificate.encoded)"))

        val verifier = script.substringAfter("val verifyReleaseSigning by tasks.registering")
            .substringBefore("val originBoundOracleMode")
        val expectedOrder = listOf(
            "PRODUCTION_RELEASE_SIGNER_UNPROVISIONED",
            "RELEASE_SIGNING_CREDENTIALS_INCOMPLETE",
            "releaseSigningCertificateSha256(",
            "RELEASE_SIGNING_CERTIFICATE_NOT_APPROVED",
        )
        val indexes = expectedOrder.map(verifier::indexOf)
        assertTrue(indexes.all { it >= 0 })
        assertEquals(indexes.sorted(), indexes)

        val gatedTasks = setOf(
            "assembleRelease",
            "bundleRelease",
            "installRelease",
            "packageRelease",
            "packageReleaseBundle",
            "signReleaseBundle",
        )
        gatedTasks.forEach { task -> assertTrue(verifier.contains("\"$task\"")) }
        assertFalse(verifier.contains("processReleaseMainManifest"))
        assertFalse(verifier.contains("testDebugUnitTest"))
        assertFalse(Regex("0{64}").containsMatchIn(script))
    }

    private fun findRepositoryRoot(): Path {
        var cursor: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(8) {
            val candidate = checkNotNull(cursor)
            if (Files.isRegularFile(candidate.resolve("app/probe/build.gradle.kts"))) {
                return candidate
            }
            cursor = candidate.parent
        }
        error("repository root not found")
    }
}
