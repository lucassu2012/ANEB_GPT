package com.aneb.probe.research

import kotlinx.serialization.json.JsonObject

/** Navigation only: a declared App is not a comparability group or a metric dimension. */
data class ResearchAppFilter(val sourceId: String, val appName: String?) {
    val label: String get() = appName?.let { "App：$it" } ?: "App 未提供/未知"
}

val ResearchDocument.appFilters: List<ResearchAppFilter> get() =
    if (isVideo) emptyList() else records.map { it.declaredAppName }.distinct().map { ResearchAppFilter(id, it) }

/** A null, stale or unavailable selection falls back to the whole source, in original order. */
fun ResearchDocument.recordsForApp(filter: ResearchAppFilter?): List<ResearchAttempt> =
    if (filter == null || filter !in appFilters) records
    else records.filter { it.declaredAppName == filter.appName }

private val ResearchAttempt.declaredAppName: String? get() =
    researchDeclaredValue((raw["app"] as? JsonObject)?.text("name"))
