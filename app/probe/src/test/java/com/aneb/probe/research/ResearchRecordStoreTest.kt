package com.aneb.probe.research

import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResearchRecordStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun cancelledUnknownAndNotRunRemainSeparateAndRawBytesDefineBatchIdentity() {
        val root = Json.parseToJsonElement(observedTestInput().toString(Charsets.UTF_8)).jsonObject
        val rows = root["records"]!!.jsonArray.mapIndexed { index, element ->
            val row = element.jsonObject
            JsonObject(row.toMutableMap().apply {
                if (index < 2) put("outcome", JsonObject(row["outcome"]!!.jsonObject.toMutableMap().apply {
                    put("status", if (index == 0) JsonPrimitive("cancelled") else JsonNull)
                }))
            })
        }
        val bytes = JsonObject(root.toMutableMap().apply { put("records", JsonArray(rows)) }).toString().toByteArray()
        val store = ResearchRecordStore(temporary.newFolder())
        val first = store.save(bytes)
        val spacedBytes = byteArrayOf(10) + bytes
        val second = store.save(spacedBytes)
        assertNotEquals(first.id, second.id)
        assertEquals(first.records.map { it.attemptId }, second.records.map { it.attemptId })
        assertEquals(listOf("已取消", "未知：NA", "未执行"), store.open(first.id).records.map { it.statusLabel })
        assertEquals(2, store.list().size)
        val output = ByteArrayOutputStream()
        store.export(second.id, output)
        assertArrayEquals(spacedBytes, output.toByteArray())
    }

    @Test fun invalidBatchReportsItsProblemWithoutDroppingRowsOrChangingExistingFiles() {
        val root = Json.parseToJsonElement(observedTestInput().toString(Charsets.UTF_8)).jsonObject
        val rows = root["records"]!!.jsonArray
        fun changedSecond(key: String, value: JsonElement): ByteArray {
            val changed = rows.toMutableList().apply {
                this[1] = JsonObject(this[1].jsonObject.toMutableMap().apply { put(key, value) })
            }
            return JsonObject(root.toMutableMap().apply { put("records", JsonArray(changed)) }).toString().toByteArray()
        }
        val invalids = listOf(
            changedSecond("record_kind", JsonPrimitive("SAMPLE")) to "第 2 条",
            changedSecond("record_kind", JsonNull) to "第 2 条",
            changedSecond("attempt_id", rows[0].jsonObject["attempt_id"]!!) to "第 2 条与第 1 条",
            JsonObject(root.toMutableMap().apply { put("record_kind", JsonPrimitive("UNCONFIRMED")) }).toString().toByteArray() to "来源类型未确认",
        )
        val store = ResearchRecordStore(temporary.newFolder())
        val original = observedTestInput()
        val saved = store.save(original)
        invalids.forEach { (bytes, expected) ->
            val before = bytes.copyOf()
            val error = assertThrows(IllegalArgumentException::class.java) { store.save(bytes) }
            assertTrue("Import error should locate $expected: ${error.message}", error.message!!.contains(expected))
            assertArrayEquals(before, bytes)
            assertEquals(listOf(saved.id), store.list().map { it.id })
        }
        val output = ByteArrayOutputStream()
        store.export(saved.id, output)
        assertArrayEquals(original, output.toByteArray())
    }

    @Test fun observedPresentationWarnsAboutMissingSourceWithoutClaimingVerification() {
        val store = ResearchRecordStore(temporary.newFolder())
        val saved = store.save(observedTestInput())
        val document = store.open(saved.id)
        assertEquals("OBSERVED · 输入声明为实际观察（未核验）", document.sourceLabel)
        assertTrue(document.exportFileName.startsWith("ANEB-R1-OBSERVED-"))
        assertEquals(listOf("失败", "未完成", "未执行"), document.records.map { it.statusLabel })
        assertTrue(document.records.all { row -> row.sourceWarnings.any { it.contains("缺少本地来源引用") } })
        assertTrue(document.sourceNotice.contains("未打开媒体"))
        val withReference = document.records[0].raw.toMutableMap().apply {
            put("evidence", buildJsonObject {
                put("local_ref", "TEST-ONLY-NONEXISTENT/source-note.txt")
                put("sha256", JsonNull)
            })
        }
        val row = ResearchAttempt(JsonObject(withReference))
        assertFalse(row.sourceWarnings.any { it.contains("缺少本地来源引用") })
        assertTrue(row.sourceWarnings.any { it.contains("哈希未记录") })
        assertTrue(document.sourceNotice.contains("不表示成功或验收通过"))
        val sample = javaClass.getResourceAsStream("/research/r1-sample.json")!!.use { it.readBytes() }
        assertEquals("SAMPLE · 虚构样例（非实测）", ResearchRecordStore.decode(sample).sourceLabel)
    }

    @Test fun declaredObservedTestBatchPreservesFailuresUnknownsAndOriginalBytesAcrossReopen() {
        // TEST ONLY: this payload exercises the OBSERVED declaration; no observation took place.
        val bytes = observedTestInput()
        val directory = temporary.newFolder()
        val saved = ResearchRecordStore(directory).save(bytes)
        val reopened = ResearchRecordStore(directory).open(saved.id)
        assertEquals(JsonPrimitive("OBSERVED"), reopened.root["record_kind"])
        assertEquals(listOf("failed", "incomplete", "not_run"), reopened.records.map { it.status })
        assertEquals(JsonNull, reopened.records[0].raw["app"]!!.jsonObject["version"])
        assertEquals(JsonNull, reopened.records[2].raw["events"]!!.jsonObject["send"])
        val output = ByteArrayOutputStream()
        ResearchRecordStore(directory).export(saved.id, output)
        assertArrayEquals(bytes, output.toByteArray())
        assertEquals(saved.id, ResearchRecordStore(directory).save(bytes).id)
        assertEquals(1, ResearchRecordStore(directory).list().size)
    }

    @Test fun corruptedSavedFileIsVisibleAsAnErrorAndCannotBeExported() {
        val bytes = javaClass.getResourceAsStream("/research/r1-sample.json")!!.use { it.readBytes() }
        val directory = temporary.newFolder()
        val store = ResearchRecordStore(directory)
        val saved = store.save(bytes)
        // Simulate a filesystem failure outside the application's store API.
        java.io.File(directory, "${saved.id}.json").writeText("broken")
        val entry = store.list().single()
        assertEquals(saved.id, entry.id)
        assertNull(entry.document)
        assertNotNull(entry.error)
        val destination = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) { store.export(saved.id, destination) }
        assertEquals(0, destination.size())
    }

    @Test fun excessivelyNestedImportIsRejectedWithoutWriting() {
        val input = javaClass.getResourceAsStream("/research/r1-sample.json")!!.use { it.readBytes() }.toString(Charsets.UTF_8)
        val deep = input.replaceFirst("{", "{\"extra\":" + "[".repeat(80) + "0" + "]".repeat(80) + ",")
        val store = ResearchRecordStore(temporary.newFolder())
        assertThrows(IllegalArgumentException::class.java) { store.save(deep.toByteArray()) }
        assertTrue(store.list().isEmpty())
    }

    @Test fun invalidAndDuplicateAttemptsDoNotReplaceSavedDocument() {
        val bytes = javaClass.getResourceAsStream("/research/r1-sample.json")!!.use { it.readBytes() }
        val store = ResearchRecordStore(temporary.newFolder())
        val saved = store.save(bytes)
        val inputs = listOf(
            "{", "null",
            bytes.toString(Charsets.UTF_8).replace("SAMPLE-KIMI-R2", "SAMPLE-KIMI-R1"),
        )
        inputs.forEach { input -> assertThrows(IllegalArgumentException::class.java) { store.save(input.toByteArray()) } }
        assertThrows(IllegalArgumentException::class.java) { store.save(ByteArray(ResearchRecordStore.MAX_BYTES + 1)) }
        assertThrows(IllegalArgumentException::class.java) { store.save(byteArrayOf(0xff.toByte())) }
        assertEquals(saved.id, store.save(bytes).id)
        assertEquals(1, store.list().size)
        assertThrows(IllegalArgumentException::class.java) { store.open("../outside") }
        val exported = ByteArrayOutputStream()
        store.export(saved.id, exported)
        assertArrayEquals(bytes, exported.toByteArray())
    }

    @Test fun unsupportedKindCannotBeSavedAsRealResearch() {
        val original = javaClass.getResourceAsStream("/research/r1-sample.json")!!.use { it.readBytes() }
        val directory = temporary.newFolder()
        val store = ResearchRecordStore(directory)
        val unsupported = original.toString(Charsets.UTF_8).replace("\"SAMPLE\"", "\"UNCONFIRMED\"").toByteArray()
        assertThrows(IllegalArgumentException::class.java) { store.save(unsupported) }
        assertTrue(store.list().isEmpty())
    }

    @Test fun sampleCanBeSavedReopenedViewedAndExportedWithoutChangingEvidence() {
        val original = javaClass.getResourceAsStream("/research/r1-sample.json")!!.use { it.readBytes() }
        val directory = temporary.newFolder()
        val saved = ResearchRecordStore(directory).save(original)
        val reopened = ResearchRecordStore(directory).open(saved.id)
        assertEquals("SAMPLE", reopened.root["record_kind"]?.toString()?.trim('"'))
        assertEquals(3, reopened.records.size)
        assertEquals(listOf("completed", "incomplete", "not_run"), reopened.records.map { it.status })
        assertEquals(listOf("SAMPLE-KIMI-R1", "SAMPLE-KIMI-R2", "SAMPLE-KIMI-R3"), reopened.records.map { it.attemptId })
        val exported = ByteArrayOutputStream()
        ResearchRecordStore(directory).export(saved.id, exported)
        assertArrayEquals(original, exported.toByteArray())
        assertEquals(saved.id, ResearchRecordStore(directory).list().single().id)
    }

    private fun observedTestInput(): ByteArray {
        val sample = javaClass.getResourceAsStream("/research/r1-sample.json")!!.use { it.readBytes() }
        val root = Json.parseToJsonElement(sample.toString(Charsets.UTF_8)).jsonObject
        val records = root["records"]!!.jsonArray.mapIndexed { index, element ->
            val original = element.jsonObject
            JsonObject(original.toMutableMap().apply {
                put("record_kind", JsonPrimitive("OBSERVED"))
                put("attempt_id", JsonPrimitive("TEST-ONLY-OBSERVED-${index + 1}"))
                put("test_fixture", JsonPrimitive("TEST ONLY; no actual observation or media"))
                if (index == 0) put("outcome", JsonObject(original["outcome"]!!.jsonObject.toMutableMap().apply {
                    put("status", JsonPrimitive("failed"))
                    put("visible_completion", JsonPrimitive("no"))
                    put("instruction_following", JsonPrimitive("uncertain"))
                    put("completion_basis", JsonNull)
                }))
            })
        }
        return JsonObject(root.toMutableMap().apply {
            put("record_kind", JsonPrimitive("OBSERVED"))
            put("test_fixture", JsonPrimitive("TEST ONLY; not real research evidence"))
            put("records", JsonArray(records))
        }).toString().toByteArray(Charsets.UTF_8)
    }
}
