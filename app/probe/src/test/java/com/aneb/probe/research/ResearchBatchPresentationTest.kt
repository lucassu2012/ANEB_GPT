package com.aneb.probe.research

import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResearchBatchPresentationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun ninePlannedRecordsIncludingThreeNotRunAreNotNineAttempts() {
        // Synthetic B01 shape: six actual observations, three unexecuted plan slots.
        val raw = Json.parseToJsonElement(textSource(List(9) { "SAMPLE App" to "SAMPLE-1" }).toString(Charsets.UTF_8)).jsonObject
        val root = JsonObject(raw.toMutableMap().apply {
            put("records", JsonArray(raw["records"]!!.jsonArray.mapIndexed { index, row ->
                JsonObject(row.jsonObject.toMutableMap().apply {
                    put("outcome", buildJsonObject {
                        put("status", if (index < 6) "incomplete" else "not_run")
                        put("executed", index < 6)
                        put("visible_completion", "uncertain")
                    })
                })
            }))
        })
        val bytes = root.toString().toByteArray()
        val store = ResearchRecordStore(temporary.newFolder())
        val saved = store.save(bytes)
        val reopened = store.open(saved.id)
        val summary = reopened.batchSummaryLines.joinToString("\n")
        assertTrue(summary, summary.contains("9 条记录"))
        assertFalse(summary, summary.contains("9 次尝试"))
        assertEquals(3, reopened.records.count { it.status == "not_run" })
        // Imported counts stay producer-owned; changing the batch noun must not recalculate them.
        val analysisBytes = buildJsonObject {
            put("source_sha256", saved.id); put("record_kind", "SAMPLE"); put("input_revision", "alignment-1")
            put("groups", buildJsonArray {})
            put("counts", buildJsonObject {
                put("planned", 9); put("attempted", 6); put("not_run", 3); put("visible_completed_confirmed", 0)
            })
            put("records", buildJsonArray { reopened.records.forEach { row -> add(buildJsonObject {
                put("attempt_id", row.attemptId); put("input_record", row.raw)
            }) } })
        }.toString().toByteArray()
        val analyses = ResearchAnalysisStore(temporary.newFolder())
        val analysis = analyses.open(reopened, analyses.save(reopened, analysisBytes).id)
        assertTrue(analysis.summaryLines.joinToString("\n").contains("计划：9；已尝试：6；未执行：3"))
        assertArrayEquals(analysisBytes, ByteArrayOutputStream().also { analyses.export(reopened, analysis.id, it) }.toByteArray())
        assertEquals(saved.id, reopened.id)
        assertArrayEquals(bytes, ByteArrayOutputStream().also { store.export(saved.id, it) }.toByteArray())
    }

    @Test fun incompleteRemainsUnconfirmedRatherThanAppFailureAfterReopening() {
        val statuses = listOf("incomplete", "failed", "cancelled", "not_run", "future-status", null)
        val raw = Json.parseToJsonElement(textSource(statuses.map { "SAMPLE App" to "SAMPLE-1" }).toString(Charsets.UTF_8)).jsonObject
        val bytes = JsonObject(raw.toMutableMap().apply {
            put("records", JsonArray(raw["records"]!!.jsonArray.mapIndexed { index, row ->
                JsonObject(row.jsonObject.toMutableMap().apply {
                    put("outcome", buildJsonObject {
                        statuses[index]?.let { put("status", it) }
                        put("visible_completion", "uncertain")
                    })
                })
            }))
        }).toString().toByteArray()
        val store = ResearchRecordStore(temporary.newFolder())
        val saved = store.save(bytes)
        val reopened = store.open(saved.id)
        assertEquals(listOf("完成未确认", "失败", "已取消", "未执行", "未知：future-status", "未知：NA"), reopened.records.map { it.statusLabel })
        assertEquals("可能尚未完成，或完成证据不足；不表示 App 失败。", reopened.records.first().statusNotice)
        assertTrue(reopened.records.drop(1).all { it.statusNotice == null })
        assertEquals(statuses, reopened.records.map { it.status })
        assertTrue(reopened.records.all { it.raw["outcome"]!!.jsonObject.text("visible_completion") == "uncertain" })
        assertEquals(saved.id, reopened.id)
        assertArrayEquals(bytes, ByteArrayOutputStream().also { store.export(saved.id, it) }.toByteArray())
    }

    @Test fun perRecordAppNamesIdentifySeparateBatchesWithoutRewritingOriginals() {
        // All values are synthetic; same shape as a batch with no root-level app.
        listOf("Kimi" to "SAMPLE-K-1", "豆包" to "SAMPLE-D-2").forEach { (name, version) ->
            val bytes = textSource(List(3) { name to version })
            val store = ResearchRecordStore(temporary.newFolder())
            val saved = store.save(bytes)
            val reopened = store.open(saved.id)
            val summary: String = reopened.batchSummaryLines.joinToString("\n")
            assertTrue(summary, summary.contains(name) && summary.contains(version))
            assertTrue(summary, summary.contains("SAMPLE") && summary.contains("3 条记录"))
            assertTrue(summary, summary.contains(saved.id.take(12)))
            assertFalse(summary, summary.contains("PRIVATE-CONTENT-BAIT"))
            assertFalse(reopened.root.containsKey("app"))
            assertEquals(saved.id, reopened.id)
            assertArrayEquals(bytes, ByteArrayOutputStream().also { store.export(saved.id, it) }.toByteArray())
        }
    }

    @Test fun mixedAndUnprovidedMetadataNeverPretendTheFirstRecordRepresentsTheBatch() {
        data class Case(val apps: List<Pair<String?, String?>>, val appLabel: String, val versionLabel: String)
        val cases = listOf(
            Case(listOf("Kimi" to "SAMPLE-1", "豆包" to "SAMPLE-2"), "多个 App", "多个版本"),
            Case(listOf("Kimi" to "SAMPLE-1", "Kimi" to "SAMPLE-2"), "Kimi", "多个版本"),
            Case(listOf("Kimi" to "SAMPLE-1", "豆包" to "SAMPLE-1"), "多个 App", "SAMPLE-1"),
            Case(listOf("Kimi" to "SAMPLE-1", null to null), "部分记录未提供", "部分记录未提供"),
            Case(listOf(null to null, null to null), "App 未提供", "版本 未提供"),
            Case(listOf("UNKNOWN" to "UNKNOWN"), "App 未提供", "版本 未提供"),
        )
        cases.forEach { case ->
            val original = Json.parseToJsonElement(textSource(case.apps).toString(Charsets.UTF_8)).jsonObject
            val root = JsonObject(original.toMutableMap().apply { put("app", JsonPrimitive("ROOT-MUST-NOT-BE-FALLBACK")) })
            val bytes = root.toString().toByteArray()
            val store = ResearchRecordStore(temporary.newFolder())
            val source = store.open(store.save(bytes).id)
            val lines = source.batchSummaryLines
            assertTrue(lines.toString(), lines[0].contains(case.appLabel))
            assertTrue(lines.toString(), lines[1].contains(case.versionLabel))
            assertFalse(lines.toString(), lines.joinToString().contains("ROOT-MUST-NOT-BE-FALLBACK"))
            assertFalse(lines.toString(), lines.joinToString().contains("PRIVATE-CONTENT-BAIT"))
            assertFalse(lines.toString(), lines.joinToString().contains("UNKNOWN"))
            assertArrayEquals(bytes, ByteArrayOutputStream().also { store.export(source.id, it) }.toByteArray())
        }
    }

    @Test fun videoUsesItsDeclaredRootAppAndOptionalVersionWithoutChangingNotRun() {
        listOf("SAMPLE-V" to "SAMPLE-V", null to "未提供").forEach { (version, expected) ->
            val bytes = buildJsonObject {
                put("format", "research-video-1"); put("record_kind", "SAMPLE"); put("app", "SAMPLE 视频 App")
                if (version != null) put("version", version)
                put("attempts", buildJsonArray { add(buildJsonObject {
                    put("slot", "SAMPLE-VIDEO-1"); put("status", "NOT_RUN")
                    put("V0", JsonNull); put("VF", JsonNull); put("window_end", JsonNull)
                    put("window_complete", "not_started"); put("visible_target_playback", "not_observed")
                    put("reason", "PRIVATE-CONTENT-BAIT")
                    put("app", buildJsonObject { put("name", "DO-NOT-USE-TEXT-APP") })
                }) })
            }.toString().toByteArray()
            val store = ResearchRecordStore(temporary.newFolder())
            val source = store.open(store.save(bytes).id)
            val lines = source.batchSummaryLines
            assertTrue(lines[0], lines[0].contains("视频") && lines[0].contains("SAMPLE 视频 App"))
            assertTrue(lines[1], lines[1].contains(expected))
            assertTrue(lines.last(), lines.last().contains("SAMPLE") && lines.last().contains("1 条记录"))
            assertFalse(lines.toString(), lines.joinToString().contains("DO-NOT-USE-TEXT-APP"))
            assertFalse(lines.toString(), lines.joinToString().contains("PRIVATE-CONTENT-BAIT"))
            assertEquals("NOT_RUN", source.records.single().status)
            assertArrayEquals(bytes, ByteArrayOutputStream().also { store.export(source.id, it) }.toByteArray())
        }
    }

    private fun textSource(apps: List<Pair<String?, String?>>): ByteArray = buildJsonObject {
        put("record_kind", "SAMPLE")
        put("action_text", "PRIVATE-CONTENT-BAIT: not a batch title")
        put("records", buildJsonArray {
            apps.forEachIndexed { i, (name, version) -> add(buildJsonObject {
                put("record_kind", "SAMPLE"); put("method_id", "alignment-1"); put("attempt_id", "SAMPLE-${i + 1}")
                put("app", buildJsonObject {
                    if (name != null) put("name", name)
                    if (version != null) put("version", version)
                })
                put("reason", "PRIVATE-CONTENT-BAIT: no summary inference")
            }) }
        })
    }.toString().toByteArray()
}
