package com.aneb.probe.engine

import com.aneb.probe.data.ScenarioResultEntity
import com.aneb.probe.data.TestRun
import com.aneb.probe.scoring.AqsScorer
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 隐私边界锚定单测（阶段3 GPS 路测，设计文档 §9.1）：/results 上报体**绝不含坐标字段**。
 *
 * GPS lat/lon/accuracy 只存在于 radio_sample 本地表与本地轨迹导出；ResultReporter
 * 的输入类型（TestRun / ScenarioResultEntity / ItlHistogram / AqsResult）本身不携带
 * 坐标，本测锚定输出 JSON 的键集合中没有任何坐标类键——若未来有人把坐标接进上报体，
 * 此测立即红灯。
 */
class ReportBodyNoGpsTest {

    private fun scenario() = ScenarioResultEntity(
        runId = "run-1", profileId = "s1_chat", profileVersion = "1.0.0",
        repeatIndex = 0, orderIndex = 0, startedAtNanos = 0L, endedAtNanos = 1L,
        validity = "valid", invalidReasons = "",
        t1TtftMs = 150.0, t1Grade = "excellent", t2ItlP95Ms = 80.0, t2Grade = "excellent",
        t2ItlP95InclCoalescedMs = null, t3StallRate = 0.0, t3Grade = "excellent",
        t3StallRateInclResume = null, t4SevereStallRate = 0.0, t4Grade = "excellent",
        t5ResumeP95Ms = null, n1RttP50Ms = 20.0, n1Grade = "excellent",
        n2JitterMs = 3.0, n2Grade = "excellent", u1GoodputMbps = 50.0, u1Grade = "excellent",
        u1GoodputExclSlowStartMbps = null, u2ToolLoopP95Ms = null, u2Grade = null,
        seqGapCount = 0, seqDupCount = 0, lowConfidenceKpis = "",
        offsetStartUs = 100L, offsetStartErrUs = 10L, offsetEndUs = 110L,
        offsetEndErrUs = 10L, offsetDriftPpm = 1.0, offsetSuspect = false,
        netTransport = "wifi", netCapabilities = "caps", netInterfaceName = "wlan0",
        serverObservedAddr = "10.0.2.16:1234", parseDurUsTotal = 1000L, perEventParseUs = 2.0,
        bufferingScore = 0.1, bufferingAttribution = "none", bufferingSampleCount = 100,
    )

    private fun run() = TestRun(
        runId = "run-1", startedAtEpochMs = 1_752_000_000_000L,
        serverBase = "http://10.0.2.2:8443", mode = "quick",
        scenarioOrder = "s1_chat,s2_coding_agent,s3_multimodal", transport = "auto",
        kpiSet = "agent-qoe-kpi-v0.2", aqsVersion = "aqs-v0.1",
        profileVersions = "s1_chat@0.2.0", schemaVersion = "1.0",
        profileSource = "server", appVersionName = "0.3.0", appVersionCode = 1L,
        guardMetadata = "private_dns_active=false", aqsScore = 88.5, aqsLowConfidence = false,
        aqsVetoApplied = false, aqsNotComputableReason = null,
        status = "completed", reportStatus = null,
    )

    @Test
    fun `上报体不含任何坐标类 JSON 键`() {
        val body = ResultReporter.build(
            run = run(),
            scenarios = listOf(scenario() to ItlHistogram.of(listOf(50.0, 80.0, 120.0))),
            aqs = AqsScorer.AqsResult(
                aqsVersion = "aqs-v0.1", kpiSetVersion = "agent-qoe-kpi-v0.2",
                score = 88.5, subScores = mapOf("T" to 90.0),
                vetoApplied = false, lowConfidence = false, notComputableReason = null,
            ),
        )
        // JSON 键级匹配（不误伤值内子串）：lat / lon / latitude / longitude / gps /
        // accuracy* / location / geo* 任何一个键出现即违反 §9.1
        val forbiddenKey = Regex(
            "\"(lat|lon|latitude|longitude|gps|accuracy[A-Za-z_]*|location|geo[A-Za-z_]*)\"\\s*:",
            RegexOption.IGNORE_CASE,
        )
        assertTrue(
            "上报体含坐标类键（违反 §9.1 隐私边界）: ${forbiddenKey.find(body)?.value}",
            forbiddenKey.find(body) == null,
        )
        // 自检：上报体本身构造成功且含合同字段（防空串假绿）
        assertTrue(body.contains("\"claim_scope\""))
        assertTrue(body.contains("\"scenarios\""))
    }
}
