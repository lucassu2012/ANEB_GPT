package com.aneb.probe.engine

import android.content.Context
import java.security.MessageDigest
import java.math.BigDecimal
import java.math.BigInteger
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
    /** Exact canonical digest of the Profile bytes parsed for this run. */
    val profileHash: String,
    /** Exact canonical digest already matched against profile.execution_plan.artifact_hash. */
    val runtimeArtifactHash: String,
    val profileAssetUri: String,
    val runtimeAssetUri: String,
)

/** Loads the generated App artifact and fails closed on any profile/model/hash drift. */
class TokenRuntimeRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(variant: String): LoadedTokenRuntime = withContext(Dispatchers.IO) {
        require(variant in setOf("quick", "standard", "stress")) { "unsupported_token_variant:$variant" }
        val base = "published/token_multimodal_$variant"
        val profileText = context.assets.open("$base/profile.json").use { it.readBytes().toString(Charsets.UTF_8) }
        val planText = context.assets.open("$base/runtime_plan.json").use { it.readBytes().toString(Charsets.UTF_8) }
        val manifestText = context.assets.open("$base/manifest.sha256")
            .use { it.readBytes().toString(Charsets.UTF_8) }
        val manifest = TokenRuntimeManifestIntegrity.verify(manifestText, profileText, planText)

        val profile = ProfileParser.parseSingle(profileText)
        val capability = ProfileCapability.assess(profile)
        require(capability.executable) {
            "token_profile_not_executable:${(capability.contractIssues + capability.unsupportedPhaseTypes).joinToString("|")}"
        }
        val execution = requireNotNull(profile.executionPlan) { "token_execution_plan_missing" }
        val profileHash = manifest.profileSha256
        val actualHash = manifest.runtimePlanSha256
        require(actualHash == execution.artifactHash) { "token_runtime_hash_mismatch" }
        val plan = json.decodeFromString(TokenRuntimePlan.serializer(), planText)
        TokenRuntimeBinding.validate(profile, plan, variant)
        LoadedTokenRuntime(
            profile = profile,
            plan = plan,
            profileHash = profileHash,
            runtimeArtifactHash = actualHash,
            profileAssetUri = "asset:///$base/profile.json",
            runtimeAssetUri = "asset:///$base/runtime_plan.json",
        )
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
        is JsonPrimitive -> canonicalPrimitive(element)
    }

    /** Matches Python json.dumps(..., sort_keys=True, separators=(",", ":"), ensure_ascii=False). */
    private fun canonicalPrimitive(value: JsonPrimitive): String {
        if (value.isString) {
            return Json.encodeToString(JsonPrimitive.serializer(), value)
        }
        val content = value.content
        if (content == "true" || content == "false" || content == "null") return content
        return if (content.contains('.') || content.contains('e', ignoreCase = true)) {
            canonicalPythonFloat(content)
        } else {
            BigInteger(content).toString()
        }
    }

    private fun canonicalPythonFloat(content: String): String {
        val number = content.toDouble()
        require(number.isFinite()) { "canonical_json_non_finite_number" }
        if (number == 0.0) {
            return if (java.lang.Double.doubleToRawLongBits(number) < 0) "-0.0" else "0.0"
        }

        // BigDecimal.valueOf starts from Java's shortest round-tripping decimal. Python and
        // Java agree on the digits; their exponent thresholds and spelling differ.
        val decimal = BigDecimal.valueOf(number).stripTrailingZeros()
        val exponent = decimal.precision() - decimal.scale() - 1
        if (exponent < -4 || exponent >= 16) {
            val digits = decimal.unscaledValue().abs().toString()
            val mantissa = if (digits.length == 1) digits else digits.first() + "." + digits.drop(1)
            val exponentText = kotlin.math.abs(exponent).toString().padStart(2, '0')
            val sign = if (exponent < 0) "-" else "+"
            return (if (decimal.signum() < 0) "-" else "") + mantissa + "e" + sign + exponentText
        }
        val plain = decimal.toPlainString()
        return if (plain.contains('.')) plain else "$plain.0"
    }
}
