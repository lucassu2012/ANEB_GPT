package com.aneb.probe.engine

import com.aneb.probe.net.TokenEvent
import com.aneb.probe.scoring.KpiCalculator
import com.aneb.probe.scoring.KpiInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ScenarioOutcome→KpiInput 变换的纯函数部分（多流合并 / pause 检测 / 直方图口径一致性）。 */
class ScenarioKpiTest {

    private fun ev(
        seq: Long,
        schedUs: Long,
        preFlushUs: Long = schedUs,
        arrivalNanos: Long = schedUs * 1_000,
        coalesced: Boolean = false,
    ) = TokenEvent(seq, schedUs, preFlushUs, arrivalNanos, 100, coalesced)

    @Test
    fun `多流合并按累计 expected 重编号且跨流首样本禁配对`() {
        val s0 = ScenarioKpi.StreamTokens(300, listOf(ev(0, 0), ev(1, 16_666)))
        val s1 = ScenarioKpi.StreamTokens(800, listOf(ev(0, 0), ev(1, 16_666)))
        val join = ScenarioKpi.joinStreams(listOf(s0, s1))

        val seqs = join.samples.map { it.seq }
        assertEquals(listOf(0L, 1L, 300L, 301L), seqs)
        // 流 1 的 seq0（重编号后 300）：sched/preFlush 置 null——跨 HTTP 连接不构成 ITL 配对
        val boundary = join.samples.first { it.seq == 300L }
        assertNull(boundary.srvSchedUs)
        assertNull(boundary.srvPreFlushUs)
        // 流 0 的首样本不受影响
        val first = join.samples.first { it.seq == 0L }
        assertEquals(0L, first.srvSchedUs)
    }

    @Test
    fun `schedΔ 超 250ms 的后继 token 标为 pause 后首 token`() {
        val events = listOf(
            ev(0, 0),
            ev(1, 16_666),
            ev(2, 16_666 + 400_000), // 400ms 簇间停顿（profile 内生）
            ev(3, 16_666 + 416_666),
        )
        val join = ScenarioKpi.joinStreams(listOf(ScenarioKpi.StreamTokens(4, events)))
        assertEquals(setOf(2L), join.pauseSeqs)
    }

    @Test
    fun `缺 sched 的样本不参与 pause 判定`() {
        val events = listOf(ev(0, 0), TokenEvent(1, -1, -1, 500_000_000, 100, false), ev(2, 900_000))
        val join = ScenarioKpi.joinStreams(listOf(ScenarioKpi.StreamTokens(3, events)))
        assertTrue(join.pauseSeqs.isEmpty())
        // schedUs=-1 转 null（R-10 缺失语义）
        assertNull(join.samples.first { it.seq == 1L }.srvSchedUs)
    }

    @Test
    fun `直方图口径的 stall 率与 KpiCalculator T3 主口径一致`() {
        // 12 个 token：名义/实际发出间隔 10ms，到达间隔 30ms；第 6 个到达额外 +400ms
        val events = ArrayList<TokenEvent>()
        var arrival = 0L
        for (k in 0 until 12) {
            if (k > 0) arrival += if (k == 6) 430_000_000L else 30_000_000L
            events.add(ev(k.toLong(), schedUs = k * 10_000L, preFlushUs = k * 10_000L, arrivalNanos = arrival))
        }
        val join = ScenarioKpi.joinStreams(listOf(ScenarioKpi.StreamTokens(12, events)))
        val corrected = ScenarioKpi.correctedItlSamplesMs(join.samples, join.pauseSeqs)

        val kpi = KpiCalculator.calculate(
            KpiInput(tokenSamples = join.samples, pauseSeqs = join.pauseSeqs)
        )
        val histStallRate = corrected.count { it > KpiCalculator.STALL_THRESHOLD_MS }.toDouble() / corrected.size
        assertEquals(kpi.t3StallRate.value!!, histStallRate, 1e-12)
        assertEquals(kpi.t3StallRate.sampleCount, corrected.size)
        assertEquals(1.0 / 11.0, histStallRate, 1e-12) // 11 对中 1 个 430ms stall
    }

    @Test
    fun `coalesced 与 pause 样本从直方图口径剔除`() {
        val events = listOf(
            ev(0, 0, arrivalNanos = 0),
            ev(1, 10_000, arrivalNanos = 30_000_000, coalesced = true), // 合帧伪 0 口径的组员
            ev(2, 320_000, arrivalNanos = 400_000_000), // pause 后首 token（schedΔ=310ms）
            ev(3, 330_000, arrivalNanos = 430_000_000),
        )
        val join = ScenarioKpi.joinStreams(listOf(ScenarioKpi.StreamTokens(4, events)))
        val corrected = ScenarioKpi.correctedItlSamplesMs(join.samples, join.pauseSeqs)
        // 对 (0,1) coalesced 剔除；(1,2) pause 剔除；仅剩 (2,3)
        assertEquals(1, corrected.size)
    }
}
