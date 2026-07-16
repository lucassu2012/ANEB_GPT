package com.aneb.probe.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KpiCalculator 单测（agent-qoe-kpi v0.1 口径）。
 * 覆盖：seq 乱序/缺号/重号、coalesced 剔除、resume 剔除、null 失败样本语义（R-10）、
 * 残差口径（R-09 金样本）、双口径差异、最近秩分位数、低置信标（R-29）。
 */
class KpiCalculatorTest {

    // ---------- 构造辅助 ----------

    /**
     * flush = sched（固定 30ms 步进），arrival 按给定间隔累加。
     * 此时校正 ITL = 到达间隔（残差 = 到达间隔 − sched 间隔，加回名义间隔恰好抵消）。
     */
    private fun tokensFromArrivalIntervals(
        intervalsMs: List<Double>,
        schedStepUs: Long = 30_000,
        sameReadBatchSeqs: Set<Long> = emptySet(),
    ): List<TokenSample> {
        val out = ArrayList<TokenSample>()
        var arrivalNs = 1_000_000_000L
        var schedUs = 0L
        out.add(TokenSample(1, schedUs, schedUs, arrivalNs, false))
        intervalsMs.forEachIndexed { idx, itv ->
            val seq = (idx + 2).toLong()
            schedUs += schedStepUs
            arrivalNs += (itv * 1e6).toLong()
            out.add(TokenSample(seq, schedUs, schedUs, arrivalNs, seq in sameReadBatchSeqs))
        }
        return out
    }

    /** 纯本地回环：sched = flush = arrival 节奏完全一致（网络零贡献），间隔可含设计停顿。 */
    private fun loopbackTokens(intervalsMs: List<Double>): List<TokenSample> {
        val out = ArrayList<TokenSample>()
        var us = 0L
        out.add(TokenSample(1, us, us, 1_000_000_000L + us * 1000, false))
        intervalsMs.forEachIndexed { idx, itv ->
            us += (itv * 1000).toLong()
            out.add(TokenSample((idx + 2).toLong(), us, us, 1_000_000_000L + us * 1000, false))
        }
        return out
    }

    /** 可分别注入服务端 flush 延迟与网络到达延迟的构造器。 */
    private fun buildTokens(
        n: Int,
        schedStepUs: Long = 30_000,
        flushDelayUsAt: (seq: Long) -> Long = { 0L },
        arrivalDelayNsAt: (seq: Long) -> Long = { 0L },
    ): List<TokenSample> = (1..n.toLong()).map { seq ->
        val sched = (seq - 1) * schedStepUs
        val flush = sched + flushDelayUsAt(seq)
        val arrival = 1_000_000_000L + flush * 1000 + arrivalDelayNsAt(seq)
        TokenSample(seq, sched, flush, arrival, false)
    }

    private fun goodEcho(n: Int = 20): List<EchoSample> =
        (1..n).map { EchoSample(rttNanos = it * 10_000_000L) } // 10,20,...ms

    // ---------- seq join / gap ----------

    @Test
    fun `seq join is independent of input order`() {
        val tokens = tokensFromArrivalIntervals(List(150) { 30.0 })
        val sorted = KpiCalculator.calculate(KpiInput(tokenSamples = tokens))
        val shuffled = KpiCalculator.calculate(KpiInput(tokenSamples = tokens.shuffled(java.util.Random(42))))
        assertEquals(sorted, shuffled)
    }

    @Test
    fun `missing seq counts gap and degrades to low confidence`() {
        val tokens = tokensFromArrivalIntervals(List(299) { 30.0 }).filter { it.seq != 150L }
        val r = KpiCalculator.calculate(KpiInput(tokenSamples = tokens))
        assertEquals(1, r.seqMissingCount)
        assertEquals(0, r.seqDupCount)
        assertEquals(1, r.seqGapCount)
        assertEquals(300, r.expectedTokenCount)
        assertEquals(Validity.VALID_LOW_CONFIDENCE, r.validity)
        assertTrue(r.invalidReasons.isEmpty())
    }

    @Test
    fun `gap over one percent is invalid with GAP_EXCEEDED and values suppressed`() {
        val tokens = tokensFromArrivalIntervals(List(99) { 30.0 })
            .filter { it.seq != 50L && it.seq != 51L } // 2/100 = 2% > 1%
        val r = KpiCalculator.calculate(KpiInput(tokenSamples = tokens))
        assertEquals(Validity.INVALID, r.validity)
        assertTrue(r.invalidReasons.contains(InvalidReason.GAP_EXCEEDED))
        assertNull(r.t2ItlP95Ms.value) // fail-closed：INVALID 抑制 KPI 值
        assertNull(r.t3StallRate.value)
        assertEquals(2, r.seqGapCount) // 诊断字段保留
    }

    @Test
    fun `duplicate seq counts gap`() {
        val tokens = tokensFromArrivalIntervals(List(299) { 30.0 })
        val withDup = tokens + tokens[49].copy(arrivalNanos = tokens[49].arrivalNanos!! + 5_000_000)
        val r = KpiCalculator.calculate(KpiInput(tokenSamples = withDup))
        assertEquals(1, r.seqDupCount)
        assertEquals(0, r.seqMissingCount)
        assertEquals(1, r.seqGapCount)
        assertEquals(Validity.VALID_LOW_CONFIDENCE, r.validity)
    }

    @Test
    fun `pairs across missing seq are skipped not position-paired`() {
        // 300 token 缺 seq150：gap 1/300 ≤1% 仍出值；(149,150)/(150,151) 两对消失
        val tokens = tokensFromArrivalIntervals(List(299) { 30.0 }).filter { it.seq != 150L }
        val r = KpiCalculator.calculate(KpiInput(tokenSamples = tokens))
        assertEquals(297, r.t2ItlP95Ms.sampleCount)
        assertEquals(30.0, r.t2ItlP95Ms.value!!, 1e-9)
    }

    @Test
    fun `null arrival excludes pair as failure never zero`() {
        val tokens = tokensFromArrivalIntervals(List(11) { 30.0 })
            .map { if (it.seq == 6L) it.copy(arrivalNanos = null) else it }
        val r = KpiCalculator.calculate(KpiInput(tokenSamples = tokens))
        // 11 个间隔中 (5,6)(6,7) 两对因 null 剔除 → 9 个样本，值不受 0 污染
        assertEquals(9, r.t2ItlP95Ms.sampleCount)
        assertEquals(30.0, r.t2ItlP95Ms.value!!, 1e-9)
    }

    // ---------- 残差口径（5.3.4 / R-09）----------

    @Test
    fun `server flush jitter is stripped by residual and not a stall`() {
        // 服务端从 seq6 起 flush 持续晚 300ms（调度问题），网络完美跟随 flush
        val tokens = buildTokens(11, flushDelayUsAt = { if (it >= 6) 300_000L else 0L })
        val r = KpiCalculator.calculate(KpiInput(tokenSamples = tokens))
        assertEquals(0.0, r.t3StallRate.value!!, 1e-12)
        // 校正 ITL 恒为名义 30ms
        assertEquals(30.0, r.t2ItlP95Ms.value!!, 1e-9)
    }

    @Test
    fun `network delay appears in corrected itl and counts as stall`() {
        // 服务端准点 flush，网络从 seq6 起持续晚 250ms → 恰一个间隔校正 ITL=280ms
        val tokens = buildTokens(11, arrivalDelayNsAt = { if (it >= 6) 250_000_000L else 0L })
        val r = KpiCalculator.calculate(KpiInput(tokenSamples = tokens))
        assertEquals(1.0 / 10, r.t3StallRate.value!!, 1e-12)
        assertEquals(280.0, r.t2ItlP95Ms.value!!, 1e-9)
        assertEquals(0.0, r.t4SevereStallRate.value!!, 1e-12)
    }

    @Test
    fun `t4 severe stall counts corrected itl over one second`() {
        val tokens = buildTokens(11, arrivalDelayNsAt = { if (it >= 6) 1_500_000_000L else 0L })
        val r = KpiCalculator.calculate(KpiInput(tokenSamples = tokens))
        assertEquals(1.0 / 10, r.t4SevereStallRate.value!!, 1e-12)
        assertEquals(1.0 / 10, r.t3StallRate.value!!, 1e-12) // >1s 也 >200ms
    }

    @Test
    fun `loopback golden sample with designed pause has zero stall`() {
        // R-09 金样本：纯本地回环 + S2 式设计停顿（500ms），pauseSeqs 标注 → T3 必须为 0
        val intervals = List(4) { 30.0 } + 500.0 + List(5) { 30.0 }
        val tokens = loopbackTokens(intervals)
        val r = KpiCalculator.calculate(KpiInput(tokenSamples = tokens, pauseSeqs = setOf(6L)))
        assertEquals(0.0, r.t3StallRate.value!!, 1e-12)
        assertEquals(0.0, r.t4SevereStallRate.value!!, 1e-12)
        assertEquals(9, r.t3StallRate.sampleCount) // 10 间隔 − 1 个 resume
    }

    // ---------- T5 resume 剔除 ----------

    @Test
    fun `resume interval goes to t5 and is excluded from t2 t3`() {
        val intervals = List(4) { 30.0 } + 500.0 + List(5) { 30.0 }
        val tokens = loopbackTokens(intervals)
        val r = KpiCalculator.calculate(KpiInput(tokenSamples = tokens, pauseSeqs = setOf(6L)))
        assertEquals(1, r.t5ResumeP95Ms.sampleCount)
        assertEquals(500.0, r.t5ResumeP95Ms.value!!, 1e-9)
        assertEquals(listOf(500.0), r.t5ResumeLatenciesMs)
        assertEquals(30.0, r.t2ItlP95Ms.value!!, 1e-9) // 500ms 未混入 T2
    }

    @Test
    fun `t3 dual metric with and without resume differ`() {
        val intervals = List(4) { 30.0 } + 500.0 + List(5) { 30.0 }
        val tokens = loopbackTokens(intervals)
        val r = KpiCalculator.calculate(KpiInput(tokenSamples = tokens, pauseSeqs = setOf(6L)))
        assertEquals(0.0, r.t3StallRate.value!!, 1e-12)
        assertEquals(1.0 / 10, r.t3StallRateInclResume.value!!, 1e-12)
    }

    @Test
    fun `unmarked pause pollutes t3 which the golden sample would catch`() {
        // 同样数据不传 pauseSeqs → 设计停顿被记 stall（这正是 R-09 要防的口径错误）
        val intervals = List(4) { 30.0 } + 500.0 + List(5) { 30.0 }
        val r = KpiCalculator.calculate(KpiInput(tokenSamples = loopbackTokens(intervals)))
        assertEquals(1.0 / 10, r.t3StallRate.value!!, 1e-12)
    }

    // ---------- coalesced 双口径 ----------

    @Test
    fun `coalesced pseudo zero intervals excluded from primary t2 but present in companion`() {
        // 间隔序列：30×9, 500, 0×10（seq12..21 同批到达）
        val intervals = List(9) { 30.0 } + 500.0 + List(10) { 0.0 }
        val tokens = tokensFromArrivalIntervals(
            intervals,
            sameReadBatchSeqs = (12L..21L).toSet(),
        )
        val r = KpiCalculator.calculate(KpiInput(tokenSamples = tokens))
        // 主口径：{30×9, 500}，最近秩 P95 = 500
        assertEquals(10, r.t2ItlP95Ms.sampleCount)
        assertEquals(500.0, r.t2ItlP95Ms.value!!, 1e-9)
        // 并列口径：含 0 值 20 个样本，rank ceil(0.95×20)=19 → 30
        assertEquals(20, r.t2ItlP95InclCoalescedMs.sampleCount)
        assertEquals(30.0, r.t2ItlP95InclCoalescedMs.value!!, 1e-9)
    }

    // ---------- T1 ----------

    @Test
    fun `t1 median ignores null failure samples`() {
        val r = KpiCalculator.calculate(
            KpiInput(ttftSamples = listOf(TtftSample(null), TtftSample(300.0), TtftSample(100.0), TtftSample(200.0)))
        )
        assertEquals(200.0, r.t1TtftMs.value!!, 1e-9) // 最近秩 P50 of {100,200,300}
        assertEquals(3, r.t1TtftMs.sampleCount)
        assertFalse(r.t1TtftMs.lowConfidence)
    }

    @Test
    fun `t1 single sample is low confidence`() {
        val r = KpiCalculator.calculate(KpiInput(ttftSamples = listOf(TtftSample(150.0))))
        assertEquals(150.0, r.t1TtftMs.value!!, 1e-9)
        assertTrue(r.t1TtftMs.lowConfidence)
        assertEquals(Validity.VALID_LOW_CONFIDENCE, r.validity)
    }

    @Test
    fun `t1 all failed gives null never zero`() {
        val r = KpiCalculator.calculate(KpiInput(ttftSamples = listOf(TtftSample(null), TtftSample(null))))
        assertNull(r.t1TtftMs.value)
        assertEquals(0, r.t1TtftMs.sampleCount)
    }

    // ---------- N1/N2 ----------

    @Test
    fun `echo warmup samples are dropped and p50 p95 computed by nearest rank`() {
        val warmups = List(3) { EchoSample(rttNanos = 999_000_000L, warmup = true) }
        val r = KpiCalculator.calculate(KpiInput(echoSamples = warmups + goodEcho(20)))
        assertEquals(20, r.n1RttP50Ms.sampleCount)
        assertEquals(100.0, r.n1RttP50Ms.value!!, 1e-9) // rank ceil(0.5×20)=10 → 100ms
        assertEquals(90.0, r.n2JitterMs.value!!, 1e-9) // P95(rank19=190) − P50(100)
        assertFalse(r.n1RttP50Ms.lowConfidence)
    }

    @Test
    fun `echo below ten samples outputs value with low confidence`() {
        val r = KpiCalculator.calculate(KpiInput(echoSamples = goodEcho(9)))
        assertNotNull(r.n1RttP50Ms.value)
        assertTrue(r.n1RttP50Ms.lowConfidence)
        assertTrue(r.n2JitterMs.lowConfidence)
        assertEquals(Validity.VALID_LOW_CONFIDENCE, r.validity)
    }

    @Test
    fun `echo all failed gives null never zero`() {
        val r = KpiCalculator.calculate(KpiInput(echoSamples = List(12) { EchoSample(rttNanos = null) }))
        assertNull(r.n1RttP50Ms.value)
        assertNull(r.n2JitterMs.value)
        assertEquals(0, r.n1RttP50Ms.sampleCount)
    }

    // ---------- U1 ----------

    @Test
    fun `u1 uses 2xx semantics and reports dual slow start values`() {
        val ok = UploadResult(
            bytes = 1_000_000, durationNanos = 1_000_000_000, http2xx = true,
            slowStartNanos = 500_000_000, slowStartBytes = 100_000,
        )
        val uploads = listOf(
            ok, ok, ok,
            UploadResult(bytes = 1_000_000, durationNanos = 900_000_000, http2xx = false), // 非 2xx 剔除
            UploadResult(bytes = 1_000_000, durationNanos = null, http2xx = true), // 失败 null 剔除
        )
        val r = KpiCalculator.calculate(KpiInput(uploadResults = uploads))
        assertEquals(3, r.u1GoodputMbps.sampleCount)
        assertEquals(8.0, r.u1GoodputMbps.value!!, 1e-9) // 1MB×8bit / 1s
        assertEquals(14.4, r.u1GoodputExclSlowStartMbps.value!!, 1e-9) // 0.9MB×8 / 0.5s
        assertFalse(r.u1GoodputMbps.lowConfidence)
    }

    @Test
    fun `u1 all failed gives null never zero`() {
        val uploads = List(3) { UploadResult(bytes = 1_000_000, durationNanos = 1_000_000_000, http2xx = false) }
        val r = KpiCalculator.calculate(KpiInput(uploadResults = uploads))
        assertNull(r.u1GoodputMbps.value)
        assertEquals(0, r.u1GoodputMbps.sampleCount)
    }

    @Test
    fun `u1 below three samples is low confidence`() {
        val uploads = List(2) { UploadResult(bytes = 1_000_000, durationNanos = 1_000_000_000, http2xx = true) }
        val r = KpiCalculator.calculate(KpiInput(uploadResults = uploads))
        assertEquals(8.0, r.u1GoodputMbps.value!!, 1e-9)
        assertTrue(r.u1GoodputMbps.lowConfidence)
    }

    // ---------- U2 ----------

    @Test
    fun `u2 subtracts server proc and p95 of eight samples equals max`() {
        // R-29：8 样本最近秩 P95 = 最大值（本实现如实输出并靠样本量口径暴露）
        val rounds = (1..8).map { ToolLoopSample(totalNanos = 200_000_000L + it * 10_000_000L, serverProcNanos = 200_000_000L) }
        val r = KpiCalculator.calculate(KpiInput(toolLoopSamples = rounds))
        assertEquals(80.0, r.u2ToolLoopP95Ms.value!!, 1e-9) // max(10..80)
        assertEquals(8, r.u2ToolLoopP95Ms.sampleCount)
        assertFalse(r.u2ToolLoopP95Ms.lowConfidence)
    }

    @Test
    fun `u2 failed round is excluded and below eight is low confidence`() {
        val rounds = (1..7).map { ToolLoopSample(totalNanos = 200_000_000L + it * 10_000_000L, serverProcNanos = 200_000_000L) } +
            ToolLoopSample(totalNanos = null, serverProcNanos = 200_000_000L) // 失败样本 null 剔除，绝不 0
        val r = KpiCalculator.calculate(KpiInput(toolLoopSamples = rounds))
        assertEquals(7, r.u2ToolLoopP95Ms.sampleCount)
        assertEquals(70.0, r.u2ToolLoopP95Ms.value!!, 1e-9)
        assertTrue(r.u2ToolLoopP95Ms.lowConfidence)
    }

    @Test
    fun `u2 all failed gives null never zero`() {
        val rounds = List(8) { ToolLoopSample(totalNanos = null, serverProcNanos = 200_000_000L) }
        val r = KpiCalculator.calculate(KpiInput(toolLoopSamples = rounds))
        assertNull(r.u2ToolLoopP95Ms.value)
        assertEquals(0, r.u2ToolLoopP95Ms.sampleCount)
    }

    // ---------- 有效性 Gate ----------

    @Test
    fun `truncated stream is invalid with TRUNCATED`() {
        val tokens = tokensFromArrivalIntervals(List(150) { 30.0 })
        val r = KpiCalculator.calculate(KpiInput(tokenSamples = tokens, streamTruncated = true))
        assertEquals(Validity.INVALID, r.validity)
        assertTrue(r.invalidReasons.contains(InvalidReason.TRUNCATED))
        assertNull(r.t2ItlP95Ms.value)
    }

    @Test
    fun `external guard reasons pass through as invalid`() {
        val tokens = tokensFromArrivalIntervals(List(150) { 30.0 })
        val r = KpiCalculator.calculate(
            KpiInput(tokenSamples = tokens, externalInvalidReasons = listOf(InvalidReason.GUARD_FAILED))
        )
        assertEquals(Validity.INVALID, r.validity)
        assertTrue(r.invalidReasons.contains(InvalidReason.GUARD_FAILED))
        assertNull(r.t3StallRate.value)
    }

    @Test
    fun `completely empty input is invalid NO_DATA`() {
        val r = KpiCalculator.calculate(KpiInput())
        assertEquals(Validity.INVALID, r.validity)
        assertTrue(r.invalidReasons.contains(InvalidReason.NO_DATA))
    }

    @Test
    fun `empty tokens with other kpis stays computable`() {
        val r = KpiCalculator.calculate(KpiInput(echoSamples = goodEcho(20)))
        assertNull(r.t2ItlP95Ms.value)
        assertEquals(0, r.t2ItlP95Ms.sampleCount)
        assertNotNull(r.n1RttP50Ms.value)
        assertEquals(Validity.VALID, r.validity)
    }

    @Test
    fun `clean large run is fully valid`() {
        val tokens = tokensFromArrivalIntervals(List(200) { 30.0 })
        val r = KpiCalculator.calculate(KpiInput(tokenSamples = tokens))
        assertEquals(Validity.VALID, r.validity)
        assertEquals(200, r.t2ItlP95Ms.sampleCount)
        assertEquals(30.0, r.t2ItlP95Ms.value!!, 1e-9)
        assertEquals(0.0, r.t3StallRate.value!!, 1e-12)
        assertFalse(r.t2ItlP95Ms.lowConfidence)
    }

    @Test
    fun `itl below hundred pairs is low confidence`() {
        val tokens = tokensFromArrivalIntervals(List(50) { 30.0 })
        val r = KpiCalculator.calculate(KpiInput(tokenSamples = tokens))
        assertTrue(r.t2ItlP95Ms.lowConfidence)
        assertEquals(Validity.VALID_LOW_CONFIDENCE, r.validity)
    }

    // ---------- 分位数方法 ----------

    @Test
    fun `nearest rank percentile matches contract`() {
        assertNull(KpiCalculator.percentileOrNull(emptyList(), 0.95))
        assertEquals(1.0, KpiCalculator.percentileOrNull(listOf(1.0), 0.95)!!, 1e-12)
        // n=20, p95 → rank 19
        val v = (1..20).map { it.toDouble() }
        assertEquals(19.0, KpiCalculator.percentileOrNull(v, 0.95)!!, 1e-12)
        // n=8, p95 → rank 8 = max（R-29 口径声明）
        val w = (1..8).map { it.toDouble() }
        assertEquals(8.0, KpiCalculator.percentileOrNull(w, 0.95)!!, 1e-12)
        // P50 n=4 → rank 2
        assertEquals(2.0, KpiCalculator.percentileOrNull(listOf(4.0, 3.0, 2.0, 1.0), 0.50)!!, 1e-12)
    }
}
