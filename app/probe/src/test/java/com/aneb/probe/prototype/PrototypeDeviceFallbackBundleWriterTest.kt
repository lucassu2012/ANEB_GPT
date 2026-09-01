package com.aneb.probe.prototype

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class PrototypeDeviceFallbackBundleWriterTest {
    @Test
    fun `cancelled result preserves run cancelled and not started topology in fallback`() =
        runBlocking {
            val config = PrototypeCampaignPersistenceFixture.campaignConfig(
                "campaign-cancelled-fallback",
            )
            val result = PrototypeCampaignPersistenceFixture.cancelledQuickCampaign(config)
            val snapshot = PrototypeDeviceFallbackBundleWriter.Snapshot(
                summary = result.summary,
                runs = result.runs.map { run ->
                    PrototypeDeviceFallbackBundleWriter.Run(
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
                    )
                },
                capabilityResponseUtf8 = config.nodeTicket.rawCapabilityBody.toByteArray(Charsets.UTF_8),
                eventJsonUtf8Records = result.runs.flatMap { run -> run.evidenceEvents }.map { event ->
                    event.toString().toByteArray(Charsets.UTF_8)
                },
            )
            val output = ByteArrayOutputStream()

            PrototypeDeviceFallbackBundleWriter.write(snapshot, output)

            val entries = readEntries(output.toByteArray()).associateBy(ZipContents::name)
            val campaign = Json.parseToJsonElement(
                entries.getValue("campaign-snapshot.json").bytes.toString(Charsets.UTF_8),
            ).jsonObject
            assertEquals("CANCELLED", campaign.getValue("campaign_status").jsonPrimitive.content)
            val runs = campaign.getValue("runs").jsonArray.map { it.jsonObject }
            assertEquals(
                listOf("CANCELLED", "NOT_STARTED", "NOT_STARTED"),
                runs.map { it.getValue("status").jsonPrimitive.content },
            )
            assertEquals("cancelled", runs.first().getValue("failure_reason").jsonPrimitive.content)
            val events = entries.getValue("events.jsonl").bytes.toString(Charsets.UTF_8)
                .lineSequence().filter(String::isNotBlank).map { line ->
                    Json.parseToJsonElement(line).jsonObject
                }.toList()
            assertEquals(
                listOf("run_started", "content_event", "run_cancelled"),
                events.map { it.getValue("event_type").jsonPrimitive.content },
            )
        }

    @Test
    fun `Acceptance snapshot preserves all nine runs and three aggregate summaries`() {
        val snapshot = acceptanceSnapshot()
        val output = ByteArrayOutputStream()

        PrototypeDeviceFallbackBundleWriter.write(snapshot, output)

        val entries = readEntries(output.toByteArray()).associateBy(ZipContents::name)
        val campaign = Json.parseToJsonElement(
            entries.getValue("campaign-snapshot.json").bytes.toString(Charsets.UTF_8),
        ).jsonObject
        assertEquals("acceptance", campaign.getValue("campaign_mode").jsonPrimitive.content)
        assertEquals(9, campaign.getValue("planned_runs").jsonPrimitive.int)
        assertEquals(9, campaign.getValue("attempted_runs").jsonPrimitive.int)
        val runs = campaign.getValue("runs").jsonArray.map { it.jsonObject }
        assertEquals((1..9).toList(), runs.map { it.getValue("run_index").jsonPrimitive.int })
        assertEquals(
            List(3) { listOf("baseline_v0.1", "slow_v0.1", "unstable_v0.1") }.flatten(),
            runs.map { it.getValue("condition_id").jsonPrimitive.content },
        )
        val summaries = campaign.getValue("condition_summaries").jsonArray.map { it.jsonObject }
        assertEquals(3, summaries.size)
        summaries.forEach { summary ->
            assertEquals(3, summary.getValue("planned_runs").jsonPrimitive.int)
            assertEquals("HIGH", summary.getValue("confidence").jsonPrimitive.content)
        }
    }

    @Test
    fun `complete persisted snapshot writes a deterministic unverified fallback zip`() {
        val capabilityBytes = """{ "server_version" : "节点-v1", "claim_scope" : "application_end_to_end_to_probe_node" }
""".toByteArray(Charsets.UTF_8)
        val eventRecords = listOf(
            """{"event_type":"run_started","note":"节点"}""".toByteArray(Charsets.UTF_8),
            """ { "note" : "lexical order kept", "event_type" : "content_event" }"""
                .toByteArray(Charsets.UTF_8),
        )
        val campaignId = "campaign/../must-not-be-a-path"
        val snapshot = completeSnapshot(
            campaignId = campaignId,
            capabilityBytes = capabilityBytes,
            eventRecords = eventRecords,
        )

        val firstSink = CloseTrackingOutputStream()
        PrototypeDeviceFallbackBundleWriter.write(snapshot, firstSink)
        val firstZip = firstSink.toByteArray()
        assertFalse(firstSink.closed)
        firstSink.write(0x5a)
        assertEquals(firstZip.size + 1, firstSink.size())

        val secondSink = CloseTrackingOutputStream()
        PrototypeDeviceFallbackBundleWriter.write(snapshot, secondSink)
        val secondZip = secondSink.toByteArray()
        assertFalse(secondSink.closed)
        assertArrayEquals(firstZip, secondZip)

        val entries = readEntries(firstZip)
        val expectedNames = EXPECTED_ENTRY_NAMES
        assertEquals(expectedNames, entries.map(ZipContents::name))
        assertEquals(expectedNames.size, entries.map(ZipContents::name).distinct().size)
        assertTrue(entries.none { entry -> entry.directory })
        assertTrue(entries.all { entry -> '/' !in entry.name && ".." !in entry.name })
        assertTrue(entries.all { entry -> campaignId !in entry.name })
        entries.forEach { entry ->
            assertEquals(ZipEntry.STORED, entry.method)
            assertEquals(entry.bytes.size.toLong(), entry.size)
            assertEquals(entry.size, entry.compressedSize)
            assertEquals(crc32(entry.bytes), entry.crc)
        }
        assertEquals(1, entries.map(ZipContents::timestamp).distinct().size)
        assertEquals(0L, entries.first().timestamp)
        assertTrue(
            entries.map(ZipContents::name).intersect(
                setOf(
                    "meta.json",
                    "runs.csv",
                    "summary.csv",
                    "report.html",
                    "run.log",
                    "manifest.json",
                ),
            ).isEmpty(),
        )

        val byName = entries.associateBy(ZipContents::name)
        val marker = byName.getValue("DEVICE_FALLBACK_UNVERIFIED.txt").bytes
        val markerText = marker.toString(Charsets.UTF_8)
        assertFalse(marker.startsWithUtf8Bom())
        assertTrue(markerText.contains("device_fallback_unverified"))
        assertTrue(markerText.contains("Local device evidence · unverified"))
        assertTrue(markerText.contains("does not satisfy G4/G5"))
        assertTrue(markerText.contains(FROZEN_CLAIM))

        assertArrayEquals(
            capabilityBytes,
            byName.getValue("capability-response.json").bytes,
        )
        val expectedEvents = ByteArrayOutputStream().apply {
            eventRecords.forEach { record ->
                write(record)
                write('\n'.code)
            }
        }.toByteArray()
        val emittedEvents = byName.getValue("events.jsonl").bytes
        assertArrayEquals(expectedEvents, emittedEvents)
        assertFalse(emittedEvents.startsWithUtf8Bom())

        val campaignSnapshot = Json.parseToJsonElement(
            byName.getValue("campaign-snapshot.json").bytes.toString(Charsets.UTF_8),
        ).jsonObject
        assertEquals(
            "aneb-prototype-device-fallback-0.1",
            campaignSnapshot.getValue("format_version").jsonPrimitive.content,
        )
        assertEquals(
            "device_fallback_unverified",
            campaignSnapshot.getValue("publication_status").jsonPrimitive.content,
        )
        assertFalse(campaignSnapshot.getValue("canonical_bundle").jsonPrimitive.boolean)
        assertEquals(snapshot.summary.campaignId, campaignSnapshot.getValue("campaign_id").jsonPrimitive.content)
        assertEquals("quick", campaignSnapshot.getValue("campaign_mode").jsonPrimitive.content)
        assertEquals("COMPLETE", campaignSnapshot.getValue("campaign_status").jsonPrimitive.content)
        assertEquals(3, campaignSnapshot.getValue("attempted_runs").jsonPrimitive.int)
        assertEquals(3, campaignSnapshot.getValue("successful_runs").jsonPrimitive.int)
        assertEquals(0, campaignSnapshot.getValue("failed_runs").jsonPrimitive.int)
        assertEquals(0, campaignSnapshot.getValue("not_started_runs").jsonPrimitive.int)
        assertEquals(1.0, campaignSnapshot.getValue("success_rate").jsonPrimitive.double, 0.0)
        assertEquals(RPI_LABEL, campaignSnapshot.getValue("rpi_label").jsonPrimitive.content)
        assertEquals(RPI_DISCLOSURE, campaignSnapshot.getValue("rpi_disclosure").jsonPrimitive.content)

        val firstRun = campaignSnapshot.getValue("runs").jsonArray.first().jsonObject
        assertEquals(0, firstRun.getValue("metrics").jsonObject.getValue("stall_count").jsonPrimitive.int)
        assertTrue(firstRun.getValue("failure_reason") === JsonNull)
        val conditionSummaries = campaignSnapshot.getValue("condition_summaries").jsonArray
        assertEquals(listOf(100, 48, 62), conditionSummaries.map { row ->
            row.jsonObject.getValue("rpi").jsonPrimitive.int
        })
        assertTrue(
            campaignSnapshot.recursiveKeys().intersect(
                setOf(
                    "aqs",
                    "grade",
                    "node_base_url",
                    "run_url",
                    "capability_url",
                    "query_token",
                    "credential",
                    "credentials",
                    "device_id",
                    "android_id",
                    "serial_number",
                ),
            ).isEmpty(),
        )

        val expectedChecksums = expectedNames.take(4).joinToString(separator = "") { name ->
            "${sha256(byName.getValue(name).bytes)}  $name\n"
        }
        val checksums = byName.getValue("SHA256SUMS.txt").bytes.toString(Charsets.UTF_8)
        assertEquals(expectedChecksums, checksums)
        assertFalse(checksums.contains("SHA256SUMS.txt"))
        checksums.lineSequence().filter(String::isNotEmpty).forEach { line ->
            assertTrue(line.substringBefore("  ").matches(Regex("[0-9a-f]{64}")))
        }
    }

    @Test
    fun `partial snapshot preserves incomplete runs canonical null reasons and unverified marker`() {
        val snapshot = partialSnapshot()
        val sink = CloseTrackingOutputStream()

        PrototypeDeviceFallbackBundleWriter.write(snapshot, sink)

        assertFalse(sink.closed)
        val entries = readEntries(sink.toByteArray())
        assertEquals(EXPECTED_ENTRY_NAMES, entries.map(ZipContents::name))
        val byName = entries.associateBy(ZipContents::name)
        val marker = byName.getValue("DEVICE_FALLBACK_UNVERIFIED.txt").bytes.toString(Charsets.UTF_8)
        assertTrue(marker.contains("device_fallback_unverified"))
        assertTrue(marker.contains("Local device evidence · unverified"))
        assertTrue(marker.contains("does not satisfy G4/G5"))
        assertTrue(marker.contains("is not canonical evidence"))
        assertTrue(marker.contains(FROZEN_CLAIM))

        val campaignSnapshot = Json.parseToJsonElement(
            byName.getValue("campaign-snapshot.json").bytes.toString(Charsets.UTF_8),
        ).jsonObject
        assertEquals(
            "device_fallback_unverified",
            campaignSnapshot.getValue("publication_status").jsonPrimitive.content,
        )
        assertFalse(campaignSnapshot.getValue("canonical_bundle").jsonPrimitive.boolean)
        assertEquals("PARTIAL", campaignSnapshot.getValue("campaign_status").jsonPrimitive.content)
        assertEquals(3, campaignSnapshot.getValue("planned_runs").jsonPrimitive.int)
        assertEquals(2, campaignSnapshot.getValue("attempted_runs").jsonPrimitive.int)
        assertEquals(1, campaignSnapshot.getValue("successful_runs").jsonPrimitive.int)
        assertEquals(1, campaignSnapshot.getValue("failed_runs").jsonPrimitive.int)
        assertEquals(1, campaignSnapshot.getValue("not_started_runs").jsonPrimitive.int)
        assertEquals(1.0 / 3.0, campaignSnapshot.getValue("success_rate").jsonPrimitive.double, 0.0)

        val emittedRuns = campaignSnapshot.getValue("runs").jsonArray.map { it.jsonObject }
        assertEquals(snapshot.runs.map { it.runIndex }, emittedRuns.map { it.getValue("run_index").jsonPrimitive.int })
        snapshot.runs.zip(emittedRuns).forEach { (expected, emitted) ->
            assertEquals(expected.runId, emitted.getValue("run_id").jsonPrimitive.content)
            assertEquals(expected.conditionId, emitted.getValue("condition_id").jsonPrimitive.content)
            assertEquals(expected.status.name, emitted.getValue("status").jsonPrimitive.content)
            assertEquals(expected.taskSuccess, emitted.getValue("task_success").jsonPrimitive.boolean)
            assertEquals(expected.scoreEligible, emitted.getValue("score_eligible").jsonPrimitive.boolean)
            assertEquals(expected.eventsExpected, emitted.getValue("events_expected").jsonPrimitive.int)
            assertEquals(expected.eventsReceived, emitted.getValue("events_received").jsonPrimitive.int)
        }

        val baseline = emittedRuns[0]
        assertTrue(baseline.getValue("failure_reason") === JsonNull)
        assertTrue(baseline.getValue("terminal_receipt_valid").jsonPrimitive.boolean)
        assertTrue(baseline.getValue("metrics") is JsonObject)
        val baselineStallCount = baseline.getValue("metrics").jsonObject.getValue("stall_count")
        assertFalse(baselineStallCount === JsonNull)
        assertEquals(0, baselineStallCount.jsonPrimitive.int)

        val interrupted = emittedRuns[1]
        assertEquals("stream_interrupted", interrupted.getValue("failure_reason").jsonPrimitive.content)
        assertTrue(interrupted.getValue("terminal_receipt_valid") === JsonNull)
        assertTrue(interrupted.getValue("metrics") === JsonNull)
        assertEquals(1, interrupted.getValue("events_received").jsonPrimitive.int)

        val notStarted = emittedRuns[2]
        assertEquals("not_started", notStarted.getValue("failure_reason").jsonPrimitive.content)
        assertTrue(notStarted.getValue("terminal_receipt_valid") === JsonNull)
        assertTrue(notStarted.getValue("metrics") === JsonNull)
        val notStartedEvents = notStarted.getValue("events_received")
        assertFalse(notStartedEvents === JsonNull)
        assertEquals(0, notStartedEvents.jsonPrimitive.int)

        val emittedConditions = campaignSnapshot.getValue("condition_summaries").jsonArray.map { it.jsonObject }
        assertEquals(
            snapshot.summary.conditionSummaries.map { it.conditionId },
            emittedConditions.map { it.getValue("condition_id").jsonPrimitive.content },
        )
        snapshot.summary.conditionSummaries.zip(emittedConditions).forEach { (expected, emitted) ->
            assertTrue(emitted.getValue("rpi") === JsonNull)
            assertEquals(
                requireNotNull(expected.primaryNullReason),
                emitted.getValue("primary_null_reason").jsonPrimitive.content,
            )
            assertEquals(
                requireNotNull(expected.allNullReasons),
                emitted.getValue("all_null_reasons").jsonArray.map { it.jsonPrimitive.content },
            )
        }
        val baselineFailedRuns = emittedConditions[0].getValue("failed_runs")
        assertFalse(baselineFailedRuns === JsonNull)
        assertEquals(0, baselineFailedRuns.jsonPrimitive.int)
        val interruptedSuccessRate = emittedConditions[1].getValue("success_rate")
        assertFalse(interruptedSuccessRate === JsonNull)
        assertEquals(0.0, interruptedSuccessRate.jsonPrimitive.double, 0.0)
    }

    @Test
    fun `destination failure propagates without closing caller owned stream`() {
        val failure = IOException("sentinel destination failure")
        val destination = OneShotThrowingOutputStream(failure)
        val snapshot = completeSnapshot(
            campaignId = "campaign-write-failure",
            capabilityBytes = "{}".toByteArray(Charsets.UTF_8),
            eventRecords = emptyList(),
        )

        val thrown = runCatching {
            PrototypeDeviceFallbackBundleWriter.write(snapshot, destination)
        }.exceptionOrNull()

        assertSame(failure, thrown)
        assertEquals(0, destination.closeCalls)
    }

    private fun completeSnapshot(
        campaignId: String,
        capabilityBytes: ByteArray,
        eventRecords: List<ByteArray>,
    ) = PrototypeDeviceFallbackBundleWriter.Snapshot(
        summary = PrototypeQuickCampaignRunner.CampaignSummary(
            campaignId = campaignId,
            campaignMode = "quick",
            plannedRuns = 3,
            attemptedRuns = 3,
            successfulRuns = 3,
            failedRuns = 0,
            notStartedRuns = 0,
            successRate = 1.0,
            status = PrototypeQuickCampaignRunner.CampaignStatus.COMPLETE,
            conditionSummaries = listOf(
                conditionSummary("baseline_v0.1", rpi = 100, ttftMs = 120.0),
                conditionSummary("slow_v0.1", rpi = 48, ttftMs = 420.0),
                conditionSummary("unstable_v0.1", rpi = 62, ttftMs = 260.0),
            ),
        ),
        runs = listOf(
            completeRun(1, "run-complete-01", "baseline_v0.1", ttftMs = 120.0),
            completeRun(2, "run-complete-02", "slow_v0.1", ttftMs = 420.0),
            completeRun(3, "run-complete-03", "unstable_v0.1", ttftMs = 260.0),
        ),
        capabilityResponseUtf8 = capabilityBytes,
        eventJsonUtf8Records = eventRecords,
    )

    private fun acceptanceSnapshot(): PrototypeDeviceFallbackBundleWriter.Snapshot {
        val conditions = List(3) {
            listOf("baseline_v0.1", "slow_v0.1", "unstable_v0.1")
        }.flatten()
        val ttftByCondition = mapOf(
            "baseline_v0.1" to 120.0,
            "slow_v0.1" to 420.0,
            "unstable_v0.1" to 260.0,
        )
        val runs = conditions.mapIndexed { index, conditionId ->
            completeRun(
                runIndex = index + 1,
                runId = "run-acceptance-${(index + 1).toString().padStart(2, '0')}",
                conditionId = conditionId,
                ttftMs = ttftByCondition.getValue(conditionId),
            )
        }
        val summary = PrototypeQuickCampaignRunner.canonicalCampaignSummary(
            campaignId = "campaign-acceptance-fallback",
            mode = PrototypeQuickCampaignRunner.CampaignMode.ACCEPTANCE,
            results = runs.map { run ->
                PrototypeQuickCampaignRunner.SummaryRun(
                    conditionId = run.conditionId,
                    status = run.status,
                    taskSuccess = run.taskSuccess,
                    scoreEligible = run.scoreEligible,
                    metrics = run.metrics,
                )
            },
        )
        return PrototypeDeviceFallbackBundleWriter.Snapshot(
            summary = summary,
            runs = runs,
            capabilityResponseUtf8 = "{}".toByteArray(Charsets.UTF_8),
            eventJsonUtf8Records = emptyList(),
        )
    }

    @Test
    fun `invalid sequence snapshot preserves failed prefix and not started suffix`() {
        val snapshot = invalidSequenceSnapshot()
        val output = ByteArrayOutputStream()

        PrototypeDeviceFallbackBundleWriter.write(snapshot, output)

        val campaign = Json.parseToJsonElement(
            readEntries(output.toByteArray())
                .single { it.name == "campaign-snapshot.json" }
                .bytes
                .toString(Charsets.UTF_8),
        ).jsonObject
        val runs = campaign.getValue("runs").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("INVALID_SEQUENCE", "NOT_STARTED", "NOT_STARTED"),
            runs.map { it.getValue("status").jsonPrimitive.content },
        )
        assertEquals(1, runs.first().getValue("events_received").jsonPrimitive.int)
        assertEquals("invalid_sequence", runs.first().getValue("failure_reason").jsonPrimitive.content)
        assertTrue(runs.first().getValue("terminal_receipt_valid") === JsonNull)
        assertTrue(runs.first().getValue("metrics") === JsonNull)
        assertEquals("PARTIAL", campaign.getValue("campaign_status").jsonPrimitive.content)
    }

    private fun partialSnapshot(): PrototypeDeviceFallbackBundleWriter.Snapshot {
        val campaignId = "campaign-partial-01"
        val runs = listOf(
            completeRun(1, "run-partial-01", "baseline_v0.1", ttftMs = 120.0),
            PrototypeDeviceFallbackBundleWriter.Run(
                runIndex = 2,
                runId = "run-partial-02",
                conditionId = "slow_v0.1",
                status = PrototypeQuickCampaignRunner.RunStatus.INTERRUPTED,
                taskSuccess = false,
                scoreEligible = false,
                eventsExpected = 120,
                eventsReceived = 1,
                failureReason = "stream_interrupted",
                terminalReceiptValid = null,
                metrics = null,
            ),
            PrototypeDeviceFallbackBundleWriter.Run(
                runIndex = 3,
                runId = "run-partial-03",
                conditionId = "unstable_v0.1",
                status = PrototypeQuickCampaignRunner.RunStatus.NOT_STARTED,
                taskSuccess = false,
                scoreEligible = false,
                eventsExpected = 120,
                eventsReceived = 0,
                failureReason = "not_started",
                terminalReceiptValid = null,
                metrics = null,
            ),
        )
        val summary = PrototypeQuickCampaignRunner.canonicalCampaignSummary(
            campaignId = campaignId,
            results = runs.map { run ->
                PrototypeQuickCampaignRunner.SummaryRun(
                    conditionId = run.conditionId,
                    status = run.status,
                    taskSuccess = run.taskSuccess,
                    scoreEligible = run.scoreEligible,
                    metrics = run.metrics,
                )
            },
        )
        return PrototypeDeviceFallbackBundleWriter.Snapshot(
            summary = summary,
            runs = runs,
            capabilityResponseUtf8 = "{}".toByteArray(Charsets.UTF_8),
            eventJsonUtf8Records = listOf(
                "{\"event_type\":\"run_started\"}".toByteArray(Charsets.UTF_8),
            ),
        )
    }

    private fun invalidSequenceSnapshot(): PrototypeDeviceFallbackBundleWriter.Snapshot {
        val campaignId = "campaign-invalid-sequence-01"
        val runs = listOf(
            PrototypeDeviceFallbackBundleWriter.Run(
                runIndex = 1,
                runId = "run-invalid-sequence-01",
                conditionId = "baseline_v0.1",
                status = PrototypeQuickCampaignRunner.RunStatus.INVALID_SEQUENCE,
                taskSuccess = false,
                scoreEligible = false,
                eventsExpected = 120,
                eventsReceived = 1,
                failureReason = "invalid_sequence",
                terminalReceiptValid = null,
                metrics = null,
            ),
            PrototypeDeviceFallbackBundleWriter.Run(
                runIndex = 2,
                runId = "run-invalid-sequence-02",
                conditionId = "slow_v0.1",
                status = PrototypeQuickCampaignRunner.RunStatus.NOT_STARTED,
                taskSuccess = false,
                scoreEligible = false,
                eventsExpected = 120,
                eventsReceived = 0,
                failureReason = "not_started",
                terminalReceiptValid = null,
                metrics = null,
            ),
            PrototypeDeviceFallbackBundleWriter.Run(
                runIndex = 3,
                runId = "run-invalid-sequence-03",
                conditionId = "unstable_v0.1",
                status = PrototypeQuickCampaignRunner.RunStatus.NOT_STARTED,
                taskSuccess = false,
                scoreEligible = false,
                eventsExpected = 120,
                eventsReceived = 0,
                failureReason = "not_started",
                terminalReceiptValid = null,
                metrics = null,
            ),
        )
        return PrototypeDeviceFallbackBundleWriter.Snapshot(
            summary = PrototypeQuickCampaignRunner.canonicalCampaignSummary(
                campaignId = campaignId,
                results = runs.map { run ->
                    PrototypeQuickCampaignRunner.SummaryRun(
                        conditionId = run.conditionId,
                        status = run.status,
                        taskSuccess = run.taskSuccess,
                        scoreEligible = run.scoreEligible,
                        metrics = run.metrics,
                    )
                },
            ),
            runs = runs,
            capabilityResponseUtf8 = "{}".toByteArray(Charsets.UTF_8),
            eventJsonUtf8Records = listOf(
                "{\"event_type\":\"run_started\"}".toByteArray(Charsets.UTF_8),
                "{\"event_type\":\"content_event\"}".toByteArray(Charsets.UTF_8),
                "{\"event_type\":\"run_failed\"}".toByteArray(Charsets.UTF_8),
            ),
        )
    }

    private fun completeRun(
        runIndex: Int,
        runId: String,
        conditionId: String,
        ttftMs: Double,
    ) = PrototypeDeviceFallbackBundleWriter.Run(
        runIndex = runIndex,
        runId = runId,
        conditionId = conditionId,
        status = PrototypeQuickCampaignRunner.RunStatus.COMPLETE,
        taskSuccess = true,
        scoreEligible = true,
        eventsExpected = 120,
        eventsReceived = 120,
        failureReason = null,
        terminalReceiptValid = true,
        metrics = PrototypeQuickCampaignRunner.RunMetrics(
            ttftMs = ttftMs,
            completionMs = 6_100.0,
            streamSpanMs = 5_950.0,
            streamEventRateEps = 20.0,
            stallThresholdMs = 100.0,
            stallCount = 0,
            stallDurationMs = 0.0,
            stallFraction = 0.0,
        ),
    )

    private fun conditionSummary(
        conditionId: String,
        rpi: Int,
        ttftMs: Double,
    ) = PrototypeQuickCampaignRunner.ConditionSummary(
        conditionId = conditionId,
        plannedRuns = 1,
        attemptedRuns = 1,
        successfulRuns = 1,
        failedRuns = 0,
        notStartedRuns = 0,
        successRate = 1.0,
        confidence = PrototypeQuickCampaignRunner.Confidence.LOW,
        medianTtftMs = ttftMs,
        minTtftMs = ttftMs,
        maxTtftMs = ttftMs,
        medianCompletionMs = 6_100.0,
        minCompletionMs = 6_100.0,
        maxCompletionMs = 6_100.0,
        medianStreamEventRateEps = 20.0,
        medianStallCount = 0.0,
        medianStallDurationMs = 0.0,
        medianStallFraction = 0.0,
        rpi = rpi,
        rpiPolicyId = "rpi-0.1",
        primaryNullReason = null,
        allNullReasons = null,
    )

    private fun readEntries(zipBytes: ByteArray): List<ZipContents> = buildList {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val bytes = zip.readBytes()
                add(
                    ZipContents(
                        name = entry.name,
                        bytes = bytes,
                        timestamp = entry.time,
                        directory = entry.isDirectory,
                        method = entry.method,
                        size = entry.size,
                        compressedSize = entry.compressedSize,
                        crc = entry.crc,
                    ),
                )
                zip.closeEntry()
            }
        }
    }

    private fun JsonObject.recursiveKeys(): Set<String> = buildSet {
        fun visit(value: kotlinx.serialization.json.JsonElement) {
            when (value) {
                is JsonObject -> value.forEach { (key, child) ->
                    add(key)
                    visit(child)
                }
                is kotlinx.serialization.json.JsonArray -> value.forEach(::visit)
                else -> Unit
            }
        }
        visit(this@recursiveKeys)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte ->
            String.format(Locale.ROOT, "%02x", byte.toInt() and 0xff)
        }

    private fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    private fun ByteArray.startsWithUtf8Bom(): Boolean =
        size >= 3 && this[0] == 0xef.toByte() && this[1] == 0xbb.toByte() && this[2] == 0xbf.toByte()

    private data class ZipContents(
        val name: String,
        val bytes: ByteArray,
        val timestamp: Long,
        val directory: Boolean,
        val method: Int,
        val size: Long,
        val compressedSize: Long,
        val crc: Long,
    )

    private class CloseTrackingOutputStream : ByteArrayOutputStream() {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class OneShotThrowingOutputStream(
        private val failure: IOException,
    ) : OutputStream() {
        var closeCalls = 0
            private set

        private var hasThrown = false

        override fun write(value: Int) {
            throwOnce()
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            throwOnce()
        }

        override fun close() {
            closeCalls += 1
        }

        private fun throwOnce() {
            if (!hasThrown) {
                hasThrown = true
                throw failure
            }
        }
    }

    private companion object {
        val EXPECTED_ENTRY_NAMES = listOf(
            "DEVICE_FALLBACK_UNVERIFIED.txt",
            "capability-response.json",
            "campaign-snapshot.json",
            "events.jsonl",
            "SHA256SUMS.txt",
        )
        const val RPI_LABEL = "Relative Prototype Index (same-campaign synthetic comparison)"
        const val RPI_DISCLOSURE =
            "This score compares deterministic application-layer conditions against this campaign's Baseline. " +
                "It is not a formal ANEB industry score and does not represent a third-party AI application's " +
                "network requirement."
        const val FROZEN_CLAIM =
            "ANEB Prototype 0.1 measures Android-client-observed timing against a local ANEB probe under " +
                "deterministic synthetic application-layer schedules. It does not emulate or measure packet " +
                "loss, RAN/core/operator quality, public-Internet quality, a real application, or model inference."
    }
}
