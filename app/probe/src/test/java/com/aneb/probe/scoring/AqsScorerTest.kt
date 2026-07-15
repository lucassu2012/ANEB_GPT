package com.aneb.probe.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AqsScorer 单测（aqs v0.1）。
 * 覆盖：门限边界值打分（每档位边界分数 = 85/70/55）、区间线性插值、端点 clamp、
 * T4 一票否决、INVALID 不出分、低置信传播、权重合计、缺失 KPI 不可计算（R-10/R-28）。
 */
class AqsScorerTest {

    private fun kv(v: Double?, unit: String = "ms", n: Int = 20, low: Boolean = false) =
        KpiValue(v, unit, n, low)

    /** 默认全部取"优/良"边界值（每项子分应为 85）。 */
    private fun kpiResult(
        t1: Double? = 200.0,
        t2: Double? = 100.0,
        t3: Double? = 0.005,
        t4: Double? = 0.0,
        u1: Double? = 20.0,
        u2: Double? = 150.0,
        n1: Double? = 30.0,
        n2: Double? = 10.0,
        validity: Validity = Validity.VALID,
        invalidReasons: List<InvalidReason> = emptyList(),
        lowConfOn: Set<String> = emptySet(),
    ): KpiResult = KpiResult(
        validity = validity,
        invalidReasons = invalidReasons,
        seqMissingCount = 0,
        seqDupCount = 0,
        seqGapCount = 0,
        expectedTokenCount = 600,
        t1TtftMs = kv(t1, low = "T1" in lowConfOn),
        t2ItlP95Ms = kv(t2, low = "T2" in lowConfOn),
        t2ItlP95InclCoalescedMs = kv(t2),
        t3StallRate = kv(t3, "ratio", low = "T3" in lowConfOn),
        t3StallRateInclResume = kv(t3, "ratio"),
        t4SevereStallRate = kv(t4, "ratio"),
        t5ResumeP95Ms = kv(null),
        t5ResumeLatenciesMs = emptyList(),
        n1RttP50Ms = kv(n1, low = "N1" in lowConfOn),
        n2JitterMs = kv(n2, low = "N2" in lowConfOn),
        u1GoodputMbps = kv(u1, "Mbps", low = "U1" in lowConfOn),
        u1GoodputExclSlowStartMbps = kv(u1, "Mbps"),
        u2ToolLoopP95Ms = kv(u2, low = "U2" in lowConfOn),
    )

    // ---------- 门限边界值 ----------

    @Test
    fun `all kpis at excellent-good boundary score exactly 85`() {
        val r = AqsScorer.score(kpiResult())
        assertNotNull(r.score)
        AqsScorer.WEIGHTS.keys.forEach { id ->
            assertEquals("subScore $id", 85.0, r.subScores.getValue(id), 1e-9)
        }
        assertEquals(85.0, r.score!!, 1e-9)
        assertFalse(r.vetoApplied)
    }

    @Test
    fun `all kpis at good-fair boundary score exactly 70`() {
        val r = AqsScorer.score(
            kpiResult(t1 = 500.0, t2 = 200.0, t3 = 0.02, u1 = 5.0, u2 = 300.0, n1 = 60.0, n2 = 30.0)
        )
        AqsScorer.WEIGHTS.keys.forEach { id ->
            assertEquals("subScore $id", 70.0, r.subScores.getValue(id), 1e-9)
        }
        assertEquals(70.0, r.score!!, 1e-9)
    }

    @Test
    fun `all kpis at fair-poor boundary score exactly 55`() {
        val r = AqsScorer.score(
            kpiResult(t1 = 1000.0, t2 = 400.0, t3 = 0.05, u1 = 1.0, u2 = 600.0, n1 = 100.0, n2 = 80.0)
        )
        AqsScorer.WEIGHTS.keys.forEach { id ->
            assertEquals("subScore $id", 55.0, r.subScores.getValue(id), 1e-9)
        }
        assertEquals(55.0, r.score!!, 1e-9)
    }

    // ---------- 区间线性插值 ----------

    @Test
    fun `linear interpolation within a band for lower-better kpi`() {
        // T1: 200→85, 500→70；350 恰在中点 → 77.5
        val r = AqsScorer.score(kpiResult(t1 = 350.0))
        assertEquals(77.5, r.subScores.getValue("T1"), 1e-9)
        // 总分 = 77.5×0.2 + 85×0.8 = 83.5
        assertEquals(83.5, r.score!!, 1e-9)
    }

    @Test
    fun `linear interpolation within a band for higher-better u1`() {
        // U1: 5→70, 20→85；12.5 恰在中点 → 77.5
        val r = AqsScorer.score(kpiResult(u1 = 12.5))
        assertEquals(77.5, r.subScores.getValue("U1"), 1e-9)
    }

    @Test
    fun `interpolation between 85 and 100 anchor in top band`() {
        // T2 优档内：0→100, 100→85；50 → 92.5
        val r = AqsScorer.score(kpiResult(t2 = 50.0))
        assertEquals(92.5, r.subScores.getValue("T2"), 1e-9)
        // U1 优档内：20→85, 100→100；60 → 85 + 40/80×15 = 92.5
        val r2 = AqsScorer.score(kpiResult(u1 = 60.0))
        assertEquals(92.5, r2.subScores.getValue("U1"), 1e-9)
    }

    // ---------- 端点 clamp ----------

    @Test
    fun `perfect values clamp to 100`() {
        val r = AqsScorer.score(
            kpiResult(t1 = 0.0, t2 = 0.0, t3 = 0.0, u1 = 150.0, u2 = 0.0, n1 = 0.0, n2 = 0.0)
        )
        AqsScorer.WEIGHTS.keys.forEach { id ->
            assertEquals("subScore $id", 100.0, r.subScores.getValue(id), 1e-9)
        }
        assertEquals(100.0, r.score!!, 1e-9)
    }

    @Test
    fun `worst values clamp to 0 not negative`() {
        val r = AqsScorer.score(
            kpiResult(t1 = 99_999.0, t2 = 99_999.0, t3 = 1.0, u1 = 0.0, u2 = 99_999.0, n1 = 9_999.0, n2 = 9_999.0)
        )
        AqsScorer.WEIGHTS.keys.forEach { id ->
            assertEquals("subScore $id", 0.0, r.subScores.getValue(id), 1e-9)
        }
        assertEquals(0.0, r.score!!, 1e-9)
    }

    // ---------- T4 一票否决 ----------

    @Test
    fun `t4 over one percent vetoes and caps aqs at 54`() {
        val r = AqsScorer.score(kpiResult(t4 = 0.02))
        assertTrue(r.vetoApplied)
        assertEquals(54.0, r.score!!, 1e-9) // 未否决应为 85 → 封顶 54
    }

    @Test
    fun `t4 exactly one percent does not veto`() {
        val r = AqsScorer.score(kpiResult(t4 = 0.01))
        assertFalse(r.vetoApplied)
        assertEquals(85.0, r.score!!, 1e-9)
    }

    @Test
    fun `t4 veto does not raise an already lower score`() {
        // 全部"可/差"边界 = 55 分 → 否决后 min(55, 54) = 54？不：封顶语义取 min
        val r = AqsScorer.score(
            kpiResult(t1 = 3000.0, t2 = 1200.0, t3 = 0.15, t4 = 0.05, u1 = 0.0, u2 = 1800.0, n1 = 300.0, n2 = 240.0)
        )
        assertTrue(r.vetoApplied)
        assertEquals(0.0, r.score!!, 1e-9) // 本就 0 分，封顶不抬分
    }

    // ---------- 有效性语义 ----------

    @Test
    fun `invalid scenario produces no score`() {
        val r = AqsScorer.score(
            kpiResult(validity = Validity.INVALID, invalidReasons = listOf(InvalidReason.GAP_EXCEEDED))
        )
        assertNull(r.score)
        assertTrue(r.subScores.isEmpty())
        assertNotNull(r.notComputableReason)
        assertTrue(r.notComputableReason!!.startsWith("INVALID_SCENARIO:"))
        assertTrue(r.notComputableReason!!.contains("GAP_EXCEEDED"))
    }

    @Test
    fun `missing weighted kpi makes aqs not computable never zero-filled`() {
        val r = AqsScorer.score(kpiResult(n1 = null))
        assertNull(r.score)
        assertNotNull(r.notComputableReason)
        assertTrue(r.notComputableReason!!.startsWith("KPI_MISSING:"))
        assertTrue(r.notComputableReason!!.contains("N1"))
    }

    @Test
    fun `low confidence validity yields score with flag`() {
        val r = AqsScorer.score(kpiResult(validity = Validity.VALID_LOW_CONFIDENCE))
        assertNotNull(r.score)
        assertTrue(r.lowConfidence)
        assertEquals(85.0, r.score!!, 1e-9)
    }

    @Test
    fun `low confidence on a weighted kpi propagates to aqs`() {
        val r = AqsScorer.score(kpiResult(lowConfOn = setOf("U2")))
        assertNotNull(r.score)
        assertTrue(r.lowConfidence)
    }

    @Test
    fun `fully valid confident scenario is not flagged`() {
        val r = AqsScorer.score(kpiResult())
        assertFalse(r.lowConfidence)
        assertNull(r.notComputableReason)
    }

    // ---------- 权重与版本 ----------

    @Test
    fun `weights match kpi doc 5-4 and sum to one`() {
        assertEquals(0.20, AqsScorer.WEIGHTS.getValue("T1"), 1e-12)
        assertEquals(0.20, AqsScorer.WEIGHTS.getValue("T3"), 1e-12)
        assertEquals(0.15, AqsScorer.WEIGHTS.getValue("T2"), 1e-12)
        assertEquals(0.15, AqsScorer.WEIGHTS.getValue("U1"), 1e-12)
        assertEquals(0.10, AqsScorer.WEIGHTS.getValue("U2"), 1e-12)
        assertEquals(0.10, AqsScorer.WEIGHTS.getValue("N1"), 1e-12)
        assertEquals(0.10, AqsScorer.WEIGHTS.getValue("N2"), 1e-12)
        assertEquals(1.0, AqsScorer.WEIGHTS.values.sum(), 1e-12)
    }

    @Test
    fun `result carries aqs and kpi set versions`() {
        val r = AqsScorer.score(kpiResult())
        assertEquals("aqs-v0.1", r.aqsVersion)
        assertEquals("agent-qoe-kpi-v0.1", r.kpiSetVersion)
    }

    @Test
    fun `weighted aggregation uses documented weights`() {
        // T1 满分 100、其余 85 → 100×0.2 + 85×0.8 = 88
        val r = AqsScorer.score(kpiResult(t1 = 0.0))
        assertEquals(88.0, r.score!!, 1e-9)
    }

    // ---------- 端到端：KpiCalculator → AqsScorer ----------

    @Test
    fun `end to end from calculator invalid scenario yields no aqs`() {
        val kpi = KpiCalculator.calculate(KpiInput(streamTruncated = true))
        val r = AqsScorer.score(kpi)
        assertNull(r.score)
        assertTrue(r.notComputableReason!!.startsWith("INVALID_SCENARIO:"))
    }

    @Test
    fun `end to end from calculator missing kpis yields not computable`() {
        // 只有 echo 数据：T/U 组缺失 → AQS 不可计算而非 0 分
        val kpi = KpiCalculator.calculate(
            KpiInput(echoSamples = (1..20).map { EchoSample(rttNanos = 20_000_000L) })
        )
        val r = AqsScorer.score(kpi)
        assertNull(r.score)
        assertTrue(r.notComputableReason!!.startsWith("KPI_MISSING:"))
        assertTrue(r.notComputableReason!!.contains("T1"))
    }
}
