package com.aneb.probe.research

import java.math.BigDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A separate imported review copy, never an Android-computed result or legacy video annotation. */
internal object BusinessVideoReviewFormat {
    const val FORMAT = "business-video-review-1"
    val metricKeys = setOf("visible_pointer_to_target_wait", "visible_switch_wait")
    private val endpointBKinds = setOf(
        "EXACT_ADJACENT_PAIR",
        "CONFIRMED_POST_SETTLE_UPPER_BOUND",
        "UNAVAILABLE",
    )
    private val targetEvaluations = setOf("MET", "NOT_MET", "UNCERTAIN", "NA")
    private val sha256Pattern = Regex("[0-9a-f]{64}")

    fun validate(source: ResearchDocument, root: JsonObject) {
        require(source.isBusinessVideo && root.text("format") == FORMAT) { "业务视频复核版本不匹配" }
        require(root["input_record"] == source.root) { "业务视频复核携带的完整原文不匹配" }
        require(!root.text("method_id").isNullOrBlank()) { "缺少复核方法声明" }
        val rows = root["records"] as? JsonArray ?: error("缺少复核记录")
        require(rows.size == 1) { "业务视频复核必须对应同一次运行" }
        val row = rows.single() as? JsonObject ?: error("复核记录需要对象")
        require(row.text("attempt_id") == source.records.single().attemptId) { "复核运行身份不匹配" }
        val qualification = row.text("qualification")
        require(qualification in setOf("NOT_QUALIFIED", "QUALIFIED")) { "复核资格声明无效" }
        if (qualification == "QUALIFIED") {
            require(source.root.text("state") == "CAPTURED_AWAITING_REVIEW" &&
                source.root.text("category") == "OBSERVATION_CAPTURED" &&
                source.root["ownedCleanup"] == JsonPrimitive(true)) {
                "复核声明不能把停止或清理未确认的原始运行改为合格"
            }
        }
        val metrics = row["metrics"] as? JsonObject ?: error("缺少新方法指标")
        require(metrics.keys == metricKeys) { "仅支持本版本的两项新方法指标，不转换旧指标" }
        metrics.values.forEach { element ->
            val metric = element as? JsonObject ?: error("指标需要对象")
            require(!metric.text("reason").isNullOrBlank()) { "缺少指标依据或NA原因" }
            val status = metric.text("status")
            var intervalBounds: Pair<BigDecimal, BigDecimal>? = null
            when (status) {
                "NA", "uncertain" -> require(metric["interval_s"] == JsonNull) { "缺失或不确定值不能携带伪造区间" }
                "interval" -> {
                    val interval = metric["interval_s"] as? JsonArray ?: error("缺少秒区间")
                    require(interval.size == 2)
                    val values = interval.map { parseBoundedNonNegativeDecimal(it) }
                    require(values[0] <= values[1]) { "区间上下界反序" }
                    intervalBounds = values[0] to values[1]
                }
                else -> error("不支持的指标状态")
            }
            val bKindElement = metric["endpoint_b_kind"]
            if (bKindElement != null && bKindElement != JsonNull) {
                val bKind = metric.text("endpoint_b_kind")
                require(bKind in endpointBKinds) { "不支持的端点B类型声明" }
                if (bKind == "UNAVAILABLE") {
                    require(status in setOf("NA", "uncertain")) { "端点B不可得时不能声明有效数值区间" }
                } else {
                    require(status in setOf("interval", "uncertain")) { "NA状态不能声明已观测端点B" }
                }
            }
            val targetElement = metric["target_s"]
            val targetSeconds = if (targetElement != null && targetElement != JsonNull) {
                parseBoundedNonNegativeDecimal(targetElement).also { require(it.signum() > 0) { "目标阈值需大于0" } }
            } else null
            val evalElement = metric["target_evaluation"]
            if (evalElement != null && evalElement != JsonNull) {
                val eval = metric.text("target_evaluation")
                require(eval in targetEvaluations) { "不支持的目标判定状态" }
                when (status) {
                    "NA" -> require(eval == "NA") { "NA指标的目标判定必须为NA" }
                    "uncertain" -> require(eval == "UNCERTAIN") { "不确定指标的目标判定必须为UNCERTAIN" }
                    "interval" -> {
                        val target = requireNotNull(targetSeconds) { "区间目标判定必须同时声明target_s" }
                        val (lower, upper) = checkNotNull(intervalBounds)
                        val expectedEval = when {
                            upper <= target -> "MET"
                            lower > target -> "NOT_MET"
                            else -> "UNCERTAIN"
                        }
                        require(eval == expectedEval) { "目标判定与区间上下界不一致" }
                    }
                }
            }
        }
        val netReviewElement = row["network_condition_review"]
        if (netReviewElement != null && netReviewElement != JsonNull) {
            val netReview = netReviewElement as? JsonObject ?: error("网络条件复核声明需要对象")
            require(!netReview.text("topology_id").isNullOrBlank()) { "缺少受控网络拓扑标识" }
            require(netReview.text("mode") in setOf("inspect", "limit")) { "不支持的受控网络模式" }
            require(netReview.text("host_request_sha256")?.matches(sha256Pattern) == true) { "缺少宿主请求SHA-256绑定" }
            require(!netReview.text("summary").isNullOrBlank()) { "缺少受控网络条件摘要" }
        }
        require(row.text("stall_status") == "not_computed") { "本版本不计算卡顿" }
        listOf("observations", "limitations").forEach { key ->
            val lines = row[key] as? JsonArray ?: error("缺少复核观察或限制")
            require(lines.all { it is JsonPrimitive && it.isString && it.content.isNotBlank() })
        }
        val references = row["evidence_references"] as? JsonArray ?: error("缺少来源引用列表")
        references.forEach { element ->
            val reference = element as? JsonObject ?: error("来源引用需要对象")
            require(!reference.text("reference").isNullOrBlank() &&
                reference.text("sha256")?.matches(sha256Pattern) == true)
        }
    }

    private fun parseBoundedNonNegativeDecimal(value: JsonElement): BigDecimal {
        val token = value as? JsonPrimitive ?: error("区间需要数字")
        require(!token.isString && token.content.length <= 1024)
        val number = token.content.toBigDecimalOrNull() ?: error("区间需要有限数字")
        // Local numeric parsing budget only, not an experience threshold.
        require(number.scale() in -1000..1000 && number.precision() <= 1000 && number.signum() >= 0)
        return number
    }
}
