package com.aneb.probe.research.business

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** New known-feed/coverage and geometry additions; Native still checks App identity and selectors. */
object VideoUiAdmission {
    fun compile(source: String): VideoViewport {
        val root = Json.parseToJsonElement(source).jsonObject
        require(root.getValue("admission_scope").jsonPrimitive.content == "KNOWN_RECOMMENDATION_FEED_ONLY") {
            "VIDEO_UI_ADMISSION_SCOPE_REQUIRED"
        }
        require(root.getValue("blocker_coverage").jsonPrimitive.content == "NON_EXHAUSTIVE") {
            "VIDEO_UI_COVERAGE_DECLARATION_REQUIRED"
        }
        val blockers = root.getValue("blockers").jsonObject
        require(blockers.keys == setOf("LOGIN_REQUIRED", "PAYMENT_REQUIRED", "PROTOCOL_REQUIRED", "AD_PRESENT", "BUSINESS_ERROR"))
        val uncovered = blockers.filterValues { it.jsonArray.isEmpty() }.keys
        val declared = root.getValue("uncovered_blocker_kinds").jsonArray.map { it.jsonPrimitive.content }
        require(declared.size == declared.toSet().size && declared.toSet() == uncovered) {
            "VIDEO_UI_UNCOVERED_BLOCKERS_MUST_BE_DECLARED"
        }
        val bounds = root.getValue("viewport_bounds").jsonArray.map { it.jsonPrimitive.int }
        require(bounds.size == 4) { "VIDEO_VIEWPORT_BINDING_REQUIRED" }
        val width = root.getValue("display_width").jsonPrimitive.int
        val height = root.getValue("display_height").jsonPrimitive.int
        require(width > 0 && height > 0 && bounds[0] >= 0 && bounds[1] >= 0 &&
            bounds[2] > bounds[0] && bounds[3] > bounds[1] && bounds[2] <= width && bounds[3] <= height) {
            "VIDEO_VIEWPORT_OUTSIDE_SOURCE_DISPLAY"
        }
        return VideoViewport(bounds[0], bounds[1], bounds[2], bounds[3])
    }
}
