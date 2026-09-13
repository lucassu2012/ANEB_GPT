package com.aneb.probe.research

import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResearchAppRecordFilterTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun allThenDeclaredAppThenAllRestoresOriginalOrderAndBytes() {
        val bytes = source(listOf("Kimi", "豆包", "Kimi"))
        val store = ResearchRecordStore(temporary.newFolder())
        val saved = store.save(bytes)
        val document = store.open(saved.id)
        assertEquals(document.records, document.recordsForApp(null))
        val kimi = document.appFilters.single { it.appName == "Kimi" }
        assertEquals(listOf("SAMPLE-1", "SAMPLE-3"), document.recordsForApp(kimi).map { it.attemptId })
        assertEquals(listOf("SAMPLE-1", "SAMPLE-2", "SAMPLE-3"), document.recordsForApp(null).map { it.attemptId })
        assertEquals(saved.id, document.id)
        assertSame(document.records[2], document.recordsForApp(kimi)[1])
        val analysisBytes = buildJsonObject {
            put("source_sha256", document.id); put("record_kind", "SAMPLE"); put("input_revision", "alignment-1")
            put("groups", buildJsonArray {})
            put("counts", buildJsonObject { put("planned", 3); put("attempted", 3); put("not_run", 0) })
            put("records", buildJsonArray { document.records.forEach { row -> add(buildJsonObject {
                put("attempt_id", row.attemptId); put("input_record", row.raw)
            }) } })
        }.toString().toByteArray()
        val analyses = ResearchAnalysisStore(temporary.newFolder())
        val analysis = analyses.open(document, analyses.save(document, analysisBytes).id)
        val wholeSummary = analysis.summaryLines
        document.recordsForApp(kimi).forEach { row -> assertNotNull(analysis.records[row.attemptId]) }
        assertEquals(wholeSummary, analysis.summaryLines)
        assertTrue(wholeSummary.joinToString().contains("计划：3；已尝试：3；未执行：0"))
        assertEquals(3, analysis.records.size)
        assertArrayEquals(analysisBytes, ByteArrayOutputStream().also { analyses.export(document, analysis.id, it) }.toByteArray())
        assertArrayEquals(bytes, ByteArrayOutputStream().also { store.export(document.id, it) }.toByteArray())
    }

    @Test fun missingAppsRemainBrowsableAndSourceSwitchCannotLeaveAStaleFilter() {
        val store = ResearchRecordStore(temporary.newFolder())
        val bytes = source(listOf("Kimi", null, "UNKNOWN", "", "豆包"))
        val first = store.open(store.save(bytes).id)
        assertEquals(listOf("Kimi", null, "豆包"), first.appFilters.map { it.appName })
        val missing = first.appFilters.single { it.appName == null }
        assertEquals("App 未提供/未知", missing.label)
        assertEquals(listOf("SAMPLE-2", "SAMPLE-3", "SAMPLE-4"), first.recordsForApp(missing).map { it.attemptId })
        val oldSelection = first.appFilters.single { it.appName == "Kimi" }
        val second = store.open(store.save(source(listOf("Kimi", "DeepSeek"))).id)
        assertNotEquals(first.id, second.id)
        assertEquals(second.records, second.recordsForApp(oldSelection))
        assertEquals(second.records, second.recordsForApp(missing))
        assertEquals(first.records, first.recordsForApp(null))
        assertArrayEquals(bytes, ByteArrayOutputStream().also { store.export(first.id, it) }.toByteArray())
    }

    @Test fun videoKeepsItsUnfilteredRecordPath() {
        val bytes = buildJsonObject {
            put("format", "research-video-1"); put("record_kind", "SAMPLE"); put("app", "SAMPLE Video")
            put("attempts", buildJsonArray { add(buildJsonObject {
                put("slot", "SAMPLE-VIDEO-1"); put("status", "NOT_RUN")
                put("V0", JsonNull); put("VF", JsonNull); put("window_end", JsonNull)
                put("window_complete", "not_started"); put("visible_target_playback", "not_observed")
                put("reason", "SAMPLE not executed")
            }) })
        }.toString().toByteArray()
        val store = ResearchRecordStore(temporary.newFolder())
        val video = store.open(store.save(bytes).id)
        assertTrue(video.appFilters.isEmpty())
        assertEquals(video.records, video.recordsForApp(ResearchAppFilter(video.id, "SAMPLE Video")))
        assertEquals("NOT_RUN", video.records.single().status)
        assertArrayEquals(bytes, ByteArrayOutputStream().also { store.export(video.id, it) }.toByteArray())
    }

    private fun source(names: List<String?>): ByteArray = buildJsonObject {
        put("record_kind", "SAMPLE")
        put("records", buildJsonArray { names.forEachIndexed { index, name -> add(buildJsonObject {
            put("record_kind", "SAMPLE"); put("method_id", "alignment-1"); put("attempt_id", "SAMPLE-${index + 1}")
            if (name != null) put("app", buildJsonObject { put("name", name); put("version", "SAMPLE-1") })
            put("outcome", buildJsonObject { put("status", "incomplete"); put("visible_completion", "uncertain") })
        }) } })
    }.toString().toByteArray()
}
