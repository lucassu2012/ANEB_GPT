package com.aneb.probe.research

import java.security.MessageDigest
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrivateSdkObservationStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun savedPrivateObservationReopensOriginalBytesFromANewStore() {
        val bytes = privateSdkTestInput()
        val before = bytes.copyOf()
        val directory = temporary.newFolder()
        val saved = PrivateSdkObservationStore(directory).save(bytes)
        val reopened = PrivateSdkObservationStore(directory).open(saved.id)

        val expectedHash = MessageDigest.getInstance("SHA-256").digest(before)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(expectedHash, saved.id)
        assertEquals(saved.id, reopened.id)
        assertArrayEquals(before, bytes)
        assertArrayEquals(before, reopened.originalBytes())
    }

    @Test fun unrelatedJsonIsRejectedWithoutCreatingAPrivateCopy() {
        val directory = temporary.newFolder()
        assertThrows(IllegalArgumentException::class.java) {
            PrivateSdkObservationStore(directory).save("{\"records\":[]}".toByteArray())
        }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun previewDoesNotPersistAndOnlyConfirmedCopiesAppearInTheLocalList() {
        val directory = temporary.newFolder()
        val store = PrivateSdkObservationStore(directory)
        val preview = store.preview(privateSdkTestInput())
        assertTrue(store.list().isEmpty())
        assertTrue(directory.listFiles()!!.isEmpty())
        store.save(preview.originalBytes())
        val entry = PrivateSdkObservationStore(directory).list().single()
        assertEquals(preview.id, entry.id)
        assertNull(entry.error)
        assertEquals(preview.byteCount, entry.byteCount)
        assertArrayEquals(preview.originalBytes(), store.open(entry.id).originalBytes())
    }

    @Test fun anOversizedExistingCopyIsRejectedAndNeverOverwrittenBySavingAgain() {
        val directory = temporary.newFolder()
        val store = PrivateSdkObservationStore(directory)
        val bytes = privateSdkTestInput()
        val saved = store.save(bytes)
        val damaged = ByteArray(ResearchRecordStore.MAX_BYTES + 1) { 42 }
        val target = File(directory, "${saved.id}.json")
        target.writeBytes(damaged)
        val error = assertThrows(IllegalArgumentException::class.java) { store.save(bytes) }
        assertTrue(error.message!!.contains("1 MiB"))
        assertArrayEquals(damaged, target.readBytes())
        assertThrows(IllegalArgumentException::class.java) { store.open(saved.id) }
        assertNotNull(store.list().single().error)
    }
}

/** Fabricated private shape only: no real nonce, process, account, device, or source path. */
internal fun privateSdkTestInput(): ByteArray = """{
  "task": "TEST_ONLY",
  "classification": "PRIVATE_ENGINEERING_SDK_LITERAL_OBSERVATION_NOT_FORMAL_RESULT",
  "verdict": "SDK_LITERAL_READONLY_OBSERVED_WITH_LIMITS",
  "nonce": "TEST_ONLY_NONCE",
  "objects": {
    "deep_label": {"checked":false,"visible":true},
    "deep_parent": {"checked":false,"checkable":true},
    "search_label": {"checked":false,"visible":true},
    "search_parent": {"checked":true,"checkable":true}
  },
  "literal_fields": {"owner":"UNPROVEN","action_target":"UNPROVEN","actions":"0"},
  "sdk_clock": {"atomic":false,"ttl":"NOT_DEFINED"},
  "limits": {
    "independent_pixel_or_human_witness":"NOT_OBSERVED",
    "checked_means_mode_on_off":false,
    "backend_mode":"NOT_OBSERVED",
    "phone_import_export_reopen":"NOT_RUN"
  }
}
""".toByteArray(Charsets.UTF_8)
