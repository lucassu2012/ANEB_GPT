package com.aneb.probe.apiprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 端到端 TTFT/ITL 计算（对照列口径；数值缺失一律 null，R-10）。 */
class ApiProbeKpiTest {

    private fun arrival(i: Int, atMs: Long, batch: Boolean = false) =
        LlmTokenArrival(index = i, arrivalNanos = atMs * 1_000_000L, sameReadBatch = batch, textChars = 2)

    @Test
    fun `ttft is first arrival minus request start`() {
        val k = ApiProbeKpi.compute(
            requestStartNanos = 100 * 1_000_000L,
            arrivals = listOf(arrival(0, 600), arrival(1, 650)),
            eofNanos = 700 * 1_000_000L,
        )
        assertEquals(500.0, k.ttftMs!!, 1e-9)
    }

    @Test
    fun `itl median and p95 nearest rank`() {
        // 间隔序列：10,20,30,40,50 → P50(最近秩)=30, P95=50
        val times = listOf(0L, 10, 30, 60, 100, 150)
        val arrivals = times.mapIndexed { i, t -> arrival(i, 1000 + t) }
        val k = ApiProbeKpi.compute(0L, arrivals, eofNanos = null)
        assertEquals(30.0, k.itlMedianMs!!, 1e-9)
        assertEquals(50.0, k.itlP95Ms!!, 1e-9)
        assertEquals(5, k.itlSampleCount)
    }

    @Test
    fun `sameReadBatch intervals excluded from itl`() {
        // 4 个到达，第 3 个标 sameReadBatch（合帧伪 0）→ 只剩 2 个间隔样本
        val arrivals = listOf(
            arrival(0, 1000),
            arrival(1, 1020),
            arrival(2, 1020, batch = true),
            arrival(3, 1050),
        )
        val k = ApiProbeKpi.compute(0L, arrivals, eofNanos = null)
        assertEquals(2, k.itlSampleCount) // 20ms 与 30ms；伪 0 间隔剔除（R-04）
        assertEquals(30.0, k.itlP95Ms!!, 1e-9)
    }

    @Test
    fun `zero interval without batch flag also excluded`() {
        val arrivals = listOf(arrival(0, 1000), arrival(1, 1000), arrival(2, 1040))
        val k = ApiProbeKpi.compute(0L, arrivals, eofNanos = null)
        assertEquals(1, k.itlSampleCount) // 0 值样本不入分位数（5.1 同款）
    }

    @Test
    fun `no arrivals yields nulls not zeros`() {
        val k = ApiProbeKpi.compute(0L, emptyList(), eofNanos = null)
        assertNull(k.ttftMs)
        assertNull(k.itlMedianMs)
        assertNull(k.itlP95Ms)
        assertNull(k.totalMs)
        assertEquals(0, k.tokenEventCount)
        assertEquals(0, k.itlSampleCount)
    }

    @Test
    fun `single arrival has ttft but no itl`() {
        val k = ApiProbeKpi.compute(0L, listOf(arrival(0, 800)), eofNanos = 900 * 1_000_000L)
        assertEquals(800.0, k.ttftMs!!, 1e-9)
        assertNull(k.itlMedianMs)
        assertEquals(1, k.tokenEventCount)
        assertEquals(900.0, k.totalMs!!, 1e-9)
    }

    @Test
    fun `total text chars summed`() {
        val k = ApiProbeKpi.compute(0L, listOf(arrival(0, 100), arrival(1, 200)), null)
        assertEquals(4, k.totalTextChars)
    }

    @Test
    fun `adapter to kpi end to end on openai fixture`() {
        // 集成：夹具 → 适配器 → KPI（到达戳 50ms 等距 → ITL 全 50ms）
        val res = OpenAiSseAdapter().parse(SseFixtures.toRawEvents(SseFixtures.OPENAI_STREAM))
        val k = ApiProbeKpi.compute(900_000_000L, res.arrivals, eofNanos = 2_000_000_000L)
        // 首 content delta 在夹具下标 1 → 1_050ms；TTFT = 1050-900 = 150ms
        assertEquals(150.0, k.ttftMs!!, 1e-9)
        assertEquals(50.0, k.itlMedianMs!!, 1e-9)
        assertEquals(50.0, k.itlP95Ms!!, 1e-9)
        assertEquals(4, k.itlSampleCount)
    }

    @Test
    fun `adapter to kpi end to end on anthropic fixture`() {
        val res = AnthropicSseAdapter().parse(SseFixtures.toRawEvents(SseFixtures.ANTHROPIC_STREAM))
        val k = ApiProbeKpi.compute(1_000_000_000L, res.arrivals, eofNanos = null)
        // 首 delta 在夹具下标 3 → 1000ms+150ms；TTFT = 150ms
        assertEquals(150.0, k.ttftMs!!, 1e-9)
        assertEquals(5, k.itlSampleCount)
        assertEquals(50.0, k.itlP95Ms!!, 1e-9)
    }
}
