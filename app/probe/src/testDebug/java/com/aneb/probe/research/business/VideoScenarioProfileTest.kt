package com.aneb.probe.research.business

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoScenarioProfileTest {
    @Test
    fun `bundled phone profile yields one fixed swipe and thirty second observation replay`() {
        val source = File("src/debug/assets/business-profiles/douyin-feed-single-switch-v1.json").readText()

        val first = VideoScenarioProfile.compile(source)
        val replay = VideoScenarioProfile.compile(source)

        assertEquals(first, replay)
        assertEquals("douyin-feed-single-switch", first.profileId)
        assertEquals(listOf(VideoAction("SWIPE_UP", 400L), VideoAction("OBSERVE", 30_000L)), first.actions)
        assertEquals(VideoSwipe(0.5, 0.8, 0.2, 400L), first.definition.swipe)
        assertEquals("NEW_TO_CAMPAIGN_NO_DELIBERATE_PREVIEW", first.definition.contentAdmission)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a different App cannot be admitted under this one scene profile`() {
        val source = File("src/debug/assets/business-profiles/douyin-feed-single-switch-v1.json").readText()
        VideoScenarioProfile.compile(source.replace("com.ss.android.ugc.aweme", "com.aliyun.tongyi"))
    }
}
