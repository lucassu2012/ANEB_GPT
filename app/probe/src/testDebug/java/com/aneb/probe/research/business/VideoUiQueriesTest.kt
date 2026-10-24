package com.aneb.probe.research.business

import org.junit.Assert.*
import org.junit.Test

class VideoUiQueriesTest {
    @Test fun `method selection requires agreement with the binding and never silently changes legacy`() {
        val legacy = binding().replace("\"observation_method\":\"targeted-public-selectors-1\",", "")
        assertEquals(VideoUiQueries.LEGACY_METHOD, VideoUiQueries.selectMethod(null, legacy))
        assertEquals(VideoUiQueries.TARGETED_METHOD, VideoUiQueries.selectMethod(VideoUiQueries.TARGETED_METHOD, binding()))
        assertThrows(IllegalArgumentException::class.java) { VideoUiQueries.selectMethod(VideoUiQueries.TARGETED_METHOD, legacy) }
        assertThrows(IllegalArgumentException::class.java) { VideoUiQueries.selectMethod(null, binding()) }
        assertThrows(IllegalArgumentException::class.java) { VideoUiQueries.selectMethod("unknown-method", binding()) }
    }

    @Test fun `recall text must be declared by the bound exact selector`() {
        assertThrows(IllegalArgumentException::class.java) {
            VideoUiQueries.compile(binding().replace("\"query_text\":\"推荐\"", "\"query_text\":\"unrelated\""))
        }
    }

    @Test fun `declared description recall stays separate from exact selected feed admission`() {
        val queries = VideoUiQueries.compile(binding())
        assertEquals(listOf(
            VideoUiQuery("feed_anchor", "TEXT", "推荐"),
            VideoUiQuery("gesture_viewport", "VIEW_ID", "fixture:id/video"),
        ), queries)
        val viewport = VideoUiPublicNode("fixture:id/video", null, "视频", VideoViewport(0, 0, 1200, 2478))
        val selected = VideoUiPublicNode(null, null, "已选中，推荐，按钮", VideoViewport(900, 180, 1000, 260))
        assertEquals("FEED_READY", VideoUiAdmission.classify(binding(), listOf(viewport, selected)).kind)
        val unselected = selected.copy(description = "推荐，按钮")
        assertEquals("UNKNOWN", VideoUiAdmission.classify(binding(), listOf(viewport, unselected)).kind)
    }

    // Fabricated selector contract; no captured App content or Android API behavior is asserted.
    private fun binding(): String = """{
      "observation_method":"targeted-public-selectors-1",
      "admission_scope":"KNOWN_RECOMMENDATION_FEED_ONLY","blocker_coverage":"NON_EXHAUSTIVE",
      "display_width":1200,"display_height":2640,"viewport_bounds":[0,0,1200,2478],
      "feed_anchor":{"description":"已选中，推荐，按钮","query_text":"推荐"},
      "gesture_viewport":{"resource_id":"fixture:id/video","description":"视频"},
      "blockers":{"LOGIN_REQUIRED":[],"PAYMENT_REQUIRED":[],"PROTOCOL_REQUIRED":[],"AD_PRESENT":[],"BUSINESS_ERROR":[]},
      "uncovered_blocker_kinds":["LOGIN_REQUIRED","PAYMENT_REQUIRED","PROTOCOL_REQUIRED","AD_PRESENT","BUSINESS_ERROR"]
    }"""
}
