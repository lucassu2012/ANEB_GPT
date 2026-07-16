package com.aneb.probe.ui

import com.aneb.probe.data.ScenarioResultEntity
import com.aneb.probe.data.TestRun
import com.aneb.probe.scoring.Validity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ReportMapper 纯映射单测：Room 落库实体 → ReportAnalyzer.RunSummary。
 * 锚定来源场景映射（T←S2 / N←S1 / U1←S3）、null 语义（R-10 绝不 0）、有效性降级。
 */
class ReportMapperTest {

    private fun run(
        runId: String = "r1",
        transport: String = "cellular",
        aqs: Double? = 72.0,
        lowConf: Boolean? = false,
        c1: Double? = null,
        epoch: Long = 1_000L,
    ) = TestRun(
        runId = runId,
        startedAtEpochMs = epoch,
        serverBase = "https://x",
        mode = "quick",
        scenarioOrder = "s1_chat,s2_coding_agent,s3_multimodal",
        transport = transport,
        kpiSet = "agent-qoe-kpi-v0.1",
        aqsVersion = "aqs-v0.1",
        profileVersions = "1.0.0",
        schemaVersion = "11",
        profileSource = "server",
        appVersionName = "0.1.0",
        appVersionCode = 1L,
        guardMetadata = null,
        aqsScore = aqs,
        aqsLowConfidence = lowConf,
        aqsVetoApplied = false,
        aqsNotComputableReason = null,
        status = "completed",
        reportStatus = "200",
        aqsV02C1DropRate = c1,
    )

    private fun scenario(
        profileId: String,
        t1: Double? = null,
        t2: Double? = null,
        t3: Double? = null,
        u1: Double? = null,
        n1: Double? = null,
        n2: Double? = null,
    ) = ScenarioResultEntity(
        runId = "r1",
        profileId = profileId,
        profileVersion = "1.0.0",
        repeatIndex = 0,
        orderIndex = 0,
        startedAtNanos = 0L,
        endedAtNanos = 1L,
        validity = "valid",
        invalidReasons = "",
        t1TtftMs = t1, t1Grade = null,
        t2ItlP95Ms = t2, t2Grade = null,
        t2ItlP95InclCoalescedMs = null,
        t3StallRate = t3, t3Grade = null,
        t3StallRateInclResume = null,
        t4SevereStallRate = null, t4Grade = null,
        t5ResumeP95Ms = null,
        n1RttP50Ms = n1, n1Grade = null,
        n2JitterMs = n2, n2Grade = null,
        u1GoodputMbps = u1, u1Grade = null,
        u1GoodputExclSlowStartMbps = null,
        u2ToolLoopP95Ms = null, u2Grade = null,
        seqGapCount = 0,
        seqDupCount = 0,
        offsetStartUs = null, offsetStartErrUs = null,
        offsetEndUs = null, offsetEndErrUs = null,
        offsetDriftPpm = null,
        offsetSuspect = false,
        netTransport = null,
        netCapabilities = null,
        netInterfaceName = null,
        serverObservedAddr = null,
        parseDurUsTotal = null,
        perEventParseUs = null,
    )

    @Test
    fun mapsMetricsFromSourceScenarios() {
        val scenarios = listOf(
            scenario("s1_chat", n1 = 30.0, n2 = 4.0),
            scenario("s2_coding_agent", t1 = 800.0, t2 = 120.0, t3 = 0.02),
            scenario("s3_multimodal", u1 = 12.5),
        )
        val s = ReportMapper.toRunSummary(run(), scenarios)
        assertEquals(800.0, s.ttftMs!!, 1e-9)
        assertEquals(120.0, s.itlP95Ms!!, 1e-9)
        assertEquals(0.02, s.stallRate!!, 1e-9)
        assertEquals(12.5, s.upMbps!!, 1e-9)
        assertEquals(30.0, s.rttMs!!, 1e-9)
        assertEquals(4.0, s.jitterMs!!, 1e-9)
        assertEquals(72.0, s.aqs!!, 1e-9)
        assertEquals("cellular", s.transport)
        assertNull("真机无 netem 剖面", s.netemProfile)
        assertNull("丢包未注入 → null（绝不 0）", s.lossPct)
        assertEquals(Validity.VALID, s.validity)
    }

    @Test
    fun medianAcrossRepeats() {
        // 取证模式同 profileId 多遍 → 中位数
        val scenarios = listOf(
            scenario("s2_coding_agent", t1 = 100.0),
            scenario("s2_coding_agent", t1 = 300.0),
            scenario("s2_coding_agent", t1 = 200.0),
        )
        val s = ReportMapper.toRunSummary(run(aqs = null), scenarios)
        assertEquals(200.0, s.ttftMs!!, 1e-9)
    }

    @Test
    fun allNullBecomesInvalid() {
        val s = ReportMapper.toRunSummary(run(aqs = null), emptyList())
        assertEquals(Validity.INVALID, s.validity)
        assertNull(s.ttftMs)
    }

    @Test
    fun lowConfidencePropagates() {
        val scenarios = listOf(scenario("s2_coding_agent", t1 = 500.0))
        val s = ReportMapper.toRunSummary(run(lowConf = true), scenarios)
        assertEquals(Validity.VALID_LOW_CONFIDENCE, s.validity)
    }
}
