package com.aneb.probe.scoring

import kotlin.math.min

/**
 * AQS 综合评分（aqs v0.1，KPI 文档 5.4；门限常量表 = agent-qoe-kpi v0.1，5.2）。
 *
 * ## 映射规则
 * 每个 KPI 按四级门限锚点做分段线性映射：优/良边界=85 分、良/可=70 分、可/差=55 分，
 * 级内线性插值，上限 100、下限 0。
 *
 * 5.4 只定义了 85/70/55 三个内部锚点（红队 R-28：优档上锚与差档下锚未定义区）。
 * 本实现按以下**显式补全锚点**（aqs v0.1 实现内定义，随版本号锁定、待阶段一标定修订）：
 * - 时延/比率类（低者优）：0 → 100 分；3×(可/差边界) → 0 分；
 * - U1 吞吐（高者优）：0 Mbps → 0 分；100 Mbps → 100 分。
 * 锚点之间线性插值，越界 clamp 到端点分。
 *
 * ## 权重（MVP，5.4 表）
 * T1 20% + T3 20% + T2 15% + U1 15% + U2 10% + N1 10% + N2 10%（合计 100%）。
 * T4 为一票否决项：T4 > 1% 时 AQS 封顶 54（不参与加权）。
 *
 * ## 进入评分的口径
 * - T2：主口径（合帧组内只保留组首间隔，5.4）；
 * - T3：主口径（不含 resume_latency，T5 已剔除，R-09）；
 * - U1：含慢启动主口径（2xx 有效 goodput）；
 * - T5 不进 AQS（5.1）。
 *
 * ## 有效性语义（5.4 / R-10）
 * - INVALID 场景不出分（score = null + 原因）；
 * - 任一权重项 KPI 值缺失（null）→ AQS 不可计算（score = null + 缺失项清单），绝不以 0 分顶替；
 * - VALID_LOW_CONFIDENCE 或任一权重项带 lowConfidence → 出分但 lowConfidence = true
 *   （展示层必须带低置信标注）。
 *
 * 表述边界：AQS 是实验性应用层综合体验分，测量对象为"终端至指定仿真节点的应用层路径"，
 * 禁止表述为 MOS/无线层评级/运营商全网评级/SLA 结论（5.4）。
 */
object AqsScorer {

    const val AQS_VERSION: String = "aqs-v0.1"

    /**
     * 阶段 2 版本（KPI 文档 5.4：阶段二引入 C 组后，v0.1 权重 ×0.8，C 组占 20%
     * = C1 10% + C2 10%）。仅当调用方提供连续性数据（[ContinuityKpi]）时可选出分；
     * 无 C 数据一律走 v0.1 默认（additive，不改 v0.1 语义）。
     */
    const val AQS_VERSION_V02: String = "aqs-v0.2"
    const val KPI_SET_VERSION: String = KpiCalculator.KPI_SET_VERSION

    /** T4 一票否决线（比率），KPI 文档 5.4 */
    const val T4_VETO_THRESHOLD: Double = 0.01

    /** T4 否决时的 AQS 封顶分 */
    const val T4_VETO_CAP: Double = 54.0

    /** 权重表（合计 1.0），KPI 文档 5.4 */
    val WEIGHTS: Map<String, Double> = mapOf(
        "T1" to 0.20,
        "T3" to 0.20,
        "T2" to 0.15,
        "U1" to 0.15,
        "U2" to 0.10,
        "N1" to 0.10,
        "N2" to 0.10,
    )

    /**
     * v0.2 权重表（合计 1.0）：v0.1 各项 ×0.8 + C1 10% + C2 10%（KPI 文档 5.4 阶段二条款）。
     * 由 v0.1 表推导（单一事实来源，防两表手工漂移）。
     */
    val WEIGHTS_V02: Map<String, Double> =
        WEIGHTS.mapValues { it.value * 0.8 } + mapOf("C1" to 0.10, "C2" to 0.10)

    /**
     * 单调锚点表：(KPI 值, 分数) 对，按值升序。端点外 clamp。
     * 门限数字全部来自 KPI 文档 5.2（agent-qoe-kpi v0.1，实验性）。
     */
    internal class AnchorMap(private val anchors: List<Pair<Double, Double>>) {
        init {
            require(anchors.size >= 2)
            require(anchors.zipWithNext().all { (a, b) -> a.first < b.first }) { "锚点值必须严格升序" }
        }

        fun score(value: Double): Double {
            if (value <= anchors.first().first) return anchors.first().second
            if (value >= anchors.last().first) return anchors.last().second
            for (i in 1 until anchors.size) {
                val (x0, y0) = anchors[i - 1]
                val (x1, y1) = anchors[i]
                if (value <= x1) {
                    return y0 + (value - x0) / (x1 - x0) * (y1 - y0)
                }
            }
            return anchors.last().second // 不可达
        }
    }

    // ---- 门限锚点常量表（agent-qoe-kpi v0.1，5.2；补全锚点见类 KDoc）----
    // 低者优：0→100，优/良→85，良/可→70，可/差→55，3×可差→0
    internal val T1_ANCHORS = AnchorMap(listOf(0.0 to 100.0, 200.0 to 85.0, 500.0 to 70.0, 1000.0 to 55.0, 3000.0 to 0.0))
    internal val T2_ANCHORS = AnchorMap(listOf(0.0 to 100.0, 100.0 to 85.0, 200.0 to 70.0, 400.0 to 55.0, 1200.0 to 0.0))
    internal val T3_ANCHORS = AnchorMap(listOf(0.0 to 100.0, 0.005 to 85.0, 0.02 to 70.0, 0.05 to 55.0, 0.15 to 0.0))
    internal val N1_ANCHORS = AnchorMap(listOf(0.0 to 100.0, 30.0 to 85.0, 60.0 to 70.0, 100.0 to 55.0, 300.0 to 0.0))
    internal val N2_ANCHORS = AnchorMap(listOf(0.0 to 100.0, 10.0 to 85.0, 30.0 to 70.0, 80.0 to 55.0, 240.0 to 0.0))
    internal val U2_ANCHORS = AnchorMap(listOf(0.0 to 100.0, 150.0 to 85.0, 300.0 to 70.0, 600.0 to 55.0, 1800.0 to 0.0))

    // 高者优（U1，Mbps）：0→0，可/差=1→55，良/可=5→70，优/良=20→85，100→100
    internal val U1_ANCHORS = AnchorMap(listOf(0.0 to 0.0, 1.0 to 55.0, 5.0 to 70.0, 20.0 to 85.0, 100.0 to 100.0))

    // ---- C 组门限锚点（agent-qoe-kpi v0.2 / KPI 文档 5.2；补全锚点规则同 v0.1）----
    // C1 会话中断率（ratio，低者优）：0.5% / 2% / 5%，差档下锚 3×0.05=0.15
    internal val C1_ANCHORS = AnchorMap(listOf(0.0 to 100.0, 0.005 to 85.0, 0.02 to 70.0, 0.05 to 55.0, 0.15 to 0.0))

    // C2 切换恢复时间（ms，低者优）：1s / 3s / 10s，差档下锚 3×10000=30000；
    // "失败"（恢复不成功）按 R-10 记 null → v0.2 不可计算（KPI_MISSING:C2），绝不以封顶值顶替
    internal val C2_ANCHORS = AnchorMap(listOf(0.0 to 100.0, 1000.0 to 85.0, 3000.0 to 70.0, 10000.0 to 55.0, 30000.0 to 0.0))

    /**
     * AQS 评分结果。
     *
     * @param score 0–100 综合分；不可计算时 null（绝不 0）
     * @param subScores 各权重项子分（KPI id → 0–100），可计算时非空
     * @param vetoApplied T4 > 1% 一票否决已触发（分数封顶 54）
     * @param lowConfidence 低置信（VALID_LOW_CONFIDENCE 场景或任一权重项样本不足），展示必须带标
     * @param notComputableReason 不可计算原因（"INVALID_SCENARIO:…" / "KPI_MISSING:…"）
     */
    data class AqsResult(
        val aqsVersion: String,
        val kpiSetVersion: String,
        val score: Double?,
        val subScores: Map<String, Double>,
        val vetoApplied: Boolean,
        val lowConfidence: Boolean,
        val notComputableReason: String?,
    )

    /**
     * C 组（连续性）评分输入（阶段 2 连续性实验产出，aqs v0.2 专用）。
     * 失败语义与 [KpiValue] 一致：无有效样本/恢复失败一律 value=null（R-10），
     * v0.2 对 null 的处理与 T/U/N 组相同——不可计算（KPI_MISSING），绝不以 0 顶替。
     *
     * @param c1SessionDropRate 会话中断率（ratio：异常断开次数/流式段总数，跨段聚合）
     * @param c2RecoveryMs 切换恢复时间（ms：中断时刻→重连流首 token 到达；多样本取中位数）
     */
    data class ContinuityKpi(
        val c1SessionDropRate: KpiValue,
        val c2RecoveryMs: KpiValue,
    )

    fun score(kpi: KpiResult): AqsResult =
        scoreWith(kpi, extraInputs = emptyMap(), version = AQS_VERSION)

    /**
     * aqs v0.2 出分入口（additive）：仅当 [continuity] 非 null 时按 v0.2 权重
     * （v0.1×0.8 + C1 10% + C2 10%）计算并标注版本号 [AQS_VERSION_V02]；
     * continuity=null 时语义与 [score] (v0.1) 完全一致——无连续性数据的 run
     * 保持 v0.1 默认（KPI 文档 5.4 阶段二条款）。
     */
    fun score(kpi: KpiResult, continuity: ContinuityKpi?): AqsResult {
        if (continuity == null) return score(kpi)
        return scoreWith(
            kpi,
            extraInputs = mapOf(
                "C1" to continuity.c1SessionDropRate,
                "C2" to continuity.c2RecoveryMs,
            ),
            version = AQS_VERSION_V02,
        )
    }

    private fun scoreWith(
        kpi: KpiResult,
        extraInputs: Map<String, KpiValue>,
        version: String,
    ): AqsResult {
        val weights = if (version == AQS_VERSION_V02) WEIGHTS_V02 else WEIGHTS
        if (kpi.validity == Validity.INVALID) {
            return AqsResult(
                aqsVersion = version,
                kpiSetVersion = KPI_SET_VERSION,
                score = null,
                subScores = emptyMap(),
                vetoApplied = false,
                lowConfidence = false,
                notComputableReason = "INVALID_SCENARIO:" + kpi.invalidReasons.joinToString(","),
            )
        }

        // 进入评分的权重项取值（口径见类 KDoc）
        val inputs: Map<String, KpiValue> = mapOf(
            "T1" to kpi.t1TtftMs,
            "T2" to kpi.t2ItlP95Ms,
            "T3" to kpi.t3StallRate,
            "U1" to kpi.u1GoodputMbps,
            "U2" to kpi.u2ToolLoopP95Ms,
            "N1" to kpi.n1RttP50Ms,
            "N2" to kpi.n2JitterMs,
        ) + extraInputs
        val missing = inputs.filterValues { it.value == null }.keys.sorted()
        if (missing.isNotEmpty()) {
            // 任一权重项缺失 → AQS 不可计算（R-10：绝不以 0 分顶替失败样本）
            return AqsResult(
                aqsVersion = version,
                kpiSetVersion = KPI_SET_VERSION,
                score = null,
                subScores = emptyMap(),
                vetoApplied = false,
                lowConfidence = false,
                notComputableReason = "KPI_MISSING:" + missing.joinToString(","),
            )
        }

        val anchorMaps: Map<String, AnchorMap> = mapOf(
            "T1" to T1_ANCHORS,
            "T2" to T2_ANCHORS,
            "T3" to T3_ANCHORS,
            "U1" to U1_ANCHORS,
            "U2" to U2_ANCHORS,
            "N1" to N1_ANCHORS,
            "N2" to N2_ANCHORS,
            "C1" to C1_ANCHORS,
            "C2" to C2_ANCHORS,
        )
        val subScores = inputs.mapValues { (id, v) -> anchorMaps.getValue(id).score(v.value!!) }
        var total = subScores.entries.sumOf { (id, s) -> s * weights.getValue(id) }

        // T4 一票否决：>1% 时封顶 54（T4 缺失时不触发否决，但 T4 属流式场景必备诊断项）
        val t4 = kpi.t4SevereStallRate.value
        val veto = t4 != null && t4 > T4_VETO_THRESHOLD
        if (veto) total = min(total, T4_VETO_CAP)

        val lowConf = kpi.validity == Validity.VALID_LOW_CONFIDENCE ||
            inputs.values.any { it.lowConfidence }

        return AqsResult(
            aqsVersion = version,
            kpiSetVersion = KPI_SET_VERSION,
            score = total,
            subScores = subScores,
            vetoApplied = veto,
            lowConfidence = lowConf,
            notComputableReason = null,
        )
    }
}
