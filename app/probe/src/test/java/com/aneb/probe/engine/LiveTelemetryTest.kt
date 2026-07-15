package com.aneb.probe.engine

import com.aneb.probe.radio.RadioSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LiveTelemetry.derive 纯函数派生（观测通道，非测量）：分层字段派生 / null 语义 /
 * 滑窗截断 / transport 无关。所有输入均为引擎既有记录的只读投影。
 */
class LiveTelemetryTest {

    private fun radio(
        networkType: String = "NR",
        rat: String? = "NR",
        nrState: String = "connected",
        overrideType: String? = null,
        rsrp: Int? = -85,
        sinr: Int? = 12,
    ) = RadioSample(
        tsNanos = 1_000,
        cellTsNanos = 1_000,
        stale = false,
        subId = 1,
        subSwitched = false,
        networkType = networkType,
        overrideType = overrideType,
        nrState = nrState,
        rat = rat,
        pci = 100,
        tac = 200,
        arfcn = 300,
        rsrp = rsrp,
        rsrq = -10,
        sinr = sinr,
        operatorName = "TestOp",
    )

    @Test
    fun `网络层：RTT 中位数+抖动+无线字段+制式标签`() {
        val t = LiveTelemetry.derive(
            TelemetrySnapshot(
                latestRadio = radio(rat = "NR"),
                rttSamplesMs = listOf(10.0, 30.0, 20.0),
                latestUpMbps = 42.5,
            )
        )
        assertEquals(20.0, t.rttMs!!, 1e-9)          // median(10,20,30)
        assertEquals(15.0, t.jitterMs!!, 1e-9)       // 到达序差 |30-10|=20,|20-30|=10 → median 15
        assertEquals(-85, t.rsrp)
        assertEquals(12, t.sinr)
        assertEquals("设备报告 NR", t.rat)            // 多源一致，只陈述设备报告 NR，不猜 SA
        assertEquals(42.5, t.upMbps!!, 1e-9)
    }

    @Test
    fun `网络层：5G 图标不当承载证据且一致 LTE 才确定`() {
        val iconOnly = LiveTelemetry.derive(
            TelemetrySnapshot(
                latestRadio = radio(
                    networkType = "LTE",
                    rat = "LTE",
                    nrState = "nsa_unknown",
                    overrideType = "nr_nsa",
                ),
            ),
        )
        assertEquals("LTE / 5G 图标不一致", iconOnly.rat)

        val lte = LiveTelemetry.derive(
            TelemetrySnapshot(
                latestRadio = radio(
                    networkType = "LTE",
                    rat = "LTE",
                    nrState = "none",
                    overrideType = "none",
                ),
            ),
        )
        assertEquals("设备报告 LTE", lte.rat)

        val mismatch = LiveTelemetry.derive(
            TelemetrySnapshot(latestRadio = radio(networkType = "LTE", rat = "NR", nrState = "nsa_unknown")),
        )
        assertEquals("制式证据不一致", mismatch.rat)
    }

    @Test
    fun `AI 业务层：ITL 中位数+stall 计数+token 速率+TTFT`() {
        // 校正 ITL 序列含两个 >500ms 的 stall
        val itl = listOf(15.0, 18.0, 600.0, 16.0, 900.0, 17.0)
        val t = LiveTelemetry.derive(
            TelemetrySnapshot(
                itlAllMs = itl,
                tokensReceived = 100,
                tokenElapsedSec = 2.0,
                ttftMs = 1200.0,
            )
        )
        assertEquals(2, t.stallCount)                 // 600,900 > 500
        assertEquals(100, t.tokensReceived)
        assertEquals(50.0, t.tokenRatePerSec!!, 1e-9) // 100 / 2s
        assertEquals(1200.0, t.ttftMs!!, 1e-9)
        assertEquals(itl.sorted().let { (it[2] + it[3]) / 2 }, t.itlMedianMs!!, 1e-9)
        assertEquals(itl, t.itlRecentMs)              // <40 个：全量即窗口
    }

    @Test
    fun `null 语义：空快照全部不出值，计数为 0（R-10 不以 0 顶替测量值）`() {
        val t = LiveTelemetry.derive(TelemetrySnapshot.NONE)
        assertNull(t.rttMs)
        assertNull(t.jitterMs)
        assertNull(t.rsrp)
        assertNull(t.sinr)
        assertNull(t.rat)
        assertNull(t.upMbps)
        assertNull(t.ttftMs)
        assertNull(t.itlMedianMs)
        assertNull(t.tokenRatePerSec)
        assertNull(t.aqsRunning)
        assertNull(t.phase)
        assertTrue(t.itlRecentMs.isEmpty())
        assertEquals(0, t.stallCount)
        assertEquals(0, t.tokensReceived)
        assertEquals(0.0, t.fraction, 1e-9)
    }

    @Test
    fun `null 语义：降级无线样本（无小区）不出 rsrp sinr rat`() {
        val degraded = radio(networkType = "permission_denied", rat = null, rsrp = null, sinr = null)
        val t = LiveTelemetry.derive(TelemetrySnapshot(latestRadio = degraded))
        assertNull(t.rsrp)
        assertNull(t.sinr)
        assertNull(t.rat)   // rat==null → 不猜制式
    }

    @Test
    fun `滑窗截断：ITL 全量 100 → 波形取最近 40，stall 计数覆盖全量`() {
        val itl = (1..100).map { it.toDouble() * 10.0 }          // 10..1000，均严格递增
        val t = LiveTelemetry.derive(TelemetrySnapshot(itlAllMs = itl))
        assertEquals(40, t.itlRecentMs.size)
        assertEquals(itl.takeLast(40), t.itlRecentMs)             // 最近 40 个
        // stall 计数覆盖全量（>500ms 的样本：510..1000 共 50 个）
        assertEquals(itl.count { it > LiveTelemetry.LIVE_STALL_MS }, t.stallCount)
    }

    @Test
    fun `transport 无关：有无无线样本不改变 AI 层与 RTT 派生（AUTO 与绑定同源）`() {
        val ai = TelemetrySnapshot(
            rttSamplesMs = listOf(25.0, 35.0),
            itlAllMs = listOf(16.0, 17.0, 18.0),
            tokensReceived = 30,
            tokenElapsedSec = 1.0,
        )
        val auto = LiveTelemetry.derive(ai)                              // 无无线样本（AUTO/无权限）
        val bound = LiveTelemetry.derive(ai.copy(latestRadio = radio())) // 有蜂窝无线样本
        assertEquals(auto.rttMs, bound.rttMs)
        assertEquals(auto.jitterMs, bound.jitterMs)
        assertEquals(auto.itlRecentMs, bound.itlRecentMs)
        assertEquals(auto.itlMedianMs, bound.itlMedianMs)
        assertEquals(auto.tokenRatePerSec, bound.tokenRatePerSec)
        assertEquals(auto.tokensReceived, bound.tokensReceived)
        // 仅无线派生字段随样本出现而变化
        assertNull(auto.rsrp)
        assertEquals(-85, bound.rsrp)
    }

    @Test
    fun `fraction 越界钳制到 0 到 1`() {
        assertEquals(1.0, LiveTelemetry.derive(TelemetrySnapshot(fraction = 1.5)).fraction, 1e-9)
        assertEquals(0.0, LiveTelemetry.derive(TelemetrySnapshot(fraction = -0.2)).fraction, 1e-9)
    }
}
