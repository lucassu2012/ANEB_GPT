package com.aneb.probe.research

import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResearchVideoImportTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun sampleVideoCanPreviewSaveReopenPairAnalysisAndExportSeparateOriginalBytes() {
        // SAMPLE ONLY: declared times and imported result are fabricated, never media measurements.
        val bytes = sampleSource().toByteArray()
        val preview = ResearchRecordStore.decode(bytes)
        assertEquals("SAMPLE", preview.recordKind)
        assertEquals("SAMPLE-R1", preview.records.single().attemptId)
        val originals = ResearchRecordStore(temporary.newFolder("source"))
        val saved = originals.save(bytes)
        val reopened = originals.open(saved.id)
        val derivedBytes = sampleAnalysis(reopened).toByteArray()
        val analyses = ResearchAnalysisStore(temporary.newFolder("analysis"))
        val derived = analyses.save(reopened, derivedBytes)
        val result = analyses.open(originals.open(saved.id), derived.id)
        assertEquals(setOf("SAMPLE-R1"), result.records.keys)
        assertTrue(result.summaryLines.joinToString().contains("计划：1；已执行：1；未执行：0"))
        val lines = result.attemptLines("SAMPLE-R1").joinToString("\n")
        assertTrue(lines, lines.contains("[2.2, 2.5] 秒"))
        assertTrue(lines, lines.contains("30秒观察窗"))
        assertFalse(lines, lines.contains("TTFC"))
        assertFalse(lines, lines.contains("Completion"))
        assertArrayEquals(bytes, ByteArrayOutputStream().also { originals.export(saved.id, it) }.toByteArray())
        assertArrayEquals(derivedBytes, ByteArrayOutputStream().also { analyses.export(reopened, derived.id, it) }.toByteArray())
        assertEquals(saved.id, originals.list().single().document!!.id)
    }

    private fun sampleSource() = """{
      "format":"research-video-1","record_kind":"SAMPLE","app":"SAMPLE synthetic video",
      "attempts":[{"slot":"SAMPLE-R1","status":"EXECUTED",
        "V0":{"clock_id":"SAMPLE-PTS","pts_s":[10,10.1]},
        "VF":{"clock_id":"SAMPLE-PTS","pts_s":[12.3,12.5]},
        "window_end":{"clock_id":"SAMPLE-PTS","pts_s":[40.1,40.2]},
        "visible_target_playback":"yes","window_complete":"yes","reason":"SAMPLE only"}]
    }"""

    private fun sampleAnalysis(source: ResearchDocument): String = buildJsonObject {
        put("record_kind", source.recordKind); put("source_sha256", source.id)
        put("input_record", source.root)
        put("counts", buildJsonObject { put("planned", 1); put("executed", 1); put("not_run", 0) })
        put("attempts", Json.parseToJsonElement("""[{"slot":"SAMPLE-R1","status":"EXECUTED","reason":"SAMPLE only",
          "first_frame_wait":{"status":"valid","interval_s":[2.2,2.5],"reason":null},
          "window":{"planned_end_pts_s":[40.0,40.1],"status":"observed_complete","reason":null},
          "buffering_pause":{"status":"not_computed","count":null,"duration_s":null}}]"""))
    }.toString()

    @Test fun declaredBlockedThreeSlotsRemainUnexecutedAfterSaveAndAnalysisReopen() {
        // Fabricated compatibility shape only, not an actual observation or private input.
        val root = buildJsonObject {
            put("record_kind", "video_observation_preflight_blocked"); put("app", "SAMPLE blocked carrier")
            put("test_fixture", "SAMPLE ONLY fabricated NOT_RUN")
            put("actual_measured_opens", 0); put("planned_slots", 3); put("not_run", 3)
            put("attempts", buildJsonArray {
                repeat(3) { i -> add(buildJsonObject {
                    put("slot", "SAMPLE-NOT-RUN-${i + 1}"); put("status", "NOT_RUN")
                    put("V0", JsonNull); put("VF", JsonNull); put("window_end", JsonNull)
                    put("visible_target_playback", "not_observed"); put("window_complete", "not_started")
                    put("reason", "SAMPLE ONLY not executed")
                }) }
            })
        }
        val bytes = root.toString().toByteArray()
        val originals = ResearchRecordStore(temporary.newFolder())
        val source = originals.save(bytes)
        assertEquals("OBSERVED", source.recordKind)
        assertTrue(source.sourceLabel.contains("未核验"))
        assertEquals("video_observation_preflight_blocked", source.root.text("record_kind"))
        assertTrue(originals.open(source.id).records.all { it.statusLabel.contains("未执行") })
        val derivedBytes = buildJsonObject {
            put("source_sha256", source.id); put("record_kind", "OBSERVED"); put("input_record", root)
            put("counts", buildJsonObject { put("planned", 3); put("executed", 0); put("not_run", 3) })
            put("attempts", buildJsonArray {
                source.records.forEach { add(buildJsonObject {
                    put("slot", it.attemptId); put("status", "NOT_RUN")
                    put("first_frame_wait", buildJsonObject { put("status", "NA"); put("interval_s", JsonNull); put("reason", "NOT_RUN") })
                    put("window", buildJsonObject { put("status", "not_started"); put("planned_end_pts_s", JsonNull) })
                }) }
            })
        }.toString().toByteArray()
        val analyses = ResearchAnalysisStore(temporary.newFolder())
        val analysis = analyses.open(source, analyses.save(source, derivedBytes).id)
        assertTrue(analysis.summaryLines.joinToString().contains("计划：3；已执行：0；未执行：3"))
        source.records.forEach {
            val lines = analysis.attemptLines(it.attemptId).joinToString()
            assertTrue(lines, lines.contains("NA") && lines.contains("窗口未开始") && lines.contains("不计播放失败"))
        }
        assertArrayEquals(bytes, ByteArrayOutputStream().also { originals.export(source.id, it) }.toByteArray())
        assertArrayEquals(derivedBytes, ByteArrayOutputStream().also { analyses.export(source, analysis.id, it) }.toByteArray())
    }

    @Test fun videoAnalysisRejectsWrongSourceOrSlotCoverageWithoutReplacingSavedBytes() {
        val source = ResearchRecordStore.decode(sampleSource().toByteArray())
        val bytes = sampleAnalysis(source).toByteArray()
        val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val row = root["attempts"]!!.jsonArray.single().jsonObject
        val invalids = listOf(
            JsonObject(root.toMutableMap().apply { put("source_sha256", JsonPrimitive("0".repeat(64))) }),
            JsonObject(root.toMutableMap().apply { put("record_kind", JsonPrimitive("OBSERVED")) }),
            JsonObject(root.toMutableMap().apply { put("attempts", JsonArray(emptyList())) }),
            JsonObject(root.toMutableMap().apply { put("attempts", JsonArray(listOf(row, row))) }),
            JsonObject(root.toMutableMap().apply { put("attempts", JsonArray(listOf(JsonObject(row.toMutableMap().apply { put("slot", JsonPrimitive("other")) })))) }),
        )
        val store = ResearchAnalysisStore(temporary.newFolder())
        val saved = store.save(source, bytes)
        invalids.forEach { bad -> assertThrows(ResearchImportException::class.java) { store.save(source, bad.toString().toByteArray()) } }
        val differentlyEncodedSource = ResearchRecordStore.decode(("\n" + sampleSource()).toByteArray())
        assertThrows(ResearchImportException::class.java) { store.save(differentlyEncodedSource, bytes) }
        assertEquals(listOf(saved.id), store.list(source).map { it.id })
        assertArrayEquals(bytes, ByteArrayOutputStream().also { store.export(source, saved.id, it) }.toByteArray())
    }

    @Test fun importedUncertaintyAndFutureReasonArePreservedWithoutInventingValues() {
        val source = ResearchRecordStore.decode(sampleSource().toByteArray())
        val template = Json.parseToJsonElement(sampleAnalysis(source)).jsonObject
        listOf("NA", "uncertain", "future-status").forEach { status ->
            val row = template["attempts"]!!.jsonArray.single().jsonObject
            val changed = JsonObject(row.toMutableMap().apply {
                put("first_frame_wait", buildJsonObject {
                    put("status", status); put("interval_s", JsonNull); put("reason", "SAMPLE_future_reason_<not-html>")
                })
            })
            val bytes = JsonObject(template.toMutableMap().apply { put("attempts", JsonArray(listOf(changed))) }).toString().toByteArray()
            val store = ResearchAnalysisStore(temporary.newFolder())
            val analysis = store.open(source, store.save(source, bytes).id)
            val lines = analysis.attemptLines("SAMPLE-R1").joinToString()
            assertTrue(lines, lines.contains("SAMPLE_future_reason_<not-html>"))
            assertTrue(lines, lines.contains(if (status == "uncertain") "不确定" else if (status == "NA") "NA" else "未知分析状态"))
            assertFalse(lines, lines.contains("[2.2, 2.5]"))
            assertArrayEquals(bytes, ByteArrayOutputStream().also { store.export(source, analysis.id, it) }.toByteArray())
        }
    }

    @Test fun cutoffReasonsAreChineseAndKeepImportedNullIntervals() {
        val source = ResearchRecordStore.decode(sampleSource().toByteArray())
        val template = Json.parseToJsonElement(sampleAnalysis(source)).jsonObject
        val cases = listOf(
            Triple("VF_AFTER_PLANNED_WINDOW", "NA", "首画面晚于计划30秒截止"),
            Triple("VF_AFTER_OBSERVATION_END", "NA", "首画面晚于实际观察截止"),
            Triple("VF_PLANNED_BOUNDARY_UNCERTAIN", "uncertain", "首画面与计划截止边界重叠"),
            Triple("VF_OBSERVATION_BOUNDARY_UNCERTAIN", "uncertain", "首画面与实际截止边界重叠"),
        )
        cases.forEach { (reason, status, label) ->
            val row = template["attempts"]!!.jsonArray.single().jsonObject
            val changed = JsonObject(row.toMutableMap().apply {
                put("first_frame_wait", buildJsonObject { put("status", status); put("interval_s", JsonNull); put("reason", reason) })
            })
            val bytes = JsonObject(template.toMutableMap().apply { put("attempts", JsonArray(listOf(changed))) }).toString().toByteArray()
            val analysis = ResearchAnalysisStore.decode(source, bytes)
            val lines = analysis.attemptLines("SAMPLE-R1").joinToString()
            assertTrue("Missing Chinese label for $reason", lines.contains(label))
            assertTrue(lines.contains(reason))
            assertFalse(lines.contains("[2.2, 2.5]"))
            assertEquals(template["counts"], analysis.root["counts"])
            assertEquals(row["window"], analysis.records.getValue("SAMPLE-R1")["window"])
        }
    }

    @Test fun originalTextDocumentOwnsAnalysisFormatDespiteUnrelatedVideoShapedMetadata() {
        // Extra metadata must not switch an existing alignment-1 document to the video display path.
        fun fixture(name: String) = checkNotNull(javaClass.getResourceAsStream("/research/$name")).use {
            it.readBytes().toString(Charsets.UTF_8).replace("\r\n", "\n")
        }
        val source = ResearchRecordStore.decode(fixture("r1-sample.json").toByteArray())
        val textRoot = Json.parseToJsonElement(fixture("r1-analysis-sample.json")).jsonObject
        val bytes = JsonObject(textRoot.toMutableMap().apply {
            put("input_record", Json.parseToJsonElement(sampleSource()))
        }).toString().toByteArray()
        val analysis = ResearchAnalysisStore.decode(source, bytes)
        assertFalse("Text original must keep text analysis presentation", analysis.isVideo)
        assertEquals(source.records.map { it.attemptId }.toSet(), analysis.records.keys)
        assertTrue(analysis.attemptLines(source.records.first().attemptId).any { it.contains("TTFC") })
        listOf("NOT_RUN", "EXECUTED").forEach { videoStatus ->
            val textAttempt = ResearchAttempt(buildJsonObject { put("outcome", buildJsonObject { put("status", videoStatus) }) })
            assertEquals("未知：$videoStatus", textAttempt.statusLabel)
        }
    }
}
