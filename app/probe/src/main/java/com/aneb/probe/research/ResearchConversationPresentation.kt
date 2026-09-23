package com.aneb.probe.research

import kotlinx.serialization.json.*

/** Read-only navigation over optional input annotations, not conversation reconstruction. */
data class ResearchConversation(
    val sourceId: String,
    val appName: String?,
    val conversationId: String?,
    val turns: List<ResearchConversationTurn>,
) {
    val label: String get() = conversationId?.let { "会话：$it" } ?: "未分会话"
}

data class ResearchConversationTurn(val sourceId: String, val attempt: ResearchAttempt, val turnIndex: Int?) {
    val label: String get() = turnIndex?.let { "第 $it 轮（原标注）" } ?: "轮次未提供或无效 · 原输入顺序"
    val prompt: String get() = attempt.raw.text("action_text")?.takeIf { it.isNotBlank() } ?: "逐轮提示词未提供"
    val contextLines: List<String> get() {
        val context = attempt.raw["context_observation"] as? JsonObject
        return listOf(
            "会话标注：${attempt.raw.annotationDisplay("conversation_id")}",
            "依赖记录：${attempt.raw.annotationDisplay("depends_on_attempt_id")}",
            if (attempt.raw.annotationConflict("turn_index")) "轮次标注：未知（顶层与上下文标注冲突）" else label,
            "仅显示本文件原标注；不拼接跨文件会话。",
            "动作及顺序保留（原观察）：${observation(context?.get("visible_actions_and_order_retained"))}",
            "纳入上下文观察分母（原标注）：${observation(context?.get("eligible_context_denominator"))}",
        )
    }

    fun analysisLines(analysis: ResearchAnalysis?): List<String> =
        analysis?.takeIf { it.sourceId == sourceId }?.attemptLines(attempt.attemptId)
            ?: listOf("尚未选择本批分析（非 0）；可在上方选择已有副本。")
}

/** Current reader scope only; analysis values remain the imported presentation's responsibility. */
data class ResearchReaderSelection(
    val scopeLabel: String,
    val turns: List<ResearchConversationTurn>,
    val records: List<ResearchAttempt>,
    val analysis: ResearchAnalysis?,
)

fun ResearchDocument.readerSelection(
    app: ResearchAppFilter?,
    conversation: ResearchConversation?,
    analysis: ResearchAnalysis?,
): ResearchReaderSelection {
    val currentApp = app?.takeIf { it in appFilters }
    val groups = conversationsForApp(currentApp)
    val currentConversation = conversation?.takeIf { it in groups }
    val turns = groups.turnsForSelection(currentConversation)
    return ResearchReaderSelection(
        if (isVideo) "当前范围：本批全部视频记录"
        else "当前范围：${currentApp?.label ?: "全部 App"} · ${currentConversation?.label ?: "全部会话 / 未分会话"}",
        turns,
        if (isVideo) records else turns.map { it.attempt },
        analysis?.takeIf { it.sourceId == id },
    )
}

/** A stale selection from another App/batch never supplies records to the current reader. */
fun List<ResearchConversation>.turnsForSelection(selection: ResearchConversation?): List<ResearchConversationTurn> =
    firstOrNull { it == selection }?.turns ?: flatMap { it.turns }

fun ResearchDocument.conversationsForApp(filter: ResearchAppFilter?): List<ResearchConversation> {
    if (isVideo) return emptyList()
    return recordsForApp(filter).map { attempt ->
        val index = (attempt.raw.annotation("turn_index") as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()?.takeIf { it > 0 }
        val id = attempt.raw.annotationText("conversation_id")
        val valid = id != null && index != null
        val app = researchDeclaredValue((attempt.raw["app"] as? JsonObject)?.text("name"))
        (app to if (valid) id else null) to ResearchConversationTurn(this.id, attempt, if (valid) index else null)
    }.groupBy({ it.first }, { it.second }).map { (key, turns) ->
        ResearchConversation(id, key.first, key.second, if (key.second == null) turns else turns.sortedBy { it.turnIndex })
    }
}

/** Optional annotations only, never inferred from record IDs or another source. */
private fun JsonObject.annotation(key: String): JsonElement? {
    if (annotationConflict(key)) return null
    val nested = get("context_observation") as? JsonObject
    return if (nested?.containsKey(key) == true) nested[key] else get(key)
}

private fun JsonObject.annotationConflict(key: String): Boolean {
    val nested = get("context_observation") as? JsonObject
    return containsKey(key) && nested?.containsKey(key) == true && get(key) != nested[key]
}

private fun JsonObject.annotationDisplay(key: String): String =
    if (annotationConflict(key)) "未知（顶层与上下文标注冲突）"
    else annotationText(key) ?: "未知（未提供、无效或不适用）"

private fun JsonObject.annotationText(key: String): String? =
    (annotation(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() && it != "UNKNOWN" }

private fun observation(value: JsonElement?): String = when (value) {
    null, JsonNull -> "未记录 / 不适用"
    is JsonPrimitive -> when (value.content) {
        "yes", "true" -> "是"
        "no", "false" -> "否"
        "uncertain" -> "不确定"
        else -> value.content
    }
    else -> value.toString()
}
