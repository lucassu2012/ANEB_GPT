package com.aneb.probe.research.business

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Explicit observation of this run's cancel request, never inferred from an arbitrary tool error. */
class VideoOperationCancelled : Exception("OWNED_CANCEL_DURING_OPERATION")

@Serializable
data class VideoViewport(val left: Int, val top: Int, val right: Int, val bottom: Int)

@Serializable
data class VideoPage(
    val packageName: String,
    val versionCode: Long,
    val kind: String,
    val networkKey: String,
    val viewport: VideoViewport,
    val originalReference: String,
)

@Serializable
data class VideoFrameObservation(
    val sequence: Int,
    val requestedNs: Long,
    val returnedNs: Long,
    val writtenNs: Long,
    val relativePath: String,
    val sha256: String,
    val bytes: Long,
)

@Serializable
data class VideoRunBinding(
    val runId: String,
    val profileSha256: String,
    val appVersionCode: Long,
    val conditionId: String,
    val networkKey: String,
    val clockId: String,
)

@Serializable
data class VideoOperationalEvent(val name: String, val atNs: Long)

@Serializable
data class VideoScenarioResult(
    val runId: String,
    val profileSha256: String,
    val conditionId: String,
    val clockId: String,
    val state: String,
    val category: String,
    val reason: String?,
    val swipeCalls: String,
    val ownedCleanup: Boolean,
    val events: List<VideoOperationalEvent>,
    val frames: List<VideoFrameObservation>,
    val firstObservedPlaybackWaitMs: Double? = null,
    val playerFirstFrameMs: Double? = null,
    val stallStatus: String = "not_computed",
    val format: String = "business-video-raw-1",
)

/** One phone scenario, not an App automation framework. Android/OS is the only replaceable boundary. */
class VideoScenarioExecution(private val phone: Phone, private val nowNs: () -> Long) {
    interface Phone {
        fun prepare(deadlineNs: Long): VideoPage
        fun startRecording(deadlineNs: Long)
        fun inspect(): VideoPage
        fun swipeOnce(swipe: VideoSwipe): Boolean
        fun frame(sequence: Int): VideoFrameObservation
        fun sleepMillis(milliseconds: Long)
        fun stopRecording()
        fun cleanupOwned(deadlineNs: Long): Boolean
    }

    fun runOnce(profileSource: String, binding: VideoRunBinding, directory: File): VideoScenarioResult {
        val plan = VideoScenarioProfile.compile(profileSource)
        require(binding.runId.matches(Regex("[A-Za-z0-9_-]{1,64}")) && directory.name == binding.runId)
        require(binding.profileSha256 == plan.profileSourceSha256 && binding.appVersionCode > 0L)
        require(binding.conditionId.matches(Regex("[A-Za-z0-9_-]{1,64}")) && binding.networkKey.isNotBlank() && binding.clockId.isNotBlank())
        check(directory.mkdir()) { "VIDEO_ATTEMPT_ALREADY_CONSUMED" }
        val codec = Json { encodeDefaults = true; prettyPrint = true }
        fun writeNew(name: String, text: String) {
            val file = File(directory, name)
            check(file.createNewFile()) { "VIDEO_ORIGINAL_ALREADY_EXISTS" }
            FileOutputStream(file).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        }
        writeNew("profile.json", profileSource)
        writeNew("binding.json", codec.encodeToString(VideoRunBinding.serializer(), binding))
        val events = mutableListOf<VideoOperationalEvent>()
        val frames = mutableListOf<VideoFrameObservation>()
        fun event(name: String) = nowNs().also { events += VideoOperationalEvent(name, it) }
        val preparationEnd = event("preparation_started") + 360_000_000_000L
        var category = "TOOL_FAILURE"
        var reason: String? = null
        var calls = "0"
        var captured = false
        var cleaned = false
        var totalBytes = 0L
        val paths = mutableSetOf<String>()
        var admittedViewport: VideoViewport? = null
        fun stop(kind: String, code: String): Nothing = throw Stop(kind, code)
        fun requirePage(page: VideoPage, action: Boolean) {
            if (page.packageName != plan.definition.packageName || page.versionCode != binding.appVersionCode) {
                stop("ENVIRONMENT_CHANGED", "APP_IDENTITY_CHANGED")
            }
            if (page.networkKey != binding.networkKey) stop("NETWORK_CHANGED", "ACTIVE_NETWORK_CHANGED_NOT_A_QUALITY_RESULT")
            val allowed = if (action) setOf("FEED_READY") else setOf("FEED_READY", "VIDEO_LOADING")
            if (page.kind !in allowed) {
                val kind = when (page.kind) {
                    "LOGIN_REQUIRED" -> "ACCOUNT_BLOCKED"
                    "PAYMENT_REQUIRED", "PROTOCOL_REQUIRED" -> "ACCESS_BLOCKED"
                    "AD_PRESENT" -> "CONTENT_NOT_ELIGIBLE"
                    "BUSINESS_ERROR" -> "BUSINESS_ERROR"
                    else -> "PAGE_MISMATCH"
                }
                stop(kind, page.kind)
            }
            val expectedViewport = admittedViewport
            if (expectedViewport == null) admittedViewport = page.viewport
            else if (page.viewport != expectedViewport) stop("ENVIRONMENT_CHANGED", "VIDEO_VIEWPORT_CHANGED")
        }
        fun live() {
            if (File(directory, "CANCEL").exists()) stop("CANCELLED", "OWNED_CANCEL_REQUESTED")
        }
        fun sample() {
            live()
            if (frames.size >= plan.definition.maxFrames) stop("TOOL_BUDGET", "FRAME_LIMIT_REACHED")
            requirePage(phone.inspect(), action = false)
            val requested = nowNs()
            val frame = phone.frame(frames.size)
            check(frame.sequence == frames.size && frame.requestedNs >= requested &&
                frame.returnedNs >= frame.requestedNs && frame.writtenNs >= frame.returnedNs && frame.writtenNs <= nowNs()) {
                "FRAME_CLOCK_OR_SEQUENCE_INVALID"
            }
            val file = File(directory, frame.relativePath).canonicalFile
            check(file.toPath().startsWith(directory.canonicalFile.toPath()) && file.isFile && paths.add(frame.relativePath)) {
                "FRAME_ORIGINAL_REFERENCE_INVALID"
            }
            check(frame.bytes > 0L && file.length() == frame.bytes) { "FRAME_ORIGINAL_SIZE_INVALID" }
            totalBytes += frame.bytes
            if (totalBytes > plan.definition.maxFrameBytes) stop("TOOL_BUDGET", "FRAME_BYTE_LIMIT_REACHED")
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val block = ByteArray(16_384)
                while (true) { val read = input.read(block); if (read < 0) break; digest.update(block, 0, read) }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == frame.sha256) { "FRAME_ORIGINAL_HASH_INVALID" }
            frames += frame
        }
        try {
            requirePage(phone.prepare(preparationEnd), action = true)
            live()
            check(nowNs() < preparationEnd) { "PREPARATION_DEADLINE_EXPIRED" }
            phone.startRecording(minOf(preparationEnd, nowNs() + 20_000_000_000L))
            requirePage(phone.inspect(), action = true)
            sample() // Preserved pre-gesture observation; not a positive playback classification.
            check(nowNs() < preparationEnd) { "PREPARATION_DEADLINE_EXPIRED" }
            writeNew("SWIPE_INTENT.json", "{\"run_id\":\"${binding.runId}\",\"state\":\"consumed_before_invocation\"}\n")
            live()
            calls = "UNKNOWN"
            event("swipe_invocation_started")
            if (!phone.swipeOnce(plan.definition.swipe)) stop("OPERATION_UNKNOWN", "GESTURE_RETURNED_FALSE_NO_RETRY")
            calls = "1"
            val windowStart = event("swipe_returned")
            val windowEnd = windowStart + plan.definition.observeMs * 1_000_000L
            var next = windowStart
            while (nowNs() < windowEnd) {
                if (nowNs() < next) {
                    phone.sleepMillis((next - nowNs() + 999_999L) / 1_000_000L)
                }
                if (nowNs() >= windowEnd) break
                sample()
                next += plan.definition.frameIntervalMs * 1_000_000L
                // Skip missed slots, never burst-capture to fill a gap or extend the observation window.
                while (next < nowNs()) next += plan.definition.frameIntervalMs * 1_000_000L
                next = minOf(next, windowEnd)
            }
            event("observation_window_ended")
            requirePage(phone.inspect(), action = false)
            phone.stopRecording()
            event("recording_closed")
            captured = true
            category = "OBSERVATION_CAPTURED"
        } catch (failure: Stop) {
            category = failure.category
            reason = failure.message
            event("stopped")
        } catch (failure: VideoOperationCancelled) {
            category = "CANCELLED"
            reason = failure.message
            event("owned_cancel_observed")
        } catch (failure: Exception) {
            category = if (calls == "UNKNOWN") "OPERATION_UNKNOWN" else "TOOL_FAILURE"
            reason = failure.javaClass.simpleName
            event("failure_retained")
        } finally {
            cleaned = try { phone.cleanupOwned(nowNs() + 210_000_000_000L) } catch (_: Exception) { false }
            event("owned_cleanup_checked")
        }
        val result = VideoScenarioResult(binding.runId, binding.profileSha256, binding.conditionId, binding.clockId,
            if (captured && cleaned) "CAPTURED_AWAITING_REVIEW" else "STOPPED",
            category, reason ?: if (!cleaned) "OWNED_CLEANUP_UNCONFIRMED" else null,
            calls, cleaned, events, frames)
        writeNew("result.json", codec.encodeToString(VideoScenarioResult.serializer(), result))
        return result
    }

    private class Stop(val category: String, code: String) : Exception(code)
}
