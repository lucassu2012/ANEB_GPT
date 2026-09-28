package com.aneb.probe.research

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Navigation only: a declared App is not a comparability group or a metric dimension. */
data class ResearchAppFilter(val sourceId: String, val appName: String?) {
    val label: String get() = appName?.let { "App：$it" } ?: "App 未提供/未知"
}

enum class ResearchSourceKindSelection { ALL, SAMPLE, OBSERVED, UNKNOWN }

sealed interface ResearchConditionFilter {
    data object All : ResearchConditionFilter
    data object MissingOrUnrecognized : ResearchConditionFilter
    data class Literal(val value: String) : ResearchConditionFilter {
        init { require(value.isNotEmpty()) }
    }
}

/** A view-only scope; it never changes source records, hashes, or selected analyses. */
data class ResearchRecordScopeFilter(
    val sourceKind: ResearchSourceKindSelection = ResearchSourceKindSelection.ALL,
    val condition: ResearchConditionFilter = ResearchConditionFilter.All,
)

val ResearchDocument.appFilters: List<ResearchAppFilter> get() =
    if (isVideo) emptyList() else records.map { it.declaredAppName }.distinct().map { ResearchAppFilter(id, it) }

fun ResearchDocument.recordsForApp(filter: ResearchAppFilter?): List<ResearchAttempt> =
    if (filter == null || filter !in appFilters) records
    else records.filter { it.declaredAppName == filter.appName }

fun ResearchDocument.matchesSourceKind(selection: ResearchSourceKindSelection): Boolean = when (selection) {
    ResearchSourceKindSelection.ALL -> true
    ResearchSourceKindSelection.SAMPLE -> recordKind == "SAMPLE"
    ResearchSourceKindSelection.OBSERVED -> recordKind == "OBSERVED"
    ResearchSourceKindSelection.UNKNOWN -> recordKind != "SAMPLE" && recordKind != "OBSERVED"
}

/** App + source-kind + exact original-condition view; videos remain on their separate unfiltered path. */
fun ResearchDocument.recordsForScope(
    appFilter: ResearchAppFilter?,
    scope: ResearchRecordScopeFilter = ResearchRecordScopeFilter(),
): List<ResearchAttempt> {
    if (isVideo) return records
    if (!matchesSourceKind(scope.sourceKind)) return emptyList()
    val appRecords = recordsForApp(appFilter)
    return when (val condition = scope.condition) {
        ResearchConditionFilter.All -> appRecords
        ResearchConditionFilter.MissingOrUnrecognized -> appRecords.filter { it.originalConditionLiteral == null }
        is ResearchConditionFilter.Literal -> appRecords.filter { it.originalConditionLiteral == condition.value }
    }
}

private val ResearchAttempt.declaredAppName: String? get() =
    researchDeclaredValue((raw["app"] as? JsonObject)?.text("name"))

internal val ResearchAttempt.originalConditionLiteral: String?
    get() = (raw["condition"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

val ResearchAttempt.originalConditionDisplay: String
    get() = when (val condition = raw["condition"]) {
        null -> "未提供（原字段缺失）"
        JsonNull -> "null"
        is JsonPrimitive -> if (condition.isString) {
            if (condition.content.isEmpty()) "\"\"（空字符串）" else JsonPrimitive(condition.content).toString()
        } else condition.content
        else -> condition.toString()
    }
