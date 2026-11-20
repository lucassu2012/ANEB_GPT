package com.aneb.probe.ui

import java.util.Locale

/**
 * AI 场景适配建议（TK-1..TK-6）。
 *
 * 门限默认与 [com.aneb.probe.scoring.AqsScorer] 70 分良锚及 [com.aneb.probe.engine.KpiGrading] 对齐
 * （`N1=60ms`, `N2=30ms`, `U1=5Mbps`），并保留分负载档位（`MB`/`10MB`/`100MB`）。
 * 测量缺失（null）→ 判定 null（无法判定，R-10 不编造）；任一实测未达标 → `false`。
 * 纯 JVM 可单测。
 */
object AiScenarioAdvisor {

    data class SubScenarioSpec(val code: String, val title: String)

    data class FitnessThresholds(
        val n1GoodMs: Double = 60.0,
        val n2GoodMs: Double = 30.0,
        val u1GenericGoodMbps: Double = 5.0,
        val u1MbGoodMbps: Double = 8.0,
        val u1ImgGoodMbps: Double = 12.0,
        val u1VideoGoodMbps: Double = 15.0,
        val d1GenericGoodMbps: Double = 8.0,
        val d1ImgGoodMbps: Double = 12.0,
        val d1VideoGoodMbps: Double = 15.0,
        val subScenarios: List<SubScenarioSpec> = DEFAULT_SUB_SCENARIOS,
    ) {
        companion object {
            val DEFAULT_SUB_SCENARIOS = listOf(
                SubScenarioSpec("TK-1", "纯文本对话"),
                SubScenarioSpec("TK-2", "文档理解"),
                SubScenarioSpec("TK-3", "图片多模态"),
                SubScenarioSpec("TK-4", "视频多模态"),
                SubScenarioSpec("TK-5", "下行大对象"),
                SubScenarioSpec("TK-6", "编码 Agent"),
            )
            val DEFAULT = FitnessThresholds()
        }
    }

    /** 单条判定：[suitable] null=测量不足无法判定。 */
    data class Verdict(
        val code: String,
        val title: String,
        val suitable: Boolean?,
        val requirement: String,
    )

    private class Req(
        val label: String,
        measured: Double?,
        val threshold: Double,
        val higherBetter: Boolean,
        val unit: String,
    ) {
        private val validMeasured: Double? = measured
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { if (it == 0.0) 0.0 else it }

        val met: Boolean? = validMeasured?.let { if (higherBetter) it >= threshold else it <= threshold }

        fun text(): String {
            val cmp = if (higherBetter) "≥" else "≤"
            val m = validMeasured?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—"
            val mark = when (met) {
                true -> "✓"
                false -> "✗"
                null -> "?"
            }
            return "$label$cmp${trim(threshold)}$unit(实测$m$mark)"
        }

        private fun trim(v: Double) =
            if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.ROOT, "%.1f", v)
    }

    fun advise(
        downMbps: Double?,
        upMbps: Double?,
        rttMs: Double?,
        jitterMs: Double?,
        thresholds: FitnessThresholds = FitnessThresholds.DEFAULT,
    ): List<Verdict> {
        fun rtt() = Req("RTT", rttMs, thresholds.n1GoodMs, higherBetter = false, unit = "ms")
        fun jit() = Req("抖动", jitterMs, thresholds.n2GoodMs, higherBetter = false, unit = "ms")
        fun up(th: Double) = Req("上行", upMbps, th, higherBetter = true, unit = "Mbps")
        fun down(th: Double) = Req("下行", downMbps, th, higherBetter = true, unit = "Mbps")

        val reqsByCode: Map<String, List<Req>> = mapOf(
            "TK-1" to listOf(rtt(), jit()),
            "TK-2" to listOf(up(thresholds.u1MbGoodMbps), rtt()),
            "TK-3" to listOf(up(thresholds.u1ImgGoodMbps), down(thresholds.d1ImgGoodMbps)),
            "TK-4" to listOf(up(thresholds.u1VideoGoodMbps), down(thresholds.d1VideoGoodMbps)),
            "TK-5" to listOf(down(thresholds.d1ImgGoodMbps)),
            "TK-6" to listOf(rtt(), jit(), up(thresholds.u1GenericGoodMbps)),
        )

        return thresholds.subScenarios.mapNotNull { sc ->
            val reqs = reqsByCode[sc.code] ?: return@mapNotNull null
            val suitable: Boolean? = when {
                reqs.any { it.met == false } -> false
                reqs.all { it.met == true } -> true
                else -> null
            }
            Verdict(
                code = sc.code,
                title = sc.title,
                suitable = suitable,
                requirement = reqs.joinToString("、") { it.text() },
            )
        }
    }
}
