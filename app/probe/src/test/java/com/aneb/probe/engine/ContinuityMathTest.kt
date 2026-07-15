package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 阶段 2 C 组纯计算逻辑单测：重连指数退避、C1 中断率（null 语义）、
 * C2 中位数、C3 阶梯序列化、C1/C2 分级门限（agent-qoe-kpi v0.2，5.2）。
 */
class ContinuityMathTest {

    // ---------- 指数退避（500ms 起 ×2，最多 5 次任务书口径） ----------

    @Test
    fun `backoff schedule doubles from 500ms`() {
        assertEquals(listOf(500L, 1000L, 2000L, 4000L, 8000L),
            (1..5).map { ContinuityMath.backoffDelayMs(it) })
    }

    @Test
    fun `backoff clamps invalid attempt to first`() {
        assertEquals(500L, ContinuityMath.backoffDelayMs(0))
        assertEquals(500L, ContinuityMath.backoffDelayMs(-3))
    }

    // ---------- C1 会话中断率 ----------

    @Test
    fun `c1 rate is disconnects over segments`() {
        assertEquals(2.0 / 3.0, ContinuityMath.c1Rate(2, 3)!!, 1e-12)
        assertEquals(0.0, ContinuityMath.c1Rate(0, 5)!!, 1e-12)
    }

    @Test
    fun `c1 rate with zero segments is null not zero`() {
        assertNull(ContinuityMath.c1Rate(0, 0)) // R-10：无分母不出值
    }

    // ---------- C2 中位数 ----------

    @Test
    fun `median of odd and even sample counts`() {
        assertEquals(1200.0, ContinuityMath.medianMs(listOf(3000.0, 1200.0, 800.0))!!, 1e-12)
        assertEquals(1000.0, ContinuityMath.medianMs(listOf(1200.0, 800.0))!!, 1e-12)
    }

    @Test
    fun `median of empty samples is null`() {
        assertNull(ContinuityMath.medianMs(emptyList())) // R-10
    }

    // ---------- C3 阶梯序列化 ----------

    @Test
    fun `c3 ladder csv encodes probes and escapes error text`() {
        val csv = ContinuityMath.c3LadderCsv(
            listOf(
                ContinuityMath.C3Probe(60, connNew = false, echoMs = 12.345, error = null),
                ContinuityMath.C3Probe(180, connNew = true, echoMs = null, error = "java.io.IOException: reset; now"),
            )
        )
        assertEquals("60:false:12.35:none;180:true:null:java.io.IOException~_reset,_now", csv)
    }

    // ---------- C1/C2 分级（KpiGrading additive） ----------

    @Test
    fun `c1 grading follows half two five percent thresholds`() {
        assertEquals("excellent", KpiGrading.grade("C1", 0.004))
        assertEquals("good", KpiGrading.grade("C1", 0.005))
        assertEquals("fair", KpiGrading.grade("C1", 0.05))
        assertEquals("poor", KpiGrading.grade("C1", 0.051))
        assertNull(KpiGrading.grade("C1", null)) // R-10：失败样本不发分级
    }

    @Test
    fun `c2 grading follows one three ten second thresholds`() {
        assertEquals("excellent", KpiGrading.grade("C2", 999.0))
        assertEquals("good", KpiGrading.grade("C2", 1000.0))
        assertEquals("fair", KpiGrading.grade("C2", 10_000.0))
        assertEquals("poor", KpiGrading.grade("C2", 10_001.0))
        assertNull(KpiGrading.grade("C2", null))
    }
}
