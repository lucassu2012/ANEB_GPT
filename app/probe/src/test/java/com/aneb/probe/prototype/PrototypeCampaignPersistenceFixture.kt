package com.aneb.probe.prototype

import com.aneb.probe.net.AnebClient
import com.aneb.probe.net.MonotonicNanosClock
import com.aneb.probe.net.RawSseEvent
import com.aneb.probe.net.RawSseStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

internal const val PERSISTENCE_LARGE_MONOTONIC_NS = 9_007_199_254_740_993L
internal const val TEST_PROFILE_MANIFEST_SHA256 =
    "44393ddd5ed11a5091038a85d08ab65ee91a8566997e837d2c40fd3add57d5dc"

internal fun completeBaselineStream(campaignId: String, runId: String): RawSseStream =
    completeStream(campaignId, runId, "baseline_v0.1")

internal fun completeStream(
    campaignId: String,
    runId: String,
    conditionId: String,
): RawSseStream {
    val blocks = buildList {
        add(runStartedBlock(campaignId, runId, conditionId))
        repeat(120) { index ->
            add(contentBlock(campaignId, runId, conditionId, index + 1))
        }
        add(doneBlock(campaignId, runId, conditionId))
    }
    return rawStream(blocks, truncatedTail = false)
}

internal fun interruptedSlowStream(campaignId: String, runId: String): RawSseStream =
    interruptedStream(campaignId, runId, "slow_v0.1")

internal fun interruptedStream(
    campaignId: String,
    runId: String,
    conditionId: String,
    truncatedTail: Boolean = false,
): RawSseStream = rawStream(
    blocks = listOf(
        runStartedBlock(campaignId, runId, conditionId),
        contentBlock(campaignId, runId, conditionId, sequence = 1),
    ),
    truncatedTail = truncatedTail,
)

internal fun runStartedBlock(campaignId: String, runId: String, conditionId: String): String {
    val condition = evidenceCondition(conditionId)
    val serverT0 = condition.runIndex * 1_000_000L
    return "event: run_started\ndata: " + buildJsonObject {
        put("schema_version", JsonPrimitive("aneb-prototype-evidence-0.1"))
        put("protocol_version", JsonPrimitive("prototype-stream-0.1"))
        put("campaign_id", JsonPrimitive(campaignId))
        put("run_id", JsonPrimitive(runId))
        put("condition_id", JsonPrimitive(conditionId))
        put("event_type", JsonPrimitive("run_started"))
        put("server_monotonic_ns", JsonPrimitive(serverT0))
        put("clock_source", JsonPrimitive("server.monotonic"))
        put("clock_unit", JsonPrimitive("ns"))
        put("clock_epoch", JsonPrimitive("process"))
        put("source", JsonPrimitive("server"))
        put("details", buildJsonObject {
            put("profile_id", JsonPrimitive("streaming_text_reference_v0.1"))
            put("profile_version", JsonPrimitive("0.1"))
            put("profile_manifest_sha256", JsonPrimitive(TEST_PROFILE_MANIFEST_SHA256))
            put("schedule_hash", JsonPrimitive(condition.scheduleHash))
            put("nominal_interval_ms", JsonPrimitive(condition.nominalIntervalMs))
            put("t0_monotonic_ns", JsonPrimitive(serverT0))
        })
    }
}

internal fun contentBlock(
    campaignId: String,
    runId: String,
    conditionId: String,
    sequence: Int,
): String {
    val condition = evidenceCondition(conditionId)
    return "event: content_event\ndata: " + buildJsonObject {
        put("schema_version", JsonPrimitive("aneb-prototype-evidence-0.1"))
        put("protocol_version", JsonPrimitive("prototype-stream-0.1"))
        put("event_type", JsonPrimitive("content_event"))
        put("campaign_id", JsonPrimitive(campaignId))
        put("run_id", JsonPrimitive(runId))
        put("condition_id", JsonPrimitive(conditionId))
        put(
            "server_monotonic_ns",
            JsonPrimitive(
                condition.runIndex * 1_000_000L +
                    plannedOffsetMs(conditionId, sequence) * 1_000_000L,
            ),
        )
        put("clock_source", JsonPrimitive("server.monotonic"))
        put("clock_unit", JsonPrimitive("ns"))
        put("clock_epoch", JsonPrimitive("process"))
        put("source", JsonPrimitive("server"))
        put("details", buildJsonObject {
            put("seq", JsonPrimitive(sequence))
            put("planned_offset_ms", JsonPrimitive(plannedOffsetMs(conditionId, sequence)))
            put("payload_id", JsonPrimitive("ref-${sequence.toString().padStart(4, '0')}"))
            put("profile_manifest_sha256", JsonPrimitive(TEST_PROFILE_MANIFEST_SHA256))
            put("schedule_hash", JsonPrimitive(condition.scheduleHash))
        })
    }
}

internal fun doneBlock(campaignId: String, runId: String, conditionId: String): String {
    val condition = evidenceCondition(conditionId)
    var fixture = readFixture("prototype_option_a_done_frame.sse")
        .replace("\"campaign-fixture-01\"", "\"$campaignId\"")
        .replace("\"run-fixture-01\"", "\"$runId\"")
    val conditionMember = "\"condition_id\":\"baseline_v0.1\""
    require(fixture.split(conditionMember).size - 1 == 2)
    fixture = fixture.replace(conditionMember, "\"condition_id\":\"$conditionId\"")
    val baseline = evidenceCondition("baseline_v0.1")
    val scheduleMember = "\"schedule_hash\":\"${baseline.scheduleHash}\""
    require(fixture.split(scheduleMember).size - 1 == 1)
    fixture = fixture.replace(scheduleMember, "\"schedule_hash\":\"${condition.scheduleHash}\"")
    val nominalMember = "\"nominal_interval_ms\":50"
    require(fixture.split(nominalMember).size - 1 == 1)
    fixture = fixture.replace(nominalMember, "\"nominal_interval_ms\":${condition.nominalIntervalMs}")
    val indexMember = "\"run_index\":1"
    require(fixture.split(indexMember).size - 1 == 1)
    fixture = fixture.replace(indexMember, "\"run_index\":${condition.runIndex}")
    return fixture.removeSuffix("\n\n")
}

internal data class EvidenceCondition(
    val id: String,
    val runIndex: Int,
    val nominalIntervalMs: Int,
    val initialDelayMs: Int,
    val scheduleHash: String,
)

internal fun evidenceCondition(conditionId: String): EvidenceCondition = when (conditionId) {
    "baseline_v0.1" -> EvidenceCondition(
        id = conditionId,
        runIndex = 1,
        nominalIntervalMs = 50,
        initialDelayMs = 200,
        scheduleHash = "46eced73d2fbc886040a3357f84551d424a95e15d6e9e69c16958f6e52e33d7e",
    )
    "slow_v0.1" -> EvidenceCondition(
        id = conditionId,
        runIndex = 2,
        nominalIntervalMs = 125,
        initialDelayMs = 650,
        scheduleHash = "b51b27fe8332b3fc8a97472a44312b3001ccd54364a61ed8799816c299d27062",
    )
    "unstable_v0.1" -> EvidenceCondition(
        id = conditionId,
        runIndex = 3,
        nominalIntervalMs = 65,
        initialDelayMs = 350,
        scheduleHash = "d11dce2a877d7c3772a4552f2d922d5f96730c9a01bb829f0203c65b110a8c58",
    )
    else -> error("unknown test condition: $conditionId")
}

internal fun plannedOffsetMs(conditionId: String, sequence: Int): Int {
    val condition = evidenceCondition(conditionId)
    val scheduledPauses = if (conditionId == "unstable_v0.1") {
        (if (sequence > 40) 900 else 0) + (if (sequence > 85) 1_400 else 0)
    } else {
        0
    }
    return condition.initialDelayMs + (sequence - 1) * condition.nominalIntervalMs + scheduledPauses
}

internal fun rawStream(blocks: List<String>, truncatedTail: Boolean): RawSseStream {
    val text = blocks.joinToString(separator = "\n\n", postfix = "\n\n")
    return RawSseStream(
        events = blocks.mapIndexed { index, block ->
            RawSseEvent(
                bytes = block.toByteArray(Charsets.UTF_8),
                arrivalNanos = (index + 1) * 1_000L,
                sameReadBatch = false,
            )
        },
        readCount = blocks.size,
        totalBytes = text.toByteArray(Charsets.UTF_8).size.toLong(),
        truncatedTail = truncatedTail,
        eofNanos = (blocks.size + 1) * 1_000L,
    )
}

internal fun readFixture(name: String): String {
    val candidates = listOf(
        Path.of("server/testdata/$name"),
        Path.of("../../server/testdata/$name"),
    )
    val path = candidates.firstOrNull(Files::isRegularFile)
        ?: error("shared fixture not found: ${candidates.joinToString()}")
    return Files.readAllBytes(path).toString(Charsets.UTF_8).replace("\r\n", "\n").also {
        require('\r' !in it) { "shared fixture contains a bare CR" }
    }
}

internal class QueuedRawPostTransport(
    private val streams: ArrayDeque<RawSseStream>,
) : PrototypeRawPostTransport {
    val postedBodies = mutableListOf<String>()

    override suspend fun post(url: String, requestBody: String): RawSseStream {
        postedBodies += requestBody
        return streams.removeFirstOrNull()
            ?: error("not_started Quick slot reached the transport")
    }
}

internal class IncrementingClock : MonotonicNanosClock {
    private var next = 1_000_000L

    override fun now(): Long = next++
}

internal class RecordingSteppedClock : MonotonicNanosClock {
    private var next = 2_000_000L
    val samples = mutableListOf<Long>()

    override fun now(): Long = next.also { sample ->
        samples += sample
        next += 10L
    }
}

/** Room persistence carrier using the actual Quick runner, adapter, projector, metrics, and summary. */
internal object PrototypeCampaignPersistenceFixture {
    const val LARGE_MONOTONIC_NS = PERSISTENCE_LARGE_MONOTONIC_NS
    const val RUN_URL = "http://127.0.0.1:18088/api/v1/prototype/runs"
    const val COMPLETE_CAMPAIGN_ID = "campaign-room-v13-complete"
    const val PARTIAL_CAMPAIGN_ID = "campaign-room-v13-partial"

    fun campaignConfig(
        campaignId: String,
        runUrl: String = RUN_URL,
    ): PrototypeCampaignConfig {
        val rawCapabilityBody = formalCapabilityBody()
        val ticket = AnebClientPrototypeRawPostTransport(AnebClient())
            .ticketFromValidatedSnapshot(runUrl, rawCapabilityBody)
        check(ticket.rawCapabilityBody == rawCapabilityBody)
        return PrototypeCampaignConfig(ticket, campaignId)
    }

    suspend fun completeQuickCampaign(
        config: PrototypeCampaignConfig,
    ): PrototypeQuickCampaignRunner.CampaignResult {
        val runIds = runIds(config.campaignId)
        val conditions = listOf("baseline_v0.1", "slow_v0.1", "unstable_v0.1")
        val streams = conditions.mapIndexed { index, conditionId ->
            TicketBoundStream(
                campaignId = config.campaignId,
                runId = runIds[index],
                conditionId = conditionId,
                stream = completeStream(config.campaignId, runIds[index], conditionId),
            )
        }
        val result = runner(
            config = config,
            streams = streams,
            runIds = runIds,
            clockSamples = completeClockSegments(PERSISTENCE_LARGE_MONOTONIC_NS).flatten(),
        ).run(config.nodeTicket.runUrl, config.campaignId)
        check(result.summary.conditionSummaries.map { it.rpi } == listOf(100, 48, 62))
        return result
    }

    suspend fun partialQuickCampaign(
        config: PrototypeCampaignConfig,
    ): PrototypeQuickCampaignRunner.CampaignResult {
        val runIds = runIds(config.campaignId)
        val baselineSamples = completeClockSegments(PERSISTENCE_LARGE_MONOTONIC_NS).first()
        val slowT0 = PERSISTENCE_LARGE_MONOTONIC_NS + 100_000_000_000L
        val clockSamples = baselineSamples + listOf(
            slowT0,
            slowT0 + 650_000_000L,
            slowT0 + 1_000_000_000L,
        )
        return runner(
            config = config,
            streams = listOf(
                TicketBoundStream(
                    campaignId = config.campaignId,
                    runId = runIds[0],
                    conditionId = "baseline_v0.1",
                    stream = completeBaselineStream(config.campaignId, runIds[0]),
                ),
                TicketBoundStream(
                    campaignId = config.campaignId,
                    runId = runIds[1],
                    conditionId = "slow_v0.1",
                    stream = interruptedSlowStream(config.campaignId, runIds[1]),
                ),
            ),
            runIds = runIds,
            clockSamples = clockSamples,
        ).run(config.nodeTicket.runUrl, config.campaignId)
    }

    fun formalCapabilityBody(): String = """
        {
          "terminal_receipt_version" : "prototype-terminal-receipt-0.1",
          "score_policy_id" : "rpi-0.1",
          "evidence_schema_version" : "aneb-prototype-evidence-0.1",
          "conditions" : [
            {"schedule_sha256":"46eced73d2fbc886040a3357f84551d424a95e15d6e9e69c16958f6e52e33d7e","nominal_interval_ms":5e1,"version":"0.1","id":"baseline_v0.1"},
            {"schedule_sha256":"b51b27fe8332b3fc8a97472a44312b3001ccd54364a61ed8799816c299d27062","nominal_interval_ms":125.0,"version":"0.1","id":"slow_v0.1"},
            {"schedule_sha256":"d11dce2a877d7c3772a4552f2d922d5f96730c9a01bb829f0203c65b110a8c58","nominal_interval_ms":6.5e1,"version":"0.1","id":"unstable_v0.1"}
          ],
          "workload" : {"content_event_count":120.0,"version":"0.1","id":"streaming_text_reference_v0.1"},
          "profile_manifest_sha256" : "$TEST_PROFILE_MANIFEST_SHA256",
          "impairment_layer" : "application",
          "evidence_mode" : "synthetic_application_impairment",
          "claim_scope" : "application_end_to_end_to_probe_node",
          "server_binary_sha256" : "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
          "server_version" : "room-v13-节点-正式",
          "protocol_version" : "prototype-stream-0.1",
          "product_version" : "prototype-0.1",
          "schema_version" : "aneb-prototype-capabilities-0.1"
        }
    """.trimIndent() + "\n"

    private fun runner(
        config: PrototypeCampaignConfig,
        streams: List<TicketBoundStream>,
        runIds: List<String>,
        clockSamples: List<Long>,
    ): PrototypeQuickCampaignRunner {
        requireTicketAuthority(config.nodeTicket)
        val queue = ArrayDeque(clockSamples)
        val clock = object : MonotonicNanosClock {
            override fun now(): Long = queue.removeFirstOrNull()
                ?: error("persistence fixture clock exhausted")
        }
        return PrototypeQuickCampaignRunner(
            streamAdapter = PrototypeRunStreamAdapter(
                transport = TicketBoundQueuedRawPostTransport(
                    ticket = config.nodeTicket,
                    streams = ArrayDeque(streams),
                ),
                clock = clock,
            ),
            runIdFactory = { index -> runIds[index - 1] },
            clockDomainIdFactory = { index -> "room-v13-clock-domain-$index" },
            waitBetweenRuns = {},
        )
    }

    private fun completeClockSegments(firstT0: Long): List<List<Long>> =
        listOf("baseline_v0.1", "slow_v0.1", "unstable_v0.1").mapIndexed {
                index,
                conditionId,
            ->
            val condition = evidenceCondition(conditionId)
            val t0 = firstT0 + index * 100_000_000_000L
            buildList {
                add(t0)
                repeat(120) { contentIndex ->
                    add(t0 + plannedOffsetMs(conditionId, contentIndex + 1) * 1_000_000L)
                }
                add(
                    t0 +
                        (plannedOffsetMs(conditionId, 120) + condition.nominalIntervalMs) *
                        1_000_000L,
                )
            }.also { samples ->
                check(samples.size == 122)
                check(samples.zipWithNext().all { (previous, next) -> next > previous })
            }
        }

    private fun runIds(campaignId: String): List<String> =
        (1..3).map { index -> "$campaignId-run-${index.toString().padStart(2, '0')}" }

    private data class TicketBoundStream(
        val campaignId: String,
        val runId: String,
        val conditionId: String,
        val stream: RawSseStream,
    )

    private class TicketBoundQueuedRawPostTransport(
        private val ticket: CompatibleNodeTicket,
        private val streams: ArrayDeque<TicketBoundStream>,
    ) : PrototypeRawPostTransport {
        private val json = Json { ignoreUnknownKeys = false }

        override suspend fun post(url: String, requestBody: String): RawSseStream {
            val endpoint = PrototypeNodeEndpoint.parseRunUrl(url)
            require(url == ticket.runUrl) { "persistence fixture must execute the ticket run URL" }
            require(endpoint.baseUrl == ticket.nodeBaseUrl)
            require(endpoint.runUrl == ticket.runUrl)
            require(endpoint.capabilityUrl == ticket.capabilityUrl)
            val request = json.parseToJsonElement(requestBody).jsonObject
            val conditionId = request.getValue("condition_id").jsonPrimitive.content
            val carrier = streams.removeFirstOrNull()
                ?: error("not-started Quick slot reached the ticket-bound transport")
            require(carrier.conditionId == conditionId)
            require(request.keys == REQUEST_KEYS)
            require(request.getValue("protocol_version").jsonPrimitive.content == ticket.identity.protocolVersion)
            require(request.getValue("campaign_id").jsonPrimitive.content == carrier.campaignId)
            require(request.getValue("run_id").jsonPrimitive.content == carrier.runId)
            require(request.getValue("campaign_mode").jsonPrimitive.content == "quick")
            require(request.getValue("run_index").jsonPrimitive.int == evidenceCondition(conditionId).runIndex)
            require(request.getValue("workload_id").jsonPrimitive.content == ticket.identity.workload.id)
            require(request.getValue("workload_version").jsonPrimitive.content == ticket.identity.workload.version)
            require(request.getValue("profile_id").jsonPrimitive.content == "streaming_text_reference_v0.1")
            require(request.getValue("profile_version").jsonPrimitive.content == "0.1")
            require(
                request.getValue("profile_manifest_sha256").jsonPrimitive.content ==
                    ticket.identity.profileManifestSha256,
            )
            require(request.getValue("condition_version").jsonPrimitive.content == "0.1")
            val ticketCondition = ticket.identity.conditions.single { it.id == conditionId }
            val fixtureCondition = evidenceCondition(conditionId)
            require(ticketCondition.version == "0.1")
            require(ticketCondition.nominalIntervalMs == fixtureCondition.nominalIntervalMs)
            require(ticketCondition.scheduleSha256 == fixtureCondition.scheduleHash)
            require(ticket.identity.workload.contentEventCount == 120)
            require(ticket.identity.profileManifestSha256 == TEST_PROFILE_MANIFEST_SHA256)
            requireStreamMatchesTicket(carrier, ticketCondition)
            return carrier.stream
        }

        private companion object {
            val REQUEST_KEYS = setOf(
                "protocol_version",
                "campaign_id",
                "run_id",
                "campaign_mode",
                "run_index",
                "workload_id",
                "workload_version",
                "profile_id",
                "profile_version",
                "profile_manifest_sha256",
                "condition_id",
                "condition_version",
            )
        }

        private fun requireStreamMatchesTicket(
            carrier: TicketBoundStream,
            ticketCondition: PrototypeCapabilityConditionIdentity,
        ) {
            require(carrier.stream.events.isNotEmpty())
            carrier.stream.events.forEach { raw ->
                val lines = raw.bytes.toString(Charsets.UTF_8).lineSequence().toList()
                require(lines.size == 2)
                require(lines[1].startsWith("data: "))
                val envelope = json.parseToJsonElement(lines[1].removePrefix("data: ")).jsonObject
                require(envelope.getValue("campaign_id").jsonPrimitive.content == carrier.campaignId)
                require(envelope.getValue("run_id").jsonPrimitive.content == carrier.runId)
                require(envelope.getValue("condition_id").jsonPrimitive.content == carrier.conditionId)
                require(envelope.getValue("protocol_version").jsonPrimitive.content == ticket.identity.protocolVersion)
                when (envelope.getValue("event_type").jsonPrimitive.content) {
                    "run_started" -> {
                        val details = envelope.getValue("details").jsonObject
                        require(details.getValue("profile_id").jsonPrimitive.content == ticket.identity.workload.id)
                        require(details.getValue("profile_version").jsonPrimitive.content == ticket.identity.workload.version)
                        require(
                            details.getValue("profile_manifest_sha256").jsonPrimitive.content ==
                                ticket.identity.profileManifestSha256,
                        )
                        require(details.getValue("schedule_hash").jsonPrimitive.content == ticketCondition.scheduleSha256)
                        require(details.getValue("nominal_interval_ms").jsonPrimitive.int == ticketCondition.nominalIntervalMs)
                    }

                    "content_event" -> {
                        val details = envelope.getValue("details").jsonObject
                        require(
                            details.getValue("profile_manifest_sha256").jsonPrimitive.content ==
                                ticket.identity.profileManifestSha256,
                        )
                        require(details.getValue("schedule_hash").jsonPrimitive.content == ticketCondition.scheduleSha256)
                    }

                    "terminal_event" -> {
                        require(carrier.stream.events.size == 122)
                        val details = envelope.getValue("details") as JsonObject
                        require(details.getValue("campaign_id").jsonPrimitive.content == carrier.campaignId)
                        require(details.getValue("run_id").jsonPrimitive.content == carrier.runId)
                        require(details.getValue("condition_id").jsonPrimitive.content == carrier.conditionId)
                        require(details.getValue("profile_id").jsonPrimitive.content == ticket.identity.workload.id)
                        require(details.getValue("profile_version").jsonPrimitive.content == ticket.identity.workload.version)
                        require(
                            details.getValue("profile_manifest_sha256").jsonPrimitive.content ==
                                ticket.identity.profileManifestSha256,
                        )
                        require(details.getValue("schedule_hash").jsonPrimitive.content == ticketCondition.scheduleSha256)
                        require(details.getValue("nominal_interval_ms").jsonPrimitive.int == ticketCondition.nominalIntervalMs)
                    }

                    else -> error("unexpected persistence fixture event type")
                }
            }
        }
    }

    private fun requireTicketAuthority(ticket: CompatibleNodeTicket) {
        val identity = ticket.identity
        require(identity.protocolVersion == "prototype-stream-0.1")
        require(identity.workload.id == "streaming_text_reference_v0.1")
        require(identity.workload.version == "0.1")
        require(identity.workload.contentEventCount == 120)
        require(identity.profileManifestSha256 == TEST_PROFILE_MANIFEST_SHA256)
        require(identity.evidenceSchemaVersion == "aneb-prototype-evidence-0.1")
        require(identity.scorePolicyId == "rpi-0.1")
        require(identity.terminalReceiptVersion == "prototype-terminal-receipt-0.1")
        val expected = listOf("baseline_v0.1", "slow_v0.1", "unstable_v0.1")
        require(identity.conditions.map { it.id } == expected)
        identity.conditions.forEach { condition ->
            val fixture = evidenceCondition(condition.id)
            require(condition.version == "0.1")
            require(condition.nominalIntervalMs == fixture.nominalIntervalMs)
            require(condition.scheduleSha256 == fixture.scheduleHash)
        }
    }
}
