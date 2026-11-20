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
    const val TARGETED_MODAL_AWARE_METHOD = "targeted-public-selectors-2"
    const val PREPARATION_METHOD = "friends-invite-public-source-1"

    private val targetedMethods = setOf(TARGETED_METHOD, TARGETED_MODAL_AWARE_METHOD)

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
        require(selected in setOf(LEGACY_METHOD) + targetedMethods) { "VIDEO_UI_METHOD_UNSUPPORTED" }
        val declared = source?.let { Json.parseToJsonElement(it).jsonObject.string("observation_method") }
            ?.ifBlank { LEGACY_METHOD } ?: LEGACY_METHOD
        require(selected == declared) { "VIDEO_UI_METHOD_BINDING_MISMATCH" }
        if (selected in targetedMethods) compile(requireNotNull(source))
        return selected
    }

    fun compile(source: String): List<VideoUiQuery> {
        VideoUiAdmission.compile(source)
        val binding = Json.parseToJsonElement(source).jsonObject
        val method = binding.string("observation_method")
        require(method in targetedMethods) { "VIDEO_TARGETED_METHOD_BINDING_REQUIRED" }
        val selectors = mutableListOf("feed_anchor" to binding.getValue("feed_anchor").jsonObject,
            "gesture_viewport" to binding.getValue("gesture_viewport").jsonObject)
        binding["loading_marker"]?.let { selectors += "loading_marker" to it.jsonObject }
        binding.getValue("blockers").jsonObject.toSortedMap().forEach { (kind, entries) ->
            entries.jsonArray.forEachIndexed { index, entry ->
                val rule = entry.jsonObject
                val allOf = rule["all_of"]?.jsonArray
                if (allOf != null) {
                    require(method == TARGETED_MODAL_AWARE_METHOD) { "VIDEO_COMPOSITE_BLOCKER_REQUIRES_V2_METHOD" }
                    require(allOf.isNotEmpty()) { "VIDEO_COMPOSITE_BLOCKER_EMPTY" }
                    allOf.forEachIndexed { subIndex, sub ->
                        selectors += "blockers.$kind.$index.$subIndex" to sub.jsonObject
                    }
                } else {
                    selectors += "blockers.$kind.$index" to rule
                }
            }
        }
        require(selectors.size <= 32) { "VIDEO_PUBLIC_QUERY_PLAN_LIMIT" }
        return selectors.map { (name, selector) ->
            val id = selector.string("resource_id")
            val text = selector.string("query_text").ifBlank { selector.string("text") }
            require(id.isNotBlank() || text.isNotBlank()) { "VIDEO_PUBLIC_QUERY_SELECTOR_UNSUPPORTED" }
            if (id.isBlank()) {
                val allowedSources = buildList {
                    add(selector.string("text"))
                    add(selector.string("description"))
                    if (method == TARGETED_MODAL_AWARE_METHOD) add(selector.string("text_contains"))
                }
                require(allowedSources.any { it.isNotBlank() && text in it }) {
                    "VIDEO_PUBLIC_QUERY_TEXT_NOT_SOURCE_BOUND"
                }
            }
            if (id.isNotBlank()) VideoUiQuery(name, "VIEW_ID", id) else VideoUiQuery(name, "TEXT", text)
        }
    }

    private fun JsonObject.string(key: String): String = (get(key) as? JsonPrimitive)
        ?.takeIf { it.isString }?.content.orEmpty()
}
