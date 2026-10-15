package com.aneb.probe.research

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Public controller + real private disk/PNG codec. Not SAF, Compose, or MP4 playback acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EngineeringObservationReaderFlowTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun syntheticOriginals(failed: Boolean = false): MutableMap<String, ByteArray> {
        val originals = linkedMapOf<String, ByteArray>()
        val pair = buildJsonObject {
            put("state", if (failed) "PAIR_STOPPED" else "PAIR_OBSERVATION_CLOSED")
            put("claim_scope", "private_engineering_observation")
            put("formal_new_slots", 0); put("rpi", "not_computed")
            put("network_settings_restored", true); put("original_reader_restored", true)
            putJsonArray("attempts") {
                for (condition in listOf("W1", "C1")) add(buildJsonObject {
                    val png = ByteArrayOutputStream().also { out ->
                        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(if (condition == "W1") android.graphics.Color.RED else android.graphics.Color.BLUE)
                        try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) }
                        finally { bitmap.recycle() }
                    }.toByteArray()
                    val clipName = if (condition == "W1") "/engineering-reader-sample.mp4" else "/engineering-reader-sample-c1.mp4"
                    val clip = checkNotNull(javaClass.getResourceAsStream(clipName)).use { it.readBytes() }
                    val run = "SYNTHETIC-READER-$condition"
                    put("run_id", run); put("network_condition", condition)
                    val send: JsonPrimitive = if (!failed) JsonPrimitive(1) else if (condition == "W1") JsonPrimitive("UNKNOWN") else JsonPrimitive(0)
                    put("state", if (!failed) "OBSERVATION_CLOSED" else if (condition == "W1") "INVOKED_OUTCOME_UNKNOWN" else "NOT_RUN")
                    put("send_calls", send); put("owned_cleanup", !failed)
                    val terminal = buildJsonObject {
                        put("run_id", run); put("network_condition", condition)
                        put("state", if (failed) "OBSERVATION_UNKNOWN" else "OBSERVATION_CLOSED")
                        put("send_calls", send); put("owned_cleanup", true)
                        put("claim_scope", "private_engineering_observation"); put("metrics", JsonNull)
                        put("formal_new_slots", 0); put("rpi", "not_computed")
                    }.toString().toByteArray()
                    val files = if (failed && condition == "C1") emptyMap() else mapOf("TERMINAL.json" to terminal, "SEND_INTENT.json" to "{\"test_only\":true}".toByteArray(),
                        "FINAL_MEDIA.json" to "{\"decoded_frames\":\"NOT_RUN\"}".toByteArray(),
                        "after-send.png" to png, "capture-end.png" to png,
                        "capture-end.xml" to "<hierarchy><node text=\"SYNTHETIC ONLY\"/></hierarchy>".toByteArray(),
                        "video.mp4" to clip)
                    putJsonArray("artifacts") {
                        files.forEach { (name, bytes) ->
                            val path = "$run/originals/$name"
                            originals[path] = bytes
                            add(buildJsonObject { put("path", path); put("bytes", bytes.size); put("sha256", sha(bytes)) })
                        }
                    }
                })
            }
        }.toString().toByteArray()
        originals["PAIR_RESULT.json"] = pair
        return originals
    }

    @Test fun confirmedEngineeringPairReopensEveryOriginalOfflineWithMetricsNA() {
        val originals = syntheticOriginals()
        val pair = originals.getValue("PAIR_RESULT.json")
        var sourceAvailable = true
        val source = EngineeringObservationSource { path ->
            check(sourceAvailable) { "External source no longer available" }
            ByteArrayInputStream(checkNotNull(originals[path]))
        }
        val directory = temporary.newFolder()
        val reader = EngineeringObservationReaderController(EngineeringObservationStore(directory))
        val preview = reader.preview(source)
        assertNotNull("The existing Reader must support previewing a raw engineering pair", preview.document)
        assertFalse(preview.saved)
        assertTrue("Preview must not save implicitly", directory.listFiles()!!.isEmpty())
        val saved = reader.confirmSave()
        assertTrue("Explicit confirmation saves the complete private copy", saved.saved)
        sourceAvailable = false
        val fresh = EngineeringObservationReaderController(EngineeringObservationStore(directory))
        val id = fresh.showList().entries.single().id
        val reopened = fresh.open(id)
        assertTrue(reopened.saved)
        assertArrayEquals(pair, reopened.document!!.originalPairBytes())
        assertEquals(listOf("W1", "C1"), reopened.document.attempts.map { it.condition })
        assertTrue(reopened.document.metricsNotice.contains("NA"))
        for ((path, bytes) in originals.filterKeys { it != "PAIR_RESULT.json" }) {
            val stored = fresh.originalFile(path)
            assertArrayEquals("Matching original retained: $path", bytes, stored.readBytes())
            if (path.endsWith(".png")) {
                val decoded = checkNotNull(BitmapFactory.decodeFile(stored.path))
                try { assertEquals(2, decoded.width); assertEquals(2, decoded.height) }
                finally { decoded.recycle() }
            }
        }
    }

    @Test fun aDamagedSelectedSourceCannotReuseAnOlderSavedSuccess() {
        val originals = syntheticOriginals()
        val source = EngineeringObservationSource { ByteArrayInputStream(originals.getValue(it)) }
        val directory = temporary.newFolder()
        val reader = EngineeringObservationReaderController(EngineeringObservationStore(directory))
        reader.preview(source)
        val id = checkNotNull(reader.confirmSave().document).id
        val videoPath = "SYNTHETIC-READER-C1/originals/video.mp4"
        val originalVideo = originals.getValue(videoPath)
        originals[videoPath] = originalVideo.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        reader.preview(source)
        val rejected = reader.confirmSave()
        assertFalse("A bad external original must not be hidden by an older valid copy", rejected.saved)
        assertNull(rejected.document)
        val fresh = EngineeringObservationReaderController(EngineeringObservationStore(directory))
        assertEquals(id, fresh.showList().entries.single().id)
        assertTrue(fresh.open(id).saved)
        assertArrayEquals(originalVideo, fresh.originalFile(videoPath).readBytes())
        assertEquals("Only the earlier complete entry remains, no staging leftovers", 1, directory.listFiles()!!.size)
    }

    @Test fun aFailedBatchKeepsUnknownSendAndHostVersusNativeDifferences() {
        val originals = syntheticOriginals(failed = true)
        val directory = temporary.newFolder()
        val reader = EngineeringObservationReaderController(EngineeringObservationStore(directory))
        val preview = reader.preview(EngineeringObservationSource { ByteArrayInputStream(originals.getValue(it)) })
        assertNotNull("Failed/UNKNOWN engineering materials must remain readable", preview.document)
        assertTrue(reader.confirmSave().saved)
        val fresh = EngineeringObservationReaderController(EngineeringObservationStore(directory))
        val reopened = fresh.open(fresh.showList().entries.single().id)
        assertTrue(reopened.saved)
        val document = checkNotNull(reopened.document)
        assertEquals("PAIR_STOPPED", document.state)
        assertEquals(listOf("INVOKED_OUTCOME_UNKNOWN", "NOT_RUN"), document.attempts.map { it.state })
        assertTrue(document.attempts.first().sendCalls.toString().contains("UNKNOWN"))
        assertFalse(document.attempts.first().ownedCleanup)
        val terminalPath = "SYNTHETIC-READER-W1/originals/TERMINAL.json"
        assertArrayEquals(originals.getValue(terminalPath), fresh.originalFile(terminalPath).readBytes())
        val native = Json.parseToJsonElement(fresh.originalFile(terminalPath).readText()).jsonObject
        assertEquals(JsonPrimitive(true), native["owned_cleanup"])
        assertEquals(JsonPrimitive("OBSERVATION_UNKNOWN"), native["state"])
        assertTrue(document.metricsNotice.contains("NA"))
    }

    @Test fun cancelledUnsafeOrDamagedSelectionsNeverDisplayAnOlderRecord() {
        val originals = syntheticOriginals()
        val source = EngineeringObservationSource { ByteArrayInputStream(originals.getValue(it)) }
        val directory = temporary.newFolder()
        val reader = EngineeringObservationReaderController(EngineeringObservationStore(directory))
        reader.preview(source)
        val id = checkNotNull(reader.confirmSave().document).id
        reader.preview(source)
        val cancelled = reader.preview(null)
        assertNull(cancelled.document); assertFalse(cancelled.saved)
        assertTrue(reader.open(id).saved)
        val screenshot = reader.originalFile("SYNTHETIC-READER-W1/originals/capture-end.png")
        val damage = "SYNTHETIC_ONLY_DAMAGED_LOCAL_COPY".toByteArray()
        screenshot.writeBytes(damage)
        val broken = reader.open(id)
        assertNull(broken.document); assertFalse(broken.saved)
        assertArrayEquals("No repair/overwrite of a damaged source", damage, screenshot.readBytes())

        val unsafe = originals.getValue("PAIR_RESULT.json").toString(Charsets.UTF_8)
            .replace("SYNTHETIC-READER-W1/originals/TERMINAL.json", "../outside.json").toByteArray()
        val requests = mutableListOf<String>()
        val rejected = reader.preview(EngineeringObservationSource { path ->
            requests += path
            check(path == "PAIR_RESULT.json") { "An unauthorized path must never be read" }
            ByteArrayInputStream(unsafe)
        })
        assertNull(rejected.document); assertFalse(rejected.saved)
        assertEquals(listOf("PAIR_RESULT.json"), requests)
        assertEquals(1, directory.listFiles()!!.size)
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
