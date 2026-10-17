package com.aneb.probe.research

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real public stores and disk. Synthetic engineering originals, not a real App observation. */
class EngineeringObservationReviewFlowTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun confirmedReviewReopensAndReturnsTheExactCorrespondingOriginal() {
        val root = temporary.newFolder()
        val originalBytes = "SYNTHETIC visible fact; not a real observation".toByteArray()
        val files = fixture(originalBytes)
        val originals = EngineeringObservationStore(File(root, "originals"))
        val reader = EngineeringObservationReaderController(originals)
        reader.preview(EngineeringObservationSource { ByteArrayInputStream(files.getValue(it)) })
        val pair = checkNotNull(reader.confirmSave().document)
        val selection = EngineeringReviewSelection("SYNTHETIC-W1", "SYNTHETIC-W1/originals/view.xml")
        val input = EngineeringReviewInput("Synthetic App", "test-only", "visible condition only",
            "A synthetic fixture is displayed", "No measurement, delivery or network inference", listOf(selection))
        val reviews = EngineeringObservationReviewStore(File(root, "reviews"), originals)
        val saved = reviews.save(pair.id, input, confirmed = true)
        val fresh = EngineeringObservationReviewStore(File(root, "reviews"),
            EngineeringObservationStore(File(root, "originals")))
        val reopened = fresh.open(pair.id, saved.id)
        assertEquals(input, reopened.input)
        assertEquals(pair.id, reopened.pairId)
        assertArrayEquals(originalBytes, fresh.originalFile(reopened, selection).readBytes())
        assertArrayEquals(files.getValue("PAIR_RESULT.json"), pair.originalPairBytes())
    }

    @Test fun draftSurvivesRecreationAndFailedSaveWithoutReplacingAnEarlierReview() {
        val root = temporary.newFolder()
        val files = fixture("SYNTHETIC only".toByteArray())
        val originals = EngineeringObservationStore(File(root, "originals"))
        val reader = EngineeringObservationReaderController(originals)
        reader.preview(EngineeringObservationSource { ByteArrayInputStream(files.getValue(it)) })
        val pairId = checkNotNull(reader.confirmSave().document).id
        val reviews = EngineeringObservationReviewStore(File(root, "reviews"), originals)
        val first = EngineeringReviewInput(facts = "Visible synthetic fact", limitations = "Everything else unknown",
            evidence = listOf(EngineeringReviewSelection("SYNTHETIC-W1", "SYNTHETIC-W1/originals/view.xml")))
        val controller = EngineeringObservationReviewController(originals, reviews)
        controller.openPair(pairId)
        controller.edit(first)
        val saved = checkNotNull(controller.confirmSave(true).review)
        val changed = first.copy(facts = "Unsaved second version")
        controller.edit(changed)
        assertFalse(controller.retainDraft().failed)
        val fresh = EngineeringObservationReviewController(originals,
            EngineeringObservationReviewStore(File(root, "reviews"), originals))
        assertEquals(changed, fresh.openPair(pairId).input)
        assertEquals(listOf(saved.id), fresh.state.entries.map { it.id })
        fresh.edit(changed.copy(facts = ""))
        val rejected = fresh.confirmSave(true)
        assertTrue(rejected.failed)
        assertEquals("", rejected.input.facts)
        assertNull("A previous success must not be displayed as this failed save", rejected.review)
        assertEquals(first, reviews.open(pairId, saved.id).input)
    }

    @Test fun exportRequiresPrivacyConfirmationAndContainsOnlyReviewAndBoundMetadata() {
        val root = temporary.newFolder()
        val secretOriginal = "PRIVATE SYNTHETIC ORIGINAL DO NOT EXPORT".toByteArray()
        val files = fixture(secretOriginal)
        val originals = EngineeringObservationStore(File(root, "originals"))
        val reader = EngineeringObservationReaderController(originals)
        reader.preview(EngineeringObservationSource { ByteArrayInputStream(files.getValue(it)) })
        val pairId = checkNotNull(reader.confirmSave().document).id
        val reviews = EngineeringObservationReviewStore(File(root, "reviews"), originals)
        val input = EngineeringReviewInput(facts = "Visible synthetic fact", limitations = "Delivery and network unknown",
            evidence = listOf(EngineeringReviewSelection("SYNTHETIC-W1", "SYNTHETIC-W1/originals/view.xml")))
        val review = reviews.save(pairId, input, true)
        assertThrows(IllegalArgumentException::class.java) { reviews.exportBytes(pairId, review.id, false) }
        val exported = reviews.exportBytes(pairId, review.id, true)
        val text = exported.toString(Charsets.UTF_8)
        assertFalse(text.contains(secretOriginal.toString(Charsets.UTF_8)))
        val json = Json.parseToJsonElement(text).jsonObject
        assertEquals(JsonPrimitive(pairId), json["pair_sha256"])
        assertEquals(JsonNull, json["metrics"])
        assertEquals(JsonPrimitive(0), json["formal_new_slots"])
        val rows = json.getValue("attempts").jsonArray
        assertEquals(JsonPrimitive("UNKNOWN"), rows.first().jsonObject["send_calls"])
        assertEquals(JsonPrimitive("NOT_RUN"), rows.last().jsonObject["state"])
        assertEquals(JsonPrimitive(sha(secretOriginal)), json.getValue("evidence").jsonArray.single().jsonObject["sha256"])
        assertEquals(input, reviews.open(pairId, review.id).input)
    }

    @Test fun aBrokenOriginalCannotReopenAReviewOrLeaveAStaleSuccessfulAssociation() {
        val root = temporary.newFolder()
        val files = fixture("SYNTHETIC original".toByteArray())
        val originals = EngineeringObservationStore(File(root, "originals"))
        val reader = EngineeringObservationReaderController(originals)
        reader.preview(EngineeringObservationSource { ByteArrayInputStream(files.getValue(it)) })
        val pairId = checkNotNull(reader.confirmSave().document).id
        val reviews = EngineeringObservationReviewStore(File(root, "reviews"), originals)
        val selected = EngineeringReviewSelection("SYNTHETIC-W1", "SYNTHETIC-W1/originals/view.xml")
        val input = EngineeringReviewInput(facts = "Visible synthetic fact", limitations = "Everything else UNKNOWN", evidence = listOf(selected))
        val saved = reviews.save(pairId, input, true)
        val controller = EngineeringObservationReviewController(originals, reviews)
        controller.openPair(pairId)
        assertEquals(saved.id, controller.openReview(saved.id).review!!.id)
        val original = reader.originalFile(selected.path)
        original.writeBytes("SYNTHETIC damage".toByteArray())
        val rejected = controller.openReview(saved.id)
        assertTrue(rejected.failed)
        assertNull(rejected.review)
        assertTrue("A read failure with no unretained input must still allow navigation", controller.retainForNavigation())
        assertThrows(IllegalArgumentException::class.java) { reviews.exportBytes(pairId, saved.id, true) }
        assertArrayEquals("SYNTHETIC damage".toByteArray(), original.readBytes())
    }

    @Test fun previewWrongRunAndUnavailableStorageCannotSaveOrEraseInput() {
        val root = temporary.newFolder()
        val files = fixture("SYNTHETIC original".toByteArray())
        val originals = EngineeringObservationStore(File(root, "originals"))
        val source = EngineeringObservationSource { ByteArrayInputStream(files.getValue(it)) }
        val reader = EngineeringObservationReaderController(originals)
        val preview = checkNotNull(reader.preview(source).document)
        val input = EngineeringReviewInput(facts = "Visible synthetic fact", limitations = "Other facts unknown",
            evidence = listOf(EngineeringReviewSelection("SYNTHETIC-W1", "SYNTHETIC-W1/originals/view.xml")))
        val reviews = EngineeringObservationReviewStore(File(root, "reviews"), originals)
        assertThrows(IllegalArgumentException::class.java) { reviews.save(preview.id, input, true) }
        assertFalse(File(root, "reviews").exists())
        reader.confirmSave()
        assertThrows(IllegalArgumentException::class.java) { reviews.save(preview.id,
            input.copy(evidence = listOf(EngineeringReviewSelection("SYNTHETIC-C1", input.evidence.single().path))), true) }
        assertThrows(IllegalArgumentException::class.java) { reviews.save(preview.id, input, false) }
        val blocked = File(root, "blocked-reviews").apply { writeText("SYNTHETIC unavailable storage") }
        val controller = EngineeringObservationReviewController(originals, EngineeringObservationReviewStore(blocked, originals))
        controller.openPair(preview.id)
        controller.edit(input)
        assertTrue(controller.retainDraft().failed)
        assertEquals(input, controller.state.input)
        assertFalse("Do not silently leave when draft storage failed", controller.retainForNavigation())
        assertTrue(controller.confirmSave(true).failed)
        assertNull(controller.state.review)
        assertEquals(input, controller.state.input)
        assertEquals("SYNTHETIC unavailable storage", blocked.readText())
        assertArrayEquals(files.getValue("PAIR_RESULT.json"), originals.open(preview.id).originalPairBytes())
        assertTrue(blocked.delete()) // This test's own synthetic failure file only.
        assertFalse(controller.confirmSave(true).failed)
        assertNotNull(controller.state.review)
    }

    @Test fun changedReviewIsANewVersionAndExplicitDraftDiscardKeepsSavedVersions() {
        val root = temporary.newFolder()
        val files = fixture("SYNTHETIC original".toByteArray())
        val originals = EngineeringObservationStore(File(root, "originals"))
        val reader = EngineeringObservationReaderController(originals)
        reader.preview(EngineeringObservationSource { ByteArrayInputStream(files.getValue(it)) })
        val pairId = checkNotNull(reader.confirmSave().document).id
        val reviews = EngineeringObservationReviewStore(File(root, "reviews"), originals)
        val controller = EngineeringObservationReviewController(originals, reviews)
        controller.openPair(pairId)
        val first = EngineeringReviewInput(facts = "First visible statement", limitations = "Everything else UNKNOWN",
            evidence = listOf(EngineeringReviewSelection("SYNTHETIC-W1", "SYNTHETIC-W1/originals/view.xml")))
        controller.edit(first)
        val v1 = checkNotNull(controller.confirmSave(true).review)
        controller.edit(first.copy(facts = "Second independently saved statement"))
        val v2 = checkNotNull(controller.confirmSave(true).review)
        assertNotEquals(v1.id, v2.id)
        assertEquals(first, reviews.open(pairId, v1.id).input)
        controller.edit(first.copy(facts = "Discard only this unsaved text"))
        controller.retainDraft()
        assertFalse(controller.discardDraft().failed)
        val fresh = EngineeringObservationReviewController(originals, reviews)
        val reopened = fresh.openPair(pairId)
        assertFalse(reopened.editing)
        assertEquals(EngineeringReviewInput(), reopened.input)
        assertEquals(setOf(v1.id, v2.id), reopened.entries.map { it.id }.toSet())
        fresh.openReview(v2.id)
        assertNull(fresh.exportBytes(false))
        assertTrue(fresh.state.failed)
        assertEquals(v2.id, fresh.state.review!!.id)
        assertNotNull(fresh.exportBytes(true))
        assertEquals(v2.id, fresh.exportOutcome(false).review!!.id)
        assertTrue(fresh.state.failed)
        assertEquals(v2.id, fresh.exportOutcome(null).review!!.id)
        assertFalse(fresh.state.notice.contains("已写入"))
        assertTrue(fresh.retainForNavigation())
    }

    @Test fun aPreviouslyOpenedPairKeepsUnsubmittedDraftWithoutReopeningUnavailableOriginals() {
        val root = temporary.newFolder()
        val originalBytes = "SYNTHETIC original".toByteArray()
        val files = fixture(originalBytes)
        val originals = EngineeringObservationStore(File(root, "originals"))
        val reader = EngineeringObservationReaderController(originals)
        reader.preview(EngineeringObservationSource { ByteArrayInputStream(files.getValue(it)) })
        val pairId = checkNotNull(reader.confirmSave().document).id
        val reviews = EngineeringObservationReviewStore(File(root, "reviews"), originals)
        val controller = EngineeringObservationReviewController(originals, reviews)
        controller.openPair(pairId) // The manifest and all originals have actually passed validation.
        val selection = EngineeringReviewSelection("SYNTHETIC-W1", "SYNTHETIC-W1/originals/view.xml")
        val input = EngineeringReviewInput(facts = "Unsubmitted synthetic statement", limitations = "All other facts UNKNOWN", evidence = listOf(selection))
        controller.edit(input)
        val originalFile = reader.originalFile(selection.path)
        assertTrue(originalFile.delete()) // Own synthetic external file boundary, not an internal mock.
        assertTrue("Retaining an unsubmitted draft must not reopen the full original batch", controller.retainForNavigation())
        assertTrue("A draft does not bypass strict submit validation", controller.confirmSave(true).failed)
        assertNull(controller.state.review)
        originalFile.writeBytes(originalBytes)
        val fresh = EngineeringObservationReviewController(originals, reviews)
        assertEquals(input, fresh.openPair(pairId).input)
        assertTrue(fresh.state.editing)
        assertTrue(fresh.state.entries.isEmpty())
    }

    private fun fixture(bytes: ByteArray): Map<String, ByteArray> {
        val path = "SYNTHETIC-W1/originals/view.xml"
        val pair = buildJsonObject {
            put("state", "PAIR_STOPPED"); put("claim_scope", "private_engineering_observation")
            put("formal_new_slots", 0); put("rpi", "not_computed")
            putJsonArray("attempts") {
                add(buildJsonObject {
                    put("run_id", "SYNTHETIC-W1"); put("network_condition", "W1")
                    put("state", "INVOKED_OUTCOME_UNKNOWN"); put("send_calls", "UNKNOWN"); put("owned_cleanup", false)
                    putJsonArray("artifacts") { add(buildJsonObject {
                        put("path", path); put("bytes", bytes.size); put("sha256", sha(bytes))
                    }) }
                })
                add(buildJsonObject {
                    put("run_id", "SYNTHETIC-C1"); put("network_condition", "C1")
                    put("state", "NOT_RUN"); put("send_calls", 0); put("owned_cleanup", true)
                    put("artifacts", JsonArray(emptyList()))
                })
            }
        }.toString().toByteArray()
        return mapOf("PAIR_RESULT.json" to pair, path to bytes)
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
