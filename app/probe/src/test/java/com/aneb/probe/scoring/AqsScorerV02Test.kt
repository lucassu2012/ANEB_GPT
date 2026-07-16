package com.aneb.probe.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AqsScorer v0.2 单测（阶段 2：C 组接入，KPI 文档 5.4 阶段二条款 + 5.2 C 组门限）。
 * 覆盖：v0.2 权重表（v0.1×0.8 + C 组 20%、合计 1.0）、C1/C2 门限边界打分、
 * C 组区间插值、无 C 数据回退 v0.1 语义、C 值缺失不可计算（R-10）、版本号透出、
 * T4 否决在 v0.2 下仍生效、C 组低置信传播、INVALID 不出分。
 */
class AqsScorerV02Test {

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
    ): KpiResult = KpiResult(
        validity = validity,
        invalidReasons = invalidReasons,
        seqMissingCount = 0,
        seqDupCount = 0,
        seqGapCount = 0,
        expectedTokenCount = 600,
        t1TtftMs = kv(t1),
        t2ItlP95Ms = kv(t2),
        t2ItlP95InclCoalescedMs = kv(t2),
        t3StallRate = kv(t3, "ratio"),
        t3StallRateInclResume = kv(t3, "ratio"),
        t4SevereStallRate = kv(t4, "ratio"),
        t5ResumeP95Ms = kv(null),
        t5ResumeLatenciesMs = emptyList(),
        n1RttP50Ms = kv(n1),
        n2JitterMs = kv(n2),
        u1GoodputMbps = kv(u1, "Mbps"),
        u1GoodputExclSlowStartMbps = kv(u1, "Mbps"),
        u2ToolLoopP95Ms = kv(u2),
    )

    private fun continuity(
        c1: Double? = 0.005,
        c2: Double? = 1000.0,
        c1Low: Boolean = false,
        c2Low: Boolean = false,
    ) = AqsScorer.ContinuityKpi(
        c1SessionDropRate = kv(c1, "ratio", low = c1Low),
        c2RecoveryMs = kv(c2, "ms", low = c2Low),
    )

    // ---------- 权重表 ----------

    @Test
    fun `v02 weights are v01 times point-eight plus c group and sum to one`() {
        AqsScorer.WEIGHTS.forEach { (id, w) ->
            assertEquals("weight $id", w * 0.8, AqsScorer.WEIGHTS_V02.getValue(id), 1e-12)
        }
        assertEquals(0.10, AqsScorer.WEIGHTS_V02.getValue("C1"), 1e-12)
        assertEquals(0.10, AqsScorer.WEIGHTS_V02.getValue("C2"), 1e-12)
        assertEquals(1.0, AqsScorer.WEIGHTS_V02.values.sum(), 1e-12)
    }

    // ---------- C 组门限边界（5.2：C1 0.5/2/5%，C2 1/3/10s） ----------

    @Test
    fun `all kpis incl c group at excellent-good boundary score 85`() {
        val r = AqsScorer.score(kpiResult(), continuity(c1 = 0.005, c2 = 1000.0))
        assertNotNull(r.score)
        AqsScorer.WEIGHTS_V02.keys.forEach { id ->
            assertEquals("subScore $id", 85.0, r.subScores.getValue(id), 1e-9)
        }
        assertEquals(85.0, r.score!!, 1e-9)
    }

    @Test
    fun `c group at good-fair boundary scores 70`() {
        val r = AqsScorer.score(kpiResult(), continuity(c1 = 0.02, c2 = 3000.0))
        assertEquals(70.0, r.subScores.getValue("C1"), 1e-9)
        assertEquals(70.0, r.subScores.getValue("C2"), 1e-9)
    }

    @Test
    fun `c group at fair-poor boundary scores 55`() {
        val r = AqsScorer.score(kpiResult(), continuity(c1 = 0.05, c2 = 10_000.0))
        assertEquals(55.0, r.subScores.getValue("C1"), 1e-9)
        assertEquals(55.0, r.subScores.getValue("C2"), 1e-9)
    }

    @Test
    fun `c group interpolates linearly and clamps at endpoints`() {
        // C2 良档中点：1000→85, 3000→70；2000 → 77.5
        val mid = AqsScorer.score(kpiResult(), continuity(c2 = 2000.0))
        assertEquals(77.5, mid.subScores.getValue("C2"), 1e-9)
        // 端点 clamp：完美 0 → 100；极差 → 0（不为负）
        val best = AqsScorer.score(kpiResult(), continuity(c1 = 0.0, c2 = 0.0))
        assertEquals(100.0, best.subScores.getValue("C1"), 1e-9)
        assertEquals(100.0, best.subScores.getValue("C2"), 1e-9)
        val worst = AqsScorer.score(kpiResult(), continuity(c1 = 1.0, c2 = 999_999.0))
        assertEquals(0.0, worst.subScores.getValue("C1"), 1e-9)
        assertEquals(0.0, worst.subScores.getValue("C2"), 1e-9)
    }

    @Test
    fun `v02 aggregation uses point-eight scaling on legacy groups`() {
        // T1 满分 100、其余（含 C 组）85：total = 100×0.16 + 85×0.84 = 87.4
        val r = AqsScorer.score(kpiResult(t1 = 0.0), continuity())
        assertEquals(87.4, r.score!!, 1e-9)
    }

    // ---------- 回退语义（无 C 数据 → v0.1 默认） ----------

    @Test
    fun `null continuity falls back to v01 semantics exactly`() {
        val kpi = kpiResult()
        val fallback = AqsScorer.score(kpi, null)
        val v01 = AqsScorer.score(kpi)
        assertEquals(v01, fallback)
        assertEquals("aqs-v0.1", fallback.aqsVersion)
        assertFalse(fallback.subScores.containsKey("C1"))
    }

    @Test
    fun `existing v01 entry point is untouched by v02 addition`() {
        val r = AqsScorer.score(kpiResult())
        assertEquals("aqs-v0.1", r.aqsVersion)
        assertEquals(85.0, r.score!!, 1e-9)
        assertEquals(AqsScorer.WEIGHTS.keys, r.subScores.keys)
    }

    // ---------- 版本号透出 ----------

    @Test
    fun `v02 result carries aqs-v02 version`() {
        val r = AqsScorer.score(kpiResult(), continuity())
        assertEquals("aqs-v0.2", r.aqsVersion)
        assertEquals("agent-qoe-kpi-v0.1", r.kpiSetVersion)
    }

    // ---------- 失败语义（R-10） ----------

    @Test
    fun `missing c1 makes v02 not computable never zero-filled`() {
        val r = AqsScorer.score(kpiResult(), continuity(c1 = null))
        assertNull(r.score)
        assertTrue(r.notComputableReason!!.startsWith("KPI_MISSING:"))
        assertTrue(r.notComputableReason!!.contains("C1"))
        assertEquals("aqs-v0.2", r.aqsVersion)
    }

    @Test
    fun `missing c2 recovery-failed sample makes v02 not computable`() {
        // C2"失败"按 R-10 记 null → 不可计算，绝不以封顶值顶替
        val r = AqsScorer.score(kpiResult(), continuity(c2 = null))
        assertNull(r.score)
        assertTrue(r.notComputableReason!!.contains("C2"))
    }

    @Test
    fun `invalid scenario yields no v02 score`() {
        val r = AqsScorer.score(
            kpiResult(validity = Validity.INVALID, invalidReasons = listOf(InvalidReason.GAP_EXCEEDED)),
            continuity(),
        )
        assertNull(r.score)
        assertTrue(r.notComputableReason!!.startsWith("INVALID_SCENARIO:"))
        assertEquals("aqs-v0.2", r.aqsVersion)
    }

    // ---------- 否决与低置信 ----------

    @Test
    fun `t4 veto still applies under v02`() {
        val r = AqsScorer.score(kpiResult(t4 = 0.02), continuity())
        assertTrue(r.vetoApplied)
        assertEquals(54.0, r.score!!, 1e-9)
    }

    @Test
    fun `low confidence on c kpi propagates to v02 aqs`() {
        val r = AqsScorer.score(kpiResult(), continuity(c2Low = true))
        assertNotNull(r.score)
        assertTrue(r.lowConfidence)
        // 对照：C 组全高置信时不带标
        assertFalse(AqsScorer.score(kpiResult(), continuity()).lowConfidence)
    }
}
