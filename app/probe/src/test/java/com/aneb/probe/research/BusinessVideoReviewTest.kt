package com.aneb.probe.research

import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BusinessVideoReviewTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun stoppedCollectorKeepsItsSeparateReviewedCopyAcrossReopenWithoutRewritingRaw() {
        val originals = ResearchRecordStore(temporary.newFolder())
        val raw = rawBytes()
        val source = originals.save(raw)
        val analysisDirectory = temporary.newFolder()
        val review = reviewBytes(source)

        val saved = ResearchAnalysisStore(analysisDirectory).save(source, review)
        val reopened = ResearchAnalysisStore(analysisDirectory).open(originals.open(source.id), saved.id)

        assertEquals(saved.id, reopened.id)
        assertEquals(source.id, reopened.sourceId)
        assertEquals("NOT_QUALIFIED", reopened.records.getValue("TEST-STOPPED").text("qualification"))
        assertArrayEquals(review, ByteArrayOutputStream().also {
            ResearchAnalysisStore(analysisDirectory).export(source, saved.id, it)
        }.toByteArray())
        assertArrayEquals(raw, ByteArrayOutputStream().also { originals.export(source.id, it) }.toByteArray())
        assertEquals(JsonPrimitive(false), originals.open(source.id).root["ownedCleanup"])
        assertEquals(JsonNull, originals.open(source.id).root["firstObservedPlaybackWaitMs"])
    }

    @Test fun importedQualificationCannotTurnAStoppedUncleanSourceIntoAPassedRun() {
        val source = ResearchRecordStore.decode(rawBytes())
        val forged = changeRow(reviewBytes(source)) { row ->
            JsonObject(row + ("qualification" to JsonPrimitive("QUALIFIED")))
        }
        val analyses = ResearchAnalysisStore(temporary.newFolder())

        assertThrows(ResearchImportException::class.java) { analyses.save(source, forged) }

        assertTrue(analyses.list(source).isEmpty())
    }

    @Test fun aMissingEndpointCannotCarryAZeroIntervalAndImportedIntervalsMustBeOrderedNumbers() {
        val source = ResearchRecordStore.decode(rawBytes())
        val review = reviewBytes(source)
        fun withMetric(metric: JsonObject): ByteArray = changeRow(review) { row ->
            val metrics = row.getValue("metrics").jsonObject
            JsonObject(row + ("metrics" to JsonObject(metrics + ("visible_switch_wait" to metric))))
        }
        val na = Json.parseToJsonElement(review.toString(Charsets.UTF_8)).jsonObject
            .getValue("records").jsonArray.single().jsonObject.getValue("metrics").jsonObject
            .getValue("visible_switch_wait").jsonObject
        val numeric = JsonObject(na + mapOf("status" to JsonPrimitive("interval"),
            "interval_s" to JsonArray(listOf(JsonPrimitive(0.1), JsonPrimitive(0.2)))))
        assertEquals(JsonArray(listOf(JsonPrimitive(0.1), JsonPrimitive(0.2))),
            ResearchAnalysisStore.decode(source, withMetric(numeric)).records.getValue("TEST-STOPPED")
                .getValue("metrics").jsonObject.getValue("visible_switch_wait").jsonObject["interval_s"])
        val invalid = listOf(
            JsonObject(na + ("interval_s" to JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(0))))),
            JsonObject(numeric + ("interval_s" to JsonArray(listOf(JsonPrimitive(0.2), JsonPrimitive(0.1))))),
            JsonObject(numeric + ("interval_s" to JsonArray(listOf(JsonPrimitive("0.1"), JsonPrimitive("0.2"))))),
        )
        invalid.forEach { metric ->
            assertThrows(ResearchImportException::class.java) { ResearchAnalysisStore.decode(source, withMetric(metric)) }
        }
    }

    @Test fun businessReviewReadoutRetainsRawFailureAndUsesItsOwnMetricNamesAndLimitations() {
        val source = ResearchRecordStore.decode(rawBytes())
        val review = ResearchAnalysisStore.decode(source, reviewBytes(source))

        val readout = (review.summaryLines + review.attemptLines("TEST-STOPPED") + review.groupLines).joinToString("\n")

        listOf("业务视频复核", "STOPPED", "PAGE_MISMATCH", "false", "NOT_QUALIFIED",
            "visible_pointer_to_target_wait", "visible_switch_wait", "NA", "not_computed",
            "TEST-target-intrinsic-endpoint-unavailable", "TEST-only account-related modal observed",
            "TEST-only source stopped; no network inference", "本机未读取或重验媒体").forEach { expected ->
            assertTrue("missing readout: $expected", readout.contains(expected))
        }
        assertFalse(readout.contains("完成 Completion"))
        assertFalse(readout.contains("首个反馈 TTFR"))
        assertFalse(review.isVideo)
    }

    @Test fun reviewedBusinessReportReusesTheExistingZipWithOriginalBytesAndTextOnlyEvidence() {
        val originals = ResearchRecordStore(temporary.newFolder())
        val raw = rawBytes()
        val source = originals.save(raw)
        val reviews = ResearchAnalysisStore(temporary.newFolder())
        val bytes = changeRow(reviewBytes(source)) { row -> JsonObject(row + mapOf(
            "observations" to JsonArray(listOf(JsonPrimitive("TEST-<script>alert(1)</script>"))),
            "evidence_references" to buildJsonArray { addJsonObject {
                put("reference", "https://example.invalid/TEST-outside.mp4")
                put("sha256", "b".repeat(64))
            } },
        )) }
        val review = reviews.save(source, bytes)
        val output = ByteArrayOutputStream()

        ResearchVideoOfflineReportExporter(originals, reviews).writeTo(source.id, review.id, output)

        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray()), Charsets.UTF_8).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                assertFalse(files.containsKey(entry.name))
                files[entry.name] = zip.readBytes()
            }
        }
        assertEquals(setOf("source.json", "analysis.json", "index.html", "SHA256SUMS.txt"), files.keys)
        assertArrayEquals(raw, files.getValue("source.json"))
        assertArrayEquals(bytes, files.getValue("analysis.json"))
        val page = files.getValue("index.html").toString(Charsets.UTF_8)
        listOf("业务视频复核", "STOPPED", "false", "NOT_QUALIFIED", "NA", "not_computed",
            "TEST-target-intrinsic-endpoint-unavailable", "TEST-only source stopped; no network inference",
            "本机未读取或重验媒体", "&lt;script&gt;", "https://example.invalid/TEST-outside.mp4").forEach {
            assertTrue("missing report boundary: $it", page.contains(it))
        }
        assertFalse(page.contains("<script>"))
        assertFalse(page.contains("href=\"https://"))
        val sums = files.getValue("SHA256SUMS.txt").toString(Charsets.UTF_8).lines()
        files.filterKeys { it != "SHA256SUMS.txt" }.forEach { (name, content) ->
            val sha = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertTrue("missing checksum: $name", "$sha  $name" in sums)
        }
    }

    @Test fun alteredFullInputOrRunIdentityCannotBeSavedAndMismatchedExportsWriteNothing() {
        val originals = ResearchRecordStore(temporary.newFolder())
        val source = originals.save(rawBytes())
        val reviews = ResearchAnalysisStore(temporary.newFolder())
        val review = reviewBytes(source)
        val root = Json.parseToJsonElement(review.toString(Charsets.UTF_8)).jsonObject
        val invalid = listOf(
            JsonObject(root + ("source_sha256" to JsonPrimitive("0".repeat(64)))).toString().toByteArray(),
            JsonObject(root + ("input_record" to JsonObject(source.root + ("state" to JsonPrimitive("CAPTURED_AWAITING_REVIEW"))))).toString().toByteArray(),
            JsonObject(root + ("input_record" to JsonObject(source.root + ("ownedCleanup" to JsonPrimitive(true))))).toString().toByteArray(),
            changeRow(review) { JsonObject(it + ("attempt_id" to JsonPrimitive("TEST-ANOTHER-RUN"))) },
        )
        invalid.forEach { bytes -> assertThrows(ResearchImportException::class.java) { reviews.save(source, bytes) } }
        assertTrue(reviews.list(source).isEmpty())

        val saved = reviews.save(source, review)
        val another = originals.save(byteArrayOf(10) + rawBytes())
        val output = ByteArrayOutputStream()
        assertThrows(Exception::class.java) {
            ResearchVideoOfflineReportExporter(originals, reviews).writeTo(another.id, saved.id, output)
        }
        assertEquals(0, output.size())
    }

    @Test fun theExistingBatchImporterPairsTheBusinessReviewBeforeItsOriginalWithoutAnotherImportSystem() {
        val raw = rawBytes()
        val source = ResearchRecordStore.decode(raw)
        val review = reviewBytes(source)
        val originals = ResearchRecordStore(temporary.newFolder())
        val reviews = ResearchAnalysisStore(temporary.newFolder())
        val preview = ResearchBatchImport.preview(listOf(
            ResearchBatchImportFile("TEST-review.json", review), ResearchBatchImportFile("TEST-source.json", raw),
        ), originals, reviews)
        assertTrue(preview.items.all { it.status == ResearchBatchImportItemStatus.READY })
        assertTrue(originals.list().isEmpty())
        assertTrue(reviews.list(source).isEmpty())

        val receipt = preview.confirmSave()

        assertEquals(2, receipt.savedCount)
        val saved = reviews.list(originals.open(source.id)).single().analysis!!
        assertArrayEquals(review, ByteArrayOutputStream().also { reviews.export(source, saved.id, it) }.toByteArray())
        assertArrayEquals(raw, ByteArrayOutputStream().also { originals.export(source.id, it) }.toByteArray())
    }

    @Test fun qualifiedCapturedObservationSupportsPostSettleUpperBoundTargetEvaluationAndHostNetworkProvenance() {
        val raw = """{
          "format":"business-video-raw-1","runId":"TEST-CAPTURED-B-01",
          "profileSha256":"${"a".repeat(64)}","conditionId":"B","clockId":"TEST-elapsed",
          "state":"CAPTURED_AWAITING_REVIEW","category":"OBSERVATION_CAPTURED","reason":null,
          "swipeCalls":"1","ownedCleanup":true,
          "events":[{"name":"swipe_returned","atNs":1000000000},{"name":"observation_window_ended","atNs":31000000000}],
          "frames":[{"sequence":0,"requestedNs":1500000000,"returnedNs":1510000000,"writtenNs":1520000000,"relativePath":"frames/frame-0000.png","sha256":"${"c".repeat(64)}","bytes":1024}],
          "firstObservedPlaybackWaitMs":null,"playerFirstFrameMs":null,"stallStatus":"not_computed",
          "hostNetworkCondition":{
            "topologyId":"ethernet-winrt-hotspot-20261119",
            "conditionMode":"limit",
            "requestSha256":"${"d".repeat(64)}",
            "uplinkInterfaceIndex":12,
            "apInterfaceIndex":14,
            "downlinkIpBytesPerSecond":125000
          }
        }""".toByteArray(Charsets.UTF_8)
        val originals = ResearchRecordStore(temporary.newFolder())
        val source = originals.save(raw)
        val review = buildJsonObject {
            put("format", "business-video-review-1")
            put("record_kind", source.recordKind)
            put("source_sha256", source.id)
            put("method_id", "TEST-visible-dynamics-v3-upper-bound")
            put("input_record", source.root)
            putJsonArray("records") {
                addJsonObject {
                    put("attempt_id", "TEST-CAPTURED-B-01")
                    put("qualification", "QUALIFIED")
                    putJsonObject("metrics") {
                        putJsonObject("visible_pointer_to_target_wait") {
                            put("status", "interval")
                            putJsonArray("interval_s") { add(0.45); add(0.62) }
                            put("endpoint_b_kind", "CONFIRMED_POST_SETTLE_UPPER_BOUND")
                            put("target_s", 3)
                            put("target_evaluation", "MET")
                            put("reason", "TEST-panel-settled-before-confirmed-hand-motion")
                        }
                        putJsonObject("visible_switch_wait") {
                            put("status", "interval")
                            putJsonArray("interval_s") { add(2.85); add(3.20) }
                            put("endpoint_b_kind", "CONFIRMED_POST_SETTLE_UPPER_BOUND")
                            put("target_s", 3)
                            put("target_evaluation", "UNCERTAIN")
                            put("reason", "TEST-conservative-interval-straddles-target")
                        }
                    }
                    putJsonObject("network_condition_review") {
                        put("topology_id", "ethernet-winrt-hotspot-20261119")
                        put("mode", "limit")
                        put("host_request_sha256", "d".repeat(64))
                        put("summary", "TEST-only 125000 IP B/s WinDivert forward pacing over Ethernet->WinRT AP")
                    }
                    put("stall_status", "not_computed")
                    putJsonArray("observations") { add("TEST-only 30s observation window captured") }
                    putJsonArray("limitations") { add("TEST-only post-settle upper bound, not exact first frame") }
                    putJsonArray("evidence_references") {
                        addJsonObject { put("reference", "supervisor.json"); put("sha256", "e".repeat(64)) }
                    }
                }
            }
        }.toString().toByteArray(Charsets.UTF_8)
        val reviews = ResearchAnalysisStore(temporary.newFolder())
        val saved = reviews.save(source, review)
        val output = ByteArrayOutputStream()
        ResearchVideoOfflineReportExporter(originals, reviews).writeTo(source.id, saved.id, output)
        val zipEntries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray()), Charsets.UTF_8).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                zipEntries[entry.name] = zip.readBytes()
            }
        }
        val html = zipEntries.getValue("index.html").toString(Charsets.UTF_8)
        listOf(
            "QUALIFIED",
            "CONFIRMED_POST_SETTLE_UPPER_BOUND",
            "MET",
            "UNCERTAIN",
            "ethernet-winrt-hotspot-20261119",
            "d".repeat(64),
            "word-break:break-word",
            "overflow-wrap:anywhere",
            "本机未读取或重验媒体",
        ).forEach { expected ->
            assertTrue("missing report token: $expected", html.contains(expected))
        }
        assertEquals(JsonNull, originals.open(source.id).root["firstObservedPlaybackWaitMs"])
    }

    @Test fun dishonestTargetEvaluationOrObservedEndpointBOnNaIsRejected() {
        val source = ResearchRecordStore.decode(rawBytes())
        val base = reviewBytes(source)
        fun mutateSwitchMetric(extra: Map<String, JsonElement>): ByteArray = changeRow(base) { row ->
            val metrics = row.getValue("metrics").jsonObject
            val sw = metrics.getValue("visible_switch_wait").jsonObject
            JsonObject(row + ("metrics" to JsonObject(metrics + ("visible_switch_wait" to JsonObject(sw + extra)))))
        }
        val rejected = listOf(
            // NA claiming MET
            mutateSwitchMetric(mapOf("target_s" to JsonPrimitive(3), "target_evaluation" to JsonPrimitive("MET"))),
            // NA claiming observed post-settle upper bound
            mutateSwitchMetric(mapOf("endpoint_b_kind" to JsonPrimitive("CONFIRMED_POST_SETTLE_UPPER_BOUND"))),
            // Straddling [2.8, 3.2] vs 3s claiming MET
            mutateSwitchMetric(
                mapOf(
                    "status" to JsonPrimitive("interval"),
                    "interval_s" to JsonArray(listOf(JsonPrimitive(2.8), JsonPrimitive(3.2))),
                    "target_s" to JsonPrimitive(3),
                    "target_evaluation" to JsonPrimitive("MET"),
                ),
            ),
            // Exceeding [3.1, 3.5] vs 3s claiming MET instead of NOT_MET
            mutateSwitchMetric(
                mapOf(
                    "status" to JsonPrimitive("interval"),
                    "interval_s" to JsonArray(listOf(JsonPrimitive(3.1), JsonPrimitive(3.5))),
                    "target_s" to JsonPrimitive(3),
                    "target_evaluation" to JsonPrimitive("MET"),
                ),
            ),
        )
        rejected.forEach { bad ->
            assertThrows(ResearchImportException::class.java) { ResearchAnalysisStore.decode(source, bad) }
        }
        val honestNotMet = mutateSwitchMetric(
            mapOf(
                "status" to JsonPrimitive("interval"),
                "interval_s" to JsonArray(listOf(JsonPrimitive(3.1), JsonPrimitive(3.5))),
                "target_s" to JsonPrimitive(3),
                "target_evaluation" to JsonPrimitive("NOT_MET"),
            ),
        )
        assertNotNull(ResearchAnalysisStore.decode(source, honestNotMet))
    }

    private fun changeRow(bytes: ByteArray, update: (JsonObject) -> JsonObject): ByteArray {
        val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val row = root.getValue("records").jsonArray.single().jsonObject
        return JsonObject(root + ("records" to JsonArray(listOf(update(row))))).toString().toByteArray()
    }

    // Fabricated input-shaped fixtures only; no real App/media/measurement or reviewer claim.
    private fun rawBytes(): ByteArray = """{
      "format":"business-video-raw-1","runId":"TEST-STOPPED",
      "profileSha256":"${"a".repeat(64)}","conditionId":"TEST-REFERENCE","clockId":"TEST-elapsed",
      "state":"STOPPED","category":"PAGE_MISMATCH","reason":"UNKNOWN",
      "swipeCalls":"1","ownedCleanup":false,
      "events":[{"name":"swipe_returned","atNs":400},{"name":"stopped","atNs":900}],
      "frames":[],"firstObservedPlaybackWaitMs":null,"playerFirstFrameMs":null,"stallStatus":"not_computed"
    }""".toByteArray()

    private fun reviewBytes(source: ResearchDocument): ByteArray = buildJsonObject {
        put("format", "business-video-review-1")
        put("record_kind", source.recordKind)
        put("source_sha256", source.id)
        put("method_id", "TEST-visible-dynamics-v2")
        put("input_record", source.root)
        putJsonArray("records") {
            addJsonObject {
                put("attempt_id", "TEST-STOPPED")
                put("qualification", "NOT_QUALIFIED")
                putJsonObject("metrics") {
                    listOf("visible_pointer_to_target_wait", "visible_switch_wait").forEach { key ->
                        putJsonObject(key) {
                            put("status", "NA")
                            put("interval_s", JsonNull)
                            put("reason", "TEST-target-intrinsic-endpoint-unavailable")
                        }
                    }
                }
                put("stall_status", "not_computed")
                putJsonArray("observations") { add("TEST-only account-related modal observed") }
                putJsonArray("limitations") { add("TEST-only source stopped; no network inference") }
                putJsonArray("evidence_references") {
                    addJsonObject { put("reference", "TEST-review.md"); put("sha256", "b".repeat(64)) }
                }
            }
        }
    }.toString().toByteArray()
}
