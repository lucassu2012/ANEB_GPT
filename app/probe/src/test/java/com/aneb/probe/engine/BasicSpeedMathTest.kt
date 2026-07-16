package com.aneb.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BasicSpeedMathTest {
    @Test
    fun `echo 汇总保留 null 失败语义并计算应用层请求失败率`() {
        val s = BasicSpeedMath.summarizeEcho(listOf(10.0, 30.0, null, 20.0))
        assertEquals(20.0, s.rttP50Ms!!, 1e-9)
        assertEquals(15.0, s.jitterMs!!, 1e-9) // 有效到达序列差 20,10 的中位数
        assertEquals(0.25, s.requestLossRate!!, 1e-9)
    }

    @Test
    fun `无字节不以 0 冒充吞吐`() {
        assertNull(BasicSpeedMath.mbps(0L, 1_000_000_000L))
        assertNull(BasicSpeedMath.mbps(1L, 0L))
        assertEquals(8.0, BasicSpeedMath.mbps(1_000_000L, 1_000_000_000L)!!, 1e-9)
    }

    @Test
    fun `字节滑窗不足 250ms 不出值并剔除过期累计点`() {
        val w = ByteRateWindow(windowNanos = 1_000_000_000L)
        assertNull(w.add(0L, 0L))
        assertNull(w.add(200_000_000L, 1_000_000L))
        assertEquals(32.0, w.add(500_000_000L, 2_000_000L)!!, 1e-9)
        // 1.6s 时 0/0.2/0.5s 均过期，仅保留最新窗口端点；首个新点还不足 250ms。
        assertNull(w.add(1_600_000_000L, 4_000_000L))
        assertEquals(40.0, w.add(2_000_000_000L, 6_000_000L)!!, 1e-9)
    }
}
