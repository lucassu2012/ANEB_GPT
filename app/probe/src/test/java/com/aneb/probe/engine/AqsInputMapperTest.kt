package com.aneb.probe.engine

import com.aneb.probe.scoring.AqsScorer
import com.aneb.probe.scoring.InvalidReason
import com.aneb.probe.scoring.KpiResult
import com.aneb.probe.scoring.KpiValue
import com.aneb.probe.scoring.Validity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** run 级 AQS 输入映射（P1 范围 5：N1/N2←S1；T←S2；U1←S3 含慢启动；U2←S2）。 */
class AqsInputMapperTest {

    private fun kv(v: Double?, lowConf: Boolean = false, n: Int = 150) =
        KpiValue(v, "x", if (v == null) 0 else n, lowConf && v != null)

    /** 构造一份场景 KpiResult；INVALID 时模拟 gate 后的全 null 值。 */
    private fun kpi(
        validity: Validity = Validity.VALID,
        t1: Double? = 100.0,
        t2: Double? = 50.0,
        t3: Double? = 0.0,
        t4: Double? = 0.0,
        n1: Double? = 20.0,
        n2: Double? = 5.0,
        u1: Double? = 30.0,
        u2: Double? = 100.0,
        lowConf: Boolean = false,
        reasons: List<InvalidReason> = emptyList(),
    ): KpiResult {
        fun g(v: Double?): Double? = if (validity == Validity.INVALID) null else v
        return KpiResult(
            validity = validity,
            invalidReasons = reasons,
            seqMissingCount = 0, seqDupCount = 0, seqGapCount = 0, expectedTokenCount = 100,
            t1TtftMs = kv(g(t1), lowConf),
            t2ItlP95Ms = kv(g(t2), lowConf),
            t2ItlP95InclCoalescedMs = kv(g(t2)),
            t3StallRate = kv(g(t3)),
            t3StallRateInclResume = kv(g(t3)),
            t4SevereStallRate = kv(g(t4)),
            t5ResumeP95Ms = kv(null),
            t5ResumeLatenciesMs = emptyList(),
            n1RttP50Ms = kv(g(n1)),
            n2JitterMs = kv(g(n2)),
            u1GoodputMbps = kv(g(u1)),
            u1GoodputExclSlowStartMbps = kv(null),
            u2ToolLoopP95Ms = kv(g(u2)),
        )
    }

    @Test
    fun `各权重项取自正确的来源场景`() {
        val composite = AqsInputMapper.map(
            mapOf(
                // S1 的 T/U 值故意设成"毒值"——若被误采，score 会明显异常
                AqsInputMapper.S1 to listOf(kpi(t1 = 9999.0, t2 = 9999.0, u1 = 0.001, u2 = 9999.0, n1 = 20.0, n2 = 5.0)),
                AqsInputMapper.S2 to listOf(kpi(t1 = 150.0, t2 = 80.0, t3 = 0.001, t4 = 0.0, u2 = 120.0, n1 = 9999.0, u1 = 0.001)),
                AqsInputMapper.S3 to listOf(kpi(u1 = 25.0, t1 = 9999.0, n1 = 9999.0)),
            )
        )
        assertEquals(150.0, composite.t1TtftMs.value!!, 1e-9)   // T1 ← S2
        assertEquals(80.0, composite.t2ItlP95Ms.value!!, 1e-9)  // T2 ← S2
        assertEquals(120.0, composite.u2ToolLoopP95Ms.value!!, 1e-9) // U2 ← S2
        assertEquals(20.0, composite.n1RttP50Ms.value!!, 1e-9)  // N1 ← S1
        assertEquals(5.0, composite.n2JitterMs.value!!, 1e-9)   // N2 ← S1
        assertEquals(25.0, composite.u1GoodputMbps.value!!, 1e-9) // U1 ← S3（含慢启动口径）
        val aqs = AqsScorer.score(composite)
        assertNotNull(aqs.score)
        assertNull(aqs.notComputableReason)
    }

    @Test
    fun `缺 S3 时 U1 为 null 且 AqsScorer 报 KPI_MISSING U1`() {
        val composite = AqsInputMapper.map(
            mapOf(
                AqsInputMapper.S1 to listOf(kpi()),
                AqsInputMapper.S2 to listOf(kpi()),
                // S3 缺失（未跑/被中止）
            )
        )
        assertNull(composite.u1GoodputMbps.value)
        val aqs = AqsScorer.score(composite)
        assertNull(aqs.score) // 绝不以 0 顶替（R-10）
        assertEquals("KPI_MISSING:U1", aqs.notComputableReason)
    }

    @Test
    fun `S2 INVALID 时 T 组与 U2 缺失 → KPI_MISSING 列出全部缺项`() {
        val composite = AqsInputMapper.map(
            mapOf(
                AqsInputMapper.S1 to listOf(kpi()),
                AqsInputMapper.S2 to listOf(kpi(validity = Validity.INVALID, reasons = listOf(InvalidReason.PATH_CHANGED))),
                AqsInputMapper.S3 to listOf(kpi()),
            )
        )
        // 合成结果不因单场景 INVALID 而整体 INVALID——缺失经 KPI_MISSING 表达
        assertTrue(composite.validity != Validity.INVALID)
        val aqs = AqsScorer.score(composite)
        assertNull(aqs.score)
        assertEquals("KPI_MISSING:T1,T2,T3,U2", aqs.notComputableReason)
    }

    @Test
    fun `取证模式三遍取中位数且 INVALID 遍被剔除`() {
        val composite = AqsInputMapper.map(
            mapOf(
                AqsInputMapper.S1 to listOf(kpi(n1 = 10.0), kpi(n1 = 30.0), kpi(n1 = 20.0)),
                AqsInputMapper.S2 to listOf(
                    kpi(t1 = 100.0),
                    kpi(validity = Validity.INVALID, reasons = listOf(InvalidReason.TRUNCATED)), // 该遍值全 null
                    kpi(t1 = 300.0),
                ),
                AqsInputMapper.S3 to listOf(kpi(u1 = 10.0), kpi(u1 = 20.0), kpi(u1 = 40.0)),
            )
        )
        assertEquals(20.0, composite.n1RttP50Ms.value!!, 1e-9) // 10/20/30 → 20
        // S2 有效遍只剩 100/300：最近秩 P50 = 100
        assertEquals(100.0, composite.t1TtftMs.value!!, 1e-9)
        assertEquals(20.0, composite.u1GoodputMbps.value!!, 1e-9)
    }

    @Test
    fun `低置信从贡献场景传播到合成结果`() {
        val clean = AqsInputMapper.map(
            mapOf(
                AqsInputMapper.S1 to listOf(kpi()),
                AqsInputMapper.S2 to listOf(kpi()),
                AqsInputMapper.S3 to listOf(kpi()),
            )
        )
        assertEquals(Validity.VALID, clean.validity)
        assertFalse(AqsScorer.score(clean).lowConfidence)

        val lowConf = AqsInputMapper.map(
            mapOf(
                AqsInputMapper.S1 to listOf(kpi()),
                AqsInputMapper.S2 to listOf(kpi(lowConf = true)),
                AqsInputMapper.S3 to listOf(kpi()),
            )
        )
        assertEquals(Validity.VALID_LOW_CONFIDENCE, lowConf.validity)
        val aqs = AqsScorer.score(lowConf)
        assertNotNull(aqs.score)
        assertTrue(aqs.lowConfidence)
    }

    @Test
    fun `全场景缺失时全部权重项 KPI_MISSING`() {
        val aqs = AqsScorer.score(AqsInputMapper.map(emptyMap()))
        assertNull(aqs.score)
        assertEquals("KPI_MISSING:N1,N2,T1,T2,T3,U1,U2", aqs.notComputableReason)
    }
}
