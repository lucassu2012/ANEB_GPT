package com.aneb.probe.research

import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManualResearchDraftTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun framesWithoutMappingRemainUnknownAndInvalidEditCanBeCorrectedWithoutLosingTheDraft() {
        val attempt = ManualResearchAttempt(executed = true, status = "cancelled",
            visibleCompletion = "uncertain", instructionFollowing = "no", reason = "TEST ONLY：已取消，无可靠帧时间映射",
            events = mapOf("first_content" to ManualResearchRange(frameLow = "12", frameHigh = "14")))
        val draft = ManualResearchDraft(recordKind = "SAMPLE", appName = "TEST ONLY",
            attempts = listOf(attempt) + List(2) { ManualResearchAttempt(executed = false, reason = "TEST ONLY 未执行") })
        val row = ResearchRecordStore.decode(draft.toRecordBytes()).records.first().raw
        val event = row["events"]!!.jsonObject["first_content"]!!.jsonObject
        assertEquals(JsonNull, event["time_ms"])
        assertEquals(Json.parseToJsonElement("[12,14]"), event["frame_range"])
        assertEquals(JsonPrimitive("cancelled"), row["outcome"]!!.jsonObject["status"])
        assertEquals(JsonNull, row["outcome"]!!.jsonObject["completion_basis"])
        val invalid = draft.copy(attempts = listOf(attempt.copy(events = mapOf("first_content" to ManualResearchRange("1", "")))) + draft.attempts.drop(1))
        val restored = ManualResearchDraft.fromDraftState(invalid.toDraftState())
        assertThrows(ResearchImportException::class.java) { restored.toRecordBytes() }
        assertEquals("1", restored.attempts.first().events.getValue("first_content").low)
        assertArrayEquals(draft.toRecordBytes(), restored.copy(attempts = draft.attempts).toRecordBytes())
        assertThrows(ResearchImportException::class.java) { draft.copy(recordKind = "").toRecordBytes() }
        assertThrows(ResearchImportException::class.java) { draft.copy(recordKind = "OBSERVED").toRecordBytes() }
        assertThrows(ResearchImportException::class.java) { draft.copy(attempts = listOf(attempt.copy(executed = null)) + draft.attempts.drop(1)).toRecordBytes() }
    }

    @Test fun manualSampleNotRunBatchSavesReopensAndExportsWithoutInventedTimes() {
        val draft = ManualResearchDraft(
            recordKind = "SAMPLE", appName = "TEST ONLY App", appVersion = "", modelMode = "",
            attempts = List(3) { ManualResearchAttempt(executed = false, reason = "TEST ONLY：尚未执行") },
        )
        val bytes = draft.toRecordBytes()
        val store = ResearchRecordStore(temporary.newFolder())
        val saved = store.save(bytes)
        val reopened = store.open(saved.id)
        assertEquals(3, reopened.records.size)
        assertEquals(3, reopened.records.map { it.attemptId }.toSet().size)
        assertEquals("SAMPLE", reopened.recordKind)
        val sample = Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/research/r1-sample.json")).use { it.readBytes().toString(Charsets.UTF_8) }).jsonObject
        assertEquals(sample["method_id"], reopened.root["method_id"])
        assertEquals(sample["action_text"], reopened.root["action_text"])
        reopened.records.forEach {
            assertEquals("未执行", it.statusLabel)
            assertEquals(JsonPrimitive(false), it.raw["outcome"]!!.jsonObject["executed"])
            assertEquals(JsonNull, it.raw["app"]!!.jsonObject["version"])
            assertEquals(JsonNull, it.raw["app"]!!.jsonObject["model_mode"])
            assertTrue(it.raw["events"]!!.jsonObject.values.all { value -> value == JsonNull })
            assertTrue(it.raw["missing_reasons"]!!.jsonObject.toString().contains("尚未执行"))
            assertEquals(JsonNull, it.raw["evidence"]!!.jsonObject["sha256"])
        }
        assertArrayEquals(bytes, ByteArrayOutputStream().also { store.export(saved.id, it) }.toByteArray())
    }

    @Test fun manualObservedFailureKeepsMissingSendAndEnteredIntervalsThroughDraftRestoreAndSave() {
        val draft = ManualResearchDraft(
            recordKind = "OBSERVED", sourceConfirmed = true, appName = "TEST ONLY fabricated App",
            attempts = List(3) { index ->
                if (index == 0) ManualResearchAttempt(
                    executed = true, status = "incomplete", visibleCompletion = "uncertain", instructionFollowing = "uncertain",
                    reason = "TEST ONLY：未观察发送锚，观察后来截断", evidenceReference = "TEST ONLY local observation note", evidenceExists = false,
                    clockSource = "TEST ONLY video timeline", videoId = "TEST-VIDEO", clockDomain = "TEST-DOMAIN", clockMapping = "TEST ONLY recorded mapping",
                    events = mapOf("first_content" to ManualResearchRange(low = "0", high = "40"), "observation_end" to ManualResearchRange(low = "2000", high = "2040")),
                    intervals = listOf(ManualResearchInterval(kind = "unclosed_tail", start = ManualResearchRange("1000", "1040"), end = ManualResearchRange("2000", "2040"), resumed = false, fullyVisible = true)),
                ) else ManualResearchAttempt(executed = false, reason = "TEST ONLY：现场未执行事实", evidenceReference = "TEST ONLY note $index")
            },
        )
        val restored = ManualResearchDraft.fromDraftState(draft.toDraftState())
        assertEquals(draft, restored)
        val bytes = restored.toRecordBytes()
        val store = ResearchRecordStore(temporary.newFolder())
        val saved = store.save(bytes)
        val row = store.open(saved.id).records.first().raw
        assertEquals(JsonPrimitive(true), row["outcome"]!!.jsonObject["executed"])
        assertEquals(JsonNull, row["events"]!!.jsonObject["send"])
        assertEquals(Json.parseToJsonElement("[0,40]"), row["events"]!!.jsonObject["first_content"]!!.jsonObject["time_ms"])
        assertEquals(JsonPrimitive("TEST-DOMAIN"), row["events"]!!.jsonObject["first_content"]!!.jsonObject["clock_domain_id"])
        assertEquals("unclosed_tail", row["observed_intervals"]!!.jsonArray[0].jsonObject.text("kind"))
        assertEquals(JsonNull, row["condition"])
        assertFalse(row.containsKey("ttfr"))
        assertArrayEquals(bytes, ByteArrayOutputStream().also { store.export(saved.id, it) }.toByteArray())
    }
}
