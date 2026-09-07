package com.aneb.probe.prototype

import com.aneb.probe.data.PrototypeCampaignRoomRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Locale

class PrototypeCanonicalUploadRendererTest {
    @Test
    fun `canonical decimal matches the verifier six-place Python oracle`() {
        assertEquals("", PrototypeCanonicalUploadRenderer.canonicalDecimal(null))
        assertEquals("0", PrototypeCanonicalUploadRenderer.canonicalDecimal(0.0))
        assertEquals("0", PrototypeCanonicalUploadRenderer.canonicalDecimal(-0.0))
        assertEquals("1", PrototypeCanonicalUploadRenderer.canonicalDecimal(1.0))
        assertEquals("1.23456", PrototypeCanonicalUploadRenderer.canonicalDecimal(1.2345605))
        assertEquals(
            "100000000000000000000",
            PrototypeCanonicalUploadRenderer.canonicalDecimal(1.0e20),
        )
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { value ->
            try {
                PrototypeCanonicalUploadRenderer.canonicalDecimal(value)
                fail("non-finite value must be rejected: $value")
            } catch (_: IllegalArgumentException) {
                // Expected: the verifier cannot admit non-finite JSON/CSV numbers.
            }
        }
    }

    @Test
    fun `complete Quick renders the exact canonical Android handoff`() = runBlocking {
        val campaignId = "campaign-canonical-upload-quick"
        val config = PrototypeCampaignPersistenceFixture.campaignConfig(campaignId)
        val result = PrototypeCampaignPersistenceFixture.completeQuickCampaign(config)
        val campaignStartedAt = requireNotNull(result.runs.first().attemptStartedAtUtc)
        val campaignEndedAt = requireNotNull(result.runs.last().attemptEndedAtUtc)
        val campaignAuthority = PrototypeCaptureAuthority.buildCampaign(
            PrototypeCaptureAuthority.CampaignCapture(
                campaignId = campaignId,
                campaignStartedAtUtc = campaignStartedAt,
                campaignEndedAtUtc = campaignEndedAt,
                sourceCommit = SOURCE_COMMIT,
                androidVersionName = "0.2.0",
                androidVersionCode = 20,
                apkSha256 = APK_SHA256,
                deviceManufacturer = "HUAWEI",
                deviceModel = "P40 Pro",
                deviceOsRelease = "12",
                deviceSdkInt = 31,
                transportKind = "lan",
            ),
        )
        val runAuthorities = result.runs.associate { run ->
            val clockDomainId = requireNotNull(run.clockDomainId)
            val startedAt = requireNotNull(run.attemptStartedAtUtc)
            val endedAt = requireNotNull(run.attemptEndedAtUtc)
            run.runId to PrototypeCaptureAuthority.buildRun(
                PrototypeCaptureAuthority.RunCapture(
                    campaignId = campaignId,
                    runId = run.runId,
                    runIndex = run.runIndex,
                    state = PrototypeCaptureAuthority.RunState.COMPLETE,
                    clockDomainId = clockDomainId,
                    t0Nanos = requireNotNull(run.t0MonotonicNanos),
                    attemptStartedAtUtc = startedAt,
                    attemptEndedAtUtc = endedAt,
                ),
            )
        }
        val storedRuns = result.runs.map { run ->
            PrototypeCampaignRoomRepository.StoredRun(
                runIndex = run.runIndex,
                runId = run.runId,
                conditionId = run.conditionId,
                status = run.status,
                taskSuccess = run.taskSuccess,
                scoreEligible = run.scoreEligible,
                eventsExpected = run.eventsExpected,
                eventsReceived = run.eventsReceived,
                failureReason = run.failureReason,
                terminalReceiptValid = run.terminalReceiptValid,
                metrics = run.metrics,
                evidenceEvents = run.evidenceEvents,
                runAuthorityJson = runAuthorities.getValue(run.runId),
            )
        }
        val storedCampaign = PrototypeCampaignRoomRepository.StoredCampaign(
            campaignId = campaignId,
            nodeBaseUrl = config.nodeTicket.nodeBaseUrl,
            runUrl = config.nodeTicket.runUrl,
            capabilityUrl = config.nodeTicket.capabilityUrl,
            rawCapabilityBody = config.nodeTicket.rawCapabilityBody,
            capabilityIdentity = config.nodeTicket.identity,
            summary = result.summary,
            runs = storedRuns,
            captureAuthorityJson = campaignAuthority,
        )
        val lexicalEvidence = storedRuns.flatMap { run ->
            run.evidenceEvents.mapIndexed { ordinal, event ->
                PrototypeCampaignRoomRepository.LexicalEvidence(
                    runIndex = run.runIndex,
                    runId = run.runId,
                    eventOrdinal = ordinal,
                    eventJson = event.toString(),
                )
            }
        }
        val snapshot = PrototypeCampaignRoomRepository.ExportSnapshot(
            campaign = storedCampaign,
            rawCapabilityBody = storedCampaign.rawCapabilityBody,
            lexicalEvidence = lexicalEvidence,
        )

        val rendered = PrototypeCanonicalUploadRenderer.render(snapshot)
        val payload = Json.parseToJsonElement(rendered).jsonObject

        assertEquals(
            setOf(
                "schema_version",
                "campaign_id",
                "meta_json",
                "events_jsonl",
                "runs_csv",
                "summary_csv",
            ),
            payload.keys,
        )
        assertEquals("aneb-prototype-upload-0.1", payload.string("schema_version"))
        assertEquals(campaignId, payload.string("campaign_id"))

        val expectedMeta = expectedMeta(storedCampaign, campaignStartedAt, campaignEndedAt)
        val expectedEvents = lexicalEvidence.joinToString(separator = "") { evidence ->
            "${evidence.eventJson}\n"
        }
        val expectedRuns = expectedRunsCsv(storedCampaign)
        val expectedSummary = expectedSummaryCsv(storedCampaign.summary)
        assertEquals("$expectedMeta\n", payload.string("meta_json"))
        assertEquals(expectedEvents, payload.string("events_jsonl"))
        assertEquals(expectedRuns, payload.string("runs_csv"))
        assertEquals(expectedSummary, payload.string("summary_csv"))
        listOf("meta_json", "events_jsonl", "runs_csv", "summary_csv").forEach { field ->
            val text = payload.string(field)
            assertTrue("$field must end in exactly one LF", text.endsWith("\n"))
            assertFalse("$field must not contain CR", text.contains('\r'))
            assertFalse("$field must not contain NUL", text.contains('\u0000'))
            assertFalse("$field must not contain a BOM", text.startsWith('\uFEFF'))
        }
    }

    private fun expectedMeta(
        campaign: PrototypeCampaignRoomRepository.StoredCampaign,
        campaignStartedAt: String,
        campaignEndedAt: String,
    ): JsonObject {
        val capability = campaign.capabilityIdentity
        return buildJsonObject {
            put("schema_version", JsonPrimitive(capability.evidenceSchemaVersion))
            put("campaign_id", JsonPrimitive(campaign.campaignId))
            put("campaign_mode", JsonPrimitive(campaign.summary.campaignMode))
            put("campaign_status", JsonPrimitive(campaign.summary.status.name.lowercase(Locale.ROOT)))
            put("started_at_utc", JsonPrimitive(campaignStartedAt))
            put("ended_at_utc", JsonPrimitive(campaignEndedAt))
            put("claim_scope", JsonPrimitive(capability.claimScope))
            put("evidence_mode", JsonPrimitive(capability.evidenceMode))
            put("impairment_layer", JsonPrimitive(capability.impairmentLayer))
            put("score_policy_id", JsonPrimitive(capability.scorePolicyId))
            put("profile_manifest_sha256", JsonPrimitive(capability.profileManifestSha256))
            put("clock_contract", buildJsonObject {
                put("source", JsonPrimitive("android.os.SystemClock.elapsedRealtimeNanos"))
                put("unit", JsonPrimitive("ns"))
                put("epoch", JsonPrimitive("device_boot"))
                put("includes_deep_sleep", JsonPrimitive(true))
                put(
                    "domain_identity",
                    JsonPrimitive("per-run opaque boot/session clock_domain_id"),
                )
            })
            put("profile", buildJsonObject {
                put("id", JsonPrimitive(capability.workload.id))
                put("version", JsonPrimitive(capability.workload.version))
                put("sha256", JsonPrimitive(capability.profileManifestSha256))
            })
            put("product", buildJsonObject {
                put("version", JsonPrimitive("prototype-0.1"))
                put("git_commit", JsonPrimitive(SOURCE_COMMIT))
            })
            put("android_app", buildJsonObject {
                put("version_name", JsonPrimitive("0.2.0"))
                put("version_code", JsonPrimitive(20))
                put("apk_sha256", JsonPrimitive(APK_SHA256))
            })
            put("server", buildJsonObject {
                put("version", JsonPrimitive(capability.serverVersion))
                put("binary_sha256", JsonPrimitive(capability.serverBinarySha256))
                put("protocol_version", JsonPrimitive(capability.protocolVersion))
            })
            put("device", buildJsonObject {
                put("manufacturer", JsonPrimitive("HUAWEI"))
                put("model", JsonPrimitive("P40 Pro"))
                put("os_release", JsonPrimitive("12"))
                put("sdk_int", JsonPrimitive(31))
            })
            put("transport", buildJsonObject {
                put("mode", JsonPrimitive("lan"))
                put("acceptance_path", JsonPrimitive(true))
            })
            put("run_plan", buildJsonObject {
                put("planned_runs", JsonPrimitive(campaign.summary.plannedRuns))
                put("order", buildJsonArray {
                    campaign.runs.forEach { run -> add(JsonPrimitive(run.conditionId)) }
                })
            })
            put("conditions", buildJsonArray {
                capability.conditions.forEach { condition ->
                    add(buildJsonObject {
                        put("id", JsonPrimitive(condition.id))
                        put("version", JsonPrimitive(condition.version))
                        put("nominal_interval_ms", JsonPrimitive(condition.nominalIntervalMs))
                        put("schedule_sha256", JsonPrimitive(condition.scheduleSha256))
                    })
                }
            })
        }
    }

    private fun expectedRunsCsv(
        campaign: PrototypeCampaignRoomRepository.StoredCampaign,
    ): String = buildString {
        append(RUN_COLUMNS.joinToString(","))
        append('\n')
        campaign.runs.forEach { run ->
            val condition = campaign.capabilityIdentity.conditions.single { it.id == run.conditionId }
            val original = resultAuthority(run)
            val metrics = run.metrics
            appendCsvRow(
                listOf(
                    "aneb-prototype-run-record-0.1",
                    campaign.campaignId,
                    run.runId,
                    campaign.summary.campaignMode,
                    run.runIndex.toString(),
                    campaign.capabilityIdentity.profileManifestSha256,
                    condition.id,
                    condition.version,
                    condition.nominalIntervalMs.toString(),
                    run.status.name.lowercase(Locale.ROOT),
                    run.taskSuccess.toString(),
                    "android.os.SystemClock.elapsedRealtimeNanos",
                    "device_boot",
                    original.string("clock_domain_id"),
                    original.longOrNull("t0_nanos")?.toString().orEmpty(),
                    original.stringOrNull("attempt_started_at_utc").orEmpty(),
                    original.stringOrNull("attempt_ended_at_utc").orEmpty(),
                    run.eventsExpected.toString(),
                    run.eventsReceived.toString(),
                    scalar(metrics?.ttftMs),
                    scalar(metrics?.completionMs),
                    scalar(metrics?.streamSpanMs),
                    scalar(metrics?.streamEventRateEps),
                    scalar(metrics?.stallThresholdMs),
                    metrics?.stallCount?.toString().orEmpty(),
                    scalar(metrics?.stallDurationMs),
                    scalar(metrics?.stallFraction),
                    condition.scheduleSha256,
                    run.terminalReceiptValid?.toString().orEmpty(),
                    run.scoreEligible.toString(),
                    run.failureReason.orEmpty(),
                ),
            )
        }
    }

    private fun expectedSummaryCsv(
        summary: PrototypeQuickCampaignRunner.CampaignSummary,
    ): String = buildString {
        append(SUMMARY_COLUMNS.joinToString(","))
        append('\n')
        summary.conditionSummaries.forEach { condition ->
            appendCsvRow(
                listOf(
                    "aneb-prototype-summary-0.1",
                    summary.campaignId,
                    summary.campaignMode,
                    summary.status.name.lowercase(Locale.ROOT),
                    condition.conditionId,
                    condition.plannedRuns.toString(),
                    condition.attemptedRuns.toString(),
                    condition.successfulRuns.toString(),
                    condition.failedRuns.toString(),
                    condition.notStartedRuns.toString(),
                    scalar(condition.successRate),
                    condition.confidence.name,
                    scalar(condition.medianTtftMs),
                    scalar(condition.minTtftMs),
                    scalar(condition.maxTtftMs),
                    scalar(condition.medianCompletionMs),
                    scalar(condition.minCompletionMs),
                    scalar(condition.maxCompletionMs),
                    scalar(condition.medianStreamEventRateEps),
                    scalar(condition.medianStallCount),
                    scalar(condition.medianStallDurationMs),
                    scalar(condition.medianStallFraction),
                    condition.rpi?.toString().orEmpty(),
                    condition.rpiPolicyId,
                    condition.primaryNullReason.orEmpty(),
                    condition.allNullReasons?.let { reasons ->
                        JsonArray(reasons.map(::JsonPrimitive)).toString()
                    }.orEmpty(),
                ),
            )
        }
    }

    private fun StringBuilder.appendCsvRow(values: List<String>) {
        append(values.joinToString(",") { value ->
            if (value.any { character -> character == ',' || character == '"' || character == '\n' || character == '\r' }) {
                "\"${value.replace("\"", "\"\"")}\""
            } else {
                value
            }
        })
        append('\n')
    }

    private fun resultAuthority(
        run: PrototypeCampaignRoomRepository.StoredRun,
    ): JsonObject = Json.parseToJsonElement(requireNotNull(run.runAuthorityJson)).jsonObject

    private fun scalar(value: Double?): String = when {
        value == null -> ""
        value == 0.0 -> "0"
        value % 1.0 == 0.0 -> value.toLong().toString()
        else -> String.format(Locale.ROOT, "%.6f", value).trimEnd('0').trimEnd('.')
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.stringOrNull(key: String): String? =
        getValue(key).takeUnless { it === JsonNull }?.jsonPrimitive?.content

    private fun JsonObject.longOrNull(key: String): Long? =
        getValue(key).takeUnless { it === JsonNull }?.jsonPrimitive?.content?.toLong()

    private companion object {
        const val SOURCE_COMMIT = "0123456789abcdef0123456789abcdef01234567"
        const val APK_SHA256 =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val RUN_COLUMNS = listOf(
            "schema_version", "campaign_id", "run_id", "campaign_mode", "run_index",
            "profile_manifest_sha256", "condition_id", "condition_version",
            "nominal_interval_ms", "run_status", "task_success", "clock_source",
            "clock_epoch", "clock_domain_id", "t0_monotonic_ns", "attempt_started_at_utc",
            "attempt_ended_at_utc", "events_expected", "events_received", "ttft_ms",
            "completion_ms", "stream_span_ms", "stream_event_rate_eps", "stall_threshold_ms",
            "stall_count", "stall_duration_ms", "stall_fraction", "schedule_hash",
            "terminal_receipt_valid", "score_eligible", "failure_reason",
        )
        val SUMMARY_COLUMNS = listOf(
            "schema_version", "campaign_id", "campaign_mode", "campaign_status", "condition_id",
            "planned_runs", "attempted_runs", "successful_runs", "failed_runs",
            "not_started_runs", "success_rate", "confidence", "median_ttft_ms", "min_ttft_ms",
            "max_ttft_ms", "median_completion_ms", "min_completion_ms", "max_completion_ms",
            "median_stream_event_rate_eps", "median_stall_count", "median_stall_duration_ms",
            "median_stall_fraction", "rpi", "rpi_policy_id", "primary_null_reason",
            "all_null_reasons",
        )
    }
}
