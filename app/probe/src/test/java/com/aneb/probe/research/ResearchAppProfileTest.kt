package com.aneb.probe.research

import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResearchAppProfileTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun savedBatchesBuildOneAppProfileButNavigateToTheirOwnSourceAndAnalysis() {
        val directory = temporary.newFolder()
        val store = ResearchRecordStore(directory)
        val firstBytes = source("FIRST", listOf("Kimi", "豆包", "Kimi"), "SAMPLE-V1", "SAMPLE-M1")
        val secondBytes = source("SECOND", listOf("Kimi"), "SAMPLE-V2", "SAMPLE-M2")
        val first = store.save(firstBytes)
        val second = store.save(secondBytes)
        val reopenedStore = ResearchRecordStore(directory)
        val profile = reopenedStore.list().appResearchProfiles().single { it.appName == "Kimi" }
        assertEquals(setOf(first.id, second.id), profile.batches.map { it.sourceId }.toSet())
        val mixed = profile.batches.single { it.sourceId == first.id }
        assertEquals(listOf("FIRST-1", "FIRST-3"), mixed.records.map { it.attemptId })
        assertEquals(1, profile.batches.single { it.sourceId == second.id }.records.size)
        assertTrue(mixed.summaryLines.joinToString().contains("本 App：2 条记录"))
        assertTrue(mixed.recordLines.joinToString().contains("SAMPLE-V1"))
        assertTrue(mixed.recordLines.joinToString().contains("SAMPLE-M1"))
        val later = profile.batches.single { it.sourceId == second.id }
        assertTrue(later.recordLines.joinToString().contains("SAMPLE-V2"))
        assertTrue(later.recordLines.joinToString().contains("SAMPLE-M2"))
        assertFalse(later.recordLines.joinToString().contains("SAMPLE-M1"))
        val analyses = ResearchAnalysisStore(temporary.newFolder())
        val a = analyses.save(first, analysisBytes(first))
        val alternateBytes = analysisBytes(first, "SAMPLE alternate analysis")
        val alternate = analyses.save(first, alternateBytes)
        val b = analyses.save(second, analysisBytes(second))
        val target = reopenedStore.open(mixed.sourceId)
        assertEquals(mixed.records, target.recordsForApp(mixed.filter))
        assertEquals(setOf(a.id, alternate.id), analyses.list(target).map { it.id }.toSet())
        assertArrayEquals(alternateBytes, ByteArrayOutputStream().also { analyses.export(target, alternate.id, it) }.toByteArray())
        assertEquals(listOf(b.id), analyses.list(second).map { it.id })
        assertThrows(IllegalArgumentException::class.java) { ResearchAnalysisStore.decode(target, analysisBytes(second)) }
        assertArrayEquals(firstBytes, ByteArrayOutputStream().also { reopenedStore.export(first.id, it) }.toByteArray())
        assertArrayEquals(secondBytes, ByteArrayOutputStream().also { reopenedStore.export(second.id, it) }.toByteArray())
    }

    @Test fun unknownMetadataAndOneDamagedFileDoNotHideHealthyBatchesOnReopen() {
        val directory = temporary.newFolder()
        val bytes = source("UNKNOWN", listOf(null, "UNKNOWN", "Kimi"), null, "UNKNOWN")
        val saved = ResearchRecordStore(directory).save(bytes)
        val broken = File(directory, "f".repeat(64) + ".json")
        broken.writeText("{")
        val reopenedStore = ResearchRecordStore(directory)
        val entries = reopenedStore.list()
        assertEquals(2, entries.size)
        val error = entries.single { it.document == null }
        assertNotNull(error.error)
        assertEquals("{", broken.readText())
        val profiles = entries.appResearchProfiles()
        assertEquals(setOf(null, "Kimi"), profiles.map { it.appName }.toSet())
        val unknown = profiles.single { it.appName == null }
        assertEquals("App 未提供/未知", unknown.label)
        val batch = unknown.batches.single()
        assertEquals(saved.id, batch.sourceId)
        assertEquals(listOf("UNKNOWN-1", "UNKNOWN-2"), batch.records.map { it.attemptId })
        assertTrue(batch.recordLines.all { it.contains("版本：未提供/未知") && it.contains("模式：未提供/未知") })
        assertTrue(batch.recordLines.all { it.contains("完成未确认") && it.contains("uncertain") })
        assertTrue(batch.summaryLines.joinToString().contains("alignment-1"))
        assertTrue(batch.summaryLines.joinToString().contains("SAMPLE"))
        assertEquals(listOf("UNKNOWN-3"), profiles.single { it.appName == "Kimi" }.batches.single().records.map { it.attemptId })
        assertArrayEquals(bytes, ByteArrayOutputStream().also { reopenedStore.export(saved.id, it) }.toByteArray())
    }

    @Test fun videoWithSameAppNameKeepsASeparateSourceEntry() {
        val store = ResearchRecordStore(temporary.newFolder())
        val text = store.save(source("TEXT", listOf("Kimi"), "SAMPLE-V", "SAMPLE-M"))
        val videoBytes = buildJsonObject {
            put("format", "research-video-1"); put("record_kind", "SAMPLE"); put("app", "Kimi")
            put("attempts", buildJsonArray { add(buildJsonObject {
                put("slot", "SAMPLE-VIDEO-1"); put("status", "NOT_RUN")
                put("V0", JsonNull); put("VF", JsonNull); put("window_end", JsonNull)
                put("window_complete", "not_started"); put("visible_target_playback", "not_observed")
                put("reason", "SAMPLE not executed")
            }) })
        }.toString().toByteArray()
        val video = store.save(videoBytes)
        val entries = store.list()
        assertEquals(listOf(text.id), entries.appResearchProfiles().single().batches.map { it.sourceId })
        assertEquals(video.id, entries.single { it.document?.isVideo == true }.id)
        assertEquals("NOT_RUN", store.open(video.id).records.single().status)
        assertArrayEquals(videoBytes, ByteArrayOutputStream().also { store.export(video.id, it) }.toByteArray())
    }

    private fun source(prefix: String, names: List<String?>, version: String?, mode: String?): ByteArray = buildJsonObject {
        put("record_kind", "SAMPLE")
        put("records", buildJsonArray { names.forEachIndexed { i, name -> add(buildJsonObject {
            put("record_kind", "SAMPLE"); put("method_id", "alignment-1"); put("attempt_id", "$prefix-${i + 1}")
            put("app", buildJsonObject {
                if (name != null) put("name", name)
                if (version != null) put("version", version)
                if (mode != null) put("model_mode", mode)
            })
            put("outcome", buildJsonObject { put("status", "incomplete"); put("visible_completion", "uncertain") })
        }) } })
    }.toString().toByteArray()

    private fun analysisBytes(source: ResearchDocument, note: String = "SAMPLE analysis"): ByteArray = buildJsonObject {
        put("test_note", note)
        put("source_sha256", source.id); put("record_kind", "SAMPLE"); put("input_revision", "alignment-1")
        put("counts", buildJsonObject {}); put("groups", buildJsonArray {})
        put("records", buildJsonArray { source.records.forEach { row -> add(buildJsonObject {
            put("attempt_id", row.attemptId); put("input_record", row.raw)
        }) } })
    }.toString().toByteArray()
}
