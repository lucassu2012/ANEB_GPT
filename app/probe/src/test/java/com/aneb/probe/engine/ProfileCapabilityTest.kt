package com.aneb.probe.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfileCapabilityTest {

    private fun completeProfile(mode: String, phases: List<ProfilePhase>) = ScenarioProfile(
        profileId = "test",
        version = "1.0.0",
        modeId = mode,
        description = "测试业务",
        presentation = ProfilePresentation(
            liveMetricId = "rate",
            liveMetricLabel = "实时速率",
            liveMetricUnit = "Mbps",
            metricIds = listOf("rate"),
            conclusionPolicyId = "policy-v1",
        ),
        phases = phases,
    )

    private fun repoFile(relFromRepoRoot: String): File {
        var cur: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (cur != null) {
            val cand = File(cur, relFromRepoRoot)
            if (cand.isFile) return cand
            cur = cur.parentFile
        }
        error("cannot find $relFromRepoRoot from user.dir")
    }

    private fun baseV2TokenProfile(): ScenarioProfile {
        val raw = repoFile("profiles/v2/token_multimodal_standard/profile.json").readText(Charsets.UTF_8)
        return ProfileParser.parseSingle(raw)
    }

    // --- Legacy v1 / basic_network regression tests (restored from HEAD) ---

    @Test
    fun `known basic profile is executable`() {
        val result = ProfileCapability.assess(
            completeProfile(
                ScenarioProfile.MODE_NETWORK_BASIC,
                listOf(
                    ProfilePhase(ProfilePhase.TYPE_CLOCK_SYNC),
                    ProfilePhase(ProfilePhase.TYPE_DOWNLOAD_THROUGHPUT),
                    ProfilePhase(ProfilePhase.TYPE_UPLOAD_THROUGHPUT),
                ),
            ),
        )
        assertTrue(result.executable)
        assertTrue(result.unsupportedPhaseTypes.isEmpty())
    }

    @Test
    fun `unknown phase requires an engine plugin`() {
        val result = ProfileCapability.assess(
            completeProfile(
                ScenarioProfile.MODE_TOKEN_EXPERIENCE,
                listOf(ProfilePhase("future_transport_probe")),
            ),
        )
        assertFalse(result.executable)
        assertTrue("future_transport_probe" in result.unsupportedPhaseTypes)
    }

    @Test
    fun `incomplete presentation is not executable`() {
        val result = ProfileCapability.assess(
            ScenarioProfile(
                profileId = "incomplete",
                version = "1.0.0",
                description = "测试业务",
                phases = listOf(ProfilePhase(ProfilePhase.TYPE_TOKEN_STREAM)),
            ),
        )
        assertFalse(result.executable)
        assertTrue(result.contractIssues.isNotEmpty())
    }

    @Test
    fun `known token_experience profile is executable and invalid legacy window fails`() {
        val valid = completeProfile(
            ScenarioProfile.MODE_TOKEN_EXPERIENCE,
            listOf(
                ProfilePhase(ProfilePhase.TYPE_CLOCK_SYNC, samples = 5),
                ProfilePhase(ProfilePhase.TYPE_TOKEN_STREAM, tokens = 10, rateTps = 20.0),
            ),
        )
        assertTrue(ProfileCapability.assess(valid).executable)

        val invalidWindow = valid.copy(
            presentation = valid.presentation.copy(liveWindowMs = 0),
        )
        val invalidResult = ProfileCapability.assess(invalidWindow)
        assertFalse(invalidResult.executable)
        assertTrue(invalidResult.contractIssues.isNotEmpty())
    }

    // --- aneb-profile-v2 capability tests ---

    @Test
    fun `complete token_simulation v2 profile with behavior_trace is executable`() {
        val assessment = ProfileCapability.assess(baseV2TokenProfile())
        assertTrue(assessment.executable)
        assertTrue(assessment.unsupportedPhaseTypes.isEmpty())
        assertTrue(assessment.contractIssues.isEmpty())
    }

    @Test
    fun `ai_realtime_simulation and network_comprehensive remain non-executable`() {
        val base = baseV2TokenProfile()
        val realtime = base.copy(modeId = ScenarioProfile.MODE_AI_REALTIME_SIMULATION)
        val comprehensive = base.copy(modeId = ScenarioProfile.MODE_NETWORK_COMPREHENSIVE)

        assertFalse(ProfileCapability.assess(realtime).executable)
        assertFalse(ProfileCapability.assess(comprehensive).executable)
    }

    @Test
    fun `token_simulation fails closed on missing v2 contract missing trace zero seed or mismatched hash`() {
        val valid = baseV2TokenProfile()

        val legacyContract = valid.copy(contractVersion = "aneb-profile-v1")
        assertFalse(ProfileCapability.assess(legacyContract).executable)

        val missingTrace = valid.copy(trace = null)
        assertFalse(ProfileCapability.assess(missingTrace).executable)

        val zeroSeed = valid.copy(
            trace = valid.trace?.copy(seed = 0L),
            phases = valid.phases.map { it.copy(seed = 0L) },
        )
        assertFalse(ProfileCapability.assess(zeroSeed).executable)

        val mismatchedHash = valid.copy(
            phases = valid.phases.map { it.copy(modelHash = "sha256:deadbeef") },
        )
        assertFalse(ProfileCapability.assess(mismatchedHash).executable)

        val unsupportedPhase = valid.copy(
            phases = valid.phases + ProfilePhase(type = ProfilePhase.TYPE_TOOL_LOOP, rounds = 1, upBytes = 1024, downBytes = 1024),
        )
        val unsupportedAssessment = ProfileCapability.assess(unsupportedPhase)
        assertFalse(unsupportedAssessment.executable)
        assertTrue(ProfilePhase.TYPE_TOOL_LOOP in unsupportedAssessment.unsupportedPhaseTypes)
    }

    @Test
    fun `v2 profile fails closed on invalid fail-closed policy group weights seed mismatch or unknown operator`() {
        val valid = baseV2TokenProfile()

        val wrongMissingPolicy = valid.copy(
            evaluation = valid.evaluation.copy(missingRequiredMetric = "ignore"),
        )
        assertFalse(ProfileCapability.assess(wrongMissingPolicy).executable)

        val wrongInvalidRun = valid.copy(
            evaluation = valid.evaluation.copy(invalidRun = "keep_score"),
        )
        assertFalse(ProfileCapability.assess(wrongInvalidRun).executable)

        val wrongLiveMissing = valid.copy(
            livePresentation = valid.livePresentation.copy(missingBehavior = "zero"),
        )
        assertFalse(ProfileCapability.assess(wrongLiveMissing).executable)

        val negativeWeights = valid.copy(
            evaluation = valid.evaluation.copy(
                groupWeights = mapOf("task_completion" to 1.5, "interaction" to -0.5),
            ),
        )
        assertFalse(ProfileCapability.assess(negativeWeights).executable)

        val seedMismatch = valid.copy(
            phases = valid.phases.map { it.copy(seed = (valid.trace?.seed ?: 1L) + 1L) },
        )
        assertFalse(ProfileCapability.assess(seedMismatch).executable)

        val wrongPrng = valid.copy(
            trace = valid.trace?.copy(prng = "mt19937"),
        )
        assertFalse(ProfileCapability.assess(wrongPrng).executable)

        val unknownOp = valid.copy(
            measurements = valid.measurements.map { m ->
                if (m.metricId == "TOK-B12") {
                    m.copy(qualityTarget = m.qualityTarget?.copy(operator = "unknown_operator"))
                } else {
                    m
                }
            },
        )
        val unknownOpAssessment = ProfileCapability.assess(unknownOp)
        assertFalse(unknownOpAssessment.executable)
        assertTrue(unknownOpAssessment.contractIssues.any { it.contains("unknown_operator") })
    }
}
