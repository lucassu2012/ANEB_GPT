package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T45/D-467 §6.3①：M7 最长帧间静默 + 近零到达占比的纯函数覆盖（VoiceRunner companion
 * object，D-390 订正后的判据——max 不丢尾，near-zero 占比答"有没有发生"）。
 */
class VoiceRunnerTest {

    @Test
    fun `maxFrameGapMs empty intervals is null not zero (R-10)`() {
        assertNull(VoiceRunner.maxFrameGapMs(emptyList()))
    }

    @Test
    fun `maxFrameGapMs picks the raw max not a percentile`() {
        // D-390 的核心教训：P95 会把这类罕见长冻结丢在尾部；max 必须原样保留
        val intervalsUs = listOf(20_000L, 20_000L, 20_000L, 4_500_000L, 20_000L)
        assertEquals(4500.0, VoiceRunner.maxFrameGapMs(intervalsUs)!!, 1e-9)
    }

    @Test
    fun `maxFrameGapMs converts microseconds to milliseconds`() {
        assertEquals(1.5, VoiceRunner.maxFrameGapMs(listOf(1_500L))!!, 1e-9)
    }

    @Test
    fun `nearZeroArrivalRatio empty intervals is null not zero (R-10)`() {
        assertNull(VoiceRunner.nearZeroArrivalRatio(emptyList()))
    }

    @Test
    fun `nearZeroArrivalRatio counts intervals strictly under the shared BufferingDetector threshold`() {
        // BufferingDetector.NEAR_ZERO_ARRIVAL_US = 1_000（µs），区间 [0,1000) 半开——
        // 999 计入、恰好 1000 不计入；两个函数必须共用同一常量,不得各自另定义边界。
        val intervalsUs = listOf(999L, 1_000L, 0L, 5_000L)
        assertEquals(0.5, VoiceRunner.nearZeroArrivalRatio(intervalsUs)!!, 1e-9) // 999 与 0 计入，共 2/4
    }

    @Test
    fun `nearZeroArrivalRatio all near-zero is 1_0 not clamped away`() {
        assertEquals(1.0, VoiceRunner.nearZeroArrivalRatio(listOf(0L, 100L, 500L))!!, 1e-9)
    }

    @Test
    fun `nearZeroArrivalRatio none near-zero is 0_0 not null (real zero, not absence)`() {
        assertEquals(0.0, VoiceRunner.nearZeroArrivalRatio(listOf(20_000L, 20_000L))!!, 1e-9)
    }
}
