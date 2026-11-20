package com.aneb.probe.engineering

import com.aneb.probe.data.VoiceResultRepository
import com.aneb.probe.engine.VoiceRunner
import com.aneb.probe.scoring.AqsScorer
import com.aneb.probe.ui.VoiceFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * Pure-JVM contract & canonical `raw.json` builder for Profile 4 synthetic voice correlation (M2.3 / F10).
 * Debug-only; never included in `release` or `prototypeRelease`, zero Room v15 or `AqsScorer` edits.
 */
object Profile4VoiceCorrelationContract {

    const val FEATURE_RELEASE: String = "profile4-voice-correlation-1"
    const val RAW_FORMAT: String = "profile4-voice-correlation-raw-1"
    const val MIW2_HOST: String = "120.77.174.207"
    const val MIW2_BASE_URL: String = "https://120.77.174.207:8443"
    const val PHASE_DEADLINE_NANOS: Long = 105_000_000_000L
    const val EXPECTED_TURNS_OK: Int = 8

    val VALID_CONDITIONS: Set<String> = setOf("R", "B1", "B2")

    val KEYS: List<String> = listOf(
        "feature_release",
        "live_authorized",
        "condition_id",
        "run_id",
        "expected_phone_ipv4",
        "fixed_remote_ipv4",
        "card_sha256",
        "source_commit",
        "target_apk_sha256",
        "test_apk_sha256",
    )

    private val prettyJson = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    data class Spec(
        val runId: String,
        val conditionId: String,
        val phoneIp: String,
        val remoteIp: String,
        val cardSha: String,
        val sourceCommit: String,
        val targetSha: String,
        val testSha: String,
    )

    data class PhaseEvent(
        val name: String,
        val nanos: Long,
        val detail: String = "",
    )

    fun parse(args: Map<String, String?>): Spec {
        require(!args.containsKey("url")) { "ARBITRARY_URL_FORBIDDEN" }
        require(args["feature_release"] == FEATURE_RELEASE && args["live_authorized"] == "true") {
            "RELEASE_REQUIRED"
        }
        val conditionId = args["condition_id"]
        require(conditionId in VALID_CONDITIONS) { "CONDITION_INVALID" }
        val runId = args["run_id"]
        require(runId != null && runId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "RUN_ID_INVALID" }
        val phoneIp = args["expected_phone_ipv4"]
        require(phoneIp != null && isValidIpv4Literal(phoneIp)) { "IPV4_REQUIRED" }
        val remote = args["fixed_remote_ipv4"]
        if (remote != null) {
            require(remote == MIW2_HOST) { "MIW2_HOST_PIN_MISMATCH" }
        }
        fun hex(key: String, size: Int): String {
            val value = args[key]
            require(value != null && value.matches(Regex("[0-9a-f]{$size}"))) { "${key}_INVALID" }
            return value
        }
        return Spec(
            runId = runId,
            conditionId = checkNotNull(conditionId),
            phoneIp = phoneIp,
            remoteIp = MIW2_HOST,
            cardSha = hex("card_sha256", 64),
            sourceCommit = hex("source_commit", 40),
            targetSha = hex("target_apk_sha256", 64),
            testSha = hex("test_apk_sha256", 64),
        )
    }

    fun isValidIpv4Literal(raw: String): Boolean {
        if (!raw.matches(Regex("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}"))) {
            return false
        }
        return raw.split('.').all { it.toIntOrNull() in 0..255 }
    }

    fun buildRawPayloadJson(
        spec: Spec,
        startedNanos: Long,
        finishedNanos: Long,
        deadlineNanos: Long,
        tsEpochMs: Long,
        deviceModel: String,
        androidApi: Int,
        packageName: String?,
        versionCode: Long?,
        versionName: String?,
        targetApkSha256: String?,
        testApkSha256: String?,
        guardMetadata: Map<String, String>,
        guardReasons: List<String>,
        networkHandle: String?,
        networkId: String?,
        interfaceName: String?,
        localAddresses: List<String>,
        postNetwork: JsonObject?,
        phaseEvents: List<PhaseEvent>,
        cleanup: JsonObject,
        termination: String,
        failure: String?,
        pathInvalidReason: String?,
        doneSample: VoiceRunner.Sample?,
    ): String {
        val entity = doneSample?.let { VoiceResultRepository.toEntity(it, tsEpochMs) }
        val voiceExportElement: JsonElement = if (entity != null) {
            Json.parseToJsonElement(VoiceFormat.buildJson(listOf(entity))).jsonObject
        } else {
            JsonNull
        }
        val aqsResult: AqsScorer.AqsResult? = doneSample?.let { VoiceFormat.scoreSample(it) }
        val aqsElement: JsonElement = if (aqsResult != null) {
            buildJsonObject {
                put("aqs_version", aqsResult.aqsVersion)
                put("kpi_set_version", aqsResult.kpiSetVersion)
                putNullableDouble("score", aqsResult.score)
                put("veto_applied", aqsResult.vetoApplied)
                put("low_confidence", aqsResult.lowConfidence)
                putNullableString("not_computable_reason", aqsResult.notComputableReason)
                put(
                    "sub_scores",
                    buildJsonObject {
                        for (k in aqsResult.subScores.keys.sorted()) {
                            putNullableDouble(k, aqsResult.subScores[k])
                        }
                    },
                )
            }
        } else {
            JsonNull
        }

        val durationMs = ((finishedNanos - startedNanos).coerceAtLeast(0L)) / 1_000_000.0
        val payload = buildJsonObject {
            put("format", RAW_FORMAT)
            put("feature_release", FEATURE_RELEASE)
            put("run_id", spec.runId)
            put("condition_id", spec.conditionId)
            put("miw2_base_url", MIW2_BASE_URL)
            put("fixed_remote_ipv4", spec.remoteIp)
            put("expected_phone_ipv4", spec.phoneIp)
            put("declared_card_sha256", spec.cardSha)
            put("declared_source_commit", spec.sourceCommit)
            put("clock", "android_elapsed_realtime_nanos")
            put("started_nanos", startedNanos)
            put("finished_nanos", finishedNanos)
            put("duration_ms", formatDecimal(durationMs))
            put("absolute_deadline_nanos", deadlineNanos)
            put("ts_epoch_ms", tsEpochMs)
            put("device_model", deviceModel)
            put("android_api", androidApi)
            putNullableString("package", packageName)
            putNullableLong("version_code", versionCode)
            putNullableString("version_name", versionName)
            putNullableString("target_apk_sha256", targetApkSha256)
            putNullableString("test_apk_sha256", testApkSha256)
            put("business_sample", false)
            put(
                "guard",
                buildJsonObject {
                    for ((k, v) in guardMetadata) put(k, v)
                },
            )
            put(
                "guard_reasons",
                buildJsonArray {
                    for (r in guardReasons) add(JsonPrimitive(r))
                },
            )
            putNullableString("network_handle", networkHandle)
            putNullableString("network_id", networkId)
            putNullableString("interface", interfaceName)
            put(
                "local_addresses",
                buildJsonArray {
                    for (addr in localAddresses) add(JsonPrimitive(addr))
                },
            )
            put("post_network", postNetwork ?: JsonNull)
            put("termination", termination)
            putNullableString("failure", failure)
            putNullableString("path_invalid_reason", pathInvalidReason)
            put("cleanup", cleanup)
            put(
                "phase_events",
                buildJsonArray {
                    for (ev in phaseEvents) {
                        add(
                            buildJsonObject {
                                put("name", ev.name)
                                put("nanos", ev.nanos)
                                put("detail", ev.detail)
                            },
                        )
                    }
                },
            )
            put("voice_export", voiceExportElement)
            put("aqs_v02", aqsElement)
        }
        return prettyJson.encodeToString(JsonObject.serializer(), payload) + "\n"
    }

    private fun formatDecimal(value: Double): Double =
        if (value.isFinite()) String.format(Locale.ROOT, "%.3f", value).toDouble() else 0.0

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullableString(key: String, value: String?) {
        if (value == null) put(key, JsonNull) else put(key, value)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullableLong(key: String, value: Long?) {
        if (value == null) put(key, JsonNull) else put(key, value)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullableDouble(key: String, value: Double?) {
        val finite = value?.takeIf { it.isFinite() }
        if (finite == null) put(key, JsonNull) else put(key, finite)
    }
}
