package com.aneb.probe.research

import kotlinx.serialization.json.*

/** Chinese labels over imported values only: no duration, count, or aggregate is calculated here. */
val ResearchAnalysis.summaryLines: List<String> get() {
    val counts = root["counts"] as? JsonObject
    return listOf(
        "导入的派生分析 · ${root.text("record_kind")} · 未独立核验数值或媒体",
        "计划：${number(counts?.get("planned"))}；已尝试：${number(counts?.get("attempted"))}；未执行：${number(counts?.get("not_run"))}；可见完成已确认：${number(counts?.get("visible_completed_confirmed"))}",
        "以下时长为秒区间，不是精确时间点或区间中点；不用于网络归因。",
    )
}

fun ResearchAnalysis.attemptLines(attemptId: String): List<String> {
    val row = records[attemptId] ?: return listOf("此尝试尚未关联分析（非 0）。")
    return buildList {
        metricNames.forEach { (key, label) ->
            val metric = row[key] as? JsonObject
            val value = when (val status = metric?.text("status")) {
                "interval" -> seconds(metric["interval_s"])
                "uncertain" -> "不确定（uncertain）"
                "NA" -> "NA（未取得可用时长）"
                else -> "未知分析状态：${status ?: "NA"}"
            }
            add("$label：$value${reason(metric?.get("reason"))}")
        }
        val stalls = row["stalls"] as? JsonObject
        add("停顿观察：${when (stalls?.text("status")) {
            "partial" -> "部分观察（partial）"
            "not_computed" -> "未计算（not_computed）"
            else -> "未知"
        }}")
        (stalls?.get("intervals") as? JsonArray)?.forEach { element ->
            val interval = element as? JsonObject
            val classification = interval?.text("classification")
            val label = when (classification) {
                "confirmed" -> "确认观察到的停顿"
                "right_censored" -> "右删失尾段"
                "observation_gap" -> "观察缺口"
                "below_threshold" -> "低于分析阈值的区间"
                "uncertain" -> "不确定的区间"
                "NA" -> "NA 区间"
                else -> "未知分类（${classification ?: "NA"}）"
            }
            add("$label：${seconds(interval?.get("interval_s"))}${reason(interval?.get("reason"))}" +
                if (classification == "right_censored") "；仅为观察到的未闭合尾段，不是完整停顿时长。" else "")
        }
        add("已确认观察次数：${number(stalls?.get("confirmed_observed_count"))}；0 不代表没有停顿。")
        add("停顿总次数：${number(stalls?.get("total_count"), "未知")}；最大停顿区间：${seconds(stalls?.get("max_interval_s"), "未知")}。未有完整覆盖依据时不补 0。")
    }
}

val ResearchAnalysis.groupLines: List<String> get() {
    val groups = root["groups"] as? JsonArray
    if (groups.isNullOrEmpty()) return listOf("无可比较分组；不自动合组或生成汇总。")
    return buildList {
        add("分析提供的可比较分组（本机未重新判定可比性）")
        groups.forEach { element ->
            val group = element as? JsonObject
            add("组：${group?.text("group_id") ?: "UNKNOWN"}；可比性键：${group?.get("comparability_key") ?: JsonNull}")
            val metrics = group?.get("metrics") as? JsonObject
            metricNames.forEach { (key, label) ->
                val metric = metrics?.get(key) as? JsonObject
                add("$label · 有效区间 valid_n：${number(metric?.get("valid_n"))}；点子集 point_n：${number(metric?.get("point_n"))}；NA：${number(metric?.get("na_n"))}；不确定：${number(metric?.get("uncertain_n"))}")
                add("点子集中位数：${number(metric?.get("point_median_s"))} 秒；点子集范围：${seconds(metric?.get("point_range_s"))}；仅描述等端点子集，不是整组中位数。")
            }
        }
    }
}

private val metricNames = listOf("ttfr" to "首个反馈 TTFR", "ttfc" to "首个内容 TTFC", "completion" to "完成 Completion")

private fun number(value: JsonElement?, missing: String = "NA"): String =
    (value as? JsonPrimitive)?.takeIf { !it.isString && it.content.toBigDecimalOrNull() != null }?.content ?: missing

private fun seconds(value: JsonElement?, missing: String = "NA（未提供区间）"): String {
    val pair = value as? JsonArray ?: return missing
    if (pair.size != 2 || pair.any { number(it) == "NA" }) return missing
    return "[${number(pair[0])}, ${number(pair[1])}] 秒"
}

private fun reason(value: JsonElement?): String {
    val code = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return ""
    val label = when (code) {
        "not_executed" -> "未执行"
        "completion_unconfirmed" -> "可见完成未确认"
        "clock_unavailable" -> "时钟依据不可用"
        "event_unavailable" -> "缺少事件"
        "invalid_interval" -> "原始区间无效"
        "clock_domain_mismatch" -> "时钟域不一致"
        "event_order_uncertain" -> "事件先后不确定"
        else -> "分析提供的原因"
    }
    return "；$label（$code）"
}
