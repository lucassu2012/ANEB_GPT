package com.aneb.probe.engine

import com.aneb.probe.data.AbResultEntity
import com.aneb.probe.net.TokenEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-C05 AbRunner 纯函数层单测：ABAB 交替顺序 / negotiatedProtocol 分箱 /
 * fallback 单列不进对比 / 每样本 KPI / seq 审计 / 最近秩分位。
 */
class AbRunnerTest {

    // ------------------------------------------------------------ 交替顺序

    @Test
    fun `alternatingOrder is strict ABAB for pairs=3`() {
        assertEquals(
            listOf("a", "b", "a", "b", "a", "b"),
            AbRunner.alternatingOrder(3),
        )
    }

    @Test
    fun `alternatingOrder empty for pairs=0`() {
        assertTrue(AbRunner.alternatingOrder(0).isEmpty())
    }

    // ------------------------------------------------------------ 分箱（红队：QUIC 启用 ≠ 协商 h3）

    @Test
    fun `binOf group A is always tcp`() {
        assertEquals(AbRunner.BIN_TCP, AbRunner.binOf(AbRunner.GROUP_A, "http/1.1"))
        assertEquals(AbRunner.BIN_TCP, AbRunner.binOf(AbRunner.GROUP_A, "h2"))
        assertEquals(AbRunner.BIN_TCP, AbRunner.binOf(AbRunner.GROUP_A, null))
    }

    @Test
    fun `binOf group B bins by negotiated protocol only`() {
        assertEquals(AbRunner.BIN_QUIC, AbRunner.binOf(AbRunner.GROUP_B, "h3"))
        assertEquals(AbRunner.BIN_QUIC, AbRunner.binOf(AbRunner.GROUP_B, "h3-29"))
        assertEquals(AbRunner.BIN_QUIC, AbRunner.binOf(AbRunner.GROUP_B, "H3")) // 大小写不敏感
        assertEquals(AbRunner.BIN_QUIC, AbRunner.binOf(AbRunner.GROUP_B, "quic/1+spdy/3"))
        // enableQuic 但实际协商到 TCP 侧协议：fallback，不得计入 QUIC 组
        assertEquals(AbRunner.BIN_FALLBACK, AbRunner.binOf(AbRunner.GROUP_B, "h2"))
        assertEquals(AbRunner.BIN_FALLBACK, AbRunner.binOf(AbRunner.GROUP_B, "http/1.1"))
        assertEquals(AbRunner.BIN_FALLBACK, AbRunner.binOf(AbRunner.GROUP_B, null))
        // "h30" 之类前缀撞车不得误判 h3
        assertEquals(AbRunner.BIN_FALLBACK, AbRunner.binOf(AbRunner.GROUP_B, "h30"))
    }

    // ------------------------------------------------------------ 汇总：fallback 单列不进对比

    private fun row(
        idx: Int,
        group: String,
        bin: String,
        proto: String?,
        error: String? = null,
        ttft: Double? = null,
        itlP50: Double? = null,
        itlP95: Double? = null,
    ) = AbResultEntity(
        runId = "r", startedAtEpochMs = 0L, serverBase = "https://x", stack = AbRunner.STACK,
        claimScope = AbRunner.CLAIM_SCOPE, profileId = "s2_coding_agent", phaseIndex = 0,
        sampleIndex = idx, groupLabel = group, bin = bin, negotiatedProtocol = proto,
        httpCode = if (error == null) 200 else null, error = error,
        ttftMs = ttft, itlP50Ms = itlP50, itlP95Ms = itlP95, itlSampleCount = 0,
        stallCount = null, stallRate = null, gapCount = 0, dupCount = 0,
        tokenEventCount = 0, truncatedEarly = false,
    )

    @Test
    fun `summarize excludes fallback samples from quic group`() {
        val rows = listOf(
            row(0, "a", AbRunner.BIN_TCP, "h2", ttft = 100.0, itlP50 = 20.0, itlP95 = 40.0),
            row(1, "b", AbRunner.BIN_QUIC, "h3", ttft = 80.0, itlP50 = 18.0, itlP95 = 35.0),
            row(2, "a", AbRunner.BIN_TCP, "h2", ttft = 120.0, itlP50 = 22.0, itlP95 = 44.0),
            // fallback 带极端值：若误入 QUIC 组，中位数必被拉偏
            row(3, "b", AbRunner.BIN_FALLBACK, "h2", ttft = 9999.0, itlP50 = 9999.0, itlP95 = 9999.0),
            row(4, "a", AbRunner.BIN_TCP, "http/1.1", ttft = 110.0, itlP50 = 21.0, itlP95 = 42.0),
            row(5, "b", AbRunner.BIN_QUIC, "h3", ttft = 90.0, itlP50 = 19.0, itlP95 = 36.0),
        )
        val s = AbRunner.summarize(rows)
        assertEquals(3, s.aN)
        assertEquals(2, s.quicN)
        assertEquals(1, s.fallbackN)
        assertEquals("h2", s.fallbackProtocols)
        assertEquals(110.0, s.aTtftP50Ms!!, 1e-9)
        assertEquals(21.0, s.aItlP50Ms!!, 1e-9)
        // QUIC 组中位数只来自 h3 样本（80,90 → 最近秩 P50 = 80）
        assertEquals(80.0, s.quicTtftP50Ms!!, 1e-9)
        assertEquals(18.0, s.quicItlP50Ms!!, 1e-9)
        assertTrue(s.comparable)
    }

    @Test
    fun `summarize with empty quic bin reports null not zero and not comparable`() {
        val rows = listOf(
            row(0, "a", AbRunner.BIN_TCP, "h2", ttft = 100.0, itlP50 = 20.0, itlP95 = 40.0),
            row(1, "b", AbRunner.BIN_FALLBACK, "h2", ttft = 90.0, itlP50 = 18.0, itlP95 = 30.0),
        )
        val s = AbRunner.summarize(rows)
        assertEquals(0, s.quicN)
        assertNull(s.quicTtftP50Ms) // R-10：无样本记 null，绝不 0
        assertNull(s.quicItlP50Ms)
        assertEquals(1, s.fallbackN)
        assertFalse(s.comparable)
    }

    @Test
    fun `summarize excludes transport-error samples from comparison`() {
        val rows = listOf(
            row(0, "a", AbRunner.BIN_TCP, "h2", ttft = 100.0, itlP50 = 20.0),
            row(1, "a", AbRunner.BIN_TCP, "h2", error = "net::ERR_FAILED", ttft = null, itlP50 = null),
            row(2, "b", AbRunner.BIN_QUIC, "h3", error = "net::ERR_QUIC_PROTOCOL_ERROR"),
            row(3, "b", AbRunner.BIN_QUIC, "h3", ttft = 80.0, itlP50 = 18.0),
        )
        val s = AbRunner.summarize(rows)
        assertEquals(1, s.aN)
        assertEquals(1, s.quicN)
        assertEquals(100.0, s.aTtftP50Ms!!, 1e-9)
        assertEquals(80.0, s.quicTtftP50Ms!!, 1e-9)
    }

    // ------------------------------------------------------------ 每样本 KPI

    /** sched==preFlush（flushΔ==schedΔ）→ corrected ITL == arrivalΔ，便于构造已知值 */
    private fun token(seq: Long, arrivalMs: Long, schedMs: Long = seq * 20) = TokenEvent(
        seq = seq,
        schedUs = schedMs * 1000,
        preFlushUs = schedMs * 1000,
        arrivalNanos = arrivalMs * 1_000_000,
        payloadBytes = 100,
        sameReadBatch = false,
    )

    @Test
    fun `sampleKpi computes itl median p95 and stall from corrected samples`() {
        // seq 0..8 到达间隔 20ms；(8,9) 到达间隔 250ms（sched 均匀 20ms → 非 pause，是 stall）
        val events = (0L..8L).map { token(it, arrivalMs = it * 20) } +
            token(9, arrivalMs = 8 * 20 + 250)
        val kpi = AbRunner.sampleKpi(
            events = events, preludeSrvTsUs = 0L, expectedTokens = 10,
            requestStartNanos = 0L,
        )
        assertEquals(9, kpi.itlSampleCount)
        // corrected：8×20ms + 1×(250−20+20)=250ms
        assertEquals(20.0, kpi.itlP50Ms!!, 1e-6)
        assertEquals(250.0, kpi.itlP95Ms!!, 1e-6) // 最近秩 ceil(0.95×9)=9 → 最大值
        assertEquals(1, kpi.stallCount)
        assertEquals(1.0 / 9.0, kpi.stallRate!!, 1e-9)
        assertEquals(0, kpi.gapCount)
        assertEquals(0, kpi.dupCount)
        assertFalse(kpi.truncatedEarly)
    }

    @Test
    fun `sampleKpi ttft strips server-known injection via prelude`() {
        // 首 token：arrival=100ms、sched_us=5000；prelude srv_ts_us=1000
        // 已知注入 = (5000−1000)/1e3 = 4ms → TTFT = 100 − 4 = 96ms
        val events = listOf(
            TokenEvent(0, 5_000, 5_000, 100_000_000, 100, false),
        )
        val kpi = AbRunner.sampleKpi(events, preludeSrvTsUs = 1_000, expectedTokens = 1, requestStartNanos = 0)
        assertEquals(96.0, kpi.ttftMs!!, 1e-6)
        // prelude 缺失：无法剥离服务端 dwell → TTFT 不出值（R-10/R-20）
        val noPrelude = AbRunner.sampleKpi(events, preludeSrvTsUs = null, expectedTokens = 1, requestStartNanos = 0)
        assertNull(noPrelude.ttftMs)
    }

    @Test
    fun `sampleKpi audits seq gaps duplicates and tail truncation`() {
        // seq 0,1,1,3；期望 6 → 区间缺 {2}=1，尾缺 {4,5}=2 → gaps=3；dup=1
        val events = listOf(token(0, 0), token(1, 20), token(1, 21), token(3, 60))
        val kpi = AbRunner.sampleKpi(events, preludeSrvTsUs = 0L, expectedTokens = 6, requestStartNanos = 0)
        assertEquals(3, kpi.gapCount)
        assertEquals(1, kpi.dupCount)
        assertTrue(kpi.truncatedEarly)
        assertEquals(4, kpi.tokenEventCount)
    }

    @Test
    fun `sampleKpi transport error yields null kpis but keeps audit counts`() {
        val events = listOf(token(0, 0), token(1, 20))
        val kpi = AbRunner.sampleKpi(
            events, preludeSrvTsUs = 0L, expectedTokens = 5, requestStartNanos = 0,
            transportError = true,
        )
        assertNull(kpi.ttftMs) // 失败样本不出值（R-10）
        assertNull(kpi.itlP50Ms)
        assertNull(kpi.stallRate)
        assertEquals(3, kpi.gapCount) // 尾缺 {2,3,4} 仍如实统计
        assertEquals(2, kpi.tokenEventCount)
        assertTrue(kpi.truncatedEarly)
    }

    // ------------------------------------------------------------ 分位数 / phase 定位

    @Test
    fun `percentile is nearest rank`() {
        val v = (1..10).map { it.toDouble() }
        assertEquals(5.0, AbRunner.percentile(v, 0.50)!!, 1e-9) // ceil(5)=5
        assertEquals(10.0, AbRunner.percentile(v, 0.95)!!, 1e-9) // ceil(9.5)=10
        assertNull(AbRunner.percentile(emptyList(), 0.5))
    }

    @Test
    fun `tokenStreamPhase indexes only token_stream phases`() {
        val profile = ScenarioProfile(
            profileId = "p", version = "1",
            phases = listOf(
                ProfilePhase(type = ProfilePhase.TYPE_CLOCK_SYNC, samples = 20),
                ProfilePhase(type = ProfilePhase.TYPE_UPLOAD_BURST, bytes = 1024),
                ProfilePhase(type = ProfilePhase.TYPE_TOKEN_STREAM, tokens = 300),
                ProfilePhase(type = ProfilePhase.TYPE_TOOL_LOOP, rounds = 8),
                ProfilePhase(type = ProfilePhase.TYPE_TOKEN_STREAM, tokens = 800),
            ),
        )
        assertEquals(300, AbRunner.tokenStreamPhase(profile, 0)?.tokens)
        assertEquals(800, AbRunner.tokenStreamPhase(profile, 1)?.tokens)
        assertNull(AbRunner.tokenStreamPhase(profile, 2))
    }
}
