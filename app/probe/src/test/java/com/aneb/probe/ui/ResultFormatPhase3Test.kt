package com.aneb.probe.ui

import com.aneb.probe.data.ScenarioResultEntity
import com.aneb.probe.data.TestRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 阶段3 遗留接线展示层单测：场景批化标注行（P1-C08；R-05 固定尾注锚点）与
 * AQS v0.2 并列展示行（阶段2 C03；含 continuity 数据来源标注、无数据不显示）。
 */
class ResultFormatPhase3Test {

    private fun scenario(
        bufferingScore: Double? = null,
        bufferingAttribution: String? = null,
    ) = ScenarioResultEntity(
        runId = "run-1", profileId = "s2_coding_agent", profileVersion = "1.0.0",
        repeatIndex = 0, orderIndex = 0, startedAtNanos = 0L, endedAtNanos = 1L,
        validity = "valid", invalidReasons = "",
        t1TtftMs = 150.0, t1Grade = null, t2ItlP95Ms = 80.0, t2Grade = null,
        t2ItlP95InclCoalescedMs = null, t3StallRate = null, t3Grade = null,
        t3StallRateInclResume = null, t4SevereStallRate = null, t4Grade = null,
        t5ResumeP95Ms = null, n1RttP50Ms = null, n1Grade = null,
        n2JitterMs = null, n2Grade = null, u1GoodputMbps = null, u1Grade = null,
        u1GoodputExclSlowStartMbps = null, u2ToolLoopP95Ms = null, u2Grade = null,
        seqGapCount = 0, seqDupCount = 0, lowConfidenceKpis = "",
        offsetStartUs = null, offsetStartErrUs = null, offsetEndUs = null,
        offsetEndErrUs = null, offsetDriftPpm = null, offsetSuspect = false,
        netTransport = null, netCapabilities = null, netInterfaceName = null,
        serverObservedAddr = null, parseDurUsTotal = null, perEventParseUs = null,
        bufferingScore = bufferingScore,
        bufferingAttribution = bufferingAttribution,
    )

    private fun run(
        aqsV02Score: Double? = null,
        aqsV02LowConfidence: Boolean? = null,
        aqsV02NotComputableReason: String? = null,
        aqsV02ContinuityRunId: String? = null,
        aqsV02ContinuityStartedAtEpochMs: Long? = null,
        aqsV02C1DropRate: Double? = null,
        aqsV02C2RecoveryMs: Double? = null,
    ) = TestRun(
        runId = "run-1", startedAtEpochMs = 1_752_000_000_000L,
        serverBase = "http://10.0.2.2:8443", mode = "quick",
        scenarioOrder = "s1_chat", transport = "auto",
        kpiSet = "agent-qoe-kpi-v0.2", aqsVersion = "aqs-v0.1",
        profileVersions = "s1_chat:1.0.0", schemaVersion = "1.0",
        profileSource = "server", appVersionName = "0.1.0", appVersionCode = 1L,
        guardMetadata = null, aqsScore = 88.5, aqsLowConfidence = false,
        aqsVetoApplied = false, aqsNotComputableReason = null,
        status = "completed", reportStatus = "http=200",
        aqsV02Score = aqsV02Score,
        aqsV02LowConfidence = aqsV02LowConfidence,
        aqsV02NotComputableReason = aqsV02NotComputableReason,
        aqsV02ContinuityRunId = aqsV02ContinuityRunId,
        aqsV02ContinuityStartedAtEpochMs = aqsV02ContinuityStartedAtEpochMs,
        aqsV02C1DropRate = aqsV02C1DropRate,
        aqsV02C2RecoveryMs = aqsV02C2RecoveryMs,
    )

    // ---------- 批化标注（R-05） ----------

    @Test
    fun bufferingLabelIsNullWhenNotAnalyzed() {
        // 未检测（score=null，如流失败）→ 不显示（R-10：绝不显示 0）
        assertNull(ResultFormat.bufferingLabel(scenario()))
    }

    @Test
    fun bufferingLabelCarriesScoreAttributionAndR05Note() {
        val label = ResultFormat.bufferingLabel(
            scenario(bufferingScore = 0.4567, bufferingAttribution = "airlink_suspect")
        )!!
        assertEquals("buffering=0.457 attribution=airlink_suspect（标注不改有效性判定）", label)
        assertTrue(label.contains(ResultFormat.BUFFERING_NOTE)) // R-05 展示锚点
    }

    @Test
    fun bufferingLabelIsLocaleIndependent() {
        val prev = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY) // 逗号小数区域
            val label = ResultFormat.bufferingLabel(scenario(bufferingScore = 0.5))!!
            assertTrue(label.startsWith("buffering=0.500 "))
        } finally {
            Locale.setDefault(prev)
        }
    }

    // ---------- AQS v0.2 并列展示 ----------

    @Test
    fun aqsV02LinesNullWithoutContinuityBranchKeepsV01Semantics() {
        // 无 v0.2 分支（run 期无可用 continuity 数据）→ null，只显 v0.1
        assertNull(ResultFormat.aqsV02Lines(run()))
    }

    @Test
    fun aqsV02LinesShowScoreGradeAndContinuityProvenance() {
        val lines = ResultFormat.aqsV02Lines(
            run(
                aqsV02Score = 82.3,
                aqsV02LowConfidence = false,
                aqsV02ContinuityRunId = "abcdef12-3456",
                aqsV02ContinuityStartedAtEpochMs = 1_752_000_000_000L,
                aqsV02C1DropRate = 0.01,
                aqsV02C2RecoveryMs = 1500.0,
            )
        )!!
        assertEquals(2, lines.size)
        assertEquals("AQS v0.2 = 82.3（良）", lines[0])
        // v0.2 必须标注所用 continuity 数据的 C1/C2 值与时间（任务合同）
        assertTrue(lines[1].contains("C1=1.00%"))
        assertTrue(lines[1].contains("C2=1500 ms"))
        assertTrue(lines[1].contains("run=abcdef12"))
        assertTrue(Regex("@\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}").containsMatchIn(lines[1]))
    }

    @Test
    fun aqsV02LinesLowConfidenceCarriesLabel() {
        val lines = ResultFormat.aqsV02Lines(
            run(
                aqsV02Score = 60.0, aqsV02LowConfidence = true,
                aqsV02ContinuityRunId = "c-run", aqsV02ContinuityStartedAtEpochMs = 0L,
                aqsV02C1DropRate = 0.0, aqsV02C2RecoveryMs = 100.0,
            )
        )!!
        assertTrue(lines[0].contains(ResultFormat.LOW_CONFIDENCE_LABEL))
    }

    @Test
    fun aqsV02LinesNotComputableBranchShowsReasonNeverZero() {
        val lines = ResultFormat.aqsV02Lines(
            run(
                aqsV02Score = null,
                aqsV02NotComputableReason = "KPI_MISSING:T1",
                aqsV02ContinuityRunId = "c-run",
                aqsV02ContinuityStartedAtEpochMs = 0L,
                aqsV02C1DropRate = 0.01, aqsV02C2RecoveryMs = 1500.0,
            )
        )!!
        assertEquals("AQS v0.2 不可计算：KPI_MISSING:T1", lines[0])
        assertTrue(!lines[0].contains("0.0")) // R-10：不可计算绝不显示为 0 分
    }
}
