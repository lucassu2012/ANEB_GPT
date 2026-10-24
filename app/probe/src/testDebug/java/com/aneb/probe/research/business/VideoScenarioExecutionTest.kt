package com.aneb.probe.research.business

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VideoScenarioExecutionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `one bound phone scenario captures a single gesture and sampled originals without inventing playback metrics`() {
        val profile = File("src/debug/assets/business-profiles/douyin-feed-single-switch-v1.json").readText()
        val plan = VideoScenarioProfile.compile(profile)
        val root = File(temporary.root, "new-reference-1")
        val phone = PhoneBoundary(root)
        val binding = VideoRunBinding(root.name, plan.profileSourceSha256, 100L, "R", "wifi-network-1", "test-clock")

        val result = VideoScenarioExecution(phone) { phone.time }.runOnce(profile, binding, root)

        assertEquals("CAPTURED_AWAITING_REVIEW", result.state)
        assertEquals(listOf(plan.definition.swipe), phone.gestures)
        assertEquals("1", result.swipeCalls)
        assertTrue(result.ownedCleanup)
        assertTrue(result.frames.size >= 2)
        assertNull(result.firstObservedPlaybackWaitMs)
        assertNull(result.playerFirstFrameMs)
        assertEquals("not_computed", result.stallStatus)
        assertTrue(File(root, "SWIPE_INTENT.json").isFile)
        assertTrue(File(root, "result.json").isFile)
        assertEquals(plan.profileSourceSha256, result.profileSha256)
        val returned = result.events.single { it.name == "swipe_returned" }.atNs
        val ended = result.events.single { it.name == "observation_window_ended" }.atNs
        assertEquals(30_000_000_000L, ended - returned)
    }

    /** External Android/time/file acquisition boundary only; these bytes are explicitly not media. */
    private class PhoneBoundary(private val directory: File) : VideoScenarioExecution.Phone {
        var time = 1_000_000_000L
        val gestures = mutableListOf<VideoSwipe>()
        private fun page() = VideoPage("com.ss.android.ugc.aweme", 100L, "FEED_READY", "wifi-network-1",
            VideoViewport(0, 100, 1200, 2400), "fixture-page-not-real-android")
        override fun prepare(deadlineNs: Long) = page()
        override fun startRecording(deadlineNs: Long) {}
        override fun inspect() = page()
        override fun swipeOnce(swipe: VideoSwipe): Boolean {
            gestures += swipe
            time += swipe.durationMs * 1_000_000L
            return true
        }
        override fun frame(sequence: Int): VideoFrameObservation {
            val requested = time
            time += 10_000_000L
            val bytes = "OFFLINE_BOUNDARY_NOT_MEDIA_$sequence".toByteArray()
            val file = File(directory, "frame-$sequence.bin")
            Files.write(file.toPath(), bytes)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return VideoFrameObservation(sequence, requested, time, time, file.name, hash, bytes.size.toLong())
        }
        override fun sleepMillis(milliseconds: Long) { time += milliseconds * 1_000_000L }
        override fun stopRecording() {}
        override fun cleanupOwned(deadlineNs: Long) = true
    }
}
