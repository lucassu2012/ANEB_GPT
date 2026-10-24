package com.aneb.probe.research.business

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VideoScenarioFailureTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `viewport change before the gesture stops without touching the feed and retains originals`() {
        val attempt = Attempt("viewport-changed-before-gesture")
        attempt.phone.preparedViewport = VideoViewport(0, 0, 1200, 2478)
        attempt.phone.inspectedViewport = VideoViewport(0, 0, 1200, 1800)

        val result = attempt.run()

        assertStopped(result, "ENVIRONMENT_CHANGED", "VIDEO_VIEWPORT_CHANGED")
        assertTrue(attempt.phone.gestures.isEmpty())
        assertTrue(result.ownedCleanup)
        assertTrue(result.frames.isEmpty())
        assertFalse(File(attempt.directory, "SWIPE_INTENT.json").exists())
        assertEquals(attempt.source, File(attempt.directory, "profile.json").readText())
        assertEquals(attempt.binding, Json.decodeFromString(
            VideoRunBinding.serializer(), File(attempt.directory, "binding.json").readText(),
        ))
        assertPersisted(attempt, result)
    }

    @Test
    fun `explicit native cancellation during an operation is cancelled and never retried`() {
        val attempt = Attempt("cancel-during-gesture")
        attempt.phone.gestureFailure = VideoOperationCancelled()
        val result = attempt.run()
        assertStopped(result, "CANCELLED", "OWNED_CANCEL_DURING_OPERATION", "UNKNOWN")
        assertEquals(1, attempt.phone.gestures.size)
        assertTrue(result.ownedCleanup)
        assertPersisted(attempt, result)
    }

    @Test
    fun `login required stops as an account block without attempting a gesture`() {
        val attempt = Attempt("login-blocked")
        attempt.phone.preparedKind = "LOGIN_REQUIRED"

        val result = attempt.run()

        assertStopped(result, "ACCOUNT_BLOCKED", "LOGIN_REQUIRED")
        assertTrue(result.ownedCleanup)
        assertTrue(attempt.phone.gestures.isEmpty())
        assertTrue(result.frames.isEmpty())
        assertFalse(File(attempt.directory, "SWIPE_INTENT.json").exists())
        assertPersisted(attempt, result)
    }

    @Test
    fun `unknown page stops as a mismatch without guessing or attempting a gesture`() {
        val attempt = Attempt("unknown-page")
        attempt.phone.preparedKind = "UNKNOWN"

        val result = attempt.run()

        assertStopped(result, "PAGE_MISMATCH", "UNKNOWN")
        assertTrue(result.ownedCleanup)
        assertTrue(attempt.phone.gestures.isEmpty())
        assertTrue(result.frames.isEmpty())
        assertFalse(File(attempt.directory, "SWIPE_INTENT.json").exists())
        assertPersisted(attempt, result)
    }

    @Test
    fun `advertisement stops as ineligible content without skipping it with a gesture`() {
        val attempt = Attempt("advertisement")
        attempt.phone.preparedKind = "AD_PRESENT"

        val result = attempt.run()

        assertStopped(result, "CONTENT_NOT_ELIGIBLE", "AD_PRESENT")
        assertTrue(result.ownedCleanup)
        assertTrue(attempt.phone.gestures.isEmpty())
        assertTrue(result.frames.isEmpty())
        assertFalse(File(attempt.directory, "SWIPE_INTENT.json").exists())
        assertPersisted(attempt, result)
    }

    @Test
    fun `business error stays a business error without attempting a gesture`() {
        val attempt = Attempt("business-error")
        attempt.phone.preparedKind = "BUSINESS_ERROR"

        val result = attempt.run()

        assertStopped(result, "BUSINESS_ERROR", "BUSINESS_ERROR")
        assertTrue(result.ownedCleanup)
        assertTrue(attempt.phone.gestures.isEmpty())
        assertTrue(result.frames.isEmpty())
        assertFalse(File(attempt.directory, "SWIPE_INTENT.json").exists())
        assertPersisted(attempt, result)
    }

    @Test
    fun `a login page appearing after recording begins still stops before the gesture`() {
        val attempt = Attempt("login-after-recording")
        attempt.phone.inspectedKind = "LOGIN_REQUIRED"

        val result = attempt.run()

        assertStopped(result, "ACCOUNT_BLOCKED", "LOGIN_REQUIRED")
        assertTrue(attempt.phone.boundaryOperations.contains("startRecording"))
        assertTrue(result.ownedCleanup)
        assertTrue(attempt.phone.gestures.isEmpty())
        assertFalse(File(attempt.directory, "SWIPE_INTENT.json").exists())
        assertPersisted(attempt, result)
    }

    @Test
    fun `recording start failure is a tool failure and not a network quality result`() {
        val attempt = Attempt("recorder-start-failed")
        attempt.phone.recordingStartFailure = IllegalStateException("fixture recorder unavailable")

        val result = attempt.run()

        assertStopped(result, "TOOL_FAILURE", "IllegalStateException")
        assertTrue(result.ownedCleanup)
        assertTrue(attempt.phone.gestures.isEmpty())
        assertTrue(result.frames.isEmpty())
        assertFalse(result.events.any { it.name == "recording_closed" })
        assertPersisted(attempt, result)
    }

    @Test
    fun `recording finalization failure retains observations but cannot claim a closed capture`() {
        val attempt = Attempt("recorder-stop-failed")
        attempt.phone.recordingStopFailure = IllegalStateException("fixture recording not finalized")

        val result = attempt.run()

        assertStopped(result, "TOOL_FAILURE", "IllegalStateException", "1")
        assertEquals(1, attempt.phone.gestures.size)
        assertTrue(result.frames.size > 1)
        assertTrue(result.ownedCleanup)
        assertFalse(result.events.any { it.name == "recording_closed" })
        assertPersisted(attempt, result)
    }

    @Test
    fun `a false gesture response stays unknown and is never automatically retried`() {
        val attempt = Attempt("gesture-unconfirmed")
        attempt.phone.gestureAccepted = false

        val result = attempt.run()

        assertStopped(result, "OPERATION_UNKNOWN", "GESTURE_RETURNED_FALSE_NO_RETRY", "UNKNOWN")
        assertEquals(listOf(attempt.plan.definition.swipe), attempt.phone.gestures)
        assertEquals(1, result.frames.size)
        assertTrue(result.ownedCleanup)
        assertTrue(File(attempt.directory, "SWIPE_INTENT.json").isFile)
        assertFalse(result.events.any { it.name == "swipe_returned" || it.name == "observation_window_ended" })
        assertPersisted(attempt, result)
    }

    @Test
    fun `active network change stops observation without becoming a playback metric`() {
        val attempt = Attempt("network-changed")
        attempt.phone.networkAfterSwipe = "cellular-network-2"

        val result = attempt.run()

        assertStopped(result, "NETWORK_CHANGED", "ACTIVE_NETWORK_CHANGED_NOT_A_QUALITY_RESULT", "1")
        assertEquals(1, attempt.phone.gestures.size)
        assertEquals(1, result.frames.size)
        assertTrue(result.ownedCleanup)
        assertFalse(result.events.any { it.name == "observation_window_ended" })
        assertPersisted(attempt, result)
    }

    @Test
    fun `unconfirmed owned cleanup keeps even a completed observation stopped`() {
        val attempt = Attempt("cleanup-unconfirmed")
        attempt.phone.cleanupConfirmed = false

        val result = attempt.run()

        assertStopped(result, "OBSERVATION_CAPTURED", "OWNED_CLEANUP_UNCONFIRMED", "1")
        assertFalse(result.ownedCleanup)
        assertEquals(1, attempt.phone.gestures.size)
        assertTrue(result.frames.size > 1)
        assertPersisted(attempt, result)
    }

    @Test
    fun `owned cleanup exception cannot be mistaken for a closed run`() {
        val attempt = Attempt("cleanup-threw")
        attempt.phone.cleanupFailure = IllegalStateException("fixture owned cleanup interrupted")

        val result = attempt.run()

        assertStopped(result, "OBSERVATION_CAPTURED", "OWNED_CLEANUP_UNCONFIRMED", "1")
        assertFalse(result.ownedCleanup)
        assertEquals(1, attempt.phone.gestures.size)
        assertTrue(result.frames.size > 1)
        assertPersisted(attempt, result)
    }

    @Test
    fun `an unconfirmed gesture consumes the run directory and replay cannot touch the phone or originals`() {
        val attempt = Attempt("consumed-gesture")
        attempt.phone.gestureAccepted = false
        val first = attempt.run()
        assertStopped(first, "OPERATION_UNKNOWN", "GESTURE_RETURNED_FALSE_NO_RETRY", "UNKNOWN")
        val originals = snapshot(attempt.directory)
        val nextPhone = PhoneBoundary(attempt.directory)

        try {
            VideoScenarioExecution(nextPhone) { nextPhone.time }
                .runOnce(attempt.source, attempt.binding, attempt.directory)
            fail("A consumed run directory must be rejected before any phone operation")
        } catch (expected: IllegalStateException) {
            assertEquals("VIDEO_ATTEMPT_ALREADY_CONSUMED", expected.message)
        }

        assertTrue(nextPhone.boundaryOperations.isEmpty())
        assertTrue(nextPhone.gestures.isEmpty())
        assertEquals(originals, snapshot(attempt.directory))
        assertPersisted(attempt, first)
    }

    private fun snapshot(directory: File): Map<String, List<Byte>> = directory.walkTopDown()
        .filter { it.isFile }
        .associate { it.relativeTo(directory).invariantSeparatorsPath to it.readBytes().toList() }

    private fun assertStopped(
        result: VideoScenarioResult,
        category: String,
        reason: String,
        swipeCalls: String = "0",
    ) {
        assertEquals("STOPPED", result.state)
        assertEquals(category, result.category)
        assertEquals(reason, result.reason)
        assertEquals(swipeCalls, result.swipeCalls)
        assertNoMetrics(result)
    }

    private fun assertNoMetrics(result: VideoScenarioResult) {
        assertNull(result.firstObservedPlaybackWaitMs)
        assertNull(result.playerFirstFrameMs)
        assertEquals("not_computed", result.stallStatus)
    }

    private fun assertPersisted(attempt: Attempt, result: VideoScenarioResult) {
        val retained = Json.decodeFromString(
            VideoScenarioResult.serializer(), File(attempt.directory, "result.json").readText(),
        )
        assertEquals(result, retained)
    }

    private inner class Attempt(name: String) {
        val directory = File(temporary.root, name)
        val source = File("src/debug/assets/business-profiles/douyin-feed-single-switch-v1.json").readText()
        val plan = VideoScenarioProfile.compile(source)
        val binding = VideoRunBinding(name, plan.profileSourceSha256, 100L, "R", "wifi-network-1", "failure-test-clock")
        val phone = PhoneBoundary(directory)

        fun run(): VideoScenarioResult = VideoScenarioExecution(phone) { phone.time }
            .runOnce(source, binding, directory)
    }

    /** Only Android acquisition, time and files are replaced; fixture bytes are not media. */
    private class PhoneBoundary(private val directory: File) : VideoScenarioExecution.Phone {
        var time = 1_000_000_000L
        var preparedKind = "FEED_READY"
        var inspectedKind = "FEED_READY"
        var preparedViewport = VideoViewport(0, 100, 1200, 2400)
        var inspectedViewport = VideoViewport(0, 100, 1200, 2400)
        var networkAfterSwipe = "wifi-network-1"
        var gestureAccepted = true
        var gestureFailure: Exception? = null
        var recordingStartFailure: RuntimeException? = null
        var recordingStopFailure: RuntimeException? = null
        var cleanupFailure: RuntimeException? = null
        var cleanupConfirmed = true
        val gestures = mutableListOf<VideoSwipe>()
        val boundaryOperations = mutableListOf<String>()

        private fun page(kind: String, viewport: VideoViewport) = VideoPage(
            "com.ss.android.ugc.aweme", 100L, kind,
            if (gestures.isEmpty()) "wifi-network-1" else networkAfterSwipe,
            viewport, "offline-boundary-not-real-android",
        )

        override fun prepare(deadlineNs: Long): VideoPage {
            boundaryOperations += "prepare"
            return page(preparedKind, preparedViewport)
        }

        override fun startRecording(deadlineNs: Long) {
            boundaryOperations += "startRecording"
            recordingStartFailure?.let { throw it }
        }

        override fun inspect(): VideoPage {
            boundaryOperations += "inspect"
            return page(inspectedKind, inspectedViewport)
        }

        override fun swipeOnce(swipe: VideoSwipe): Boolean {
            boundaryOperations += "swipeOnce"
            gestures += swipe
            time += swipe.durationMs * 1_000_000L
            gestureFailure?.let { throw it }
            return gestureAccepted
        }

        override fun frame(sequence: Int): VideoFrameObservation {
            boundaryOperations += "frame"
            val requested = time
            time += 10_000_000L
            val bytes = "OFFLINE_FAILURE_FIXTURE_NOT_MEDIA_$sequence".toByteArray(Charsets.UTF_8)
            val original = File(directory, "frame-$sequence.bin")
            Files.write(original.toPath(), bytes)
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            return VideoFrameObservation(sequence, requested, time, time, original.name, sha, bytes.size.toLong())
        }

        override fun sleepMillis(milliseconds: Long) {
            boundaryOperations += "sleepMillis"
            time += milliseconds * 1_000_000L
        }

        override fun stopRecording() {
            boundaryOperations += "stopRecording"
            recordingStopFailure?.let { throw it }
        }

        override fun cleanupOwned(deadlineNs: Long): Boolean {
            boundaryOperations += "cleanupOwned"
            cleanupFailure?.let { throw it }
            return cleanupConfirmed
        }
    }
}
