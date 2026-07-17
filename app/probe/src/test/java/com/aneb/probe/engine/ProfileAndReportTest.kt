package com.aneb.probe.engine

import com.aneb.probe.net.AnebClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** profile 解析 / ITL 直方图 / 慢启动估计（P1 范围 1/8 的纯函数部分）。 */
class ProfileAndReportTest {

    private val profileJson = { id: String, ver: String ->
        """{"profile_id":"$id","version":"$ver","kpi_set":"agent-qoe-kpi-v0.2",
            "phases":[{"type":"clock_sync","samples":20},
                      {"type":"token_stream","tokens":600,"rate_tps":40,"seed":1001},
                      {"type":"clock_sync","samples":20}]}"""
    }

    @Test
    fun `服务端 profiles 响应解析与版本串`() {
        val body = """{"server_version":"aneb-server/0.1.0","profiles":[
            ${profileJson("s1_chat", "0.2.0")},
            ${profileJson("s2_coding_agent", "0.2.0")},
            ${profileJson("s3_multimodal", "0.2.0")}]}"""
        val map = ProfileParser.parseServerResponse(body)
        assertEquals(3, map.size)
        assertEquals(600, map.getValue("s1_chat").phases[1].tokens)
        assertEquals(
            "s1_chat@0.2.0;s2_coding_agent@0.2.0;s3_multimodal@0.2.0",
            ProfileParser.versionString(map),
        )
    }

    @Test
    fun `缺任一必需场景即抛异常 不静默缺省`() {
        val body = """{"profiles":[${profileJson("s1_chat", "0.2.0")}]}"""
        assertThrows(IllegalArgumentException::class.java) {
            ProfileParser.parseServerResponse(body)
        }
    }

    @Test
    fun `基本测速 profile 解析模式 实时指标与并发阶段`() {
        val profile = ProfileParser.parseSingle(
            """{
              "profile_id":"basic_network","version":"0.1.0","mode_id":"network_basic",
              "kpi_set":"basic-network-kpi-v0.1",
              "presentation":{"live_metric_id":"phase_throughput_mbps","live_metric_unit":"Mbps",
                "live_window_ms":1000,"ui_refresh_ms":250,"metric_ids":["D1","U1"],
                "conclusion_policy_id":"network-basic-conclusions-v1"},
              "phases":[{"type":"download_throughput","duration_ms":10000,"bytes":268435456,
                "chunk_kb":256,"parallel":4}]
            }""",
        )
        assertEquals(ScenarioProfile.MODE_NETWORK_BASIC, profile.modeId)
        assertEquals("phase_throughput_mbps", profile.presentation.liveMetricId)
        assertEquals(250, profile.presentation.uiRefreshMs)
        assertEquals(ProfilePhase.TYPE_DOWNLOAD_THROUGHPUT, profile.phases.single().type)
        assertEquals(4, profile.phases.single().parallel)
    }

    @Test
    fun `ITL 直方图桶界含全部门限锚点且计数守恒`() {
        assertTrue(ItlHistogram.EDGES_MS.containsAll(listOf(100.0, 200.0, 400.0, 1000.0)))
        assertEquals(ItlHistogram.EDGES_MS, ItlHistogram.EDGES_MS.sorted())

        val samples = listOf(-5.0, 0.5, 150.0, 200.0, 430.0, 9999.0, 20000.0)
        val hist = ItlHistogram.of(samples)
        assertEquals(samples.size, hist.total)
        assertEquals(hist.edgesMs.size + 1, hist.counts.size)
        // 负残差（5.3.4 不 clamp）落首桶
        assertEquals(2, hist.counts[0]) // -5.0 与 0.5 都 < 1.0
        val idx200 = hist.edgesMs.indexOf(200.0)
        assertEquals(1, hist.counts[idx200]) // 150.0 ∈ [128,200)
        // 200.0 恰在桶界：落 [200,256) 桶——与服务端复算 ">200ms 即 stall" 边界语义一致
        assertEquals(1, hist.counts[idx200 + 1])
        // 超出最大对数桶界（8192ms）的样本落尾桶
        assertEquals(2, hist.counts.last()) // 9999.0 与 20000.0
    }

    @Test
    fun `慢启动估计：先爬坡后稳态可估 均匀到达不可估`() {
        val chunk = 65_536L
        // 爬坡：前 8 块间隔 50ms，后 24 块间隔 5ms（稳态快 10 倍）
        val arrivals = ArrayList<Long>()
        var t = 0L
        repeat(8) { t += 50_000; arrivals.add(t) }
        repeat(24) { t += 5_000; arrivals.add(t) }
        val est = UploadAnalysis.estimateSlowStart(arrivals, recvStartUs = 0, chunkBytes = chunk)
        requireNotNull(est)
        assertTrue("慢启动段应覆盖爬坡块", est.second in chunk * 4..chunk * 9)

        // 均匀到达：一开始即稳态 → null（无爬坡段可剥离）
        val uniform = (1..32).map { it * 5_000L }
        assertEquals(null, UploadAnalysis.estimateSlowStart(uniform, 0, chunk))

        // 块数不足：null（S1 2KB / S2 512KB 不出剔慢启动口径）
        assertEquals(null, UploadAnalysis.estimateSlowStart((1..8).map { it * 5_000L }, 0, chunk))
    }

    @Test
    fun `prelude srv_ts 解析`() {
        assertEquals(
            123456789L,
            ScenarioRunner.parsePreludeSrvTsUs("""prelude {"srv_ts_us":123456789,"anchor_wall_unix_ns":1}"""),
        )
        assertEquals(null, ScenarioRunner.parsePreludeSrvTsUs("prelude {}"))
    }

    @Test
    fun `download burst only exposes goodput after exact body drain`() {
        val complete = ScenarioRunner.DownloadOutcome(
            index = 0,
            profileBytes = 12_582_912,
            result = AnebClient.TransferResult(
                startNanos = 1_000_000_000,
                endNanos = 3_000_000_000,
                totalBytes = 12_582_912,
                httpCode = 200,
                error = null,
                timing = null,
            ),
        )
        assertTrue(complete.complete)
        assertEquals(2_000_000_000L, complete.durationNanos)
        assertEquals(50.331648, complete.goodputMbps!!, 1e-9)

        val truncated = ScenarioRunner.DownloadOutcome(
            index = 1,
            profileBytes = 12_582_912,
            result = AnebClient.TransferResult(
                startNanos = 1_000_000_000,
                endNanos = 2_000_000_000,
                totalBytes = 6_291_456,
                httpCode = 200,
                error = null,
                timing = null,
            ),
        )
        assertFalse(truncated.complete)
        assertNull(truncated.durationNanos)
        assertNull(truncated.goodputMbps)
    }
}
