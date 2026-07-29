package com.aneb.probe.engine

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkProfileIntegrityTest {
    @Test fun publishedNetworkProfilesAreExecutableAndKeepLoadedRttPrimary() {
        listOf("quick", "standard", "repeatability_qualification").forEach { variant ->
            val path = repositoryRoot().resolve("profiles/published/network_comprehensive_$variant/profile.json")
            val profile = ProfileParser.parseSingle(Files.readAllBytes(path).toString(Charsets.UTF_8))
            assertTrue(ProfileCapability.assess(profile).executable)
            assertEquals(variant, profile.evidenceTier)
            assertEquals("LOADED_RTT_LIVE", profile.livePresentation.primaryMetricId)
            assertEquals("network-comprehensive-score-v1", profile.evaluation.scorePolicyId)
            assertTrue("NET-B10" in profile.evaluation.requiredMetricIds)
            if (variant == "repeatability_qualification") {
                val qualification = requireNotNull(profile.qualification)
                assertEquals("forbidden", qualification.transportPooling)
                assertTrue(qualification.q2RequiresQ1Pass)
                assertTrue(qualification.singleRunConfidenceUnchanged)
            }
        }
    }

    private fun repositoryRoot(): Path {
        var cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(6) {
            if (Files.isDirectory(cursor.resolve("profiles")) && Files.isDirectory(cursor.resolve("app"))) return cursor
            cursor = cursor.parent ?: return@repeat
        }
        error("repository root not found")
    }
}
