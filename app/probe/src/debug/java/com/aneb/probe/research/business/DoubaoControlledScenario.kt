package com.aneb.probe.research.business

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DoubaoScenarioDefinition(
    val contractVersion: String,
    val profileId: String,
    val version: String,
    val packageName: String,
    val appVersionPolicy: String,
    val scenario: String,
    val prompt: String,
    val observeMs: Long,
    val captureBackend: String,
    val visualMetric: String,
    val recovery: String,
    val exitPolicy: String,
)

data class DoubaoActionPlan(
    val definition: DoubaoScenarioDefinition,
    val profileSourceSha256: String,
) {
    val profileId: String get() = definition.profileId
    val version: String get() = definition.version
}

@Serializable
data class DoubaoRunBinding(
    val runId: String,
    val profileSha256: String,
    val appVersionCode: Long,
    val conditionId: String,
    val networkKey: String,
    val clockId: String,
    val uiObservationMethod: String,
    val hostNetworkCondition: VideoHostNetworkBinding? = null,
)

object DoubaoScenarioProfile {
    private val json = Json { ignoreUnknownKeys = false }

    fun compile(source: String): DoubaoActionPlan {
        require(source.toByteArray(Charsets.UTF_8).size in 1..65_536) { "doubao_profile_size_invalid" }
        val profile = json.decodeFromString(DoubaoScenarioDefinition.serializer(), source)
        require(profile.contractVersion == "aneb-ai-business-profile-1") { "doubao_profile_contract_unsupported" }
        require(
            profile.profileId == "doubao-chat-single-send" &&
                profile.packageName == "com.larus.nova" &&
                profile.scenario == "SINGLE_PROMPT_SEND",
        ) { "doubao_profile_scenario_unsupported" }
        require(profile.version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { "doubao_profile_version_invalid" }
        require(profile.appVersionPolicy == "PIN_AT_FIRST_ADMISSION") { "doubao_profile_admission_unsupported" }
        require(profile.prompt.isNotBlank()) { "doubao_profile_prompt_missing" }
        require(profile.observeMs in 1_000L..60_000L) { "doubao_profile_observe_ms_invalid" }
        require(profile.captureBackend == "android_media_projection_v1") { "doubao_profile_capture_backend_unsupported" }
        require(profile.recovery == "STOP_NO_RETRY" && profile.exitPolicy == "OWNED_ONLY_LAUNCHER_RESTORE") {
            "doubao_profile_stop_policy_invalid"
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return DoubaoActionPlan(profile, sha)
    }
}

/** Pure-JVM testable controlled R/B execution state machine for Doubao (MediaProjection 30fps). */
class DoubaoControlledScenario(
    private val phone: Phone,
    private val nowNs: () -> Long,
) {
    interface Phone {
        fun prepare(deadlineNs: Long)
        fun startRecording(deadlineNs: Long)
        fun saveOriginals(label: String)
        fun sendOnce(): Boolean
        fun observeUntil(deadlineNs: Long)
        fun stopRecording()
        fun cleanupOwned(deadlineNs: Long): Boolean
    }

    fun runOnce(profileSource: String, binding: DoubaoRunBinding, directory: File): VideoScenarioResult {
        val plan = DoubaoScenarioProfile.compile(profileSource)
        require(binding.runId.matches(Regex("[A-Za-z0-9_-]{1,64}")) && directory.name == binding.runId) {
            "DOUBAO_RUN_ID_INVALID"
        }
        require(binding.profileSha256 == plan.profileSourceSha256 && binding.appVersionCode > 0L) {
            "DOUBAO_BINDING_MISMATCH"
        }
        require(binding.conditionId in setOf("R", "B") && binding.networkKey.isNotBlank() && binding.clockId.isNotBlank()) {
            "DOUBAO_CONDITION_OR_NETWORK_INVALID"
        }
        require(binding.uiObservationMethod.isNotBlank()) { "DOUBAO_UI_OBSERVATION_METHOD_REQUIRED" }
        binding.hostNetworkCondition?.let { host ->
            require(host.topologyId.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "DOUBAO_HOST_TOPOLOGY_ID_INVALID" }
            require(host.conditionMode in setOf("inspect", "limit")) { "DOUBAO_HOST_CONDITION_MODE_INVALID" }
            require(host.requestSha256.matches(Regex("[0-9a-f]{64}"))) { "DOUBAO_HOST_REQUEST_SHA_INVALID" }
            host.uplinkInterfaceIndex?.let { require(it > 0) { "DOUBAO_HOST_UPLINK_IF_INVALID" } }
            host.apInterfaceIndex?.let { require(it > 0) { "DOUBAO_HOST_AP_IF_INVALID" } }
            if (host.uplinkInterfaceIndex != null && host.apInterfaceIndex != null) {
                require(host.uplinkInterfaceIndex != host.apInterfaceIndex) { "DOUBAO_HOST_UPLINK_EQUALS_AP_IF" }
            }
            host.downlinkIpBytesPerSecond?.let { require(it in 1L..1_000_000_000L) { "DOUBAO_HOST_RATE_INVALID" } }
        }
        check(directory.mkdir()) { "DOUBAO_ATTEMPT_ALREADY_CONSUMED" }

        val codec = Json { encodeDefaults = true; prettyPrint = true }
        fun writeNew(name: String, text: String) {
            val file = File(directory, name)
            check(file.createNewFile()) { "DOUBAO_ORIGINAL_ALREADY_EXISTS" }
            FileOutputStream(file).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        }
        writeNew("profile.json", profileSource)
        writeNew("binding.json", codec.encodeToString(DoubaoRunBinding.serializer(), binding))

        val events = mutableListOf<VideoOperationalEvent>()
        fun event(name: String): Long = nowNs().also { events += VideoOperationalEvent(name, it) }
        val preparationEnd = event("preparation_started") + 360_000_000_000L

        var category = "TOOL_FAILURE"
        var reason: String? = null
        var calls = "0"
        var captured = false
        var cleaned = false

        fun stop(kind: String, code: String): Nothing = throw Stop(kind, code)
        fun checkLive() {
            if (File(directory, "CANCEL").exists()) stop("CANCELLED", "OWNED_CANCEL_REQUESTED")
        }

        try {
            phone.prepare(preparationEnd)
            checkLive()
            check(nowNs() < preparationEnd) { "PREPARATION_DEADLINE_EXPIRED" }

            phone.startRecording(minOf(preparationEnd, nowNs() + 20_000_000_000L))
            event("recording_started")
            checkLive()
            check(nowNs() < preparationEnd) { "PREPARATION_DEADLINE_EXPIRED" }

            phone.saveOriginals("pre-send")
            checkLive()
            check(nowNs() < preparationEnd) { "PREPARATION_DEADLINE_EXPIRED" }

            writeNew("SEND_INTENT.json", "{\"run_id\":\"${binding.runId}\",\"state\":\"consumed_before_invocation\"}\n")
            checkLive()
            calls = "UNKNOWN"
            event("send_invocation_started")
            if (!phone.sendOnce()) stop("OPERATION_UNKNOWN", "SEND_RETURNED_FALSE_NO_RETRY")
            calls = "1"
            val sendReturned = event("send_invocation_returned")

            phone.observeUntil(sendReturned + plan.definition.observeMs * 1_000_000L)
            event("observation_window_ended")

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
            reason = failure.message ?: failure.javaClass.simpleName
            event("failure_retained")
        } finally {
            cleaned = try {
                phone.cleanupOwned(nowNs() + 210_000_000_000L)
            } catch (_: Exception) {
                false
            }
            event("owned_cleanup_checked")
        }

        val result = VideoScenarioResult(
            runId = binding.runId,
            profileSha256 = binding.profileSha256,
            conditionId = binding.conditionId,
            clockId = binding.clockId,
            state = if (captured && cleaned) "CAPTURED_AWAITING_REVIEW" else "STOPPED",
            category = category,
            reason = reason ?: if (!cleaned) "OWNED_CLEANUP_UNCONFIRMED" else null,
            swipeCalls = calls,
            ownedCleanup = cleaned,
            events = events,
            frames = emptyList(),
            preInputRejection = null,
            uiObservationMethod = binding.uiObservationMethod,
            hostNetworkCondition = binding.hostNetworkCondition,
        )
        writeNew("result.json", codec.encodeToString(VideoScenarioResult.serializer(), result))
        return result
    }

    private class Stop(val category: String, code: String) : Exception(code)
}
