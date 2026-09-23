package com.aneb.probe.research

import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Synthetic reader examples only; no private research input is checked in. */
class ResearchConversationPresentationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun topLevelAnnotationsRemainVisibleAfterReopenWithoutRewritingSource() {
        val raw = JsonObject(row("synthetic-t2", "SyntheticApp", null, null, "Synthetic prompt").toMutableMap().apply {
            put("conversation_id", JsonPrimitive("synthetic-conversation"))
            put("turn_index", JsonPrimitive(2))
            put("depends_on_attempt_id", JsonPrimitive("synthetic-t1"))
        })
        val bytes = source(listOf(raw))
        val store = ResearchRecordStore(temporary.newFolder())
        val document = store.open(store.save(bytes).id)
        val conversation = document.conversationsForApp(null).single()
        assertEquals("synthetic-conversation", conversation.conversationId)
        val turn = conversation.turns.single()
        assertEquals("第 2 轮（原标注）", turn.label)
        assertTrue(turn.contextLines.any { it.contains("依赖记录：synthetic-t1") })
        assertEquals(1, document.readerSelection(null, conversation, null).records.size)
        assertArrayEquals(bytes, ByteArrayOutputStream().also { store.export(document.id, it) }.toByteArray())
    }

    @Test fun conflictingAnnotationsStayUnknownInsteadOfChoosingOneOrigin() {
        val raw = JsonObject(row("conflict", "SyntheticApp", "nested", 1, "prompt", "nested-parent").toMutableMap().apply {
            put("conversation_id", JsonPrimitive("top")); put("turn_index", JsonPrimitive(2))
            put("depends_on_attempt_id", JsonPrimitive("top-parent"))
        })
        val bytes = source(listOf(raw))
        val store = ResearchRecordStore(temporary.newFolder())
        val doc = store.open(store.save(bytes).id)
        val group = doc.conversationsForApp(null).single()
        assertNull(group.conversationId)
        assertNull(group.turns.single().turnIndex)
        val lines = group.turns.single().contextLines
        assertTrue(lines.any { it.startsWith("会话标注：") && it.contains("冲突") })
        assertTrue(lines.any { it.startsWith("依赖记录：") && it.contains("冲突") })
        assertArrayEquals(bytes, ByteArrayOutputStream().also { store.export(doc.id, it) }.toByteArray())
    }

    @Test fun importedLongIntervalIsDisplayedOutwardWithoutChangingAnalysisBytes() {
        val doc = ResearchRecordStore.decode(source(listOf(row("timing", "SyntheticApp", "conversation", 2, "prompt"))))
        val root = Json.parseToJsonElement(analysisBytes(doc, "interval").toString(Charsets.UTF_8)).jsonObject
        val originalRow = root["records"]!!.jsonArray.single().jsonObject
        val bytes = JsonObject(root.toMutableMap().apply {
            put("records", JsonArray(listOf(JsonObject(originalRow.toMutableMap().apply {
                put("ttfc", buildJsonObject {
                    put("status", "interval")
                    put("interval_s", Json.parseToJsonElement("[10.235444444444444,10.437222222222223]"))
                })
            }))))
        }).toString().toByteArray()
        val store = ResearchAnalysisStore(temporary.newFolder())
        val analysis = store.open(doc, store.save(doc, bytes).id)
        val reading = doc.readerSelection(null, null, analysis)
        val lines = reading.turns.single().analysisLines(reading.analysis)
        assertTrue(lines.any { it.contains("TTFC：[10.235, 10.438] 秒") })
        assertTrue(lines.any { it.contains("向外取整") && it.contains("原始数据未改") })
        assertArrayEquals(bytes, ByteArrayOutputStream().also { store.export(doc, analysis.id, it) }.toByteArray())
    }

    @Test fun topLevelTurnsStayWithinTheirFileAndInvalidTurnRemainsUnassigned() {
        fun top(id: String, index: JsonElement) = JsonObject(row(id, "SyntheticApp", null, null, id).toMutableMap().apply {
            put("conversation_id", JsonPrimitive("same-explicit-conversation")); put("turn_index", index)
        })
        val first = ResearchRecordStore.decode(source(listOf(top("first", JsonPrimitive(1)))))
        val second = ResearchRecordStore.decode(source(listOf(top("second", JsonPrimitive(2)), top("invalid", JsonPrimitive("3")))))
        val stale = first.conversationsForApp(null).single()
        val current = second.readerSelection(null, stale, null)
        assertEquals(listOf("second", "invalid"), current.records.map { it.attemptId })
        assertEquals(2, current.turns.first().turnIndex)
        assertNull(current.turns.last().turnIndex)
        assertEquals("未分会话", second.conversationsForApp(null).last().label)
        assertTrue(current.turns.all { it.sourceId == second.id })
        assertTrue(current.turns.first().contextLines.any { it.contains("不拼接跨文件") })
    }

    @Test fun intervalDisplayKeepsExactBoundariesAndUnknownsWhenAnalysisChanges() {
        val doc = ResearchRecordStore.decode(source(listOf(row("timing", "SyntheticApp", null, null, "prompt"))))
        val store = ResearchAnalysisStore(temporary.newFolder())
        val template = Json.parseToJsonElement(analysisBytes(doc, "interval").toString(Charsets.UTF_8)).jsonObject
        val original = template["records"]!!.jsonArray.single().jsonObject
        listOf("[0.9999,1.0001]" to "[0.999, 1.001]", "[1.234,1.234]" to "[1.234, 1.234]").forEach { (raw, expected) ->
            val bytes = JsonObject(template.toMutableMap().apply {
                put("records", JsonArray(listOf(JsonObject(original.toMutableMap().apply {
                    put("ttfc", buildJsonObject { put("status", "interval"); put("interval_s", Json.parseToJsonElement(raw)) })
                }))))
            }).toString().toByteArray()
            val analysis = store.open(doc, store.save(doc, bytes).id)
            assertTrue(doc.readerSelection(null, null, analysis).turns.single().analysisLines(analysis).any { it.contains(expected) })
            assertArrayEquals(bytes, ByteArrayOutputStream().also { store.export(doc, analysis.id, it) }.toByteArray())
        }
        listOf("uncertain", "NA").forEach { status ->
            val bytes = analysisBytes(doc, status)
            val analysis = store.open(doc, store.save(doc, bytes).id)
            val lines = doc.readerSelection(null, null, analysis).turns.single().analysisLines(analysis)
            assertTrue(lines.single { it.startsWith("首个内容 TTFC") }.contains(status))
            assertFalse(lines.single { it.startsWith("首个内容 TTFC") }.contains("[0"))
        }
        assertNull(doc.readerSelection(null, null, null).analysis)
    }

    @Test fun resultsFirstSelectionKeepsOnlyCurrentScopeAndExplicitAnalysis() {
        val document = ResearchRecordStore.decode(source(listOf(
            row("a", "App甲", "会话甲", 1, "甲提示"),
            row("b", "App乙", "会话乙", 1, "乙提示"),
        )))
        val analyses = ResearchAnalysisStore(temporary.newFolder())
        val selected = analyses.save(document, analysisBytes(document, "uncertain"))
        val other = ResearchRecordStore.decode(source(listOf(row("a", "App甲", "会话甲", 1, "另一批"))))
        val app = document.appFilters.first()
        val conversation = document.conversationsForApp(app).single()
        val reading = document.readerSelection(app, conversation, selected)
        assertEquals(listOf("a"), reading.turns.map { it.attempt.attemptId })
        assertSame(selected, reading.analysis)
        assertEquals(selected.attemptLines("a"), reading.turns.single().analysisLines(reading.analysis))
        assertTrue(reading.scopeLabel.contains("App甲"))
        assertTrue(reading.scopeLabel.contains("会话甲"))
        assertNull(document.readerSelection(app, conversation, null).analysis)
        val switched = other.readerSelection(app, conversation, selected)
        assertNull(switched.analysis)
        assertEquals("另一批", switched.turns.single().prompt)
        assertTrue(switched.scopeLabel.contains("全部"))
        assertEquals(listOf("b"), document.readerSelection(document.appFilters.last(), conversation, selected).turns.map { it.attempt.attemptId })
    }

    @Test fun reopenedBatchShowsExplicitTurnsAndKeepsUnassignedRecordsWithoutChangingExport() {
        val bytes = source(listOf(
            row("second", "示例App", "会话甲", 2, "合成提示：缩短时间", "first"),
            row("first", "示例App", "会话甲", 1, "合成提示：安排时间"),
            row("unassigned", "示例App", null, null, null),
            row("other", "示例App", "会话乙", 1, "另一合成提示"),
        ))
        val directory = temporary.newFolder()
        val saved = ResearchRecordStore(directory).save(bytes)
        val store = ResearchRecordStore(directory)
        val document = store.open(saved.id)
        val conversations = document.conversationsForApp(document.appFilters.single())
        assertEquals(listOf("会话甲", null, "会话乙"), conversations.map { it.conversationId })
        val first = conversations.first()
        assertEquals(listOf("first", "second"), first.turns.map { it.attempt.attemptId })
        assertEquals("第 2 轮（原标注）", first.turns[1].label)
        assertEquals("合成提示：缩短时间", first.turns[1].prompt)
        assertTrue(first.turns[1].contextLines.any { it.contains("依赖记录：first") })
        assertEquals("完成未确认", first.turns[1].attempt.statusLabel)
        assertEquals("未分会话", conversations[1].label)
        assertEquals("逐轮提示词未提供", conversations[1].turns.single().prompt)
        assertArrayEquals(bytes, ByteArrayOutputStream().also { store.export(saved.id, it) }.toByteArray())
    }

    @Test fun videoReaderKeepsWholeVideoScopeAndDoesNotSelectAnAnalysisImplicitly() {
        val bytes = """{"format":"research-video-1","record_kind":"SAMPLE","app":"SAMPLE video",
          "attempts":[{"slot":"SAMPLE-video","status":"NOT_RUN","reason":"SAMPLE not executed",
            "V0":null,"VF":null,"window_end":null,"window_complete":"not_started","visible_target_playback":"not_observed"}]}""".toByteArray()
        val store = ResearchRecordStore(temporary.newFolder())
        val document = store.open(store.save(bytes).id)
        val text = ResearchRecordStore.decode(source(listOf(row("a", "App甲", "会话甲", 1, "提示"))))
        val reading = document.readerSelection(text.appFilters.single(), text.conversationsForApp(null).single(), null)
        assertTrue(reading.turns.isEmpty())
        assertEquals(document.records, reading.records)
        assertEquals("当前范围：本批全部视频记录", reading.scopeLabel)
        assertNull(reading.analysis)
        assertEquals(document.records.single().statusLabel, reading.records.single().statusLabel)
        assertTrue(reading.records.single().statusLabel.contains("不计播放失败"))
        assertArrayEquals(bytes, ByteArrayOutputStream().also { store.export(document.id, it) }.toByteArray())
    }

    @Test fun switchingAppOrSourceCannotRetainAnotherConversationOrAnalysis() {
        val store = ResearchRecordStore(temporary.newFolder())
        val firstBytes = source(listOf(
            row("a", "App甲", "同名会话", 1, "甲提示"),
            row("b", "App乙", "同名会话", 1, "乙提示"),
        ))
        val first = store.save(firstBytes)
        val second = store.save(source(listOf(row("a", "App甲", "同名会话", 1, "另一批提示"))))
        val a = first.conversationsForApp(first.appFilters.first())
        val b = first.conversationsForApp(first.appFilters.last())
        assertEquals(listOf("b"), b.turnsForSelection(a.single()).map { it.attempt.attemptId })
        val later = second.conversationsForApp(second.appFilters.single())
        assertEquals("另一批提示", later.turnsForSelection(a.single()).single().prompt)
        val analyses = ResearchAnalysisStore(temporary.newFolder())
        assertTrue(analyses.list(first).isEmpty())
        assertTrue(a.single().turns.single().analysisLines(null).single().contains("尚未选择"))
        val bytes1 = analysisBytes(first, "uncertain")
        val bytes2 = analysisBytes(first, "NA")
        val copy1 = analyses.save(first, bytes1)
        val copy2 = analyses.save(first, bytes2)
        assertEquals(setOf(copy1.id, copy2.id), analyses.list(first).map { it.id }.toSet())
        assertTrue(a.single().turns.single().analysisLines(copy1).any { it.contains("不确定") })
        assertTrue(a.single().turns.single().analysisLines(copy2).any { it.contains("NA") })
        assertTrue(later.single().turns.single().analysisLines(copy1).single().contains("尚未选择"))
        assertArrayEquals(bytes2, ByteArrayOutputStream().also { analyses.export(first, copy2.id, it) }.toByteArray())
        assertArrayEquals(firstBytes, ByteArrayOutputStream().also { store.export(first.id, it) }.toByteArray())
    }

    @Test fun incompleteContextRemainsUnassignedAndDuplicateTurnNumbersKeepInputOrder() {
        val invalidIndex = row("bad-index", "App甲", "会话", 1, "保留此提示").toMutableMap().apply {
            put("context_observation", buildJsonObject { put("conversation_id", "会话"); put("turn_index", "2") })
        }
        val nonObject = row("bad-context", "App甲", null, null, null).toMutableMap().apply {
            put("context_observation", JsonPrimitive("无法分组的原标注"))
        }
        val document = ResearchRecordStore.decode(source(listOf(
            JsonObject(invalidIndex), row("missing-turn", "App甲", "会话", null, "保留未编号提示"),
            JsonObject(nonObject), row("missing-id", "App甲", null, 1, "保留未归组提示"),
            row("same-number-a", "App甲", "有效会话", 1, "先输入"),
            row("same-number-b", "App甲", "有效会话", 1, "后输入"),
        )))
        val groups = document.conversationsForApp(null)
        assertEquals(listOf("bad-index", "missing-turn", "bad-context", "missing-id"), groups.first().turns.map { it.attempt.attemptId })
        assertEquals("未分会话", groups.first().label)
        assertTrue(groups.first().turns.all { it.turnIndex == null })
        assertEquals(listOf("先输入", "后输入"), groups.last().turns.map { it.prompt })
        assertEquals(6, groups.turnsForSelection(null).size)
        assertEquals("保留此提示", groups.first().turns.first().prompt)
    }

    private fun analysisBytes(document: ResearchDocument, status: String): ByteArray = buildJsonObject {
        put("source_sha256", document.id); put("record_kind", "SAMPLE"); put("input_revision", "alignment-1")
        put("counts", buildJsonObject {}); put("groups", buildJsonArray {})
        put("records", buildJsonArray { document.records.forEach { attempt -> add(buildJsonObject {
            put("attempt_id", attempt.attemptId); put("input_record", attempt.raw)
            put("ttfc", buildJsonObject { put("status", status) })
        }) } })
    }.toString().toByteArray()

    private fun source(rows: List<JsonObject>): ByteArray = buildJsonObject {
        put("record_kind", "SAMPLE")
        put("records", JsonArray(rows))
    }.toString().toByteArray()

    private fun row(id: String, app: String, conversation: String?, turn: Int?, prompt: String?, dependency: String? = null): JsonObject = buildJsonObject {
        put("record_kind", "SAMPLE"); put("method_id", "alignment-1"); put("attempt_id", id)
        put("app", buildJsonObject { put("name", app) })
        put("outcome", buildJsonObject { put("status", "incomplete"); put("visible_completion", "uncertain") })
        if (prompt != null) put("action_text", prompt)
        if (conversation != null || turn != null) put("context_observation", buildJsonObject {
            if (conversation != null) put("conversation_id", conversation)
            if (turn != null) put("turn_index", turn)
            put("depends_on_attempt_id", dependency?.let(::JsonPrimitive) ?: JsonNull)
            put("visible_actions_and_order_retained", JsonNull)
            put("eligible_context_denominator", false)
        })
    }
}
