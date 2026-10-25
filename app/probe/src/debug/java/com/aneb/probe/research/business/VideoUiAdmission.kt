package com.aneb.probe.research.business

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Native supplies only nodes passing its existing same-package/visibility checks. */
data class VideoUiPublicNode(val resourceId: String?, val text: String?, val description: String?, val bounds: VideoViewport)
data class VideoUiDecision(val kind: String, val viewport: VideoViewport, val reason: String? = null)

/** New known-feed/coverage and geometry additions; Native still checks App identity and selectors. */
object VideoUiAdmission {
    /** Same classifier for Native and bounded offline replay, not an Android-tree authenticity check. */
    fun classify(source: String, visibleNodes: List<VideoUiPublicNode>): VideoUiDecision {
        val binding = Json.parseToJsonElement(source).jsonObject
        val expected = compile(source)
        val keys = listOf("resource_id", "text", "description")
        fun matches(selector: JsonObject): List<VideoUiPublicNode> {
            fun value(key: String) = selector[key]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content.orEmpty()
            check(keys.any { value(it).isNotBlank() }) { "VIDEO_PUBLIC_SELECTOR_REQUIRED" }
            return visibleNodes.filter { node -> keys.all { field ->
                field !in selector || value(field) == when (field) {
                    "resource_id" -> node.resourceId.orEmpty()
                    "text" -> node.text.orEmpty()
                    else -> node.description.orEmpty()
                }
            } }
        }
        val blocked = binding.getValue("blockers").jsonObject.filterValues { selectors ->
            selectors.jsonArray.any { matches(it.jsonObject).isNotEmpty() }
        }.keys
        val regions = matches(binding.getValue("gesture_viewport").jsonObject)
        val region = regions.singleOrNull()
        val viewport = region?.bounds ?: VideoViewport(0, 0, 0, 0)
        if (region != null) {
            check(viewport.left >= 0 && viewport.top >= 0 && viewport.right <= binding.getValue("display_width").jsonPrimitive.int &&
                viewport.bottom <= binding.getValue("display_height").jsonPrimitive.int &&
                viewport.right > viewport.left && viewport.bottom > viewport.top)
        }
        // Preserve blocker/viewport precedence: do not consult feed/loading selectors on a blocked page.
        val feedMatches = if (blocked.isEmpty() && viewport == expected)
            matches(binding.getValue("feed_anchor").jsonObject).size else 0
        val kind = when {
            blocked.size > 1 -> "UNKNOWN"
            blocked.size == 1 -> blocked.single()
            viewport != expected -> "UNKNOWN"
            feedMatches == 1 && region != null -> "FEED_READY"
            "loading_marker" in binding && matches(binding.getValue("loading_marker").jsonObject).size == 1 -> "VIDEO_LOADING"
            else -> "UNKNOWN"
        }
        val reason = when {
            kind == "FEED_READY" -> null
            blocked.size > 1 -> "MULTIPLE_BLOCKER_KINDS"
            blocked.size == 1 -> blocked.single()
            region == null -> if (regions.isEmpty()) "VIDEO_VIEWPORT_MISSING" else "VIDEO_VIEWPORT_NOT_UNIQUE"
            viewport != expected -> "VIDEO_VIEWPORT_CHANGED"
            kind == "VIDEO_LOADING" -> "VIDEO_LOADING"
            feedMatches == 0 -> "FEED_ANCHOR_MISSING"
            else -> "FEED_ANCHOR_NOT_UNIQUE"
        }
        return VideoUiDecision(kind, viewport, reason)
    }

    /** Final Android adapter check, before its first input injection. No retry or admission relaxation. */
    fun requireBeforeInput(page: VideoPage, packageName: String, versionCode: Long, networkKey: String) {
        val rejection = when {
            page.packageName != packageName || page.versionCode != versionCode -> "APP_IDENTITY_CHANGED"
            page.kind != "FEED_READY" -> page.admissionReason ?: "PAGE_NOT_READY:${page.kind}"
            page.networkKey != networkKey -> "ACTIVE_NETWORK_CHANGED_NOT_A_QUALITY_RESULT"
            else -> return
        }
        VideoPreInputRejected.reject(page, rejection)
    }

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
