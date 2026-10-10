package com.aneb.probe.ui.research

import com.aneb.probe.research.PrivateSdkObservationStore
import com.aneb.probe.research.ResearchRecordStore
import com.aneb.probe.research.privateSdkTestInput
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Host production-state tests, not SAF/Compose/device/new-process acceptance. */
class PrivateSdkObservationReaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun historicalVisibleObservationPreviewsOriginalJsonWithoutSavingOrOpeningDeclaredPaths() {
        val directory = temporary.newFolder()
        val bytes = visibleSdkTestInput()
        val reader = PrivateSdkObservationReaderController(PrivateSdkObservationStore(directory))
        val preview = reader.preview { ByteArrayInputStream(bytes) }
        assertNotNull("historical visible SDK input must be readable", preview.document)
        assertArrayEquals(bytes, preview.document!!.originalBytes())
        assertFalse(preview.saved)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun historicalGetterAndCaptureTimeRowsKeepTheirOriginalTypesAndUnknownBoundaries() {
        val bytes = visibleSdkTestInput()
        val document = PrivateSdkObservationStore(temporary.newFolder()).preview(bytes)
        val rows = PrivateSdkObservationPresentation.fields(document.root).associateBy { it.path }
        assertEquals("JSON string", rows["sdk.raw_getter_objects.deep_label.checked"]?.type)
        assertEquals("\"false\"", rows["sdk.raw_getter_objects.deep_label.checked"]?.literal)
        assertEquals("\"0\"", rows["sdk.raw_fields.actions"]?.literal)
        assertEquals("\"UNPROVEN\"", rows["sdk.raw_fields.owner"]?.literal)
        assertEquals("true", rows["time_boundaries.non_atomic"]?.literal)
        assertEquals("JSON boolean", rows["time_boundaries.non_atomic"]?.type)
        assertEquals("\"UNKNOWN\"", rows["time_boundaries.phone_wall_clock_mapping"]?.literal)
        assertEquals("\"TEST_HISTORICAL_TIME\"", rows["visible_witness.capture_start_utc_ns"]?.literal)
        assertArrayEquals(bytes, document.originalBytes())
    }

    @Test fun selectedInputIsPreviewedThenExplicitlySavedAndReopenedFromTheLocalList() {
        val directory = temporary.newFolder()
        val bytes = privateSdkTestInput()
        val reader = PrivateSdkObservationReaderController(PrivateSdkObservationStore(directory))
        val preview = reader.preview { ByteArrayInputStream(bytes) }
        assertNotNull(preview.document)
        assertFalse(preview.saved)
        assertTrue(directory.listFiles()!!.isEmpty())
        val saved = reader.confirmSave()
        assertTrue(saved.saved)
        assertArrayEquals(bytes, saved.document!!.originalBytes())
        val freshReader = PrivateSdkObservationReaderController(PrivateSdkObservationStore(directory))
        val list = freshReader.showList()
        assertNull(list.document)
        assertEquals(saved.document.id, list.entries.single().id)
        val reopened = freshReader.open(list.entries.single().id)
        assertTrue(reopened.saved)
        assertEquals(saved.document.id, reopened.document!!.id)
        assertArrayEquals(bytes, reopened.document.originalBytes())
    }

    @Test fun displayedLiteralsKeepFalseStringFalseNullMissingAndUnknownDistinct() {
        val bytes = privateSdkTestInput().toString(Charsets.UTF_8)
            .replace("\"actions\":\"0\"", "\"actions\":\"0\",\"bool_false\":false,\"string_false\":\"false\",\"explicit_null\":null,\"unknown\":\"UNKNOWN\"")
            .toByteArray(Charsets.UTF_8)
        val document = PrivateSdkObservationStore(temporary.newFolder()).preview(bytes)
        val rows = PrivateSdkObservationPresentation.fields(document.root).associateBy { it.path }
        assertEquals("false", rows["literal_fields.bool_false"]?.literal)
        assertEquals("JSON boolean", rows["literal_fields.bool_false"]?.type)
        assertEquals("\"false\"", rows["literal_fields.string_false"]?.literal)
        assertEquals("JSON string", rows["literal_fields.string_false"]?.type)
        assertEquals("null", rows["literal_fields.explicit_null"]?.literal)
        assertEquals("MISSING（未提供）", rows["objects.deep_label.enabled"]?.literal)
        assertEquals("\"UNKNOWN\"", rows["literal_fields.unknown"]?.literal)
        assertEquals("\"UNPROVEN\"", rows["literal_fields.owner"]?.literal)
        assertEquals("\"NOT_OBSERVED\"", rows["limits.backend_mode"]?.literal)
        assertEquals("\"NOT_RUN\"", rows["limits.phone_import_export_reopen"]?.literal)
        assertArrayEquals(bytes, document.originalBytes())
    }

    @Test fun cancellingOrFailingANewSelectionNeverShowsAnOlderSavedSuccess() {
        val store = PrivateSdkObservationStore(temporary.newFolder())
        val reader = PrivateSdkObservationReaderController(store)
        val bytes = privateSdkTestInput()
        reader.preview { ByteArrayInputStream(bytes) }
        val id = reader.confirmSave().document!!.id
        val cancelled = reader.preview(null)
        assertNull(cancelled.document)
        assertFalse(cancelled.saved)
        assertTrue(cancelled.notice.contains("取消"))
        assertTrue(reader.open(id).saved)
        val failed = reader.preview { throw IOException("TEST_PRIVATE_PATH_MUST_NOT_LEAK") }
        assertNull(failed.document)
        assertFalse(failed.saved)
        assertFalse(failed.notice.contains("TEST_PRIVATE_PATH"))
        assertArrayEquals(bytes, store.open(id).originalBytes())
        assertEquals(1, store.list().size)
    }

    @Test fun aDamagedDiskCopyCannotReuseACachedSuccessOrBeOverwritten() {
        val directory = temporary.newFolder()
        val store = PrivateSdkObservationStore(directory)
        val reader = PrivateSdkObservationReaderController(store)
        val bytes = privateSdkTestInput()
        reader.preview { ByteArrayInputStream(bytes) }
        val id = reader.confirmSave().document!!.id
        assertTrue(reader.open(id).saved)
        val damaged = "TEST_ONLY_DAMAGED_BYTES".toByteArray()
        val target = File(directory, "$id.json")
        target.writeBytes(damaged)
        val failedOpen = reader.open(id)
        assertNull(failedOpen.document)
        assertFalse(failedOpen.saved)
        assertTrue(failedOpen.notice.contains("损坏"))
        assertNotNull(reader.showList().entries.single().error)
        reader.preview { ByteArrayInputStream(bytes) }
        val failedSave = reader.confirmSave()
        assertNull(failedSave.document)
        assertFalse(failedSave.saved)
        assertTrue(failedSave.notice.contains("保存失败"))
        assertArrayEquals(damaged, target.readBytes())
    }

    @Test fun invalidOrOversizedSelectionClosesTheStreamAndClearsThePreview() {
        val directory = temporary.newFolder()
        val reader = PrivateSdkObservationReaderController(PrivateSdkObservationStore(directory))
        val invalid = listOf(
            "{TEST_ONLY_INVALID_JSON".toByteArray(),
            byteArrayOf(0xC3.toByte(), 0x28),
            ("{\"nested\":" + "[".repeat(65) + "0" + "]".repeat(65) + "}").toByteArray(),
            ByteArray(ResearchRecordStore.MAX_BYTES + 1) { 32 },
        )
        invalid.forEach { bytes ->
            assertNotNull(reader.preview { ByteArrayInputStream(privateSdkTestInput()) }.document)
            var closed = false
            val input = object : ByteArrayInputStream(bytes) {
                override fun close() { closed = true; super.close() }
            }
            val failed = reader.preview { input }
            assertTrue(closed)
            assertNull(failed.document)
            assertFalse(failed.saved)
            assertTrue(failed.notice.contains("无法预览"))
            assertFalse(failed.notice.contains("TEST_ONLY_INVALID_JSON"))
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }
}

/** Anonymous historical input; no real observation, identifiers, paths, or pixels. */
internal fun visibleSdkTestInput(pngBytes: Int = 0, pngSha: String = "0".repeat(64)): ByteArray = """
    {
      "schema_version":1,"task":"TEST_ONLY","verdict":"PRIVATE_INPUT",
      "visible_witness":{"status":"INPUT_DECLARATION","original_source":{
        "path":"https://invalid.example/do-not-open.png","bytes":$pngBytes,"sha256":"$pngSha"},
        "capture_start_utc_ns":"TEST_HISTORICAL_TIME","actually_viewed":true},
      "sdk":{"raw_getter_objects":{
        "deep_label":{"checked":"false","checkable":"false","visible":"true"},
        "deep_parent":{},"search_label":{},"search_parent":{}},
        "raw_fields":{"owner":"UNPROVEN","action_target":"UNPROVEN","actions":"0","permit":"NONE"}},
      "time_boundaries":{"non_atomic":true,"host_and_android_domains_separate":true,
        "phone_wall_clock_mapping":"UNKNOWN"},
      "nonclaims":["semantic UNKNOWN","backend UNKNOWN"]
    }
""".trimIndent().toByteArray(Charsets.UTF_8)
