package com.aneb.probe.prototype

import com.aneb.probe.data.PrototypeCampaignRoomRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.Locale

/** Renders the four canonical Android evidence records accepted by the G4 verifier runtime. */
internal object PrototypeCanonicalUploadRenderer {
    fun render(snapshot: PrototypeCampaignRoomRepository.ExportSnapshot): String {
        val campaign = snapshot.campaign
        require(SAFE_CAMPAIGN_ID.matches(campaign.campaignId)) { INVALID_AUTHORITY }
        require(campaign.summary.campaignId == campaign.campaignId) { INVALID_AUTHORITY }
        val mode = PrototypeQuickCampaignRunner.CampaignMode.entries.singleOrNull { candidate ->
            candidate.wireValue == campaign.summary.campaignMode
        } ?: throw IllegalArgumentException(UNSUPPORTED_TOPOLOGY)
        val expectedConditions = List(mode.runsPerCondition) {
            listOf("baseline_v0.1", "slow_v0.1", "unstable_v0.1")
        }.flatten()
        require(campaign.runs.size == expectedConditions.size) { UNSUPPORTED_TOPOLOGY }
        val firstNotStarted = campaign.runs.indexOfFirst { run ->
            run.status == PrototypeQuickCampaignRunner.RunStatus.NOT_STARTED
        }
        if (firstNotStarted >= 0) {
            require(campaign.runs.drop(firstNotStarted).all { run ->
                run.status == PrototypeQuickCampaignRunner.RunStatus.NOT_STARTED
            }) { UNSUPPORTED_TOPOLOGY }
        }
        val expectedCampaignStatus = when {
            firstNotStarted < 0 -> PrototypeQuickCampaignRunner.CampaignStatus.COMPLETE
            campaign.runs.any { run ->
                run.status == PrototypeQuickCampaignRunner.RunStatus.CANCELLED
            } -> PrototypeQuickCampaignRunner.CampaignStatus.CANCELLED
            else -> PrototypeQuickCampaignRunner.CampaignStatus.PARTIAL
        }
        require(campaign.summary.status == expectedCampaignStatus) { INVALID_AUTHORITY }
        require(campaign.rawCapabilityBody == snapshot.rawCapabilityBody) { INVALID_AUTHORITY }

        val capture = parseCampaignAuthority(
            source = requireNotNull(campaign.captureAuthorityJson) { MISSING_AUTHORITY },
            expectedCampaignId = campaign.campaignId,
        )
        val runs = campaign.runs.map { run ->
            val condition = campaign.capabilityIdentity.conditions.singleOrNull { condition ->
                condition.id == run.conditionId
            } ?: throw IllegalArgumentException(INVALID_AUTHORITY)
            RenderRun(
                stored = run,
                condition = condition,
                authority = parseRunAuthority(
                    source = requireNotNull(run.runAuthorityJson) { MISSING_AUTHORITY },
                    expectedCampaignId = campaign.campaignId,
                    expectedRun = run,
                ),
            )
        }
        require(runs.map { it.stored.runIndex } == (1..expectedConditions.size).toList()) {
            INVALID_AUTHORITY
        }
        require(runs.map { it.stored.conditionId } == expectedConditions) { INVALID_AUTHORITY }
        val campaignStartedAt = Instant.parse(capture.startedAtUtc)
        val campaignEndedAt = Instant.parse(capture.endedAtUtc)
        require(runs.all { run ->
            val startedAt = run.authority.attemptStartedAtUtc?.let(Instant::parse)
            val endedAt = run.authority.attemptEndedAtUtc?.let(Instant::parse)
            (startedAt == null && endedAt == null) ||
                (startedAt != null && endedAt != null &&
                    !startedAt.isBefore(campaignStartedAt) &&
                    !endedAt.isAfter(campaignEndedAt))
        }) { INVALID_AUTHORITY }

        val eventsJsonl = renderEvents(snapshot, runs)
        val metaJson = renderMeta(campaign, capture)
        val runsCsv = renderRuns(campaign, runs)
        val summaryCsv = renderSummary(campaign.summary)
        return buildJsonObject {
            put("schema_version", JsonPrimitive(UPLOAD_SCHEMA_VERSION))
            put("campaign_id", JsonPrimitive(campaign.campaignId))
            put("meta_json", JsonPrimitive(metaJson))
            put("events_jsonl", JsonPrimitive(eventsJsonl))
            put("runs_csv", JsonPrimitive(runsCsv))
            put("summary_csv", JsonPrimitive(summaryCsv))
        }.toString()
    }

    private fun parseCampaignAuthority(
        source: String,
        expectedCampaignId: String,
    ): CampaignAuthority {
        val root = exactCanonicalObject(source)
        root.requireKeys(CAMPAIGN_AUTHORITY_KEYS)
        require(root.string("schema_version") == CAPTURE_AUTHORITY_SCHEMA_VERSION) { INVALID_AUTHORITY }
        require(root.string("campaign_id") == expectedCampaignId) { INVALID_AUTHORITY }
        val startedAt = root.string("campaign_started_at_utc").requireInstant()
        val endedAt = root.string("campaign_ended_at_utc").requireInstant()
        require(!endedAt.isBefore(startedAt)) { INVALID_AUTHORITY }

        val product = root.objectValue("product").also { it.requireKeys(PRODUCT_KEYS) }
        require(product.string("version") == PRODUCT_VERSION) { INVALID_AUTHORITY }
        val sourceCommit = product.string("source_commit")
        require(SOURCE_COMMIT.matches(sourceCommit)) { INVALID_AUTHORITY }

        val android = root.objectValue("android").also { it.requireKeys(ANDROID_KEYS) }
        val versionName = android.string("version_name")
        requireSafeNonEmpty(versionName)
        val versionCode = android.integer("version_code")
        require(versionCode > 0) { INVALID_AUTHORITY }
        val apkSha256 = android.string("apk_sha256")
        require(SHA256.matches(apkSha256)) { INVALID_AUTHORITY }

        val device = root.objectValue("device").also { it.requireKeys(DEVICE_KEYS) }
        val manufacturer = device.string("manufacturer").also(::requireSafeNonEmpty)
        val model = device.string("model").also(::requireSafeNonEmpty)
        val osRelease = device.string("os_release").also(::requireSafeNonEmpty)
        val sdkInt = device.integer("sdk_int")
        require(sdkInt > 0) { INVALID_AUTHORITY }

        val transport = root.objectValue("transport").also { it.requireKeys(TRANSPORT_KEYS) }
        val transportKind = transport.string("kind")
        val acceptanceEligible = transport.boolean("acceptance_eligible")
        require(
            (transportKind == "lan" && acceptanceEligible) ||
                (transportKind == "adb_reverse" && !acceptanceEligible),
        ) { INVALID_AUTHORITY }

        return CampaignAuthority(
            startedAtUtc = root.string("campaign_started_at_utc"),
            endedAtUtc = root.string("campaign_ended_at_utc"),
            sourceCommit = sourceCommit,
            androidVersionName = versionName,
            androidVersionCode = versionCode,
            apkSha256 = apkSha256,
            deviceManufacturer = manufacturer,
            deviceModel = model,
            deviceOsRelease = osRelease,
            deviceSdkInt = sdkInt,
            transportKind = transportKind,
            acceptancePath = acceptanceEligible,
        )
    }

    private fun parseRunAuthority(
        source: String,
        expectedCampaignId: String,
        expectedRun: PrototypeCampaignRoomRepository.StoredRun,
    ): RunAuthority {
        val root = exactCanonicalObject(source)
        root.requireKeys(RUN_AUTHORITY_KEYS)
        require(root.string("schema_version") == RUN_AUTHORITY_SCHEMA_VERSION) { INVALID_AUTHORITY }
        require(root.string("campaign_id") == expectedCampaignId) { INVALID_AUTHORITY }
        require(root.string("run_id") == expectedRun.runId) { INVALID_AUTHORITY }
        require(root.integer("run_index") == expectedRun.runIndex) { INVALID_AUTHORITY }
        val clockDomainId = root.string("clock_domain_id")
        requireSafeNonEmpty(clockDomainId)
        val t0Nanos = root.longOrNull("t0_nanos")
        val startedAtText = root.stringOrNull("attempt_started_at_utc")
        val endedAtText = root.stringOrNull("attempt_ended_at_utc")
        if (expectedRun.status == PrototypeQuickCampaignRunner.RunStatus.NOT_STARTED) {
            require(t0Nanos == null && startedAtText == null && endedAtText == null) {
                INVALID_AUTHORITY
            }
        } else {
            require(t0Nanos != null && t0Nanos >= 0L) { INVALID_AUTHORITY }
            require(startedAtText != null && endedAtText != null) { INVALID_AUTHORITY }
            val startedAt = startedAtText.requireInstant()
            val endedAt = endedAtText.requireInstant()
            require(!endedAt.isBefore(startedAt)) { INVALID_AUTHORITY }
        }
        return RunAuthority(
            clockDomainId = clockDomainId,
            t0Nanos = t0Nanos,
            attemptStartedAtUtc = startedAtText,
            attemptEndedAtUtc = endedAtText,
        )
    }

    private fun renderMeta(
        campaign: PrototypeCampaignRoomRepository.StoredCampaign,
        capture: CampaignAuthority,
    ): String {
        val capability = campaign.capabilityIdentity
        require(capability.evidenceSchemaVersion == EVIDENCE_SCHEMA_VERSION) { INVALID_AUTHORITY }
        require(capability.productVersion == PRODUCT_VERSION) { INVALID_AUTHORITY }
        require(capability.protocolVersion == PROTOCOL_VERSION) { INVALID_AUTHORITY }
        require(capability.scorePolicyId == SCORE_POLICY_ID) { INVALID_AUTHORITY }
        require(SHA256.matches(capability.profileManifestSha256)) { INVALID_AUTHORITY }
        require(SHA256.matches(capability.serverBinarySha256)) { INVALID_AUTHORITY }
        require(capability.conditions.size == 3) { INVALID_AUTHORITY }

        val meta = buildJsonObject {
            put("schema_version", JsonPrimitive(capability.evidenceSchemaVersion))
            put("campaign_id", JsonPrimitive(campaign.campaignId))
            put("campaign_mode", JsonPrimitive(campaign.summary.campaignMode))
            put("campaign_status", JsonPrimitive(campaign.summary.status.wireValue()))
            put("started_at_utc", JsonPrimitive(capture.startedAtUtc))
            put("ended_at_utc", JsonPrimitive(capture.endedAtUtc))
            put("claim_scope", JsonPrimitive(capability.claimScope))
            put("evidence_mode", JsonPrimitive(capability.evidenceMode))
            put("impairment_layer", JsonPrimitive(capability.impairmentLayer))
            put("score_policy_id", JsonPrimitive(capability.scorePolicyId))
            put("profile_manifest_sha256", JsonPrimitive(capability.profileManifestSha256))
            put("clock_contract", buildJsonObject {
                put("source", JsonPrimitive(CLOCK_SOURCE))
                put("unit", JsonPrimitive("ns"))
                put("epoch", JsonPrimitive(CLOCK_EPOCH))
                put("includes_deep_sleep", JsonPrimitive(true))
                put("domain_identity", JsonPrimitive(CLOCK_DOMAIN_DISCLOSURE))
            })
            put("profile", buildJsonObject {
                put("id", JsonPrimitive(capability.workload.id))
                put("version", JsonPrimitive(capability.workload.version))
                put("sha256", JsonPrimitive(capability.profileManifestSha256))
            })
            put("product", buildJsonObject {
                put("version", JsonPrimitive(PRODUCT_VERSION))
                put("git_commit", JsonPrimitive(capture.sourceCommit))
            })
            put("android_app", buildJsonObject {
                put("version_name", JsonPrimitive(capture.androidVersionName))
                put("version_code", JsonPrimitive(capture.androidVersionCode))
                put("apk_sha256", JsonPrimitive(capture.apkSha256))
            })
            put("server", buildJsonObject {
                put("version", JsonPrimitive(capability.serverVersion))
                put("binary_sha256", JsonPrimitive(capability.serverBinarySha256))
                put("protocol_version", JsonPrimitive(capability.protocolVersion))
            })
            put("device", buildJsonObject {
                put("manufacturer", JsonPrimitive(capture.deviceManufacturer))
                put("model", JsonPrimitive(capture.deviceModel))
                put("os_release", JsonPrimitive(capture.deviceOsRelease))
                put("sdk_int", JsonPrimitive(capture.deviceSdkInt))
            })
            put("transport", buildJsonObject {
                put("mode", JsonPrimitive(capture.transportKind))
                put("acceptance_path", JsonPrimitive(capture.acceptancePath))
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
        return "$meta\n"
    }

    private fun renderEvents(
        snapshot: PrototypeCampaignRoomRepository.ExportSnapshot,
        runs: List<RenderRun>,
    ): String {
        val expectedCoordinates = runs.flatMap { run ->
            run.stored.evidenceEvents.indices.map { ordinal ->
                Triple(run.stored.runIndex, run.stored.runId, ordinal)
            }
        }
        require(snapshot.lexicalEvidence.size == expectedCoordinates.size) { INVALID_AUTHORITY }
        return buildString {
            snapshot.lexicalEvidence.zip(expectedCoordinates).forEach { (evidence, expected) ->
                require(
                    evidence.runIndex == expected.first &&
                        evidence.runId == expected.second &&
                        evidence.eventOrdinal == expected.third,
                ) { INVALID_AUTHORITY }
                requireCanonicalLine(evidence.eventJson)
                val run = runs.single { candidate -> candidate.stored.runId == evidence.runId }
                val parsed = JSON.parseToJsonElement(evidence.eventJson).jsonObject
                require(parsed.string("campaign_id") == snapshot.campaign.campaignId) { INVALID_AUTHORITY }
                require(parsed.string("run_id") == evidence.runId) { INVALID_AUTHORITY }
                require(parsed.integer("run_index") == evidence.runIndex) { INVALID_AUTHORITY }
                require(parsed.string("clock_domain_id") == run.authority.clockDomainId) {
                    INVALID_AUTHORITY
                }
                require(parsed == run.stored.evidenceEvents[evidence.eventOrdinal]) { INVALID_AUTHORITY }
                if (evidence.eventOrdinal == 0) {
                    val details = parsed.objectValue("details")
                    require(details.long("t0_monotonic_ns") == run.authority.t0Nanos) {
                        INVALID_AUTHORITY
                    }
                }
                if (evidence.eventOrdinal == run.stored.evidenceEvents.lastIndex) {
                    val details = parsed.objectValue("details")
                    when (run.stored.status) {
                        PrototypeQuickCampaignRunner.RunStatus.COMPLETE -> {
                            require(parsed.string("event_type") == "terminal_event") {
                                INVALID_AUTHORITY
                            }
                            require(details.long("t0_monotonic_ns") == run.authority.t0Nanos) {
                                INVALID_AUTHORITY
                            }
                            require(details.string("clock_domain_id") == run.authority.clockDomainId) {
                                INVALID_AUTHORITY
                            }
                        }
                        PrototypeQuickCampaignRunner.RunStatus.INTERRUPTED,
                        PrototypeQuickCampaignRunner.RunStatus.INVALID_SEQUENCE,
                        PrototypeQuickCampaignRunner.RunStatus.CANCELLED,
                        -> {
                            val expectedEventType =
                                if (run.stored.status == PrototypeQuickCampaignRunner.RunStatus.CANCELLED) {
                                    "run_cancelled"
                                } else {
                                    "run_failed"
                                }
                            require(parsed.string("event_type") == expectedEventType) {
                                INVALID_AUTHORITY
                            }
                            require(details.string("failure_reason") == run.stored.failureReason) {
                                INVALID_AUTHORITY
                            }
                            require(details.integer("events_received") == run.stored.eventsReceived) {
                                INVALID_AUTHORITY
                            }
                        }
                        PrototypeQuickCampaignRunner.RunStatus.NOT_STARTED ->
                            throw IllegalArgumentException(INVALID_AUTHORITY)
                    }
                }
                append(evidence.eventJson)
                append('\n')
            }
        }
    }

    private fun renderRuns(
        campaign: PrototypeCampaignRoomRepository.StoredCampaign,
        runs: List<RenderRun>,
    ): String = buildString {
        append(RUN_COLUMNS.joinToString(","))
        append('\n')
        runs.forEach { run ->
            val stored = run.stored
            val metrics = stored.metrics
            appendCsvRow(
                listOf(
                    RUN_RECORD_SCHEMA_VERSION,
                    campaign.campaignId,
                    stored.runId,
                    campaign.summary.campaignMode,
                    stored.runIndex.toString(),
                    campaign.capabilityIdentity.profileManifestSha256,
                    run.condition.id,
                    run.condition.version,
                    run.condition.nominalIntervalMs.toString(),
                    stored.status.wireValue(),
                    scalar(stored.taskSuccess),
                    CLOCK_SOURCE,
                    CLOCK_EPOCH,
                    run.authority.clockDomainId,
                    run.authority.t0Nanos?.toString().orEmpty(),
                    run.authority.attemptStartedAtUtc.orEmpty(),
                    run.authority.attemptEndedAtUtc.orEmpty(),
                    stored.eventsExpected.toString(),
                    stored.eventsReceived.toString(),
                    scalar(metrics?.ttftMs),
                    scalar(metrics?.completionMs),
                    scalar(metrics?.streamSpanMs),
                    scalar(metrics?.streamEventRateEps),
                    scalar(metrics?.stallThresholdMs),
                    metrics?.stallCount?.toString().orEmpty(),
                    scalar(metrics?.stallDurationMs),
                    scalar(metrics?.stallFraction),
                    run.condition.scheduleSha256,
                    stored.terminalReceiptValid?.let(::scalar).orEmpty(),
                    scalar(stored.scoreEligible),
                    stored.failureReason.orEmpty(),
                ),
            )
        }
    }

    private fun renderSummary(summary: PrototypeQuickCampaignRunner.CampaignSummary): String =
        buildString {
            append(SUMMARY_COLUMNS.joinToString(","))
            append('\n')
            summary.conditionSummaries.forEach { condition ->
                appendCsvRow(
                    listOf(
                        SUMMARY_SCHEMA_VERSION,
                        summary.campaignId,
                        summary.campaignMode,
                        summary.status.wireValue(),
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

    private fun exactCanonicalObject(source: String): JsonObject {
        requireCanonicalLine(source)
        val root = try {
            JSON.parseToJsonElement(source).jsonObject
        } catch (error: Exception) {
            throw IllegalArgumentException(INVALID_AUTHORITY, error)
        }
        require(root.toString() == source) { INVALID_AUTHORITY }
        return root
    }

    private fun requireCanonicalLine(value: String) {
        require(
            value.isNotEmpty() &&
                !value.startsWith('\uFEFF') &&
                '\r' !in value &&
                '\n' !in value &&
                '\u0000' !in value,
        ) { INVALID_AUTHORITY }
    }

    private fun requireSafeNonEmpty(value: String) {
        require(value.isNotBlank() && '\r' !in value && '\n' !in value && '\u0000' !in value) {
            INVALID_AUTHORITY
        }
    }

    private fun String.requireInstant(): Instant {
        val parsed = try {
            Instant.parse(this)
        } catch (error: Exception) {
            throw IllegalArgumentException(INVALID_AUTHORITY, error)
        }
        require(parsed.toString() == this) { INVALID_AUTHORITY }
        return parsed
    }

    private fun JsonObject.requireKeys(expected: Set<String>) {
        require(keys == expected) { INVALID_AUTHORITY }
    }

    private fun JsonObject.objectValue(key: String): JsonObject =
        this[key] as? JsonObject ?: throw IllegalArgumentException(INVALID_AUTHORITY)

    private fun JsonObject.string(key: String): String {
        val primitive = this[key] as? JsonPrimitive
            ?: throw IllegalArgumentException(INVALID_AUTHORITY)
        require(primitive.isString) { INVALID_AUTHORITY }
        return primitive.content
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val value = this[key] ?: throw IllegalArgumentException(INVALID_AUTHORITY)
        if (value === JsonNull) return null
        val primitive = value as? JsonPrimitive ?: throw IllegalArgumentException(INVALID_AUTHORITY)
        require(primitive.isString) { INVALID_AUTHORITY }
        return primitive.content
    }

    private fun JsonObject.boolean(key: String): Boolean {
        val primitive = this[key] as? JsonPrimitive
            ?: throw IllegalArgumentException(INVALID_AUTHORITY)
        require(!primitive.isString && primitive.content in setOf("true", "false")) {
            INVALID_AUTHORITY
        }
        return primitive.content.toBooleanStrict()
    }

    private fun JsonObject.integer(key: String): Int = long(key).also { value ->
        require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { INVALID_AUTHORITY }
    }.toInt()

    private fun JsonObject.long(key: String): Long =
        longOrNull(key) ?: throw IllegalArgumentException(INVALID_AUTHORITY)

    private fun JsonObject.longOrNull(key: String): Long? {
        val value = this[key] ?: throw IllegalArgumentException(INVALID_AUTHORITY)
        if (value === JsonNull) return null
        val primitive = value as? JsonPrimitive ?: throw IllegalArgumentException(INVALID_AUTHORITY)
        require(!primitive.isString && INTEGER.matches(primitive.content)) { INVALID_AUTHORITY }
        return primitive.content.toLongOrNull() ?: throw IllegalArgumentException(INVALID_AUTHORITY)
    }

    private fun StringBuilder.appendCsvRow(values: List<String>) {
        values.forEachIndexed { index, value ->
            require('\r' !in value && '\u0000' !in value) { INVALID_AUTHORITY }
            if (index > 0) append(',')
            if (value.any { character -> character == ',' || character == '"' || character == '\n' }) {
                append('"')
                append(value.replace("\"", "\"\""))
                append('"')
            } else {
                append(value)
            }
        }
        append('\n')
    }

    private fun scalar(value: Boolean): String = if (value) "true" else "false"

    internal fun canonicalDecimal(value: Double?): String {
        if (value == null) return ""
        require(value.isFinite() && value >= 0.0) { INVALID_AUTHORITY }
        if (value == 0.0) return "0"
        return BigDecimal(value)
            .setScale(6, RoundingMode.HALF_EVEN)
            .stripTrailingZeros()
            .toPlainString()
    }

    private fun scalar(value: Double?): String = canonicalDecimal(value)

    private fun PrototypeQuickCampaignRunner.CampaignStatus.wireValue(): String =
        name.lowercase(Locale.ROOT)

    private fun PrototypeQuickCampaignRunner.RunStatus.wireValue(): String =
        name.lowercase(Locale.ROOT)

    private data class CampaignAuthority(
        val startedAtUtc: String,
        val endedAtUtc: String,
        val sourceCommit: String,
        val androidVersionName: String,
        val androidVersionCode: Int,
        val apkSha256: String,
        val deviceManufacturer: String,
        val deviceModel: String,
        val deviceOsRelease: String,
        val deviceSdkInt: Int,
        val transportKind: String,
        val acceptancePath: Boolean,
    )

    private data class RunAuthority(
        val clockDomainId: String,
        val t0Nanos: Long?,
        val attemptStartedAtUtc: String?,
        val attemptEndedAtUtc: String?,
    )

    private data class RenderRun(
        val stored: PrototypeCampaignRoomRepository.StoredRun,
        val condition: PrototypeCapabilityConditionIdentity,
        val authority: RunAuthority,
    )

    private val JSON = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }
    private val SAFE_CAMPAIGN_ID = Regex("^[A-Za-z0-9._:-]+$")
    private val SOURCE_COMMIT = Regex("^[a-f0-9]{40}$")
    private val SHA256 = Regex("^[a-f0-9]{64}$")
    private val INTEGER = Regex("^(?:0|-?[1-9][0-9]*)$")
    private val CAMPAIGN_AUTHORITY_KEYS = setOf(
        "schema_version", "campaign_id", "campaign_started_at_utc", "campaign_ended_at_utc",
        "product", "android", "device", "transport",
    )
    private val PRODUCT_KEYS = setOf("version", "source_commit")
    private val ANDROID_KEYS = setOf("version_name", "version_code", "apk_sha256")
    private val DEVICE_KEYS = setOf("manufacturer", "model", "os_release", "sdk_int")
    private val TRANSPORT_KEYS = setOf("kind", "acceptance_eligible")
    private val RUN_AUTHORITY_KEYS = setOf(
        "schema_version", "campaign_id", "run_id", "run_index", "clock_domain_id",
        "t0_nanos", "attempt_started_at_utc", "attempt_ended_at_utc",
    )
    private val RUN_COLUMNS = listOf(
        "schema_version", "campaign_id", "run_id", "campaign_mode", "run_index",
        "profile_manifest_sha256", "condition_id", "condition_version",
        "nominal_interval_ms", "run_status", "task_success", "clock_source", "clock_epoch",
        "clock_domain_id", "t0_monotonic_ns", "attempt_started_at_utc", "attempt_ended_at_utc",
        "events_expected", "events_received", "ttft_ms", "completion_ms", "stream_span_ms",
        "stream_event_rate_eps", "stall_threshold_ms", "stall_count", "stall_duration_ms",
        "stall_fraction", "schedule_hash", "terminal_receipt_valid", "score_eligible",
        "failure_reason",
    )
    private val SUMMARY_COLUMNS = listOf(
        "schema_version", "campaign_id", "campaign_mode", "campaign_status", "condition_id",
        "planned_runs", "attempted_runs", "successful_runs", "failed_runs", "not_started_runs",
        "success_rate", "confidence", "median_ttft_ms", "min_ttft_ms", "max_ttft_ms",
        "median_completion_ms", "min_completion_ms", "max_completion_ms",
        "median_stream_event_rate_eps", "median_stall_count", "median_stall_duration_ms",
        "median_stall_fraction", "rpi", "rpi_policy_id", "primary_null_reason",
        "all_null_reasons",
    )

    private const val UPLOAD_SCHEMA_VERSION = "aneb-prototype-upload-0.1"
    private const val CAPTURE_AUTHORITY_SCHEMA_VERSION = "aneb-prototype-capture-authority-0.1"
    private const val RUN_AUTHORITY_SCHEMA_VERSION = "aneb-prototype-run-authority-0.1"
    private const val EVIDENCE_SCHEMA_VERSION = "aneb-prototype-evidence-0.1"
    private const val RUN_RECORD_SCHEMA_VERSION = "aneb-prototype-run-record-0.1"
    private const val SUMMARY_SCHEMA_VERSION = "aneb-prototype-summary-0.1"
    private const val PRODUCT_VERSION = "prototype-0.1"
    private const val PROTOCOL_VERSION = "prototype-stream-0.1"
    private const val SCORE_POLICY_ID = "rpi-0.1"
    private const val CLOCK_SOURCE = "android.os.SystemClock.elapsedRealtimeNanos"
    private const val CLOCK_EPOCH = "device_boot"
    private const val CLOCK_DOMAIN_DISCLOSURE = "per-run opaque boot/session clock_domain_id"
    private const val MISSING_AUTHORITY = "prototype canonical upload authority is missing"
    private const val INVALID_AUTHORITY = "prototype canonical upload authority is invalid"
    private const val UNSUPPORTED_TOPOLOGY = "prototype canonical upload topology is not supported"
}
