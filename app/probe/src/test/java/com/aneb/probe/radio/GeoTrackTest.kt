package com.aneb.probe.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GPS 路测轨迹纯函数（阶段3）：Haversine / 场景窗口摘要 / 本地轨迹 CSV。 */
class GeoTrackTest {

    // ------------------------------------------------------------------
    // haversineMeters
    // ------------------------------------------------------------------

    @Test
    fun `同点距离为 0`() {
        assertEquals(0.0, GeoTrack.haversineMeters(31.23, 121.47, 31.23, 121.47), 1e-9)
    }

    @Test
    fun `赤道上经度差 1 度约 111 point 2 km`() {
        val d = GeoTrack.haversineMeters(0.0, 0.0, 0.0, 1.0)
        // 2πR/360 ≈ 111.19 km（球面模型）
        assertEquals(111_195.0, d, 200.0)
    }

    @Test
    fun `纬度差 1 度与经度差 1 度在赤道等距 高纬经度收缩`() {
        val dLat = GeoTrack.haversineMeters(50.0, 10.0, 51.0, 10.0)
        val dLon = GeoTrack.haversineMeters(50.0, 10.0, 50.0, 11.0)
        assertEquals(111_195.0, dLat, 200.0)
        // cos(50°) ≈ 0.643
        assertEquals(111_195.0 * Math.cos(Math.toRadians(50.0)), dLon, 500.0)
    }

    @Test
    fun `对称性`() {
        val a = GeoTrack.haversineMeters(31.2304, 121.4737, 39.9042, 116.4074)
        val b = GeoTrack.haversineMeters(39.9042, 116.4074, 31.2304, 121.4737)
        assertEquals(a, b, 1e-6)
        // 上海—北京直线约 1068 km（球面模型容差 1%）
        assertEquals(1_067_000.0, a, 12_000.0)
    }

    // ------------------------------------------------------------------
    // summarize（场景窗口）
    // ------------------------------------------------------------------

    private fun pt(ts: Long, lat: Double?, lon: Double?) = GeoTrack.Point(ts, lat, lon, null)

    @Test
    fun `窗口内只统计有坐标的点 起终点距离按时间序`() {
        val points = listOf(
            pt(5, 0.0, 0.0),
            pt(1, null, null),   // 无坐标：不计
            pt(10, 0.0, 0.001),  // 窗口内终点
            pt(20, 9.0, 9.0),    // 窗口外
        )
        val s = GeoTrack.summarize(points, startNanos = 0, endNanos = 15)
        assertEquals(2, s.points)
        // 0.001° 经度（赤道）≈ 111.2m
        assertEquals(111.2, s.startEndMeters!!, 0.5)
    }

    @Test
    fun `不足 2 点时距离 null 不出 0 顶替`() {
        assertEquals(GeoTrack.Summary(0, null), GeoTrack.summarize(emptyList(), 0, null))
        val one = GeoTrack.summarize(listOf(pt(1, 1.0, 1.0)), 0, null)
        assertEquals(1, one.points)
        assertNull(one.startEndMeters)
        // 全部点均无坐标 → 0 点
        assertEquals(0, GeoTrack.summarize(listOf(pt(1, null, null)), 0, null).points)
    }

    @Test
    fun `endNanos 为 null 时开区间到末尾 乱序输入按时间排序`() {
        val points = listOf(pt(30, 0.0, 0.002), pt(10, 0.0, 0.0), pt(20, 0.0, 0.001))
        val s = GeoTrack.summarize(points, startNanos = 10, endNanos = null)
        assertEquals(3, s.points)
        // 起点 ts=10 (0,0) → 终点 ts=30 (0,0.002) ≈ 222.4m
        assertEquals(222.4, s.startEndMeters!!, 1.0)
    }

    // ------------------------------------------------------------------
    // buildTrackCsv（本地导出；隐私边界内）
    // ------------------------------------------------------------------

    @Test
    fun `轨迹 CSV 表头固定 只含有坐标的行 按时间序`() {
        val csv = GeoTrack.buildTrackCsv(
            listOf(
                GeoTrack.Point(20, 1.5, 2.5, 3.0),
                GeoTrack.Point(10, 1.0, 2.0, null),
                GeoTrack.Point(15, null, null, null), // 无坐标不进轨迹
            ),
        )
        val lines = csv.trim().split('\n')
        assertEquals("ts_nanos,lat,lon,accuracy_m", lines[0])
        assertEquals(3, lines.size)
        assertEquals("10,1.0,2.0,", lines[1])
        assertEquals("20,1.5,2.5,3.0", lines[2])
    }

    @Test
    fun `空轨迹只有表头`() {
        assertEquals("ts_nanos,lat,lon,accuracy_m\n", GeoTrack.buildTrackCsv(emptyList()))
        assertTrue(GeoTrack.buildTrackCsv(listOf(GeoTrack.Point(1, null, null, null))).trim().split('\n').size == 1)
    }
}
