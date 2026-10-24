package com.aneb.probe.research.business

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

data class VideoUiQuery(val selector: String, val kind: String, val value: String)

/** Only the source-bound public queries for this one video UI; never substitutes for classify(). */
object VideoUiQueries {
    const val LEGACY_METHOD = "legacy-public-tree-1"
    const val TARGETED_METHOD = "targeted-public-selectors-1"
    const val PREPARATION_METHOD = "friends-invite-public-source-1"

    /** Fixed recall from already observed invitation text, never semantic admission or an action selector. */
    fun preparationQueries(requested: String, executionMode: String): List<VideoUiQuery> {
        require(requested == PREPARATION_METHOD && executionMode == "SOURCE_PREPARATION") {
            "VIDEO_SOURCE_QUERY_SCOPE_REQUIRED"
        }
        return listOf(
            VideoUiQuery("source.friends_invite.title", "TEXT", "发现抖音朋友"),
            VideoUiQuery("source.friends_invite.body", "TEXT", "登录后"),
            VideoUiQuery("source.friends_invite.decline", "TEXT", "拒绝"),
            VideoUiQuery("source.friends_invite.continue", "TEXT", "继续"),
        )
    }

    fun selectMethod(requested: String?, source: String?): String {
        val selected = requested ?: LEGACY_METHOD
        require(selected in setOf(LEGACY_METHOD, TARGETED_METHOD)) { "VIDEO_UI_METHOD_UNSUPPORTED" }
        val declared = source?.let { Json.parseToJsonElement(it).jsonObject.string("observation_method") }
            ?.ifBlank { LEGACY_METHOD } ?: LEGACY_METHOD
        require(selected == declared) { "VIDEO_UI_METHOD_BINDING_MISMATCH" }
        if (selected == TARGETED_METHOD) compile(requireNotNull(source))
        return selected
    }

    fun compile(source: String): List<VideoUiQuery> {
        VideoUiAdmission.compile(source)
        val binding = Json.parseToJsonElement(source).jsonObject
        require(binding.string("observation_method") == TARGETED_METHOD) { "VIDEO_TARGETED_METHOD_BINDING_REQUIRED" }
        val selectors = mutableListOf("feed_anchor" to binding.getValue("feed_anchor").jsonObject,
            "gesture_viewport" to binding.getValue("gesture_viewport").jsonObject)
        binding["loading_marker"]?.let { selectors += "loading_marker" to it.jsonObject }
        binding.getValue("blockers").jsonObject.toSortedMap().forEach { (kind, entries) ->
            entries.jsonArray.forEachIndexed { index, entry -> selectors += "blockers.$kind.$index" to entry.jsonObject }
        }
        require(selectors.size <= 32) { "VIDEO_PUBLIC_QUERY_PLAN_LIMIT" }
        return selectors.map { (name, selector) ->
            val id = selector.string("resource_id")
            val text = selector.string("query_text").ifBlank { selector.string("text") }
            require(id.isNotBlank() || text.isNotBlank()) { "VIDEO_PUBLIC_QUERY_SELECTOR_UNSUPPORTED" }
            if (id.isBlank()) require(listOf(selector.string("text"), selector.string("description")).any { text in it }) {
                "VIDEO_PUBLIC_QUERY_TEXT_NOT_SOURCE_BOUND"
            }
            if (id.isNotBlank()) VideoUiQuery(name, "VIEW_ID", id) else VideoUiQuery(name, "TEXT", text)
        }
    }

    private fun JsonObject.string(key: String): String = (get(key) as? JsonPrimitive)
        ?.takeIf { it.isString }?.content.orEmpty()
}
