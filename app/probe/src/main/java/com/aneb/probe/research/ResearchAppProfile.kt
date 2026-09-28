package com.aneb.probe.research

import kotlinx.serialization.json.JsonObject

/** A local navigation index, not a cross-batch comparison or a research conclusion. */
data class ResearchAppProfile(val appName: String?, val batches: List<ResearchAppProfileBatch>) {
    val label: String get() = appName?.let { "App：$it" } ?: "App 未提供/未知"
}

data class ResearchAppProfileBatch(
    val document: ResearchDocument,
    val filter: ResearchAppFilter,
    val scope: ResearchRecordScopeFilter = ResearchRecordScopeFilter(),
) {
    val sourceId: String get() = document.id
    val records: List<ResearchAttempt> get() = document.recordsForScope(filter, scope)
    val originalAppRecords: List<ResearchAttempt> get() = document.recordsForApp(filter)
    val summaryLines: List<String> get() = listOf(
        "筛选显示：${records.size} 条 / 原批次：${document.records.size} 条（仅显示数量，不是成功率分母）",
        "本 App 原记录：${originalAppRecords.size} 条",
        "方法：${researchDeclaredValue(document.methodId) ?: "未提供/未知"}",
        document.sourceLabel,
        "源 SHA-256：$sourceId",
    )
    val recordLines: List<String> get() = records.map { row ->
        val app = row.raw["app"] as? JsonObject
        val outcome = row.raw["outcome"] as? JsonObject
        "${row.attemptId} · 原始 condition：${row.originalConditionDisplay} · 版本：${researchDeclaredValue(app?.text("version")) ?: "未提供/未知"}" +
            " · 模式：${researchDeclaredValue(app?.text("model_mode")) ?: "未提供/未知"}" +
            " · ${row.statusLabel} · 可见完成（原标注）：${outcome?.text("visible_completion") ?: "未提供/未知"}"
    }
}

/** Preserve source/App/row order; records with no matching scope stay accessible via All or Unknown. */
fun List<ResearchEntry>.appResearchProfiles(
    scope: ResearchRecordScopeFilter = ResearchRecordScopeFilter(),
): List<ResearchAppProfile> =
    mapNotNull { it.document }.flatMap { document ->
        if (document.isVideo) emptyList()
        else document.appFilters.map { ResearchAppProfileBatch(document, it, scope) }
    }.groupBy { it.filter.appName }.map { (name, batches) -> ResearchAppProfile(name, batches) }
