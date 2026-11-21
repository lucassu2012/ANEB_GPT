package com.aneb.probe.ui

import com.aneb.probe.data.BasicSpeedResultEntity
import com.aneb.probe.data.TestRun
import com.aneb.probe.data.VoiceResultEntity
import com.aneb.probe.engine.LoadedResponsivenessSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BusinessExperienceEvaluatorTest {

    @Test
    fun `douyin evaluator aligns with P40 Pro 1_5M 2_0M and 4_0M calibrated inflections`() {
        assertEquals("待实测", BusinessExperienceEvaluator.evaluateDouyinMetrics(null, 20.0, 0.0).badge)
        assertEquals(
            BusinessExperienceEvaluator.Tone.POOR,
            BusinessExperienceEvaluator.evaluateDouyinMetrics(1.0, 40.0, 0.0).tone,
        )
        assertEquals(
            BusinessExperienceEvaluator.Tone.CAUTION,
            BusinessExperienceEvaluator.evaluateDouyinMetrics(1.5, 40.0, 0.0).tone,
        )
        assertEquals(
            BusinessExperienceEvaluator.Tone.GOOD,
            BusinessExperienceEvaluator.evaluateDouyinMetrics(2.5, 60.0, 0.01).tone,
        )
        assertEquals(
            BusinessExperienceEvaluator.Tone.EXCELLENT,
            BusinessExperienceEvaluator.evaluateDouyinMetrics(12.0, 180.0, 0.04).tone,
        )
    }

    @Test
    fun `doubao chat evaluator uses Token AQS when present and falls back to basic cliff honestly`() {
        val run = TestRun(
            runId = "t-1",
            startedAtEpochMs = 1L,
            serverBase = "https://120.77.174.207:8443",
            mode = "quick",
            scenarioOrder = "s1_chat",
            transport = "wifi",
            kpiSet = "agent-qoe-kpi@0.1",
            aqsVersion = "aqs-v0.1",
            profileVersions = "s1_chat@0.1.0",
            schemaVersion = "1.0",
            profileSource = "server",
            appVersionName = "0.2.0",
            appVersionCode = 20L,
            guardMetadata = "",
            aqsScore = 91.2,
            aqsLowConfidence = false,
            aqsVetoApplied = false,
            aqsNotComputableReason = null,
            status = "completed",
            reportStatus = "ok",
        )
        val highVerdict = BusinessExperienceEvaluator.evaluateDoubaoChat(run)
        assertEquals(BusinessExperienceEvaluator.Tone.EXCELLENT, highVerdict.tone)
        assertEquals("跟手流畅", highVerdict.badge)

        val starvedBasic = BasicSpeedResultEntity(
            runId = "b-1",
            startedAtEpochMs = 1L,
            serverBase = "https://120.77.174.207:8443",
            claimScope = "application_end_to_end_to_probe_node",
            profileId = "basic_network",
            profileVersion = "0.1.0",
            conclusionPolicyId = BasicSpeedConclusions.POLICY_ID,
            status = "completed",
            downloadMbps = 0.25,
            uploadMbps = 0.2,
            pingMs = 350.0,
            jitterMs = 80.0,
            requestLossRate = 0.05,
            postLoadPingMs = 400.0,
            downloadBytes = 1024L,
            uploadBytes = 1024L,
            transferErrors = "",
        )
        val starvedVerdict = BusinessExperienceEvaluator.evaluateDoubaoChat(null, emptyList(), starvedBasic)
        assertEquals(BusinessExperienceEvaluator.Tone.POOR, starvedVerdict.tone)
        assertEquals("极易饿死", starvedVerdict.badge)
    }

    @Test
    fun `voice evaluator enforces 400ms mouth-to-ear veto and refuses to fabricate when unmeasured`() {
        val unmeasured = BusinessExperienceEvaluator.evaluateVoice(null)
        assertEquals(BusinessExperienceEvaluator.Tone.NEUTRAL, unmeasured.tone)
        assertTrue(unmeasured.detail.contains("不能替代"))

        val smooth = voiceRow(mouthEarProxyMs = 218.0, m7GapMs = 60.0)
        assertEquals(BusinessExperienceEvaluator.Tone.EXCELLENT, BusinessExperienceEvaluator.evaluateVoice(smooth).tone)

        val vetoed = voiceRow(mouthEarProxyMs = 706.0, m7GapMs = 340.0)
        assertEquals(BusinessExperienceEvaluator.Tone.POOR, BusinessExperienceEvaluator.evaluateVoice(vetoed).tone)
    }

    @Test
    fun `loaded responsiveness evaluator categorizes bufferbloat deltas`() {
        val good = LoadedResponsivenessSummary(30.0, 50.0, 55.0, 20.0, 25.0, emptyList())
        assertEquals(BusinessExperienceEvaluator.Tone.EXCELLENT, BusinessExperienceEvaluator.evaluateLoadedResponsiveness(good).tone)

        val bloated = LoadedResponsivenessSummary(30.0, 380.0, 120.0, 350.0, 90.0, emptyList())
        assertEquals(BusinessExperienceEvaluator.Tone.POOR, BusinessExperienceEvaluator.evaluateLoadedResponsiveness(bloated).tone)
    }

    private fun voiceRow(mouthEarProxyMs: Double, m7GapMs: Double) = VoiceResultEntity(
        id = 1L,
        tsEpochMs = 1_700_000_000_000L,
        caliber = "server-sim",
        lowConfidence = false,
        rttMs = 50.0,
        jitterMs = 4.0,
        upFrameJitterMs = 4.0,
        downFrameJitterMs = 5.0,
        mouthEarBudgetMs = mouthEarProxyMs,
        framesSent = 100,
        framesRecv = 100,
        ttfbP50Ms = 120.0,
        ttfbP95Ms = 180.0,
        downNetJitterMs = 5.0,
        mouthEarProxyMs = mouthEarProxyMs,
        turnSwitchP50Ms = 200.0,
        bargeStopMaxMs = 90.0,
        turnsOk = 8,
        m7MaxFrameGapMs = m7GapMs,
        voiceNearZeroArrivalRatio = 0.0,
    )
}
