package com.aneb.probe.research.business

import org.junit.Assert.*
import org.junit.Test

class VideoUiAdmissionTest {
    @Test fun `a missing feed anchor stays unknown and explains the refusal without discarding its viewport`() {
        val source = binding().dropLast(1) + """,
          "feed_anchor":{"description":"selected fixture feed"},
          "gesture_viewport":{"resource_id":"fixture-viewport"}
        }"""
        val viewport = VideoUiPublicNode("fixture-viewport", null, null, VideoViewport(0, 0, 1200, 2478))
        val anchor = VideoUiPublicNode(null, null, "selected fixture feed", VideoViewport(0, 0, 100, 100))
        assertEquals("FEED_READY", VideoUiAdmission.classify(source, listOf(anchor, viewport)).kind)

        val missing = VideoUiAdmission.classify(source, listOf(viewport))

        assertEquals("UNKNOWN", missing.kind)
        assertEquals(viewport.bounds, missing.viewport)
        assertEquals("FEED_ANCHOR_MISSING", missing.reason)
    }

    @Test fun `declared known feed can bind its source viewport without inventing blocker rules`() {
        assertEquals(VideoViewport(0, 0, 1200, 2478), VideoUiAdmission.compile(binding()))
    }

    @Test fun `known feed cannot silently claim missing or exhaustive abnormal state coverage`() {
        val variants = listOf(
            binding().replace("KNOWN_RECOMMENDATION_FEED_ONLY", "UNDECLARED_SCOPE"),
            binding().replace("NON_EXHAUSTIVE", "EXHAUSTIVE"),
            binding().replace("[\"LOGIN_REQUIRED\",\"PAYMENT_REQUIRED\",\"PROTOCOL_REQUIRED\",\"AD_PRESENT\",\"BUSINESS_ERROR\"]", "[]"),
        )
        variants.forEach { source ->
            assertThrows(IllegalArgumentException::class.java) { VideoUiAdmission.compile(source) }
        }
    }

    @Test fun `source viewport outside the bound display cannot become a gesture region`() {
        val beyondDisplay = binding().replace("[0,0,1200,2478]", "[0,0,1200,2800]")
        assertThrows(IllegalArgumentException::class.java) { VideoUiAdmission.compile(beyondDisplay) }
    }

    // Fabricated contract input only, not an actual phone source or evidence of any App state.
    private fun binding(): String = """{
      "admission_scope":"KNOWN_RECOMMENDATION_FEED_ONLY",
      "blocker_coverage":"NON_EXHAUSTIVE",
      "display_width":1200,"display_height":2640,
      "viewport_bounds":[0,0,1200,2478],
      "blockers":{"LOGIN_REQUIRED":[],"PAYMENT_REQUIRED":[],"PROTOCOL_REQUIRED":[],"AD_PRESENT":[],"BUSINESS_ERROR":[]},
      "uncovered_blocker_kinds":["LOGIN_REQUIRED","PAYMENT_REQUIRED","PROTOCOL_REQUIRED","AD_PRESENT","BUSINESS_ERROR"]
    }"""
}
