package com.aneb.probe.engine

import android.content.Context
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class TokenRuntimeUpload(
    @SerialName("payload_bytes") val payloadBytes: Long,
    @SerialName("chunk_bytes") val chunkBytes: Int,
    @SerialName("chunk_cadence_ms") val chunkCadenceMs: Double,
)

@Serializable
data class TokenRuntimeStream(
    @SerialName("intervals_ms") val intervalsMs: List<Double>,
    @SerialName("sizes_bytes") val sizesBytes: List<Int>,
)

@Serializable
data class TokenRuntimeTask(
    @SerialName("task_id") val taskId: String,
    @SerialName("workload_kind") val workloadKind: String,
    val repetition: Int,
    @SerialName("start_after_previous_ms") val startAfterPreviousMs: Double,
    val upload: TokenRuntimeUpload,
    @SerialName("processing_ms") val processingMs: Double,
    @SerialName("token_stream") val tokenStream: TokenRuntimeStream,
    @SerialName("response_artifact_bytes") val responseArtifactBytes: Long,
    @SerialName("planned_duration_ms") val plannedDurationMs: Double,
)

@Serializable
data class TokenRuntimePlan(
    @SerialName("contract_version") val contractVersion: String,
    @SerialName("model_id") val modelId: String,
    @SerialName("model_version") val modelVersion: String,
    @SerialName("model_hash") val modelHash: String,
    @SerialName("calibration_status") val calibrationStatus: String,
    val seed: Long,
    val variant: String,
    @SerialName("task_count") val taskCount: Int,
    val tasks: List<TokenRuntimeTask>,
    val claim: String,
)

data class LoadedTokenRuntime(
    val profile: ScenarioProfile,
    val plan: TokenRuntimePlan,
)

/** Loads the generated App artifact and fails closed on any profile/model/hash drift. */
class TokenRuntimeRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(variant: String): LoadedTokenRuntime = withContext(Dispatchers.IO) {
        require(variant in setOf("quick", "standard", "stress")) { "unsupported_token_variant:$variant" }
        val base = "published/token_multimodal_$variant"
        val profileText = context.assets.open("$base/profile.json").use { it.readBytes().toString(Charsets.UTF_8) }
        val planText = context.assets.open("$base/runtime_plan.json").use { it.readBytes().toString(Charsets.UTF_8) }
        val profile = ProfileParser.parseSingle(profileText)
        val capability = ProfileCapability.assess(profile)
        require(capability.executable) {
            "token_profile_not_executable:${(capability.contractIssues + capability.unsupportedPhaseTypes).joinToString("|")}"
        }
        val execution = requireNotNull(profile.executionPlan) { "token_execution_plan_missing" }
        val actualHash = TokenRuntimeIntegrity.canonicalSha256(planText)
        require(actualHash == execution.artifactHash) { "token_runtime_hash_mismatch" }
        val plan = json.decodeFromString(TokenRuntimePlan.serializer(), planText)
        validateBinding(profile, plan)
        LoadedTokenRuntime(profile, plan)
    }

    private fun validateBinding(profile: ScenarioProfile, plan: TokenRuntimePlan) {
        val execution = requireNotNull(profile.executionPlan)
        require(plan.contractVersion == execution.contractVersion) { "token_runtime_contract_mismatch" }
        require(plan.modelId == profile.business.behaviorModelId) { "token_runtime_model_id_mismatch" }
        require(plan.modelVersion == profile.business.behaviorModelVersion) { "token_runtime_model_version_mismatch" }
        require(plan.modelHash == profile.business.behaviorModelHash) { "token_runtime_model_hash_mismatch" }
        require(plan.calibrationStatus == profile.business.calibrationStatus) { "token_runtime_calibration_mismatch" }
        require(plan.seed == execution.seed && plan.variant == execution.variant) { "token_runtime_seed_or_variant_mismatch" }
        require(plan.taskCount == plan.tasks.size && plan.tasks.isNotEmpty()) { "token_runtime_task_count_invalid" }
        plan.tasks.forEach { task ->
            require(task.taskId.isNotBlank() && task.workloadKind in setOf("text", "document", "image", "video")) { "token_runtime_task_identity_invalid" }
            require(task.upload.payloadBytes > 0 && task.upload.chunkBytes > 0) { "token_runtime_upload_invalid" }
            require(task.tokenStream.intervalsMs.isNotEmpty()) { "token_runtime_stream_empty" }
            require(task.tokenStream.intervalsMs.size == task.tokenStream.sizesBytes.size) { "token_runtime_stream_length_mismatch" }
            require(task.tokenStream.intervalsMs.first() == 0.0) { "token_runtime_first_interval_not_zero" }
            require(task.tokenStream.intervalsMs.all { it >= 0.0 } && task.tokenStream.sizesBytes.all { it > 0 }) { "token_runtime_stream_value_invalid" }
        }
    }

}

internal object TokenRuntimeIntegrity {
    private val json = Json { ignoreUnknownKeys = true }

    fun canonicalSha256(text: String): String {
        val canonical = canonicalJson(json.parseToJsonElement(text))
        return "sha256:" + MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun canonicalJson(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries.sortedBy { it.key }.joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
            Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(key)) + ":" + canonicalJson(value)
        }
        is JsonArray -> element.joinToString(prefix = "[", postfix = "]", separator = ",") { canonicalJson(it) }
        is JsonPrimitive -> element.toString()
    }
}
