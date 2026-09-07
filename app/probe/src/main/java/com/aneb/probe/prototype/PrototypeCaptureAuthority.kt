package com.aneb.probe.prototype

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.time.format.DateTimeParseException

/** Builds the canonical persisted authority captured by the Android prototype client. */
internal object PrototypeCaptureAuthority {
    data class CampaignCapture(
        val campaignId: String,
        val campaignStartedAtUtc: String,
        val campaignEndedAtUtc: String,
        val sourceCommit: String,
        val androidVersionName: String,
        val androidVersionCode: Int,
        val apkSha256: String,
        val deviceManufacturer: String,
        val deviceModel: String,
        val deviceOsRelease: String,
        val deviceSdkInt: Int,
        val transportKind: String,
    )

    enum class RunState {
        COMPLETE,
        INTERRUPTED,
        INVALID,
        CANCELLED,
        NOT_STARTED,
    }

    data class RunCapture(
        val campaignId: String,
        val runId: String,
        val runIndex: Int,
        val state: RunState,
        val clockDomainId: String,
        val t0Nanos: Long?,
        val attemptStartedAtUtc: String?,
        val attemptEndedAtUtc: String?,
    )

    fun buildCampaign(capture: CampaignCapture): String {
        requireValidCampaignCapture(capture)
        return buildJsonObject {
            put("schema_version", JsonPrimitive("aneb-prototype-capture-authority-0.1"))
            put("campaign_id", JsonPrimitive(capture.campaignId))
            put("campaign_started_at_utc", JsonPrimitive(capture.campaignStartedAtUtc))
            put("campaign_ended_at_utc", JsonPrimitive(capture.campaignEndedAtUtc))
            put("product", buildJsonObject {
                put("version", JsonPrimitive("prototype-0.1"))
                put("source_commit", JsonPrimitive(capture.sourceCommit))
            })
            put("android", buildJsonObject {
                put("version_name", JsonPrimitive(capture.androidVersionName))
                put("version_code", JsonPrimitive(capture.androidVersionCode))
                put("apk_sha256", JsonPrimitive(capture.apkSha256))
            })
            put("device", buildJsonObject {
                put("manufacturer", JsonPrimitive(capture.deviceManufacturer))
                put("model", JsonPrimitive(capture.deviceModel))
                put("os_release", JsonPrimitive(capture.deviceOsRelease))
                put("sdk_int", JsonPrimitive(capture.deviceSdkInt))
            })
            put("transport", buildJsonObject {
                put("kind", JsonPrimitive(capture.transportKind))
                put("acceptance_eligible", JsonPrimitive(capture.transportKind == "lan"))
            })
        }.toString()
    }

    fun buildRun(capture: RunCapture): String {
        requireValidRunCapture(capture)

        return buildJsonObject {
            put("schema_version", JsonPrimitive("aneb-prototype-run-authority-0.1"))
            put("campaign_id", JsonPrimitive(capture.campaignId))
            put("run_id", JsonPrimitive(capture.runId))
            put("run_index", JsonPrimitive(capture.runIndex))
            put("clock_domain_id", JsonPrimitive(capture.clockDomainId))
            put("t0_nanos", capture.t0Nanos?.let(::JsonPrimitive) ?: JsonNull)
            put(
                "attempt_started_at_utc",
                capture.attemptStartedAtUtc?.let(::JsonPrimitive) ?: JsonNull,
            )
            put(
                "attempt_ended_at_utc",
                capture.attemptEndedAtUtc?.let(::JsonPrimitive) ?: JsonNull,
            )
        }.toString()
    }

    private fun requireValidRunCapture(capture: RunCapture) {
        require(capture.campaignId.isNotBlank())
        require(capture.runId.isNotBlank())
        require(capture.runIndex >= 1)
        require(capture.clockDomainId.isNotBlank())

        if (capture.state == RunState.NOT_STARTED) {
            require(capture.t0Nanos == null)
            require(capture.attemptStartedAtUtc == null)
            require(capture.attemptEndedAtUtc == null)
            return
        }

        require(requireNotNull(capture.t0Nanos) >= 0L)
        val startedAt = requireCanonicalInstant(requireNotNull(capture.attemptStartedAtUtc))
        val endedAt = requireCanonicalInstant(requireNotNull(capture.attemptEndedAtUtc))
        require(!endedAt.isBefore(startedAt))
    }

    private fun requireValidCampaignCapture(capture: CampaignCapture) {
        requireSafeText(capture.campaignId)
        val startedAt = requireCanonicalInstant(capture.campaignStartedAtUtc)
        val endedAt = requireCanonicalInstant(capture.campaignEndedAtUtc)
        require(!endedAt.isBefore(startedAt))
        require(LOWERCASE_COMMIT.matches(capture.sourceCommit))
        requireSafeText(capture.androidVersionName)
        require(capture.androidVersionCode >= 1)
        require(LOWERCASE_SHA256.matches(capture.apkSha256))
        requireSafeText(capture.deviceManufacturer)
        requireSafeText(capture.deviceModel)
        requireSafeText(capture.deviceOsRelease)
        require(capture.deviceSdkInt >= 1)
        require(capture.transportKind == "lan" || capture.transportKind == "adb_reverse")
    }

    private fun requireSafeText(value: String) {
        require(value.isNotBlank() && '\r' !in value && '\n' !in value && '\u0000' !in value)
    }

    private fun requireCanonicalInstant(value: String): Instant {
        val parsed = try {
            Instant.parse(value)
        } catch (error: DateTimeParseException) {
            throw IllegalArgumentException(error)
        }
        require(parsed.toString() == value)
        return parsed
    }

    private val LOWERCASE_COMMIT = Regex("^[0-9a-f]{40}$")
    private val LOWERCASE_SHA256 = Regex("^[0-9a-f]{64}$")
}
