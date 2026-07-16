package com.aneb.probe.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P3-C05 retrans 共变量单测：netem 100ms/1% 取证（evidence/phase3/
 * netem_experiments_20260713.md 断言 3）发现丢包重传批化与 nginx proxy_buffering
 * 批化签名同形被误报 MIDDLEBOX_SUSPECT——引入服务端 TCP_INFO retrans 共变量后：
 *  - 批化特征存在且 retransRate 显著（> RETRANS_RATE_SIGNIFICANT）→ RETRANS_SUSPECT；
 *  - 无共变量数据（null）→ 行为与引入前**完全一致**（零回归合同）；
 *  - retrans 只改写"原本判 MIDDLEBOX"的结论，绝不触碰 R-05 弱信号红线 /
 *    空口网格 / DEVICE_SIDE / INDETERMINATE 分支。
 */
class RetransCovariateTest {

    // ---------- 1. 显著 retrans：MIDDLEBOX → RETRANS_SUSPECT ----------

    /** 无 R1、连续分布批化 + 显著重传 → RETRANS_SUSPECT（netem 误报场景的修复本体）。 */
    @Test
    fun nginxLikeBatching_significantRetrans_attributedRetransSuspect() {
        val report = BufferingDetector.analyze(
            SyntheticResidualStreams.nginxBatching(),
            retransRate = 0.05,
        )
        assertEquals(BufferingAttribution.RETRANS_SUSPECT, report.attribution)
        assertEquals("原始共变量值透出供标定", 0.05, report.retransRate!!, 1e-9)
    }

    /** 信号优 + 批化 + 显著重传 → 同样改判 RETRANS_SUSPECT（radioGood 分支覆盖）。 */
    @Test
    fun goodSignalBatching_significantRetrans_attributedRetransSuspect() {
        val report = BufferingDetector.analyze(
            SyntheticResidualStreams.nginxBatching(),
            radio = RadioSummary(rsrpMedianDbm = -85.0, sinrMedianDb = 15.0),
            retransRate = 0.05,
        )
        assertEquals(true, report.radioGood)
        assertEquals(BufferingAttribution.RETRANS_SUSPECT, report.attribution)
    }

    // ---------- 2. 缺数据回退：与现状完全一致（零回归合同） ----------

    /** retransRate=null（非 Linux 服务端/h3/summary 缺失）→ 既有 MIDDLEBOX 判定原样保留。 */
    @Test
    fun noRetransData_behaviorUnchanged_middleboxPreserved() {
        val without = BufferingDetector.analyze(SyntheticResidualStreams.nginxBatching())
        val withNull = BufferingDetector.analyze(SyntheticResidualStreams.nginxBatching(), retransRate = null)
        assertEquals(BufferingAttribution.MIDDLEBOX_SUSPECT, without.attribution)
        assertEquals("null 共变量必须与不传参完全一致", without, withNull)
        assertNull(without.retransRate)
    }

    // ---------- 3. 阈值边界 ----------

    /** 低于显著线（干净路径/零星散粒重传）→ 仍判 MIDDLEBOX（nginx 真中间盒不受影响）。 */
    @Test
    fun belowThresholdRetrans_stillMiddlebox() {
        val report = BufferingDetector.analyze(
            SyntheticResidualStreams.nginxBatching(),
            retransRate = 0.001,
        )
        assertEquals(BufferingAttribution.MIDDLEBOX_SUSPECT, report.attribution)
        assertEquals(0.001, report.retransRate!!, 1e-9)
        // 干净/纯中间盒路径的物理零点：rate=0.0 必不显著
        assertEquals(
            BufferingAttribution.MIDDLEBOX_SUSPECT,
            BufferingDetector.analyze(SyntheticResidualStreams.nginxBatching(), retransRate = 0.0).attribution,
        )
    }

    /** 恰在阈值上（=0.02，严格大于才显著）→ 不改判。 */
    @Test
    fun exactlyAtThreshold_notSignificant_middleboxPreserved() {
        val report = BufferingDetector.analyze(
            SyntheticResidualStreams.nginxBatching(),
            retransRate = BufferingDetector.RETRANS_RATE_SIGNIFICANT,
        )
        assertEquals(BufferingAttribution.MIDDLEBOX_SUSPECT, report.attribution)
    }

    // ---------- 4. 不触碰非 MIDDLEBOX 分支 ----------

    /** R-05 红线不受 retrans 影响：弱信号 + 批化 + 显著重传 → 仍 AIRLINK_SUSPECT。 */
    @Test
    fun weakSignal_significantRetrans_airlinkRedLineUntouched() {
        val report = BufferingDetector.analyze(
            SyntheticResidualStreams.nginxBatching(),
            radio = RadioSummary(rsrpMedianDbm = -112.0, sinrMedianDb = -3.0),
            retransRate = 0.05,
        )
        assertEquals(true, report.radioWeak)
        assertEquals(BufferingAttribution.AIRLINK_SUSPECT, report.attribution)
    }

    /** 空口离散网格命中 + 显著重传 → 仍 AIRLINK_SUSPECT（网格证据优先级不变）。 */
    @Test
    fun drxGridPeriodicity_significantRetrans_airlinkPreserved() {
        val report = BufferingDetector.analyze(
            SyntheticResidualStreams.drxMicroBatching(cycleUs = 20_000),
            retransRate = 0.05,
        )
        assertEquals(BufferingAttribution.AIRLINK_SUSPECT, report.attribution)
    }

    /** 单一大批（批起点不足）+ 显著重传 → 仍 INDETERMINATE（retrans 只改写 MIDDLEBOX）。 */
    @Test
    fun singleBigBatch_significantRetrans_indeterminatePreserved() {
        val report = BufferingDetector.analyze(
            SyntheticResidualStreams.singleBigBatch(),
            retransRate = 0.05,
        )
        assertEquals(BufferingAttribution.INDETERMINATE, report.attribution)
    }

    /** 干净流 + 显著重传 → 仍 NONE（无批化特征时 retrans 不制造归因）。 */
    @Test
    fun cleanStream_significantRetrans_noneStillNone() {
        val report = BufferingDetector.analyze(
            SyntheticResidualStreams.clean(),
            retransRate = 0.05,
        )
        assertEquals(BufferingAttribution.NONE, report.attribution)
    }
}
