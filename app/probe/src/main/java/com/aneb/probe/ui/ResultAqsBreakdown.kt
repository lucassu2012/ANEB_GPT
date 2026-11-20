package com.aneb.probe.ui

import com.aneb.probe.scoring.AqsScorer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/**
 * 专业结果视图的 **AQS 真实子分分解**（组 → KPI → 贡献分），纯 JVM、无 Android，可单测。
 *
 * ## 数据来源（D-02：展示层不重算测量）
 * 子分不是本层现算：它们是 run 结束时 [AqsScorer] 计算、并已随 /results 上报体落库
 * 的产物（`report_body.body` → `run.aqs.sub_scores`，键=KPI id、值=0–100 子分）。本层只做：
 * 1) 解析既有落库子分；2) × [AqsScorer.WEIGHTS] / [AqsScorer.WEIGHTS_V02] 得每 KPI 贡献分。
 * 绝不重算 AqsScorer 内部逻辑，也绝不重定义任何门限/权重。
 *
 * ## 红线
 * - R-28：AQS 数字旁必须并列三大组子分（流式/上行/基线），本分解即其数据来源。
 * - R-10：run 不可计算（sub_scores 缺失）→ 返回 `null`；展示层据此走"不可计算 / 分级近似"
 *   分支，绝不以 0 顶替任何 KPI 的子分或贡献分。
 */
object ResultAqsBreakdown {

    private val json = Json { ignoreUnknownKeys = true }

    /** 组内一个 KPI 的真实贡献分。 */
    data class KpiContribution(
        val id: String,
        /** 中文短名（如"首字响应"） */
        val label: String,
        /** 权重（分数，如 0.20），来自 [AqsScorer.WEIGHTS] 单一事实源 */
        val weight: Double,
        /** AqsScorer 落库子分 0..100 */
        val subScore: Double,
    ) {
        /** 该 KPI 满贡献分（= 权重×100，如 20.0） */
        val maxPoints: Double get() = weight * 100.0

        /** 实际贡献分（= 子分×权重，如 97.3×0.20=19.46） */
        val contributionPoints: Double get() = subScore * weight

        val gradeKey: String get() = ResultFormat.aqsGrade(subScore)
    }

    /** 一个 AQS 组（流式/上行/基线/连续性）。 */
    data class Group(
        val label: String,
        val kpis: List<KpiContribution>,
    ) {
        /** 组权重（= 成员权重之和） */
        val weight: Double get() = kpis.sumOf { it.weight }

        /** 组满贡献分（= 组权重×100） */
        val maxPoints: Double get() = weight * 100.0

        /** 组实际小计贡献分（= 成员贡献分之和） */
        val subtotalPoints: Double get() = kpis.sumOf { it.contributionPoints }
    }

    /** 一次 run 的 AQS 分解。 */
    data class Breakdown(
        val aqsVersion: String,
        /** 综合分 0..100；上报体记录的 run.aqs.score */
        val score: Double?,
        val lowConfidence: Boolean,
        val vetoApplied: Boolean,
        val groups: List<Group>,
    )

    /**
     * 本次 run 的拖累维度＝子分最低的那个 KPI；无可用子分时 null（R-10）。
     * 先剔除 0..100 之外的越界子分，再按规范组顺序取 argmin。
     */
    fun draggingDim(breakdown: Breakdown?): KpiContribution? =
        breakdown?.groups
            ?.flatMap { it.kpis }
            ?.filter { it.subScore.isFinite() && it.subScore in 0.0..100.0 }
            ?.minByOrNull { it.subScore }

    /**
     * 结果页「拖累维度」判词行文案。
     */
    fun draggingVerdictLine(breakdown: Breakdown?): String {
        val d = draggingDim(breakdown) ?: return "拖累维度 —（无可用子分）"
        return "拖累维度 ${d.label}（${d.id}）=${String.format(Locale.ROOT, "%.0f", d.subScore)} 分"
    }

    private data class Slot(val id: String, val label: String)

    private val STREAM = listOf(Slot("T1", "首字响应"), Slot("T3", "卡顿率"), Slot("T2", "Token 间隔"))
    private val UPLINK = listOf(Slot("U1", "上行"), Slot("U2", "工具循环"))
    private val BASELINE = listOf(Slot("N1", "RTT"), Slot("N2", "抖动"))
    private val CONTINUITY = listOf(Slot("C1", "会话中断"), Slot("C2", "切换恢复"))

    val GROUP_KPI_IDS_V01: List<Pair<String, List<String>>> = listOf(
        "流式体验" to STREAM.map { it.id },
        "上行突发" to UPLINK.map { it.id },
        "网络基线" to BASELINE.map { it.id },
    )

    fun fromReportJson(reportJson: String?): Breakdown? =
        parseNode(reportJson, nodeKey = "aqs", versionOverride = null)

    fun v02FromReportJson(reportJson: String?): Breakdown? =
        parseNode(reportJson, nodeKey = "aqs_v02", versionOverride = AqsScorer.AQS_VERSION_V02)

    private fun parseNode(reportJson: String?, nodeKey: String, versionOverride: String?): Breakdown? {
        if (reportJson.isNullOrBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(reportJson) as? JsonObject ?: return@runCatching null
            val topExplicit = root["aqs_version"]?.jsonPrimitive?.contentOrNull
            val aqs = (root["run"] as? JsonObject)?.get(nodeKey) as? JsonObject ?: return@runCatching null
            val nodeExplicit = aqs["aqs_version"]?.jsonPrimitive?.contentOrNull
            val explicitVersion = versionOverride ?: nodeExplicit ?: topExplicit
            parseAqs(aqs, explicitVersion)
        }.getOrNull()
    }

    private fun parseAqs(aqs: JsonObject, explicitVersion: String?): Breakdown? {
        val subObj = aqs["sub_scores"] as? JsonObject ?: return null
        val subs: Map<String, Double> = subObj.mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.doubleOrNull
                ?.takeIf { it.isFinite() && it in 0.0..100.0 }
                ?.let { k to it }
        }.toMap()
        if (subs.isEmpty()) return null

        val isV02 = when (explicitVersion) {
            AqsScorer.AQS_VERSION_V02 -> true
            null -> subs.containsKey("C1") || subs.containsKey("C2")
            else -> false
        }
        val resolvedVersion = explicitVersion ?: if (isV02) AqsScorer.AQS_VERSION_V02 else AqsScorer.AQS_VERSION
        val weights = if (isV02) AqsScorer.WEIGHTS_V02 else AqsScorer.WEIGHTS

        fun group(label: String, slots: List<Slot>): Group? {
            val kpis = slots.mapNotNull { slot ->
                val ss = subs[slot.id] ?: return@mapNotNull null
                val w = weights[slot.id] ?: return@mapNotNull null
                KpiContribution(id = slot.id, label = slot.label, weight = w, subScore = ss)
            }
            return if (kpis.isEmpty()) null else Group(label, kpis)
        }

        val groups = buildList {
            group("流式体验", STREAM)?.let { add(it) }
            group("上行突发", UPLINK)?.let { add(it) }
            group("网络基线", BASELINE)?.let { add(it) }
            if (isV02) group("连续性", CONTINUITY)?.let { add(it) }
        }
        if (groups.isEmpty()) return null

        return Breakdown(
            aqsVersion = resolvedVersion,
            score = aqs["score"]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() && it in 0.0..100.0 },
            lowConfidence = aqs["low_confidence"]?.jsonPrimitive?.booleanOrNull ?: false,
            vetoApplied = aqs["veto_applied"]?.jsonPrimitive?.booleanOrNull ?: false,
            groups = groups,
        )
    }
}
