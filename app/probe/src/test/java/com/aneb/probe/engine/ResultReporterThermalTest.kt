package com.aneb.probe.engine

import com.aneb.probe.data.ScenarioResultEntity
import com.aneb.probe.data.TestRun
import com.aneb.probe.scoring.AqsScorer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THERMAL 接线（D-556）：`run.env` 块的序列化守卫。
 */
class ResultReporterThermalTest {

    private fun scenario() = ScenarioResultEntity(
        runId = "run-1", profileId = "s1_chat", profileVersion = "1.0.0",
        repeatIndex = 0, orderIndex = 0, startedAtNanos = 0L, endedAtNanos = 1L,
        validity = "valid", invalidReasons = "",
        t1TtftMs = null, t1Grade = null, t2ItlP95Ms = null, t2Grade = null,
        t2ItlP95InclCoalescedMs = null, t3StallRate = null, t3Grade = null,
        t3StallRateInclResume = null, t4SevereStallRate = null, t4Grade = null,
        t5ResumeP95Ms = null, n1RttP50Ms = 20.0, n1Grade = "excellent",
        n2JitterMs = 3.0, n2Grade = "excellent", u1GoodputMbps = 50.0, u1Grade = "excellent",
        u1GoodputExclSlowStartMbps = null, u2ToolLoopP95Ms = null, u2Grade = null,
        seqGapCount = 0, seqDupCount = 0, lowConfidenceKpis = "",
        offsetStartUs = 100L, offsetStartErrUs = 10L, offsetEndUs = 110L,
        offsetEndErrUs = 10L, offsetDriftPpm = 1.0, offsetSuspect = false,
        netTransport = "wifi", netCapabilities = "caps", netInterfaceName = "if0",
        serverObservedAddr = "203.0.113.7:8443", parseDurUsTotal = null, perEventParseUs = null,
    )

    private fun run() = TestRun(
        runId = "run-1", startedAtEpochMs = 1_752_000_000_000L,
        serverBase = "http://10.0.2.2:8443", mode = "quick",
        scenarioOrder = "s1_chat,s2_coding_agent,s3_multimodal", transport = "auto",
        kpiSet = "agent-qoe-kpi-v0.2", aqsVersion = "aqs-v0.1",
        profileVersions = "s1_chat@0.2.0", schemaVersion = "1.0",
        profileSource = "server", appVersionName = "0.3.0", appVersionCode = 1L,
        guardMetadata = null, aqsScore = 88.5, aqsLowConfidence = false,
        aqsVetoApplied = false, aqsNotComputableReason = null,
        status = "completed", reportStatus = null,
    )

    private fun body(env: ThermalSummary.Env? = null): String = ResultReporter.build(
        run = run(),
        scenarios = listOf(scenario() to ItlHistogram.of(emptyList())),
        aqs = AqsScorer.AqsResult(
            aqsVersion = "aqs-v0.1", kpiSetVersion = "agent-qoe-kpi-v0.2",
            score = 88.5, subScores = mapOf("T" to 90.0),
            vetoApplied = false, lowConfidence = false, notComputableReason = null,
        ),
        env = env,
    )

    @Test
    fun `env 参数缺省 → wire 无 env 键——老调用形状一字节不变`() {
        assertFalse("param 默认 null 必须保持块缺席（=早于上线的老形状）", body().contains("\"env\":"))
    }

    @Test
    fun `监控在位且干净 → none 加 0——0 是真实读数，真的落 wire`() {
        val b = body(ThermalSummary.Env("none", 0))
        assertTrue(b.contains("\"thermal_max_status\":\"none\""))
        assertTrue(b.contains("\"thermal_polluting_event_count\":0"))
    }

    @Test
    fun `无监控 → 双 JsonNull 真的落 wire——键在值 null，不是键消失（R-10）`() {
        val b = body(ThermalSummary.Env(null, null))
        assertTrue(b.contains("\"thermal_max_status\":null"))
        assertTrue(b.contains("\"thermal_polluting_event_count\":null"))
    }

    @Test
    fun `有污染的 run 自证——severe 与计数逐字落 wire`() {
        val b = body(ThermalSummary.Env("severe", 3))
        assertTrue(b.contains("\"thermal_max_status\":\"severe\""))
        assertTrue(b.contains("\"thermal_polluting_event_count\":3"))
    }
}
