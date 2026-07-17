package com.aneb.probe.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RealtimeRuntimeTurn(
    @SerialName("turn_id") val turnId: String,
    @SerialName("turn_index") val turnIndex: Int,
    @SerialName("start_after_previous_ms") val startAfterPreviousMs: Double,
    @SerialName("uplink_frames") val uplinkFrames: Int,
    @SerialName("uplink_frame_bytes") val uplinkFrameBytes: Int,
    @SerialName("response_wait_ms") val responseWaitMs: Double,
    @SerialName("planned_downlink_frames") val plannedDownlinkFrames: Int,
    @SerialName("downlink_frames_before_stop") val downlinkFramesBeforeStop: Int,
    @SerialName("downlink_frame_bytes") val downlinkFrameBytes: Int,
    val interrupted: Boolean,
    @SerialName("barge_in_after_frames") val bargeInAfterFrames: Int? = null,
    @SerialName("expected_stop_within_ms") val expectedStopWithinMs: Int? = null,
    @SerialName("speech_ms") val speechMs: Double,
    @SerialName("commit_mode") val commitMode: String,
    @SerialName("planned_duration_ms") val plannedDurationMs: Double,
)

@Serializable
data class RealtimeRuntimeSession(
    @SerialName("session_id") val sessionId: String,
    @SerialName("start_after_previous_ms") val startAfterPreviousMs: Double,
    @SerialName("setup_ms") val setupMs: Double,
    @SerialName("frame_ms") val frameMs: Int,
    @SerialName("turn_count") val turnCount: Int,
    val turns: List<RealtimeRuntimeTurn>,
    @SerialName("planned_duration_ms") val plannedDurationMs: Double,
    @SerialName("controlled_disconnect_after_turn") val controlledDisconnectAfterTurn: Int? = null,
)

@Serializable
data class RealtimeRuntimePlan(
    @SerialName("contract_version") val contractVersion: String,
    @SerialName("model_id") val modelId: String,
    @SerialName("model_version") val modelVersion: String,
    @SerialName("model_hash") val modelHash: String,
    @SerialName("calibration_status") val calibrationStatus: String,
    val seed: Long,
    val variant: String,
    @SerialName("recovery_probe_contract") val recoveryProbeContract: String? = null,
    @SerialName("session_count") val sessionCount: Int,
    val sessions: List<RealtimeRuntimeSession>,
    val claim: String,
)

data class LoadedRealtimeRuntime(
    val profile: ScenarioProfile,
    val plan: RealtimeRuntimePlan,
)

class RealtimeRuntimeRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(variant: String): LoadedRealtimeRuntime = withContext(Dispatchers.IO) {
        require(variant in setOf("quick", "standard", "recovery")) { "unsupported_realtime_variant:$variant" }
        val base = "published/ai_realtime_voice_$variant"
        val profileText = context.assets.open("$base/profile.json").use { it.readBytes().toString(Charsets.UTF_8) }
        val planText = context.assets.open("$base/runtime_plan.json").use { it.readBytes().toString(Charsets.UTF_8) }
        val profile = ProfileParser.parseSingle(profileText)
        val capability = ProfileCapability.assess(profile)
        require(capability.executable) {
            "realtime_profile_not_executable:${(capability.contractIssues + capability.unsupportedPhaseTypes).joinToString("|")}"
        }
        val execution = requireNotNull(profile.executionPlan) { "realtime_execution_plan_missing" }
        require(TokenRuntimeIntegrity.canonicalSha256(planText) == execution.artifactHash) { "realtime_runtime_hash_mismatch" }
        val plan = json.decodeFromString(RealtimeRuntimePlan.serializer(), planText)
        validateBinding(profile, plan)
        LoadedRealtimeRuntime(profile, plan)
    }

    private fun validateBinding(profile: ScenarioProfile, plan: RealtimeRuntimePlan) {
        val execution = requireNotNull(profile.executionPlan)
        require(plan.contractVersion == execution.contractVersion) { "realtime_runtime_contract_mismatch" }
        require(plan.modelId == profile.business.behaviorModelId) { "realtime_runtime_model_id_mismatch" }
        require(plan.modelVersion == profile.business.behaviorModelVersion) { "realtime_runtime_model_version_mismatch" }
        require(plan.modelHash == profile.business.behaviorModelHash) { "realtime_runtime_model_hash_mismatch" }
        require(plan.calibrationStatus == profile.business.calibrationStatus) { "realtime_runtime_calibration_mismatch" }
        require(plan.seed == execution.seed && plan.variant == execution.variant) { "realtime_runtime_seed_or_variant_mismatch" }
        if (plan.variant == "recovery") {
            require(plan.recoveryProbeContract == "fixed_model_derived_minimum_speech_plus_wait_v1") {
                "realtime_runtime_recovery_probe_contract_invalid"
            }
        } else {
            require(plan.recoveryProbeContract == null) { "realtime_runtime_unexpected_recovery_probe_contract" }
        }
        require(plan.sessionCount == plan.sessions.size && plan.sessions.isNotEmpty()) { "realtime_runtime_session_count_invalid" }
        plan.sessions.forEach { session ->
            require(session.sessionId.isNotBlank() && session.frameMs in 10..100) { "realtime_runtime_session_invalid" }
            require(session.turnCount == session.turns.size && session.turns.isNotEmpty()) { "realtime_runtime_turn_count_invalid" }
            if (plan.variant == "recovery") {
                require(
                    session.controlledDisconnectAfterTurn == null ||
                        session.controlledDisconnectAfterTurn in session.turns.indices,
                ) { "realtime_runtime_controlled_disconnect_invalid" }
            } else {
                require(session.controlledDisconnectAfterTurn == null) { "realtime_runtime_unexpected_controlled_disconnect" }
            }
            session.turns.forEachIndexed { index, turn ->
                require(turn.turnIndex == index && turn.turnId.isNotBlank()) { "realtime_runtime_turn_identity_invalid" }
                require(turn.uplinkFrames > 0 && turn.plannedDownlinkFrames > 0) { "realtime_runtime_frame_count_invalid" }
                require(turn.uplinkFrameBytes > 0 && turn.downlinkFrameBytes > 0) { "realtime_runtime_frame_size_invalid" }
                require(turn.commitMode in setOf("vad", "manual")) { "realtime_runtime_commit_mode_invalid" }
                if (turn.interrupted) {
                    require(turn.bargeInAfterFrames != null && turn.bargeInAfterFrames in 1 until turn.plannedDownlinkFrames) { "realtime_runtime_barge_invalid" }
                    require(turn.expectedStopWithinMs != null && turn.expectedStopWithinMs > 0) { "realtime_runtime_stop_target_invalid" }
                } else {
                    require(turn.bargeInAfterFrames == null && turn.expectedStopWithinMs == null) { "realtime_runtime_unexpected_barge" }
                }
            }
        }
        if (plan.variant == "recovery") {
            val faultIndexes = plan.sessions.indices.filter { plan.sessions[it].controlledDisconnectAfterTurn != null }
            require(faultIndexes.size >= 2) {
                "realtime_runtime_recovery_samples_insufficient"
            }
            require(
                faultIndexes.all { index ->
                    index + 1 in plan.sessions.indices &&
                        plan.sessions[index + 1].controlledDisconnectAfterTurn == null
                },
            ) { "realtime_runtime_recovery_pairing_invalid" }
        }
    }
}
