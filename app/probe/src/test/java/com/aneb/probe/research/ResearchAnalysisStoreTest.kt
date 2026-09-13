package com.aneb.probe.research

import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResearchAnalysisStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun sourceConfirmedCompletionCountDoesNotImplyUsableDurationAndMissingConditionsSurviveImport() {
        // TEST ONLY: three fabricated attempts; mirrors the two-yes/one-uncertain pattern, not private evidence.
        val raw = Json.parseToJsonElement(resource("r1-sample.json").toString(Charsets.UTF_8)).jsonObject
        val originals = raw["records"]!!.jsonArray.mapIndexed { index, element ->
            JsonObject(element.jsonObject.toMutableMap().apply {
                put("outcome", buildJsonObject {
                    put("executed", true); put("status", "completed")
                    put("visible_completion", if (index == 0) "uncertain" else "yes")
                    put("instruction_following", "uncertain")
                })
            })
        }
        val source = ResearchRecordStore.decode(JsonObject(raw.toMutableMap().apply {
            put("records", JsonArray(originals))
        }).toString().toByteArray())
        val template = Json.parseToJsonElement(resource("r1-analysis-sample.json").toString(Charsets.UTF_8)).jsonObject
        val rows = template["records"]!!.jsonArray.mapIndexed { index, element ->
            JsonObject(element.jsonObject.toMutableMap().apply {
                put("input_record", originals[index])
                put("completion", buildJsonObject {
                    put("status", "NA"); put("interval_s", JsonNull)
                    put("reason", if (index == 0) "completion_unconfirmed" else "completion_timing_unavailable")
                    if (index != 0) put("missing_conditions", JsonArray(listOf(
                        "last_content_unavailable", "continuous_visibility_unconfirmed", "stable_tail_unconfirmed",
                        "timing_event_unavailable", "TEST-ONLY-future-reason",
                    ).map(::JsonPrimitive)))
                })
            })
        }
        val bytes = JsonObject(template.toMutableMap().apply {
            put("source_sha256", JsonPrimitive(source.id)); put("records", JsonArray(rows))
            put("counts", buildJsonObject { put("planned", 3); put("attempted", 3); put("not_run", 0); put("visible_completed_confirmed", 2) })
        }).toString().toByteArray()
        val store = ResearchAnalysisStore(temporary.newFolder())
        val saved = store.save(source, bytes)
        val analysis = store.open(source, saved.id)
        val first = analysis.attemptLines(source.records[0].attemptId).single { it.startsWith("完成 Completion") }
        assertTrue(first, first.contains("可见完成未确认"))
        source.records.drop(1).forEach { attempt ->
            val lines = analysis.attemptLines(attempt.attemptId).joinToString("\n")
            assertTrue(lines, lines.contains("源标注可见完成；完成时长不可计算"))
            assertTrue(lines, lines.contains("缺少最后正文时刻（T3）"))
            assertTrue(lines, lines.contains("正文连续可见未确认"))
            assertTrue(lines, lines.contains("至少3秒稳定窗口未确认"))
            assertTrue(lines, lines.contains("完成时长条件不可用（未知原因）"))
            assertFalse(lines, lines.contains("TEST-ONLY-future-reason"))
            assertFalse(lines, lines.contains("可见完成未确认"))
        }
        assertTrue(analysis.summaryLines.joinToString("\n").contains("可见完成按源标注计数，不等于指令成功或App成功率"))
        assertTrue(analysis.summaryLines.joinToString("\n").contains("可见完成已确认：2"))
        source.records.forEach { assertTrue(analysis.attemptLines(it.attemptId).any { line -> line.startsWith("完成 Completion：NA") }) }
        println("TEST ONLY Chinese consumer evidence:\n" + analysis.summaryLines.joinToString("\n"))
        source.records.forEach { attempt -> println(attempt.attemptId + "\n" + analysis.attemptLines(attempt.attemptId).filter { it.startsWith("完成") }.joinToString("\n")) }
        assertArrayEquals(bytes, ByteArrayOutputStream().also { store.export(source, saved.id, it) }.toByteArray())
    }

    @Test fun importedAnalysisReopensBySourceAndAttemptWithoutChangingEitherOriginal() {
        val rawBytes = resource("r1-sample.json")
        val analysisBytes = resource("r1-analysis-sample.json")
        val originals = ResearchRecordStore(temporary.newFolder("originals"))
        val original = originals.save(rawBytes)
        val directory = temporary.newFolder("analyses")
        val saved = ResearchAnalysisStore(directory).save(original, analysisBytes)
        val reopened = ResearchAnalysisStore(directory).open(original, saved.id)
        assertEquals(original.id, reopened.sourceId)
        assertEquals(original.records.map { it.attemptId }.toSet(), reopened.records.keys)
        assertEquals("SAMPLE", reopened.root.text("record_kind"))
        assertEquals(listOf(saved.id), ResearchAnalysisStore(directory).list(original).map { it.id })
        assertArrayEquals(analysisBytes, ByteArrayOutputStream().also {
            ResearchAnalysisStore(directory).export(original, saved.id, it)
        }.toByteArray())
        assertArrayEquals(rawBytes, ByteArrayOutputStream().also { originals.export(original.id, it) }.toByteArray())
        assertEquals(1, originals.list().size)
    }

    @Test fun mismatchedOrIncompleteAnalysisCannotReplaceSavedAnalysisOrOriginal() {
        val originals = ResearchRecordStore(temporary.newFolder())
        val source = originals.save(resource("r1-sample.json"))
        val store = ResearchAnalysisStore(temporary.newFolder())
        val valid = resource("r1-analysis-sample.json")
        val saved = store.save(source, valid)
        val root = Json.parseToJsonElement(valid.toString(Charsets.UTF_8)).jsonObject
        val rows = root["records"]!!.jsonArray
        fun changed(key: String, value: JsonElement) = JsonObject(root.toMutableMap().apply { put(key, value) }).toString().toByteArray()
        val invalids = listOf(
            changed("source_sha256", JsonPrimitive("0".repeat(64))),
            changed("record_kind", JsonPrimitive("OBSERVED")),
            changed("records", JsonArray(rows.dropLast(1))),
            changed("records", JsonArray(rows + rows[0])),
            changed("records", JsonArray(rows.toMutableList().apply {
                this[0] = JsonObject(rows[0].jsonObject.toMutableMap().apply { put("attempt_id", JsonPrimitive("extra-attempt")) })
            })),
            changed("records", JsonArray(rows.toMutableList().apply {
                this[0] = JsonObject(rows[0].jsonObject.toMutableMap().apply { put("input_record", rows[1].jsonObject["input_record"]!!) })
            })),
        )
        invalids.forEach { bytes ->
            assertThrows(ResearchImportException::class.java) { store.save(source, bytes) }
            assertEquals(listOf(saved.id), store.list(source).map { it.id })
            assertArrayEquals(valid, ByteArrayOutputStream().also { store.export(source, saved.id, it) }.toByteArray())
        }
        // Even identical attempts cannot rebind an analysis to differently encoded original bytes.
        val other = originals.save(byteArrayOf(10) + resource("r1-sample.json"))
        assertThrows(ResearchImportException::class.java) { store.save(other, valid) }
        assertTrue(store.list(other).isEmpty())
        assertArrayEquals(resource("r1-sample.json"), ByteArrayOutputStream().also { originals.export(source.id, it) }.toByteArray())
    }

    @Test fun distinctCopiesReopenIndependentlyAndCorruptionDoesNotFallBackToAnotherResult() {
        val source = ResearchRecordStore.decode(resource("r1-sample.json"))
        val directory = temporary.newFolder()
        val store = ResearchAnalysisStore(directory)
        val bytes = resource("r1-analysis-sample.json")
        val first = store.save(source, bytes)
        assertEquals(first.id, store.save(source, bytes).id)
        val second = store.save(source, byteArrayOf(10) + bytes)
        assertEquals(2, store.list(source).size)
        assertNotEquals(first.id, second.id)
        java.io.File(java.io.File(directory, source.id), "${first.id}.json").writeText("broken")
        assertNotNull(store.list(source).single { it.id == first.id }.error)
        assertThrows(IllegalArgumentException::class.java) { store.open(source, first.id) }
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) { store.export(source, first.id, output) }
        assertEquals(0, output.size())
        assertEquals(second.id, ResearchAnalysisStore(directory).open(source, second.id).id)
    }

    @Test fun sampleDisplayPreservesSecondsIntervalsMissingReasonsAndPartialStallEvidence() {
        val source = ResearchRecordStore.decode(resource("r1-sample.json"))
        val analysis = ResearchAnalysisStore.decode(source, resource("r1-analysis-sample.json"))
        val summary = analysis.summaryLines.joinToString("\n")
        assertTrue(summary.contains("计划：3"))
        assertTrue(summary.contains("已尝试：2"))
        assertTrue(summary.contains("未执行：1"))
        assertTrue(summary.contains("可见完成已确认：1"))
        assertTrue(analysis.groupLines.single().contains("无可比较分组"))
        val first = analysis.attemptLines("SAMPLE-KIMI-R1").joinToString("\n")
        assertTrue(first.contains("TTFR：[0.76, 0.84] 秒"))
        assertTrue(first.contains("TTFC：[1.96, 2.04] 秒"))
        assertTrue(first.contains("Completion：[7.96, 8.04] 秒"))
        assertTrue(first.contains("确认观察到的停顿：[2.36, 2.44] 秒"))
        assertTrue(first.contains("总次数：未知"))
        val second = analysis.attemptLines("SAMPLE-KIMI-R2").joinToString("\n")
        assertTrue(second.contains("Completion：NA"))
        assertTrue(second.contains("completion_unconfirmed"))
        assertTrue(second.contains("右删失尾段：[2.96, 3.04] 秒"))
        assertTrue(second.contains("不是完整停顿时长"))
        assertTrue(second.contains("0 不代表没有停顿"))
        assertTrue(analysis.attemptLines("SAMPLE-KIMI-R3").joinToString("\n").contains("not_executed"))
    }

    @Test fun suppliedUncertaintyAndGroupPointSubsetAreLabeledWithoutWholeGroupEstimates() {
        // TEST ONLY derived carrier following 3a's output shape; not a changed official SAMPLE or computed research result.
        val source = ResearchRecordStore.decode(resource("r1-sample.json"))
        val root = Json.parseToJsonElement(resource("r1-analysis-sample.json").toString(Charsets.UTF_8)).jsonObject
        val rows = root["records"]!!.jsonArray.toMutableList()
        rows[0] = JsonObject(rows[0].jsonObject.toMutableMap().apply {
            put("ttfr", buildJsonObject {
                put("status", "uncertain"); put("interval_s", JsonNull); put("reason", "event_order_uncertain")
            })
        })
        val groups = Json.parseToJsonElement("""[{"group_id":"TEST-ONLY-group","comparability_key":["TEST-ONLY"],"metrics":{"ttfr":{"attempts":[],"valid_n":2,"point_n":1,"na_n":0,"uncertain_n":1,"point_median_s":0.8,"point_range_s":[0.8,0.8]}}}]""")
        val bytes = JsonObject(root.toMutableMap().apply {
            put("records", JsonArray(rows)); put("groups", groups); put("test_fixture", JsonPrimitive("TEST ONLY presentation values"))
        }).toString().toByteArray()
        val analysis = ResearchAnalysisStore.decode(source, bytes)
        val attempt = analysis.attemptLines("SAMPLE-KIMI-R1").joinToString("\n")
        assertTrue(attempt.contains("不确定（uncertain）"))
        assertTrue(attempt.contains("event_order_uncertain"))
        val group = analysis.groupLines.joinToString("\n")
        assertTrue(group.contains("有效区间 valid_n：2"))
        assertTrue(group.contains("点子集 point_n：1"))
        assertTrue(group.contains("点子集中位数：0.8 秒"))
        assertTrue(group.contains("不是整组中位数"))
        assertTrue(group.contains("点子集范围：[0.8, 0.8] 秒"))
        assertTrue(group.contains("TEST-ONLY-group"))
    }

    @Test fun declaredObservedAnalysisKeepsItsOwnKindAndRawCopyWithoutClaimingVerification() {
        // TEST ONLY: fabricated association carrier, not observed data or a 3a measurement receipt.
        val raw = Json.parseToJsonElement(resource("r1-sample.json").toString(Charsets.UTF_8)).jsonObject
        val rows = raw["records"]!!.jsonArray.mapIndexed { index, element ->
            JsonObject(element.jsonObject.toMutableMap().apply {
                put("record_kind", JsonPrimitive("OBSERVED"))
                put("attempt_id", JsonPrimitive("TEST-ONLY-OBSERVED-${index + 1}"))
            })
        }
        val sourceBytes = JsonObject(raw.toMutableMap().apply {
            put("record_kind", JsonPrimitive("OBSERVED")); put("records", JsonArray(rows))
            put("test_fixture", JsonPrimitive("TEST ONLY fabricated association"))
        }).toString().toByteArray()
        val originals = ResearchRecordStore(temporary.newFolder())
        val source = originals.save(sourceBytes)
        val template = Json.parseToJsonElement(resource("r1-analysis-sample.json").toString(Charsets.UTF_8)).jsonObject
        val derivedRows = template["records"]!!.jsonArray.mapIndexed { index, element ->
            JsonObject(element.jsonObject.toMutableMap().apply {
                put("attempt_id", rows[index]["attempt_id"]!!); put("input_record", rows[index])
            })
        }
        val bytes = JsonObject(template.toMutableMap().apply {
            put("record_kind", JsonPrimitive("OBSERVED")); put("source_sha256", JsonPrimitive(source.id))
            put("records", JsonArray(derivedRows)); put("test_fixture", JsonPrimitive("TEST ONLY fabricated association"))
        }).toString().toByteArray()
        val directory = temporary.newFolder()
        val saved = ResearchAnalysisStore(directory).save(source, bytes)
        val reopened = ResearchAnalysisStore(directory).open(originals.open(source.id), saved.id)
        assertTrue(reopened.summaryLines.joinToString("\n").contains("OBSERVED"))
        assertTrue(reopened.summaryLines.joinToString("\n").contains("未独立核验"))
        assertTrue(reopened.exportFileName.contains("analysis-OBSERVED"))
        assertEquals(rows.map { it.text("attempt_id") }.toSet(), reopened.records.keys)
        assertArrayEquals(bytes, ByteArrayOutputStream().also { ResearchAnalysisStore(directory).export(source, saved.id, it) }.toByteArray())
        assertArrayEquals(sourceBytes, ByteArrayOutputStream().also { originals.export(source.id, it) }.toByteArray())
    }

    // These repository fixtures bind a Git/LF SHA; do not inherit Windows checkout CRLF.
    // Production imports remain byte-exact and never normalize user documents.
    private fun resource(name: String): ByteArray = checkNotNull(javaClass.getResourceAsStream("/research/$name")).use {
        it.readBytes().toString(Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
    }
}
