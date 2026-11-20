package com.aneb.probe.ui

import com.aneb.probe.scoring.AqsScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AiScenarioAdvisorTest {

    private fun verdictOf(code: String, vs: List<AiScenarioAdvisor.Verdict>) =
        vs.first { it.code == code }

    @Test
    fun `全指标优良_六场景全部适合`() {
        val vs = AiScenarioAdvisor.advise(downMbps = 400.0, upMbps = 200.0, rttMs = 20.0, jitterMs = 3.0)
        assertEquals(6, vs.size)
        assertTrue("全指标优良应全部适合", vs.all { it.suitable == true })
    }

    @Test
    fun `上行不足_大上行场景不适合_纯文本仍适合`() {
        val vs = AiScenarioAdvisor.advise(downMbps = 400.0, upMbps = 10.0, rttMs = 20.0, jitterMs = 3.0)
        assertEquals(false, verdictOf("TK-4", vs).suitable)
        assertEquals(false, verdictOf("TK-3", vs).suitable)
        assertEquals(true, verdictOf("TK-2", vs).suitable)
        assertEquals(true, verdictOf("TK-1", vs).suitable)
    }

    @Test
    fun `高时延_低时延场景不适合_下行场景不受影响`() {
        val vs = AiScenarioAdvisor.advise(downMbps = 400.0, upMbps = 200.0, rttMs = 150.0, jitterMs = 3.0)
        assertEquals(false, verdictOf("TK-1", vs).suitable)
        assertEquals(false, verdictOf("TK-6", vs).suitable)
        assertEquals(true, verdictOf("TK-5", vs).suitable)
    }

    @Test
    fun `缺测下行_下行场景无法判定而非否定_R10`() {
        val vs = AiScenarioAdvisor.advise(downMbps = null, upMbps = 200.0, rttMs = 20.0, jitterMs = 3.0)
        assertNull("下行未测 → TK-5 无法判定", verdictOf("TK-5", vs).suitable)
        assertNull("下行未测 → TK-3 无法判定（上行达标无否定证据）", verdictOf("TK-3", vs).suitable)
        assertEquals(true, verdictOf("TK-1", vs).suitable)
    }

    @Test
    fun `缺测但另一实测已否定_仍判不适合`() {
        val vs = AiScenarioAdvisor.advise(downMbps = null, upMbps = 1.0, rttMs = 20.0, jitterMs = 3.0)
        assertEquals(false, verdictOf("TK-3", vs).suitable)
    }

    @Test
    fun `需求串含实测对照标记`() {
        val vs = AiScenarioAdvisor.advise(downMbps = 400.0, upMbps = 10.0, rttMs = 20.0, jitterMs = 3.0)
        val tk4 = verdictOf("TK-4", vs)
        assertTrue("需求串应含未达标记 ✗", tk4.requirement.contains("✗"))
        assertTrue("需求串应含实测值", tk4.requirement.contains("10.0"))
    }

    @Test
    fun `默认良锚与 AqsScorer 70 分良级锚点对齐`() {
        val d = AiScenarioAdvisor.FitnessThresholds.DEFAULT
        assertEquals(70.0, AqsScorer.N1_ANCHORS.score(d.n1GoodMs), 1e-9)
        assertEquals(70.0, AqsScorer.N2_ANCHORS.score(d.n2GoodMs), 1e-9)
        assertEquals(70.0, AqsScorer.U1_ANCHORS.score(d.u1GenericGoodMbps), 1e-9)
    }

    @Test
    fun `Locale GERMANY 下需求串仍使用小数点而非逗号`() {
        val prev = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val vs = AiScenarioAdvisor.advise(downMbps = 400.0, upMbps = 10.0, rttMs = 20.0, jitterMs = 3.0)
            val tk4 = verdictOf("TK-4", vs)
            assertTrue("需求串应使用 Locale.ROOT 小数点", tk4.requirement.contains("10.0"))
        } finally {
            Locale.setDefault(prev)
        }
    }

    @Test
    fun `非有限或负值输入按缺测处理_绝不输出NaN或误判达标`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val vs = AiScenarioAdvisor.advise(downMbps = bad, upMbps = bad, rttMs = bad, jitterMs = bad)
            assertTrue("bad=$bad must yield suitable=null for all", vs.all { it.suitable == null })
            assertTrue("bad=$bad must not leak NaN/Infinity text", vs.all {
                !it.requirement.contains("NaN") && !it.requirement.contains("Infinity") && it.requirement.contains("实测—?")
            })
        }

        // Co-occurring valid failing metric still yields suitable = false
        val mixed = AiScenarioAdvisor.advise(downMbps = Double.NaN, upMbps = 1.0, rttMs = 20.0, jitterMs = 3.0)
        assertEquals(false, verdictOf("TK-3", mixed).suitable)

        // Negative latencies must never pass <= threshold checks
        val neg = AiScenarioAdvisor.advise(downMbps = 400.0, upMbps = 200.0, rttMs = -1.0, jitterMs = -5.0)
        assertNull(verdictOf("TK-1", neg).suitable)
        assertNull(verdictOf("TK-2", neg).suitable)
        assertNull(verdictOf("TK-6", neg).suitable)

        // Zero inputs remain valid (0.0 ms passes latency, 0.0 Mbps fails throughput)
        val zeros = AiScenarioAdvisor.advise(downMbps = 0.0, upMbps = 0.0, rttMs = 0.0, jitterMs = 0.0)
        assertEquals(true, verdictOf("TK-1", zeros).suitable)
        for (code in listOf("TK-2", "TK-3", "TK-4", "TK-5", "TK-6")) {
            assertEquals(false, verdictOf(code, zeros).suitable)
        }
    }
}
