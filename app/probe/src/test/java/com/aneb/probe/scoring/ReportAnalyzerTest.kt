package com.aneb.probe.scoring

import com.aneb.probe.scoring.ReportAnalyzer.Method
import com.aneb.probe.scoring.ReportAnalyzer.RunSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ReportAnalyzer 单测（分析层 ③；纯 JVM）。
 *
 * 夹具取自 evidence/phase3/netem_experiments_20260713.md 的**真实 netem 实测数字**：
 * - 对照组（clean）：RTT ~4.7–4.9ms、TTFT S1 7.3–18.2ms、U1 S3 23.4–23.8Mbps、AQS 96.3/97.1、stall≈0、loss 0%。
 * - 注入组（delay100ms+loss1%）：RTT ~105ms、TTFT S1 104–127ms、U1 5.2–11.9Mbps、AQS 86.0/86.1、loss 1%。
 * - 跨境组（delay150±10ms+loss0.5%）：RTT ~153–159ms、TTFT ~154–164ms、U1 8.2–8.5Mbps、AQS 80.0/80.9、stall 0.005–0.010、loss 0.5%。
 *
 * 覆盖：分组敏感度方向与量级、相关模式回归、样本不足降级、token 派生带估算标记、
 * claim scope 文案在位、INVALID 剔除、趋势序列 null 保断点、无网络条件不硬凑。
 */
class ReportAnalyzerTest {

    // ---- 真实实测夹具 ----
    private fun cleanRuns() = listOf(
        RunSummary("c1", "wifi", rttMs = 4.71, jitterMs = 1.66, lossPct = 0.0,
            ttftMs = 18.20, itlP95Ms = 25.46, stallRate = 0.0, upMbps = 23.38, aqs = 96.3,
            netemProfile = "clean", epochMs = 1000),
        RunSummary("c2", "wifi", rttMs = 4.92, jitterMs = 0.83, lossPct = 0.0,
            ttftMs = 7.33, itlP95Ms = 25.42, stallRate = 0.0, upMbps = 23.75, aqs = 97.1,
            netemProfile = "clean", epochMs = 2000),
    )

    private fun injectedRuns() = listOf(
        RunSummary("i1", "wifi", rttMs = 105.06, jitterMs = 1.04, lossPct = 1.0,
            ttftMs = 127.18, itlP95Ms = 35.02, stallRate = 0.0, upMbps = 11.87, aqs = 86.0,
            netemProfile = "delay100_loss1", validity = Validity.VALID_LOW_CONFIDENCE, epochMs = 3000),
        RunSummary("i2", "wifi", rttMs = 105.13, jitterMs = 2.21, lossPct = 1.0,
            ttftMs = 104.34, itlP95Ms = 25.52, stallRate = 0.001, upMbps = 5.21, aqs = 86.1,
            netemProfile = "delay100_loss1", validity = Validity.VALID_LOW_CONFIDENCE, epochMs = 4000),
    )

    private fun crossBorderRuns() = listOf(
        RunSummary("x1", "wifi", rttMs = 153.0, jitterMs = 13.1, lossPct = 0.5,
            ttftMs = 159.2, itlP95Ms = 52.5, stallRate = 0.005, upMbps = 8.5, aqs = 80.0,
            netemProfile = "cross_border", validity = Validity.VALID_LOW_CONFIDENCE, epochMs = 5000),
        RunSummary("x2", "wifi", rttMs = 159.4, jitterMs = 28.6, lossPct = 0.5,
            ttftMs = 164.4, itlP95Ms = 55.1, stallRate = 0.010, upMbps = 8.2, aqs = 80.9,
            netemProfile = "cross_border", validity = Validity.VALID_LOW_CONFIDENCE, epochMs = 6000),
    )

    private fun findingOf(a: ReportAnalyzer.ReportAnalysis, driver: String, metric: String) =
        a.sensitivity.first { it.driver == driver && it.metric == metric }

    // 1. 分组模式：RTT↑ → TTFT↑（方向 + 量级 ≈注入单程 100ms）
    @Test
    fun grouped_rttUp_ttftUp_directionAndMagnitude() {
        val a = ReportAnalyzer.analyze(cleanRuns() + injectedRuns())
        assertEquals(Method.GROUPED, a.method)
        val f = findingOf(a, "rtt", "ttft")
        // 基线 RTT ~4.8ms → 注入 ~105ms
        assertEquals(4.815, f.driverFrom, 0.01)
        assertEquals(105.095, f.driverTo, 0.01)
        // TTFT 12.765 → 115.76，绝对增量≈+103ms（≈注入单程 100ms 量级）
        assertTrue("TTFT 应显著上升", f.absDelta > 0)
        assertEquals(102.995, f.absDelta, 0.01)
        assertTrue("增量应在注入单程 100ms 量级(90–120ms)", f.absDelta in 90.0..120.0)
        assertNotNull(f.pctDelta)
        assertTrue("相对增量应 >>100%", f.pctDelta!! > 100.0)
    }

    // 2. 分组模式：RTT↑ / loss↑ → 有效上行吞吐↓ ~-64%（loss1% 触发拥塞窗口收缩）
    @Test
    fun grouped_impairment_upThroughputDown() {
        val a = ReportAnalyzer.analyze(cleanRuns() + injectedRuns())
        val f = findingOf(a, "rtt", "up")
        // 23.565 → 8.54，约 -64%
        assertTrue("上行吞吐应下降", f.absDelta < 0)
        assertEquals(-63.76, f.pctDelta!!, 0.5)
    }

    // 3. 分组模式：RTT 与 loss 同变时两驱动都出结论（单组无法独立归因，如实并列）
    @Test
    fun grouped_bothDriversReported() {
        val a = ReportAnalyzer.analyze(cleanRuns() + injectedRuns())
        assertTrue(a.sensitivity.any { it.driver == "rtt" })
        assertTrue(a.sensitivity.any { it.driver == "loss" })
        // loss 0%→1% → stall 结论存在且方向非负
        val stallF = findingOf(a, "loss", "stall")
        assertEquals(0.0, stallF.driverFrom, 1e-9)
        assertEquals(1.0, stallF.driverTo, 1e-9)
        assertTrue(stallF.absDelta >= 0.0)
    }

    // 4. AQS 掉档量级：clean ~96.7 → 注入 ~86.05（约 -10.6 分，方向正确）
    @Test
    fun grouped_aqsDrop() {
        val a = ReportAnalyzer.analyze(cleanRuns() + injectedRuns())
        val f = findingOf(a, "rtt", "aqs")
        assertEquals(96.7, f.metricFrom, 0.01)
        assertEquals(86.05, f.metricTo, 0.01)
        assertEquals(-10.65, f.absDelta, 0.01)
    }

    // 5. 相关模式（无 netem 剖面，≥3 不同 RTT 条件）：回归斜率正、Pearson 高
    @Test
    fun correlation_mode_regression() {
        val runs = listOf(
            RunSummary("r1", "cellular", rttMs = 5.0, ttftMs = 10.0, upMbps = 24.0, aqs = 97.0),
            RunSummary("r2", "cellular", rttMs = 55.0, ttftMs = 60.0, upMbps = 18.0, aqs = 90.0),
            RunSummary("r3", "cellular", rttMs = 105.0, ttftMs = 112.0, upMbps = 9.0, aqs = 86.0),
            RunSummary("r4", "cellular", rttMs = 155.0, ttftMs = 160.0, upMbps = 8.3, aqs = 80.0),
        )
        val a = ReportAnalyzer.analyze(runs)
        assertEquals(Method.CORRELATION, a.method)
        val f = findingOf(a, "rtt", "ttft")
        assertNotNull(f.pearson)
        assertTrue("RTT↔TTFT 应强正相关", f.pearson!! > 0.99)
        assertNotNull(f.slopePerUnit)
        assertTrue("斜率约 1（每 +1ms RTT ~+1ms TTFT）", f.slopePerUnit!! in 0.9..1.1)
    }

    // 6. 样本不足：仅 1 个网络条件 → 降级 INSUFFICIENT，出"需更多样本"文案，不硬凑趋势
    @Test
    fun insufficient_singleCondition_degrades() {
        val a = ReportAnalyzer.analyze(cleanRuns()) // 同一 clean 剖面
        assertEquals(Method.INSUFFICIENT, a.method)
        assertTrue(a.sensitivity.isEmpty())
        assertTrue(a.conclusions.any { it.contains("样本不足") && it.contains("不同网络条件=1") })
    }

    // 7. token 派生投影：带估算标记 + HALO 外推区间（丢包增量 1% → 每 token TPOT ~308.6–617.2ms）
    @Test
    fun tokenProjection_derivedMarker_andHaloInterval() {
        val a = ReportAnalyzer.analyze(cleanRuns() + injectedRuns(), sessionDropRate = 0.05)
        val p = a.tokenProjection
        assertTrue(p.estimate)
        assertEquals("派生/估算,非直接测量", p.marker)
        // 丢包增量 0→1% = 1.0
        assertEquals(1.0, p.lossPctDelta!!, 1e-9)
        // HALO 617.2ms/1% × [0.5,1.0]
        assertEquals(308.6, p.tpotElongationMsLow!!, 0.01)
        assertEquals(617.2, p.tpotElongationMsHigh!!, 0.01)
        // 会话中断率 5% × 上下文[5万,20万]
        assertEquals(2500.0, p.uplinkResendTokensLow!!, 1e-6)
        assertEquals(10000.0, p.uplinkResendTokensHigh!!, 1e-6)
        assertTrue(p.literatureAnchors.any { it.name == "HALO" })
        assertTrue(p.literatureAnchors.any { it.name == "Eloquent" })
        // 结论文案里派生投影恒带标记
        assertTrue(a.conclusions.any { it.contains("派生/估算，非直接测量") })
    }

    // 8. token 投影：无会话中断率实测时如实标注、不投影上行重发分量
    @Test
    fun tokenProjection_noDropRate_honest() {
        val a = ReportAnalyzer.analyze(cleanRuns() + injectedRuns()) // sessionDropRate 缺省
        val p = a.tokenProjection
        assertNull(p.sessionDropRate)
        assertNull(p.uplinkResendTokensLow)
        assertTrue(p.note.contains("无会话中断率实测"))
    }

    // 9. claim scope 文案恒在结论末尾在位（不外推全网/运营商）
    @Test
    fun claimScope_noteAlwaysPresent() {
        val a = ReportAnalyzer.analyze(cleanRuns() + injectedRuns())
        assertEquals(ReportAnalyzer.CLAIM_SCOPE, a.claimScope)
        val last = a.conclusions.last()
        assertTrue(last.contains("应用层端到端路径"))
        assertTrue(last.contains("不外推"))
    }

    // 10. INVALID run 被剔除、不进聚合；趋势序列以 null 保断点绝不填 0
    @Test
    fun invalidExcluded_andTrendsPreserveNullGaps() {
        val invalid = RunSummary("bad", "wifi", rttMs = 999.0, ttftMs = null, upMbps = null,
            aqs = null, netemProfile = "delay100_loss1", validity = Validity.INVALID, epochMs = 3500)
        val a = ReportAnalyzer.analyze(cleanRuns() + injectedRuns() + invalid)
        assertEquals("INVALID 不计入有效 run", 4, a.validRunCount)
        // 趋势不含被剔除的 bad
        assertFalse(a.trends.orderedRunIds.contains("bad"))
        // rsrp 全缺失 → 该序列全 null（保断点，不 0 顶替）
        val rsrp = a.trends.series.first { it.metric == "rsrp" }
        assertTrue(rsrp.values.all { it == null })
    }

    // 11. 三剖面（clean/injected/cross_border）：基线自动取 RTT 最小组，跨境组量级更大
    @Test
    fun threeProfiles_baselineIsBestNetwork() {
        val a = ReportAnalyzer.analyze(cleanRuns() + injectedRuns() + crossBorderRuns())
        assertEquals(Method.GROUPED, a.method)
        assertEquals(3, a.distinctConditionCount)
        // 基线=clean(RTT~4.8)；跨境组 driverTo 应 ~156ms
        val xb = a.sensitivity.first { it.driver == "rtt" && it.metric == "ttft" && it.driverTo > 150.0 }
        assertEquals(4.815, xb.driverFrom, 0.01)
        assertTrue(xb.driverTo in 150.0..162.0)
        assertTrue("跨境 TTFT 增量应更大", xb.absDelta > 140.0)
    }

    // 12. 完全无网络条件数据：不硬凑，降级 INSUFFICIENT
    @Test
    fun noNetworkCondition_noFabrication() {
        val runs = listOf(
            RunSummary("n1", "wifi", ttftMs = 10.0, aqs = 95.0),
            RunSummary("n2", "wifi", ttftMs = 12.0, aqs = 94.0),
        )
        val a = ReportAnalyzer.analyze(runs)
        assertEquals(Method.INSUFFICIENT, a.method)
        assertTrue(a.sensitivity.isEmpty())
    }
}
