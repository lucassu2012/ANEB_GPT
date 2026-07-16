package com.aneb.probe.scoring

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BufferingDetector 单测（P1-C08，KPI 5.3.3 / 红队 R-05）。
 *
 * 合成签名：干净流、S2 设计停顿（残差域剥离）、nginx 式攒批（中间盒）、
 * DRX 式微批（空口网格）、设备冻结（app_jank 重叠）、稀疏零星批化（不误报）、
 * 单一大批（INDETERMINATE）；以及锯齿/自相关/输入健壮性边界。
 */
class BufferingDetectorTest {

    // ---------- 构造辅助：直接给定残差序列（间隔/到达仅占位） ----------

    private fun samplesFromResiduals(
        residualsUs: List<Long>,
        arrivalIntervalUs: Long = 10_000,
    ): List<ResidualSample> {
        var arrival = 1_000_000L
        return residualsUs.mapIndexed { idx, r ->
            arrival += arrivalIntervalUs
            ResidualSample(seq = (idx + 2).toLong(), arrivalUs = arrival, arrivalIntervalUs = arrivalIntervalUs, residualUs = r)
        }
    }

    // ---------- 1. 干净流 ----------

    @Test
    fun clean_scoreNearZero_attributionNone() {
        val report = BufferingDetector.analyze(SyntheticResidualStreams.clean())
        assertTrue("干净流 score 应≈0，实际 ${report.bufferingScore}", report.bufferingScore < 0.1)
        assertEquals(BufferingAttribution.NONE, report.attribution)
        assertEquals(0.0, report.sawtoothRatio, 1e-9)
        assertEquals(0.0, report.nearZeroArrivalRatio, 1e-9)
        assertEquals(0, report.batchCount)
    }

    /** R-05 核心：S2 式 300–800ms 设计停顿在残差域被剥离，不触发批化。 */
    @Test
    fun cleanWithDesignPauses_profileRhythmStrippedInResidualDomain() {
        val report = BufferingDetector.analyze(SyntheticResidualStreams.cleanWithDesignPauses())
        assertTrue("设计停顿不应抬高 score，实际 ${report.bufferingScore}", report.bufferingScore < 0.1)
        assertEquals(BufferingAttribution.NONE, report.attribution)
        assertEquals(0.0, report.sawtoothRatio, 1e-9)
    }

    // ---------- 2. nginx 式攒批（周期性大批 + 长静默 → 中间盒） ----------

    @Test
    fun nginxBatching_highBufferingScore() {
        val report = BufferingDetector.analyze(SyntheticResidualStreams.nginxBatching())
        assertTrue("nginx 式攒批 score 应高，实际 ${report.bufferingScore}", report.bufferingScore > 0.6)
    }

    @Test
    fun nginxBatching_sawtoothAndNearZeroDominant() {
        val report = BufferingDetector.analyze(SyntheticResidualStreams.nginxBatching())
        assertTrue("锯齿占比应高，实际 ${report.sawtoothRatio}", report.sawtoothRatio > 0.8)
        assertTrue("近零间隔占比应高，实际 ${report.nearZeroArrivalRatio}", report.nearZeroArrivalRatio > 0.7)
        assertTrue("正尖峰占比应>0", report.positiveSpikeRatio > 0.0)
    }

    @Test
    fun nginxBatching_noRadio_attributedMiddlebox_noGridHit() {
        val report = BufferingDetector.analyze(SyntheticResidualStreams.nginxBatching())
        assertEquals(BufferingAttribution.MIDDLEBOX_SUSPECT, report.attribution)
        assertFalse("连续抖动的攒批周期不应命中离散网格", report.airlinkPeriodicity)
        assertNull(report.bestGridUs)
        val median = report.interBatchMedianUs
        assertNotNull(median)
        assertTrue("批间隔中位数应≈150ms，实际 $median", median!! in 140_000..160_000)
    }

    @Test
    fun nginxBatching_goodSignal_attributedMiddlebox() {
        val report = BufferingDetector.analyze(
            SyntheticResidualStreams.nginxBatching(),
            radio = RadioSummary(rsrpMedianDbm = -85.0, sinrMedianDb = 15.0),
        )
        assertEquals(true, report.radioGood)
        assertEquals(BufferingAttribution.MIDDLEBOX_SUSPECT, report.attribution)
    }

    /** R-05 红线：弱信号 + 批化 → AIRLINK_SUSPECT 而非 MIDDLEBOX（即使形态像中间盒）。 */
    @Test
    fun nginxBatching_weakSignal_attributedAirlink_R05RedLine() {
        val report = BufferingDetector.analyze(
            SyntheticResidualStreams.nginxBatching(),
            radio = RadioSummary(rsrpMedianDbm = -112.0, sinrMedianDb = -3.0),
        )
        assertEquals(true, report.radioWeak)
        assertEquals(BufferingAttribution.AIRLINK_SUSPECT, report.attribution)
    }

    // ---------- 3. DRX 式微批（离散网格周期 → 空口） ----------

    @Test
    fun drx20ms_bestGridDetected() {
        val report = BufferingDetector.analyze(SyntheticResidualStreams.drxMicroBatching(cycleUs = 20_000))
        assertTrue(report.airlinkPeriodicity)
        assertEquals(20_000L, report.bestGridUs)
        assertTrue("20ms 网格命中率应≥0.7，实际 ${report.bestGridHitRatio}", report.bestGridHitRatio >= 0.7)
        assertTrue("批起点应足量", report.batchCount >= BufferingDetector.MIN_BATCH_COUNT)
    }

    @Test
    fun drx20ms_attributedAirlink_withoutRadio() {
        val report = BufferingDetector.analyze(SyntheticResidualStreams.drxMicroBatching(cycleUs = 20_000))
        assertTrue("DRX 微批 score 应过活跃线，实际 ${report.bufferingScore}",
            report.bufferingScore >= BufferingDetector.SCORE_ACTIVE_THRESHOLD)
        assertEquals(BufferingAttribution.AIRLINK_SUSPECT, report.attribution)
    }

    @Test
    fun drx10ms_bestGridDetected() {
        val report = BufferingDetector.analyze(
            SyntheticResidualStreams.drxMicroBatching(cycleUs = 10_000, sendStepUs = 5_000)
        )
        assertTrue(report.airlinkPeriodicity)
        assertEquals(10_000L, report.bestGridUs)
        assertEquals(BufferingAttribution.AIRLINK_SUSPECT, report.attribution)
    }

    // ---------- 4. 设备侧冻结（app_jank 重叠 → DEVICE_SIDE，R-12） ----------

    @Test
    fun deviceFreeze_jankOverlap_attributedDeviceSide() {
        val (samples, jank) = SyntheticResidualStreams.deviceFreeze()
        val report = BufferingDetector.analyze(samples, appJankEventsUs = jank)
        assertTrue("冻结批化 score 应过活跃线，实际 ${report.bufferingScore}",
            report.bufferingScore >= BufferingDetector.SCORE_ACTIVE_THRESHOLD)
        assertTrue("jank 重叠率应≥0.5，实际 ${report.jankOverlapRatio}",
            report.jankOverlapRatio >= BufferingDetector.JANK_OVERLAP_THRESHOLD)
        assertEquals(BufferingAttribution.DEVICE_SIDE, report.attribution)
    }

    @Test
    fun deviceFreeze_withoutJankEvents_notDeviceSide() {
        val (samples, _) = SyntheticResidualStreams.deviceFreeze()
        val report = BufferingDetector.analyze(samples)
        assertEquals(0.0, report.jankOverlapRatio, 1e-9)
        assertNotEquals(BufferingAttribution.DEVICE_SIDE, report.attribution)
        // 无 jank/R1 佐证、批间隔连续 → 按连续谱倾向中间盒（原始特征仍全量透出）
        assertEquals(BufferingAttribution.MIDDLEBOX_SUSPECT, report.attribution)
    }

    // ---------- 5. 稀疏零星批化（低分不误报） ----------

    @Test
    fun sparseBatching_lowScore_noFalsePositive() {
        val report = BufferingDetector.analyze(SyntheticResidualStreams.sparseBatching())
        assertTrue("稀疏批化 score 应低于活跃线，实际 ${report.bufferingScore}",
            report.bufferingScore < BufferingDetector.SCORE_ACTIVE_THRESHOLD)
        assertEquals(BufferingAttribution.NONE, report.attribution)
        assertTrue("零星批化的锯齿仍应被如实记录", report.sawtoothRatio > 0.0)
    }

    // ---------- 6. 单一大批：高分但区分特征不足 → INDETERMINATE ----------

    @Test
    fun singleBigBatch_highScoreButIndeterminate() {
        val report = BufferingDetector.analyze(SyntheticResidualStreams.singleBigBatch())
        assertTrue("单一大批 score 应过活跃线，实际 ${report.bufferingScore}",
            report.bufferingScore >= BufferingDetector.SCORE_ACTIVE_THRESHOLD)
        assertTrue(report.batchCount < BufferingDetector.MIN_BATCH_COUNT)
        assertEquals(BufferingAttribution.INDETERMINATE, report.attribution)
    }

    // ---------- 7. 锯齿特征边界 ----------

    @Test
    fun sawtoothRatio_manualSpikeThenNegativeCluster() {
        // 10 个样本：1 尖峰 + 3 负簇成员 = 4 个锯齿段样本 → 0.4
        val samples = samplesFromResiduals(
            listOf(0, 0, 20_000, -10_000, -10_000, -10_000, 0, 0, 0, 0)
        )
        val report = BufferingDetector.analyze(samples)
        assertEquals(0.4, report.sawtoothRatio, 1e-9)
        assertEquals(0.1, report.positiveSpikeRatio, 1e-9)
        assertEquals(0.3, report.negativeClusterRatio, 1e-9)
    }

    @Test
    fun sawtoothRatio_spikeWithoutNegativeClusterNotCounted() {
        // 尖峰后无负簇（纯正向抖动）不构成攒-放锯齿
        val samples = samplesFromResiduals(listOf(0, 0, 20_000, 0, 0, 20_000, 0, 0, 0, 0))
        val report = BufferingDetector.analyze(samples)
        assertEquals(0.0, report.sawtoothRatio, 1e-9)
        assertEquals(0.2, report.positiveSpikeRatio, 1e-9)
    }

    // ---------- 8. 自相关边界 ----------

    @Test
    fun lag1_alternatingResidual_nearMinusOne() {
        val samples = samplesFromResiduals(List(100) { if (it % 2 == 0) 10_000L else -10_000L })
        val report = BufferingDetector.analyze(samples)
        assertTrue("交替序列 r1 应趋近 -1，实际 ${report.lag1Autocorrelation}",
            report.lag1Autocorrelation < -0.9)
        assertTrue("对干净基线的偏离分量应接近 1", report.autocorrelationComponent > 0.8)
    }

    @Test
    fun lag1_constantResidual_degenerateToZeroComponent() {
        val samples = samplesFromResiduals(List(50) { 5_000L })
        val report = BufferingDetector.analyze(samples)
        assertEquals(0.0, report.lag1Autocorrelation, 1e-9)
        assertEquals("方差退化时自相关分量按 0 计", 0.0, report.autocorrelationComponent, 1e-9)
    }

    /** 干净物理流的残差是 MA(1)：r1 理论值 −0.5（CLEAN_LAG1_BASELINE 的依据）。 */
    @Test
    fun lag1_cleanStream_nearMa1Baseline() {
        val report = BufferingDetector.analyze(SyntheticResidualStreams.clean(n = 800))
        assertTrue("干净流 r1 应≈-0.5，实际 ${report.lag1Autocorrelation}",
            report.lag1Autocorrelation in -0.65..-0.35)
        assertTrue("干净流自相关分量应低，实际 ${report.autocorrelationComponent}",
            report.autocorrelationComponent < 0.35)
    }

    @Test
    fun lag1_whiteNoiseResidual_maxDeviationFromBaseline() {
        // 残差本身为白噪声（r1≈0）意味着显著偏离干净 MA(1) 基线 → 分量接近 1
        val rnd = Random(7)
        val samples = samplesFromResiduals(List(500) { rnd.nextLong(-5_000L, 5_001L) })
        val report = BufferingDetector.analyze(samples)
        assertTrue("白噪声残差 r1 应≈0，实际 ${report.lag1Autocorrelation}",
            report.lag1Autocorrelation in -0.15..0.15)
        assertTrue(report.autocorrelationComponent > 0.7)
    }

    // ---------- 9. 输入健壮性（R-05：不丢样本、不裁决） ----------

    @Test
    fun emptyInput_safeZeroReport() {
        val report = BufferingDetector.analyze(emptyList())
        assertEquals(0, report.sampleCount)
        assertEquals(0.0, report.bufferingScore, 1e-9)
        assertEquals(BufferingAttribution.NONE, report.attribution)
        assertEquals(BufferingDetector.PERIODICITY_GRIDS_US.size, report.gridHits.size)
    }

    @Test
    fun singleSample_safeNoAttribution() {
        val report = BufferingDetector.analyze(
            listOf(ResidualSample(seq = 2, arrivalUs = 1_000, arrivalIntervalUs = 10_000, residualUs = -2_000))
        )
        assertEquals(1, report.sampleCount)
        assertEquals(BufferingAttribution.NONE, report.attribution)
    }

    @Test
    fun inputOrderIndependent_andNoSampleDropped() {
        val samples = SyntheticResidualStreams.nginxBatching()
        val shuffled = samples.shuffled(Random(9))
        val a = BufferingDetector.analyze(samples)
        val b = BufferingDetector.analyze(shuffled)
        assertEquals("样本数恒等于输入数（不丢样本）", samples.size, a.sampleCount)
        assertEquals("seq 排序后与输入顺序无关", a, b)
    }

    @Test
    fun negativeResiduals_notClampedAndExposed() {
        val samples = SyntheticResidualStreams.nginxBatching()
        assertTrue("合成流应含负残差", samples.any { it.residualUs < 0 })
        val report = BufferingDetector.analyze(samples)
        assertTrue("负残差簇占比应如实透出", report.negativeClusterRatio > 0.5)
        assertTrue(report.negativeResidualRatio >= report.negativeClusterRatio)
    }

    @Test
    fun score_alwaysWithin01() {
        val streams = listOf(
            SyntheticResidualStreams.clean(),
            SyntheticResidualStreams.nginxBatching(),
            SyntheticResidualStreams.drxMicroBatching(),
            SyntheticResidualStreams.sparseBatching(),
            SyntheticResidualStreams.singleBigBatch(),
            SyntheticResidualStreams.deviceFreeze().first,
            samplesFromResiduals(List(100) { if (it % 2 == 0) 500_000L else -500_000L }),
        )
        for (s in streams) {
            val r = BufferingDetector.analyze(s)
            assertTrue("score 越界: ${r.bufferingScore}", r.bufferingScore in 0.0..1.0)
        }
    }
}
