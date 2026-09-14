package com.aneb.probe.research

import kotlinx.serialization.json.JsonObject

/** A local navigation index, not a cross-batch comparison or a research conclusion. */
data class ResearchAppProfile(val appName: String?, val batches: List<ResearchAppProfileBatch>) {
    val label: String get() = appName?.let { "App：$it" } ?: "App 未提供/未知"
}

data class ResearchAppProfileBatch(val document: ResearchDocument, val filter: ResearchAppFilter) {
    val sourceId: String get() = document.id
    val records: List<ResearchAttempt> get() = document.recordsForApp(filter)
    val summaryLines: List<String> get() = listOf(
        "本 App：${records.size} 条记录 · 来源整批：${document.records.size} 条记录",
        "方法：${researchDeclaredValue(document.methodId) ?: "未提供/未知"}",
        document.sourceLabel,
        "源 SHA-256：${sourceId.take(12)}…",
    )
    val recordLines: List<String> get() = records.map { row ->
        val app = row.raw["app"] as? JsonObject
        val outcome = row.raw["outcome"] as? JsonObject
        "${row.attemptId} · 版本：${researchDeclaredValue(app?.text("version")) ?: "未提供/未知"}" +
            " · 模式：${researchDeclaredValue(app?.text("model_mode")) ?: "未提供/未知"}" +
            " · ${row.statusLabel} · 可见完成（原标注）：${outcome?.text("visible_completion") ?: "未提供/未知"}"
    }
}

/** Preserve the store's batch order and original row order; video/error rows stay separate. */
fun List<ResearchEntry>.appResearchProfiles(): List<ResearchAppProfile> =
    mapNotNull { it.document }.flatMap { document ->
        document.appFilters.map { ResearchAppProfileBatch(document, it) }
    }.groupBy { it.filter.appName }.map { (name, batches) -> ResearchAppProfile(name, batches) }
