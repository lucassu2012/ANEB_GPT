package com.aneb.probe.scoring

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 多次测试敏感度报告分析器（analysis layer ③；纯 JVM，无 Android 依赖）。
 *
 * 目的：把"同一探针在不同网络条件下的多次 run 摘要"收敛为一份**结论性、可诚实展示**的
 * 敏感度报告——量化"网络条件恶化 → AI 业务体验 KPI 如何变"的方向与量级，供 App 工程内部
 * 多次测试后展示"分层测试敏感度报告"。
 *
 * ## 诚实红线（对齐 R-10 / claim scope，见 KPI 文档 5.3.2 / 5.4）
 * - **实测优先**：敏感度结论只用实际 run 数据算——有 netem 分组用分组均值对比，无分组则跨 run
 *   相关性/线性回归；样本或不同网络条件不足则**如实降级**为"需更多样本"，绝不硬凑趋势。
 * - **派生量显式标注**：token 消耗投影是由中断率/丢包外推的**估算**，恒带 "派生/估算,非直接测量"
 *   标记，并附文献锚点（口径不同、来源注明）供对照，不与实测值混同。
 * - **claim scope 边界**：所有结论限"终端至仿真节点的应用层端到端路径"，
 *   不外推为无线层/IP 层丢包/运营商全网/SLA/MOS 结论。
 *
 * 本类只做只读分析：不改 UI/engine/scoring 既有测量语义，不落库、不发网络。
 */
object ReportAnalyzer {

    const val ANALYZER_VERSION: String = "report-analyzer-v0.1"

    /** claim scope 常量（与 KPI 文档 5.4 同源锁定）。 */
    const val CLAIM_SCOPE: String = "application_end_to_end_to_probe_node"

    /** 每条结论文案统一附带的 claim scope 边界话术。 */
    const val CLAIM_SCOPE_NOTE: String =
        "本报告结论限\"终端至仿真节点的应用层端到端路径\"（claim scope=$CLAIM_SCOPE），" +
            "不外推为无线层/IP 层丢包/运营商全网/SLA/MOS 结论。"

    /** 分组对比所需最小分组数（不同 netemProfile）。 */
    const val MIN_GROUPS_FOR_GROUPED: Int = 2

    /** 无分组时相关性分析所需最小不同网络条件（不同 RTT/丢包）样本数。 */
    const val MIN_DISTINCT_CONDITIONS: Int = 3

    /** HALO 文献锚点：TCP 丢包每 +1% → TPOT 约 +617.2ms。 */
    const val HALO_TPOT_MS_PER_LOSS_PCT: Double = 617.2

    /** 上下文重发投影所用的会话上下文 token 规模区间（诉求文档：单命令 5 万–15 万，深链 20 万+）。 */
    const val CONTEXT_TOKENS_LOW: Double = 50_000.0
    const val CONTEXT_TOKENS_HIGH: Double = 200_000.0

    val HALO_ANCHOR = LiteratureAnchor(
        name = "HALO",
        statement = "对 TCP，丢包率每增 1%，TPOT 约 +617.2ms（1% 丢包时开销≈无丢包 TPOT 的 70%）",
        source = "arXiv:2601.11676（8 树莓派 dllama+TinyLlama 张量并行；绝对值随模型/硬件变，趋势普适）",
    )

    val ELOQUENT_ANCHOR = LiteratureAnchor(
        name = "Eloquent",
        statement = "丢包 10.5–15.8% 时 GPT-3.5 的 P95 token 卡顿从 377ms 恶化到 3483ms",
        source = "arXiv:2401.12961（SIGCOMM NAIC'24；移动 LTE 步行场景，口径为 P95 ITL）",
    )

    // ---------------------------------------------------------------------
    // 输入
    // ---------------------------------------------------------------------

    /**
     * 单次 run 的摘要（分析层输入；由上层从 Room 实体/评分结果映射，本类不依赖 Room）。
     *
     * 网络条件字段（自变量）与 AI 业务字段（因变量）都可缺失（null）——缺失项自动被
     * 对应维度的分析跳过，绝不以 0 顶替（R-10）。
     *
     * @param runId run 标识（趋势序列/溯源用）
     * @param transport 传输/接入类型（如 "wifi" / "cellular" / "sim"），仅标签用途
     * @param rttMs 网络条件：应用层 RTT（N1），ms
     * @param jitterMs 网络条件：抖动（N2），ms
     * @param lossPct 网络条件：丢包率（%，如 netem 注入已知值）；未知记 null
     * @param rsrp 网络条件：无线信号 RSRP（dBm，真机蜂窝）；未知记 null
     * @param ttftMs AI 指标：首字延迟 T1，ms
     * @param itlP95Ms AI 指标：ITL P95 T2，ms
     * @param stallRate AI 指标：stall 率 T3，ratio 0..1
     * @param upMbps AI 指标：有效上行吞吐 U1，Mbps
     * @param aqs AI 综合体验分 AQS，0..100
     * @param netemProfile netem 剖面/分组键（如 "clean" / "delay100_loss1"）；无分组记 null
     * @param validity 有效性三态；INVALID 的 run 不进任何聚合（fail-closed）
     * @param epochMs 采集时刻（趋势按时间排序用，可选）
     */
    data class RunSummary(
        val runId: String,
        val transport: String,
        val rttMs: Double? = null,
        val jitterMs: Double? = null,
        val lossPct: Double? = null,
        val rsrp: Double? = null,
        val ttftMs: Double? = null,
        val itlP95Ms: Double? = null,
        val stallRate: Double? = null,
        val upMbps: Double? = null,
        val aqs: Double? = null,
        val netemProfile: String? = null,
        val validity: Validity = Validity.VALID,
        val epochMs: Long? = null,
    )

    // ---------------------------------------------------------------------
    // 输出
    // ---------------------------------------------------------------------

    /** 文献锚点（对照用；口径与本探针不同，来源显式）。 */
    data class LiteratureAnchor(
        val name: String,
        val statement: String,
        val source: String,
    )

    /** 分析方法枚举（结论透明：读者可知结论从哪来）。 */
    enum class Method {
        /** 有 ≥2 个 netemProfile 分组：分组均值对比 */
        GROUPED,
        /** 无分组但有 ≥3 个不同网络条件：跨 run 相关性/线性回归 */
        CORRELATION,
        /** 样本/不同网络条件不足：如实降级 */
        INSUFFICIENT,
    }

    /** 一个自变量→因变量的量化敏感度发现（实测）。 */
    data class SensitivityFinding(
        /** 自变量维度："rtt" / "loss" / "jitter" / "rsrp" */
        val driver: String,
        /** 因变量维度："ttft" / "itl" / "stall" / "up" / "aqs" */
        val metric: String,
        /** 自变量基线值→对比值（分组模式为组均值；相关模式为 min→max 条件） */
        val driverFrom: Double,
        val driverTo: Double,
        /** 因变量基线值→对比值 */
        val metricFrom: Double,
        val metricTo: Double,
        /** 因变量绝对变化（metricTo − metricFrom） */
        val absDelta: Double,
        /** 因变量相对变化（%）；基线为 0 无法算相对时为 null */
        val pctDelta: Double?,
        /** 相关模式下的线性回归斜率（因变量单位 / 自变量单位）；分组模式为 null */
        val slopePerUnit: Double? = null,
        /** Pearson 相关系数（相关模式）；分组模式为 null */
        val pearson: Double? = null,
        /** 参与计算的样本/条件数 */
        val n: Int,
        /** 低置信（样本少或含 VALID_LOW_CONFIDENCE） */
        val lowConfidence: Boolean,
    )

    /**
     * token 消耗派生投影（**估算，非直接测量**）。
     *
     * 两个分量：
     * 1. **上行重发上下文**：会话中断率 × 每次中断重发的上下文 token 规模 → 每会话多传的上行 token。
     * 2. **stall 拉长的 TPOT**：由丢包增量按 HALO 锚点外推的每 token 时延增量。
     */
    data class TokenProjection(
        val estimate: Boolean = true,
        val marker: String = "派生/估算,非直接测量",
        /** 用于 TPOT 外推的丢包增量（%）；无则 null */
        val lossPctDelta: Double?,
        /** 每 token TPOT 增量区间（ms），由 HALO 锚点 ×[0.5,1.0] 保守带；无丢包增量则 null */
        val tpotElongationMsLow: Double?,
        val tpotElongationMsHigh: Double?,
        /** 会话中断率（ratio 0..1）；无实测则 null */
        val sessionDropRate: Double?,
        /** 每会话上行重发上下文 token 区间；无中断率则 null */
        val uplinkResendTokensLow: Double?,
        val uplinkResendTokensHigh: Double?,
        val literatureAnchors: List<LiteratureAnchor>,
        val note: String,
    )

    /** 一条 KPI 的时间/条件序列（供 UI 画线；null 保留为断点，绝不填 0）。 */
    data class KpiSeries(
        val metric: String,
        val runIds: List<String>,
        val values: List<Double?>,
    )

    /** 各 KPI 与网络条件随序列的趋势（按 epoch 或输入顺序排序）。 */
    data class Trends(
        val orderedRunIds: List<String>,
        val series: List<KpiSeries>,
    )

    /** 完整分析报告。 */
    data class ReportAnalysis(
        val analyzerVersion: String,
        val method: Method,
        /** 参与分析的有效 run 数（已剔除 INVALID） */
        val validRunCount: Int,
        /** 不同网络条件数（按 profile 或 rtt/loss 去重） */
        val distinctConditionCount: Int,
        val sensitivity: List<SensitivityFinding>,
        val tokenProjection: TokenProjection,
        val trends: Trends,
        /** 结论文案（中文，一句一条，带数字；含 claim scope 与"数据不足"话术） */
        val conclusions: List<String>,
        val claimScope: String = CLAIM_SCOPE,
        val claimScopeNote: String = CLAIM_SCOPE_NOTE,
    )

    // ---------------------------------------------------------------------
    // 入口
    // ---------------------------------------------------------------------

    /** 因变量维度定义：id → (取值抽取, 中文名, 单位, 是否"高者优"）。 */
    private data class MetricDef(
        val id: String,
        val label: String,
        val unit: String,
        val higherBetter: Boolean,
        val extract: (RunSummary) -> Double?,
    )

    private val METRICS: List<MetricDef> = listOf(
        MetricDef("ttft", "首字延迟 TTFT", "ms", false) { it.ttftMs },
        MetricDef("itl", "ITL P95", "ms", false) { it.itlP95Ms },
        MetricDef("stall", "stall 率", "ratio", false) { it.stallRate },
        MetricDef("up", "有效上行吞吐", "Mbps", true) { it.upMbps },
        MetricDef("aqs", "AQS", "分", true) { it.aqs },
    )

    /**
     * 分析入口。
     *
     * @param runs 多次 run 摘要
     * @param sessionDropRate 可选：会话中断率实测（C1，ratio 0..1），用于 token 上行重发投影；
     *   缺省 null → 该分量如实标注"无中断率实测"
     */
    fun analyze(runs: List<RunSummary>, sessionDropRate: Double? = null): ReportAnalysis {
        val valid = runs.filter { it.validity != Validity.INVALID }
        val trends = buildTrends(valid)

        val groups = valid.filter { it.netemProfile != null }
            .groupBy { it.netemProfile!! }
        val distinctProfiles = groups.keys.size
        val distinctConditions = distinctConditionCount(valid)

        val method: Method
        val findings: List<SensitivityFinding>
        when {
            distinctProfiles >= MIN_GROUPS_FOR_GROUPED -> {
                method = Method.GROUPED
                findings = groupedSensitivity(groups)
            }
            distinctConditions >= MIN_DISTINCT_CONDITIONS -> {
                method = Method.CORRELATION
                findings = correlationSensitivity(valid)
            }
            else -> {
                method = Method.INSUFFICIENT
                findings = emptyList()
            }
        }

        val projection = buildTokenProjection(valid, groups, method, findings, sessionDropRate)
        val conclusions = buildConclusions(method, valid.size, distinctConditions, findings, projection)

        return ReportAnalysis(
            analyzerVersion = ANALYZER_VERSION,
            method = method,
            validRunCount = valid.size,
            distinctConditionCount = distinctConditions,
            sensitivity = findings,
            tokenProjection = projection,
            trends = trends,
            conclusions = conclusions,
        )
    }

    // ---------------------------------------------------------------------
    // 分组模式（有 netem 剖面）
    // ---------------------------------------------------------------------

    private fun groupedSensitivity(groups: Map<String, List<RunSummary>>): List<SensitivityFinding> {
        // 基线组 = 平均 RTT 最小者（网络最好）；RTT 全缺失时退化取平均丢包最小者。
        val baselineKey = groups.entries.minByOrNull { (_, rs) ->
            meanOf(rs) { it.rttMs } ?: meanOf(rs) { it.lossPct } ?: Double.MAX_VALUE
        }?.key ?: return emptyList()
        val baseline = groups.getValue(baselineKey)

        val out = mutableListOf<SensitivityFinding>()
        for ((key, impaired) in groups) {
            if (key == baselineKey) continue
            // netem 受控自变量：RTT 与丢包同变时两者都出结论（单组无法独立归因，如实并列）。
            val drivers = changedDrivers(baseline, impaired)
            if (drivers.isEmpty()) continue
            val lowConf = (baseline + impaired).any { it.validity == Validity.VALID_LOW_CONFIDENCE } ||
                (baseline.size < 2 || impaired.size < 2)
            for (driver in drivers) {
                for (m in METRICS) {
                    val from = meanOf(baseline, m.extract) ?: continue
                    val to = meanOf(impaired, m.extract) ?: continue
                    out += SensitivityFinding(
                        driver = driver.id,
                        metric = m.id,
                        driverFrom = driver.from,
                        driverTo = driver.to,
                        metricFrom = from,
                        metricTo = to,
                        absDelta = to - from,
                        pctDelta = pct(from, to),
                        n = baseline.size + impaired.size,
                        lowConfidence = lowConf,
                    )
                }
            }
        }
        return out
    }

    private data class Driver(val id: String, val from: Double, val to: Double)

    private fun changedDrivers(baseline: List<RunSummary>, impaired: List<RunSummary>): List<Driver> {
        // 只在**劣化方向**（impaired 比 baseline 更差）报驱动：结论/派生投影文案统一以"增至/恶化"
        // 措辞，若某组自变量反而更优（如 baseline 按最低 RTT 选时某组丢包更低）则不出该驱动，
        // 避免把"下降"叙述成"增至"。RTT/丢包更大＝更差，故门槛为 B>A（含 1e-9 容差）。
        val out = mutableListOf<Driver>()
        val rttA = meanOf(baseline) { it.rttMs }
        val rttB = meanOf(impaired) { it.rttMs }
        if (rttA != null && rttB != null && rttB - rttA > 1e-9) out += Driver("rtt", rttA, rttB)
        val lossA = meanOf(baseline) { it.lossPct }
        val lossB = meanOf(impaired) { it.lossPct }
        if (lossA != null && lossB != null && lossB - lossA > 1e-9) out += Driver("loss", lossA, lossB)
        return out
    }

    // ---------------------------------------------------------------------
    // 相关模式（无剖面，跨 run 回归）
    // ---------------------------------------------------------------------

    private fun correlationSensitivity(valid: List<RunSummary>): List<SensitivityFinding> {
        val drivers = listOf<Triple<String, (RunSummary) -> Double?, Boolean>>(
            Triple("rtt", { it.rttMs }, true),
            Triple("loss", { it.lossPct }, false),
        )
        val out = mutableListOf<SensitivityFinding>()
        for ((driverId, driverExtract, _) in drivers) {
            val driverVals = valid.mapNotNull(driverExtract)
            if (driverVals.distinct().size < MIN_DISTINCT_CONDITIONS) continue
            for (m in METRICS) {
                val pairs = valid.mapNotNull { r ->
                    val x = driverExtract(r) ?: return@mapNotNull null
                    val y = m.extract(r) ?: return@mapNotNull null
                    x to y
                }
                if (pairs.map { it.first }.distinct().size < MIN_DISTINCT_CONDITIONS) continue
                val xs = pairs.map { it.first }
                val ys = pairs.map { it.second }
                val r = pearson(xs, ys) ?: continue
                val slope = slope(xs, ys) ?: continue
                val xFrom = xs.min()
                val xTo = xs.max()
                val yFrom = ys[xs.indexOf(xFrom)]
                val yTo = ys[xs.indexOf(xTo)]
                out += SensitivityFinding(
                    driver = driverId,
                    metric = m.id,
                    driverFrom = xFrom,
                    driverTo = xTo,
                    metricFrom = yFrom,
                    metricTo = yTo,
                    absDelta = slope * (xTo - xFrom),
                    pctDelta = pct(yFrom, yFrom + slope * (xTo - xFrom)),
                    slopePerUnit = slope,
                    pearson = r,
                    n = pairs.size,
                    lowConfidence = pairs.size < 5 ||
                        valid.any { it.validity == Validity.VALID_LOW_CONFIDENCE },
                )
            }
        }
        return out
    }

    // ---------------------------------------------------------------------
    // token 消耗派生投影
    // ---------------------------------------------------------------------

    private fun buildTokenProjection(
        valid: List<RunSummary>,
        groups: Map<String, List<RunSummary>>,
        method: Method,
        findings: List<SensitivityFinding>,
        sessionDropRate: Double?,
    ): TokenProjection {
        // 丢包增量：分组模式取 finding 里 driver=loss 的增量；否则取全体 loss 极差。
        val lossDelta: Double? = findings.firstOrNull { it.driver == "loss" }
            ?.let { it.driverTo - it.driverFrom }
            ?: run {
                val losses = valid.mapNotNull { it.lossPct }
                if (losses.size >= 2 && losses.max() > losses.min()) {
                    losses.max() - losses.min()
                } else {
                    null
                }
            }

        val tpotLow = lossDelta?.let { it * HALO_TPOT_MS_PER_LOSS_PCT * 0.5 }
        val tpotHigh = lossDelta?.let { it * HALO_TPOT_MS_PER_LOSS_PCT * 1.0 }

        val resendLow = sessionDropRate?.let { it * CONTEXT_TOKENS_LOW }
        val resendHigh = sessionDropRate?.let { it * CONTEXT_TOKENS_HIGH }

        val notes = buildList {
            add("派生/估算，非直接测量：本探针未直接计量 token 账单。")
            if (lossDelta != null) {
                add("TPOT 分量按 HALO 锚点 617.2ms/1% 丢包外推，保守取 ×[0.5,1.0] 区间（绝对值随模型/硬件变）。")
            } else {
                add("无可用丢包增量，TPOT 拉长分量不投影。")
            }
            if (sessionDropRate != null) {
                add("上行重发分量 = 会话中断率 × 上下文 token 规模[5万,20万]。")
            } else {
                add("无会话中断率实测，上行重发分量不投影（需 C 组连续性数据）。")
            }
        }.joinToString(" ")

        return TokenProjection(
            lossPctDelta = lossDelta,
            tpotElongationMsLow = tpotLow,
            tpotElongationMsHigh = tpotHigh,
            sessionDropRate = sessionDropRate,
            uplinkResendTokensLow = resendLow,
            uplinkResendTokensHigh = resendHigh,
            literatureAnchors = listOf(HALO_ANCHOR, ELOQUENT_ANCHOR),
            note = notes,
        )
    }

    // ---------------------------------------------------------------------
    // 趋势
    // ---------------------------------------------------------------------

    private fun buildTrends(valid: List<RunSummary>): Trends {
        val ordered = valid.sortedWith(
            compareBy({ it.epochMs ?: Long.MAX_VALUE }),
        )
        val ids = ordered.map { it.runId }
        val netSeries = listOf<Pair<String, (RunSummary) -> Double?>>(
            "rtt" to { it.rttMs },
            "jitter" to { it.jitterMs },
            "loss" to { it.lossPct },
            "rsrp" to { it.rsrp },
        )
        val kpiSeries = METRICS.map { m -> m.id to m.extract }
        val series = (netSeries + kpiSeries).map { (id, extract) ->
            KpiSeries(id, ids, ordered.map(extract))
        }
        return Trends(orderedRunIds = ids, series = series)
    }

    // ---------------------------------------------------------------------
    // 结论文案
    // ---------------------------------------------------------------------

    private fun buildConclusions(
        method: Method,
        validCount: Int,
        distinctConditions: Int,
        findings: List<SensitivityFinding>,
        projection: TokenProjection,
    ): List<String> {
        val out = mutableListOf<String>()
        when (method) {
            Method.INSUFFICIENT -> {
                out += "样本不足：当前有效 run n=$validCount，不同网络条件=$distinctConditions" +
                    "（<${MIN_DISTINCT_CONDITIONS}），无法给出敏感度结论——需在更多不同网络条件下补测。"
            }
            Method.GROUPED, Method.CORRELATION -> {
                // 只对 RTT 驱动挑一条主结论合并叙述（TTFT/上行/AQS）。
                val rttFindings = findings.filter { it.driver == "rtt" }.associateBy { it.metric }
                val lossFindings = findings.filter { it.driver == "loss" }.associateBy { it.metric }
                rttFindings["ttft"]?.let { f ->
                    val extras = listOfNotNull(
                        rttFindings["up"]?.let { "有效上行吞吐 ${signedPct(it.pctDelta)}" },
                        rttFindings["aqs"]?.let { "AQS ${signedDelta(it.absDelta, "分")}" },
                    ).joinToString("、")
                    out += "网络往返时延 RTT 从 ${fmt(f.driverFrom)}ms 增至 ${fmt(f.driverTo)}ms" +
                        "（${signedPct(pct(f.driverFrom, f.driverTo))}）时，" +
                        "首字延迟 TTFT ${signedPct(f.pctDelta)}（${signedDelta(f.absDelta, "ms")}）" +
                        (if (extras.isNotEmpty()) "、$extras" else "") +
                        lowConfSuffix(f) + "。"
                }
                lossFindings["stall"]?.let { f ->
                    out += "丢包率从 ${fmt(f.driverFrom)}% 增至 ${fmt(f.driverTo)}%时，" +
                        "stall 率 ${signedDelta(f.absDelta, "")}（${fmt(f.metricFrom)}→${fmt(f.metricTo)}）" +
                        lowConfSuffix(f) + "。"
                }
                lossFindings["up"]?.let { f ->
                    out += "丢包率从 ${fmt(f.driverFrom)}% 增至 ${fmt(f.driverTo)}%时，" +
                        "有效上行吞吐 ${signedPct(f.pctDelta)}（${fmt(f.metricFrom)}→${fmt(f.metricTo)} Mbps，" +
                        "拥塞窗口收缩）" + lowConfSuffix(f) + "。"
                }
                if (method == Method.CORRELATION) {
                    rttFindings["ttft"]?.pearson?.let { r ->
                        out += "跨 run 相关性：RTT↔TTFT Pearson r=${fmt(r)}（n=${rttFindings["ttft"]!!.n}），" +
                            "斜率≈每 +10ms RTT，TTFT ${signedDelta((rttFindings["ttft"]!!.slopePerUnit ?: 0.0) * 10, "ms")}。"
                    }
                }
                if (out.isEmpty()) {
                    out += "已按${if (method == Method.GROUPED) "分组均值对比" else "跨 run 相关性"}" +
                        "分析，但可用因变量维度不足以形成主结论——建议补齐 TTFT/上行/AQS 落库。"
                }
            }
        }

        // token 派生投影（恒带估算标记）
        val proj = StringBuilder("token 消耗投影（派生/估算，非直接测量）：")
        if (projection.tpotElongationMsHigh != null && projection.lossPctDelta != null) {
            proj.append("丢包 +${fmt(projection.lossPctDelta)}% 下，按 HALO 锚点外推每 token TPOT 约 +" +
                "${fmt(projection.tpotElongationMsLow!!)}–${fmt(projection.tpotElongationMsHigh)}ms；")
        }
        if (projection.uplinkResendTokensHigh != null && projection.sessionDropRate != null) {
            proj.append("会话中断率 ${fmt(projection.sessionDropRate * 100)}% × 上下文[5万,20万] token → " +
                "每会话多传上行约 ${fmt(projection.uplinkResendTokensLow!!)}–${fmt(projection.uplinkResendTokensHigh)} token；")
        }
        if (projection.tpotElongationMsHigh == null && projection.uplinkResendTokensHigh == null) {
            proj.append("当前缺丢包增量与会话中断率实测，暂不投影（文献锚点：HALO 617.2ms/1%丢包、Eloquent P95 377→3483ms 供对照）。")
        } else {
            proj.append("文献锚点仅供对照，口径与本探针不同。")
        }
        out += proj.toString()

        out += CLAIM_SCOPE_NOTE
        return out
    }

    // ---------------------------------------------------------------------
    // 数值工具（纯函数）
    // ---------------------------------------------------------------------

    /** 不同网络条件数：优先按 profile，否则按 (rtt,loss) 去重。 */
    private fun distinctConditionCount(valid: List<RunSummary>): Int {
        val profiles = valid.mapNotNull { it.netemProfile }.distinct()
        if (profiles.isNotEmpty()) return profiles.size
        return valid.map { it.rttMs to it.lossPct }
            .filter { it.first != null || it.second != null }
            .distinct().size
    }

    private fun meanOf(rs: List<RunSummary>, extract: (RunSummary) -> Double?): Double? {
        val vals = rs.mapNotNull(extract)
        return if (vals.isEmpty()) null else vals.sum() / vals.size
    }

    /** 相对变化 %（from→to）；from≈0 时返回 null（不可算相对）。 */
    private fun pct(from: Double, to: Double): Double? =
        if (abs(from) < 1e-9) null else (to - from) / from * 100.0

    private fun pearson(xs: List<Double>, ys: List<Double>): Double? {
        val n = xs.size
        if (n < 2) return null
        val mx = xs.sum() / n
        val my = ys.sum() / n
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - mx
            val dy = ys[i] - my
            sxy += dx * dy
            sxx += dx * dx
            syy += dy * dy
        }
        if (sxx < 1e-12 || syy < 1e-12) return null
        return sxy / sqrt(sxx * syy)
    }

    /** 最小二乘斜率 dy/dx。 */
    private fun slope(xs: List<Double>, ys: List<Double>): Double? {
        val n = xs.size
        if (n < 2) return null
        val mx = xs.sum() / n
        val my = ys.sum() / n
        var sxy = 0.0
        var sxx = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - mx
            sxy += dx * (ys[i] - my)
            sxx += dx * dx
        }
        if (sxx < 1e-12) return null
        return sxy / sxx
    }

    private fun fmt(v: Double): String {
        val abs = abs(v)
        return when {
            abs >= 1000.0 -> "%.0f".format(v)
            abs >= 10.0 -> "%.1f".format(v)
            abs >= 1.0 -> "%.2f".format(v)
            else -> "%.3f".format(v)
        }
    }

    private fun signedPct(p: Double?): String =
        if (p == null) "变化不可算相对值" else (if (p >= 0) "+" else "") + fmt(p) + "%"

    private fun signedDelta(d: Double, unit: String): String =
        (if (d >= 0) "+" else "") + fmt(d) + unit

    private fun lowConfSuffix(f: SensitivityFinding): String =
        if (f.lowConfidence) "（低置信：样本量/条件有限）" else ""
}
