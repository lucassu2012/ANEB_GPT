package com.aneb.probe.research

import kotlinx.serialization.json.*

/** Display producer values and unknown reasons as text; no interval/count formulas live here. */
internal val ResearchAnalysis.videoSummaryLines: List<String> get() {
    val counts = root["counts"] as? JsonObject
    return listOf(
        "视频分析 · ${root.text("record_kind")} · 来源声明及数值未独立核验",
        "计划：${videoNumber(counts?.get("planned"))}；已执行：${videoNumber(counts?.get("executed"))}；未执行：${videoNumber(counts?.get("not_run"))}",
        "未执行不计播放失败；执行次数不是成功率。",
        "30秒观察窗从可见打开 V0 开始，包括首次等待；不是播放完成或有效播放时长。",
        "仅显示导入的秒区间，不取中点、不补 0；未打开媒体或核验时钟。",
    )
}

internal fun ResearchAnalysis.videoAttemptLines(slot: String): List<String> {
    val row = records[slot] ?: return listOf("此槽尚未关联视频分析（非 0）。")
    val wait = row["first_frame_wait"] as? JsonObject
    val window = row["window"] as? JsonObject
    val first = when (wait?.text("status")) {
        "valid" -> videoSeconds(wait["interval_s"])
        "NA" -> "NA（不可计算，不补 0）"
        "uncertain" -> "不确定（uncertain；不生成点值）"
        else -> "未知分析状态（${wait?.text("status") ?: "NA"}）；不推算数值"
    }
    val windowState = when (window?.text("status")) {
        "observed_complete" -> "据标注已观察完整（不是软件验证连续播放）"
        "observed_partial" -> "仅部分观察"
        "not_started" -> "窗口未开始"
        "uncertain" -> "不确定（uncertain）"
        "NA" -> "NA（缺少可用窗口依据）"
        else -> "未知窗口状态（${window?.text("status") ?: "NA"}）"
    }
    return listOf(
        "可见打开至首画面：$first${videoReason(wait?.get("reason"))}",
        "30秒观察窗：$windowState${videoReason(window?.get("reason"))}",
        "计划终点 PTS：${videoSeconds(window?.get("planned_end_pts_s"))}（非实测终点）",
        "原始说明：${row.text("reason") ?: "未提供"}",
        "缓冲 / 暂停次数与时长：未计算（null，不是 0）。",
    )
}

private fun videoNumber(value: JsonElement?): String = (value as? JsonPrimitive)
    ?.takeIf { !it.isString && it.content.toBigDecimalOrNull() != null }?.content ?: "NA"

private fun videoSeconds(value: JsonElement?): String {
    val pair = value as? JsonArray ?: return "NA（未提供区间）"
    if (pair.size != 2 || pair.any { videoNumber(it) == "NA" }) return "NA（未提供区间）"
    return "[${videoNumber(pair[0])}, ${videoNumber(pair[1])}] 秒"
}

private fun videoReason(value: JsonElement?): String {
    val code = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return ""
    val label = when (code) {
        "NOT_RUN" -> "未执行，不计播放失败"
        "V0_MISSING" -> "缺少可见打开边界"
        "VF_MISSING" -> "缺少目标首画面；不以30秒替代"
        "V0_INVALID", "VF_INVALID" -> "原始边界或时钟无效"
        "CLOCK_MISMATCH", "WINDOW_CLOCK_MISMATCH" -> "时钟声明不一致"
        "ORDER_OVERLAP", "WINDOW_ORDER_OVERLAP" -> "先后顺序不确定"
        "ORDER_REVERSED", "WINDOW_ORDER_REVERSED" -> "事件顺序倒置"
        "TARGET_PLAYBACK_UNCONFIRMED" -> "目标播放尚未确认"
        "VF_AFTER_PLANNED_WINDOW" -> "首画面晚于计划30秒截止"
        "VF_AFTER_OBSERVATION_END" -> "首画面晚于实际观察截止"
        "VF_PLANNED_BOUNDARY_UNCERTAIN" -> "首画面与计划截止边界重叠"
        "VF_OBSERVATION_BOUNDARY_UNCERTAIN" -> "首画面与实际截止边界重叠"
        "WINDOW_END_MISSING", "WINDOW_END_INVALID" -> "缺少可用观察终点"
        "WINDOW_INCOMPLETE" -> "未覆盖完整窗口"
        "WINDOW_CLAIM_CONFLICT" -> "完整声明与终点冲突"
        "WINDOW_BOUNDARY_UNCERTAIN", "WINDOW_COVERAGE_UNCERTAIN" -> "窗口覆盖不确定"
        else -> "分析提供的原因（未解释；不推算数值）"
    }
    return "；$label：$code"
}
