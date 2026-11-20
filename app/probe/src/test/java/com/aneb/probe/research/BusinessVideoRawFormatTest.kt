package com.aneb.probe.research

import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BusinessVideoRawFormatTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun rawCollectorCanBeSavedReopenedAndExportedWithoutLegacyMetrics() {
        // Fabricated collector-shaped bytes only; no actual observation, account, or media.
        val bytes = rawCollectorBytes()
        val original = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val directory = temporary.newFolder()
        val saved = ResearchRecordStore(directory).save(bytes)
        val reopened = ResearchRecordStore(directory).open(saved.id)
        assertEquals(saved.id, reopened.id)
        assertEquals(original, reopened.root)
        assertFalse(reopened.isVideo)
        assertTrue(reopened.isBusinessVideo)
        assertEquals("business-video-raw-1", reopened.methodId)
        assertEquals("UNVERIFIED_RAW", reopened.recordKind)
        assertTrue(reopened.sourceLabel.contains("未核验"))
        assertTrue(reopened.sourceNotice.contains("不是旧 V0/VF"))
        assertEquals("TEST-RAW-ONLY-1", reopened.records.single().attemptId)
        assertEquals("CAPTURED_AWAITING_REVIEW", reopened.records.single().status)
        assertFalse(reopened.records.single().statusLabel.contains("播放成功"))
        assertEquals(JsonNull, reopened.root["firstObservedPlaybackWaitMs"])
        assertEquals(JsonNull, reopened.root["playerFirstFrameMs"])
        assertFalse(reopened.root.containsKey("V0"))
        assertFalse(reopened.root.containsKey("VF"))
        assertArrayEquals(bytes, ByteArrayOutputStream().also {
            ResearchRecordStore(directory).export(saved.id, it)
        }.toByteArray())
        assertEquals(saved.id, ResearchRecordStore(directory).save(bytes).id)
        assertEquals(1, ResearchRecordStore(directory).list().size)
    }

    @Test fun rawCollectorRejectsMetricsAndMalformedFrameStructureBeforeSaving() {
        val bytes = rawCollectorBytes()
        val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val metricClaim = JsonObject(root.toMutableMap().apply {
            put("firstObservedPlaybackWaitMs", JsonPrimitive(1.0))
            put("playerFirstFrameMs", JsonPrimitive(1.0))
            put("stallStatus", JsonPrimitive("computed"))
        })
        val frame = root["frames"]!!.jsonArray.single().jsonObject
        val malformedFrame = JsonObject(frame.toMutableMap().apply {
            put("sequence", JsonPrimitive(2))
            put("returnedNs", JsonPrimitive(100))
            put("relativePath", JsonPrimitive("../outside.png"))
            put("sha256", JsonPrimitive("not-a-sha256"))
            put("bytes", JsonPrimitive(0))
        })
        val badReference = JsonObject(root.toMutableMap().apply {
            put("frames", JsonArray(listOf(malformedFrame)))
        })
        val store = ResearchRecordStore(temporary.newFolder())
        val saved = store.save(bytes)
        listOf(metricClaim, badReference).forEach { input ->
            val rejected = input.toString().toByteArray(Charsets.UTF_8)
            val snapshot = rejected.copyOf()
            assertThrows(ResearchImportException::class.java) { store.save(rejected) }
            assertArrayEquals(snapshot, rejected)
            assertEquals(listOf(saved.id), store.list().map { it.id })
        }
        assertArrayEquals(bytes, ByteArrayOutputStream().also { store.export(saved.id, it) }.toByteArray())
    }

    private fun rawCollectorBytes(): ByteArray = """{
      "runId":"TEST-RAW-ONLY-1",
      "profileSha256":"${"a".repeat(64)}",
      "conditionId":"TEST-NOT-A-NETWORK-MEASUREMENT",
      "clockId":"elapsedRealtimeNanos:TEST-ONLY-BOOT",
      "state":"CAPTURED_AWAITING_REVIEW",
      "category":"OBSERVATION_CAPTURED",
      "reason":null,
      "swipeCalls":"1",
      "ownedCleanup":true,
      "events":[{"name":"preparation_started","atNs":100},{"name":"swipe_returned","atNs":400}],
      "frames":[{"sequence":0,"requestedNs":200,"returnedNs":250,"writtenNs":300,
        "relativePath":"frames/frame-0000.png","sha256":"${"b".repeat(64)}","bytes":123}],
      "firstObservedPlaybackWaitMs":null,
      "playerFirstFrameMs":null,
      "stallStatus":"not_computed",
      "format":"business-video-raw-1"
    }
    """.toByteArray(Charsets.UTF_8)
}
