package com.aneb.probe.scoring

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 残差域批化检测器（P1-C08，KPI 文档 5.3.3 / 红队 R-05）。
 *
 * 检测运行在**服务端节奏剥离后的残差域**（残差 = 到达间隔 − 发出间隔，逐 seq 对齐）：
 * profile 内生的突发节奏（S2 簇间 300–800ms 停顿、think_pause）在残差域天然被剥离，
 * 不会误触发；「正残差尖峰 + 负残差簇」的锯齿才是缓冲「攒-放」的因果证据。
 *
 * ## R-05 红线（本类的硬约束）
 * 1. **绝不二值判无效**：只产出连续 [BufferingReport.bufferingScore] 与归因假设
 *    [BufferingAttribution]，判无效阈值留待阶段一签名样本标定，由上游 Gate 决策。
 * 2. **弱信号 + 批化 → [BufferingAttribution.AIRLINK_SUSPECT]** 而非 MIDDLEBOX：
 *    弱覆盖下空口 TTI/C-DRX 天然微批与中间盒缓冲形态高度相似，误判会系统性丢弃
 *    最有取证价值的弱网样本（幸存者偏差）。
 * 3. **不丢样本**：输入残差含负值不 clamp、全量参与统计；[BufferingReport.sampleCount]
 *    恒等于输入样本数；所有特征原始数值全部透出，供阶段一重新加权。
 *
 * ## 区分手段（KPI 5.3.3）
 * - 批间隔周期性谱：批起点间隔命中 8/10/20/40ms 离散网格 → 疑似空口 TTI/DRX 聚合；
 *   连续分布 / RTT 相关 → 疑似中间盒缓冲。
 * - R1 无线快照联动：信号差 + 批化 → 空口聚合；信号优 + 批化 → 中间盒。
 * - app_jank 时间轴比对：批起点与客户端卡顿事件重叠 → 设备侧冻结
 *   （device_side_batching，R-12），与链路缓冲（link_batching）区分。
 *
 * 纯函数、无 Android 依赖，可在 JVM 单测与阶段一标定脚本中直接复用。
 */
object BufferingDetector {

    // =====================================================================
    // 阈值常量（集中一处，全部 experimental：阶段一用签名样本——无中间盒 WiFi 直连 /
    // 已知代理路径 / nginx 默认缓冲反代——标定后重校，标定数据存 evidence/）
    // =====================================================================

    /** 正残差尖峰判定线（µs）：批首 token 吸收整段静默，残差显著为正。experimental。 */
    const val POS_SPIKE_US: Long = 8_000

    /** 负残差簇成员判定线（µs）：批内成员到达间隔≈0 而发出间隔为正，残差显著为负。experimental。 */
    const val NEG_CLUSTER_US: Long = -4_000

    /** 近零到达间隔判定线（µs）：批内背靠背投递的表现（R-04 合帧同形态）。experimental。 */
    const val NEAR_ZERO_ARRIVAL_US: Long = 1_000

    /** 批起点判定：到达间隔 ≥ 此值且紧跟 ≥1 个近零间隔（gap-then-burst）。experimental。 */
    const val BATCH_START_GAP_US: Long = 5_000

    /** 周期性谱的离散网格（µs）：LTE/NR TTI 与常见 C-DRX 周期（8/10/20/40ms）。 */
    val PERIODICITY_GRIDS_US: List<Long> = listOf(8_000, 10_000, 20_000, 40_000)

    /** 网格命中容差（µs）：批起点间隔到网格整数倍的最大距离。experimental。 */
    const val GRID_TOLERANCE_US: Long = 1_000

    /** 网格命中率 ≥ 此值判空口周期性（10ms 网格随机基线约 0.2，需显著超出）。experimental。 */
    const val GRID_HIT_THRESHOLD: Double = 0.7

    /** 周期性/连续性判定所需最少批起点数（少于此数谱特征不可靠）。experimental。 */
    const val MIN_BATCH_COUNT: Int = 4

    /** 批起点与 app_jank 事件的重叠窗口（µs，双侧）。experimental。 */
    const val JANK_OVERLAP_WINDOW_US: Long = 50_000

    /** 批起点 jank 重叠率 ≥ 此值判设备侧冻结（R-12 device_side_batching）。experimental。 */
    const val JANK_OVERLAP_THRESHOLD: Double = 0.5

    /** buffering_score ≥ 此值才进入归因分支，否则 NONE（不判无效，仅是归因假设为空）。experimental。 */
    const val SCORE_ACTIVE_THRESHOLD: Double = 0.25

    /**
     * retrans 共变量显著线（P3-C05）：retransRate = 服务端 TCP_INFO 连接累计重传段数 /
     * 场景 token 事件数 > 此值时，原判 MIDDLEBOX_SUSPECT 的批化改归因
     * [BufferingAttribution.RETRANS_SUSPECT]——netem 100ms/1% 取证（evidence/phase3/
     * netem_experiments_20260713.md 断言 3）发现丢包重传造成的到达批化与 nginx
     * proxy_buffering 批化签名同形。
     *
     * 标定依据（evidence/phase3/c05_fix_retrans_covariate_20260713.log 预检）：
     * 账本先验取 0.02，但实测 netem loss 1% 下事件级重传率仅 ~0.008（600 token
     * 均匀 pacing，1 event ≈ 1 段；S2 burst 簇内多 token 合段后更低）——0.02 会漏放行
     * 全部真实丢包批化样本。区分对象是"物理零"：无丢包的干净路径 / 纯中间盒缓冲路径
     * retrans 恒 ≈ 0（量表零点），故显著线只需压过零星散粒噪声（quick 场景 ~600 事件
     * 下 0.002 ≈ 至少 2 个重传段）。0.002 居"干净零点"与"1% loss 实测 0.004–0.008"
     * 之间。experimental，真实弱网样本回流后与其余阈值一并重标定。
     */
    const val RETRANS_RATE_SIGNIFICANT: Double = 0.002

    /**
     * 干净流残差滞后1自相关的理论基线。到达时刻 = 发出时刻 + 独立时延噪声 ε 时，
     * 残差 = ε_i − ε_{i−1} 是 MA(1) 过程，理论 r1 = −0.5；缓冲/批化使残差结构化并
     * 偏离该基线（nginx 式大批 → r1 趋近 0；交替微批 → r1 趋近 −1）。experimental。
     */
    const val CLEAN_LAG1_BASELINE: Double = -0.5

    /** R1 弱信号线（工程惯用「差」档，KPI 5.2）：RSRP < −105dBm 或 SINR < 0dB。experimental。 */
    const val RSRP_WEAK_DBM: Double = -105.0
    const val SINR_WEAK_DB: Double = 0.0

    /** R1 优良信号线（「良」档下界）：RSRP ≥ −95dBm 且 SINR ≥ 10dB。experimental。 */
    const val RSRP_GOOD_DBM: Double = -95.0
    const val SINR_GOOD_DB: Double = 10.0

    // =====================================================================
    // 评分权重（KDoc 说明推导；比例 5:3:2 为工程先验，experimental，
    // 阶段一签名样本回流后重新加权——因此所有分量原始值随报告透出）
    // =====================================================================

    /**
     * 锯齿占比权重 0.5：「正尖峰 + 负残差簇」是缓冲攒-放的**直接因果签名**
     * （KPI 5.3.3 原文「负残差簇+正残差尖峰的锯齿才是缓冲证据」），
     * 干净流与 profile 内生节奏在残差域均不产生该形态，误报面最小 → 最高权重。
     */
    const val WEIGHT_SAWTOOTH: Double = 0.5

    /**
     * 近零到达间隔占比权重 0.3：批内成员背靠背投递的必然表现，但也可由良性
     * TLS record 合帧 / 读线程调度（R-04）产生，区分力次于锯齿 → 次权重。
     */
    const val WEIGHT_NEAR_ZERO: Double = 0.3

    /**
     * 自相关分量权重 0.2：残差滞后1自相关对 MA(1) 干净基线（[CLEAN_LAG1_BASELINE]）
     * 的偏离度，度量残差结构化程度；对「时延噪声独立」假设敏感（真实网络排队
     * 时延正相关会抬升基线），最噪 → 最低权重。
     */
    const val WEIGHT_AUTOCORR: Double = 0.2

    /**
     * 分析逐 seq 残差序列，产出连续 buffering_score 与归因假设。
     *
     * 不修改、不丢弃、不 clamp 任何输入样本；输入乱序时按 seq 排序后分析。
     *
     * @param samples 逐 seq 残差样本（从第 2 个 token 起自然产生间隔）；空列表安全。
     * @param radio 可选 R1 信号摘要（场景期间 rsrp/sinr 中位数），null = 无无线信息。
     * @param appJankEventsUs 可选客户端 app_jank 事件时刻集合（µs，与
     *   [ResidualSample.arrivalUs] 同一单调时钟基准）。
     * @param retransRate 可选 retrans 共变量（P3-C05）：服务端 TCP_INFO 连接累计重传
     *   段数 / 场景 token 事件数（[BufferingWiring 侧口径]）。null = 无共变量数据
     *   （非 Linux 服务端、h3、summary 缺失）——行为与引入前完全一致（零回归合同）。
     */
    fun analyze(
        samples: List<ResidualSample>,
        radio: RadioSummary? = null,
        appJankEventsUs: Collection<Long> = emptyList(),
        retransRate: Double? = null,
    ): BufferingReport {
        val sorted = samples.sortedBy { it.seq }
        val n = sorted.size
        if (n == 0) return emptyReport()

        val residuals = DoubleArray(n) { sorted[it].residualUs.toDouble() }

        // ---- 特征 1：锯齿占比（正尖峰后紧跟 ≥1 个负残差簇成员，批攒-放签名） ----
        val sawtoothMarked = markSawtooth(sorted)
        val sawtoothRatio = sawtoothMarked.count { it }.toDouble() / n
        val positiveSpikeRatio = sorted.count { it.residualUs >= POS_SPIKE_US }.toDouble() / n
        val negativeClusterRatio = sorted.count { it.residualUs <= NEG_CLUSTER_US }.toDouble() / n
        val negativeResidualRatio = sorted.count { it.residualUs < 0 }.toDouble() / n

        // ---- 特征 2：残差滞后1自相关（对干净 MA(1) 基线的偏离） ----
        val (lag1, degenerate) = lag1Autocorrelation(residuals)
        val autocorrComponent =
            if (degenerate) 0.0
            else clamp01(abs(lag1 - CLEAN_LAG1_BASELINE) / abs(CLEAN_LAG1_BASELINE))

        // ---- 特征 3：近零到达间隔占比 ----
        val nearZeroArrivalRatio =
            sorted.count { it.arrivalIntervalUs in 0 until NEAR_ZERO_ARRIVAL_US }.toDouble() / n

        // ---- 连续 buffering_score ----
        val score = clamp01(
            WEIGHT_SAWTOOTH * sawtoothRatio +
                WEIGHT_NEAR_ZERO * nearZeroArrivalRatio +
                WEIGHT_AUTOCORR * autocorrComponent
        )

        // ---- 周期性谱：批起点间隔的离散网格命中 ----
        val batchStartArrivals = detectBatchStarts(sorted)
        val batchCount = batchStartArrivals.size
        val interBatch = LongArray(maxOf(0, batchCount - 1)) {
            batchStartArrivals[it + 1] - batchStartArrivals[it]
        }
        val interBatchMedianUs = medianOrNull(interBatch)
        val gridHits = PERIODICITY_GRIDS_US.map { grid -> GridHit(grid, gridHitRatio(interBatch, grid)) }
        val qualified = gridHits.filter { it.hitRatio >= GRID_HIT_THRESHOLD }
        val enoughBatches = batchCount >= MIN_BATCH_COUNT
        val bestGrid = if (enoughBatches) qualified.maxByOrNull { it.gridUs } else null
        val bestGridHitRatio =
            bestGrid?.hitRatio ?: (gridHits.maxOfOrNull { it.hitRatio } ?: 0.0)
        val airlinkPeriodicity = bestGrid != null

        // ---- app_jank 重叠（R-12：设备侧冻结 vs 链路缓冲） ----
        val jankOverlapRatio = jankOverlapRatio(batchStartArrivals, appJankEventsUs)

        // ---- R1 信号联动 ----
        val radioWeak = radio?.let { r ->
            val w1 = r.rsrpMedianDbm?.let { it < RSRP_WEAK_DBM }
            val w2 = r.sinrMedianDb?.let { it < SINR_WEAK_DB }
            if (w1 == null && w2 == null) null else (w1 == true || w2 == true)
        }
        val radioGood = radio?.let { r ->
            val g1 = r.rsrpMedianDbm?.let { it >= RSRP_GOOD_DBM }
            val g2 = r.sinrMedianDb?.let { it >= SINR_GOOD_DB }
            if (g1 == null && g2 == null) null else (g1 != false && g2 != false)
        }

        // ---- retrans 共变量（P3-C05）：只改写"原本会判 MIDDLEBOX"的结论 ----
        // netem 取证（断言 3）：丢包重传批化与中间盒缓冲批化残差签名同形，唯一可靠
        // 区分量是传输层重传共变量。无数据（null）时不参与任何分支——零回归合同。
        val retransSignificant = retransRate != null && retransRate > RETRANS_RATE_SIGNIFICANT

        // ---- 初步归因（假设而非裁决；R-05：弱信号先于任何 MIDDLEBOX 分支） ----
        val attribution = when {
            score < SCORE_ACTIVE_THRESHOLD -> BufferingAttribution.NONE
            batchCount > 0 && jankOverlapRatio >= JANK_OVERLAP_THRESHOLD ->
                BufferingAttribution.DEVICE_SIDE
            radioWeak == true -> BufferingAttribution.AIRLINK_SUSPECT
            airlinkPeriodicity -> BufferingAttribution.AIRLINK_SUSPECT
            radioGood == true ->
                if (retransSignificant) BufferingAttribution.RETRANS_SUSPECT
                else BufferingAttribution.MIDDLEBOX_SUSPECT
            // 无 R1、无网格命中但批起点足量：连续分布倾向中间盒（KPI 5.3.3）；
            // 但重传共变量显著时批化更可能是丢包重传所致 → RETRANS_SUSPECT
            enoughBatches ->
                if (retransSignificant) BufferingAttribution.RETRANS_SUSPECT
                else BufferingAttribution.MIDDLEBOX_SUSPECT
            else -> BufferingAttribution.INDETERMINATE
        }

        return BufferingReport(
            bufferingScore = score,
            attribution = attribution,
            sampleCount = n,
            sawtoothRatio = sawtoothRatio,
            positiveSpikeRatio = positiveSpikeRatio,
            negativeClusterRatio = negativeClusterRatio,
            negativeResidualRatio = negativeResidualRatio,
            lag1Autocorrelation = lag1,
            autocorrelationComponent = autocorrComponent,
            nearZeroArrivalRatio = nearZeroArrivalRatio,
            batchCount = batchCount,
            interBatchMedianUs = interBatchMedianUs,
            gridHits = gridHits,
            bestGridUs = bestGrid?.gridUs,
            bestGridHitRatio = bestGridHitRatio,
            airlinkPeriodicity = airlinkPeriodicity,
            jankOverlapRatio = jankOverlapRatio,
            radioWeak = radioWeak,
            radioGood = radioGood,
            retransRate = retransRate,
        )
    }

    // =====================================================================
    // 内部实现
    // =====================================================================

    /** 标记「正尖峰 + 紧随 ≥1 个负残差簇成员」的锯齿段（尖峰与簇成员均计入）。 */
    private fun markSawtooth(sorted: List<ResidualSample>): BooleanArray {
        val n = sorted.size
        val marked = BooleanArray(n)
        var i = 0
        while (i < n) {
            if (sorted[i].residualUs >= POS_SPIKE_US) {
                var j = i + 1
                while (j < n && sorted[j].residualUs <= NEG_CLUSTER_US) j++
                if (j - i - 1 >= 1) {
                    for (k in i until j) marked[k] = true
                }
                i = maxOf(j, i + 1)
            } else {
                i++
            }
        }
        return marked
    }

    /**
     * 滞后1自相关。返回 (r1, degenerate)：方差退化（近常数序列，如全零残差的
     * 理想干净流）时 r1 记 0 且 degenerate=true，自相关分量按 0 计。
     */
    private fun lag1Autocorrelation(x: DoubleArray): Pair<Double, Boolean> {
        if (x.size < 3) return 0.0 to true
        val mean = x.average()
        var den = 0.0
        for (v in x) den += (v - mean) * (v - mean)
        if (den < 1e-6) return 0.0 to true
        var num = 0.0
        for (i in 0 until x.size - 1) num += (x[i] - mean) * (x[i + 1] - mean)
        return (num / den) to false
    }

    /** 批起点 = 到达间隔 ≥ [BATCH_START_GAP_US] 且下一样本为近零间隔（gap-then-burst）。 */
    private fun detectBatchStarts(sorted: List<ResidualSample>): List<Long> {
        val out = ArrayList<Long>()
        for (i in sorted.indices) {
            if (sorted[i].arrivalIntervalUs < BATCH_START_GAP_US) continue
            val next = sorted.getOrNull(i + 1) ?: continue
            if (next.arrivalIntervalUs in 0 until NEAR_ZERO_ARRIVAL_US) {
                out.add(sorted[i].arrivalUs)
            }
        }
        return out
    }

    /** 间隔到网格最近正整数倍的距离 ≤ 容差即命中。 */
    private fun gridHitRatio(interBatchUs: LongArray, gridUs: Long): Double {
        if (interBatchUs.isEmpty()) return 0.0
        var hits = 0
        for (d in interBatchUs) {
            val k = (d.toDouble() / gridUs).roundToLong()
            if (k >= 1 && abs(d - k * gridUs) <= GRID_TOLERANCE_US) hits++
        }
        return hits.toDouble() / interBatchUs.size
    }

    private fun jankOverlapRatio(batchStarts: List<Long>, jankUs: Collection<Long>): Double {
        if (batchStarts.isEmpty() || jankUs.isEmpty()) return 0.0
        val sortedJank = jankUs.sorted()
        var overlap = 0
        for (b in batchStarts) {
            val idx = sortedJank.binarySearch { it.compareTo(b) }
            val ins = if (idx >= 0) idx else -(idx + 1)
            val near = listOfNotNull(
                sortedJank.getOrNull(ins - 1),
                sortedJank.getOrNull(ins),
            )
            if (near.any { abs(it - b) <= JANK_OVERLAP_WINDOW_US }) overlap++
        }
        return overlap.toDouble() / batchStarts.size
    }

    private fun medianOrNull(v: LongArray): Long? {
        if (v.isEmpty()) return null
        val s = v.sorted()
        return s[s.size / 2]
    }

    private fun clamp01(x: Double): Double = when {
        x < 0.0 -> 0.0
        x > 1.0 -> 1.0
        else -> x
    }

    private fun emptyReport() = BufferingReport(
        bufferingScore = 0.0,
        attribution = BufferingAttribution.NONE,
        sampleCount = 0,
        sawtoothRatio = 0.0,
        positiveSpikeRatio = 0.0,
        negativeClusterRatio = 0.0,
        negativeResidualRatio = 0.0,
        lag1Autocorrelation = 0.0,
        autocorrelationComponent = 0.0,
        nearZeroArrivalRatio = 0.0,
        batchCount = 0,
        interBatchMedianUs = null,
        gridHits = PERIODICITY_GRIDS_US.map { GridHit(it, 0.0) },
        bestGridUs = null,
        bestGridHitRatio = 0.0,
        airlinkPeriodicity = false,
        jankOverlapRatio = 0.0,
        radioWeak = null,
        radioGood = null,
        retransRate = null,
    )
}

/**
 * 单个 token 的残差样本（逐 seq 对齐后的产物，KPI 5.3.4 / 5.3.8）。
 *
 * @property seq event 内嵌序号（join 键，禁止位置配对，R-08）。
 * @property arrivalUs 客户端到达时刻（µs，单调钟；与 app_jank 事件同基准，供重叠比对与批间隔谱）。
 * @property arrivalIntervalUs 到达间隔（µs，相对上一 seq 的到达）。
 * @property residualUs 残差 = 到达间隔 − 发出间隔（µs，**含负值不 clamp**，5.3.4）。
 */
data class ResidualSample(
    val seq: Long,
    val arrivalUs: Long,
    val arrivalIntervalUs: Long,
    val residualUs: Long,
)

/** R1 无线信号摘要（场景期间中位数；字段可缺）。 */
data class RadioSummary(
    val rsrpMedianDbm: Double? = null,
    val sinrMedianDb: Double? = null,
)

/**
 * 批化初步归因——**假设而非裁决**（R-05：检测器绝不判无效，
 * 有效性 Gate 与阈值标定在阶段一签名样本回流后由上游完成）。
 */
enum class BufferingAttribution {
    /** buffering_score 低于活跃线，无批化证据。 */
    NONE,

    /** 疑似空口 TTI/C-DRX 聚合：弱信号+批化，或批间隔命中 8/10/20/40ms 离散网格。 */
    AIRLINK_SUSPECT,

    /** 疑似中间盒缓冲：批间隔连续分布/RTT 相关，或信号优+批化。 */
    MIDDLEBOX_SUSPECT,

    /**
     * 疑似丢包重传批化（P3-C05，additive）：批化特征本会判 MIDDLEBOX_SUSPECT，
     * 但服务端 TCP_INFO retrans 共变量显著（retransRate >
     * [BufferingDetector.RETRANS_RATE_SIGNIFICANT]）——弱网丢包下 TCP 重传/乱序
     * 重组造成的到达批化与中间盒缓冲签名同形（netem 100ms/1% 取证断言 3），
     * 重传共变量是当前唯一可靠区分量。与其余归因一样仅是假设标注（R-05）。
     */
    RETRANS_SUSPECT,

    /** 设备侧冻结（device_side_batching，R-12）：批起点与 app_jank 事件时间轴重叠。 */
    DEVICE_SIDE,

    /** 有批化证据但区分特征不足（批起点过少且无 R1/jank 佐证）。 */
    INDETERMINATE,
}

/** 单一网格的周期性命中率（原始值透出，供标定）。 */
data class GridHit(
    val gridUs: Long,
    val hitRatio: Double,
)

/**
 * 批化检测报告。除 [bufferingScore] 与 [attribution] 外，**全部特征原始数值透出**，
 * 供阶段一签名样本标定重新加权（R-05：永远保留原始数据）。
 */
data class BufferingReport(
    /** 连续批化分 ∈ [0,1]：锯齿占比、近零间隔占比、自相关偏离的加权和。 */
    val bufferingScore: Double,
    /** 初步归因假设（非有效性裁决）。 */
    val attribution: BufferingAttribution,
    /** 参与分析的样本数（恒等于输入样本数——不丢样本）。 */
    val sampleCount: Int,
    /** 锯齿占比：正尖峰+负残差簇段样本 / 全部样本。 */
    val sawtoothRatio: Double,
    /** 正残差尖峰（≥ [BufferingDetector.POS_SPIKE_US]）占比。 */
    val positiveSpikeRatio: Double,
    /** 负残差簇成员（≤ [BufferingDetector.NEG_CLUSTER_US]）占比。 */
    val negativeClusterRatio: Double,
    /** 负残差占比（任意负值；发出时刻可信度参考，5.3.4）。 */
    val negativeResidualRatio: Double,
    /** 残差滞后1自相关原始值（方差退化时为 0）。 */
    val lag1Autocorrelation: Double,
    /** 自相关分量 = |r1 − 干净 MA(1) 基线| / |基线|，∈ [0,1]。 */
    val autocorrelationComponent: Double,
    /** 近零到达间隔（< [BufferingDetector.NEAR_ZERO_ARRIVAL_US]）占比。 */
    val nearZeroArrivalRatio: Double,
    /** 批起点（gap-then-burst）个数。 */
    val batchCount: Int,
    /** 批起点间隔中位数（µs），批起点 <2 时为 null。 */
    val interBatchMedianUs: Long?,
    /** 各离散网格（8/10/20/40ms）的命中率。 */
    val gridHits: List<GridHit>,
    /** 命中率达标的最大网格周期（µs）；无达标网格或批起点不足时为 null。 */
    val bestGridUs: Long?,
    /** [bestGridUs] 的命中率；无达标网格时为全网格最大命中率。 */
    val bestGridHitRatio: Double,
    /** 是否呈空口 TTI/DRX 离散周期特征（批起点足量且有网格达标）。 */
    val airlinkPeriodicity: Boolean,
    /** 批起点与 app_jank 事件的重叠率。 */
    val jankOverlapRatio: Double,
    /** R1 弱信号（差档）；无 R1 数据时 null。 */
    val radioWeak: Boolean?,
    /** R1 优良信号（良档以上）；无 R1 数据时 null。 */
    val radioGood: Boolean?,
    /**
     * retrans 共变量原始值（P3-C05：服务端 TCP_INFO 连接累计重传段数 / 事件数）；
     * 无共变量数据（非 Linux 服务端/h3/summary 缺失）时 null。原样透出供标定重加权。
     */
    val retransRate: Double? = null,
)
