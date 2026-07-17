package com.aneb.probe.engine

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeRuntimeIntegrityTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `published realtime assets are executable hash bound and contain barge in`() {
        listOf("quick", "standard").forEach { variant ->
            val base = repositoryRoot().resolve("profiles/published/ai_realtime_voice_$variant")
            val profileText = Files.readAllBytes(base.resolve("profile.json")).toString(Charsets.UTF_8)
            val planText = Files.readAllBytes(base.resolve("runtime_plan.json")).toString(Charsets.UTF_8)
            val profile = ProfileParser.parseSingle(profileText)
            val plan = json.decodeFromString(RealtimeRuntimePlan.serializer(), planText)

            assertTrue(ProfileCapability.assess(profile).executable)
            assertEquals(profile.executionPlan?.artifactHash, TokenRuntimeIntegrity.canonicalSha256(planText))
            assertEquals(variant, plan.variant)
            assertEquals(plan.sessionCount, plan.sessions.size)
            assertTrue(plan.sessions.flatMap { it.turns }.any { it.interrupted })
            assertEquals(
                setOf(
                    "LIVE-B01", "LIVE-B02", "LIVE-B03", "LIVE-B04", "LIVE-B05", "LIVE-B06",
                    "LIVE-B07", "LIVE-B08", "LIVE-B09", "LIVE-B10", "LIVE-B11", "LIVE-B12",
                    "LIVE-N01", "LIVE-N02", "LIVE-N03", "LIVE-N04", "LIVE-N05", "LIVE-N06",
                    "LIVE-N07", "LIVE-N08", "LIVE-R01",
                ),
                profile.measurements.map { it.metricId }.toSet(),
            )
            assertEquals("AUDIO_ON_TIME_RATIO_2S", profile.livePresentation.primaryMetricId)
            assertTrue(profile.livePresentation.secondaryMetricIds.contains("RTT_LIVE"))
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
