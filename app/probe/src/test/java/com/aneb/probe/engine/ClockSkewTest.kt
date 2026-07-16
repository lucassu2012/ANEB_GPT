package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 双 clock_sync skew 插值与漂移率（C06/R-22，P1 新增 ≥10 单测的一部分）。 */
class ClockSkewTest {

    private fun point(offsetUs: Long?, midUs: Long?, err: Long? = 100L) =
        ClockSyncPoint(offsetUs, if (offsetUs == null) null else err, midUs, if (offsetUs == null) 0 else 17)

    @Test
    fun `双端可用时中点线性插值`() {
        val track = OffsetTrack(point(1000L, 0L), point(1900L, 9_000_000L))
        assertEquals(1450L, track.offsetAtUs(4_500_000L))
        // 端点处取端点值
        assertEquals(1000L, track.offsetAtUs(0L))
        assertEquals(1900L, track.offsetAtUs(9_000_000L))
    }

    @Test
    fun `外插沿漂移斜率延伸`() {
        val track = OffsetTrack(point(0L, 0L), point(900L, 9_000_000L)) // 100ppm
        assertEquals(1000L, track.offsetAtUs(10_000_000L))
    }

    @Test
    fun `漂移率恰为 100ppm 不置疑 超过即置疑`() {
        // 900us / 9s = 100ppm：边界值不置疑（阈值语义为 >100ppm）
        val atBoundary = OffsetTrack(point(0L, 0L), point(900L, 9_000_000L))
        assertNotNull(atBoundary.driftPpm)
        assertEquals(100.0, atBoundary.driftPpm!!, 1e-9)
        assertFalse(atBoundary.offsetSuspect)

        // 901us / 9s ≈ 100.1ppm：置疑
        val above = OffsetTrack(point(0L, 0L), point(901L, 9_000_000L))
        assertTrue(above.offsetSuspect)

        // 负向漂移同样按绝对值判定
        val negative = OffsetTrack(point(0L, 0L), point(-901L, 9_000_000L))
        assertTrue(negative.offsetSuspect)
    }

    @Test
    fun `尾端缺失退化为常数 offset 且保守置疑`() {
        val track = OffsetTrack(point(1000L, 0L), point(null, null))
        assertNull(track.driftPpm)
        assertTrue(track.offsetSuspect) // 无法核验漂移 → 置疑（证据缺失 ≠ 隐式健康）
        assertEquals(1000L, track.offsetAtUs(123_456_789L))
    }

    @Test
    fun `双端缺失时 offset 为 null 且不置疑`() {
        val track = OffsetTrack(point(null, null), point(null, null))
        assertNull(track.driftPpm)
        assertFalse(track.offsetSuspect) // 本就没有 offset 派生量可置疑
        assertNull(track.offsetAtUs(0L))
    }

    @Test
    fun `锚点间隔不足 1s 不估漂移率`() {
        val track = OffsetTrack(point(0L, 0L), point(500L, 500_000L)) // 0.5s 内漂 500us=1000ppm
        assertNull(track.driftPpm) // 分母太小放大噪声 → 不估
        assertFalse(track.offsetSuspect)
    }
}
