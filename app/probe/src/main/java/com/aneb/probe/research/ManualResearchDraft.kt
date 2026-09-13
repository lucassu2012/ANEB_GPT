package com.aneb.probe.research

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Form state only. Export uses the existing alignment-1 record, never this draft format. */
@Serializable
data class ManualResearchAttempt(
    val executed: Boolean? = null,
    val status: String = "not_run",
    val visibleCompletion: String? = null,
    val instructionFollowing: String? = null,
    val reason: String = "",
    val evidenceReference: String = "",
    val evidenceHash: String = "",
    val evidenceExists: Boolean? = null,
    val clockSource: String = "",
    val videoId: String = "",
    val clockDomain: String = "",
    val clockMapping: String = "",
    val sendAnchor: String = "",
    val feedbackAnchor: String = "",
    val bodyAnchor: String = "",
    val completionBasis: String = "",
    val bodyContinuouslyVisible: Boolean? = null,
    val uiExitedNormally: Boolean? = null,
    val events: Map<String, ManualResearchRange> = emptyMap(),
    val intervals: List<ManualResearchInterval> = emptyList(),
)

@Serializable
data class ManualResearchRange(
    val low: String = "", val high: String = "",
    val frameLow: String = "", val frameHigh: String = "", val reason: String = "",
) {
    internal fun times(label: String): JsonElement {
        if (low.isBlank() && high.isBlank()) return JsonNull
        val a = low.toBigDecimalOrNull(); val b = high.toBigDecimalOrNull()
        ManualResearchDraft.checkInput(a != null && b != null && a.signum() >= 0 && a <= b,
            "$label：请填写非负毫秒下界和上界（下界≤上界），未知时两格都留空；不要填0代替未知。")
        return JsonArray(listOf(JsonPrimitive(a!!), JsonPrimitive(b!!)))
    }
    internal fun frames(label: String): JsonElement {
        if (frameLow.isBlank() && frameHigh.isBlank()) return JsonNull
        val a = frameLow.toLongOrNull(); val b = frameHigh.toLongOrNull()
        ManualResearchDraft.checkInput(a != null && b != null && a >= 0 && a <= b, "$label：帧范围需为非负整数下界≤上界；未知请都留空。")
        return JsonArray(listOf(JsonPrimitive(a!!), JsonPrimitive(b!!)))
    }
}

@Serializable
data class ManualResearchInterval(
    val kind: String = "gap",
    val start: ManualResearchRange = ManualResearchRange(),
    val end: ManualResearchRange = ManualResearchRange(),
    val resumed: Boolean? = null,
    val fullyVisible: Boolean? = null,
)

@Serializable
data class ManualResearchDraft(
    val batchId: String = UUID.randomUUID().toString(),
    val recordKind: String = "",
    val sourceConfirmed: Boolean = false,
    val appName: String = "",
    val appVersion: String = "",
    val modelMode: String = "",
    val device: String = "",
    val observedAt: String = "",
    val networkDescription: String = "",
    val unmodifiedConditionConfirmed: Boolean = false,
    val attempts: List<ManualResearchAttempt> = List(3) { ManualResearchAttempt() },
) {
    fun toRecordBytes(): ByteArray {
        checkInput(recordKind == "SAMPLE" || recordKind == "OBSERVED", "请选择来源：虚构 SAMPLE 或已有现场观察声明 OBSERVED。未知来源请保留草稿。")
        checkInput(appName.isNotBlank(), "请填写 App 名称。")
        checkInput(attempts.size == 3, "当前固定动作保留三个计划槽位，不删除失败或未执行项。")
        checkInput(!unmodifiedConditionConfirmed || networkDescription.isNotBlank(), "确认C0前请填写当时网络描述；未知条件不自动填C0。")
        if (recordKind == "OBSERVED") checkInput(sourceConfirmed, "请先确认来源声明；工具不会核验现场或媒体。")
        val rows = attempts.mapIndexed { index, attempt ->
            checkInput(attempt.reason.isNotBlank(), "第 ${index + 1} 次：请填写未执行或缺失原因。")
            if (recordKind == "OBSERVED") checkInput(attempt.evidenceReference.isNotBlank(), "第 ${index + 1} 次：OBSERVED 需要现场记录引用，未执行事实也需引用；不能把占位计划当实际观察。")
            checkInput(attempt.evidenceHash.isBlank() || attempt.evidenceHash.matches(Regex("[0-9a-fA-F]{64}")), "第 ${index + 1} 次：证据 SHA-256 应为64位十六进制；未取得请留空，不补造。")
            checkInput(recordKind != "SAMPLE" || (attempt.evidenceReference.isBlank() && attempt.evidenceHash.isBlank() && attempt.evidenceExists != true), "第 ${index + 1} 次：SAMPLE不填真实来源引用/哈希或声明证据存在；请把演示说明放入缺失说明。")
            checkInput(attempt.executed != null, "第 ${index + 1} 次：请明确是否实际发送；未知请留草稿。")
            checkInput(attempt.status in STATUSES && ((attempt.executed == false) == (attempt.status == "not_run")), "第 ${index + 1} 次：未发送应为not_run；已发送即使缺T0也不能改为not_run。")
            checkInput(if (attempt.executed == true) attempt.visibleCompletion in OBSERVATIONS && attempt.instructionFollowing in OBSERVATIONS
                else attempt.visibleCompletion == null && attempt.instructionFollowing == null,
                "第 ${index + 1} 次：请独立选择可见完成和指令满足；未发送时两项为不适用。")
            val hasMapping = listOf(attempt.clockSource, attempt.videoId, attempt.clockDomain, attempt.clockMapping).all { it.isNotBlank() }
            val eventRows = EVENT_NAMES.associateWith { name ->
                val value = attempt.events[name] ?: ManualResearchRange()
                val times = value.times("第 ${index + 1} 次 $name")
                val frames = value.frames("第 ${index + 1} 次 $name")
                checkInput(times == JsonNull || hasMapping, "第 ${index + 1} 次：毫秒值需要已有视频/时钟域/映射说明；无可靠映射请保留帧范围并留空毫秒。")
                if (times == JsonNull && frames == JsonNull) JsonNull else buildJsonObject {
                    put("time_ms", times); put("frame_range", frames); put("clock_domain_id", optional(attempt.clockDomain))
                }
            }
            val intervals = attempt.intervals.mapIndexed { intervalIndex, interval ->
                checkInput(interval.kind in listOf("gap", "update_gap", "unclosed_tail"), "请选择已有观察区间类型。")
                val start = interval.start.times("第 ${index + 1} 次区间 ${intervalIndex + 1} 起点")
                val end = interval.end.times("第 ${index + 1} 次区间 ${intervalIndex + 1} 终点")
                checkInput(start != JsonNull && end != JsonNull && hasMapping, "第 ${index + 1} 次区间 ${intervalIndex + 1}：端点或映射不完整，请留草稿或移除此未完成区间并保留说明，不编造0。")
                checkInput(!(interval.kind == "unclosed_tail" && interval.resumed == true), "未闭合尾段不能同时标为已恢复。")
                buildJsonObject {
                    put("kind", interval.kind); put("start_ms", start); put("end_ms", end)
                    put("resumed", interval.resumed?.let(::JsonPrimitive) ?: JsonNull)
                    put("fully_visible", interval.fullyVisible?.let(::JsonPrimitive) ?: JsonNull)
                }
            }
            checkInput(attempt.executed != false || (eventRows.values.all { it == JsonNull } && intervals.isEmpty()), "第 ${index + 1} 次：未发送槽位仍含事件/区间，请核对；不会静默删除已填值。")
            if (attempt.visibleCompletion == "yes") checkInput(
                attempt.completionBasis.isNotBlank() && attempt.bodyAnchor.isNotBlank() && attempt.bodyContinuouslyVisible == true &&
                    attempt.uiExitedNormally == true && eventRows["complete_confirm"] != JsonNull,
                "第 ${index + 1} 次：可见完成需要原观察依据、正文锚、连续可见/正常退出确认和确认事件；否则请选择不确定，不从最后正文自动推定。",
            )
            buildJsonObject {
                put("record_kind", recordKind); put("attempt_id", "manual-$batchId-${index + 1}")
                put("method_id", METHOD_ID); put("action_text", ACTION_TEXT)
                put("app", buildJsonObject { put("name", appName); put("version", optional(appVersion)); put("model_mode", optional(modelMode)) })
                put("condition", if (unmodifiedConditionConfirmed) JsonPrimitive("C0_existing_unmodified") else JsonNull)
                put("metadata", buildJsonObject { put("device", optional(device)); put("observed_at", optional(observedAt)); put("network_description", optional(networkDescription)) })
                put("clock", buildJsonObject { put("source", optional(attempt.clockSource)); put("unit", "ms"); put("video_id", optional(attempt.videoId)); put("mapping", optional(attempt.clockMapping)); put("domain_id", optional(attempt.clockDomain)) })
                put("anchors", buildJsonObject { put("send", optional(attempt.sendAnchor)); put("feedback", optional(attempt.feedbackAnchor)); put("body", optional(attempt.bodyAnchor)) })
                put("evidence", buildJsonObject { put("exists", if (recordKind == "SAMPLE") JsonPrimitive(false) else attempt.evidenceExists?.let(::JsonPrimitive) ?: JsonNull); put("local_ref", optional(attempt.evidenceReference)); put("sha256", optional(attempt.evidenceHash)) })
                put("events", JsonObject(eventRows))
                put("observed_intervals", JsonArray(intervals))
                put("missing_reasons", buildJsonObject {
                    put("all_timing", attempt.reason)
                    put("source", attempt.reason)
                    EVENT_NAMES.forEach { name ->
                        val specific = attempt.events[name]?.reason.orEmpty()
                        if (specific.isNotBlank()) put(name, specific)
                        else if (eventRows[name] == JsonNull || (eventRows[name] as? JsonObject)?.get("time_ms") == JsonNull) put(name, attempt.reason)
                    }
                    if (!unmodifiedConditionConfirmed) put("condition", "历史网络条件未确认；不自动填C0")
                    if (attempt.completionBasis.isBlank()) put("completion", attempt.reason)
                    if (attempt.clockSource.isBlank() || !hasMapping) put("clock", "映射信息不完整；不自动补时钟或时间")
                    if (device.isBlank()) put("metadata.device", "手工未提供")
                    if (observedAt.isBlank()) put("metadata.observed_at", "手工未提供")
                    if (networkDescription.isBlank()) put("metadata.network_description", "手工未提供")
                    if (attempt.sendAnchor.isBlank()) put("anchors.send", attempt.reason)
                    if (attempt.feedbackAnchor.isBlank()) put("anchors.feedback", attempt.reason)
                    if (attempt.bodyAnchor.isBlank()) put("anchors.body", attempt.reason)
                    if (attempt.evidenceHash.isBlank()) put("evidence.sha256", "手工未提供哈希；未核验，不补造")
                    if (appVersion.isBlank()) put("app.version", "手工未提供")
                    if (modelMode.isBlank()) put("app.model_mode", "手工未提供")
                })
                put("outcome", buildJsonObject { put("executed", attempt.executed!!); put("status", attempt.status); put("visible_completion", attempt.visibleCompletion?.let(::JsonPrimitive) ?: JsonNull); put("instruction_following", attempt.instructionFollowing?.let(::JsonPrimitive) ?: JsonNull); put("completion_basis", optional(attempt.completionBasis)) })
                put("completion_observation", buildJsonObject { put("body_continuously_visible", attempt.bodyContinuouslyVisible?.let(::JsonPrimitive) ?: JsonNull); put("ui_exited_generation_normally", attempt.uiExitedNormally?.let(::JsonPrimitive) ?: JsonNull) })
                put("summary_group", buildJsonObject { put("confirmed", false); put("id", JsonNull); put("reason", "手工记录尚未确认可比组，不自动合组") })
            }
        }
        return buildJsonObject {
            put("record_kind", recordKind); put("input_revision", "alignment-1")
            put("method_id", METHOD_ID); put("action_text", ACTION_TEXT); put("time_unit", "ms")
            put("records", JsonArray(rows))
        }.toString().toByteArray(Charsets.UTF_8).also { ResearchRecordStore.decode(it) }
    }

    fun toDraftState(): String = Json.encodeToString(this)

    companion object {
        const val METHOD_ID = "R1-visible-text-internal-v0.1"
        const val ACTION_TEXT = "请只用中文回答：用恰好10个编号要点解释“为什么移动网络时延会影响AI助手的交互体验”。每个要点写2句话；不要使用表格，不要调用外部工具，不要向我提问。"
        val EVENT_NAMES = listOf("send", "first_feedback", "first_content", "last_content", "complete_confirm", "observation_end")
        val STATUSES = listOf("completed", "failed", "cancelled", "incomplete", "not_run")
        val OBSERVATIONS = listOf("yes", "no", "uncertain")
        fun fromDraftState(state: String): ManualResearchDraft = Json.decodeFromString(state)
        internal fun optional(value: String): JsonElement = if (value.isBlank()) JsonNull else JsonPrimitive(value)
        internal fun checkInput(valid: Boolean, message: String) { if (!valid) throw ResearchImportException(message) }
    }
}
