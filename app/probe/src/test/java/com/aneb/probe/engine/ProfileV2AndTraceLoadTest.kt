package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfileV2AndTraceLoadTest {

    private fun repoFile(relFromRepoRoot: String): File {
        var cur: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (cur != null) {
            val cand = File(cur, relFromRepoRoot)
            if (cand.isFile) return cand
            cur = cur.parentFile
        }
        error("cannot find $relFromRepoRoot from user.dir")
    }

    @Test
    fun `loads exported token_multimodal_standard profile and golden trace cleanly`() {
        val profileJson = repoFile("profiles/v2/token_multimodal_standard/profile.json").readText(Charsets.UTF_8)
        val traceJsonl = repoFile("profiles/v2/token_multimodal_standard/golden_trace.jsonl").readText(Charsets.UTF_8)

        val profile = ProfileParser.parseSingle(profileJson)
        val assessment = ProfileCapability.assess(profile)
        assertTrue(
            "exported v2 profile should be executable: issues=${assessment.contractIssues}, unsupported=${assessment.unsupportedPhaseTypes}",
            assessment.executable,
        )
        assertEquals(ScenarioProfile.MODE_TOKEN_SIMULATION, profile.modeId)
        assertEquals(ScenarioProfile.CONTRACT_V2, profile.contractVersion)

        val plan = BehaviorTracePlan.parse(traceJsonl, profile)
        assertEquals(20, plan.tasks.size)
        assertEquals(14040, plan.totalPlannedTokens)
        assertEquals(16023, plan.eventCount)
        assertEquals(20260716L, plan.seed)
        assertTrue(plan.totalPlannedSpanMs > 1_300_000.0)
        assertTrue(plan.tasks.all { it.uploadBytes > 0L && it.tokenPlans.isNotEmpty() })

        // Frozen v1 contracts remain intact.
        assertEquals(listOf("s1_chat", "s2_coding_agent", "s3_multimodal"), ProfileParser.REQUIRED_IDS)
        assertEquals(listOf("s1_chat", "s2_coding_agent", "s3_multimodal", "basic_network"), ProfileParser.BUILTIN_IDS)
    }

    @Test
    fun `rejects trace with leaked network outcome fields`() {
        val profileJson = repoFile("profiles/v2/token_multimodal_standard/profile.json").readText(Charsets.UTF_8)
        val profile = ProfileParser.parseSingle(profileJson)
        val leakedLine = """{"trace_contract_version":"aneb-behavior-trace-v1","model_id":"${profile.business.behaviorModelId}","model_version":"${profile.business.behaviorModelVersion}","seed":20260716,"event_index":0,"task_id":"task-0001","event_type":"user_action","planned_offset_ms":0.0,"measured_rtt_ms":12.3}"""
        assertThrows(IllegalArgumentException::class.java) {
            BehaviorTracePlan.parse(leakedLine, profile)
        }
    }
}
