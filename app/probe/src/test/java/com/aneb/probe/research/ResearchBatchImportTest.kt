package com.aneb.probe.research

import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResearchBatchImportTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun reversedRawAndAnalysisSelectionPreviewsBySourceHashAndSavesOnlyAfterConfirmation() {
        val rawBytes = resource("r1-sample.json")
        val analysisBytes = resource("r1-analysis-sample.json")
        val source = ResearchRecordStore.decode(rawBytes)
        val rawStore = ResearchRecordStore(temporary.newFolder("raw"))
        val analysisStore = ResearchAnalysisStore(temporary.newFolder("analysis"))

        val preview = ResearchBatchImport.preview(
            files = listOf(
                ResearchBatchImportFile("analysis.json", analysisBytes),
                ResearchBatchImportFile("raw.json", rawBytes),
            ),
            records = rawStore,
            analyses = analysisStore,
        )

        assertEquals(listOf(ResearchBatchImportItemStatus.READY, ResearchBatchImportItemStatus.READY), preview.items.map { it.status })
        assertEquals(1, preview.pairs.size)
        assertEquals(source.id, preview.pairs.single().sourceId)
        assertEquals("raw.json", preview.pairs.single().rawFileName)
        assertEquals(listOf("analysis.json"), preview.pairs.single().analysisFileNames)
        assertTrue("Preview must not write either store", rawStore.list().isEmpty())
        assertTrue("Preview must not write either store", analysisStore.list(source).isEmpty())

        val receipt = preview.confirmSave()

        assertEquals(2, receipt.items.size)
        assertTrue(receipt.items.all { it.status == ResearchBatchImportSaveStatus.SAVED })
        val reopenedSource = rawStore.open(source.id)
        val reopenedAnalysis = analysisStore.open(reopenedSource, receipt.items.single { it.fileName == "analysis.json" }.analysisId!!)
        assertEquals(source.id, reopenedAnalysis.sourceId)
        assertEquals(source.records.map { it.attemptId }.toSet(), reopenedAnalysis.records.keys)
        assertArrayEquals(rawBytes, ByteArrayOutputStream().also { rawStore.export(source.id, it) }.toByteArray())
        assertArrayEquals(
            analysisBytes,
            ByteArrayOutputStream().also { analysisStore.export(reopenedSource, reopenedAnalysis.id, it) }.toByteArray(),
        )
    }

    @Test fun multipleSourcesAndAnalysisCopiesPairByHashIndependentOfSelectionOrder() {
        val rawOneBytes = resource("r1-sample.json")
        val rawTwoBytes = byteArrayOf(10) + rawOneBytes
        val rawOne = ResearchRecordStore.decode(rawOneBytes)
        val rawTwo = ResearchRecordStore.decode(rawTwoBytes)
        assertNotEquals(rawOne.id, rawTwo.id)
        val analysisOneBytes = analysisFor(rawOne)
        val analysisTwoBytes = analysisFor(rawTwo)
        val analysisTwoAlternateBytes = byteArrayOf(10) + analysisTwoBytes
        val rawStore = ResearchRecordStore(temporary.newFolder("multi-raw"))
        val analysisStore = ResearchAnalysisStore(temporary.newFolder("multi-analysis"))

        val preview = ResearchBatchImport.preview(
            files = listOf(
                ResearchBatchImportFile("two-b.json", analysisTwoBytes),
                ResearchBatchImportFile("one-raw.json", rawOneBytes),
                ResearchBatchImportFile("two-a.json", analysisTwoAlternateBytes),
                ResearchBatchImportFile("one-analysis.json", analysisOneBytes),
                ResearchBatchImportFile("two-raw.json", rawTwoBytes),
            ),
            records = rawStore,
            analyses = analysisStore,
        )

        assertTrue(preview.items.all { it.status == ResearchBatchImportItemStatus.READY })
        assertEquals(2, preview.pairs.size)
        assertEquals(rawOne.id, preview.pairs[0].sourceId)
        assertEquals("one-raw.json", preview.pairs[0].rawFileName)
        assertEquals(listOf("one-analysis.json"), preview.pairs[0].analysisFileNames)
        assertEquals(rawTwo.id, preview.pairs[1].sourceId)
        assertEquals("two-raw.json", preview.pairs[1].rawFileName)
        assertEquals(listOf("two-b.json", "two-a.json"), preview.pairs[1].analysisFileNames)
        assertTrue(rawStore.list().isEmpty())

        val receipt = preview.confirmSave()
        assertEquals(5, receipt.savedCount)
        val reopenedOne = rawStore.open(rawOne.id)
        val reopenedTwo = rawStore.open(rawTwo.id)
        assertEquals(1, analysisStore.list(reopenedOne).size)
        assertEquals(2, analysisStore.list(reopenedTwo).size)
        assertArrayEquals(rawOneBytes, ByteArrayOutputStream().also { rawStore.export(rawOne.id, it) }.toByteArray())
        assertArrayEquals(rawTwoBytes, ByteArrayOutputStream().also { rawStore.export(rawTwo.id, it) }.toByteArray())
    }

    @Test fun invalidAndUnmatchedFilesAreListedWhileValidRawCanStillBeSaved() {
        val rawBytes = resource("r1-sample.json")
        val raw = ResearchRecordStore.decode(rawBytes)
        val rawStore = ResearchRecordStore(temporary.newFolder("valid-with-invalid"))
        val analysisStore = ResearchAnalysisStore(temporary.newFolder("valid-with-unmatched"))
        val missingSourceRoot = Json.parseToJsonElement(analysisFor(raw).toString(Charsets.UTF_8)).jsonObject
        val missingSourceAnalysis = JsonObject(missingSourceRoot.toMutableMap().apply { remove("source_sha256") })
            .toString().toByteArray(Charsets.UTF_8)
        val preview = ResearchBatchImport.preview(
            files = listOf(
                ResearchBatchImportFile("broken.json", "not json".toByteArray()),
                ResearchBatchImportFile("orphan-analysis.json", analysisForSourceHash("0".repeat(64))),
                ResearchBatchImportFile("missing-source-analysis.json", missingSourceAnalysis),
                ResearchBatchImportFile("valid-raw.json", rawBytes),
            ),
            records = rawStore,
            analyses = analysisStore,
        )

        assertEquals(
            listOf(
                ResearchBatchImportItemStatus.INVALID,
                ResearchBatchImportItemStatus.UNMATCHED_SOURCE,
                ResearchBatchImportItemStatus.INVALID,
                ResearchBatchImportItemStatus.READY,
            ),
            preview.items.map { it.status },
        )
        assertEquals(ResearchBatchImportItemKind.ANALYSIS, preview.items[2].kind)
        val receipt = preview.confirmSave()
        assertEquals(1, receipt.savedCount)
        assertEquals(ResearchBatchImportSaveStatus.NOT_SAVED, receipt.items[0].status)
        assertEquals(ResearchBatchImportSaveStatus.NOT_SAVED, receipt.items[1].status)
        assertEquals(ResearchBatchImportSaveStatus.NOT_SAVED, receipt.items[2].status)
        assertEquals(ResearchBatchImportSaveStatus.SAVED, receipt.items[3].status)
        assertEquals(raw.id, rawStore.open(raw.id).id)
        assertTrue(analysisStore.list(raw).isEmpty())
    }

    @Test fun duplicateSelectedRawIsReportedAndNotSilentlySavedOnce() {
        val rawBytes = resource("r1-sample.json")
        val raw = ResearchRecordStore.decode(rawBytes)
        val rawStore = ResearchRecordStore(temporary.newFolder("duplicate-raw"))
        val analysisStore = ResearchAnalysisStore(temporary.newFolder("duplicate-analysis"))
        val preview = ResearchBatchImport.preview(
            listOf(
                ResearchBatchImportFile("first.json", rawBytes),
                ResearchBatchImportFile("second.json", rawBytes),
            ),
            rawStore,
            analysisStore,
        )

        assertEquals(listOf(ResearchBatchImportItemStatus.DUPLICATE, ResearchBatchImportItemStatus.DUPLICATE), preview.items.map { it.status })
        assertTrue(preview.pairs.isEmpty())
        assertEquals(0, preview.confirmSave().savedCount)
        assertTrue(rawStore.list().isEmpty())
        assertTrue(analysisStore.list(raw).isEmpty())
    }

    @Test fun deepJsonIsRejectedByTheExistingImportDepthBudgetBeforePreviewParsing() {
        val deepJson = """{"record_kind":"SAMPLE","records":${"[".repeat(65)}0${"]".repeat(65)}}""".toByteArray()
        val rawStore = ResearchRecordStore(temporary.newFolder("deep-raw"))
        val analysisStore = ResearchAnalysisStore(temporary.newFolder("deep-analysis"))

        val preview = ResearchBatchImport.preview(
            listOf(ResearchBatchImportFile("deep.json", deepJson)), rawStore, analysisStore,
        )

        assertEquals(ResearchBatchImportItemStatus.INVALID, preview.items.single().status)
        assertTrue(preview.items.single().message!!.contains("64 层"))
        assertEquals(0, preview.confirmSave().savedCount)
        assertTrue(rawStore.list().isEmpty())
    }

    @Test fun duplicateAndAttemptMismatchedAnalysisFilesAreVisibleAndNeverSaved() {
        val rawOneBytes = resource("r1-sample.json")
        val rawTwoBytes = byteArrayOf(10) + rawOneBytes
        val rawOne = ResearchRecordStore.decode(rawOneBytes)
        val rawTwo = ResearchRecordStore.decode(rawTwoBytes)
        val exactAnalysis = analysisFor(rawOne)
        val mismatchedRoot = Json.parseToJsonElement(analysisFor(rawTwo).toString(Charsets.UTF_8)).jsonObject
        val mismatchedRows = (mismatchedRoot["records"] as JsonArray).mapIndexed { index, element ->
            if (index != 0) element else JsonObject(element.jsonObject.toMutableMap().apply {
                put("attempt_id", JsonPrimitive("SAMPLE-UNKNOWN-ATTEMPT"))
            })
        }
        val mismatchedAnalysis = JsonObject(mismatchedRoot.toMutableMap().apply { put("records", JsonArray(mismatchedRows)) })
            .toString().toByteArray(Charsets.UTF_8)
        val rawStore = ResearchRecordStore(temporary.newFolder("analysis-errors-raw"))
        val analysisStore = ResearchAnalysisStore(temporary.newFolder("analysis-errors-store"))

        val preview = ResearchBatchImport.preview(
            listOf(
                ResearchBatchImportFile("one-raw.json", rawOneBytes),
                ResearchBatchImportFile("same-analysis-a.json", exactAnalysis),
                ResearchBatchImportFile("same-analysis-b.json", exactAnalysis),
                ResearchBatchImportFile("two-raw.json", rawTwoBytes),
                ResearchBatchImportFile("wrong-attempt.json", mismatchedAnalysis),
            ),
            rawStore,
            analysisStore,
        )

        assertEquals(
            listOf(
                ResearchBatchImportItemStatus.READY,
                ResearchBatchImportItemStatus.DUPLICATE,
                ResearchBatchImportItemStatus.DUPLICATE,
                ResearchBatchImportItemStatus.READY,
                ResearchBatchImportItemStatus.INVALID,
            ),
            preview.items.map { it.status },
        )
        assertTrue(preview.pairs.all { it.analysisFileNames.isEmpty() })
        val receipt = preview.confirmSave()
        assertEquals(2, receipt.savedCount)
        assertTrue(analysisStore.list(rawStore.open(rawOne.id)).isEmpty())
        assertTrue(analysisStore.list(rawStore.open(rawTwo.id)).isEmpty())
    }

    @Test fun batchCountAndByteLimitsFailClosedWithoutStoreWrites() {
        val rawStore = ResearchRecordStore(temporary.newFolder("limit-raw"))
        val analysisStore = ResearchAnalysisStore(temporary.newFolder("limit-analysis"))
        val tooMany = (0..ResearchBatchImport.MAX_FILES).map { ResearchBatchImportFile("$it.json", byteArrayOf()) }
        val countPreview = ResearchBatchImport.preview(tooMany, rawStore, analysisStore)
        assertTrue(countPreview.items.all { it.status == ResearchBatchImportItemStatus.INVALID })
        assertTrue(countPreview.items.first().message!!.contains("20 个文件"))
        assertEquals(0, countPreview.confirmSave().savedCount)

        val tooLarge = (1..11).map { ResearchBatchImportFile("$it.json", ByteArray(ResearchRecordStore.MAX_BYTES)) }
        val bytePreview = ResearchBatchImport.preview(tooLarge, rawStore, analysisStore)
        assertTrue(bytePreview.items.all { it.status == ResearchBatchImportItemStatus.INVALID })
        assertTrue(bytePreview.items.first().message!!.contains("10 MiB"))
        assertEquals(0, bytePreview.confirmSave().savedCount)
        assertTrue(rawStore.list().isEmpty())
    }

    @Test fun failedItemDoesNotHideOtherSavedItemsOrClaimItsDependentAnalysisWasSaved() {
        val rawOneBytes = resource("r1-sample.json")
        val rawTwoBytes = byteArrayOf(10) + rawOneBytes
        val rawOne = ResearchRecordStore.decode(rawOneBytes)
        val rawTwo = ResearchRecordStore.decode(rawTwoBytes)
        val rawDirectory = temporary.newFolder("partial-raw")
        // A damaged same-hash target simulates one Store item failing without poisoning its sibling.
        File(rawDirectory, "${rawOne.id}.json").writeText("damaged")
        val rawStore = ResearchRecordStore(rawDirectory)
        val analysisStore = ResearchAnalysisStore(temporary.newFolder("partial-analysis"))
        val preview = ResearchBatchImport.preview(
            listOf(
                ResearchBatchImportFile("one-raw.json", rawOneBytes),
                ResearchBatchImportFile("one-analysis.json", analysisFor(rawOne)),
                ResearchBatchImportFile("two-raw.json", rawTwoBytes),
                ResearchBatchImportFile("two-analysis.json", analysisFor(rawTwo)),
            ),
            rawStore,
            analysisStore,
        )
        assertTrue(preview.items.all { it.status == ResearchBatchImportItemStatus.READY })

        val receipt = preview.confirmSave()

        assertEquals(
            listOf(
                ResearchBatchImportSaveStatus.NOT_SAVED,
                ResearchBatchImportSaveStatus.NOT_SAVED,
                ResearchBatchImportSaveStatus.SAVED,
                ResearchBatchImportSaveStatus.SAVED,
            ),
            receipt.items.map { it.status },
        )
        assertTrue(receipt.items[0].message!!.contains("未保存"))
        assertTrue(receipt.items[1].message!!.contains("原文未保存"))
        val reopenedTwo = rawStore.open(rawTwo.id)
        assertEquals(1, analysisStore.list(reopenedTwo).size)
        assertFalse(analysisStore.list(rawOne).any { it.id == ResearchAnalysisStore.decode(rawOne, analysisFor(rawOne)).id })
    }

    // Repository pair fixtures bind a Git/LF source SHA; imports themselves remain byte-exact.
    private fun resource(name: String): ByteArray = checkNotNull(javaClass.getResourceAsStream("/research/$name")).use {
        it.readBytes().toString(Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
    }

    private fun analysisFor(source: ResearchDocument): ByteArray = analysisForSourceHash(source.id)

    private fun analysisForSourceHash(sourceId: String): ByteArray {
        val original = Json.parseToJsonElement(resource("r1-analysis-sample.json").toString(Charsets.UTF_8)).jsonObject
        return JsonObject(original.toMutableMap().apply { put("source_sha256", kotlinx.serialization.json.JsonPrimitive(sourceId)) })
            .toString().toByteArray(Charsets.UTF_8)
    }
}
