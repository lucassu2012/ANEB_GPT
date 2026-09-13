package com.aneb.probe.research

import kotlinx.serialization.json.*

/** Thin local format adapter. Does not read media, derive times, or convert video to alignment-1. */
internal object ResearchVideoFormat {
    const val BLOCKED_KIND = "video_observation_preflight_blocked"

    fun matches(root: JsonObject): Boolean = root.text("format") == "research-video-1" ||
        root.text("record_kind") == BLOCKED_KIND

    fun readAttempts(root: JsonObject): List<ResearchAttempt> {
        try {
            require(root.text("record_kind") in setOf("SAMPLE", "OBSERVED", BLOCKED_KIND))
            require(!root.text("app").isNullOrBlank())
            val rows = root["attempts"] as? JsonArray ?: error("attempts")
            require(rows.isNotEmpty())
            val seen = mutableSetOf<String>()
            val attempts = rows.map { element ->
                val row = element as? JsonObject ?: error("attempt")
                val slot = row.text("slot")
                require(!slot.isNullOrBlank() && seen.add(slot))
                require(row.text("status") in setOf("EXECUTED", "NOT_RUN"))
                if (row.text("status") == "NOT_RUN") {
                    require(listOf("V0", "VF", "window_end").all { row[it] == JsonNull })
                    require(row.text("window_complete") == "not_started")
                    require(row.text("visible_target_playback") == "not_observed")
                } else {
                    require(row.text("window_complete") in setOf("yes", "no", "uncertain"))
                    require(row.text("visible_target_playback") in setOf("yes", "no", "uncertain"))
                }
                ResearchAttempt(row, isVideo = true)
            }
            if (root.text("record_kind") == BLOCKED_KIND) {
                require(attempts.all { it.status == "NOT_RUN" })
                require(root["actual_measured_opens"] == JsonPrimitive(0))
                require(root["planned_slots"] == JsonPrimitive(attempts.size) && root["not_run"] == JsonPrimitive(attempts.size))
            }
            return attempts
        } catch (_: Exception) {
            throw ResearchImportException("无法导入视频记录：请核对 research-video-1、来源类型、App、唯一 slot 与执行状态。NOT_RUN 必须无时刻且窗口未开始；整份未保存。")
        }
    }

    fun validateAnalysis(source: ResearchDocument, root: JsonObject) {
        val context = root["input_record"] as? JsonObject ?: error("input_record")
        require(matches(context) && context.text("record_kind") == source.root.text("record_kind"))
        require(context.text("app") == source.root.text("app"))
        val originals = source.records.associateBy { it.attemptId }
        require(readAttempts(context).associate { it.attemptId to it.status } == originals.mapValues { it.value.status })
        val rows = root["attempts"] as? JsonArray ?: error("attempts")
        val seen = mutableSetOf<String>()
        rows.forEach { element ->
            val row = element as? JsonObject ?: error("attempt")
            val slot = row.text("slot") ?: error("slot")
            require(seen.add(slot) && slot in originals)
            require(row.text("status") == originals.getValue(slot).status)
            require(row["first_frame_wait"] is JsonObject && row["window"] is JsonObject)
        }
        require(seen == originals.keys)
        require(root["counts"] is JsonObject)
    }
}
