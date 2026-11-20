package com.aneb.probe.engine

import com.aneb.probe.ui.BasicSpeedConclusions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [RttDominanceGuard.evaluate] 判据锚定与主动路径集成测试。
 */
class RttDominanceGuardTest {

    @Test
    fun `s1_chat 历史倍数 1_77x 判不安全`() {
        val v = RttDominanceGuard.evaluate(windowActualMs = 354.0, rttRefMs = 200.0, bytesTransferred = 200_000L)
        assertFalse("s1 历史区间必须判不安全", v.ok)
        assertEquals(1.77, v.ratio!!, 1e-9)
    }

    @Test
    fun `s2_coding_agent 历史倍数 6_5x 判不安全`() {
        val v = RttDominanceGuard.evaluate(windowActualMs = 650.0, rttRefMs = 100.0, bytesTransferred = 200_000L)
        assertFalse(v.ok)
        assertEquals(6.5, v.ratio!!, 1e-9)
    }

    @Test
    fun `s3_multimodal 历史最高值 9_8x 仍判不安全`() {
        val v = RttDominanceGuard.evaluate(windowActualMs = 980.0, rttRefMs = 100.0, bytesTransferred = 200_000L)
        assertFalse("即使是历史最高值 9.8x 也必须判不安全", v.ok)
        assertEquals(9.8, v.ratio!!, 1e-9)
    }

    @Test
    fun `倍数 15x 且窗口字节数均达标时判安全`() {
        val v = RttDominanceGuard.evaluate(windowActualMs = 1500.0, rttRefMs = 100.0, bytesTransferred = 200_000L)
        assertTrue(v.ok)
        assertEquals(15.0, v.ratio!!, 1e-9)
    }

    @Test
    fun `ratio 恰为 15 且其余达标时判安全`() {
        val v = RttDominanceGuard.evaluate(windowActualMs = 1500.0, rttRefMs = 100.0, bytesTransferred = 200_000L)
        assertEquals(15.0, v.ratio!!, 1e-9)
        assertTrue(v.ok)
    }

    @Test
    fun `ratio 14_99 判不安全`() {
        val v = RttDominanceGuard.evaluate(windowActualMs = 1499.0, rttRefMs = 100.0, bytesTransferred = 200_000L)
        assertFalse(v.ok)
    }

    @Test
    fun `ratio 极高但窗口时长低于 ABS_FLOOR_MS 时仍判不安全`() {
        val v = RttDominanceGuard.evaluate(windowActualMs = 250.0, rttRefMs = 2.5, bytesTransferred = 200_000L)
        assertEquals(100.0, v.ratio!!, 1e-9)
        assertFalse("高 ratio 不能掩盖窗口时长过短", v.ok)
    }

    @Test
    fun `ratio 与窗口时长均达标但字节数低于下限时仍判不安全`() {
        val v = RttDominanceGuard.evaluate(windowActualMs = 1500.0, rttRefMs = 100.0, bytesTransferred = 50_000L)
        assertEquals(15.0, v.ratio!!, 1e-9)
        assertFalse("高 ratio 不能掩盖字节数不足", v.ok)
    }

    @Test
    fun `rttRefMs 为 null 时判不安全且 ratio 为 null（不猜）`() {
        val v = RttDominanceGuard.evaluate(windowActualMs = 1500.0, rttRefMs = null, bytesTransferred = 200_000L)
        assertFalse(v.ok)
        assertNull(v.ratio)
    }

    @Test
    fun `rttRefMs 为 0 时判不安全（防除零）`() {
        val v = RttDominanceGuard.evaluate(windowActualMs = 1500.0, rttRefMs = 0.0, bytesTransferred = 200_000L)
        assertFalse(v.ok)
        assertNull(v.ratio)
    }

    private fun repoFile(relFromRepoRoot: String): File {
        var cur: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (cur != null) {
            val cand = File(cur, relFromRepoRoot)
            if (cand.isFile) return cand
            cur = cur.parentFile
        }
        error("找不到 $relFromRepoRoot（从 user.dir 向上未命中）")
    }

    private fun basicNetworkDurationMs(): Int {
        val text = repoFile("profiles/basic_network.json").readText(Charsets.UTF_8)
        val found = Regex("\"duration_ms\"\\s*:\\s*(\\d+)").findAll(text)
            .map { it.groupValues[1].toInt() }.toList()
        assertTrue("basic_network.json 应含下载与上传两个 duration_ms", found.size >= 2)
        assertEquals("下载与上传窗口 duration_ms 应一致", 1, found.distinct().size)
        return found.first()
    }

    @Test
    fun `绊线 basic_network duration_ms 与阈值的商即临界RTT`() {
        val durationMs = basicNetworkDurationMs()
        val threshold = RttDominanceGuard.RTT_DOMINANCE_MIN

        assertEquals(10000, durationMs)
        assertEquals(15.0, threshold, 1e-9)
        assertEquals(10000.0 / 15.0, durationMs / threshold, 1e-9)
    }

    @Test
    fun `绊线 恰在 basic_network 临界RTT内侧判安全 略超即判不安全`() {
        val durationMs = basicNetworkDurationMs().toDouble()
        val criticalRttMs = durationMs / RttDominanceGuard.RTT_DOMINANCE_MIN

        val justInside = criticalRttMs * (1 - 1e-9)
        val atCritical = RttDominanceGuard.evaluate(
            windowActualMs = durationMs, rttRefMs = justInside, bytesTransferred = 200_000L,
        )
        assertTrue("RTT 在临界内侧应判安全", atCritical.ok)

        val justOver = RttDominanceGuard.evaluate(
            windowActualMs = durationMs, rttRefMs = criticalRttMs * 1.01, bytesTransferred = 200_000L,
        )
        assertFalse("RTT 略超临界值即应判不安全", justOver.ok)
    }

    @Test
    fun `BasicSpeedConclusions 在窗口受 RTT 主导时追加 CAUTION 提示而正常窗不追加`() {
        val healthy = BasicSpeedResult(
            runId = "r1",
            startedAtEpochMs = 1L,
            serverBase = "https://example.test",
            profileVersion = "0.1.0",
            status = "completed",
            downloadMbps = 120.0,
            uploadMbps = 40.0,
            pingMs = 20.0,
            jitterMs = 3.0,
            requestLossRate = 0.0,
            postLoadPingMs = 25.0,
            downloadBytes = 500_000L,
            uploadBytes = 500_000L,
            transferErrors = emptyList(),
            downloadWindowMs = 10_000.0,
            uploadWindowMs = 10_000.0,
        )
        assertFalse(BasicSpeedConclusions.build(healthy).any { it.title == "吞吐窗受 RTT 主导提示" })

        val rttDominated = healthy.copy(pingMs = 800.0)
        val items = BasicSpeedConclusions.build(rttDominated)
        val guard = items.firstOrNull { it.title == "吞吐窗受 RTT 主导提示" }
        assertTrue(guard != null && guard.tone == BasicSpeedConclusions.Tone.CAUTION)
    }

    @Test
    fun `LoadedResponsiveness summarize 为下载上传阶段计算 rttDominanceOk 与 ratio`() {
        fun echo(stage: String, seq: Int, t0: Long, rttUs: Long) = ResponsivenessEcho(
            stage = stage,
            sequence = seq,
            t0Us = t0,
            t1Us = 4_000_000L,
            t2Us = 4_000_000L,
            t3Us = t0 + rttUs,
            httpCode = 200,
            error = null,
            bytesBefore = if (stage == "idle") 0L else 100L,
            bytesAfter = if (stage == "idle") 0L else 200L,
        )

        val report = LoadedResponsivenessReport(
            runId = "r-dom",
            serverBase = "https://example.test",
            transport = "wifi",
            status = "completed",
            stages = listOf(
                ResponsivenessStage(
                    stage = "idle",
                    startUs = 0L,
                    endUs = 1_000_000L,
                    transferBytes = 0L,
                    samples = listOf(echo("idle", 0, 100_000L, 100_000L)), // 100 ms idle P50
                ),
                ResponsivenessStage(
                    stage = "download",
                    startUs = 1_000_000L,
                    endUs = 3_000_000L, // 2000 ms -> ratio 20.0 >= 15.0
                    transferBytes = 200_000L,
                    samples = listOf(echo("download", 0, 1_100_000L, 120_000L)),
                    byteObservations = listOf(
                        ResponsivenessByteObservation(1_050_000L, 100L),
                        ResponsivenessByteObservation(1_250_000L, 200L),
                    ),
                ),
                ResponsivenessStage(
                    stage = "upload",
                    startUs = 3_000_000L,
                    endUs = 3_500_000L, // 500 ms -> ratio 5.0 < 15.0
                    transferBytes = 200_000L,
                    samples = listOf(echo("upload", 0, 3_100_000L, 120_000L)),
                    byteObservations = listOf(
                        ResponsivenessByteObservation(3_050_000L, 100L),
                        ResponsivenessByteObservation(3_250_000L, 200L),
                    ),
                ),
            ),
        )

        val summary = LoadedResponsiveness.summarize(report)
        val dl = summary.stages.single { it.stage == "download" }
        val ul = summary.stages.single { it.stage == "upload" }
        assertEquals(true, dl.rttDominanceOk)
        assertEquals(20.0, dl.rttDominanceRatio!!, 1e-9)
        assertEquals(false, ul.rttDominanceOk)
        assertEquals(5.0, ul.rttDominanceRatio!!, 1e-9)
    }

    @Test
    fun `evaluate rejects non-finite non-positive and negative inputs cleanly`() {
        val cases = listOf(
            RttDominanceGuard.evaluate(Double.NaN, 100.0, 200_000L),
            RttDominanceGuard.evaluate(Double.POSITIVE_INFINITY, 100.0, 200_000L),
            RttDominanceGuard.evaluate(Double.NEGATIVE_INFINITY, 100.0, 200_000L),
            RttDominanceGuard.evaluate(0.0, 100.0, 200_000L),
            RttDominanceGuard.evaluate(-10.0, 100.0, 200_000L),
            RttDominanceGuard.evaluate(1500.0, Double.NaN, 200_000L),
            RttDominanceGuard.evaluate(1500.0, Double.POSITIVE_INFINITY, 200_000L),
            RttDominanceGuard.evaluate(1500.0, Double.NEGATIVE_INFINITY, 200_000L),
            RttDominanceGuard.evaluate(1500.0, -5.0, 200_000L),
            RttDominanceGuard.evaluate(1500.0, 100.0, -1L),
            RttDominanceGuard.evaluate(Double.MAX_VALUE, 1e-300, 200_000L),
        )
        for (v in cases) {
            assertFalse("invalid input must not pass dominance guard: $v", v.ok)
            assertNull("invalid input must yield null ratio: $v", v.ratio)
        }
    }

    @Test
    fun `BasicSpeedConclusions suppresses RTT dominance caution when pingMs is missing or non-finite`() {
        val base = BasicSpeedResult(
            runId = "r-nan",
            startedAtEpochMs = 1L,
            serverBase = "https://example.test",
            profileVersion = "0.1.0",
            status = "completed",
            downloadMbps = 120.0,
            uploadMbps = 40.0,
            pingMs = null,
            jitterMs = 3.0,
            requestLossRate = 0.0,
            postLoadPingMs = null,
            downloadBytes = 10_000_000L,
            uploadBytes = 10_000_000L,
            transferErrors = emptyList(),
            downloadWindowMs = 10_000.0,
            uploadWindowMs = 10_000.0,
        )
        for (badPing in listOf(null, Double.NaN, Double.POSITIVE_INFINITY)) {
            val items = BasicSpeedConclusions.build(base.copy(pingMs = badPing))
            assertFalse(
                "pingMs=$badPing must not emit RTT dominance caution",
                items.any { it.title == "吞吐窗受 RTT 主导提示" },
            )
        }
    }

    @Test
    fun `BasicSpeedConclusions reports unmeasured direction cleanly when only one direction is present`() {
        val partial = BasicSpeedResult(
            runId = "r-partial",
            startedAtEpochMs = 1L,
            serverBase = "https://example.test",
            profileVersion = "0.1.0",
            status = "partial",
            downloadMbps = 5.0,
            uploadMbps = null,
            pingMs = 20.0,
            jitterMs = 3.0,
            requestLossRate = 0.0,
            postLoadPingMs = null,
            downloadBytes = 50_000L,
            uploadBytes = 0L,
            transferErrors = emptyList(),
            downloadWindowMs = 10_000.0,
            uploadWindowMs = null,
        )
        val guard = BasicSpeedConclusions.build(partial).firstOrNull { it.title == "吞吐窗受 RTT 主导提示" }
        assertTrue(guard != null && guard.body.contains("上行未测"))
    }

    @Test
    fun `LoadedResponsiveness summarize leaves rttDominanceOk null when idle P50 is missing`() {
        val echo = ResponsivenessEcho(
            stage = "download",
            sequence = 0,
            t0Us = 1_100_000L,
            t1Us = 4_000_000L,
            t2Us = 4_000_000L,
            t3Us = 1_200_000L,
            httpCode = 200,
            error = null,
            bytesBefore = 100L,
            bytesAfter = 200L,
        )
        val report = LoadedResponsivenessReport(
            runId = "r-no-idle",
            serverBase = "https://example.test",
            transport = "wifi",
            status = "completed",
            stages = listOf(
                ResponsivenessStage(stage = "idle", startUs = 0L, endUs = 1_000_000L, transferBytes = 0L),
                ResponsivenessStage(
                    stage = "download",
                    startUs = 1_000_000L,
                    endUs = 2_000_000L,
                    transferBytes = 200_000L,
                    samples = listOf(echo),
                    byteObservations = listOf(
                        ResponsivenessByteObservation(1_050_000L, 100L),
                        ResponsivenessByteObservation(1_250_000L, 200L),
                    ),
                ),
            ),
        )
        val dl = LoadedResponsiveness.summarize(report).stages.single { it.stage == "download" }
        assertNull(dl.rttDominanceOk)
        assertNull(dl.rttDominanceRatio)
    }
}
