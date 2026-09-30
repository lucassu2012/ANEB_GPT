package com.aneb.probe.research

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResearchConclusionIndexTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun observedSingleAppCardIsLocatedAndReopenedByItsExactThreeHashes() {
        val sources = ResearchRecordStore(temporary.newFolder("sources"))
        val analyses = ResearchAnalysisStore(temporary.newFolder("analyses"))
        val cards = ResearchConclusionStore(temporary.newFolder("cards"))
        val source = sources.save(sourceBytes())
        val analysis = analyses.save(source, analysisBytes(source))
        val card = cards.save(source, analysis, "人工观察；分析 SHA-256：${analysis.id}".toByteArray())
        val index = ResearchConclusionIndex(sources, analyses, cards)
        val target = ResearchConclusionTarget(source.id, analysis.id, card.id)

        assertEquals(listOf(target), index.list(source.id))
        val opened = index.open(target)
        assertEquals(source.id, opened.source.id)
        assertEquals(analysis.id, opened.analysis.id)
        assertEquals(card.id, opened.card.id)
        assertEquals("Kimi", opened.card.appName)
    }

    @Test fun changingAnySavedCopyAfterListingBlocksTheExactCard() {
        val sourceDir = temporary.newFolder("sources")
        val analysisDir = temporary.newFolder("analyses")
        val cardDir = temporary.newFolder("cards")
        val sources = ResearchRecordStore(sourceDir)
        val analyses = ResearchAnalysisStore(analysisDir)
        val cards = ResearchConclusionStore(cardDir)
        val source = sources.save(sourceBytes())
        val analysis = analyses.save(source, analysisBytes(source))
        val card = cards.save(source, analysis, "人工观察；分析 SHA-256：${analysis.id}".toByteArray())
        val index = ResearchConclusionIndex(sources, analyses, cards)
        val target = index.list(source.id).single()
        assertEquals(card.id, target.cardId)

        val copies = listOf(
            java.io.File(sourceDir, "${source.id}.json"),
            java.io.File(java.io.File(analysisDir, source.id), "${analysis.id}.json"),
            java.io.File(java.io.File(java.io.File(cardDir, source.id), analysis.id), "${card.id}.md"),
        )
        copies.forEach { file ->
            val original = file.readBytes()
            file.writeText("changed after indexing")
            assertThrows(Exception::class.java) { index.open(target) }
            assertTrue(file.delete())
            assertThrows(Exception::class.java) { index.open(target) }
            file.writeBytes(original)
        }
        assertEquals(card.id, index.open(target).card.id)
    }

    @Test fun sampleAndMixedAppSourcesNeverBecomeObservedResultEntries() {
        val sources = ResearchRecordStore(temporary.newFolder("sources"))
        val analyses = ResearchAnalysisStore(temporary.newFolder("analyses"))
        val cards = ResearchConclusionStore(temporary.newFolder("cards"))
        val index = ResearchConclusionIndex(sources, analyses, cards)
        val sample = sources.save(sourceBytes(kind = "SAMPLE"))
        val sampleAnalysis = analyses.save(sample, analysisBytes(sample))
        val sampleCard = cards.save(sample, sampleAnalysis,
            "样例而非实测；分析 SHA-256：${sampleAnalysis.id}".toByteArray())

        assertTrue(index.list(sample.id).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            index.open(ResearchConclusionTarget(sample.id, sampleAnalysis.id, sampleCard.id))
        }

        val mixed = sources.save(sourceBytes(appName = "Kimi", secondAppName = "另一 App"))
        val mixedAnalysis = analyses.save(mixed, analysisBytes(mixed))
        assertTrue(index.list(mixed.id).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            index.open(ResearchConclusionTarget(mixed.id, mixedAnalysis.id, "0".repeat(64)))
        }
    }

    @Test fun everyAnalysisAndCardRemainsAnIndependentCandidate() {
        val sources = ResearchRecordStore(temporary.newFolder("sources"))
        val analyses = ResearchAnalysisStore(temporary.newFolder("analyses"))
        val cards = ResearchConclusionStore(temporary.newFolder("cards"))
        val source = sources.save(sourceBytes())
        val first = analyses.save(source, analysisBytes(source))
        val second = analyses.save(source, byteArrayOf('\n'.code.toByte()) + analysisBytes(source))
        val firstCard = cards.save(source, first, "第一卡；分析 SHA-256：${first.id}".toByteArray())
        val alternativeCard = cards.save(source, first, "另一卡；分析 SHA-256：${first.id}".toByteArray())
        val secondCard = cards.save(source, second, "第二分析；SHA-256：${second.id}".toByteArray())
        val index = ResearchConclusionIndex(sources, analyses, cards)

        val targets = index.list(source.id)
        assertEquals(setOf(
            ResearchConclusionTarget(source.id, first.id, firstCard.id),
            ResearchConclusionTarget(source.id, first.id, alternativeCard.id),
            ResearchConclusionTarget(source.id, second.id, secondCard.id),
        ), targets.toSet())
        assertEquals(3, targets.size)
        assertEquals(setOf("第一卡", "另一卡", "第二分析"), targets.map { index.open(it).card.text.substringBefore('；') }.toSet())
    }

    @Test fun rawUnknownAppNameRemainsAValidSingleAppCardAddress() {
        val sources = ResearchRecordStore(temporary.newFolder("sources"))
        val analyses = ResearchAnalysisStore(temporary.newFolder("analyses"))
        val cards = ResearchConclusionStore(temporary.newFolder("cards"))
        val source = sources.save(sourceBytes(appName = "UNKNOWN"))
        val analysis = analyses.save(source, analysisBytes(source))
        val card = cards.save(source, analysis, "人工观察；分析 SHA-256：${analysis.id}".toByteArray())
        val batch = sources.list().appResearchProfiles().single().batches.single()

        assertEquals(null, batch.filter.appName) // Display grouping normalizes UNKNOWN.
        assertEquals("UNKNOWN", batch.singleAppRawName) // Card authority preserves source bytes.
        assertTrue(batch.canShowObservedConclusionEntry)
        val opened = ResearchConclusionIndex(sources, analyses, cards)
            .open(ResearchConclusionTarget(source.id, analysis.id, card.id))
        assertEquals("UNKNOWN", opened.card.appName)
        assertTrue(opened.belongsTo(batch))
    }

    @Test fun cardEntryFollowsTheCurrentSourceAndConditionScope() {
        val sources = ResearchRecordStore(temporary.newFolder("sources"))
        val analyses = ResearchAnalysisStore(temporary.newFolder("analyses"))
        val cards = ResearchConclusionStore(temporary.newFolder("cards"))
        val source = sources.save(sourceBytes())
        val analysis = analyses.save(source, analysisBytes(source))
        val card = cards.save(source, analysis, "人工观察；分析 SHA-256：${analysis.id}".toByteArray())
        val opened = ResearchConclusionIndex(sources, analyses, cards)
            .open(ResearchConclusionTarget(source.id, analysis.id, card.id))
        val entry = sources.list().single()
        fun batch(scope: ResearchRecordScopeFilter) = listOf(entry).appResearchProfiles(scope).single().batches.single()

        assertTrue(opened.belongsTo(batch(ResearchRecordScopeFilter())))
        assertTrue(opened.belongsTo(batch(ResearchRecordScopeFilter(condition = ResearchConditionFilter.Literal("W1")))))
        assertTrue(!opened.belongsTo(batch(ResearchRecordScopeFilter(sourceKind = ResearchSourceKindSelection.SAMPLE))))
        assertTrue(!opened.belongsTo(batch(ResearchRecordScopeFilter(condition = ResearchConditionFilter.Literal("C1")))))
        assertEquals(source.id, batch(ResearchRecordScopeFilter()).sourceId)
    }

    private fun sourceBytes(
        kind: String = "OBSERVED",
        appName: String = "Kimi",
        secondAppName: String? = null,
    ): ByteArray = buildJsonObject {
        put("record_kind", kind)
        put("records", buildJsonArray { add(buildJsonObject {
            put("record_kind", kind)
            put("method_id", "alignment-1")
            put("attempt_id", "test-attempt-1")
            put("condition", "W1")
            put("app", buildJsonObject { put("name", appName) })
        }); if (secondAppName != null) add(buildJsonObject {
            put("record_kind", kind)
            put("method_id", "alignment-1")
            put("attempt_id", "test-attempt-2")
            put("app", buildJsonObject { put("name", secondAppName) })
        }) })
    }.toString().toByteArray()

    private fun analysisBytes(source: ResearchDocument): ByteArray = buildJsonObject {
        put("source_sha256", source.id)
        put("record_kind", source.recordKind)
        put("input_revision", "alignment-1")
        put("counts", buildJsonObject {})
        put("groups", buildJsonArray {})
        put("records", buildJsonArray { source.records.forEach { row -> add(buildJsonObject {
            put("attempt_id", row.attemptId)
            put("input_record", row.raw)
        }) } })
    }.toString().toByteArray()
}
