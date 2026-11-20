package com.aneb.probe.research.business

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One video vertical, not the simulator ScenarioProfile or the Reader navigation index. */
data class VideoAction(val type: String, val durationMs: Long)

/** Fractions refer to the freshly admitted video viewport, never a guessed whole-screen rectangle. */
@Serializable
data class VideoSwipe(val x: Double, val fromY: Double, val toY: Double, val durationMs: Long)

@Serializable
data class VideoScenarioDefinition(
    val contractVersion: String,
    val profileId: String,
    val version: String,
    val packageName: String,
    val appVersionPolicy: String,
    val scenario: String,
    val initialState: String,
    val accountPolicy: String,
    val contentSelection: String,
    val contentAdmission: String,
    val cachePolicy: String,
    val swipe: VideoSwipe,
    val observeMs: Long,
    val frameIntervalMs: Long,
    val maxFrames: Int,
    val maxFrameBytes: Long,
    val visualMetric: String,
    val firstFrameTargetMs: Long,
    val blockedStates: List<String>,
    val recovery: String,
    val exitPolicy: String,
)

/** Replay consumes the WHOLE plan: actions alone are labels, not a gesture program. */
data class VideoActionPlan(
    val definition: VideoScenarioDefinition,
    val profileSourceSha256: String,
    val actions: List<VideoAction>,
) {
    val profileId: String get() = definition.profileId
    val version: String get() = definition.version
}

object VideoScenarioProfile {
    private val json = Json { ignoreUnknownKeys = false }

    /** Network conditions are deliberately not an input to the phone's behavior plan. */
    fun compile(source: String): VideoActionPlan {
        require(source.toByteArray(Charsets.UTF_8).size in 1..65_536) { "profile_size_invalid" }
        val profile = json.decodeFromString(VideoScenarioDefinition.serializer(), source)
        require(profile.contractVersion == "aneb-video-business-profile-1") { "profile_contract_unsupported" }
        require(profile.profileId == "douyin-feed-single-switch" &&
            profile.packageName == "com.ss.android.ugc.aweme" && profile.scenario == "SINGLE_FEED_SWITCH") {
            "profile_scenario_unsupported"
        }
        require(profile.version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { "profile_version_invalid" }
        require(profile.appVersionPolicy == "PIN_AT_FIRST_ADMISSION" && profile.initialState == "FEED_READY" &&
            profile.accountPolicy == "EXISTING_ACCOUNT_NO_LOGIN_FLOW") { "profile_admission_unsupported" }
        require(profile.contentSelection == "ONE_NEXT_ITEM_BIND_NO_RETRY" &&
            profile.contentAdmission == "NEW_TO_CAMPAIGN_NO_DELIBERATE_PREVIEW" &&
            profile.cachePolicy == "NO_CLEAR_NO_PREWATCH_RECORD_UNKNOWN") { "profile_content_policy_unsupported" }
        val swipe = profile.swipe
        require(swipe.x.isFinite() && swipe.fromY.isFinite() && swipe.toY.isFinite() &&
            swipe.x in 0.1..0.9 && swipe.toY >= 0.1 && swipe.fromY <= 0.9 &&
            swipe.toY < swipe.fromY && swipe.durationMs in 100L..1_500L) { "profile_swipe_invalid" }
        require(profile.observeMs == 30_000L && profile.firstFrameTargetMs in 1L..profile.observeMs) {
            "profile_observation_policy_invalid"
        }
        require(profile.frameIntervalMs == 500L && profile.maxFrames == 64 && profile.maxFrameBytes == 268_435_456L &&
            profile.visualMetric == "FIRST_OBSERVED_PLAYBACK_NOT_PLAYER_FIRST_FRAME") { "profile_capture_policy_unsupported" }
        val blocked = setOf("LOGIN_REQUIRED", "PAYMENT_REQUIRED", "PROTOCOL_REQUIRED", "AD_PRESENT", "UNKNOWN")
        require(profile.blockedStates.size == blocked.size && profile.blockedStates.toSet() == blocked &&
            profile.recovery == "STOP_NO_RETRY" && profile.exitPolicy == "OWNED_ONLY_LAUNCHER_RESTORE") {
            "profile_stop_policy_invalid"
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return VideoActionPlan(profile, sha, listOf(
            VideoAction("SWIPE_UP", swipe.durationMs), VideoAction("OBSERVE", profile.observeMs),
        ))
    }
}
