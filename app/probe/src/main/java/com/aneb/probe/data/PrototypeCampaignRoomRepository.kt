package com.aneb.probe.data

import androidx.room.withTransaction
import com.aneb.probe.prototype.AnebClientPrototypeRawPostTransport
import com.aneb.probe.prototype.PrototypeCampaignConfig
import com.aneb.probe.prototype.PrototypeCapabilityConditionIdentity
import com.aneb.probe.prototype.PrototypeCapabilityIdentity
import com.aneb.probe.prototype.PrototypeCapabilityWorkloadIdentity
import com.aneb.probe.prototype.PrototypeQuickCampaignRunner
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Persists one validated Prototype campaign as a normalized, transactionally consistent graph.
 *
 * Ticket and aggregate JSON remain diagnostic/result authority, while run and evidence rows remain
 * the only authority for normalized children. Loading therefore validates the complete graph and
 * fails closed instead of rebuilding missing or inconsistent children from the aggregate JSON.
 */
class PrototypeCampaignRoomRepository(
    private val database: AnebDatabase,
) {
    private val dao: PrototypeCampaignDao
        get() = database.prototypeCampaignDao()

    suspend fun save(
        config: PrototypeCampaignConfig,
        result: PrototypeQuickCampaignRunner.CampaignResult,
    ) {
        val stored = StoredCampaign(
            campaignId = config.campaignId,
            nodeBaseUrl = config.nodeTicket.nodeBaseUrl,
            runUrl = config.nodeTicket.runUrl,
            capabilityUrl = config.nodeTicket.capabilityUrl,
            rawCapabilityBody = config.nodeTicket.rawCapabilityBody,
            capabilityIdentity = config.nodeTicket.identity,
            summary = result.summary,
            runs = result.runs.map(::storedRun),
        )
        validate(stored)

        val campaign = PrototypeCampaignEntity(
            campaignId = stored.campaignId,
            nodeBaseUrl = stored.nodeBaseUrl,
            runUrl = stored.runUrl,
            capabilityUrl = stored.capabilityUrl,
            rawCapabilityBody = stored.rawCapabilityBody,
            capabilityIdentityJson = JSON.encodeToString(
                CapabilityIdentityRecord.from(stored.capabilityIdentity),
            ),
            summaryJson = JSON.encodeToString(CampaignSummaryRecord.from(stored.summary)),
        )
        val runs = stored.runs.map { run ->
            PrototypeRunEntity(
                campaignId = stored.campaignId,
                runId = run.runId,
                runIndex = run.runIndex,
                conditionId = run.conditionId,
                status = run.status.name,
                taskSuccess = run.taskSuccess,
                scoreEligible = run.scoreEligible,
                eventsExpected = run.eventsExpected,
                eventsReceived = run.eventsReceived,
                failureReason = run.failureReason,
                terminalReceiptValid = run.terminalReceiptValid,
                metricsJson = run.metrics?.let { metrics ->
                    JSON.encodeToString(RunMetricsRecord.from(metrics))
                },
            )
        }
        val evidence = stored.runs.flatMap { run ->
            run.evidenceEvents.mapIndexed { eventOrdinal, event ->
                PrototypeEvidenceEventEntity(
                    campaignId = stored.campaignId,
                    runId = run.runId,
                    eventOrdinal = eventOrdinal,
                    eventJson = event.toString(),
                )
            }
        }

        database.withTransaction {
            dao.insertCampaign(campaign)
            dao.insertRuns(runs)
            dao.insertEvidenceEvents(evidence)
        }
    }

    suspend fun load(campaignId: String): StoredCampaign? = database.withTransaction {
        readValidatedGraph(campaignId)?.campaign
    }

    suspend fun loadExportSnapshot(campaignId: String): ExportSnapshot? =
        database.withTransaction {
            val graph = readValidatedGraph(campaignId) ?: return@withTransaction null
            ExportSnapshot(
                campaign = graph.campaign,
                rawCapabilityBody = graph.campaign.rawCapabilityBody,
                lexicalEvidence = graph.lexicalEvidence,
            )
        }

    private suspend fun readValidatedGraph(campaignId: String): LoadedGraph? {
        val campaign = dao.campaign(campaignId) ?: return null
        val lexicalEvidence = mutableListOf<LexicalEvidence>()
        val runs = dao.runs(campaignId).map { run ->
            val evidenceRows = dao.evidenceEvents(campaignId, run.runId)
            evidenceRows.forEachIndexed { expectedOrdinal, event ->
                require(event.eventOrdinal == expectedOrdinal) { INVALID_GRAPH }
                requireUniqueEvidenceRootKeys(event.eventJson)
                lexicalEvidence += LexicalEvidence(
                    runIndex = run.runIndex,
                    runId = run.runId,
                    eventOrdinal = event.eventOrdinal,
                    eventJson = event.eventJson,
                )
            }
            StoredRun(
                runIndex = run.runIndex,
                runId = run.runId,
                conditionId = run.conditionId,
                status = enumValueOf(run.status),
                taskSuccess = run.taskSuccess,
                scoreEligible = run.scoreEligible,
                eventsExpected = run.eventsExpected,
                eventsReceived = run.eventsReceived,
                failureReason = run.failureReason,
                terminalReceiptValid = run.terminalReceiptValid,
                metrics = run.metricsJson?.let { encoded ->
                    JSON.decodeFromString<RunMetricsRecord>(encoded).toMetrics()
                },
                evidenceEvents = evidenceRows.map { event ->
                    JSON.parseToJsonElement(event.eventJson).jsonObject
                },
            )
        }
        val storedCampaign = StoredCampaign(
            campaignId = campaign.campaignId,
            nodeBaseUrl = campaign.nodeBaseUrl,
            runUrl = campaign.runUrl,
            capabilityUrl = campaign.capabilityUrl,
            rawCapabilityBody = campaign.rawCapabilityBody,
            capabilityIdentity = JSON.decodeFromString<CapabilityIdentityRecord>(
                campaign.capabilityIdentityJson,
            ).toIdentity(),
            summary = JSON.decodeFromString<CampaignSummaryRecord>(campaign.summaryJson).toSummary(),
            runs = runs,
        ).also(::validate)
        return LoadedGraph(storedCampaign, lexicalEvidence)
    }

    private fun requireUniqueEvidenceRootKeys(eventJson: String) {
        try {
            EVIDENCE_PROBE_JSON.decodeFromString(StoredEvidenceRootDuplicateKeyProbe, eventJson)
        } catch (error: Exception) {
            throw IllegalArgumentException(INVALID_GRAPH, error)
        }
    }

    data class ExportSnapshot(
        val campaign: StoredCampaign,
        val rawCapabilityBody: String,
        val lexicalEvidence: List<LexicalEvidence>,
    )

    data class LexicalEvidence(
        val runIndex: Int,
        val runId: String,
        val eventOrdinal: Int,
        val eventJson: String,
    )

    data class StoredCampaign(
        val campaignId: String,
        val nodeBaseUrl: String,
        val runUrl: String,
        val capabilityUrl: String,
        val rawCapabilityBody: String,
        val capabilityIdentity: PrototypeCapabilityIdentity,
        val summary: PrototypeQuickCampaignRunner.CampaignSummary,
        val runs: List<StoredRun>,
    )

    data class StoredRun(
        val runIndex: Int,
        val runId: String,
        val conditionId: String,
        val status: PrototypeQuickCampaignRunner.RunStatus,
        val taskSuccess: Boolean,
        val scoreEligible: Boolean,
        val eventsExpected: Int,
        val eventsReceived: Int,
        val failureReason: String?,
        val terminalReceiptValid: Boolean?,
        val metrics: PrototypeQuickCampaignRunner.RunMetrics?,
        val evidenceEvents: List<JsonObject>,
    )

    private data class LoadedGraph(
        val campaign: StoredCampaign,
        val lexicalEvidence: List<LexicalEvidence>,
    )

    private fun storedRun(run: PrototypeQuickCampaignRunner.RunResult): StoredRun = StoredRun(
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
    )

    private fun validate(campaign: StoredCampaign) {
        require(campaign.campaignId.isNotBlank()) { INVALID_GRAPH }
        require(
            AnebClientPrototypeRawPostTransport.validatedCapabilityIdentityOrNull(
                campaign.rawCapabilityBody,
            ) == campaign.capabilityIdentity,
        ) { INVALID_GRAPH }
        require(campaign.summary.campaignId == campaign.campaignId) { INVALID_GRAPH }
        require(campaign.summary.campaignMode == CAMPAIGN_MODE) { INVALID_GRAPH }
        require(campaign.summary.plannedRuns == EXPECTED_RUNS) { INVALID_GRAPH }
        require(campaign.runs.size == EXPECTED_RUNS) { INVALID_GRAPH }
        require(campaign.runs.map(StoredRun::runIndex) == EXPECTED_RUN_INDICES) { INVALID_GRAPH }
        require(campaign.runs.map(StoredRun::conditionId) == EXPECTED_CONDITIONS) { INVALID_GRAPH }
        require(campaign.runs.map(StoredRun::runId).distinct().size == EXPECTED_RUNS) {
            INVALID_GRAPH
        }
        require(
            campaign.summary.conditionSummaries.map { summary -> summary.conditionId } ==
                EXPECTED_CONDITIONS,
        ) { INVALID_GRAPH }
        require(
            campaign.capabilityIdentity.conditions.map { condition -> condition.id } ==
                EXPECTED_CONDITIONS,
        ) { INVALID_GRAPH }
        val firstIncomplete = campaign.runs.indexOfFirst { run ->
            run.status != PrototypeQuickCampaignRunner.RunStatus.COMPLETE
        }
        if (firstIncomplete >= 0) {
            require(
                campaign.runs[firstIncomplete].status ==
                    PrototypeQuickCampaignRunner.RunStatus.INTERRUPTED,
            ) { INVALID_GRAPH }
            require(
                campaign.runs.drop(firstIncomplete + 1).all { run ->
                    run.status == PrototypeQuickCampaignRunner.RunStatus.NOT_STARTED
                },
            ) { INVALID_GRAPH }
        }

        val attempted = campaign.runs.count { run ->
            run.status != PrototypeQuickCampaignRunner.RunStatus.NOT_STARTED
        }
        val successful = campaign.runs.count(StoredRun::taskSuccess)
        require(campaign.summary.attemptedRuns == attempted) { INVALID_GRAPH }
        require(campaign.summary.successfulRuns == successful) { INVALID_GRAPH }
        require(campaign.summary.failedRuns == attempted - successful) { INVALID_GRAPH }
        require(campaign.summary.notStartedRuns == EXPECTED_RUNS - attempted) { INVALID_GRAPH }
        require(
            campaign.summary.successRate == successful.toDouble() / EXPECTED_RUNS,
        ) { INVALID_GRAPH }
        require(
            campaign.summary.status == if (campaign.runs.none { run ->
                    run.status == PrototypeQuickCampaignRunner.RunStatus.NOT_STARTED
                }
            ) {
                PrototypeQuickCampaignRunner.CampaignStatus.COMPLETE
            } else {
                PrototypeQuickCampaignRunner.CampaignStatus.PARTIAL
            },
        ) { INVALID_GRAPH }

        campaign.runs.forEach { run -> validateRun(campaign.campaignId, run) }
        val canonicalSummary = try {
            PrototypeQuickCampaignRunner.canonicalCampaignSummary(
                campaignId = campaign.campaignId,
                results = campaign.runs.map { run ->
                    PrototypeQuickCampaignRunner.SummaryRun(
                        conditionId = run.conditionId,
                        status = run.status,
                        taskSuccess = run.taskSuccess,
                        scoreEligible = run.scoreEligible,
                        metrics = run.metrics,
                    )
                },
            )
        } catch (error: Exception) {
            throw IllegalArgumentException(INVALID_GRAPH, error)
        }
        require(campaign.summary == canonicalSummary) { INVALID_GRAPH }
    }

    private fun validateRun(campaignId: String, run: StoredRun) {
        require(run.runId.isNotBlank()) { INVALID_GRAPH }
        require(run.eventsExpected == EXPECTED_CONTENT_EVENTS) { INVALID_GRAPH }
        when (run.status) {
            PrototypeQuickCampaignRunner.RunStatus.COMPLETE -> {
                require(run.taskSuccess && run.scoreEligible) { INVALID_GRAPH }
                require(run.eventsReceived == EXPECTED_CONTENT_EVENTS) { INVALID_GRAPH }
                require(run.failureReason == null && run.terminalReceiptValid == true) {
                    INVALID_GRAPH
                }
                require(run.metrics != null) { INVALID_GRAPH }
                require(run.evidenceEvents.size == COMPLETE_EVIDENCE_EVENTS) { INVALID_GRAPH }
            }

            PrototypeQuickCampaignRunner.RunStatus.INTERRUPTED -> {
                require(!run.taskSuccess && !run.scoreEligible) { INVALID_GRAPH }
                require(run.eventsReceived in 0 until EXPECTED_CONTENT_EVENTS) { INVALID_GRAPH }
                require(run.failureReason == INTERRUPTED_REASON) { INVALID_GRAPH }
                require(run.terminalReceiptValid == null) { INVALID_GRAPH }
                require(run.evidenceEvents.size == run.eventsReceived + 2) { INVALID_GRAPH }
            }

            PrototypeQuickCampaignRunner.RunStatus.NOT_STARTED -> {
                require(!run.taskSuccess && !run.scoreEligible) { INVALID_GRAPH }
                require(run.eventsReceived == 0 && run.evidenceEvents.isEmpty()) { INVALID_GRAPH }
                require(run.failureReason == NOT_STARTED_REASON) { INVALID_GRAPH }
                require(run.terminalReceiptValid == null && run.metrics == null) { INVALID_GRAPH }
            }
        }

        run.evidenceEvents.forEachIndexed { ordinal, event ->
            require(event.keys == CANONICAL_STORED_EVENT_KEYS) { INVALID_GRAPH }
            require(event.string("campaign_id") == campaignId) { INVALID_GRAPH }
            require(event.string("run_id") == run.runId) { INVALID_GRAPH }
            require(event.string("condition_id") == run.conditionId) { INVALID_GRAPH }
            require(event.exactLong("run_index") == run.runIndex.toLong()) { INVALID_GRAPH }
            val eventType = event.string("event_type")
            when (ordinal) {
                0 -> require(eventType == "run_started") { INVALID_GRAPH }
                run.evidenceEvents.lastIndex -> require(
                    eventType == when (run.status) {
                        PrototypeQuickCampaignRunner.RunStatus.COMPLETE -> "terminal_event"
                        PrototypeQuickCampaignRunner.RunStatus.INTERRUPTED -> "run_failed"
                        PrototypeQuickCampaignRunner.RunStatus.NOT_STARTED -> error(INVALID_GRAPH)
                    },
                ) { INVALID_GRAPH }

                else -> {
                    require(eventType == "content_event") { INVALID_GRAPH }
                    val details = event["details"] as? JsonObject
                        ?: throw IllegalArgumentException(INVALID_GRAPH)
                    require(details.keys == CANONICAL_STORED_CONTENT_DETAIL_KEYS) { INVALID_GRAPH }
                    require(details.exactLong("seq") == ordinal.toLong()) { INVALID_GRAPH }
                    val expectedPayloadId = "ref-${ordinal.toString().padStart(4, '0')}"
                    require(details["payload_id"] == JsonPrimitive(expectedPayloadId)) {
                        INVALID_GRAPH
                    }
                }
            }
        }
        if (run.status == PrototypeQuickCampaignRunner.RunStatus.COMPLETE) {
            val terminalDetails = run.evidenceEvents.last()["details"] as? JsonObject
                ?: throw IllegalArgumentException(INVALID_GRAPH)
            require(terminalDetails.size == TERMINAL_DETAIL_KEY_COUNT) { INVALID_GRAPH }
        }
    }

    private fun JsonObject.string(key: String): String {
        val value = this[key] as? JsonPrimitive ?: throw IllegalArgumentException(INVALID_GRAPH)
        require(value.isString) { INVALID_GRAPH }
        return value.content
    }

    private fun JsonObject.exactLong(key: String): Long {
        val value = this[key] as? JsonPrimitive ?: throw IllegalArgumentException(INVALID_GRAPH)
        require(!value.isString && value !== JsonNull) { INVALID_GRAPH }
        return value.content.toLongOrNull() ?: throw IllegalArgumentException(INVALID_GRAPH)
    }

    private companion object {
        const val CAMPAIGN_MODE = "quick"
        const val EXPECTED_RUNS = 3
        const val EXPECTED_CONTENT_EVENTS = 120
        const val COMPLETE_EVIDENCE_EVENTS = 122
        const val TERMINAL_DETAIL_KEY_COUNT = 24
        const val INTERRUPTED_REASON = "stream_interrupted"
        const val NOT_STARTED_REASON = "not_started"
        const val INVALID_GRAPH = "prototype campaign persistence graph is inconsistent"
        val EXPECTED_RUN_INDICES = listOf(1, 2, 3)
        val EXPECTED_CONDITIONS = listOf(
            "baseline_v0.1",
            "slow_v0.1",
            "unstable_v0.1",
        )
        val CANONICAL_STORED_EVENT_KEYS = setOf(
            "schema_version",
            "campaign_id",
            "run_id",
            "campaign_mode",
            "run_index",
            "condition_id",
            "condition_version",
            "nominal_interval_ms",
            "profile_manifest_sha256",
            "schedule_hash",
            "event_type",
            "client_monotonic_ns",
            "clock_source",
            "clock_unit",
            "clock_epoch",
            "clock_domain_id",
            "source",
            "details",
        )
        private val CANONICAL_STORED_CONTENT_DETAIL_KEYS = setOf(
            "seq",
            "planned_offset_ms",
            "payload_id",
        )
        private val EVENT_TYPE_KEY_INDEX = CANONICAL_STORED_EVENT_KEYS.indexOf("event_type")
        private val DETAILS_KEY_INDEX = CANONICAL_STORED_EVENT_KEYS.indexOf("details")
        private val EVIDENCE_PROBE_JSON = Json {
            ignoreUnknownKeys = true
            isLenient = false
        }

        private object StoredContentDetailsDuplicateKeyProbe : DeserializationStrategy<Boolean> {
            override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
                "PrototypeStoredContentDetailsDuplicateKeyProbe",
            ) {
                CANONICAL_STORED_CONTENT_DETAIL_KEYS.forEach { key ->
                    element(key, JsonElement.serializer().descriptor, isOptional = true)
                }
            }

            override fun deserialize(decoder: Decoder): Boolean {
                var duplicateKey = false
                decoder.decodeStructure(descriptor) {
                    val seen = BooleanArray(CANONICAL_STORED_CONTENT_DETAIL_KEYS.size)
                    while (true) {
                        when (val index = decodeElementIndex(descriptor)) {
                            CompositeDecoder.DECODE_DONE -> break
                            in seen.indices -> {
                                if (seen[index]) duplicateKey = true
                                seen[index] = true
                                decodeSerializableElement(
                                    descriptor,
                                    index,
                                    JsonElement.serializer(),
                                )
                            }

                            else -> throw IllegalArgumentException(INVALID_GRAPH)
                        }
                    }
                }
                return duplicateKey
            }
        }

        private object StoredEvidenceRootDuplicateKeyProbe : DeserializationStrategy<Unit> {
            override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
                "PrototypeStoredEvidenceRootDuplicateKeyProbe",
            ) {
                CANONICAL_STORED_EVENT_KEYS.forEach { key ->
                    val valueDescriptor = if (key == "details") {
                        StoredContentDetailsDuplicateKeyProbe.descriptor
                    } else {
                        JsonElement.serializer().descriptor
                    }
                    element(key, valueDescriptor, isOptional = true)
                }
            }

            override fun deserialize(decoder: Decoder) {
                var eventType: String? = null
                var duplicateContentDetailKey = false
                decoder.decodeStructure(descriptor) {
                    val seen = BooleanArray(CANONICAL_STORED_EVENT_KEYS.size)
                    while (true) {
                        when (val index = decodeElementIndex(descriptor)) {
                            CompositeDecoder.DECODE_DONE -> break
                            in seen.indices -> {
                                require(!seen[index]) { INVALID_GRAPH }
                                seen[index] = true
                                when (index) {
                                    EVENT_TYPE_KEY_INDEX -> {
                                        val value = decodeSerializableElement(
                                            descriptor,
                                            index,
                                            JsonElement.serializer(),
                                        )
                                        eventType = (value as? JsonPrimitive)
                                            ?.takeIf { it.isString }
                                            ?.content
                                    }

                                    DETAILS_KEY_INDEX -> {
                                        duplicateContentDetailKey = decodeSerializableElement(
                                            descriptor,
                                            index,
                                            StoredContentDetailsDuplicateKeyProbe,
                                        )
                                    }

                                    else -> decodeSerializableElement(
                                        descriptor,
                                        index,
                                        JsonElement.serializer(),
                                    )
                                }
                            }

                            else -> throw IllegalArgumentException(INVALID_GRAPH)
                        }
                    }
                }
                if (eventType == "content_event") {
                    require(!duplicateContentDetailKey) { INVALID_GRAPH }
                }
            }
        }

        val JSON = Json
    }
}

@Serializable
private data class CapabilityWorkloadRecord(
    val id: String,
    val version: String,
    val contentEventCount: Int,
) {
    fun toIdentity() = PrototypeCapabilityWorkloadIdentity(id, version, contentEventCount)

    companion object {
        fun from(identity: PrototypeCapabilityWorkloadIdentity) = CapabilityWorkloadRecord(
            identity.id,
            identity.version,
            identity.contentEventCount,
        )
    }
}

@Serializable
private data class CapabilityConditionRecord(
    val id: String,
    val version: String,
    val nominalIntervalMs: Int,
    val scheduleSha256: String,
) {
    fun toIdentity() = PrototypeCapabilityConditionIdentity(
        id,
        version,
        nominalIntervalMs,
        scheduleSha256,
    )

    companion object {
        fun from(identity: PrototypeCapabilityConditionIdentity) = CapabilityConditionRecord(
            identity.id,
            identity.version,
            identity.nominalIntervalMs,
            identity.scheduleSha256,
        )
    }
}

@Serializable
private data class CapabilityIdentityRecord(
    val schemaVersion: String,
    val productVersion: String,
    val protocolVersion: String,
    val serverVersion: String,
    val serverBinarySha256: String,
    val claimScope: String,
    val evidenceMode: String,
    val impairmentLayer: String,
    val profileManifestSha256: String,
    val workload: CapabilityWorkloadRecord,
    val conditions: List<CapabilityConditionRecord>,
    val evidenceSchemaVersion: String,
    val scorePolicyId: String,
    val terminalReceiptVersion: String,
) {
    fun toIdentity() = PrototypeCapabilityIdentity(
        schemaVersion = schemaVersion,
        productVersion = productVersion,
        protocolVersion = protocolVersion,
        serverVersion = serverVersion,
        serverBinarySha256 = serverBinarySha256,
        claimScope = claimScope,
        evidenceMode = evidenceMode,
        impairmentLayer = impairmentLayer,
        profileManifestSha256 = profileManifestSha256,
        workload = workload.toIdentity(),
        conditions = conditions.map(CapabilityConditionRecord::toIdentity),
        evidenceSchemaVersion = evidenceSchemaVersion,
        scorePolicyId = scorePolicyId,
        terminalReceiptVersion = terminalReceiptVersion,
    )

    companion object {
        fun from(identity: PrototypeCapabilityIdentity) = CapabilityIdentityRecord(
            schemaVersion = identity.schemaVersion,
            productVersion = identity.productVersion,
            protocolVersion = identity.protocolVersion,
            serverVersion = identity.serverVersion,
            serverBinarySha256 = identity.serverBinarySha256,
            claimScope = identity.claimScope,
            evidenceMode = identity.evidenceMode,
            impairmentLayer = identity.impairmentLayer,
            profileManifestSha256 = identity.profileManifestSha256,
            workload = CapabilityWorkloadRecord.from(identity.workload),
            conditions = identity.conditions.map(CapabilityConditionRecord::from),
            evidenceSchemaVersion = identity.evidenceSchemaVersion,
            scorePolicyId = identity.scorePolicyId,
            terminalReceiptVersion = identity.terminalReceiptVersion,
        )
    }
}

@Serializable
private data class RunMetricsRecord(
    val ttftMs: Double?,
    val completionMs: Double?,
    val streamSpanMs: Double?,
    val streamEventRateEps: Double?,
    val stallThresholdMs: Double?,
    val stallCount: Int?,
    val stallDurationMs: Double?,
    val stallFraction: Double?,
) {
    fun toMetrics() = PrototypeQuickCampaignRunner.RunMetrics(
        ttftMs = ttftMs,
        completionMs = completionMs,
        streamSpanMs = streamSpanMs,
        streamEventRateEps = streamEventRateEps,
        stallThresholdMs = stallThresholdMs,
        stallCount = stallCount,
        stallDurationMs = stallDurationMs,
        stallFraction = stallFraction,
    )

    companion object {
        fun from(metrics: PrototypeQuickCampaignRunner.RunMetrics) = RunMetricsRecord(
            ttftMs = metrics.ttftMs,
            completionMs = metrics.completionMs,
            streamSpanMs = metrics.streamSpanMs,
            streamEventRateEps = metrics.streamEventRateEps,
            stallThresholdMs = metrics.stallThresholdMs,
            stallCount = metrics.stallCount,
            stallDurationMs = metrics.stallDurationMs,
            stallFraction = metrics.stallFraction,
        )
    }
}

@Serializable
private data class ConditionSummaryRecord(
    val conditionId: String,
    val plannedRuns: Int,
    val attemptedRuns: Int,
    val successfulRuns: Int,
    val failedRuns: Int,
    val notStartedRuns: Int,
    val successRate: Double,
    val confidence: String,
    val medianTtftMs: Double?,
    val minTtftMs: Double?,
    val maxTtftMs: Double?,
    val medianCompletionMs: Double?,
    val minCompletionMs: Double?,
    val maxCompletionMs: Double?,
    val medianStreamEventRateEps: Double?,
    val medianStallCount: Double?,
    val medianStallDurationMs: Double?,
    val medianStallFraction: Double?,
    val rpi: Int?,
    val rpiPolicyId: String,
    val primaryNullReason: String?,
    val allNullReasons: List<String>?,
) {
    fun toSummary() = PrototypeQuickCampaignRunner.ConditionSummary(
        conditionId = conditionId,
        plannedRuns = plannedRuns,
        attemptedRuns = attemptedRuns,
        successfulRuns = successfulRuns,
        failedRuns = failedRuns,
        notStartedRuns = notStartedRuns,
        successRate = successRate,
        confidence = enumValueOf(confidence),
        medianTtftMs = medianTtftMs,
        minTtftMs = minTtftMs,
        maxTtftMs = maxTtftMs,
        medianCompletionMs = medianCompletionMs,
        minCompletionMs = minCompletionMs,
        maxCompletionMs = maxCompletionMs,
        medianStreamEventRateEps = medianStreamEventRateEps,
        medianStallCount = medianStallCount,
        medianStallDurationMs = medianStallDurationMs,
        medianStallFraction = medianStallFraction,
        rpi = rpi,
        rpiPolicyId = rpiPolicyId,
        primaryNullReason = primaryNullReason,
        allNullReasons = allNullReasons,
    )

    companion object {
        fun from(summary: PrototypeQuickCampaignRunner.ConditionSummary) = ConditionSummaryRecord(
            conditionId = summary.conditionId,
            plannedRuns = summary.plannedRuns,
            attemptedRuns = summary.attemptedRuns,
            successfulRuns = summary.successfulRuns,
            failedRuns = summary.failedRuns,
            notStartedRuns = summary.notStartedRuns,
            successRate = summary.successRate,
            confidence = summary.confidence.name,
            medianTtftMs = summary.medianTtftMs,
            minTtftMs = summary.minTtftMs,
            maxTtftMs = summary.maxTtftMs,
            medianCompletionMs = summary.medianCompletionMs,
            minCompletionMs = summary.minCompletionMs,
            maxCompletionMs = summary.maxCompletionMs,
            medianStreamEventRateEps = summary.medianStreamEventRateEps,
            medianStallCount = summary.medianStallCount,
            medianStallDurationMs = summary.medianStallDurationMs,
            medianStallFraction = summary.medianStallFraction,
            rpi = summary.rpi,
            rpiPolicyId = summary.rpiPolicyId,
            primaryNullReason = summary.primaryNullReason,
            allNullReasons = summary.allNullReasons,
        )
    }
}

@Serializable
private data class CampaignSummaryRecord(
    val campaignId: String,
    val campaignMode: String,
    val plannedRuns: Int,
    val attemptedRuns: Int,
    val successfulRuns: Int,
    val failedRuns: Int,
    val notStartedRuns: Int,
    val successRate: Double,
    val status: String,
    val conditionSummaries: List<ConditionSummaryRecord>,
) {
    fun toSummary() = PrototypeQuickCampaignRunner.CampaignSummary(
        campaignId = campaignId,
        campaignMode = campaignMode,
        plannedRuns = plannedRuns,
        attemptedRuns = attemptedRuns,
        successfulRuns = successfulRuns,
        failedRuns = failedRuns,
        notStartedRuns = notStartedRuns,
        successRate = successRate,
        status = enumValueOf(status),
        conditionSummaries = conditionSummaries.map(ConditionSummaryRecord::toSummary),
    )

    companion object {
        fun from(summary: PrototypeQuickCampaignRunner.CampaignSummary) = CampaignSummaryRecord(
            campaignId = summary.campaignId,
            campaignMode = summary.campaignMode,
            plannedRuns = summary.plannedRuns,
            attemptedRuns = summary.attemptedRuns,
            successfulRuns = summary.successfulRuns,
            failedRuns = summary.failedRuns,
            notStartedRuns = summary.notStartedRuns,
            successRate = summary.successRate,
            status = summary.status.name,
            conditionSummaries = summary.conditionSummaries.map(ConditionSummaryRecord::from),
        )
    }
}
