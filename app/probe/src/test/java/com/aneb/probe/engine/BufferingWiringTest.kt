package com.aneb.probe.engine

import com.aneb.probe.data.EnvEvent
import com.aneb.probe.data.EnvEventType
import com.aneb.probe.net.TokenEvent
import com.aneb.probe.scoring.BufferingAttribution
import com.aneb.probe.scoring.BufferingDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-C08 接线单测：token 事件 → 残差样本变换（BufferingWiring.residualSamples）、
 * R1/jank 联动取材的窗口过滤，以及"干净流→零残差→score=0/NONE"的接线端到端合同
 * （R-05：接线层绝不产出会改 validity 的东西——这里锚定其输出仅为标注数据）。
 */
class BufferingWiringTest {

    private fun ev(
        seq: Long,
        preFlushUs: Long,
        arrivalNanos: Long,
        sameReadBatch: Boolean = false,
    ) = TokenEvent(
        seq = seq,
        schedUs = preFlushUs, // sched 不参与残差；给同值即可
        preFlushUs = preFlushUs,
        arrivalNanos = arrivalNanos,
        payloadBytes = 3,
        sameReadBatch = sameReadBatch,
    )

    private fun stream(expected: Int, events: List<TokenEvent>) =
        ScenarioKpi.StreamTokens(expected, events)

    // ---------- 残差构造 ----------

    @Test
    fun residualIsArrivalIntervalMinusFlushInterval() {
        // flush 间隔 20ms、到达间隔 30ms → 残差 +10ms；到达间隔 15ms → 残差 −5ms（不 clamp）
        val events = listOf(
            ev(0, preFlushUs = 0, arrivalNanos = 1_000_000_000L),
            ev(1, preFlushUs = 20_000, arrivalNanos = 1_030_000_000L),
            ev(2, preFlushUs = 40_000, arrivalNanos = 1_045_000_000L),
        )
        val out = BufferingWiring.residualSamples(listOf(stream(3, events)))
        assertEquals(2, out.size)
        assertEquals(1L, out[0].seq)
        assertEquals(30_000L, out[0].arrivalIntervalUs)
        assertEquals(10_000L, out[0].residualUs)
        assertEquals(1_030_000L, out[0].arrivalUs) // µs，与 jank 事件同基准
        assertEquals(2L, out[1].seq)
        assertEquals(-5_000L, out[1].residualUs) // 负残差保留（5.3.4 不 clamp）
    }

    @Test
    fun pairsWithMissingPreFlushAreSkipped() {
        // seq1 preFlushUs=-1（wire 缺失）→ (0,1) 与 (1,2) 两对都不可算（R-10 不造值）
        val events = listOf(
            ev(0, preFlushUs = 0, arrivalNanos = 1_000_000_000L),
            ev(1, preFlushUs = -1, arrivalNanos = 1_020_000_000L),
            ev(2, preFlushUs = 40_000, arrivalNanos = 1_040_000_000L),
        )
        val out = BufferingWiring.residualSamples(listOf(stream(3, events)))
        assertTrue(out.isEmpty())
    }

    @Test
    fun seqGapBreaksPairingAndDuplicateKeepsFirstSeen() {
        val events = listOf(
            ev(0, preFlushUs = 0, arrivalNanos = 1_000_000_000L),
            ev(0, preFlushUs = 999, arrivalNanos = 9_000_000_000L), // 重复 seq：首见获胜
            ev(2, preFlushUs = 40_000, arrivalNanos = 1_040_000_000L), // gap：无 seq1
            ev(3, preFlushUs = 60_000, arrivalNanos = 1_070_000_000L),
        )
        val out = BufferingWiring.residualSamples(listOf(stream(4, events)))
        // 只有 (2,3) 可配对：到达间隔 30ms − flush 间隔 20ms = +10ms
        assertEquals(1, out.size)
        assertEquals(3L, out[0].seq)
        assertEquals(10_000L, out[0].residualUs)
    }

    @Test
    fun multiStreamRenumbersAndNeverPairsAcrossBoundary() {
        // 两段流（S2 形态）：流间的"间隔"内含 tool_loop 整段时长，禁配对
        val s0 = listOf(
            ev(0, preFlushUs = 0, arrivalNanos = 1_000_000_000L),
            ev(1, preFlushUs = 20_000, arrivalNanos = 1_020_000_000L),
        )
        val s1 = listOf(
            ev(0, preFlushUs = 0, arrivalNanos = 5_000_000_000L),
            ev(1, preFlushUs = 20_000, arrivalNanos = 5_020_000_000L),
        )
        val out = BufferingWiring.residualSamples(listOf(stream(300, s0), stream(300, s1)))
        assertEquals(2, out.size)
        // 流 0 样本 seq=1；流 1 样本 seq 平移 base=300 → 301（场景内唯一）
        assertEquals(listOf(1L, 301L), out.map { it.seq })
        // 跨边界对（流0 seq1 → 流1 seq0，间隔 ~4s）绝不出现
        assertTrue(out.none { it.arrivalIntervalUs > 1_000_000L })
    }

    // ---------- R1 / jank 联动取材（场景窗口过滤） ----------

    @Test
    fun radioSummaryFiltersWindowAndTakesMedian() {
        fun sample(ts: Long, rsrp: Int?, sinr: Int?) = com.aneb.probe.radio.RadioSample(
            tsNanos = ts, cellTsNanos = null, stale = false, subId = 1, subSwitched = false,
            networkType = "LTE", overrideType = null, nrState = "NONE",
            rat = "LTE", pci = 1, tac = 1, arfcn = 100, rsrp = rsrp, rsrq = null, sinr = sinr,
            operatorName = null,
        )
        val samples = listOf(
            sample(50L, rsrp = -140, sinr = -20), // 窗口前：剔除
            sample(100L, rsrp = -100, sinr = 5),
            sample(200L, rsrp = -110, sinr = null),
            sample(300L, rsrp = -90, sinr = 15),
            sample(999L, rsrp = -60, sinr = 30), // 窗口后：剔除
        )
        val sum = BufferingWiring.radioSummary(samples, startNanos = 100L, endNanos = 300L)
        assertNotNull(sum)
        assertEquals(-100.0, sum!!.rsrpMedianDbm!!, 1e-9) // [-110,-100,-90] 中位
        assertEquals(15.0, sum.sinrMedianDb!!, 1e-9) // [5,15] 上中位
        // 窗口内无任何信号值 → null（检测器当"无无线信息"）
        assertNull(BufferingWiring.radioSummary(listOf(sample(100L, null, null)), 0L, 200L))
        assertNull(BufferingWiring.radioSummary(emptyList(), 0L, 200L))
    }

    @Test
    fun jankEventsFilterTypeAndWindowAndConvertToMicros() {
        val events = listOf(
            EnvEvent(1_000_000L, EnvEventType.APP_JANK, "jank 40ms"),
            EnvEvent(2_000_000L, EnvEventType.THERMAL, "severe"), // 非 jank：剔除
            EnvEvent(3_000_000L, EnvEventType.APP_JANK, "jank 35ms"),
            EnvEvent(9_000_000L, EnvEventType.APP_JANK, "jank 50ms"), // 窗口外：剔除
        )
        val out = BufferingWiring.jankEventsUs(events, startNanos = 500_000L, endNanos = 4_000_000L)
        assertEquals(listOf(1_000L, 3_000L), out)
    }

    // ---------- retrans 共变量聚合（P3-C05） ----------

    @Test
    fun retransRate_noStreamHasData_returnsNull() {
        // 全部流无 retrans 数据（非 Linux 服务端/h3/无 summary）→ null：
        // 检测器按无共变量数据回退，行为与引入前完全一致（零回归合同）
        assertNull(BufferingWiring.retransRate(emptyList()))
        assertNull(
            BufferingWiring.retransRate(
                listOf(
                    BufferingWiring.StreamRetrans(retransTotal = null, eventCount = 100),
                    BufferingWiring.StreamRetrans(retransTotal = null, eventCount = 200),
                )
            )
        )
    }

    @Test
    fun retransRate_zeroEventDenominator_returnsNull() {
        // 有 retrans 数据但事件数为 0（截断流）→ 率不可算，null 而非除零/造值
        assertNull(
            BufferingWiring.retransRate(
                listOf(BufferingWiring.StreamRetrans(retransTotal = 5L, eventCount = 0))
            )
        )
    }

    @Test
    fun retransRate_takesMaxOverStreamsAndSumsEventsWithData() {
        // tcpi_total_retrans 是连接累计值：场景内多流复用同连接时后一流已含前一流，
        // 分子取 max（同连接下即连接累计真值）、分母取带数据流的事件数之和
        val rate = BufferingWiring.retransRate(
            listOf(
                BufferingWiring.StreamRetrans(retransTotal = 3L, eventCount = 100),
                BufferingWiring.StreamRetrans(retransTotal = 8L, eventCount = 300),
            )
        )
        assertEquals(8.0 / 400.0, rate!!, 1e-12)
    }

    @Test
    fun retransRate_mixedDataStreams_excludesNoDataEventsFromDenominator() {
        // 无数据流（如 h3 分支）的事件不进分母——分子分母保持同一观测范围
        val rate = BufferingWiring.retransRate(
            listOf(
                BufferingWiring.StreamRetrans(retransTotal = null, eventCount = 500),
                BufferingWiring.StreamRetrans(retransTotal = 4L, eventCount = 200),
            )
        )
        assertEquals(4.0 / 200.0, rate!!, 1e-12)
        // 干净路径 retrans=0 → 率 0.0（有数据的 0 与 null 语义不同，如实透出）
        val zero = BufferingWiring.retransRate(
            listOf(BufferingWiring.StreamRetrans(retransTotal = 0L, eventCount = 150))
        )
        assertEquals(0.0, zero!!, 1e-12)
    }

    // ---------- 接线端到端合同 ----------

    @Test
    fun cleanStreamYieldsZeroScoreAndNoneAttribution() {
        // 干净流：到达间隔恒等于 flush 间隔 → 全零残差 → score=0、NONE（不会误标注）
        val events = (0..20L).map { i ->
            ev(i, preFlushUs = i * 20_000, arrivalNanos = 1_000_000_000L + i * 20_000_000L)
        }
        val residuals = BufferingWiring.residualSamples(listOf(stream(21, events)))
        assertEquals(20, residuals.size)
        val report = BufferingDetector.analyze(residuals)
        assertEquals(0.0, report.bufferingScore, 1e-9)
        assertEquals(BufferingAttribution.NONE, report.attribution)
        assertEquals(residuals.size, report.sampleCount) // 不丢样本红线
    }

    @Test
    fun batchedStreamYieldsPositiveScoreViaWiring() {
        // 攒-放形态：每 5 个 token 攒成一批——批首吸收 4 段静默（正尖峰），
        // 批内背靠背到达（近零间隔+负残差簇）
        val events = ArrayList<TokenEvent>()
        var arrival = 1_000_000_000L
        for (i in 0 until 40L) {
            if (i % 5 == 0L && i > 0) arrival += 100_000_000L // 批间 gap 100ms
            events.add(ev(i, preFlushUs = i * 20_000, arrivalNanos = arrival))
            arrival += 100_000L // 批内 0.1ms 背靠背
        }
        val residuals = BufferingWiring.residualSamples(listOf(stream(40, events)))
        val report = BufferingDetector.analyze(residuals)
        assertTrue("score=${report.bufferingScore}", report.bufferingScore > 0.25)
        assertTrue(report.batchCount > 0)
    }
}
