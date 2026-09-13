package com.aneb.probe.research

import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResearchBatchPresentationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun perRecordAppNamesIdentifySeparateBatchesWithoutRewritingOriginals() {
        // All values are synthetic; same shape as a batch with no root-level app.
        listOf("Kimi" to "SAMPLE-K-1", "豆包" to "SAMPLE-D-2").forEach { (name, version) ->
            val bytes = textSource(List(3) { name to version })
            val store = ResearchRecordStore(temporary.newFolder())
            val saved = store.save(bytes)
            val reopened = store.open(saved.id)
            val summary: String = reopened.batchSummaryLines.joinToString("\n")
            assertTrue(summary, summary.contains(name) && summary.contains(version))
            assertTrue(summary, summary.contains("SAMPLE") && summary.contains("3 次尝试"))
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
            assertTrue(lines.last(), lines.last().contains("SAMPLE") && lines.last().contains("1 次尝试"))
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
