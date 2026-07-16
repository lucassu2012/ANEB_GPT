package com.aneb.probe.scoring

import kotlin.random.Random

/**
 * 合成残差流签名生成器（P1-C08 单测/阶段一标定复用）。
 *
 * 统一物理模型：token i 按发出时刻表 send[i]（µs）发出，经链路后于 arrival[i] 到达；
 * ResidualSample 从第 2 个 token 起产生（第 1 个无间隔）：
 * arrivalInterval = arrival[i] − arrival[i−1]，residual = arrivalInterval − (send[i] − send[i−1])。
 * 到达时刻强制单调不减（+50µs 兜底），残差可为负、不 clamp。
 */
object SyntheticResidualStreams {

    /** 批内成员序列化间隔（µs）。 */
    const val SERIALIZATION_US: Long = 100

    /** 由发出/到达时刻表构造样本序列（seq 从 2 起，与真实管线一致）。 */
    fun toSamples(sendUs: LongArray, arrivalRawUs: LongArray): List<ResidualSample> {
        require(sendUs.size == arrivalRawUs.size)
        val n = sendUs.size
        val arrival = LongArray(n)
        for (i in 0 until n) {
            arrival[i] = if (i == 0) arrivalRawUs[0] else maxOf(arrivalRawUs[i], arrival[i - 1] + 50)
        }
        val out = ArrayList<ResidualSample>(maxOf(0, n - 1))
        for (i in 1 until n) {
            val arrItv = arrival[i] - arrival[i - 1]
            val sndItv = sendUs[i] - sendUs[i - 1]
            out.add(ResidualSample(seq = (i + 1).toLong(), arrivalUs = arrival[i], arrivalIntervalUs = arrItv, residualUs = arrItv - sndItv))
        }
        return out
    }

    /** 干净流：稳定 pacing + 每 token 独立时延抖动（残差为 MA(1)，r1≈−0.5）。 */
    fun clean(
        n: Int = 400,
        sendStepUs: Long = 10_000,
        jitterUs: Long = 1_500,
        seed: Long = 42,
    ): List<ResidualSample> {
        val rnd = Random(seed)
        val send = LongArray(n) { it * sendStepUs }
        val arrival = LongArray(n) { send[it] + 30_000 + rnd.nextLong(-jitterUs, jitterUs + 1) }
        return toSamples(send, arrival)
    }

    /**
     * 含 profile 设计停顿的干净流（S2 式簇间 300–800ms 停顿）：
     * 停顿同时出现在发出与到达间隔中，残差域应将其剥离（R-05 核心场景）。
     */
    fun cleanWithDesignPauses(
        n: Int = 400,
        sendStepUs: Long = 10_000,
        pauseEvery: Int = 20,
        seed: Long = 43,
    ): List<ResidualSample> {
        val rnd = Random(seed)
        val send = LongArray(n)
        var t = 0L
        for (i in 0 until n) {
            send[i] = t
            t += if ((i + 1) % pauseEvery == 0) 300_000 + rnd.nextLong(500_001) else sendStepUs
        }
        val arrival = LongArray(n) { send[it] + 30_000 + rnd.nextLong(-1_500L, 1_501L) }
        return toSamples(send, arrival)
    }

    /**
     * nginx 式攒批：稳定发出，代理按连续周期（含连续抖动，不对齐任何离散网格）
     * 整批放行 → 周期性大批 + 长静默。
     */
    fun nginxBatching(
        n: Int = 400,
        sendStepUs: Long = 10_000,
        flushPeriodUs: Long = 150_000,
        flushJitterUs: Long = 4_000,
        seed: Long = 44,
    ): List<ResidualSample> {
        val rnd = Random(seed)
        val send = LongArray(n) { it * sendStepUs }
        val arrival = LongArray(n)
        var flushAt = flushPeriodUs + rnd.nextLong(-flushJitterUs, flushJitterUs + 1)
        var idxInFlush = 0
        for (i in 0 until n) {
            while (send[i] > flushAt) {
                flushAt += flushPeriodUs + rnd.nextLong(-flushJitterUs, flushJitterUs + 1)
                idxInFlush = 0
            }
            arrival[i] = flushAt + idxInFlush * SERIALIZATION_US
            idxInFlush++
        }
        return toSamples(send, arrival)
    }

    /**
     * DRX 式微批：UE 按 cycleUs 离散网格醒来收数，偶发跳过一个周期
     * （批起点间隔为网格的 1–2 倍，命中离散谱）。
     */
    fun drxMicroBatching(
        n: Int = 300,
        sendStepUs: Long = 10_000,
        cycleUs: Long = 20_000,
        skipEvery: Int = 4,
        seed: Long = 45,
    ): List<ResidualSample> {
        val send = LongArray(n) { it * sendStepUs }
        val arrival = LongArray(n)
        var lastWake = -1L
        var idxInWake = 0
        for (i in 0 until n) {
            var wakeIdx = (send[i] + cycleUs - 1) / cycleUs // 下一个网格时刻
            if (skipEvery > 0 && wakeIdx % skipEvery == 0L) wakeIdx++ // 该周期休眠，顺延
            val wake = wakeIdx * cycleUs
            if (wake != lastWake) {
                lastWake = wake
                idxInWake = 0
            }
            arrival[i] = wake + idxInWake * SERIALIZATION_US
            idxInWake++
        }
        return toSamples(send, arrival)
    }

    /**
     * 设备侧冻结：基线干净流叠加周期性读线程冻结窗口，窗口内自然到达的 token
     * 被扣押至窗口结束一次性交付；每个窗口结束时刻记一条 app_jank 事件。
     * 冻结排布带非网格抖动，防批间隔谱误命中离散网格。
     *
     * @return 样本序列 + app_jank 事件时刻（µs，与 arrivalUs 同基准）。
     */
    fun deviceFreeze(
        n: Int = 300,
        sendStepUs: Long = 10_000,
        freezeEveryUs: Long = 450_000,
        freezeDurationUs: Long = 200_000,
        seed: Long = 46,
    ): Pair<List<ResidualSample>, List<Long>> {
        val rnd = Random(seed)
        val send = LongArray(n) { it * sendStepUs }
        val totalUs = n * sendStepUs + 30_000
        val windows = ArrayList<Pair<Long, Long>>()
        var ws = 400_000L + rnd.nextLong(-7_000L, 7_001L)
        while (ws + freezeDurationUs < totalUs) {
            windows.add(ws to ws + freezeDurationUs + rnd.nextLong(-7_000L, 7_001L))
            ws += freezeEveryUs + rnd.nextLong(-7_000L, 7_001L)
        }
        val jank = windows.map { it.second }
        val arrival = LongArray(n)
        val heldIdx = HashMap<Long, Int>() // 窗口结束时刻 → 已释放个数
        for (i in 0 until n) {
            val natural = send[i] + 30_000 + rnd.nextLong(-1_000L, 1_001L)
            val w = windows.firstOrNull { natural >= it.first && natural < it.second }
            arrival[i] = if (w == null) natural else {
                val k = heldIdx.getOrDefault(w.second, 0)
                heldIdx[w.second] = k + 1
                w.second + k * SERIALIZATION_US
            }
        }
        return toSamples(send, arrival) to jank
    }

    /** 稀疏零星批化：干净流中每 coalesceEvery 个 token 有一对被合并交付（低分不误报）。 */
    fun sparseBatching(
        n: Int = 400,
        sendStepUs: Long = 10_000,
        coalesceEvery: Int = 25,
        seed: Long = 47,
    ): List<ResidualSample> {
        val rnd = Random(seed)
        val send = LongArray(n) { it * sendStepUs }
        val arrival = LongArray(n)
        for (i in 0 until n) {
            arrival[i] = send[i] + 30_000 + rnd.nextLong(-1_200L, 1_201L)
        }
        var i = coalesceEvery
        while (i + 1 < n) {
            arrival[i] = arrival[i + 1] - SERIALIZATION_US // token i 被扣押至与 i+1 同批
            i += coalesceEvery
        }
        return toSamples(send, arrival)
    }

    /** 单一大批（高分但批起点不足，区分特征不够 → INDETERMINATE 场景）。 */
    fun singleBigBatch(
        n: Int = 60,
        sendStepUs: Long = 10_000,
        holdFromIdx: Int = 20,
        holdCount: Int = 30,
        seed: Long = 48,
    ): List<ResidualSample> {
        val rnd = Random(seed)
        val send = LongArray(n) { it * sendStepUs }
        val arrival = LongArray(n)
        val releaseAt = send[holdFromIdx + holdCount - 1] + 30_000 + 40_000
        for (i in 0 until n) {
            arrival[i] = if (i in holdFromIdx until holdFromIdx + holdCount) {
                releaseAt + (i - holdFromIdx) * SERIALIZATION_US
            } else {
                send[i] + 30_000 + rnd.nextLong(-1_000L, 1_001L)
            }
        }
        return toSamples(send, arrival)
    }
}
