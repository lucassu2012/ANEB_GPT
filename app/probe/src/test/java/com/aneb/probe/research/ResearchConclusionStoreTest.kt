package com.aneb.probe.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ResearchConclusionStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun aCardBoundToASavedSingleAppAnalysisCanBeReadAfterReopening() {
        // The committed SAMPLE analysis binds the LF Git bytes, while Windows checkout may use CRLF.
        val sourceBytes = resource("r1-sample.json").toString(Charsets.UTF_8)
            .replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
        val source = ResearchRecordStore(temporary.newFolder("sources")).save(sourceBytes)
        val analysis = ResearchAnalysisStore(temporary.newFolder("analyses"))
            .save(source, resource("r1-analysis-sample.json"))
        val cardBytes = "# Kimi 样例人工结论\n分析 SHA-256：${analysis.id}\n未知指标仍为 UNKNOWN。\n"
            .toByteArray(Charsets.UTF_8)
        val directory = temporary.newFolder("cards")

        val saved = ResearchConclusionStore(directory).save(source, analysis, cardBytes)
        val reopened = ResearchConclusionStore(directory).open(source, analysis, saved.id)

        assertEquals(source.id, reopened.sourceId)
        assertEquals(analysis.id, reopened.analysisId)
        assertEquals("Kimi", reopened.appName)
        assertEquals(cardBytes.toString(Charsets.UTF_8), reopened.text)
        assertTrue(reopened.id.matches(Regex("[0-9a-f]{64}")))
        assertEquals(saved.id, ResearchConclusionStore(directory).list(source, analysis).single().id)
    }

    @Test fun selectingAnotherAnalysisDoesNotShowThePreviousAnalysisCard() {
        val sourceBytes = resource("r1-sample.json").toString(Charsets.UTF_8)
            .replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
        val source = ResearchRecordStore(temporary.newFolder("sources")).save(sourceBytes)
        val analyses = ResearchAnalysisStore(temporary.newFolder("analyses"))
        val first = analyses.save(source, resource("r1-analysis-sample.json"))
        val second = analyses.save(source, byteArrayOf('\n'.code.toByte()) + resource("r1-analysis-sample.json"))
        val directory = temporary.newFolder("cards")
        val saved = ResearchConclusionStore(directory).save(
            source, first, "人工结论\n分析 SHA-256：${first.id}\n".toByteArray(Charsets.UTF_8),
        )

        assertEquals(listOf(saved.id), ResearchConclusionStore(directory).list(source, first).map { it.id })
        assertTrue(ResearchConclusionStore(directory).list(source, second).isEmpty())
    }

    @Test fun previewValidatesBindingWithoutSaving() {
        val sourceBytes = resource("r1-sample.json").toString(Charsets.UTF_8)
            .replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
        val source = ResearchRecordStore(temporary.newFolder("sources")).save(sourceBytes)
        val analysis = ResearchAnalysisStore(temporary.newFolder("analyses"))
            .save(source, resource("r1-analysis-sample.json"))
        val store = ResearchConclusionStore(temporary.newFolder("cards"))
        val bytes = "人工结论\n分析 SHA-256：${analysis.id}\n".toByteArray(Charsets.UTF_8)

        val preview = store.preview(source, analysis, bytes)

        assertEquals(source.id, preview.sourceId)
        assertEquals(analysis.id, preview.analysisId)
        assertTrue(store.list(source, analysis).isEmpty())
    }

    @Test fun onlyASavedSingleAppTextPairCanOfferConclusionAttachment() {
        val sourceBytes = resource("r1-sample.json").toString(Charsets.UTF_8)
            .replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
        val source = ResearchRecordStore(temporary.newFolder("sources")).save(sourceBytes)
        val analysis = ResearchAnalysisStore(temporary.newFolder("analyses"))
            .save(source, resource("r1-analysis-sample.json"))
        val store = ResearchConclusionStore(temporary.newFolder("cards"))
        val secondRecord = source.records[1]
        val mixedRecord = secondRecord.copy(raw = JsonObject(secondRecord.raw + (
            "app" to JsonObject(mapOf("name" to JsonPrimitive("Another App")))
        )))
        val mixedSource = source.copy(records = source.records.toMutableList().also { it[1] = mixedRecord })

        assertTrue(store.canAttach(source, analysis))
        assertFalse(store.canAttach(source, analysis.copy(sourceId = "0".repeat(64))))
        assertFalse(store.canAttach(source, analysis.copy(isVideo = true)))
        assertFalse(store.canAttach(mixedSource, analysis))
        assertTrue(store.list(source.copy(id = "0".repeat(64)), analysis).isEmpty())
    }

    @Test fun incorrectBindingOrUnreadableCardBytesAreNotSaved() {
        val sourceBytes = resource("r1-sample.json").toString(Charsets.UTF_8)
            .replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
        val source = ResearchRecordStore(temporary.newFolder("sources")).save(sourceBytes)
        val analysis = ResearchAnalysisStore(temporary.newFolder("analyses"))
            .save(source, resource("r1-analysis-sample.json"))
        val directory = temporary.newFolder("cards")
        val store = ResearchConclusionStore(directory)

        assertThrows(IllegalArgumentException::class.java) {
            store.save(source, analysis, "人工结论：${"0".repeat(64)}".toByteArray(Charsets.UTF_8))
        }
        assertThrows(Exception::class.java) {
            store.save(source, analysis, byteArrayOf(0xc3.toByte(), 0x28))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.save(source, analysis, ByteArray(1024 * 1024 + 1))
        }
        assertTrue(store.list(source, analysis).isEmpty())
    }

    @Test fun damagedSavedCardIsReportedWithoutBeingDisplayedOrOverwritten() {
        val sourceBytes = resource("r1-sample.json").toString(Charsets.UTF_8)
            .replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
        val source = ResearchRecordStore(temporary.newFolder("sources")).save(sourceBytes)
        val analysis = ResearchAnalysisStore(temporary.newFolder("analyses"))
            .save(source, resource("r1-analysis-sample.json"))
        val directory = temporary.newFolder("cards")
        val store = ResearchConclusionStore(directory)
        val bytes = "人工结论\n分析 SHA-256：${analysis.id}\n".toByteArray(Charsets.UTF_8)
        val saved = store.save(source, analysis, bytes)
        val target = java.io.File(java.io.File(java.io.File(directory, source.id), analysis.id), "${saved.id}.md")
        target.writeText("damaged", Charsets.UTF_8)

        assertThrows(IllegalArgumentException::class.java) { store.open(source, analysis, saved.id) }
        assertThrows(IllegalStateException::class.java) { store.save(source, analysis, bytes) }
        val listed = store.list(source, analysis).single()
        assertEquals(saved.id, listed.id)
        // Listing is metadata-only; reopening the selected card performs full validation.
        assertTrue(listed.error == null)
        assertEquals("damaged", target.readText(Charsets.UTF_8))
    }

    private fun resource(name: String): ByteArray = checkNotNull(javaClass.getResourceAsStream("/research/$name")).use {
        it.readBytes()
    }
}
