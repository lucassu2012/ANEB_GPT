package com.aneb.probe.scoring

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * P1-C08 标定夹具测试：用三组**真实路径**签名样本验证 BufferingDetector 方向性
 * （KPI 5.3.3：判无效阈值待签名样本标定；本测试即标定回路的可重跑载体）。
 *
 * 夹具来源（evidence/phase1/calibration/，采集器 server/tools/capture，2026-07-13）：
 * - clean_run{1,2}.jsonl：本机 aneb-server 直连（127.0.0.1:8443），600 token @40tps；
 * - nginx_run{1,2}.jsonl：docker nginx:alpine 反代 + gzip 输出缓冲 16KB
 *   （proxy_buffering on + gzip 攒-放，conf 同目录存档），600 token @40tps；
 * - proxied_run{1,2}.jsonl：经本机代理 127.0.0.1:33210 中转至 E-01 公网
 *   （120.79.148.0:8443），600 token @40tps——真实代理中转路径（R-03 实证同源）。
 *
 * JSONL 行格式：{"seq":N,"sched_us":N,"pre_flush_us":N,"arrival_us":N}；
 * 残差按 KPI 5.3.4 逐 seq 对齐：residual = 到达间隔 − 发出间隔（发出取服务端
 * pre_flush_us——实际写出前时刻，服务端调度误差由此剥离）。
 *
 * 方向性断言（标定合同）：
 * 1. clean score < nginx score（逐 run 对比：全部 clean 的最大值 < 全部 nginx 的最小值）；
 * 2. clean 归因 NONE；
 * 3. nginx 归因 MIDDLEBOX_SUSPECT（无 R1、批间隔连续分布 → 中间盒假设）；
 * 4. proxied 组如实打印，不做强断言（代理逐 event 转发时形态可接近 clean）。
 *
 * 夹具目录缺失时整类跳过（Assume）——CI 无 evidence 目录也不红。
 * 路径可用系统属性 -Daneb.calibration.dir=... 覆盖。
 */
class CalibrationFixtureTest {

    // ---------- 夹具装载 ----------

    private fun calibrationDir(): File? {
        System.getProperty("aneb.calibration.dir")?.let { p ->
            val f = File(p)
            if (f.isDirectory) return f
        }
        // 从模块工作目录（app/probe）向上找仓库根的 evidence/phase1/calibration
        var cur: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val cand = File(cur, "evidence/phase1/calibration")
            if (cand.isDirectory) return cand
            cur = cur?.parentFile ?: return null
        }
        return null
    }

    private data class RawEvent(val seq: Long, val preFlushUs: Long, val arrivalUs: Long)

    private val lineRegex = Regex("\"(\\w+)\"\\s*:\\s*(-?\\d+)")

    /** 读 JSONL 夹具 → 按 seq join → 残差序列（KPI 5.3.4/5.3.8：seq 排序，禁位置配对）。 */
    private fun loadResiduals(file: File): List<ResidualSample> {
        val events = file.readLines().filter { it.isNotBlank() }.map { line ->
            val kv = lineRegex.findAll(line).associate { it.groupValues[1] to it.groupValues[2].toLong() }
            RawEvent(
                seq = kv.getValue("seq"),
                preFlushUs = kv.getValue("pre_flush_us"),
                arrivalUs = kv.getValue("arrival_us"),
            )
        }.sortedBy { it.seq }
        val out = ArrayList<ResidualSample>(maxOf(0, events.size - 1))
        for (i in 1 until events.size) {
            val arrItv = events[i].arrivalUs - events[i - 1].arrivalUs
            val sndItv = events[i].preFlushUs - events[i - 1].preFlushUs
            out.add(
                ResidualSample(
                    seq = events[i].seq,
                    arrivalUs = events[i].arrivalUs,
                    arrivalIntervalUs = arrItv,
                    residualUs = arrItv - sndItv,
                )
            )
        }
        return out
    }

    private fun analyzeGroup(dir: File, prefix: String): Map<String, BufferingReport> {
        val files = dir.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".jsonl") }
            ?.sortedBy { it.name } ?: emptyList()
        return files.associate { f ->
            val report = BufferingDetector.analyze(loadResiduals(f))
            printReport(f.name, report)
            f.name to report
        }
    }

    private fun printReport(name: String, r: BufferingReport) {
        println(
            "[calibration] $name: score=%.4f attr=%s n=%d sawtooth=%.4f posSpike=%.4f negCluster=%.4f " .format(
                r.bufferingScore, r.attribution, r.sampleCount,
                r.sawtoothRatio, r.positiveSpikeRatio, r.negativeClusterRatio,
            ) +
                "negResid=%.4f r1=%.4f autocorrComp=%.4f nearZero=%.4f batches=%d interBatchMedianUs=%s ".format(
                    r.negativeResidualRatio, r.lag1Autocorrelation, r.autocorrelationComponent,
                    r.nearZeroArrivalRatio, r.batchCount, r.interBatchMedianUs,
                ) +
                "bestGrid=%s bestGridHit=%.3f airlinkPeriodicity=%s".format(
                    r.bestGridUs, r.bestGridHitRatio, r.airlinkPeriodicity,
                )
        )
    }

    // ---------- 标定方向性断言 ----------

    @Test
    fun calibration_directionality_cleanVsNginxVsProxied() {
        val dir = calibrationDir()
        assumeTrue("夹具目录 evidence/phase1/calibration 不存在，跳过标定测试", dir != null)

        val clean = analyzeGroup(dir!!, "clean_run")
        val nginx = analyzeGroup(dir, "nginx_run")
        val proxied = analyzeGroup(dir, "proxied_run") // 如实打印，不强断言

        assumeTrue("clean 夹具缺失，跳过", clean.isNotEmpty())
        assumeTrue("nginx 夹具缺失，跳过", nginx.isNotEmpty())

        // 1. 方向性：所有 clean run 的 score < 所有 nginx run 的 score
        val cleanMax = clean.values.maxOf { it.bufferingScore }
        val nginxMin = nginx.values.minOf { it.bufferingScore }
        assertTrue(
            "方向性失败：clean 最大 score=$cleanMax 应 < nginx 最小 score=$nginxMin",
            cleanMax < nginxMin,
        )

        // 2. clean 归因 NONE（活跃线以下，无批化证据）
        for ((name, r) in clean) {
            assertEquals("$name 应归因 NONE", BufferingAttribution.NONE, r.attribution)
        }

        // 3. nginx 归因 MIDDLEBOX_SUSPECT（批间隔连续分布、无 R1、批起点足量）
        for ((name, r) in nginx) {
            assertEquals("$name 应归因 MIDDLEBOX_SUSPECT", BufferingAttribution.MIDDLEBOX_SUSPECT, r.attribution)
        }

        // proxied：真实代理中转，逐 event 转发时允许接近 clean——仅记录归因分布
        for ((name, r) in proxied) {
            println("[calibration] proxied 归因（如实记录，不断言）：$name -> ${r.attribution}")
        }
    }

    /** 对照证据：纯 proxy_buffering on（无 gzip 累积）在快速本地客户端下不攒批。 */
    @Test
    fun calibration_nginxWithoutAccumulation_looksClean() {
        val dir = calibrationDir()
        assumeTrue("夹具目录不存在，跳过", dir != null)
        val nobuf = analyzeGroup(dir!!, "nginx_nobuf_run")
        assumeTrue("nginx_nobuf 夹具缺失，跳过", nobuf.isNotEmpty())
        for ((name, r) in nobuf) {
            assertTrue(
                "$name：纯 proxy_buffering（无输出侧累积）不应产生高批化分，实际 ${r.bufferingScore}",
                r.bufferingScore < BufferingDetector.SCORE_ACTIVE_THRESHOLD,
            )
        }
    }
}
