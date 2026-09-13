package com.aneb.probe.research

import kotlinx.serialization.json.JsonObject

/** Batch identification from declared metadata only, never a grouping or measurement decision. */
val ResearchDocument.batchSummaryLines: List<String> get() {
    val apps = if (isVideo) listOf(root.text("app"))
        else records.map { (it.raw["app"] as? JsonObject)?.text("name") }
    val versions = if (isVideo) listOf(root.text("version"))
        else records.map { (it.raw["app"] as? JsonObject)?.text("version") }
    return listOf(
        (if (isVideo) "视频 · " else "") + declaredSummary(apps, "App"),
        declaredSummary(versions, "版本"),
        "$sourceLabel · ${records.size} 条记录 · ${id.take(12)}…",
    )
}

/** Absence wording only. Do not normalize, infer or write back a supplied value. */
internal fun researchDeclaredValue(value: String?): String? =
    value?.takeUnless { it.isBlank() || it.equals("UNKNOWN", ignoreCase = true) }

private fun declaredSummary(values: List<String?>, label: String): String {
    val supplied = values.map(::researchDeclaredValue)
    val distinct = supplied.filterNotNull().distinct()
    return when (distinct.size) {
        0 -> "$label 未提供（见逐条）"
        1 -> (if (label == "App") distinct.single() else "$label：${distinct.single()}") +
            if (supplied.any { it == null }) "（部分记录未提供，见逐条）" else ""
        else -> if (label == "App") "多个 App（见逐条）" else "多个版本（见逐条）"
    }
}
