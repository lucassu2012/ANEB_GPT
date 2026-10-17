package com.aneb.probe.research

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManualResearchMediaStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun savedOriginalCanReopenAfterExternalSourceIsRemovedWithoutChangingRawExport() {
        // TEST ONLY: fabricated bytes exercise local storage, not MP4 decoding or real research.
        val original = "TEST ONLY media storage bytes; not a decodable MP4 or observation".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(original)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val attemptId = "TEST-ONLY-MANUAL-R1"
        val raw = buildJsonObject {
            put("record_kind", "OBSERVED")
            put("test_fixture", "TEST ONLY; no actual observation or media")
            put("records", buildJsonArray {
                add(buildJsonObject {
                    put("record_kind", "OBSERVED")
                    put("method_id", "R1-visible-text-internal-v0.1")
                    put("attempt_id", attemptId)
                    put("evidence", buildJsonObject {
                        put("local_ref", "TEST-ONLY-UNAVAILABLE/desktop-original.mp4")
                        put("sha256", digest)
                        put("bytes", original.size)
                    })
                })
            })
        }.toString().toByteArray()
        val recordDirectory = temporary.newFolder("records")
        val mediaDirectory = temporary.newFolder("media")
        val records = ResearchRecordStore(recordDirectory)
        val source = records.save(raw)
        val external = File(temporary.newFolder("external"), "test-only.mp4").apply { writeBytes(original) }

        external.inputStream().use { ManualResearchMediaStore(mediaDirectory, records).save(source.id, attemptId, it) }
        assertTrue("The external selection is no longer available", external.delete())
        val reopenedRecords = ResearchRecordStore(recordDirectory)
        val reopened = ManualResearchMediaStore(mediaDirectory, reopenedRecords).open(source.id, attemptId)

        assertArrayEquals(original, reopened.readBytes())
        assertTrue(reopened.canonicalPath.startsWith(mediaDirectory.canonicalPath + File.separator))
        assertArrayEquals(raw, ByteArrayOutputStream().also { reopenedRecords.export(source.id, it) }.toByteArray())
    }

    @Test fun wrongSelectedContentCannotReopenAndCorrectReselectionPreservesRawExport() {
        // TEST ONLY: same-length fabricated files test SHA binding, not video decoding.
        val original = "TEST ONLY correctly selected media bytes; no real observation".toByteArray()
        val wrong = original.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val digest = MessageDigest.getInstance("SHA-256").digest(original)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val attemptId = "TEST-ONLY-RESELECT-R1"
        val raw = buildJsonObject {
            put("record_kind", "OBSERVED")
            put("test_fixture", "TEST ONLY; no actual observation or media")
            put("records", buildJsonArray {
                add(buildJsonObject {
                    put("record_kind", "OBSERVED")
                    put("method_id", "R1-visible-text-internal-v0.1")
                    put("attempt_id", attemptId)
                    put("evidence", buildJsonObject {
                        put("sha256", digest)
                        put("bytes", original.size)
                    })
                })
            })
        }.toString().toByteArray()
        val records = ResearchRecordStore(temporary.newFolder("records"))
        val source = records.save(raw)
        val mediaDirectory = temporary.newFolder("media")
        val store = ManualResearchMediaStore(mediaDirectory, records)
        val externalDirectory = temporary.newFolder("external")
        val wrongFile = File(externalDirectory, "wrong-test-only.mp4").apply { writeBytes(wrong) }
        val correctFile = File(externalDirectory, "correct-test-only.mp4").apply { writeBytes(original) }

        wrongFile.inputStream().use { selected ->
            assertThrows(IllegalArgumentException::class.java) { store.save(source.id, attemptId, selected) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManualResearchMediaStore(mediaDirectory, records).open(source.id, attemptId)
        }
        correctFile.inputStream().use { store.save(source.id, attemptId, it) }

        assertArrayEquals(original, ManualResearchMediaStore(mediaDirectory, records).open(source.id, attemptId).readBytes())
        assertArrayEquals(raw, ByteArrayOutputStream().also { records.export(source.id, it) }.toByteArray())
    }
}
