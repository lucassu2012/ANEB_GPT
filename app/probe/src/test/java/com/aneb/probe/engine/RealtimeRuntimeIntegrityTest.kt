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
        listOf("quick", "standard", "recovery", "repeatability_qualification").forEach { variant ->
            val base = repositoryRoot().resolve("profiles/published/ai_realtime_voice_$variant")
            val profileText = Files.readAllBytes(base.resolve("profile.json")).toString(Charsets.UTF_8)
            val planText = Files.readAllBytes(base.resolve("runtime_plan.json")).toString(Charsets.UTF_8)
            val profile = ProfileParser.parseSingle(profileText)
            val plan = json.decodeFromString(RealtimeRuntimePlan.serializer(), planText)

            assertTrue(ProfileCapability.assess(profile).executable)
            assertEquals(profile.executionPlan?.artifactHash, TokenRuntimeIntegrity.canonicalSha256(planText))
            assertEquals(variant, profile.evidenceTier)
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
            if (variant == "recovery") {
                assertEquals("realtime-recovery-score-v2", profile.evaluation.scorePolicyId)
                assertEquals("fixed_model_derived_minimum_speech_plus_wait_v1", plan.recoveryProbeContract)
                assertEquals(2, plan.sessions.count { it.controlledDisconnectAfterTurn != null })
            } else {
                assertTrue(plan.sessions.none { it.controlledDisconnectAfterTurn != null })
            }
            if (variant == "repeatability_qualification") {
                val qualification = requireNotNull(profile.qualification)
                assertEquals("D-110", qualification.decisionId)
                assertTrue(qualification.repeatabilityAndQualityGatesIndependent)
                assertTrue(!qualification.formalBaselineEligible)
                assertTrue(qualification.singleRunConfidenceUnchanged)
            }
        }
    }

    @Test
    fun `controlled recovery consumes only the adjacent attempt even when it fails`() {
        val firstFaultEnd = 1_000L
        val afterFirstFault = nextRealtimeRecoveryStartNanos(
            controlledPairing = true,
            currentStartNanos = null,
            isControlledFault = true,
            isRecoveryAttempt = false,
            unexpectedDisconnect = true,
            sessionEndNanos = firstFaultEnd,
            recoveryMs = null,
        )
        assertEquals(firstFaultEnd, afterFirstFault)

        val afterFailedRecovery = nextRealtimeRecoveryStartNanos(
            controlledPairing = true,
            currentStartNanos = afterFirstFault,
            isControlledFault = false,
            isRecoveryAttempt = true,
            unexpectedDisconnect = true,
            sessionEndNanos = 2_000L,
            recoveryMs = null,
        )
        assertEquals(null, afterFailedRecovery)

        val secondFaultEnd = 3_000L
        val afterSecondFault = nextRealtimeRecoveryStartNanos(
            controlledPairing = true,
            currentStartNanos = afterFailedRecovery,
            isControlledFault = true,
            isRecoveryAttempt = false,
            unexpectedDisconnect = true,
            sessionEndNanos = secondFaultEnd,
            recoveryMs = null,
        )
        assertEquals(secondFaultEnd, afterSecondFault)
    }

    @Test
    fun `natural recovery retains the first disconnect until audio returns`() {
        val firstFaultEnd = 4_000L
        val afterFault = nextRealtimeRecoveryStartNanos(
            controlledPairing = false,
            currentStartNanos = null,
            isControlledFault = false,
            isRecoveryAttempt = false,
            unexpectedDisconnect = true,
            sessionEndNanos = firstFaultEnd,
            recoveryMs = null,
        )
        val afterSecondFailure = nextRealtimeRecoveryStartNanos(
            controlledPairing = false,
            currentStartNanos = afterFault,
            isControlledFault = false,
            isRecoveryAttempt = true,
            unexpectedDisconnect = true,
            sessionEndNanos = 5_000L,
            recoveryMs = null,
        )
        assertEquals(firstFaultEnd, afterSecondFailure)

        assertEquals(
            null,
            nextRealtimeRecoveryStartNanos(
                controlledPairing = false,
                currentStartNanos = afterSecondFailure,
                isControlledFault = false,
                isRecoveryAttempt = true,
                unexpectedDisconnect = false,
                sessionEndNanos = 6_000L,
                recoveryMs = 1_200.0,
            ),
        )
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
